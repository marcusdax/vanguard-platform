/**
 * Academy Progression, Earn-to-Learn Micro-Escrow Vault, DePIN Economic Half-Life Royalties & Tier 4 Attestation.
 */
import { Prisma } from '@prisma/client';
import { latLngToCell } from 'h3-js';
import { prisma } from '../../prisma';
import { VANGUARD_CURRICULUM } from '../../engine/credentials';
import {
  PRELAUNCH_STAGING_DISCLOSURE,
  computeEconomicHalfLife,
  quoteSurfaceFee,
} from '../../engine/economics';
import { HARDWARE_GEAR_CATALOG } from './catalog';
import { appendAuditRecord, anchorMerkleTreeHead } from './ledgerChain';
import { sanitizeNeutralTelemetry } from './microZoneDispatch';

export async function getAcademyState(companyId: string, userId: string) {
  const [completed, credentials] = await Promise.all([
    prisma.vanguardModuleProgress.findMany({
      where: { companyId, userId },
      orderBy: { completedAt: 'desc' },
    }),
    prisma.operatorCredential.findMany({
      where: { companyId, userId },
      orderBy: { grantedAt: 'asc' },
    }),
  ]);

  const completedMap = new Map(completed.map((c) => [c.moduleCode, c]));
  const heldSet = new Set(credentials.map((c) => c.credentialCode));
  const highestTier = credentials.reduce((max, c) => Math.max(max, c.tierLevel), 1);

  return {
    highestTier,
    heldCredentials: Array.from(heldSet),
    completedCount: completed.length,
    totalModules: VANGUARD_CURRICULUM.length,
    modules: VANGUARD_CURRICULUM.map((m) => ({
      ...m,
      completed: completedMap.has(m.code),
      completedAt: completedMap.get(m.code)?.completedAt || null,
    })),
  };
}

export async function completeAcademyModule(params: {
  companyId: string;
  userId: string;
  moduleCode: string;
  licenseNumber?: string;
  instructorUserId?: string;
}) {
  const mod = VANGUARD_CURRICULUM.find(
    (m) => m.code.toUpperCase() === params.moduleCode.toUpperCase(),
  );
  if (!mod) return { ok: false as const, status: 404, error: `Unknown module ${params.moduleCode}` };

  // Reconciliation R-08: Tier 4 Commander Attestor requires state license reference
  if (mod.code === 'T4-01' && !params.licenseNumber?.trim()) {
    return {
      ok: false as const,
      status: 422,
      error: 'TX Ins. Code Ch. 4102 / Contractor License number is required to earn VANGUARD_COMMANDER_T4.',
    };
  }

  const progress = await prisma.vanguardModuleProgress.upsert({
    where: { userId_moduleCode: { userId: params.userId, moduleCode: mod.code } },
    create: {
      companyId: params.companyId,
      userId: params.userId,
      moduleCode: mod.code,
      instructorUserId: params.instructorUserId || null,
    },
    update: {},
  });

  let credential = null;
  if (mod.grantsCredential) {
    credential = await prisma.operatorCredential.upsert({
      where: {
        userId_credentialCode: { userId: params.userId, credentialCode: mod.grantsCredential },
      },
      create: {
        companyId: params.companyId,
        userId: params.userId,
        credentialCode: mod.grantsCredential,
        tierLevel: mod.tier,
        earnedViaModule: mod.code,
        instructorId: params.instructorUserId || null,
        licenseNumber: params.licenseNumber?.trim() || null,
      },
      update: {},
    });
  }

  await appendAuditRecord({
    companyId: params.companyId,
    subjectRef: `operator:${params.userId}`,
    kind: 'INSPECTION',
    actorId: params.userId,
    summary: `Completed Vanguard Academy module ${mod.code}: ${mod.title}`,
    payload: {
      moduleCode: mod.code,
      tier: String(mod.tier),
      credentialGranted: mod.grantsCredential || 'NONE',
    },
  });

  return { ok: true as const, status: 200, module: mod, progress, credential };
}

export async function getOrSeedEarnToLearnVault(companyId: string, userId: string) {
  let vault = await prisma.earnToLearnVault.findUnique({ where: { userId } });
  if (!vault) {
    vault = await prisma.earnToLearnVault.create({
      data: {
        companyId,
        userId,
        routingBps: 1000, // 10%
        balanceCents: 18500, // Starter demo accumulated floor credit
        totalContributedCents: 18500,
      },
    });
  }
  return {
    vault,
    catalog: HARDWARE_GEAR_CATALOG,
    stagingDisclosure: PRELAUNCH_STAGING_DISCLOSURE,
  };
}

export async function updateEarnToLearnRouting(params: {
  companyId: string;
  userId: string;
  routingBps: number;
}) {
  const clamped = Math.max(0, Math.min(2500, Math.round(params.routingBps)));
  await getOrSeedEarnToLearnVault(params.companyId, params.userId);
  const updated = await prisma.earnToLearnVault.update({
    where: { userId: params.userId },
    data: { routingBps: clamped },
  });
  return updated;
}

export async function redeemHardwareWithVault(params: {
  companyId: string;
  userId: string;
  sku: string;
}) {
  const item = HARDWARE_GEAR_CATALOG.find((g) => g.sku === params.sku);
  if (!item) return { ok: false as const, status: 404, error: 'Hardware kit not found.' };

  const { vault } = await getOrSeedEarnToLearnVault(params.companyId, params.userId);
  if (vault.balanceCents < item.escrowUnlockCents) {
    return {
      ok: false as const,
      status: 422,
      error: `Vault balance ($${(vault.balanceCents / 100).toFixed(2)}) is below $${(item.escrowUnlockCents / 100).toFixed(2)} escrow unlock threshold for ${item.name}.`,
    };
  }

  const currentGear = Array.isArray(vault.redeemedGear) ? (vault.redeemedGear as any[]) : [];
  const nextGear = [
    ...currentGear,
    { sku: item.sku, name: item.name, redeemedAt: new Date().toISOString(), amountCents: item.escrowUnlockCents },
  ];

  const updated = await prisma.earnToLearnVault.update({
    where: { userId: params.userId },
    data: {
      balanceCents: vault.balanceCents - item.escrowUnlockCents,
      redeemedGear: nextGear as Prisma.InputJsonValue,
    },
  });

  await prisma.fieldLedgerEntry.create({
    data: {
      companyId: params.companyId,
      userId: params.userId,
      kind: 'ADJUSTMENT',
      amountCents: -item.escrowUnlockCents,
      status: 'SETTLED_STAGING',
      description: `Earn-to-Learn Hardware Vault redemption: ${item.name}`,
      idempotencyKey: `etl-redeem:${params.userId}:${item.sku}:${Date.now()}`,
    },
  });

  return { ok: true as const, status: 200, vault: updated, unlockedItem: item };
}

/**
 * Complete task -> release 100% bounty to FieldLedgerEntry (0 bps worker fee),
 * optionally split Earn-to-Learn % + register opt-in DePIN SpatialAsset.
 */
export async function completeAndSettleVanguardTask(params: {
  taskId: string;
  companyId: string;
  userId: string;
  telemetryPayload: Record<string, unknown>;
}) {
  const task = await prisma.vanguardTask.findFirst({
    where: { id: params.taskId, companyId: params.companyId },
  });
  if (!task) return { ok: false as const, status: 404, error: 'Task not found.' };

  const heldCreds = await prisma.operatorCredential.findMany({
    where: { companyId: params.companyId, userId: params.userId },
  });
  const isCommander = heldCreds.some((c) => c.credentialCode === 'VANGUARD_COMMANDER_T4');
  const { sanitized, strippedKeys } = sanitizeNeutralTelemetry(params.telemetryPayload, isCommander);

  const settled = await prisma.vanguardTask.update({
    where: { id: task.id },
    data: {
      status: 'PAID',
      claimedByUserId: task.claimedByUserId || params.userId,
      telemetryPayload: sanitized as Prisma.InputJsonValue,
      completedAt: new Date(),
    },
  });

  const { vault } = await getOrSeedEarnToLearnVault(params.companyId, params.userId);
  const vaultCutCents = Math.round((task.grossBountyCents * vault.routingBps) / 10_000);
  const workerDirectCents = task.grossBountyCents - vaultCutCents;

  await prisma.fieldLedgerEntry.create({
    data: {
      companyId: params.companyId,
      userId: params.userId,
      kind: 'EARNING',
      amountCents: workerDirectCents,
      status: 'AVAILABLE',
      description: `${task.verticalCode} Bounty (${task.title}) · ${quoteSurfaceFee(task.surface as any, task.grossBountyCents).disclosureCopy}`,
      idempotencyKey: `vanguard-settle:${task.id}`,
    },
  });

  if (vaultCutCents > 0) {
    await prisma.earnToLearnVault.update({
      where: { userId: params.userId },
      data: {
        balanceCents: { increment: vaultCutCents },
        totalContributedCents: { increment: vaultCutCents },
      },
    });
  }

  let spatialAsset = null;
  if (task.consentOptInTelemetry) {
    spatialAsset = await prisma.spatialAsset.create({
      data: {
        companyId: params.companyId,
        h3Index: task.h3Index,
        address: task.address,
        lat: task.lat,
        lng: task.lng,
        capturerUserId: params.userId,
        homeownerLabel: task.requesterName || 'Opted-In Parcel Owner',
        homeownerOptIn: true,
      },
    });
  }

  const ledgerRow = await appendAuditRecord({
    companyId: params.companyId,
    subjectRef: `vanguard-task:${task.id}`,
    kind: isCommander && task.requiredTier === 4 ? 'ATTEST' : 'CAPTURE',
    actorId: params.userId,
    summary: `Settled ${task.verticalCode} capture (0 bps worker fee, stripped=${strippedKeys.length})`,
    payload: {
      taskId: task.id,
      grossBountyCents: String(task.grossBountyCents),
      vaultCutCents: String(vaultCutCents),
      ...sanitized,
    },
  });

  await anchorMerkleTreeHead(params.companyId);

  return {
    ok: true as const,
    status: 200,
    task: settled,
    workerDirectCents,
    vaultCutCents,
    strippedNeutralityFields: strippedKeys,
    spatialAsset,
    ledgerRow,
    stagingDisclosure: PRELAUNCH_STAGING_DISCLOSURE,
  };
}

export async function ensureStarterDepinData(companyId: string, userId: string) {
  const [assetCount, poolCount] = await Promise.all([
    prisma.spatialAsset.count({ where: { companyId } }),
    prisma.hardwareVaultPool.count({ where: { companyId } }),
  ]);

  if (assetCount === 0) {
    const samples = [
      { address: '428 Prairie Timber Rd, Burleson, TX', lat: 32.5432, lng: -97.3195, ageDays: 14, queries: 3 },
      { address: '518 Elk Ridge Ln, Burleson, TX', lat: 32.5458, lng: -97.3212, ageDays: 112, queries: 5 },
      { address: '910 Alsbury Meadows Dr, Burleson, TX', lat: 32.5495, lng: -97.3161, ageDays: 245, queries: 1 },
    ];
    for (const s of samples) {
      await prisma.spatialAsset.create({
        data: {
          companyId,
          h3Index: latLngToCell(s.lat, s.lng, 7),
          address: s.address,
          lat: s.lat,
          lng: s.lng,
          capturerUserId: userId,
          homeownerLabel: 'Opted-In Neighbor Parcel',
          homeownerOptIn: true,
          capturedAt: new Date(Date.now() - s.ageDays * 86_400_000),
          queryCount: s.queries,
          totalRoyaltiesCents: s.queries * 380,
        },
      });
    }
  }

  if (poolCount === 0) {
    for (const item of HARDWARE_GEAR_CATALOG) {
      await prisma.hardwareVaultPool.create({
        data: {
          companyId,
          name: item.name,
          category: item.category,
          unitCostCents: item.retailCents,
          totalShares: 100,
          fundedShares: 42,
          yieldShareBps: 800,
          totalYieldEarnedCents: 6400,
        },
      });
    }
  }
}

export async function getDepinPortfolio(companyId: string, userId: string) {
  await ensureStarterDepinData(companyId, userId);

  const [assets, pools, myShares, latestHead] = await Promise.all([
    prisma.spatialAsset.findMany({
      where: { companyId },
      orderBy: { capturedAt: 'desc' },
      include: { royalties: { orderBy: { createdAt: 'desc' }, take: 3 } },
    }),
    prisma.hardwareVaultPool.findMany({
      where: { companyId },
      orderBy: { createdAt: 'asc' },
    }),
    prisma.hardwareVaultShare.findMany({ where: { companyId, userId } }),
    prisma.merkleTreeHead.findFirst({ where: { companyId }, orderBy: { anchoredAt: 'desc' } }),
  ]);

  const enrichedAssets = assets.map((a) => {
    const ageDays = Math.floor((Date.now() - a.capturedAt.getTime()) / 86_400_000);
    return {
      ...a,
      halfLife: computeEconomicHalfLife(ageDays),
    };
  });

  const shareByPool = new Map(myShares.map((s) => [s.poolId, s]));
  const enrichedPools = pools.map((p) => ({
    ...p,
    myShares: shareByPool.get(p.id)?.shares || 0,
    myYieldCents: shareByPool.get(p.id)?.yieldEarnedCents || 0,
  }));

  return {
    assets: enrichedAssets,
    hardwarePools: enrichedPools,
    latestMerkleHead: latestHead,
    stagingDisclosure: PRELAUNCH_STAGING_DISCLOSURE,
  };
}

export async function licenseSpatialAssetQuery(params: {
  assetId: string;
  companyId: string;
  buyerName: string;
}) {
  const asset = await prisma.spatialAsset.findFirst({
    where: { id: params.assetId, companyId: params.companyId },
  });
  if (!asset) return { ok: false as const, status: 404, error: 'Spatial asset not found.' };

  const ageDays = Math.floor((Date.now() - asset.capturedAt.getTime()) / 86_400_000);
  const hl = computeEconomicHalfLife(ageDays);

  const royalty = await prisma.dataRoyaltyEvent.create({
    data: {
      assetId: asset.id,
      companyId: params.companyId,
      buyerName: params.buyerName || 'FACET Underwriting API',
      queryPriceCents: hl.queryPriceCents,
      workerRoyaltyCents: hl.workerRoyaltyCents,
      homeownerRoyaltyCents: asset.homeownerOptIn ? hl.homeownerRoyaltyCents : 0,
      treasuryBurnCents:
        hl.treasuryBurnCents + (asset.homeownerOptIn ? 0 : hl.homeownerRoyaltyCents),
      ageDaysAtQuery: ageDays,
    },
  });

  await prisma.spatialAsset.update({
    where: { id: asset.id },
    data: {
      queryCount: { increment: 1 },
      totalRoyaltiesCents: { increment: hl.workerRoyaltyCents },
      lastQueriedAt: new Date(),
    },
  });

  await prisma.fieldLedgerEntry.create({
    data: {
      companyId: params.companyId,
      userId: asset.capturerUserId,
      kind: 'EARNING',
      amountCents: hl.workerRoyaltyCents,
      status: 'AVAILABLE',
      description: `DePIN Spatial Data Royalty (${params.buyerName}) · ${asset.address}`,
      idempotencyKey: `depin-royalty:${royalty.id}`,
    },
  });

  await appendAuditRecord({
    companyId: params.companyId,
    subjectRef: `spatial:${asset.id}`,
    kind: 'PAYMENT',
    actorId: asset.capturerUserId,
    summary: `Licensed spatial asset (${ageDays}d half-life) to ${params.buyerName}`,
    payload: {
      assetId: asset.id,
      queryPriceCents: String(hl.queryPriceCents),
      workerRoyaltyCents: String(hl.workerRoyaltyCents),
      treasuryBurnCents: String(hl.treasuryBurnCents),
      respaCompliance: 'FIXED_FAIR_MARKET_DATA_FEE',
    },
  });

  return { ok: true as const, status: 201, royalty, halfLife: hl };
}
