import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import {
  verifyVanguardResponder,
  evaluateSafetyBlanketDispatch,
  activateBlanketSearchParty,
  closeAndWipeSearchParty,
  buildWelfareSituationReport,
} from '../src/safetyBlanket';

describe('@vanguard/trust-engine — Community Safety Blanket, 3-Tier Certs & Welfare Sitrep', () => {
  it('enforces Tier 3 LCSO state license & STAY_CLEAR exclusion geofence on ACTIVE_THREAT', () => {
    const now = Math.floor(Date.now() / 1000);
    assert.throws(
      () =>
        verifyVanguardResponder({
          workerId: 'w-bad',
          tier: 'tier_3_lcso',
          jurisdiction: 'TX',
          hexCell: 'h10_1',
          onDuty: true,
          backgroundCleared: true,
          deescalationCertified: true,
        }),
      /stateLicenseId/
    );

    const t1 = verifyVanguardResponder({
      workerId: 'w-1',
      tier: 'tier_1_sentinel',
      jurisdiction: 'TX',
      hexCell: 'h10_1',
      onDuty: true,
      backgroundCleared: true,
      deescalationCertified: true,
    });
    const t3 = verifyVanguardResponder({
      workerId: 'w-3',
      tier: 'tier_3_lcso',
      jurisdiction: 'TX',
      hexCell: 'h10_1',
      onDuty: true,
      backgroundCleared: true,
      deescalationCertified: true,
      stateLicenseId: 'TX-PSB-9941',
      licenseExpiresEpoch: now + 86400 * 90,
      liabilityBondActive: true,
    });

    const route = evaluateSafetyBlanketDispatch([t1, t3], {
      alertClass: 'ACTIVE_THREAT',
      jurisdiction: 'TX',
      lcsoPatrolAuthorized: true,
    });
    assert.equal(route.tier12DispatchBlocked, true);
    assert.equal(route.exclusionGeofenceActive, true);
    assert.equal(route.stayClearWorkers.length, 1);
    assert.equal(route.eligibleTier3Lcso?.length, 1);
  });

  it('activates & cryptographically wipes Blanket Search Party and enforces non-AI hazard sources on Welfare Sitrep', () => {
    const card = activateBlanketSearchParty({
      caregiverConfirmed: true,
      clothing: 'Blue hoodie',
      optInPhoto: true,
      sectorHexCells: ['h10_1', 'h10_2'],
    });
    assert.equal(card.status, 'active');
    assert.equal(card.visualOnlyNoRestraint, true);

    const wiped = closeAndWipeSearchParty(card, 'found_safe');
    assert.equal(wiped.clothing, '[CRYPTOGRAPHICALLY_WIPED]');
    assert.equal(wiped.photoTokenActive, false);

    const sitrep = buildWelfareSituationReport({
      confirmedBy: 'caseworker-lead',
      addressLine: '401 Maple Ave, Austin, TX',
      sourceModule: 'hrp_foster_care',
      triageTierOrScore: 'tier_2_concerning',
      triggeredVectors: ['circadian_disruption'],
      accessibilityFlags: ['nonverbal_autism_uses_aac'],
      hazardProfile: [
        {
          flag: 'potential_violent_family_member',
          source: 'court_protective_order',
          officerSafetyNote: 'Active stay-away protective order on file.',
        },
      ],
    });
    assert.equal(sitrep.potentialViolenceOrWeaponRisk, true);
    assert.equal(sitrep.lawEnforcementEscortRecommended, true);
    assert.equal(sitrep.tier12DispatchBlocked, true);
    assert.equal(sitrep.aiThreatScoringUsed, false);
    assert.equal(sitrep.canonicalDigest.length, 64);
  });
});
