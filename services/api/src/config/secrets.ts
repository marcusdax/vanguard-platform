/**
 * JWT signing secrets, resolved once.
 *
 * In production the secrets must be set and strong: a missing or placeholder
 * secret would let anyone forge sign-in tokens, so the API refuses to start.
 * In development a fixed fallback keeps `npm run dev` working, with a warning.
 */

const DEV_FALLBACK = {
  access: 'roofpro-crm-dev-only-access-secret-not-for-production',
  refresh: 'roofpro-crm-dev-only-refresh-secret-not-for-production',
} as const;

const MIN_LENGTH = 32;
const PLACEHOLDER = /change[-_ ]?(in[-_ ]?production|me)|^secret$|example|placeholder/i;

export type SecretKind = keyof typeof DEV_FALLBACK;

export function resolveJwtSecret(
  kind: SecretKind,
  env: Record<string, string | undefined> = process.env,
  warn: (msg: string) => void = (m) => console.warn(m),
): string {
  const name = kind === 'access' ? 'JWT_SECRET' : 'JWT_REFRESH_SECRET';
  const value = env[name]?.trim();
  const problem = !value
    ? 'is not set'
    : value.length < MIN_LENGTH
      ? `is shorter than ${MIN_LENGTH} characters`
      : PLACEHOLDER.test(value)
        ? 'looks like a placeholder'
        : null;
  if (!problem) return value!;
  if (env.NODE_ENV === 'production') {
    throw new Error(`${name} ${problem}. Set a long random value (e.g. \`openssl rand -hex 48\`) before starting in production.`);
  }
  warn(`[secrets] ${name} ${problem}; using a development-only fallback. Never deploy like this.`);
  return DEV_FALLBACK[kind];
}

export const JWT_SECRET = resolveJwtSecret('access');
export const JWT_REFRESH_SECRET = resolveJwtSecret('refresh');
