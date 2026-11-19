/**
 * In-memory rate limiting + provider runtime health/cooldown state.
 *
 * Free-tier providers advertise tight per-minute / per-day limits; we track
 * counters per `providerId::model`, keep a process-local cooldown map with
 * exponential backoff, and expose a snapshot for /ai/status.
 *
 * This is intentionally process-local (no Redis) — a single API instance is the
 * deployment target, and losing counters on restart only makes us slightly more
 * optimistic, never less correct.
 */

export interface RateLimits {
  /** requests per minute */
  rpm?: number;
  /** requests per day */
  rpd?: number;
  /** tokens per minute */
  tpm?: number;
  /** tokens per day */
  tpd?: number;
}

export type RateLimitReason = 'COOLDOWN' | 'RPM' | 'RPD' | 'TPM' | 'TPD';

export interface CanServeResult {
  allowed: boolean;
  reason?: RateLimitReason;
  /** epoch ms when the cooldown expires (only for COOLDOWN) */
  cooldownUntil?: number;
}

export type RuntimeHealthStatus = 'ok' | 'error' | 'unknown' | 'unconfigured' | 'cooldown';

export interface RuntimeHealth {
  status: RuntimeHealthStatus;
  lastCheckAt?: string;
  latencyMs?: number;
  error?: string;
  model?: string;
}

export interface ProviderStats {
  ok: number;
  fail: number;
  lastLatencyMs?: number;
  consecutiveFailures: number;
}

interface CounterBucket {
  minuteKey: number;
  minuteRequests: number;
  minuteTokens: number;
  dayKey: string;
  dayRequests: number;
  dayTokens: number;
}

interface CooldownEntry {
  until: number;
  level: number;
}

interface SnapshotCounter {
  providerId: string;
  model: string;
  minuteRequests: number;
  minuteTokens: number;
  dayRequests: number;
  dayTokens: number;
}

export interface RuntimeSnapshot {
  generatedAt: string;
  counters: SnapshotCounter[];
  cooldowns: Array<{ providerId: string; until: string; level: number }>;
  health: Array<{ providerId: string; health: RuntimeHealth; stats: ProviderStats }>;
}

const COOLDOWN_BASE_MS = 30_000;
const COOLDOWN_MAX_MS = 15 * 60_000;
const DEFAULT_STATS: ProviderStats = { ok: 0, fail: 0, consecutiveFailures: 0 };

const counters = new Map<string, CounterBucket>();
const cooldowns = new Map<string, CooldownEntry>();
const health = new Map<string, RuntimeHealth>();
const stats = new Map<string, ProviderStats>();

function counterKey(providerId: string, model: string): string {
  return `${providerId}::${model}`;
}

function minuteKey(now: number): number {
  return Math.floor(now / 60_000);
}

function dayKey(now: number): string {
  return new Date(now).toISOString().slice(0, 10);
}

/** Get (or create) a counter bucket, rolling windows forward when stale. */
function bucketFor(providerId: string, model: string, now: number): CounterBucket {
  const key = counterKey(providerId, model);
  const mk = minuteKey(now);
  const dk = dayKey(now);
  let bucket = counters.get(key);
  if (!bucket) {
    bucket = { minuteKey: mk, minuteRequests: 0, minuteTokens: 0, dayKey: dk, dayRequests: 0, dayTokens: 0 };
    counters.set(key, bucket);
    return bucket;
  }
  if (bucket.minuteKey !== mk) {
    bucket.minuteKey = mk;
    bucket.minuteRequests = 0;
    bucket.minuteTokens = 0;
  }
  if (bucket.dayKey !== dk) {
    bucket.dayKey = dk;
    bucket.dayRequests = 0;
    bucket.dayTokens = 0;
  }
  return bucket;
}

function positive(value: number | undefined): number {
  return typeof value === 'number' && Number.isFinite(value) && value > 0 ? Math.floor(value) : 0;
}

export function isCoolingDown(providerId: string, now: number = Date.now()): boolean {
  const entry = cooldowns.get(providerId);
  if (!entry) return false;
  if (entry.until <= now) {
    cooldowns.delete(providerId);
    return false;
  }
  return true;
}

export function getCooldownUntil(providerId: string, now: number = Date.now()): string | null {
  const entry = cooldowns.get(providerId);
  if (!entry || entry.until <= now) {
    if (entry) cooldowns.delete(providerId);
    return null;
  }
  return new Date(entry.until).toISOString();
}

export function getCooldownLevel(providerId: string): number {
  return cooldowns.get(providerId)?.level ?? 0;
}

/**
 * Whether this provider+model can take one more request right now.
 * `estimatedTokens` is added to the token windows so a large prompt is not
 * admitted just under the wire.
 */
export function canServe(
  providerId: string,
  model: string,
  limits: RateLimits = {},
  estimatedTokens: number = 0,
  now: number = Date.now()
): CanServeResult {
  if (isCoolingDown(providerId, now)) {
    return { allowed: false, reason: 'COOLDOWN', cooldownUntil: cooldowns.get(providerId)!.until };
  }
  const bucket = bucketFor(providerId, model, now);
  const tokens = positive(estimatedTokens);
  const rpm = positive(limits.rpm);
  const rpd = positive(limits.rpd);
  const tpm = positive(limits.tpm);
  const tpd = positive(limits.tpd);

  if (rpm && bucket.minuteRequests + 1 > rpm) return { allowed: false, reason: 'RPM' };
  if (rpd && bucket.dayRequests + 1 > rpd) return { allowed: false, reason: 'RPD' };
  if (tpm && bucket.minuteTokens + tokens > tpm) return { allowed: false, reason: 'TPM' };
  if (tpd && bucket.dayTokens + tokens > tpd) return { allowed: false, reason: 'TPD' };
  return { allowed: true };
}

/** Record a successful request (and the tokens it consumed). */
export function record(
  providerId: string,
  model: string,
  tokens: number = 0,
  now: number = Date.now()
): void {
  const bucket = bucketFor(providerId, model, now);
  const tk = positive(tokens);
  bucket.minuteRequests += 1;
  bucket.dayRequests += 1;
  bucket.minuteTokens += tk;
  bucket.dayTokens += tk;
}

/** Mark a provider healthy again; clears cooldown backoff. */
export function markSuccess(
  providerId: string,
  latencyMs?: number,
  model?: string,
  now: number = Date.now()
): void {
  cooldowns.delete(providerId);
  health.set(providerId, {
    status: 'ok',
    lastCheckAt: new Date(now).toISOString(),
    ...(typeof latencyMs === 'number' ? { latencyMs: Math.round(latencyMs) } : {}),
    ...(model ? { model } : {}),
  });
  const current = stats.get(providerId) ?? { ...DEFAULT_STATS };
  current.ok += 1;
  current.consecutiveFailures = 0;
  if (typeof latencyMs === 'number') current.lastLatencyMs = Math.round(latencyMs);
  stats.set(providerId, current);
}

/**
 * Mark a provider unhealthy and start (or extend) its cooldown.
 * Backoff doubles per consecutive failure: 30s, 60s, 120s … capped at 15 min.
 * A provider `Retry-After` hint wins when longer than the computed backoff.
 */
export function markFailure(
  providerId: string,
  opts: { error?: string; model?: string; retryAfterMs?: number; now?: number } = {}
): number {
  const now = opts.now ?? Date.now();
  const current = stats.get(providerId) ?? { ...DEFAULT_STATS };
  current.fail += 1;
  current.consecutiveFailures += 1;
  stats.set(providerId, current);

  const level = current.consecutiveFailures;
  const exponential = Math.min(COOLDOWN_MAX_MS, COOLDOWN_BASE_MS * Math.pow(2, Math.max(0, level - 1)));
  const hinted = positive(opts.retryAfterMs);
  const cooldownMs = Math.min(COOLDOWN_MAX_MS, Math.max(exponential, hinted));
  const until = now + cooldownMs;
  cooldowns.set(providerId, { until, level });

  const message = (opts.error || 'Provider call failed').slice(0, 200);
  health.set(providerId, {
    status: 'error',
    lastCheckAt: new Date(now).toISOString(),
    error: message,
    ...(opts.model ? { model: opts.model } : {}),
  });
  return cooldownMs;
}

export function getRuntimeHealth(providerId: string): RuntimeHealth | undefined {
  return health.get(providerId);
}

export function getProviderStats(providerId: string): ProviderStats {
  const current = stats.get(providerId);
  return current ? { ...current } : { ...DEFAULT_STATS };
}

/**
 * Reliability in [0,1] with a Laplace prior so a provider we have never called
 * is not treated as broken: (ok + 1) / (ok + fail + 2).
 */
export function getReliabilityScore(providerId: string): number {
  const current = stats.get(providerId) ?? DEFAULT_STATS;
  return (current.ok + 1) / (current.ok + current.fail + 2);
}

/** Discovered/observed latency for strategy scoring (undefined when unknown). */
export function getObservedLatency(providerId: string): number | undefined {
  return stats.get(providerId)?.lastLatencyMs;
}

/** Full runtime state, useful for /ai/status and tests. */
export function snapshot(now: number = Date.now()): RuntimeSnapshot {
  const counterRows: SnapshotCounter[] = [];
  for (const [key, bucket] of counters) {
    const sep = key.indexOf('::');
    const providerId = key.slice(0, sep);
    const model = key.slice(sep + 2);
    const fresh = bucket.minuteKey === minuteKey(now) && bucket.dayKey === dayKey(now);
    if (!fresh && bucket.dayKey !== dayKey(now)) continue;
    counterRows.push({
      providerId,
      model,
      minuteRequests: bucket.minuteKey === minuteKey(now) ? bucket.minuteRequests : 0,
      minuteTokens: bucket.minuteKey === minuteKey(now) ? bucket.minuteTokens : 0,
      dayRequests: bucket.dayKey === dayKey(now) ? bucket.dayRequests : 0,
      dayTokens: bucket.dayKey === dayKey(now) ? bucket.dayTokens : 0,
    });
  }
  const cooldownRows: RuntimeSnapshot['cooldowns'] = [];
  for (const [providerId, entry] of cooldowns) {
    if (entry.until > now) {
      cooldownRows.push({ providerId, until: new Date(entry.until).toISOString(), level: entry.level });
    }
  }
  const healthRows: RuntimeSnapshot['health'] = [];
  for (const [providerId, runtime] of health) {
    healthRows.push({ providerId, health: { ...runtime }, stats: getProviderStats(providerId) });
  }
  return {
    generatedAt: new Date(now).toISOString(),
    counters: counterRows,
    cooldowns: cooldownRows,
    health: healthRows,
  };
}

/** Test hook: clear all in-memory counters, cooldowns and health. */
export function resetRuntimeState(): void {
  counters.clear();
  cooldowns.clear();
  health.clear();
  stats.clear();
}
