/**
 * AES-256-GCM secret encryption for provider API keys.
 *
 * - Key material: `AI_ENCRYPTION_KEY` (64 hex chars === 32 bytes) when present,
 *   otherwise SHA-256(`JWT_SECRET`) so local development works without extra setup.
 * - Ciphertext format: `v1:<ivB64>:<tagB64>:<cipherB64>` (versioned for rotation).
 * - Plaintext keys never leave the process and are never logged. Only
 *   `maskSecret()` output (•••• + last 4) may be returned by the API.
 */
import { createCipheriv, createDecipheriv, createHash, randomBytes, timingSafeEqual } from 'crypto';

const ALGORITHM = 'aes-256-gcm';
const VERSION = 'v1';
const IV_BYTES = 12;

const DEV_JWT_FALLBACK = 'roofpro-crm-super-secret-key-change-in-production';

/** Read configuration lazily: `dotenv.config()` runs after module evaluation in ESM. */
function readEnv(name: string): string {
  return (process.env[name] || '').trim();
}

function legacyJwtSecret(): string {
  return readEnv('JWT_SECRET') || DEV_JWT_FALLBACK;
}

/**
 * Resolve the 32-byte AES key.
 * `AI_ENCRYPTION_KEY` must be 64 hex characters; anything else throws rather
 * than silently weakening encryption.
 */
export function getEncryptionKey(): Buffer {
  const raw = readEnv('AI_ENCRYPTION_KEY');
  if (raw) {
    if (!/^[0-9a-fA-F]{64}$/.test(raw)) {
      throw new Error(
        'AI_ENCRYPTION_KEY must be exactly 64 hex characters (32 bytes). Generate one with: node -e "console.log(require(\'crypto\').randomBytes(32).toString(\'hex\'))"'
      );
    }
    return Buffer.from(raw, 'hex');
  }
  return createHash('sha256').update(legacyJwtSecret(), 'utf8').digest();
}

/** AES-256-GCM encrypt. Returns the versioned storage string. */
export function encryptSecret(plaintext: string): string {
  if (typeof plaintext !== 'string' || plaintext.length === 0) {
    throw new Error('Cannot encrypt an empty secret');
  }
  const key = getEncryptionKey();
  const iv = randomBytes(IV_BYTES);
  const cipher = createCipheriv(ALGORITHM, key, iv);
  const encrypted = Buffer.concat([cipher.update(plaintext, 'utf8'), cipher.final()]);
  const tag = cipher.getAuthTag();
  return [VERSION, iv.toString('base64'), tag.toString('base64'), encrypted.toString('base64')].join(':');
}

/** AES-256-GCM decrypt. Throws a generic error on malformed/tampered input. */
export function decryptSecret(ciphertext: string): string {
  if (typeof ciphertext !== 'string' || ciphertext.length === 0) {
    throw new Error('Cannot decrypt an empty value');
  }
  const parts = ciphertext.split(':');
  if (parts.length !== 4 || parts[0] !== VERSION) {
    throw new Error('Unsupported or malformed encrypted value');
  }
  let key: Buffer;
  try {
    key = getEncryptionKey();
  } catch (error) {
    throw new Error(`AI encryption key is invalid: ${(error as Error).message}`);
  }
  const iv = Buffer.from(parts[1], 'base64');
  const tag = Buffer.from(parts[2], 'base64');
  const data = Buffer.from(parts[3], 'base64');
  if (iv.length !== IV_BYTES || tag.length !== 16) {
    throw new Error('Unsupported or malformed encrypted value');
  }
  try {
    const decipher = createDecipheriv(ALGORITHM, key, iv);
    decipher.setAuthTag(tag);
    const plaintext = Buffer.concat([decipher.update(data), decipher.final()]);
    return plaintext.toString('utf8');
  } catch {
    // Never echo ciphertext/key material in the error.
    throw new Error('Stored API key could not be decrypted (encryption key changed?)');
  }
}

/**
 * Return the display-safe masked form of a secret: `••••last4`.
 * Accepts already-masked strings defensively (never grows a mask).
 */
export function maskSecret(secret: string | null | undefined): string | null {
  if (!secret || typeof secret !== 'string') return null;
  if (secret.startsWith('••••')) return secret;
  const visible = secret.length > 8 ? secret.slice(-4) : secret.slice(-2);
  return `••••${visible}`;
}

/** Constant-time comparison helper for tests / future key rotation checks. */
export function secretsEqual(a: string, b: string): boolean {
  const ba = Buffer.from(a, 'utf8');
  const bb = Buffer.from(b, 'utf8');
  if (ba.length !== bb.length) return false;
  return timingSafeEqual(ba, bb);
}
