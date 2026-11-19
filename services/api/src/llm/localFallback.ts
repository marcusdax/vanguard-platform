/**
 * Deterministic local engine — the product must work with zero provider keys.
 *
 * Only generic plumbing lives here: token estimation and a clearly-labelled
 * "offline engine" answer. Feature heuristics (storm brief, map plan, lead
 * explanation) live in `backend/src/services/ai/*` so they can stay deterministic
 * and unit-testable without touching the router.
 */
import { emptyUsage, type LLMAttempt, type LLMRequest, type LLMResult } from './types';

export const LOCAL_PROVIDER_ID = 'local';
export const LOCAL_MODEL = 'deterministic-v1';
export const LOCAL_ENGINE = 'deterministic-v1';

/** Cheap, deterministic token estimate (~4 chars/token) used for rate-limit windows. */
export function estimateTokens(text: string | null | undefined): number {
  if (!text) return 0;
  return Math.max(1, Math.ceil(text.length / 4));
}

export function estimateMessagesTokens(request: Pick<LLMRequest, 'messages' | 'maxTokens'>): number {
  const promptChars = request.messages.reduce((sum, m) => sum + (m.content?.length ?? 0) + 8, 0);
  const completion = request.maxTokens ?? 512;
  return estimateTokens('x'.repeat(promptChars)) + completion;
}

/**
 * Build the offline answer.
 * JSON-mode consumers get `{}` (valid JSON, no invented data) so their own
 * heuristic fallback path takes over; plain chat gets an honest explanation.
 */
export function buildLocalContent(request: Pick<LLMRequest, 'json' | 'purpose'>): string {
  if (request.json) return '{}';
  const purposeNote: Record<string, string> = {
    storm_brief: 'a deterministic storm brief from live NOAA data',
    map_copilot: 'a deterministic map plan parsed from your request',
    lead_insights: 'a deterministic storm-impact explanation',
    chat: 'a deterministic offline reply',
    provider_test: 'provider connectivity test (no provider available)',
  };
  const feature = purposeNote[request.purpose] ?? 'a deterministic offline reply';
  return [
    'Offline engine: no AI provider is currently available, so RoofPro is answering with its',
    `deterministic rules instead of a language model. You are seeing ${feature}.`,
    'Add a free provider key under Settings → AI Gateway to enable model-written summaries.',
  ].join(' ');
}

/** Build a complete LLMResult for the local engine. */
export function localResult(
  request: Pick<LLMRequest, 'messages' | 'json' | 'purpose'>,
  attempts: LLMAttempt[],
  opts: { latencyMs?: number; now?: () => number } = {}
): LLMResult {
  const content = buildLocalContent(request);
  const promptTokens = request.messages.reduce((sum, m) => sum + estimateTokens(m.content), 0);
  const completionTokens = estimateTokens(content);
  const latencyMs =
    typeof opts.latencyMs === 'number' ? opts.latencyMs : Math.max(0, Math.round(performance.now()));
  return {
    content,
    providerId: LOCAL_PROVIDER_ID,
    model: LOCAL_MODEL,
    usage: {
      promptTokens,
      completionTokens,
      totalTokens: promptTokens + completionTokens,
    },
    latencyMs,
    fallback: true,
    attempts,
  };
}

export { emptyUsage };
