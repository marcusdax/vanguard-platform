/**
 * router.test.ts — chain selection, failover, cooldown, sticky sessions, metering
 * and local fallback, using an injected fake provider (no network, no DB).
 * Run: npx tsx --test src/llm/__tests__/router.test.ts
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { LLMUnavailableError, type LLMRequest, type LLMResult } from '../types';
import {
  orderProviders,
  resetStickySessions,
  routeLLM,
  selectRoutableProviders,
  type MeterEntry,
  type RouteDeps,
} from '../router';
import type { ProviderRuntime, ProviderState } from '../registry';
import { GatewayError, type GatewayCallParams, type GatewayResponse } from '../gateway';
import { isCoolingDown, resetRuntimeState } from '../rateLimit';

const NOW = Date.UTC(2026, 10, 16, 12, 0, 0);

function makeProvider(overrides: Partial<ProviderRuntime> & { providerId: string }): ProviderRuntime {
  return {
    id: overrides.providerId,
    providerId: overrides.providerId,
    name: overrides.providerId,
    kind: 'remote',
    baseUrl: 'https://example.test/v1',
    apiKey: 'test-provider-key',
    apiKeyMasked: '••••-key',
    enabled: true,
    priority: 10,
    models: ['model-a'],
    limits: {},
    requiresApiKey: true,
    requiresBaseUrl: false,
    configured: true,
    keySource: 'db',
    health: { status: 'unknown' },
    ...overrides,
  };
}

function stateOf(providers: ProviderRuntime[]): ProviderState {
  return { providers, catalog: [], bootstrapKeys: {}, dbAvailable: true };
}

function requestOf(overrides: Partial<LLMRequest> = {}): LLMRequest {
  return {
    messages: [{ role: 'user', content: 'ping' }],
    purpose: 'chat',
    ...overrides,
  };
}

function responseOf(content = 'remote answer', model = 'model-a'): GatewayResponse {
  return { content, usage: { promptTokens: 3, completionTokens: 2, totalTokens: 5 }, latencyMs: 4, model };
}

interface Harness {
  deps: RouteDeps;
  calls: GatewayCallParams[];
  meters: MeterEntry[];
}

function harness(providers: ProviderRuntime[], call: (params: GatewayCallParams) => Promise<GatewayResponse>): Harness {
  const calls: GatewayCallParams[] = [];
  const meters: MeterEntry[] = [];
  const deps: RouteDeps = {
    loadProviders: async () => stateOf(providers),
    loadRouting: async () => ({ strategy: 'priority', stickyMinutes: 30, allowLocalFallback: true }),
    callProvider: async (params) => {
      calls.push(params);
      return call(params);
    },
    meter: async (entry) => {
      meters.push(entry);
    },
    persistHealth: async () => undefined,
    now: () => NOW,
  };
  return { deps, calls, meters };
}

test.beforeEach(() => {
  resetRuntimeState();
  resetStickySessions();
});

test('uses the highest-priority ready provider and meters the request', async () => {
  const providers = [makeProvider({ providerId: 'google', priority: 20 }), makeProvider({ providerId: 'groq', priority: 10 })];
  const { deps, calls, meters } = harness(providers, async () => responseOf('hello'));

  const result: LLMResult = await routeLLM(requestOf(), { companyId: 'company-1', userId: 'user-1' }, deps);

  assert.equal(result.providerId, 'groq');
  assert.equal(result.fallback, false);
  assert.equal(result.content, 'hello');
  assert.equal(calls.length, 1);
  assert.equal(calls[0].providerId, 'groq');
  assert.equal(result.attempts.filter((attempt) => attempt.ok).length, 1);
  assert.equal(meters.length, 1);
  assert.equal(meters[0].status, 'SUCCESS');
  assert.equal(meters[0].companyId, 'company-1');
  assert.equal(meters[0].totalTokens, 5);
});

test('fails over to the next provider on 429 and cools the first one down', async () => {
  const providers = [makeProvider({ providerId: 'groq', priority: 10 }), makeProvider({ providerId: 'google', priority: 20 })];
  const { deps, calls, meters } = harness(providers, async (params) => {
    if (params.providerId === 'groq') {
      throw new GatewayError('groq: rate limited', {
        code: 'RATE_LIMIT',
        status: 429,
        retryable: true,
        providerId: 'groq',
        model: params.model,
      });
    }
    return responseOf('google answer', params.model);
  });

  const result = await routeLLM(requestOf(), { companyId: 'company-1' }, deps);

  assert.equal(result.providerId, 'google');
  assert.equal(result.fallback, false);
  assert.equal(calls.length, 2);
  assert.equal(result.attempts.length, 2);
  assert.equal(result.attempts[0].ok, false);
  assert.equal(result.attempts[0].error, 'RATE_LIMIT');
  assert.equal(isCoolingDown('groq', NOW + 1), true);
  assert.equal(meters.length, 1);
  assert.equal(meters[0].providerId, 'google');
});

test('falls back to the deterministic local engine when every provider fails', async () => {
  const providers = [makeProvider({ providerId: 'groq', priority: 10 }), makeProvider({ providerId: 'google', priority: 20 })];
  const { deps, meters } = harness(providers, async (params) => {
    throw new GatewayError(`${params.providerId}: upstream down`, {
      code: 'SERVER_ERROR',
      status: 503,
      retryable: true,
      providerId: params.providerId,
      model: params.model,
    });
  });

  const result = await routeLLM(requestOf({ json: true }), { companyId: 'company-1' }, deps);

  assert.equal(result.fallback, true);
  assert.equal(result.providerId, 'local');
  assert.equal(result.model, 'deterministic-v1');
  assert.equal(result.content, '{}', 'json-mode local fallback returns an empty JSON object');
  assert.equal(result.attempts.length, 2);
  assert.equal(meters.length, 1);
  assert.equal(meters[0].status, 'FALLBACK');
  assert.equal(meters[0].fallback, true);
});

test('throws LLMUnavailableError when noFallback is set and providers fail', async () => {
  const providers = [makeProvider({ providerId: 'groq' })];
  const { deps } = harness(providers, async () => {
    throw new GatewayError('network', {
      code: 'NETWORK',
      retryable: true,
      providerId: 'groq',
      model: 'model-a',
    });
  });

  await assert.rejects(routeLLM(requestOf({ noFallback: true }), { companyId: 'company-1' }, deps), (error) => {
    assert.ok(error instanceof LLMUnavailableError);
    assert.equal(error.code, 'AI_PROVIDER_UNAVAILABLE');
    assert.equal(error.attempts.length, 1);
    return true;
  });
});

test('honours routing settings that disable local fallback', async () => {
  const providers = [makeProvider({ providerId: 'groq' })];
  const { deps } = harness(providers, async () => {
    throw new GatewayError('down', { code: 'SERVER_ERROR', retryable: true, providerId: 'groq', model: 'model-a' });
  });
  deps.loadRouting = async () => ({ strategy: 'priority', stickyMinutes: 30, allowLocalFallback: false });

  await assert.rejects(routeLLM(requestOf(), { companyId: 'company-1' }, deps), LLMUnavailableError);
});

test('falls back locally when no provider is configured', async () => {
  const { deps, calls } = harness([], async () => responseOf());
  const result = await routeLLM(requestOf(), { companyId: 'company-1' }, deps);
  assert.equal(result.fallback, true);
  assert.equal(calls.length, 0);
  assert.equal(result.attempts[0].error, 'no_provider_available');
});

test('skips disabled, unconfigured and cooling-down providers', async () => {
  const providers = [
    makeProvider({ providerId: 'groq', enabled: false }),
    makeProvider({ providerId: 'google', configured: false }),
    makeProvider({ providerId: 'mistral', priority: 30 }),
  ];
  const { deps, calls } = harness(providers, async () => responseOf('mistral answer', 'm'));
  const result = await routeLLM(requestOf(), { companyId: 'company-1' }, deps);
  assert.equal(result.providerId, 'mistral');
  assert.equal(calls.length, 1);
});

test('sticky session keeps the conversation on the last successful provider', async () => {
  const providers = [
    makeProvider({ providerId: 'groq', priority: 10 }),
    makeProvider({ providerId: 'google', priority: 20 }),
  ];
  const { deps, calls } = harness(providers, async (params) => responseOf(`answer from ${params.providerId}`, params.model));

  const first = await routeLLM(requestOf({ conversationId: 'conv-1' }), { companyId: 'company-1' }, deps);
  assert.equal(first.providerId, 'groq');

  // google becomes higher priority on the next turn, but sticky must keep groq first.
  providers[1].priority = 5;
  const second = await routeLLM(requestOf({ conversationId: 'conv-1' }), { companyId: 'company-1' }, deps);
  assert.equal(second.providerId, 'groq');
  assert.equal(calls.length, 2);
  assert.equal(calls[1].providerId, 'groq');
});

test('sticky session prefers the model that answered last', async () => {
  const providers = [makeProvider({ providerId: 'groq', models: ['model-a', 'model-b'] })];
  let callCount = 0;
  const { deps, calls } = harness(providers, async () => {
    callCount += 1;
    return responseOf('answer', 'model-b');
  });

  await routeLLM(requestOf({ conversationId: 'conv-2' }), { companyId: 'company-1' }, deps);
  assert.equal(calls[0].model, 'model-a');
  await routeLLM(requestOf({ conversationId: 'conv-2' }), { companyId: 'company-1' }, deps);
  assert.equal(calls[1].model, 'model-b');
});

test('model-level errors advance to the provider next model before failing over', async () => {
  const providers = [makeProvider({ providerId: 'groq', models: ['missing-model', 'good-model'] })];
  const { deps, calls } = harness(providers, async (params) => {
    if (params.model === 'missing-model') {
      throw new GatewayError('model not found', {
        code: 'NOT_FOUND',
        status: 404,
        retryable: false,
        providerId: 'groq',
        model: params.model,
      });
    }
    return responseOf('good answer', params.model);
  });

  const result = await routeLLM(requestOf(), { companyId: 'company-1' }, deps);
  assert.equal(result.model, 'good-model');
  assert.equal(calls.length, 2);
  assert.equal(result.attempts[0].error, 'NOT_FOUND');
});

test('rate-limit pre-check prevents a second call within the RPM budget', async () => {
  const providers = [makeProvider({ providerId: 'groq', limits: { rpm: 1 } })];
  const { deps, calls } = harness(providers, async () => responseOf('first'));

  const first = await routeLLM(requestOf(), { companyId: 'company-1' }, deps);
  assert.equal(first.providerId, 'groq');

  const second = await routeLLM(requestOf(), { companyId: 'company-1' }, deps);
  assert.equal(calls.length, 1, 'second call must not hit the provider');
  assert.equal(second.fallback, true);
  assert.equal(second.attempts[0].error, 'unavailable:RPM');
});

test('strategy ordering is deterministic', () => {
  const providers = [
    makeProvider({ providerId: 'slow', priority: 10 }),
    makeProvider({ providerId: 'fast', priority: 50 }),
    makeProvider({ providerId: 'unreliable', priority: 5 }),
  ];
  assert.deepEqual(
    orderProviders(providers, 'priority').map((provider) => provider.providerId),
    ['unreliable', 'slow', 'fast']
  );
  // speed shares the same latency prior → falls back to priority tie-break
  assert.deepEqual(
    orderProviders(providers, 'speed').map((provider) => provider.providerId),
    ['unreliable', 'slow', 'fast']
  );
  const routable = selectRoutableProviders(providers, 'priority', { now: NOW });
  assert.equal(routable.length, 3);
});
