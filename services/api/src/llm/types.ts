/**
 * LLM gateway shared types — contract §4.1 of docs/AI_STORM_ARCHITECTURE.md.
 *
 * Owner: backend-ai. Frontend/backend consumers import these shapes; keep them
 * byte-compatible with the architecture contract.
 */

export type ChatRole = 'system' | 'user' | 'assistant';

export interface ChatMessage {
  role: ChatRole;
  content: string;
}

export type LLMPurpose = 'storm_brief' | 'map_copilot' | 'lead_insights' | 'chat' | 'provider_test';

export interface LLMRequest {
  messages: ChatMessage[];
  /** Routing purpose — used for chain selection, metering and prompt profiles. */
  purpose: LLMPurpose;
  /** Ask the router for JSON output; providers that support response_format get it. */
  json?: boolean;
  temperature?: number;
  maxTokens?: number;
  /** Sticky-session key: same key tends to stick to the same model for 30 min. */
  conversationId?: string;
  /** Skip the local fallback (used by provider connectivity tests). */
  noFallback?: boolean;
}

export interface LLMUsage {
  promptTokens: number;
  completionTokens: number;
  totalTokens: number;
}

export interface LLMAttempt {
  providerId: string;
  model?: string;
  ok: boolean;
  error?: string;
  latencyMs?: number;
}

export interface LLMResult {
  content: string;
  /** 'groq' | 'google' | ... | 'local' */
  providerId: string;
  model: string;
  usage: LLMUsage;
  latencyMs: number;
  /** true when the local deterministic engine answered */
  fallback: boolean;
  attempts: LLMAttempt[];
}

/** Raised when every remote provider failed and local fallback is not allowed. */
export class LLMUnavailableError extends Error {
  readonly code = 'AI_PROVIDER_UNAVAILABLE';
  readonly attempts: LLMAttempt[];

  constructor(message: string, attempts: LLMAttempt[]) {
    super(message);
    this.name = 'LLMUnavailableError';
    this.attempts = attempts;
  }
}

export function emptyUsage(): LLMUsage {
  return { promptTokens: 0, completionTokens: 0, totalTokens: 0 };
}

export function normalizeUsage(raw: unknown): LLMUsage {
  if (!raw || typeof raw !== 'object') return emptyUsage();
  const u = raw as Record<string, unknown>;
  const num = (v: unknown): number => (typeof v === 'number' && Number.isFinite(v) && v > 0 ? Math.round(v) : 0);
  const promptTokens = num(u.prompt_tokens ?? u.input_tokens ?? u.promptTokens);
  const completionTokens = num(u.completion_tokens ?? u.output_tokens ?? u.completionTokens);
  const totalTokens = num(u.total_tokens ?? u.totalTokens) || promptTokens + completionTokens;
  return { promptTokens, completionTokens, totalTokens };
}
