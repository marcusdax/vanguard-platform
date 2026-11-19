import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { createSpacesProofCard, verifySpacesProofCard } from '../src/spacesProofCards';

describe('@vanguard/trust-engine — Multi-Vertical Spaces & Social Media Proof Cards', () => {
  it('creates and verifies < 5 KB PII-redacted Proof Cards across AUCTUM.coffee, AUCTUM.atlas & MASTERNEX Compliance', () => {
    const coffee = createSpacesProofCard({
      vertical: 'auctum_coffee',
      headline: 'Ethiopian Gedeb Roast — 38% Direct Farmer Share (contact roast@example.com)',
      verifiedClaims: { eudrDeforestationFree: true, farmerBps: 3800, brokerNullBps: 0 },
    });
    assert.equal(coffee.under5KbBudget, true);
    assert.equal(verifySpacesProofCard(coffee), true);
    assert.doesNotMatch(coffee.headline, /roast@example\.com/);

    const compliance = createSpacesProofCard({
      vertical: 'masternex_compliance',
      headline: 'Vibe-Coded App Hardened with MASTERNEX.os (EU AI Act Art. 12 + SR 11-7)',
      verifiedClaims: { euAiActArt12: true, sr117: true, piiRedacted: true },
    });
    assert.equal(verifySpacesProofCard(compliance), true);
    // Tamper detection
    assert.equal(verifySpacesProofCard({ ...compliance, headline: 'Tampered' }), false);
  });
});
