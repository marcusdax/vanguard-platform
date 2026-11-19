/**
 * Vanguard — Canonical Catalog:
 *   - 4 Career Tiers ($45 -> $85 -> $250+ -> $600+)
 *   - 10 Demand Verticals (Tier 0A/0B B2C + V-01..V-08 B2B)
 *   - Earn-to-Learn Hardware Vault Kit Catalog
 */
import type { MarketSurface } from '../../engine/economics';

export interface VanguardTierSummary {
  tier: 1 | 2 | 3 | 4;
  roleTitle: string;
  subtitle: string;
  payoutHeadline: string;
  avgPayoutCents: number;
  legalBoundary: string;
  highlights: string[];
}

export const VANGUARD_TIERS: VanguardTierSummary[] = [
  {
    tier: 1,
    roleTitle: 'Field Scout & Community Helper',
    subtitle: 'Reported · Zero-Paywall Entry (Invariant I-1)',
    payoutHeadline: '$45 / task avg',
    avgPayoutCents: 4500,
    legalBoundary: 'Neutral camera & community helper; legal role ends at upload (TREC 531.20 safe harbor).',
    highlights: [
      'Start earning day 1 with standard ID + background check',
      'Senior grocery carry-ins, lawn care & exterior photo bounties',
      'H3 1.5-mile zone batching cuts $0.76/mi vehicle overhead',
      'Optional 5%–15% Earn-to-Learn gear escrow routing',
    ],
  },
  {
    tier: 2,
    roleTitle: 'Field Inspector & Trusted Care Pal',
    subtitle: 'Counterparty-Confirmed · Verbal PIN & AR Basics',
    payoutHeadline: '$85 / task avg',
    avgPayoutCents: 8500,
    legalBoundary: 'Counterparty sign-off only; zero non-medical care intervention or property valuation.',
    highlights: [
      'Homeowner 4-digit verbal PIN & scope agreement sign-off',
      'LiteRT on-device RAM blur & AR framing validation',
      'Post-storm exterior & flood water-line surveys',
      'Opt-in spatial telemetry DePIN royalty eligibility',
    ],
  },
  {
    tier: 3,
    roleTitle: 'Lead Technical Inspector',
    subtitle: 'Field-Captured · Instrument & Sensor Backed',
    payoutHeadline: '$250+ / task',
    avgPayoutCents: 25000,
    legalBoundary: 'TX Insurance Code Ch. 4102 neutral capture technician; never negotiates claims.',
    highlights: [
      '4-slope roof hail capture, chalk test squares & Dart FFI pitch gauge',
      'FAA Part 107 solar array & commercial roof thermal UAV scans',
      'FLIR thermal & moisture intrusion mapping',
      'Hardware Play Integrity + organic IMU jitter proof',
    ],
  },
  {
    tier: 4,
    roleTitle: 'Vanguard Commander',
    subtitle: 'Formal Attestor (RecordKind: ATTEST) & Crew Orchestrator',
    payoutHeadline: '$600+ / dossier',
    avgPayoutCents: 60000,
    legalBoundary: 'License-verified formal Attestor; Dual-Sign Desk Co-Signing & RESPA §8 compliant.',
    highlights: [
      'Formal RecordKind: ATTEST co-signing of Tier 3 dossiers',
      'CAT storm crew orchestration across H3 micro-zones',
      'Vanguard Instructor certification (90-day cohort royalties)',
      'DePIN fractional asset portfolio & carrier retainer SLAs',
    ],
  },
];

export interface VerticalDefinition {
  code: string;
  tierGroup: 'T0' | 'T1' | 'T2' | 'T3';
  surface: MarketSurface;
  surfaceLabel: string;
  name: string;
  payoutRangeLabel: string;
  defaultBountyCents: number;
  requiredTier: 1 | 2 | 3 | 4;
  requiredCredential: string | null;
  triggerSource: string;
  deliverable: string;
  legalNote: string;
}

export const DEMAND_VERTICALS: VerticalDefinition[] = [
  {
    code: 'V-0A',
    tierGroup: 'T0',
    surface: 'helios.local.b2c',
    surfaceLabel: 'Helios Local (B2C)',
    name: 'Community & Senior Care Errands',
    payoutRangeLabel: '$18–$45 / task',
    defaultBountyCents: 1800,
    requiredTier: 1,
    requiredCredential: null, // Invariant I-1: floor errand never gated
    triggerSource: 'Senior, family proxy, or care plan booking',
    deliverable: 'Grocery carry-in, pharmacy pickup, lawn check + 4-digit verbal PIN',
    legalNote: 'Non-medical Papa Pal safe harbor (no medication/hands-on lifting)',
  },
  {
    code: 'V-0B',
    tierGroup: 'T0',
    surface: 'helios.local.b2c',
    surfaceLabel: 'Helios Local (B2C)',
    name: 'Residential Lawn & Exterior Upkeep',
    payoutRangeLabel: '$41–$71 / mow',
    defaultBountyCents: 4200,
    requiredTier: 1,
    requiredCredential: null,
    triggerSource: 'On-demand mow or 26–46 wk seasonal neighborhood schedule',
    deliverable: 'Quarter-acre mow, edge, blow + Vanguard Lens AR before/after scan',
    legalNote: 'Homeowner keeps opt-in spatial data dividend; 5% community fee',
  },
  {
    code: 'V-01',
    tierGroup: 'T1',
    surface: 'atlas.roofing',
    surfaceLabel: 'Atlas Storm (B2B)',
    name: 'Tree Service & Emergency Clearing',
    payoutRangeLabel: '$20–$180 / task',
    defaultBountyCents: 2000,
    requiredTier: 1,
    requiredCredential: null,
    triggerSource: 'Atlas severe wind/ice polygon pre-call alert',
    deliverable: 'Rapid street-level scout of downed limbs/trees on structures',
    legalNote: 'Neutral street photography; 2.50% Atlas contractor fee on invoice',
  },
  {
    code: 'V-02',
    tierGroup: 'T1',
    surface: 'vanguard.b2b.bounty',
    surfaceLabel: 'Atlas Roofing (B2B)',
    name: 'Roofing & Storm Restoration',
    payoutRangeLabel: '$65–$250 / roof',
    defaultBountyCents: 6500,
    requiredTier: 3,
    requiredCredential: 'ROOF_SAFETY',
    triggerSource: 'Atlas hail/wind swath radar + property age filter',
    deliverable: '4-slope hail sequence, test square, pitch gauge & C2PA dossier',
    legalNote: 'TX Ins. Code Ch. 4102 neutral capture; zero claim negotiation',
  },
  {
    code: 'V-03',
    tierGroup: 'T1',
    surface: 'vanguard.b2b.bounty',
    surfaceLabel: 'Atlas Flood (B2B)',
    name: 'Water & Flood Mitigation',
    payoutRangeLabel: '$75–$220 / task',
    defaultBountyCents: 7500,
    requiredTier: 2,
    requiredCredential: 'COUNTERPARTY_ATTEST_T2',
    triggerSource: 'NOAA flash-flood polygon warning',
    deliverable: 'High-water mark photos, foundation intrusion & moisture readings',
    legalNote: 'Objective sensor telemetry only',
  },
  {
    code: 'V-04',
    tierGroup: 'T2',
    surface: 'vanguard.b2b.bounty',
    surfaceLabel: 'Atlas Claims Desk',
    name: 'Public Adjusting Support',
    payoutRangeLabel: '$150–$600+ / dossier',
    defaultBountyCents: 15000,
    requiredTier: 4,
    requiredCredential: 'VANGUARD_COMMANDER_T4',
    triggerSource: 'Disputed insurance claim / Dual-Sign Desk request',
    deliverable: 'Formal RecordKind: ATTEST forensic scope packet + custody proof',
    legalNote: 'License-verified Tier 4 Commander attestation strictly enforced',
  },
  {
    code: 'V-05',
    tierGroup: 'T2',
    surface: 'vanguard.b2b.bounty',
    surfaceLabel: 'Atlas / Helios Solar',
    name: 'Solar Array Thermal Inspection',
    payoutRangeLabel: '$120–$600 / array',
    defaultBountyCents: 25000,
    requiredTier: 3,
    requiredCredential: 'DRONE_PILOT',
    triggerSource: 'Post-hail anomaly or commercial MW audit',
    deliverable: 'Radiometric thermal UAV orthomosaic & inverter hotspot map',
    legalNote: 'FAA Part 107 mission log + IMU jitter check',
  },
  {
    code: 'V-06',
    tierGroup: 'T2',
    surface: 'vanguard.b2b.bounty',
    surfaceLabel: 'FACET Property (Tier B)',
    name: 'Real Estate & FACET Underwriting',
    payoutRangeLabel: '$30–$150 / scan',
    defaultBountyCents: 7500,
    requiredTier: 2,
    requiredCredential: 'TREC_SAFE_HARBOR',
    triggerSource: 'Mortgage desktop verification or investor property queue',
    deliverable: 'Objective interior/exterior condition & LiDAR scan receipt',
    legalNote: 'RESPA §8 fixed fair-market data fee + TREC 531.20 safe harbor',
  },
  {
    code: 'V-07',
    tierGroup: 'T3',
    surface: 'vanguard.b2b.bounty',
    surfaceLabel: 'Atlas / Helios Restoration',
    name: 'Fire & Smoke Restoration',
    payoutRangeLabel: '$100–$280 / loss',
    defaultBountyCents: 20000,
    requiredTier: 3,
    requiredCredential: 'THERMAL_MOISTURE_T3',
    triggerSource: 'Structural fire loss dispatch notification',
    deliverable: 'Board-up verification, soot/char perimeter & 3D LiDAR walkthrough',
    legalNote: 'Neutral loss capture — excludes repair settlement estimates',
  },
  {
    code: 'V-08',
    tierGroup: 'T3',
    surface: 'supply.standard',
    surfaceLabel: 'Vanguard Supply / Chain of Custody',
    name: 'Supply Chain, Pavement & Fiduciary',
    payoutRangeLabel: '$70–$180 / audit',
    defaultBountyCents: 9000,
    requiredTier: 2,
    requiredCredential: 'COUNTERPARTY_ATTEST_T2',
    triggerSource: 'Green coffee lot arrival, hardscape hail, or fiduciary seal audit',
    deliverable: 'Sensory cupping score, crack mapping, or tamper-evident seal log',
    legalNote: 'Deterministic PostgreSQL trigger & append-only custody ledger',
  },
];

export interface HardwareGearCatalogItem {
  sku: string;
  name: string;
  category: 'FLIR_THERMAL' | 'UAV_PART_107' | 'LIDAR_RIG' | 'ELECTRIC_MOWER';
  retailCents: number;
  escrowUnlockCents: number;
  unlocksVertical: string;
}

export const HARDWARE_GEAR_CATALOG: HardwareGearCatalogItem[] = [
  {
    sku: 'GEAR-FLIR-ONE-PRO',
    name: 'FLIR One Edge Pro Thermal Attachment + Moisture Pin Kit',
    category: 'FLIR_THERMAL',
    retailCents: 44900,
    escrowUnlockCents: 38000,
    unlocksVertical: 'V-03 Water Mitigation & V-07 Fire Restoration ($75–$280/task)',
  },
  {
    sku: 'GEAR-EGO-MOWER-56V',
    name: 'Commercial Electric Walk-Behind Mower + Trimmer Kit',
    category: 'ELECTRIC_MOWER',
    retailCents: 69900,
    escrowUnlockCents: 58000,
    unlocksVertical: 'V-0B Recurring Neighborhood Lawns ($42–$71/mow)',
  },
  {
    sku: 'GEAR-LIDAR-PITCH-KIT',
    name: 'Dart FFI Calibrated Inclinometer + Safety Harness Bundle',
    category: 'LIDAR_RIG',
    retailCents: 32000,
    escrowUnlockCents: 26000,
    unlocksVertical: 'V-02 4-Slope Technical Roof Captures ($65–$250/roof)',
  },
  {
    sku: 'GEAR-UAV-THERMAL-107',
    name: 'Thermal UAV Survey Rig (Fractional Vault Share / Lease)',
    category: 'UAV_PART_107',
    retailCents: 245000,
    escrowUnlockCents: 95000,
    unlocksVertical: 'V-05 Solar Array & Commercial UAV Missions ($250–$600)',
  },
];
