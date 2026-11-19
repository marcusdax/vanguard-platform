export interface VanguardTierSummary {
  tier: 1 | 2 | 3 | 4;
  roleTitle: string;
  subtitle: string;
  payoutHeadline: string;
  avgPayoutCents: number;
  legalBoundary: string;
  highlights: string[];
}

export interface VanguardModuleItem {
  code: string;
  tier: 1 | 2 | 3 | 4;
  title: string;
  minutes: number;
  required: boolean;
  grantsCredential: string | null;
  unlocksSummary: string;
  neutralityBoundary: string;
  completed?: boolean;
  completedAt?: string | null;
}

export interface VerticalDefinition {
  code: string;
  tierGroup: 'T0' | 'T1' | 'T2' | 'T3';
  surface: string;
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

export interface HardwareGearCatalogItem {
  sku: string;
  name: string;
  category: string;
  retailCents: number;
  escrowUnlockCents: number;
  unlocksVertical: string;
}

export interface FeeRuleItem {
  surface: string;
  payer: string;
  rateBps: number;
  workerFeeBps: number;
  disclosureTemplate: string;
}

export interface ShortestFixPayload {
  moduleId: string;
  lessonTitle: string;
  minutes: number;
  costCents: number;
}

export interface CredentialGateBlock {
  allowed: false;
  code: 'CREDENTIAL_REQUIRED';
  invariant: 'I-5';
  missingCredential: string;
  reason: string;
  shortestFix: ShortestFixPayload;
}

export interface VanguardTaskItem {
  id: string;
  verticalCode: string;
  surface: string;
  h3Index: string;
  title: string;
  description: string;
  address: string;
  requiredTier: number;
  requiredCredential: string | null;
  isFloorTask: boolean;
  grossBountyCents: number;
  estimatedMinutes: number;
  status: string;
  pin?: string | null;
  requesterName?: string | null;
  feeDisclosure: string;
  credentialGate: { allowed: true } | CredentialGateBlock;
}

export interface MicroZoneCluster {
  h3Index: string;
  taskCount: number;
  floorTaskCount: number;
  estimatedLoopMiles: number;
  economics: {
    grossCents: number;
    vehicleCostCents: number;
    netCents: number;
    netHourlyCents: number;
    totalMinutes: number;
  };
  tasks: VanguardTaskItem[];
}

export interface CatalogResponse {
  tiers: VanguardTierSummary[];
  modules: VanguardModuleItem[];
  verticals: VerticalDefinition[];
  hardwareCatalog: HardwareGearCatalogItem[];
  feeSchedule: FeeRuleItem[];
  irsMileageCents: { h1_2026: number; h2_2026: number };
  stagingDisclosure: string;
}

export interface AcademyResponse {
  highestTier: number;
  heldCredentials: string[];
  completedCount: number;
  totalModules: number;
  modules: VanguardModuleItem[];
}

export interface SpatialAssetItem {
  id: string;
  h3Index: string;
  address: string;
  queryCount: number;
  totalRoyaltiesCents: number;
  homeownerOptIn: boolean;
  halfLife: {
    ageDays: number;
    queryPriceCents: number;
    workerRoyaltyCents: number;
    homeownerRoyaltyCents: number;
    treasuryBurnCents: number;
    freshnessState: string;
    refreshMultiplier: number;
  };
}

export interface HardwarePoolItem {
  id: string;
  name: string;
  hardwareSku: string;
  targetCents: number;
  totalShares: number;
  fundedShares: number;
  myShares: number;
  myYieldCents: number;
}
