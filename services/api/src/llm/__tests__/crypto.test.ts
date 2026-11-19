/**
 * crypto.test.ts — AES-256-GCM secret handling (contract §4.1 / §5.6).
 * Run: npx tsx --test src/llm/__tests__/crypto.test.ts
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { decryptSecret, encryptSecret, getEncryptionKey, maskSecret, secretsEqual } from '../crypto';

const VALID_KEY = 'a'.repeat(64);
const VALID_JWT = 'test-jwt-secret-for-ai-encryption';

function setKeyEnv(value: string | undefined): void {
  if (value === undefined) delete process.env.AI_ENCRYPTION_KEY;
  else process.env.AI_ENCRYPTION_KEY = value;
}

test('round-trips a secret with a 64-hex AI_ENCRYPTION_KEY', () => {
  setKeyEnv(VALID_KEY);
  try {
    const plaintext = 'sk-test-groq-key-1234567890';
    const ciphertext = encryptSecret(plaintext);
    assert.match(ciphertext, /^v1:/);
    assert.ok(!ciphertext.includes(plaintext), 'ciphertext must not contain plaintext');
    assert.equal(decryptSecret(ciphertext), plaintext);
  } finally {
    setKeyEnv(undefined);
  }
});

test('uses a fresh IV for every encryption (same input → different ciphertext)', () => {
  setKeyEnv(VALID_KEY);
  try {
    const a = encryptSecret('same-secret');
    const b = encryptSecret('same-secret');
    assert.notEqual(a, b);
    assert.equal(decryptSecret(a), decryptSecret(b));
  } finally {
    setKeyEnv(undefined);
  }
});

test('falls back to sha256(JWT_SECRET) when AI_ENCRYPTION_KEY is unset', () => {
  setKeyEnv(undefined);
  process.env.JWT_SECRET = VALID_JWT;
  try {
    const ciphertext = encryptSecret('fallback-secret');
    assert.equal(decryptSecret(ciphertext), 'fallback-secret');
    assert.equal(getEncryptionKey().length, 32);
  } finally {
    delete process.env.JWT_SECRET;
  }
});

test('rejects malformed AI_ENCRYPTION_KEY instead of silently weakening encryption', () => {
  setKeyEnv('too-short');
  try {
    assert.throws(() => encryptSecret('x'), /64 hex characters/);
  } finally {
    setKeyEnv(undefined);
  }
});

test('rejects tampered ciphertext and never echoes the plaintext in the error', () => {
  setKeyEnv(VALID_KEY);
  try {
    const plaintext = 'super-secret-provider-key';
    const ciphertext = encryptSecret(plaintext);
    const parts = ciphertext.split(':');
    const tampered = [parts[0], parts[1], parts[2], Buffer.from('tampered').toString('base64')].join(':');
    assert.throws(
      () => decryptSecret(tampered),
      (error: Error) => {
        assert.ok(!error.message.includes(plaintext));
        return /could not be decrypted|malformed/i.test(error.message);
      }
    );
  } finally {
    setKeyEnv(undefined);
  }
});

test('rejects unsupported ciphertext versions', () => {
  setKeyEnv(VALID_KEY);
  try {
    assert.throws(() => decryptSecret('v9:abc:def:ghi'), /Unsupported or malformed/);
    assert.throws(() => decryptSecret(''), /empty/);
  } finally {
    setKeyEnv(undefined);
  }
});

test('masks secrets as ••••last4 and never grows a mask', () => {
  assert.equal(maskSecret('sk-1234567890abcd'), '••••abcd');
  assert.equal(maskSecret('short'), '••••rt');
  assert.equal(maskSecret('••••abcd'), '••••abcd');
  assert.equal(maskSecret(''), null);
  assert.equal(maskSecret(null), null);
  assert.equal(maskSecret(undefined), null);
});

test('secretsEqual is a correct constant-time comparison helper', () => {
  assert.equal(secretsEqual('abc', 'abc'), true);
  assert.equal(secretsEqual('abc', 'abd'), false);
  assert.equal(secretsEqual('abc', 'abcd'), false);
});
