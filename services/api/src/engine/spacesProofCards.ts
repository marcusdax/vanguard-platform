// @vanguard/trust-engine — Multi-Vertical Spaces & Social Media Proof Cards (< 5 KB Verifier Budget)
// Supports AUCTUM.coffee, AUCTUM.atlas, AUCTUM.claims, AUCTUM.cacao, Vanguard Home Shield,
// Wealth Symphony (Fiduciary OS), and MASTERNEX.os Compliance Sidecar.

import { createHash } from 'node:crypto';
import { normalizeNfc } from './canonical';

export type SpacesProofVertical =
  | 'auctum_coffee'
  | 'auctum_atlas'
  | 'auctum_claims'
  | 'auctum_cacao'
  | 'vanguard_home_shield'
  | 'wealth_symphony'
  | 'masternex_compliance';

export interface SpacesProofCard {
  cardType: 'auctum-spaces-proof-card/v1';
  vertical: SpacesProofVertical;
  headline: string;
  verifiedClaims: Record<string, string | number | boolean>;
  issuedAt: string;
  canonicalSha256: string;
  byteSize: number;
  under5KbBudget: boolean;
}

const PII_REDACT_PATTERNS = [
  /\b\d{3}-\d{2}-\d{4}\b/g,
  /\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b/g,
  /\b(?:\+?1[-.\s]?)?(?:\(\d{3}\)|\d{3})[-.\s]\d{3}[-.\s]\d{4}\b/g,
];

function redactPublicText(text: string): string {
  let out = String(text || '');
  for (const rx of PII_REDACT_PATTERNS) out = out.replace(rx, '[REDACTED]');
  return out.trim();
}

export function createSpacesProofCard(params: {
  vertical: SpacesProofVertical;
  headline: string;
  verifiedClaims: Record<string, string | number | boolean>;
  issuedAt?: string;
}): SpacesProofCard {
  const cleanHeadline = redactPublicText(params.headline).slice(0, 160);
  const body = {
    cardType: 'auctum-spaces-proof-card/v1' as const,
    vertical: params.vertical,
    headline: cleanHeadline,
    verifiedClaims: params.verifiedClaims,
    issuedAt: params.issuedAt || new Date().toISOString(),
  };
  const canonical = JSON.stringify(normalizeNfc(body));
  const canonicalSha256 = createHash('sha256').update(canonical, 'utf8').digest('hex');
  const byteSize = Buffer.byteLength(canonical, 'utf8');
  return {
    ...body,
    canonicalSha256,
    byteSize,
    under5KbBudget: byteSize < 5120,
  };
}

export function verifySpacesProofCard(card: SpacesProofCard): boolean {
  const body = {
    cardType: card.cardType,
    vertical: card.vertical,
    headline: card.headline,
    verifiedClaims: card.verifiedClaims,
    issuedAt: card.issuedAt,
  };
  const canonical = JSON.stringify(normalizeNfc(body));
  const expected = createHash('sha256').update(canonical, 'utf8').digest('hex');
  return expected === card.canonicalSha256 && card.byteSize < 5120;
}
