/**
 * LLM router — priority chain, strategy scoring, cooldown, sticky sessions,
 * usage metering and deterministic local fallback.
 *
 * Contract §4.1 / §5.1. Key properties:
 *  - only calls providers that are enabled, configured, healthy and not cooling down
 *  - retries the next provider on 429 / 5xx / network / timeout / bad response
 *  - model-level errors (404/400) advance to the provider's next model first
 *  - `conversationId` sticks to the last successful provider+model for stickyMinutes
 *  - every outcome is metered into AIUsage (best effort; never fails the request)
 *  - when nothing can serve and local fallback is allowed, the deterministic
 *    engine answers with `fallback: true`
 */
import type { LLMAttempt, LLMRequest, LLMResult } from './types';
import { LLMUnavailableError } from './types';
import {
  isProviderReady,
  loadProviderState,
  persistProviderHealth,
  type ProviderHealth,
  type ProviderRuntime,
  type ProviderState,
} from './registry';
import {
  chatCompletion,
  GatewayError,
  getRequestTimeoutMs,
  type GatewayCallParams,
  type GatewayResponse,
} from './gateway';
import {
  canServe,
  getCooldownUntil,
  getObservedLatency,
  getReliabilityScore,
  getRuntimeHealth,
  isCoolingDown,
  markFailure,
  markSuccess,
  record,
} from './rateLimit';
import { estimateMessagesTokens, localResult } from './localFallback';

export type RoutingStrategy = 'balanced' | 'speed' | 'reliability' | 'priority';

export const ROUTING_STRATEGIES: RoutingStrategy[] = ['balanced', 'speed', 'reliability', 'priority'];
export const DEFAULT_STRATEGY: RoutingStrategy = 'balanced';
export const DEFAULT_STICKY_MINUTES = 30;

export interface RoutingSettings {
  strategy: RoutingStrategy;
  stickyMinutes: number;
  allowLocalFallback: boolean;
}

export interface RouterEnv {
  enabled: boolean;
  allowLocalFallback: boolean;
  defaultStrategy: RoutingStrategy;
  timeoutMs: number;
}

export interface ChainEntry {
  providerId: string;
  model: string;
}

export interface FallbackChain {
  strategy: RoutingStrategy;
  stickyMinutes: number;
  fallbackChain: ChainEntry[];
}

export interface RouteContext {
  companyId: string;
  userId?: string;
}

export interface MeterEntry {
  companyId: string;
  userId?: string;
  providerId: string | null;
  model: string | null;
  purpose: string;
  promptTokens: number;
  completionTokens: number;
  totalTokens: number;
  latencyMs: number;
  status: 'SUCCESS' | 'FALLBACK' | 'ERROR';
  fallback: boolean;
  error?: string;
}

export interface RouteDeps {
  loadProviders?: () => Promise<ProviderState>;
  loadRouting?: (companyId: string) => Promise<RoutingSettings>;
  callProvider?: (params: GatewayCallParams) => Promise<GatewayResponse>;
  meter?: (entry: MeterEntry) => Promise<void>;
  persistHealth?: (dbId: string, health: ProviderHealth) => Promise<void>;
  now?: () => number;
}

function readEnv(name: string): string {
  return (process.env[name] || '').trim();
}

/** Lazy env read (dotenv runs after ESM module evaluation). */
export function readRouterEnv(): RouterEnv {
  const strategyRaw = readEnv('AI_DEFAULT_STRATEGY') as RoutingStrategy;
  return {
    enabled: readEnv('AI_ENABLED') !== 'false',
    allowLocalFallback: readEnv('AI_ALLOW_LOCAL_FALLBACK') !== 'false',
    defaultStrategy: ROUTING_STRATEGIES.includes(strategyRaw) ? strategyRaw : DEFAULT_STRATEGY,
    timeoutMs: getRequestTimeoutMs(),
  };
}

function defaultRoutingSettings(env: RouterEnv): RoutingSettings {
  return {
    strategy: env.defaultStrategy,
    stickyMinutes: DEFAULT_STICKY_MINUTES,
    allowLocalFallback: env.allowLocalFallback,
  };
}

function parseRoutingJson(value: unknown): Partial<RoutingSettings> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return {};
  const obj = value as Record<string, unknown>;
  const out: Partial<RoutingSettings> = {};
  if (typeof obj.strategy === 'string' && ROUTING_STRATEGIES.includes(obj.strategy as RoutingStrategy)) {
    out.strategy = obj.strategy as RoutingStrategy;
  }
  if (typeof obj.stickyMinutes === 'number' && Number.isFinite(obj.stickyMinutes)) {
    out.stickyMinutes = Math.min(1_440, Math.max(1, Math.round(obj.stickyMinutes)));
  }
  if (typeof obj.allowLocalFallback === 'boolean') out.allowLocalFallback = obj.allowLocalFallback;
  return out;
}

/**
 * Effective routing settings: env defaults, overridden by a company AISetting
 * row, then by the global AISetting row. Never throws.
 */
export async function loadRoutingSettings(companyId: string): Promise<RoutingSettings> {
  const env = readRouterEnv();
  const settings = defaultRoutingSettings(env);
  try {
    const { prisma } = await import('../prisma');
    const companySetting = await prisma.aISetting.findUnique({ where: { companyId } });
    const globalSetting = companySetting ? null : await prisma.aISetting.findUnique({ where: { id: 'global' } });
    const row = companySetting ?? globalSetting;
    if (row) Object.assign(settings, parseRoutingJson(row.routing));
  } catch {
    // Settings are optional; env defaults are sufficient.
  }
  return settings;
}

/* ────────────────────────────── ordering ────────────────────────────── */

/** Strategy-based ordering. Lower priority number always wins ties. */
export function orderProviders(providers: ProviderRuntime[], strategy: RoutingStrategy): ProviderRuntime[] {
  const copy = [...providers];
  const latency = (provider: ProviderRuntime): number =>
    getObservedLatency(provider.providerId) ??
    provider.health.latencyMs ??
    600;
  const tieBreak = (a: ProviderRuntime, b: ProviderRuntime): number =>
    a.priority - b.priority || a.providerId.localeCompare(b.providerId);

  switch (strategy) {
    case 'priority':
      return copy.sort(tieBreak);
    case 'speed':
      return copy.sort((a, b) => latency(a) - latency(b) || tieBreak(a, b));
    case 'reliability':
      return copy.sort(
        (a, b) => getReliabilityScore(b.providerId) - getReliabilityScore(a.providerId) || tieBreak(a, b)
      );
    case 'balanced':
    default: {
      const score = (provider: ProviderRuntime): number => {
        const reliabilityPenalty = (1 - getReliabilityScore(provider.providerId)) * 60;
        const latencyPenalty = latency(provider) / 25;
        const failurePenalty = (getReliabilityScore(provider.providerId) < 0.5 ? 20 : 0);
        return provider.priority + reliabilityPenalty + latencyPenalty + failurePenalty;
      };
      return copy.sort((a, b) => score(a) - score(b) || tieBreak(a, b));
    }
  }
}

/** Provider + model order for the attempt loop (sticky model first). */
export function orderModels(provider: ProviderRuntime, stickyModel?: string): string[] {
  const models = provider.models.filter((model) => typeof model === 'string' && model.trim().length > 0);
  if (models.length === 0) return [];
  if (stickyModel && models.includes(stickyModel)) {
    return [stickyModel, ...models.filter((model) => model !== stickyModel)];
  }
  return models;
}

/* ───────────────────────────── sticky sessions ───────────────────────────── */

interface StickyEntry {
  providerId: string;
  model: string;
  expiresAt: number;
}

const stickySessions = new Map<string, StickyEntry>();

export function getStickySession(conversationId: string, now: number = Date.now()): StickyEntry | null {
  const entry = stickySessions.get(conversationId);
  if (!entry) return null;
  if (entry.expiresAt <= now) {
    stickySessions.delete(conversationId);
    return null;
  }
  return entry;
}

function setStickySession(conversationId: string, providerId: string, model: string, minutes: number, now: number): void {
  stickySessions.set(conversationId, {
    providerId,
    model,
    expiresAt: now + Math.max(1, minutes) * 60_000,
  });
  // Bound memory: drop expired entries when the map grows.
  if (stickySessions.size > 500) {
    for (const [key, entry] of stickySessions) {
      if (entry.expiresAt <= now) stickySessions.delete(key);
    }
  }
}

function clearStickySession(conversationId: string): void {
  stickySessions.delete(conversationId);
}

/** Test hook. */
export function resetStickySessions(): void {
  stickySessions.clear();
}

/* ───────────────────────────── chain building ───────────────────────────── */

/**
 * Ready providers in routing order.
 * `includeCoolingDown` is true for /ai/status (describe the chain) and false
 * for actual routing.
 */
export function selectRoutableProviders(
  providers: ProviderRuntime[],
  strategy: RoutingStrategy,
  opts: { includeCoolingDown?: boolean; now?: number } = {}
): ProviderRuntime[] {
  const now = opts.now ?? Date.now();
  const ready = providers.filter(
    (provider) => isProviderReady(provider) && (opts.includeCoolingDown || !isCoolingDown(provider.providerId, now))
  );
  return orderProviders(ready, strategy);
}

/** Build the ordered provider+model chain for status/UI. */
export function buildFallbackChain(
  providers: ProviderRuntime[],
  settings: RoutingSettings,
  opts: { includeCoolingDown?: boolean; now?: number } = {}
): ChainEntry[] {
  return selectRoutableProviders(providers, settings.strategy, opts)
    .map((provider) => ({ providerId: provider.providerId, model: provider.models[0] ?? '' }))
    .filter((entry) => entry.model.length > 0);
}

/** Public helper used by GET /ai/status and GET /ai/providers. */
export async function getRoutingChain(
  companyId: string,
  deps: RouteDeps = {}
): Promise<FallbackChain & { providers: ProviderRuntime[] }> {
  const env = readRouterEnv();
  const settings = deps.loadRouting ? await deps.loadRouting(companyId) : await loadRoutingSettings(companyId);
  const state = deps.loadProviders ? await deps.loadProviders() : await loadProviderState();
  return {
    strategy: settings.strategy || env.defaultStrategy,
    stickyMinutes: settings.stickyMinutes,
    fallbackChain: buildFallbackChain(state.providers, settings, { includeCoolingDown: true }),
    providers: state.providers,
  };
}

/* ───────────────────────────── metering / health ───────────────────────────── */

function safeError(error: unknown, fallback: string): string {
  const message = error instanceof Error ? error.message : typeof error === 'string' ? error : fallback;
  return message.replace(/\s+/g, ' ').slice(0, 300);
}

async function defaultMeter(entry: MeterEntry): Promise<void> {
  const { prisma } = await import('../prisma');
  await prisma.aIUsage.create({
    data: {
      companyId: entry.companyId,
      userId: entry.userId ?? null,
      providerId: entry.providerId,
      model: entry.model,
      purpose: entry.purpose,
      promptTokens: entry.promptTokens,
      completionTokens: entry.completionTokens,
      totalTokens: entry.totalTokens,
      latencyMs: Math.max(0, Math.round(entry.latencyMs)),
      status: entry.status,
      fallback: entry.fallback,
      error: entry.error ? entry.error.slice(0, 300) : null,
    },
  });
}

async function meterSafe(meter: (entry: MeterEntry) => Promise<void>, entry: MeterEntry): Promise<void> {
  try {
    await meter(entry);
  } catch (error) {
    console.warn('[ai/router] Could not record AI usage:', safeError(error, 'unknown error'));
  }
}

function toGatewayError(error: unknown, providerId: string, model: string): GatewayError {
  if (error instanceof GatewayError) return error;
  return new GatewayError(safeError(error, `${providerId} call failed`), {
    code: 'UNKNOWN',
    retryable: false,
    providerId,
    model,
  });
}

/* ───────────────────────────── main route ───────────────────────────── */

export async function routeLLM(request: LLMRequest, ctx: RouteContext, deps: RouteDeps = {}): Promise<LLMResult> {
  const now = deps.now ?? (() => Date.now());
  const env = readRouterEnv();
  const settings = deps.loadRouting
    ? await deps.loadRouting(ctx.companyId).catch(() => defaultRoutingSettings(env))
    : await loadRoutingSettings(ctx.companyId).catch(() => defaultRoutingSettings(env));
  const state = deps.loadProviders
    ? await deps.loadProviders().catch((): ProviderState => ({ providers: [], catalog: [], bootstrapKeys: {}, dbAvailable: false }))
    : await loadProviderState().catch((): ProviderState => ({ providers: [], catalog: [], bootstrapKeys: {}, dbAvailable: false }));
  const callProvider = deps.callProvider ?? ((params: GatewayCallParams) => chatCompletion(params));
  const meter = deps.meter ?? defaultMeter;
  const persistHealth = deps.persistHealth ?? persistProviderHealth;

  const attempts: LLMAttempt[] = [];
  const startedAt = now();
  const estimatedTokens = estimateMessagesTokens(request);
  const allowLocal = settings.allowLocalFallback && env.allowLocalFallback && !request.noFallback;

  const finishLocal = async (reason: string): Promise<LLMResult> => {
    if (!allowLocal) {
      await meterSafe(meter, {
        companyId: ctx.companyId,
        userId: ctx.userId,
        providerId: null,
        model: null,
        purpose: request.purpose,
        promptTokens: 0,
        completionTokens: 0,
        totalTokens: 0,
        latencyMs: now() - startedAt,
        status: 'ERROR',
        fallback: false,
        error: reason,
      });
      throw new LLMUnavailableError(
        'No AI provider is available and local fallback is disabled. Add a provider key in Settings → AI Gateway.',
        attempts
      );
    }
    const result = localResult(request, attempts, { latencyMs: Math.max(0, now() - startedAt) });
    await meterSafe(meter, {
      companyId: ctx.companyId,
      userId: ctx.userId,
      providerId: 'local',
      model: result.model,
      purpose: request.purpose,
      promptTokens: result.usage.promptTokens,
      completionTokens: result.usage.completionTokens,
      totalTokens: result.usage.totalTokens,
      latencyMs: result.latencyMs,
      status: 'FALLBACK',
      fallback: true,
    });
    return result;
  };

  if (!env.enabled) {
    attempts.push({ providerId: 'router', ok: false, error: 'ai_disabled' });
    return finishLocal('AI_ENABLED=false');
  }

  const sticky = request.conversationId ? getStickySession(request.conversationId, now()) : null;
  let chain = selectRoutableProviders(state.providers, settings.strategy, { now: now() });
  if (sticky) {
    const index = chain.findIndex((provider) => provider.providerId === sticky.providerId);
    if (index > 0) {
      const [stickyProvider] = chain.splice(index, 1);
      chain = [stickyProvider, ...chain];
    }
  }

  if (chain.length === 0) {
    attempts.push({ providerId: 'router', ok: false, error: 'no_provider_available' });
    return finishLocal('no configured provider');
  }

  for (const provider of chain) {
    const stickyModel = sticky && sticky.providerId === provider.providerId ? sticky.model : undefined;
    const models = orderModels(provider, stickyModel);
    if (models.length === 0) {
      attempts.push({ providerId: provider.providerId, ok: false, error: 'no_models_configured' });
      continue;
    }

    for (const model of models) {
      const serve = canServe(provider.providerId, model, provider.limits, estimatedTokens, now());
      if (!serve.allowed) {
        attempts.push({ providerId: provider.providerId, model, ok: false, error: `unavailable:${serve.reason}` });
        break; // provider-level limit → next provider
      }

      const attemptStartedAt = now();
      try {
        const response = await callProvider({
          providerId: provider.providerId,
          baseUrl: provider.baseUrl as string,
          apiKey: provider.apiKey,
          model,
          messages: request.messages,
          json: request.json,
          temperature: request.temperature,
          maxTokens: request.maxTokens,
          timeoutMs: env.timeoutMs,
          headers: provider.headers,
        });

        const latencyMs = Math.max(0, response.latencyMs ?? now() - attemptStartedAt);
        const resolvedModel = response.model || model;
        record(provider.providerId, model, response.usage.totalTokens, now());
        markSuccess(provider.providerId, latencyMs, resolvedModel, now());
        attempts.push({ providerId: provider.providerId, model: resolvedModel, ok: true, latencyMs });

        if (provider.dbId) {
          // Health persistence must never fail the request.
          await persistHealth(provider.dbId, {
            status: 'ok',
            lastCheckAt: new Date(now()).toISOString(),
            latencyMs,
            model: resolvedModel,
          }).catch(() => undefined);
        }
        if (request.conversationId) {
          setStickySession(request.conversationId, provider.providerId, resolvedModel, settings.stickyMinutes, now());
        }

        await meterSafe(meter, {
          companyId: ctx.companyId,
          userId: ctx.userId,
          providerId: provider.providerId,
          model: resolvedModel,
          purpose: request.purpose,
          promptTokens: response.usage.promptTokens,
          completionTokens: response.usage.completionTokens,
          totalTokens: response.usage.totalTokens,
          latencyMs,
          status: 'SUCCESS',
          fallback: false,
        });

        return {
          content: response.content,
          providerId: provider.providerId,
          model: resolvedModel,
          usage: response.usage,
          latencyMs,
          fallback: false,
          attempts,
        };
      } catch (error) {
        const gatewayError = toGatewayError(error, provider.providerId, model);
        const attemptLatency = Math.max(0, now() - attemptStartedAt);
        attempts.push({
          providerId: provider.providerId,
          model,
          ok: false,
          error: gatewayError.code,
          latencyMs: attemptLatency,
        });

        if (gatewayError.code === 'NOT_FOUND' || gatewayError.code === 'BAD_REQUEST') {
          // Model-specific problem — try this provider's next model.
          continue;
        }

        // Provider-level problem → exponential cooldown, then next provider.
        markFailure(provider.providerId, {
          error: gatewayError.message,
          model,
          retryAfterMs: gatewayError.retryAfterMs,
          now: now(),
        });
        if (provider.dbId) {
          await persistHealth(provider.dbId, {
            status: 'error',
            lastCheckAt: new Date(now()).toISOString(),
            error: gatewayError.message.slice(0, 300),
            model,
          }).catch(() => undefined);
        }
        if (request.conversationId) clearStickySession(request.conversationId);
        break;
      }
    }
  }

  return finishLocal('all providers failed');
}

/**
 * Effective routing view for /ai/status.
 * `cooldownUntil` for a provider comes from the in-memory cooldown map.
 */
export function describeProviderRuntime(provider: ProviderRuntime): {
  health: ProviderHealth;
  cooldownUntil: string | null;
} {
  const runtime = getRuntimeHealth(provider.providerId);
  const cooldownUntil = getCooldownUntil(provider.providerId);
  const health: ProviderHealth = runtime
    ? { ...provider.health, ...runtime, ...(cooldownUntil ? { status: 'cooldown' as const } : {}) }
    : { ...provider.health, ...(cooldownUntil ? { status: 'cooldown' as const } : {}) };
  return { health, cooldownUntil };
}
