/**
 * Integer minor-unit (cents) FeeQuote, IRS 2026 Mileage Economics & DePIN Half-Life Engine.
 * Enforces Invariant I-3 & Conformance Check C-09 / C-12 + Reconciliation R-16.
 */

export const IRS_MILEAGE_CENTS_H1_2026 = 72.5;
export const IRS_MILEAGE_CENTS_H2_2026 = 76.0;

export type MarketSurface =
  | 'helios.local.b2c'
  | 'vanguard.b2b.bounty'
  | 'atlas.roofing'
  | 'vanguard.marketplace'
  | 'supply.standard'
  | 'supply.founding';

export interface FeeRule {
  surface: MarketSurface;
  payer: 'REQUESTER' | 'BUYER' | 'CONTRACTOR' | 'SELLER';
  rateBps: number;
  workerFeeBps: 0;
  disclosureTemplate: string;
}

export const FEE_SCHEDULE_RULES: Record<MarketSurface, FeeRule> = {
  'helios.local.b2c': {
    surface: 'helios.local.b2c',
    payer: 'REQUESTER',
    rateBps: 500,
    workerFeeBps: 0,
    disclosureTemplate: 'Worker keeps 100% of task rate ($BOUNTY); buyer pays 5.00% ($FEE) community escrow fee.',
  },
  'vanguard.b2b.bounty': {
    surface: 'vanguard.b2b.bounty',
    payer: 'BUYER',
    rateBps: 1000,
    workerFeeBps: 0,
    disclosureTemplate: 'Worker receives 100% of stated bounty ($BOUNTY); buyer pays 10.00% ($FEE) escrow verification fee.',
  },
  'atlas.roofing': {
    surface: 'atlas.roofing',
    payer: 'CONTRACTOR',
    rateBps: 250,
    workerFeeBps: 0,
    disclosureTemplate: 'Includes 2.50% ($FEE) contractor-paid Atlas platform fee.',
  },
  'vanguard.marketplace': {
    surface: 'vanguard.marketplace',
    payer: 'REQUESTER',
    rateBps: 1500,
    workerFeeBps: 0,
    disclosureTemplate: 'Includes 15.00% ($FEE) requester-paid platform fee; zero fee deducted from operator payout.',
  },
  'supply.standard': {
    surface: 'supply.standard',
    payer: 'SELLER',
    rateBps: 200,
    workerFeeBps: 0,
    disclosureTemplate: 'Includes 2.00% ($FEE) seller-paid platform fee.',
  },
  'supply.founding': {
    surface: 'supply.founding',
    payer: 'SELLER',
    rateBps: 125,
    workerFeeBps: 0,
    disclosureTemplate: 'Includes 1.25% ($FEE) founding member seller-paid platform fee.',
  },
};

export const PRELAUNCH_STAGING_DISCLOSURE =
  'Pre-launch staging: live floor payouts activate upon Stripe Connect settlement switch-on.';

export function applyBasisPointsHalfUp(amountCents: number, bps: number): number {
  if (!Number.isFinite(amountCents) || amountCents < 0) return 0;
  return Math.round((Math.trunc(amountCents) * bps) / 10_000);
}

export function formatCentsUsd(cents: number): string {
  const neg = cents < 0;
  const abs = Math.abs(Math.round(cents));
  return `${neg ? '-' : ''}$${(abs / 100).toFixed(2)}`;
}

export function quoteSurfaceFee(surface: MarketSurface, baseCents: number) {
  const rule = FEE_SCHEDULE_RULES[surface];
  const feeCents = applyBasisPointsHalfUp(baseCents, rule.rateBps);
  const totalBuyerChargeCents =
    rule.payer === 'SELLER' ? Math.trunc(baseCents) : Math.trunc(baseCents) + feeCents;
  const workerReceivesCents =
    rule.payer === 'SELLER' ? Math.trunc(baseCents) - feeCents : Math.trunc(baseCents);

  return {
    surface,
    payer: rule.payer,
    rateBps: rule.rateBps,
    workerFeeBps: 0 as const,
    baseCents: Math.trunc(baseCents),
    feeCents,
    totalBuyerChargeCents,
    workerReceivesCents,
    disclosureCopy: rule.disclosureTemplate
      .replace('$BOUNTY', formatCentsUsd(baseCents))
      .replace('$FEE', formatCentsUsd(feeCents)),
    stagingDisclosure: PRELAUNCH_STAGING_DISCLOSURE,
  };
}

export function computeClusterEconomics(params: {
  bountiesCents: number[];
  taskMinutes: number;
  miles: number;
  irsCentsPerMile?: number;
}) {
  const irsRate = params.irsCentsPerMile ?? IRS_MILEAGE_CENTS_H2_2026;
  const grossCents = params.bountiesCents.reduce((sum, c) => sum + Math.trunc(c), 0);
  const vehicleCostCents = Math.round(Math.max(0, params.miles) * irsRate);
  const netCents = grossCents - vehicleCostCents;
  const driveMinutes = Math.round((Math.max(0, params.miles) / 20) * 60);
  const totalMinutes = Math.max(15, Math.round(params.taskMinutes) + driveMinutes);
  const netHourlyCents = Math.round((netCents * 60) / totalMinutes);
  return {
    grossCents,
    vehicleCostCents,
    netCents,
    totalMinutes,
    netHourlyCents,
    irsCentsPerMile: irsRate,
  };
}

/**
 * Economic Half-Life Pricing & Automated Refresh Multiplier.
 * 120-day exponential decay on enterprise telemetry queries ($16.00 peak -> $2.50 floor).
 * Stale parcels (>= 90d) auto-boost worker capture bounty up to 1.85x.
 */
export function computeEconomicHalfLife(ageDays: number) {
  const clampedAge = Math.max(0, Math.round(ageDays));
  const decayFactor = Math.pow(0.5, clampedAge / 120);
  const queryPriceCents = Math.max(250, Math.round(1600 * decayFactor));
  const workerRoyaltyCents = Math.round(queryPriceCents * 0.25);
  const homeownerRoyaltyCents = Math.round(queryPriceCents * 0.10);
  const treasuryBurnCents = queryPriceCents - workerRoyaltyCents - homeownerRoyaltyCents;
  const refreshMultiplier =
    clampedAge < 90
      ? 1.0
      : Number((1.0 + (Math.min(clampedAge, 365) - 90) / 275 * 0.85).toFixed(2));
  const freshnessState: 'PEAK' | 'MID_HALF_LIFE' | 'STALE_REFRESH_TRIGGERED' =
    clampedAge <= 60 ? 'PEAK' : clampedAge < 90 ? 'MID_HALF_LIFE' : 'STALE_REFRESH_TRIGGERED';

  return {
    ageDays: clampedAge,
    freshnessState,
    queryPriceCents,
    workerRoyaltyCents,
    homeownerRoyaltyCents,
    treasuryBurnCents,
    refreshMultiplier,
  };
}
