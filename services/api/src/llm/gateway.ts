/**
 * OpenAI-compatible chat-completions client used for every provider
 * (Groq, Google OpenAI-compat, Cerebras, Mistral, OpenRouter, NVIDIA,
 *  an external FreeLLMAPI gateway, or an admin's custom endpoint).
 *
 * - global `fetch` only (no SDK dependency)
 * - AbortController timeout from AI_REQUEST_TIMEOUT_MS (default 20s)
 * - `response_format: {type:'json_object'}` when JSON output is requested
 * - usage normalised from prompt/completion or input/output token shapes
 * - errors normalised to GatewayError with retry semantics; API keys are never
 *   logged, stored in error messages, or included in thrown text.
 */
import { normalizeUsage, type ChatMessage, type LLMUsage } from './types';

export interface GatewayCallParams {
  providerId: string;
  baseUrl: string;
  apiKey?: string;
  model: string;
  messages: ChatMessage[];
  json?: boolean;
  temperature?: number;
  maxTokens?: number;
  timeoutMs?: number;
  headers?: Record<string, string>;
}

export interface GatewayResponse {
  content: string;
  usage: LLMUsage;
  latencyMs: number;
  model: string;
}

export type GatewayErrorCode =
  | 'TIMEOUT'
  | 'NETWORK'
  | 'RATE_LIMIT'
  | 'UNAUTHORIZED'
  | 'NOT_FOUND'
  | 'BAD_REQUEST'
  | 'SERVER_ERROR'
  | 'BAD_RESPONSE'
  | 'ABORTED'
  | 'UNKNOWN';

export class GatewayError extends Error {
  readonly code: GatewayErrorCode;
  readonly status?: number;
  /** whether retrying the *same* provider could plausibly succeed */
  readonly retryable: boolean;
  readonly providerId: string;
  readonly model: string;
  /** provider hint from Retry-After, in ms */
  readonly retryAfterMs?: number;

  constructor(
    message: string,
    opts: {
      code: GatewayErrorCode;
      status?: number;
      retryable: boolean;
      providerId: string;
      model: string;
      retryAfterMs?: number;
    }
  ) {
    super(message);
    this.name = 'GatewayError';
    this.code = opts.code;
    this.status = opts.status;
    this.retryable = opts.retryable;
    this.providerId = opts.providerId;
    this.model = opts.model;
    this.retryAfterMs = opts.retryAfterMs;
  }
}

const DEFAULT_TIMEOUT_MS = 20_000;
const MAX_TIMEOUT_MS = 120_000;

/** Lazy env read because dotenv loads after module evaluation in ESM. */
export function getRequestTimeoutMs(): number {
  const raw = Number.parseInt((process.env.AI_REQUEST_TIMEOUT_MS || '').trim(), 10);
  if (!Number.isFinite(raw) || raw <= 0) return DEFAULT_TIMEOUT_MS;
  return Math.min(MAX_TIMEOUT_MS, Math.max(1_000, raw));
}

function joinUrl(baseUrl: string, path: string): string {
  return `${baseUrl.replace(/\/+$/, '')}/${path.replace(/^\/+/, '')}`;
}

function truncate(value: string, max = 300): string {
  const clean = value.replace(/\s+/g, ' ').trim();
  return clean.length > max ? `${clean.slice(0, max)}…` : clean;
}

/** Pull a human-readable provider error without ever touching credentials. */
function extractErrorMessage(status: number, bodyText: string, data: unknown): string {
  if (data && typeof data === 'object') {
    const obj = data as Record<string, unknown>;
    const err = obj.error;
    if (typeof err === 'string' && err.trim()) return truncate(err);
    if (err && typeof err === 'object') {
      const nested = (err as Record<string, unknown>).message;
      if (typeof nested === 'string' && nested.trim()) return truncate(nested);
    }
    if (typeof obj.message === 'string' && obj.message.trim()) return truncate(obj.message);
  }
  if (bodyText && bodyText.trim()) return truncate(bodyText);
  return `Provider returned HTTP ${status}`;
}

function parseRetryAfter(header: string | null): number | undefined {
  if (!header) return undefined;
  const seconds = Number.parseFloat(header);
  if (Number.isFinite(seconds) && seconds >= 0) return Math.round(seconds * 1000);
  const asDate = Date.parse(header);
  if (Number.isFinite(asDate)) {
    const delta = asDate - Date.now();
    return delta > 0 ? delta : undefined;
  }
  return undefined;
}

function classifyStatus(status: number): { code: GatewayErrorCode; retryable: boolean } {
  if (status === 429) return { code: 'RATE_LIMIT', retryable: true };
  if (status === 401 || status === 403) return { code: 'UNAUTHORIZED', retryable: false };
  if (status === 404) return { code: 'NOT_FOUND', retryable: false };
  if (status === 400 || status === 409 || status === 422) return { code: 'BAD_REQUEST', retryable: false };
  if (status === 408 || status === 425) return { code: 'TIMEOUT', retryable: true };
  if (status >= 500) return { code: 'SERVER_ERROR', retryable: true };
  return { code: 'UNKNOWN', retryable: false };
}

function readContent(data: unknown): string | null {
  if (!data || typeof data !== 'object') return null;
  const choices = (data as Record<string, unknown>).choices;
  if (!Array.isArray(choices) || choices.length === 0) return null;
  const first = choices[0] as Record<string, unknown>;
  const message = first?.message as Record<string, unknown> | undefined;
  const content = message?.content ?? first?.text;
  if (typeof content === 'string') return content;
  // Some OpenAI-compatible providers return content parts.
  if (Array.isArray(content)) {
    const parts = content
      .map((part) => {
        if (typeof part === 'string') return part;
        if (part && typeof part === 'object' && typeof (part as Record<string, unknown>).text === 'string') {
          return (part as Record<string, unknown>).text as string;
        }
        return '';
      })
      .join('');
    return parts.length > 0 ? parts : null;
  }
  return null;
}

export interface GatewayDeps {
  fetchImpl?: typeof fetch;
}

/**
 * Call `POST {baseUrl}/chat/completions`.
 * Throws GatewayError on failure (message is safe to surface — no secrets).
 */
export async function chatCompletion(
  params: GatewayCallParams,
  deps: GatewayDeps = {}
): Promise<GatewayResponse> {
  if (!params.baseUrl || !/^https?:\/\//i.test(params.baseUrl)) {
    throw new GatewayError(`Provider ${params.providerId} has no valid base URL`, {
      code: 'BAD_REQUEST',
      retryable: false,
      providerId: params.providerId,
      model: params.model,
    });
  }

  const timeoutMs = params.timeoutMs ?? getRequestTimeoutMs();
  const doFetch = deps.fetchImpl ?? fetch;
  const url = joinUrl(params.baseUrl, 'chat/completions');
  const startedAt = Date.now();

  /** One HTTP attempt; returns a GatewayError so callers can decide about retries. */
  const attempt = async (useJsonMode: boolean): Promise<GatewayResponse | GatewayError> => {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
      Accept: 'application/json',
      ...(params.headers ?? {}),
    };
    if (params.apiKey) headers.Authorization = `Bearer ${params.apiKey}`;

    const body: Record<string, unknown> = {
      model: params.model,
      messages: params.messages,
      stream: false,
    };
    if (typeof params.temperature === 'number') body.temperature = params.temperature;
    if (typeof params.maxTokens === 'number' && params.maxTokens > 0) body.max_tokens = params.maxTokens;
    if (useJsonMode) body.response_format = { type: 'json_object' };

    let response: Response;
    try {
      response = await doFetch(url, {
        method: 'POST',
        headers,
        body: JSON.stringify(body),
        signal: controller.signal,
      });
    } catch (error) {
      const err = error as Error & { name?: string };
      if (err?.name === 'AbortError') {
        return new GatewayError(`Provider ${params.providerId} timed out after ${timeoutMs}ms`, {
          code: 'TIMEOUT',
          retryable: true,
          providerId: params.providerId,
          model: params.model,
        });
      }
      return new GatewayError(`Network error talking to ${params.providerId}`, {
        code: 'NETWORK',
        retryable: true,
        providerId: params.providerId,
        model: params.model,
      });
    } finally {
      clearTimeout(timer);
    }

    const latencyMs = Date.now() - startedAt;
    const bodyText = await response.text().catch(() => '');
    let data: unknown = null;
    if (bodyText) {
      try {
        data = JSON.parse(bodyText);
      } catch {
        data = null;
      }
    }

    if (!response.ok) {
      const { code, retryable } = classifyStatus(response.status);
      const message = extractErrorMessage(response.status, bodyText, data);
      return new GatewayError(`${params.providerId}: ${message}`, {
        code,
        status: response.status,
        retryable,
        providerId: params.providerId,
        model: params.model,
        retryAfterMs: parseRetryAfter(response.headers?.get?.('retry-after') ?? null),
      });
    }

    const content = readContent(data);
    if (content === null || content === undefined) {
      return new GatewayError(`${params.providerId} returned an unexpected response shape`, {
        code: 'BAD_RESPONSE',
        status: response.status,
        // A malformed success body could be a transient provider issue → try next.
        retryable: true,
        providerId: params.providerId,
        model: params.model,
      });
    }

    const responseModel =
      data && typeof data === 'object' && typeof (data as Record<string, unknown>).model === 'string'
        ? ((data as Record<string, unknown>).model as string)
        : params.model;

    let usage = normalizeUsage(data && typeof data === 'object' ? (data as Record<string, unknown>).usage : null);
    if (usage.totalTokens === 0) {
      const promptChars = params.messages.reduce((sum, m) => sum + m.content.length, 0);
      const promptTokens = Math.max(1, Math.ceil(promptChars / 4));
      const completionTokens = Math.max(1, Math.ceil(content.length / 4));
      usage = { promptTokens, completionTokens, totalTokens: promptTokens + completionTokens };
    }

    return { content, usage, latencyMs, model: responseModel };
  };

  const first = await attempt(Boolean(params.json));
  if (!(first instanceof GatewayError)) return first;

  if (first.code === 'BAD_REQUEST' && params.json) {
    // Some OpenAI-compatible endpoints reject response_format; retry without it.
    const retry = await attempt(false);
    if (!(retry instanceof GatewayError)) return retry;
    throw retry;
  }
  throw first;
}
