/**
 * Vanguard Invariants I-1 & I-5 (Conformance Checks C-11 & C-12).
 *
 * I-1 (Check C-12): Floor payments (Tier 1 base bounties & payout claims) are NEVER gated
 *                   by plan tier, credential, subscription, or feature flag.
 * I-5 (Check C-11): Any gated commercial action must explicitly name missingCredential,
 *                   reason, and shortestFix { moduleId, lessonTitle, minutes, costCents: 0 }.
 */

export type VanguardTierLevel = 1 | 2 | 3 | 4;

export interface VanguardModuleDef {
  code: string;
  tier: VanguardTierLevel;
  title: string;
  minutes: number;
  required: boolean;
  grantsCredential: string | null;
  unlocksSummary: string;
  neutralityBoundary: string;
}

export const VANGUARD_CURRICULUM: VanguardModuleDef[] = [
  // Tier 1 — Field Scout & Community Helper ($45 avg) — Zero-Paywall Entry
  { code: 'T1-ONB', tier: 1, title: 'Identity & Background Verification', minutes: 20, required: true, grantsCredential: 'IDENTITY_VERIFIED', unlocksSummary: 'Tier 1 community errands & basic exterior photo bounties', neutralityBoundary: 'Clerical identity onboarding only' },
  { code: 'T1-01', tier: 1, title: 'Community Safety & Senior Care', minutes: 30, required: false, grantsCredential: 'SENIOR_SAFE_CARE', unlocksSummary: 'Elder grocery carry-ins & non-medical companion errands', neutralityBoundary: 'No medication administration or hands-on lifting (Papa Pal safe harbor)' },
  { code: 'T1-02', tier: 1, title: 'Vanguard Lens: Basic Photo Documentation', minutes: 45, required: false, grantsCredential: 'LENS_BASIC_PHOTO', unlocksSummary: 'Basic exterior property photo bounties with C2PA provenance', neutralityBoundary: 'Neutral camera operator — capture ends at upload' },
  { code: 'T1-03', tier: 1, title: 'Hyper-Local H3 Zone Batching', minutes: 20, required: false, grantsCredential: null, unlocksSummary: 'Stacking multi-vertical tasks in 1.5-mile loops ($0.76/mi IRS rate)', neutralityBoundary: 'Independent route selection (FLSA 2026 Core Factor)' },
  { code: 'T1-04', tier: 1, title: 'Escrow & Payout Mechanics', minutes: 15, required: true, grantsCredential: null, unlocksSummary: 'Micro-escrow settlement & optional Earn-to-Learn vault routing', neutralityBoundary: '100% worker floor protected by Invariant I-1' },
  { code: 'T1-05', tier: 1, title: 'Neutrality Boundary — TREC 531.20 & TX §4102', minutes: 25, required: true, grantsCredential: 'TREC_SAFE_HARBOR', unlocksSummary: 'Objective data collection across real estate & insurance surfaces', neutralityBoundary: 'Never act as adjuster, appraiser, or real estate agent' },

  // Tier 2 — Field Inspector & Trusted Care Pal ($85 avg)
  { code: 'T2-01', tier: 2, title: 'Counterparty Confirmation Protocol', minutes: 60, required: true, grantsCredential: 'COUNTERPARTY_ATTEST_T2', unlocksSummary: 'Verbal PIN & homeowner scope confirmation workflows', neutralityBoundary: 'Counterparty verifies physical presence/completion only' },
  { code: 'T2-02', tier: 2, title: 'AR Inspection Fundamentals (Vanguard Lens)', minutes: 90, required: false, grantsCredential: 'LENS_AR_INSPECTOR_T2', unlocksSummary: 'LiteRT on-device framing & semantic segmentation captures', neutralityBoundary: 'Objective frames only — no repair valuation fields' },
  { code: 'T2-03', tier: 2, title: 'Exterior Damage Identification', minutes: 45, required: false, grantsCredential: 'POST_STORM_EXTERIOR_T2', unlocksSummary: 'Post-storm exterior hail/wind/water intrusion documentation', neutralityBoundary: 'Documents visible physical artifacts without coverage opinions' },
  { code: 'T2-04', tier: 2, title: 'Customer Interaction & Trust-Building', minutes: 30, required: false, grantsCredential: null, unlocksSummary: 'Neighborhood trust moat & repeat Helios Local bookings', neutralityBoundary: 'Resident-first clarity without high-pressure sales' },
  { code: 'T2-05', tier: 2, title: 'Spatial Telemetry & Data Royalties', minutes: 40, required: false, grantsCredential: 'TELEMETRY_ROYALTY_OPTIN', unlocksSummary: 'Opt-in property owner consent & DePIN half-life royalty splits', neutralityBoundary: 'Fixed fair-market data pricing (RESPA §8 firewall)' },
  { code: 'T2-06', tier: 2, title: 'Vertical Intro: Choose Your First Specialty', minutes: 35, required: true, grantsCredential: null, unlocksSummary: 'Specialty vertical scheduling across 9 market channels', neutralityBoundary: 'Independent contractor specialty choice' },

  // Tier 3 — Lead Technical Inspector ($250+ per task)
  { code: 'T3-01', tier: 3, title: 'Roof Access & OSHA Safety Certification', minutes: 180, required: true, grantsCredential: 'ROOF_SAFETY', unlocksSummary: 'Physical roof pitch & slope access bounties', neutralityBoundary: 'Physical capture protocol only (Reconciliation R-08)' },
  { code: 'T3-02', tier: 3, title: 'Hail & Wind Damage Documentation', minutes: 120, required: false, grantsCredential: 'DART_FFI_INCLINOMETER_T3', unlocksSummary: '4-slope hail sequences, test squares & calibrated inclinometer telemetry', neutralityBoundary: 'TX Ins. Code Ch. 4102 neutral capture safe harbor' },
  { code: 'T3-03', tier: 3, title: 'Drone Operations (FAA Part 107 Prep)', minutes: 90, required: false, grantsCredential: 'DRONE_PILOT', unlocksSummary: 'Aerial solar array & commercial roof photogrammetry missions', neutralityBoundary: 'FAA Part 107 compliant mission telemetry' },
  { code: 'T3-04', tier: 3, title: 'Moisture & Thermal Imaging Basics', minutes: 120, required: false, grantsCredential: 'THERMAL_MOISTURE_T3', unlocksSummary: 'Water mitigation FLIR & moisture meter mapping bounties', neutralityBoundary: 'Objective instrument readings only' },
  { code: 'T3-05', tier: 3, title: 'Play Integrity & Anti-Spoofing Protocol', minutes: 60, required: true, grantsCredential: 'HARDWARE_ATTESTATION_T3', unlocksSummary: 'Nonce-bound hardware attestation & IMU jitter compliance', neutralityBoundary: 'Zero covert telemetry — operator-visible hardware proof' },
  { code: 'T3-06', tier: 3, title: 'Cryptographic Evidence Chain & Merkle Proofs', minutes: 90, required: false, grantsCredential: null, unlocksSummary: 'Tamper-evident SHA-256 chains & RFC 6962 exhibit packaging', neutralityBoundary: 'Verifiable by any third party in browser' },

  // Tier 4 — Vanguard Commander & Formal Attestor ($600+ per dossier)
  { code: 'T4-01', tier: 4, title: 'Attestor Certification (Formal RecordKind: ATTEST)', minutes: 480, required: true, grantsCredential: 'VANGUARD_COMMANDER_T4', unlocksSummary: 'Dual-Sign Desk Co-Signing & formal legal exhibit attestation', neutralityBoundary: 'Validates active state license before writing RecordKind: ATTEST' },
  { code: 'T4-02', tier: 4, title: 'Crew Leadership & Dispatch Optimization', minutes: 240, required: false, grantsCredential: 'CREW_COMMANDER_T4', unlocksSummary: 'H3 storm surge orchestration & crew override shares', neutralityBoundary: 'Non-coercive independent marketplace coordination' },
  { code: 'T4-03', tier: 4, title: 'Vanguard Instructor Certification', minutes: 180, required: false, grantsCredential: 'VANGUARD_INSTRUCTOR_T4', unlocksSummary: '90-day instruction royalty share on certified student earnings', neutralityBoundary: 'Competency verification on open curriculum' },
  { code: 'T4-04', tier: 4, title: 'DePIN Tokenomics & Passive Portfolio', minutes: 120, required: false, grantsCredential: null, unlocksSummary: 'Hardware vault co-ownership & half-life royalty management', neutralityBoundary: 'Fiat/USDC floor always separated from DePIN yield' },
  { code: 'T4-05', tier: 4, title: 'Legal Foundations for Attestors', minutes: 120, required: true, grantsCredential: null, unlocksSummary: 'TX Ch. 4102, RESPA §8 & 2026 FLSA contractor guardrails', neutralityBoundary: 'License-scoped attestation boundary' },
  { code: 'T4-06', tier: 4, title: 'Enterprise Client Relations', minutes: 90, required: false, grantsCredential: null, unlocksSummary: 'Institutional SLA retainers across carriers, solar & FACET', neutralityBoundary: 'Evidence delivery SLA — zero outcome contingency' },
];

export type CredentialBlockReason = {
  allowed: false;
  code: 'CREDENTIAL_REQUIRED';
  invariant: 'I-5';
  missingCredential: string;
  reason: string;
  shortestFix: {
    moduleId: string;
    lessonTitle: string;
    minutes: number;
    costCents: 0;
  };
};

export type CredentialAllowResult = {
  allowed: true;
  invariant: 'I-1' | 'I-5';
};

export type CredentialCheckResult = CredentialAllowResult | CredentialBlockReason;

/**
 * Invariant I-1 (Check C-12): Floor payments and base Tier 1 floor claims are NEVER gated.
 */
export function evaluateFloorPaymentGate(): CredentialAllowResult {
  return { allowed: true, invariant: 'I-1' };
}

export function checkCredentialsForAction(params: {
  isFloorAction?: boolean;
  requiredCredentials: string[];
  heldCredentials: string[];
  actionLabel?: string;
}): CredentialCheckResult {
  if (params.isFloorAction || params.requiredCredentials.length === 0) {
    return evaluateFloorPaymentGate();
  }
  const held = new Set(params.heldCredentials.map((c) => c.toUpperCase()));
  const missing = params.requiredCredentials.find((req) => !held.has(req.toUpperCase()));
  if (!missing) {
    return { allowed: true, invariant: 'I-5' };
  }
  const mod = VANGUARD_CURRICULUM.find(
    (m) => m.grantsCredential?.toUpperCase() === missing.toUpperCase(),
  );
  return {
    allowed: false,
    code: 'CREDENTIAL_REQUIRED',
    invariant: 'I-5',
    missingCredential: missing,
    reason: `${params.actionLabel || 'This assignment'} requires ${missing}. Complete the free micro-module below to unlock.`,
    shortestFix: {
      moduleId: mod?.code || 'T2-01',
      lessonTitle: mod?.title || `Unlock ${missing}`,
      minutes: mod?.minutes || 30,
      costCents: 0,
    },
  };
}
