// @vanguard/trust-engine — Community Safety Blanket, 3-Tier Security Certification,
// Blanket Search Party (Autism/Alzheimer's) & Law Enforcement Welfare Situation Reports.

import { createHash } from 'node:crypto';
import { normalizeNfc } from './canonical';

export type VanguardSecurityTier = 'tier_1_sentinel' | 'tier_2_cst' | 'tier_3_lcso';

export type HouseholdHazardFlag =
  | 'potential_violent_resident'
  | 'potential_violent_family_member'
  | 'active_protective_order'
  | 'weapons_reported_on_premises'
  | 'acute_behavioral_aggression'
  | 'aggressive_animal_on_premises';

export type HazardRecordSource =
  | 'court_protective_order'
  | 'caseworker_verified_history'
  | 'law_enforcement_prior_incident'
  | 'clinical_behavioral_plan';

export const VALID_HAZARD_FLAGS = new Set<HouseholdHazardFlag>([
  'potential_violent_resident',
  'potential_violent_family_member',
  'active_protective_order',
  'weapons_reported_on_premises',
  'acute_behavioral_aggression',
  'aggressive_animal_on_premises',
]);

export const VALID_HAZARD_SOURCES = new Set<HazardRecordSource>([
  'court_protective_order',
  'caseworker_verified_history',
  'law_enforcement_prior_incident',
  'clinical_behavioral_plan',
]);

export interface VanguardResponderProfile {
  workerId: string;
  tier: VanguardSecurityTier;
  jurisdiction: string;
  hexCell: string;
  onDuty: boolean;
  backgroundCleared: boolean;
  deescalationCertified: boolean;
  stateLicenseId?: string;
  licenseExpiresEpoch?: number;
  liabilityBondActive?: boolean;
}

export interface VerifiedResponderRecord {
  workerRef: string;
  tier: VanguardSecurityTier;
  jurisdiction: string;
  hexCell: string;
  onDuty: boolean;
  stateLicenseVerified: boolean;
  licenseDigest: string | null;
}

function sha256Hex(input: string): string {
  return createHash('sha256').update(input, 'utf8').digest('hex');
}

export function verifyVanguardResponder(
  profile: VanguardResponderProfile,
  nowSec: number = Math.floor(Date.now() / 1000)
): VerifiedResponderRecord {
  if (!profile.backgroundCleared || !profile.deescalationCertified) {
    throw new Error('All Vanguard tiers require backgroundCleared and deescalationCertified.');
  }
  if (profile.tier === 'tier_3_lcso') {
    if (!profile.stateLicenseId || !profile.stateLicenseId.trim()) {
      throw new Error('Tier 3 LCSO requires a verified stateLicenseId.');
    }
    if (!profile.licenseExpiresEpoch || profile.licenseExpiresEpoch <= nowSec) {
      throw new Error('Tier 3 LCSO state license is expired.');
    }
    if (!profile.liabilityBondActive) {
      throw new Error('Tier 3 LCSO requires an active commercial liability bond.');
    }
  }
  return {
    workerRef: 'vg-' + sha256Hex(profile.workerId).slice(0, 12),
    tier: profile.tier,
    jurisdiction: profile.jurisdiction.trim().toUpperCase(),
    hexCell: profile.hexCell,
    onDuty: Boolean(profile.onDuty),
    stateLicenseVerified: profile.tier === 'tier_3_lcso',
    licenseDigest: profile.stateLicenseId ? sha256Hex(profile.stateLicenseId).slice(0, 16) : null,
  };
}

export function evaluateSafetyBlanketDispatch(
  responders: VerifiedResponderRecord[],
  opts: {
    alertClass: 'ACTIVE_THREAT' | 'HRP_TIER_3_CRITICAL' | 'NON_VIOLENT_SUPPORT';
    jurisdiction: string;
    lcsoPatrolAuthorized?: boolean;
  }
) {
  const onDuty = responders.filter((r) => r.onDuty);
  const jur = opts.jurisdiction.trim().toUpperCase();
  if (opts.alertClass === 'ACTIVE_THREAT' || opts.alertClass === 'HRP_TIER_3_CRITICAL') {
    const stayClearWorkers = onDuty.filter((r) => r.tier !== 'tier_3_lcso').map((r) => r.workerRef);
    const eligibleLcso = opts.lcsoPatrolAuthorized
      ? onDuty
          .filter((r) => r.tier === 'tier_3_lcso' && r.stateLicenseVerified && r.jurisdiction === jur)
          .map((r) => r.workerRef)
      : [];
    return {
      alertClass: opts.alertClass,
      tier12DispatchBlocked: true,
      exclusionGeofenceActive: true,
      stayClearWorkers,
      homeAddressExposedToStayClear: false,
      eligibleTier3Lcso: eligibleLcso,
    };
  }
  return {
    alertClass: opts.alertClass,
    tier12DispatchBlocked: false,
    exclusionGeofenceActive: false,
    stayClearWorkers: [] as string[],
    homeAddressExposedToStayClear: false,
    safeSupportCandidates: onDuty.filter((r) => r.jurisdiction === jur).map((r) => r.workerRef),
  };
}

export interface BlanketSearchCard {
  searchId: string;
  status: 'active' | 'closed';
  profileCategory: string;
  clothing: string;
  deescalationInstructions: string;
  waterHazardPriority: boolean;
  photoTokenActive: boolean;
  visualOnlyNoRestraint: true;
  sectors: Array<{ hexCell: string; ring: string; claimedBy: string | null; status: 'unclaimed' | 'searching' }>;
  outcome?: string;
}

export function activateBlanketSearchParty(params: {
  caregiverConfirmed: boolean;
  profileCategory?: string;
  clothing: string;
  deescalationInstructions?: string;
  waterHazardPriority?: boolean;
  optInPhoto?: boolean;
  sectorHexCells: string[];
}): BlanketSearchCard {
  if (!params.caregiverConfirmed) {
    throw new Error('Blanket Search Party requires explicit caregiverConfirmed=true.');
  }
  return {
    searchId: 'bsp-' + sha256Hex(params.clothing + Date.now()).slice(0, 10),
    status: 'active',
    profileCategory: params.profileCategory || 'autism_nonverbal',
    clothing: params.clothing.slice(0, 200),
    deescalationInstructions:
      params.deescalationInstructions ||
      'Nonverbal — do NOT shout, chase, or grab. Maintain calm visual distance and contact caregiver/911.',
    waterHazardPriority: params.waterHazardPriority ?? true,
    photoTokenActive: Boolean(params.optInPhoto),
    visualOnlyNoRestraint: true,
    sectors: params.sectorHexCells.map((hexCell, i) => ({
      hexCell,
      ring: i === 0 ? 'ring_1_0_500m' : 'ring_2_500m_2km',
      claimedBy: null,
      status: 'unclaimed',
    })),
  };
}

export function closeAndWipeSearchParty(card: BlanketSearchCard, outcome = 'found_safe'): BlanketSearchCard {
  return {
    ...card,
    status: 'closed',
    outcome,
    clothing: '[CRYPTOGRAPHICALLY_WIPED]',
    deescalationInstructions: '[CRYPTOGRAPHICALLY_WIPED]',
    photoTokenActive: false,
  };
}

export interface HazardProfileEntry {
  flag: HouseholdHazardFlag;
  source: HazardRecordSource;
  roleRelation?: string;
  officerSafetyNote?: string;
}

export function buildWelfareSituationReport(params: {
  confirmedBy: string;
  addressLine: string;
  sourceModule: 'hrp_foster_care' | 'family_shield_elder_care' | 'vanguard_home_shield';
  triageTierOrScore: string;
  triggeredVectors: string[];
  accessibilityFlags: string[];
  hazardProfile: HazardProfileEntry[];
  situationNotes?: string;
}) {
  if (!params.confirmedBy.trim()) throw new Error('Welfare Situation Report requires confirmedBy.');
  if (!params.addressLine.trim()) throw new Error('Welfare Situation Report requires addressLine.');

  for (const h of params.hazardProfile) {
    if (!VALID_HAZARD_FLAGS.has(h.flag)) throw new Error(`Unsupported hazard flag: ${h.flag}`);
    if (!VALID_HAZARD_SOURCES.has(h.source)) {
      throw new Error(`Hazard flag requires verified non-AI source: ${h.source}`);
    }
  }

  const highDanger = new Set<HouseholdHazardFlag>([
    'potential_violent_resident',
    'potential_violent_family_member',
    'active_protective_order',
    'weapons_reported_on_premises',
  ]);
  const hasViolenceRisk = params.hazardProfile.some((h) => highDanger.has(h.flag));

  const body = {
    packetType: 'vanguard-hrp-welfare-sitrep/v1',
    sourceModule: params.sourceModule,
    triageTierOrScore: params.triageTierOrScore,
    confirmedByDigest: sha256Hex(params.confirmedBy).slice(0, 16),
    addressDigest: sha256Hex(params.addressLine).slice(0, 16),
    triggeredVectors: params.triggeredVectors,
    accessibilityFlags: params.accessibilityFlags,
    hazardProfile: params.hazardProfile,
    potentialViolenceOrWeaponRisk: hasViolenceRisk,
    lawEnforcementEscortRecommended: hasViolenceRisk || /tier_3/i.test(params.triageTierOrScore),
    tier12DispatchBlocked: hasViolenceRisk || /tier_3/i.test(params.triageTierOrScore),
    stayClearExclusionEnforced: hasViolenceRisk,
    aiThreatScoringUsed: false,
    rawIndoorCameraAttached: false,
    situationNotes: (params.situationNotes || '').slice(0, 400),
  };

  return {
    ...body,
    canonicalDigest: sha256Hex(JSON.stringify(normalizeNfc(body))),
  };
}
