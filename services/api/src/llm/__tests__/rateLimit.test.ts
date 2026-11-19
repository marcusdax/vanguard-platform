/**
 * rateLimit.test.ts — counters, budget enforcement, exponential cooldown.
 * Run: npx tsx --test src/llm/__tests__/rateLimit.test.ts
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import {
  canServe,
  getCooldownUntil,
  getProviderStats,
  getReliabilityScore,
  isCoolingDown,
  markFailure,
  markSuccess,
  record,
  resetRuntimeState,
  snapshot,
} from '../rateLimit';

const NOW = Date.UTC(2026, 10, 16, 12, 0, 0);

test.beforeEach(() => {
  resetRuntimeState();
});

test('enforces the per-minute request budget (RPM)', () => {
  const limits = { rpm: 2 };
  assert.deepEqual(canServe('groq', 'm', limits, 0, NOW), { allowed: true });
  record('groq', 'm', 10, NOW);
  assert.equal(canServe('groq', 'm', limits, 0, NOW).allowed, true);
  record('groq', 'm', 10, NOW);
  const denied = canServe('groq', 'm', limits, 0, NOW);
  assert.equal(denied.allowed, false);
  assert.equal(denied.reason, 'RPM');
});

test('enforces the per-day request budget (RPD) and rolls the window over', () => {
  const limits = { rpd: 1 };
  record('cerebras', 'm', 0, NOW);
  assert.equal(canServe('cerebras', 'm', limits, 0, NOW).reason, 'RPD');
  // Next UTC day is a fresh window.
  const tomorrow = NOW + 24 * 60 * 60 * 1000 + 1000;
  assert.equal(canServe('cerebras', 'm', limits, 0, tomorrow).allowed, true);
});

test('enforces token budgets (TPM/TPD) including the estimated request size', () => {
  const limits = { tpm: 100 };
  record('google', 'gemini', 60, NOW);
  assert.equal(canServe('google', 'gemini', limits, 30, NOW).allowed, true);
  record('google', 'gemini', 30, NOW);
  const denied = canServe('google', 'gemini', limits, 50, NOW);
  assert.equal(denied.allowed, false);
  assert.equal(denied.reason, 'TPM');

  resetRuntimeState();
  const dayLimits = { tpd: 200 };
  record('google', 'gemini', 190, NOW);
  assert.equal(canServe('google', 'gemini', dayLimits, 20, NOW).reason, 'TPD');
});

test('cooldown backs off exponentially and clears on success', () => {
  const first = markFailure('mistral', { error: '429 rate limited', now: NOW });
  assert.equal(first, 30_000);
  assert.equal(isCoolingDown('mistral', NOW + 1), true);
  assert.equal(canServe('mistral', 'm', {}, 0, NOW + 1).reason, 'COOLDOWN');
  assert.equal(getCooldownUntil('mistral', NOW + 1), new Date(NOW + 30_000).toISOString());

  const second = markFailure('mistral', { error: '429 again', now: NOW + 1_000 });
  assert.equal(second, 60_000, 'second consecutive failure doubles backoff');
  const third = markFailure('mistral', { error: '429 again', now: NOW + 2_000 });
  assert.equal(third, 120_000);

  markSuccess('mistral', 250, 'm', NOW + 3_000);
  assert.equal(isCoolingDown('mistral', NOW + 3_001), false);
  assert.equal(getCooldownUntil('mistral', NOW + 3_001), null);
  assert.equal(getProviderStats('mistral').consecutiveFailures, 0);
});

test('cooldown expires after its window and canServe recovers', () => {
  markFailure('openrouter', { now: NOW });
  assert.equal(isCoolingDown('openrouter', NOW + 29_000), true);
  assert.equal(isCoolingDown('openrouter', NOW + 30_001), false);
  assert.equal(canServe('openrouter', 'm', {}, 0, NOW + 30_001).allowed, true);
});

test('a provider Retry-After hint longer than the backoff wins', () => {
  const cooldown = markFailure('nvidia', { retryAfterMs: 120_000, now: NOW });
  assert.equal(cooldown, 120_000);
});

test('reliability uses a Laplace prior and success/failure counts', () => {
  assert.equal(getReliabilityScore('unknown-provider'), 0.5);
  markSuccess('groq', 100, 'm', NOW);
  markSuccess('groq', 120, 'm', NOW);
  const reliable = getReliabilityScore('groq');
  assert.ok(reliable > 0.7, `expected reliable score, got ${reliable}`);
  markFailure('groq', { now: NOW + 1_000 });
  assert.ok(getReliabilityScore('groq') < reliable);
  assert.equal(getProviderStats('groq').ok, 2);
  assert.equal(getProviderStats('groq').fail, 1);
});

test('snapshot reports live counters, health and cooldowns', () => {
  record('groq', 'llama', 42, NOW);
  markFailure('mistral', { error: 'down', now: NOW });
  const snap = snapshot(NOW + 1_000);
  const counter = snap.counters.find((row) => row.providerId === 'groq');
  assert.ok(counter);
  assert.equal(counter?.model, 'llama');
  assert.equal(counter?.minuteRequests, 1);
  assert.equal(counter?.dayTokens, 42);
  assert.ok(snap.cooldowns.some((row) => row.providerId === 'mistral'));
  assert.ok(snap.health.some((row) => row.providerId === 'mistral' && row.health.status === 'error'));
});

test('limits per provider+model are isolated from other models', () => {
  const limits = { rpm: 1 };
  record('groq', 'model-a', 0, NOW);
  assert.equal(canServe('groq', 'model-a', limits, 0, NOW).reason, 'RPM');
  assert.equal(canServe('groq', 'model-b', limits, 0, NOW).allowed, true);
});
