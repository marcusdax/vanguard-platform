/**
 * Rate limits for the public authentication endpoints.
 *
 * Counted per client IP (Express trusts X-Forwarded-For only from private-network
 * proxies, see server.ts). Login counts only failed attempts, so a person who
 * signs in correctly is never locked out by the limit; guessing passwords is.
 */
import rateLimit from 'express-rate-limit';

const common = { standardHeaders: 'draft-7' as const, legacyHeaders: false };

export const loginLimiter = rateLimit({
  ...common,
  windowMs: 15 * 60_000,
  limit: 10,
  skipSuccessfulRequests: true,
  message: { error: 'Too many failed sign-in attempts. Wait 15 minutes and try again.', code: 'AUTH_RATE_LIMITED' },
});

export const registerLimiter = rateLimit({
  ...common,
  windowMs: 60 * 60_000,
  limit: 5,
  message: { error: 'Too many new accounts from this network. Try again in an hour.', code: 'AUTH_RATE_LIMITED' },
});

export const refreshLimiter = rateLimit({
  ...common,
  windowMs: 15 * 60_000,
  limit: 120,
  message: { error: 'Too many token refreshes. Try again shortly.', code: 'AUTH_RATE_LIMITED' },
});
