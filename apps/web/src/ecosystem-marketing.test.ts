import { describe, expect, it } from 'vitest';
import { SHOWCASE_PROOF_CARDS } from './components/MultiVerticalProofShowcase';
import {
  DEPIN_ROYALTY_PER_MILE_USD,
  SOVEREIGN_CAREER_TRACKS,
  computeEcosystemProjection,
} from './components/EcosystemRoiCalculator';

describe('Vanguard /verify Multi-Vertical Proof Showcase & Ecosystem ROI Calculator', () => {
  it('all showcase Spaces Proof Cards are zero-PII and strictly under 5 KB', () => {
    expect(SHOWCASE_PROOF_CARDS.length).toBeGreaterThanOrEqual(6);
    for (const card of SHOWCASE_PROOF_CARDS) {
      expect(card.piiScrubbed).toBe(true);
      const bytes = new TextEncoder().encode(JSON.stringify(card)).length;
      expect(bytes).toBeLessThan(5120);
    }
  });

  it('computes 120-day DePIN passive royalties and Sovereign Career track income accurately', () => {
    expect(SOVEREIGN_CAREER_TRACKS).toHaveLength(5);
    expect(DEPIN_ROYALTY_PER_MILE_USD).toBeCloseTo(0.76, 2);

    const proj = computeEcosystemProjection('field_evidence_auditor', 20, 100);
    expect(proj.activeWeeklyUsd).toBeCloseTo(20 * 54.97, 2);
    expect(proj.depinWeeklyUsd).toBeCloseTo(76.0, 2);
    expect(proj.depin120DayRoyaltyUsd).toBeGreaterThan(1300);
    expect(proj.annualUsd).toBeGreaterThan(60000);
  });
});
