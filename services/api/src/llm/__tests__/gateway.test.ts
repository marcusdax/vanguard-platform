/**
 * gateway.test.ts — OpenAI-compatible HTTP client with injected fetch (no network).
 * Run: npx tsx --test src/llm/__tests__/gateway.test.ts
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { chatCompletion, GatewayError } from '../gateway';

const API_KEY = 'sk-test-secret-key-do-not-log';

function jsonResponse(body: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json', ...headers },
  });
}

function successBody(content = 'hello from provider'): unknown {
  return {
    model: 'provider-model-v1',
    choices: [{ message: { role: 'assistant', content } }],
    usage: { prompt_tokens: 11, completion_tokens: 7, total_tokens: 18 },
  };
}

const baseParams = {
  providerId: 'groq',
  baseUrl: 'https://api.example.test/openai/v1',
  apiKey: API_KEY,
  model: 'llama-test',
  messages: [
    { role: 'system' as const, content: 'be brief' },
    { role: 'user' as const, content: 'hi' },
  ],
};

test('parses a successful chat completion and normalises usage', async () => {
  let capturedUrl = '';
  let capturedInit: RequestInit | undefined;
  const fetchImpl = (async (url: string | URL | Request, init?: RequestInit) => {
    capturedUrl = String(url);
    capturedInit = init;
    return jsonResponse(successBody());
  }) as typeof fetch;

  const result = await chatCompletion(baseParams, { fetchImpl });
  assert.equal(result.content, 'hello from provider');
  assert.equal(result.model, 'provider-model-v1');
  assert.deepEqual(result.usage, { promptTokens: 11, completionTokens: 7, totalTokens: 18 });
  assert.ok(result.latencyMs >= 0);
  assert.equal(capturedUrl, 'https://api.example.test/openai/v1/chat/completions');
  assert.equal((capturedInit?.headers as Record<string, string>).Authorization, `Bearer ${API_KEY}`);
});

test('requests response_format json_object when json output is asked for', async () => {
  let capturedBody: Record<string, unknown> | undefined;
  const fetchImpl = (async (_url: string | URL | Request, init?: RequestInit) => {
    capturedBody = JSON.parse(String(init?.body ?? '{}')) as Record<string, unknown>;
    return jsonResponse(successBody('{}'));
  }) as typeof fetch;

  await chatCompletion({ ...baseParams, json: true, temperature: 0.2, maxTokens: 128 }, { fetchImpl });
  assert.deepEqual(capturedBody?.response_format, { type: 'json_object' });
  assert.equal(capturedBody?.temperature, 0.2);
  assert.equal(capturedBody?.max_tokens, 128);
  assert.equal(capturedBody?.stream, false);
});

test('429 becomes a retryable RATE_LIMIT error with Retry-After', async () => {
  const fetchImpl = (async () =>
    jsonResponse({ error: { message: 'rate limit exceeded' } }, 429, { 'retry-after': '2' })) as typeof fetch;

  await assert.rejects(chatCompletion(baseParams, { fetchImpl }), (error: GatewayError) => {
    assert.ok(error instanceof GatewayError);
    assert.equal(error.code, 'RATE_LIMIT');
    assert.equal(error.retryable, true);
    assert.equal(error.retryAfterMs, 2_000);
    assert.equal(error.status, 429);
    return true;
  });
});

test('401 is non-retryable and never leaks the API key in the message', async () => {
  const fetchImpl = (async () =>
    jsonResponse({ error: { message: 'invalid api key' } }, 401)) as typeof fetch;

  await assert.rejects(chatCompletion(baseParams, { fetchImpl }), (error: GatewayError) => {
    assert.equal(error.code, 'UNAUTHORIZED');
    assert.equal(error.retryable, false);
    assert.ok(!error.message.includes(API_KEY), 'error message must not contain the API key');
    return true;
  });
});

test('5xx becomes a retryable SERVER_ERROR', async () => {
  const fetchImpl = (async () => jsonResponse({ message: 'upstream exploded' }, 503)) as typeof fetch;
  await assert.rejects(chatCompletion(baseParams, { fetchImpl }), (error: GatewayError) => {
    assert.equal(error.code, 'SERVER_ERROR');
    assert.equal(error.retryable, true);
    return true;
  });
});

test('malformed success payload becomes a retryable BAD_RESPONSE', async () => {
  const fetchImpl = (async () => jsonResponse({ unexpected: true })) as typeof fetch;
  await assert.rejects(chatCompletion(baseParams, { fetchImpl }), (error: GatewayError) => {
    assert.equal(error.code, 'BAD_RESPONSE');
    assert.equal(error.retryable, true);
    return true;
  });
});

test('timeouts abort the request and surface as TIMEOUT', async () => {
  const fetchImpl = ((_url: string | URL | Request, init?: RequestInit) => {
    return new Promise((_resolve, reject) => {
      const signal = init?.signal;
      const fail = () => {
        const error = new Error('aborted');
        error.name = 'AbortError';
        reject(error);
      };
      if (signal?.aborted) fail();
      else signal?.addEventListener('abort', fail);
    });
  }) as typeof fetch;

  await assert.rejects(
    chatCompletion({ ...baseParams, timeoutMs: 20 }, { fetchImpl }),
    (error: GatewayError) => {
      assert.equal(error.code, 'TIMEOUT');
      assert.equal(error.retryable, true);
      return true;
    }
  );
});

test('network failures surface as retryable NETWORK errors', async () => {
  const fetchImpl = (async () => {
    throw new TypeError('fetch failed');
  }) as typeof fetch;
  await assert.rejects(chatCompletion(baseParams, { fetchImpl }), (error: GatewayError) => {
    assert.equal(error.code, 'NETWORK');
    assert.equal(error.retryable, true);
    return true;
  });
});

test('retries once without response_format when the provider rejects JSON mode', async () => {
  const bodies: Array<Record<string, unknown>> = [];
  let calls = 0;
  const fetchImpl = (async (_url: string | URL | Request, init?: RequestInit) => {
    calls += 1;
    bodies.push(JSON.parse(String(init?.body ?? '{}')) as Record<string, unknown>);
    if (calls === 1) {
      return jsonResponse({ error: { message: 'response_format is not supported' } }, 400);
    }
    return jsonResponse(successBody('{"ok":true}'));
  }) as typeof fetch;

  const result = await chatCompletion({ ...baseParams, json: true }, { fetchImpl });
  assert.equal(calls, 2);
  assert.deepEqual(bodies[0].response_format, { type: 'json_object' });
  assert.equal(bodies[1].response_format, undefined);
  assert.equal(result.content, '{"ok":true}');
});

test('estimates tokens when the provider omits usage', async () => {
  const fetchImpl = (async () =>
    jsonResponse({
      choices: [{ message: { content: 'a'.repeat(40) } }],
    })) as typeof fetch;
  const result = await chatCompletion(baseParams, { fetchImpl });
  assert.ok(result.usage.totalTokens > 0);
  assert.equal(result.usage.totalTokens, result.usage.promptTokens + result.usage.completionTokens);
});

test('rejects an invalid base URL before attempting a fetch', async () => {
  let called = false;
  const fetchImpl = (async () => {
    called = true;
    return jsonResponse(successBody());
  }) as typeof fetch;
  await assert.rejects(chatCompletion({ ...baseParams, baseUrl: 'ftp://nope' }, { fetchImpl }), /base URL/);
  assert.equal(called, false);
});
