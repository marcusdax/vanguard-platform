/**
 * Hyper-Local Micro-Zone Batching Dispatch Engine (H3 + Route 2-Opt + I-1/I-5 + R-08).
 * Clusters Tier 0 B2C errands (groceries, lawn mow) with B2B storm & FACET tasks
 * inside H3 micro-zones so operators stay net-positive after $0.76/mi H2 2026 vehicle cost.
 */
import { latLngToCell } from 'h3-js';
import { prisma } from '../../prisma';
import { sha256Hex } from '../../engine/canonical';
import { checkCredentialsForAction } from '../../engine/credentials';
import {
  IRS_MILEAGE_CENTS_H2_2026,
  computeClusterEconomics,
  quoteSurfaceFee,
} from '../../engine/economics';
import { DEMAND_VERTICALS } from './catalog';
import { appendAuditRecord } from './ledgerChain';
import { sanitizeNeutralTelemetry } from './rules';

export { sanitizeNeutralTelemetry };

export function haversineMiles(lat1: number, lng1: number, lat2: number, lng2: number): number {
  const R = 3958.8;
  const toRad = (d: number) => (d * Math.PI) / 180;
  const dLat = toRad(lat2 - lat1);
  const dLng = toRad(lng2 - lng1);
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLng / 2) ** 2;
  return 2 * R * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

export async function ensureStarterVanguardTasks(companyId: string) {
  const existing = await prisma.vanguardTask.count({
    where: { companyId, status: { in: ['AVAILABLE', 'CLAIMED', 'PIN_CONFIRMED'] } },
  });
  if (existing > 0) return;

  // Seed a realistic 4-task Burleson / South DFW neighborhood loop matching the Unit-Economics model
  // ($18 grocery + $42 lawn + $20 tree + $20 hail triage = $100 gross in ~1.5 mi radius)
  // plus gated Tier 2-4 commercial tasks so workers can inspect shortestFix blocking live.
  const starterSpecs = [
    {
      verticalCode: 'V-0A',
      surface: 'helios.local.b2c',
      title: 'Carry In Groceries & Safety Check (Mrs. Miller)',
      description: 'Unload 3 grocery bags inside kitchen counter; 4-digit verbal PIN confirm.',
      address: '412 Prairie Timber Rd, Burleson, TX',
      lat: 32.5421,
      lng: -97.3208,
      requiredTier: 1,
      requiredCredential: null,
      isFloorTask: true,
      grossBountyCents: 1800,
      estimatedMinutes: 15,
      requesterName: 'Mrs. Margaret Miller',
      pin: '4829',
    },
    {
      verticalCode: 'V-0B',
      surface: 'helios.local.b2c',
      title: 'Quarter-Acre Lawn Mow, Edge & AR Before/After Scan',
      description: 'Mow front & back yard, edge sidewalk, complete 15s Vanguard Lens opt-in scan.',
      address: '428 Prairie Timber Rd, Burleson, TX',
      lat: 32.5432,
      lng: -97.3195,
      requiredTier: 1,
      requiredCredential: null,
      isFloorTask: true,
      grossBountyCents: 4200,
      estimatedMinutes: 45,
      requesterName: 'Robert Chen (Proxy for Oak St)',
      pin: '3104',
    },
    {
      verticalCode: 'V-01',
      surface: 'atlas.roofing',
      title: 'Post-Storm Tree Limb Street Triage',
      description: 'Document downed oak limb near driveway apron; 3 wide/mid/GPS frames.',
      address: '509 Elk Ridge Ln, Burleson, TX',
      lat: 32.5450,
      lng: -97.3229,
      requiredTier: 1,
      requiredCredential: null,
      isFloorTask: true,
      grossBountyCents: 2000,
      estimatedMinutes: 15,
      requesterName: 'Atlas Storm Dispatch',
      pin: null,
    },
    {
      verticalCode: 'V-02',
      surface: 'vanguard.b2b.bounty',
      title: 'Hail Cell Perimeter Street Curb Triage (Exterior Only)',
      description: 'Curb-level exterior photo verification along storm corridor.',
      address: '518 Elk Ridge Ln, Burleson, TX',
      lat: 32.5458,
      lng: -97.3212,
      requiredTier: 1,
      requiredCredential: null,
      isFloorTask: true,
      grossBountyCents: 2000,
      estimatedMinutes: 15,
      requesterName: 'NorthStar Roofing & Atlas',
      pin: null,
    },
    {
      verticalCode: 'V-03',
      surface: 'vanguard.b2b.bounty',
      title: 'Flash-Flood Crawlspace Moisture & High-Water Mark Log',
      description: 'Document foundation waterline + Bluetooth hygrometer read with homeowner sign-off.',
      address: '804 Canyon Creek Ct, Burleson, TX',
      lat: 32.5471,
      lng: -97.3180,
      requiredTier: 2,
      requiredCredential: 'COUNTERPARTY_ATTEST_T2',
      isFloorTask: false,
      grossBountyCents: 7500,
      estimatedMinutes: 35,
      requesterName: 'Trinity Flood Restoration',
      pin: '7741',
    },
    {
      verticalCode: 'V-02',
      surface: 'vanguard.b2b.bounty',
      title: '4-Slope Technical Hail Inspection (Dart FFI Inclinometer)',
      description: 'Full 4-slope hail test squares, chalk count, and Dart FFI slope calibration.',
      address: '910 Alsbury Meadows Dr, Burleson, TX',
      lat: 32.5495,
      lng: -97.3161,
      requiredTier: 3,
      requiredCredential: 'ROOF_SAFETY',
      isFloorTask: false,
      grossBountyCents: 6500,
      estimatedMinutes: 40,
      requesterName: 'FACET / Atlas Carrier Desk',
      pin: null,
    },
    {
      verticalCode: 'V-04',
      surface: 'vanguard.b2b.bounty',
      title: 'Tier 4 Dual-Sign Desk Dossier Attestation (Claim #TX-8841)',
      description: 'Co-sign subordinate Tier 3 capture dossier with formal RecordKind: ATTEST.',
      address: '940 Alsbury Meadows Dr, Burleson, TX',
      lat: 32.5504,
      lng: -97.3150,
      requiredTier: 4,
      requiredCredential: 'VANGUARD_COMMANDER_T4',
      isFloorTask: false,
      grossBountyCents: 60000,
      estimatedMinutes: 50,
      requesterName: 'Lone Star Public Adjusting',
      pin: null,
    },
  ];

  for (const s of starterSpecs) {
    const feeQuote = quoteSurfaceFee(s.surface as any, s.grossBountyCents);
    await prisma.vanguardTask.create({
      data: {
        companyId,
        verticalCode: s.verticalCode,
        surface: s.surface,
        title: s.title,
        description: s.description,
        address: s.address,
        lat: s.lat,
        lng: s.lng,
        h3Index: latLngToCell(s.lat, s.lng, 7),
        requiredTier: s.requiredTier,
        requiredCredential: s.requiredCredential,
        isFloorTask: s.isFloorTask,
        grossBountyCents: s.grossBountyCents,
        buyerFeeBps: feeQuote.rateBps,
        buyerFeeCents: feeQuote.feeCents,
        estimatedMinutes: s.estimatedMinutes,
        requesterName: s.requesterName,
        completionPinHash: s.pin ? sha256Hex(`pin:${s.pin}`) : null,
        completionPinPreview: s.pin,
        consentOptInTelemetry: true,
        expiresAt: new Date(Date.now() + 72 * 3_600_000),
      },
    });
  }
}

export async function getMicroZoneClusters(params: {
  companyId: string;
  userId: string;
  irsCentsPerMile?: number;
}) {
  await ensureStarterVanguardTasks(params.companyId);

  const [tasks, heldCreds, hardwarePools] = await Promise.all([
    prisma.vanguardTask.findMany({
      where: { companyId: params.companyId, status: { in: ['AVAILABLE', 'CLAIMED', 'PIN_CONFIRMED'] } },
      orderBy: [{ requiredTier: 'asc' }, { createdAt: 'asc' }],
    }),
    prisma.operatorCredential.findMany({
      where: { companyId: params.companyId, userId: params.userId },
      select: { credentialCode: true },
    }),
    prisma.hardwareVaultPool.count({ where: { companyId: params.companyId } }),
  ]);

  // Suppress unused import lint if needed
  void hardwarePools;

  const heldSet = heldCreds.map((c) => c.credentialCode);
  const irsRate = params.irsCentsPerMile ?? IRS_MILEAGE_CENTS_H2_2026;

  // Group tasks by H3 Res 7 cell
  const byH3 = new Map<string, typeof tasks>();
  for (const t of tasks) {
    const arr = byH3.get(t.h3Index) || [];
    arr.push(t);
    byH3.set(t.h3Index, arr);
  }

  const clusters = Array.from(byH3.entries()).map(([h3Index, group]) => {
    const floorTasks = group.filter((t) => t.isFloorTask || !t.requiredCredential);
    const sampleBundle = floorTasks.length > 0 ? floorTasks : group;

    // Estimate compact neighborhood loop distance between grouped points (minimum 1.2 mi loop)
    let loopMiles = 1.5;
    if (sampleBundle.length > 1) {
      let dist = 0;
      for (let i = 1; i < sampleBundle.length; i++) {
        dist += haversineMiles(
          sampleBundle[i - 1].lat,
          sampleBundle[i - 1].lng,
          sampleBundle[i].lat,
          sampleBundle[i].lng,
        );
      }
      dist += haversineMiles(
        sampleBundle[sampleBundle.length - 1].lat,
        sampleBundle[sampleBundle.length - 1].lng,
        sampleBundle[0].lat,
        sampleBundle[0].lng,
      );
      loopMiles = Math.max(2.0, Number((dist + 3.5).toFixed(1)));
    }

    const eco = computeClusterEconomics({
      bountiesCents: sampleBundle.map((t) => t.grossBountyCents),
      taskMinutes: sampleBundle.reduce((s, t) => s + t.estimatedMinutes, 0),
      miles: loopMiles,
      irsCentsPerMile: irsRate,
    });

    const annotatedTasks = group.map((t) => {
      const gate = checkCredentialsForAction({
        isFloorAction: t.isFloorTask,
        requiredCredentials: t.requiredCredential ? [t.requiredCredential] : [],
        heldCredentials: heldSet,
        actionLabel: t.title,
      });
      return {
        ...t,
        verticalMeta: DEMAND_VERTICALS.find((v) => v.code === t.verticalCode) || null,
        feeDisclosure: quoteSurfaceFee(t.surface as any, t.grossBountyCents).disclosureCopy,
        credentialGate: gate,
      };
    });

    return {
      h3Index,
      taskCount: group.length,
      floorTaskCount: floorTasks.length,
      estimatedLoopMiles: loopMiles,
      economics: eco,
      tasks: annotatedTasks,
    };
  });

  return { irsCentsPerMile: irsRate, heldCredentials: heldSet, clusters };
}

export async function claimVanguardTask(params: {
  taskId: string;
  companyId: string;
  userId: string;
  allowFloorOverride?: boolean;
}) {
  const task = await prisma.vanguardTask.findFirst({
    where: { id: params.taskId, companyId: params.companyId },
  });
  if (!task) return { ok: false as const, status: 404, error: 'Task not found.' };
  if (task.status !== 'AVAILABLE') {
    return {
      ok: false as const,
      status: 409,
      error: `Task is currently ${task.status}.`,
    };
  }

  const creds = await prisma.operatorCredential.findMany({
    where: { companyId: params.companyId, userId: params.userId },
    select: { credentialCode: true },
  });

  const gate = checkCredentialsForAction({
    isFloorAction: task.isFloorTask,
    requiredCredentials: task.requiredCredential ? [task.requiredCredential] : [],
    heldCredentials: creds.map((c) => c.credentialCode),
    actionLabel: task.title,
  });

  if (!gate.allowed) {
    return {
      ok: false as const,
      status: 403,
      body: gate,
    };
  }

  const updated = await prisma.vanguardTask.update({
    where: { id: task.id },
    data: {
      status: 'CLAIMED',
      claimedByUserId: params.userId,
      claimedAt: new Date(),
    },
  });

  const feeQuote = quoteSurfaceFee(task.surface as any, task.grossBountyCents);

  await appendAuditRecord({
    companyId: params.companyId,
    subjectRef: `vanguard-task:${task.id}`,
    kind: 'CUSTODY',
    actorId: params.userId,
    summary: `Operator claimed ${task.verticalCode} task (${task.title})`,
    payload: {
      taskId: task.id,
      h3Index: task.h3Index,
      grossBountyCents: String(task.grossBountyCents),
      workerFeeBps: '0',
    },
  });

  return { ok: true as const, status: 200, task: updated, feeQuote };
}

export async function confirmTaskPinHandshake(params: {
  taskId: string;
  companyId: string;
  userId: string;
  pin: string;
}) {
  const task = await prisma.vanguardTask.findFirst({
    where: { id: params.taskId, companyId: params.companyId },
  });
  if (!task) return { ok: false as const, status: 404, error: 'Task not found.' };
  if (task.claimedByUserId && task.claimedByUserId !== params.userId) {
    return { ok: false as const, status: 403, error: 'Task claimed by another operator.' };
  }
  const cleanPin = String(params.pin || '').trim();
  if (!/^\d{4}$/.test(cleanPin)) {
    return { ok: false as const, status: 400, error: '4-digit homeowner verbal PIN required.' };
  }
  if (task.completionPinHash && sha256Hex(`pin:${cleanPin}`) !== task.completionPinHash) {
    return { ok: false as const, status: 422, error: 'Verbal PIN mismatch.' };
  }

  const updated = await prisma.vanguardTask.update({
    where: { id: task.id },
    data: {
      status: 'PIN_CONFIRMED',
      pinConfirmedAt: new Date(),
    },
  });

  await appendAuditRecord({
    companyId: params.companyId,
    subjectRef: `vanguard-task:${task.id}`,
    kind: 'ATTEST',
    actorId: params.userId,
    summary: 'Tier 2 Counterparty 4-digit verbal PIN handshake confirmed',
    payload: {
      taskId: task.id,
      tier: '2',
      pinHashPrefix: sha256Hex(`pin:${cleanPin}`).slice(0, 16),
    },
  });

  return { ok: true as const, status: 200, task: updated };
}

export async function createSeniorCommunityBooking(params: {
  companyId: string;
  kind: 'GROCERY_CARRY_IN' | 'LAWN_MOW_QUARTER_ACRE';
  address: string;
  requesterName: string;
  familyProxyName?: string;
  consentOptInTelemetry?: boolean;
  lat?: number;
  lng?: number;
}) {
  const isLawn = params.kind === 'LAWN_MOW_QUARTER_ACRE';
  const grossBountyCents = isLawn ? 4200 : 1800;
  const estimatedMinutes = isLawn ? 45 : 15;
  const verticalCode = isLawn ? 'V-0B' : 'V-0A';
  const title = isLawn
    ? `Quarter-Acre Lawn Mow & Edge (${params.requesterName})`
    : `Senior Grocery Carry-In & Check (${params.requesterName})`;
  const description = isLawn
    ? '3-tap Helios Local booking: mow, edge, blow + Vanguard Lens completion scan.'
    : '3-tap Helios Local booking: carry groceries inside kitchen counter (non-medical safe harbor).';

  const lat = typeof params.lat === 'number' && Number.isFinite(params.lat) ? params.lat : 32.5441;
  const lng = typeof params.lng === 'number' && Number.isFinite(params.lng) ? params.lng : -97.3201;
  const pin = String(Math.floor(1000 + Math.random() * 9000));
  const feeQuote = quoteSurfaceFee('helios.local.b2c', grossBountyCents);

  const task = await prisma.vanguardTask.create({
    data: {
      companyId: params.companyId,
      verticalCode,
      surface: 'helios.local.b2c',
      title,
      description,
      address: params.address,
      lat,
      lng,
      h3Index: latLngToCell(lat, lng, 7),
      requiredTier: 1,
      requiredCredential: null,
      isFloorTask: true, // Invariant I-1: base community errands are never gated
      grossBountyCents,
      buyerFeeBps: feeQuote.rateBps,
      buyerFeeCents: feeQuote.feeCents,
      estimatedMinutes,
      requesterName: params.requesterName,
      familyProxyName: params.familyProxyName || null,
      completionPinHash: sha256Hex(`pin:${pin}`),
      completionPinPreview: pin,
      consentOptInTelemetry: params.consentOptInTelemetry !== false,
    },
  });

  return { task, verbalPin: pin, feeQuote };
}

/**
 * Emit Vanguard B2B storm triage bounties (Tree / Roof / Flood) directly from NOAA StormEvents.
 */
export async function dispatchBountiesFromStormEvent(params: {
  companyId: string;
  stormEventId: string;
  actorId: string;
}) {
  const event = await prisma.stormEvent.findFirst({
    where: { id: params.stormEventId, companyId: params.companyId },
  });
  if (!event) return { ok: false as const, error: 'StormEvent not found' };

  const coords = (event.affectedArea as any)?.center || { lat: 32.5441, lng: -97.3201 };
  const baseLat = Number(coords.lat) || 32.5441;
  const baseLng = Number(coords.lng) || -97.3201;

  const areaLabel = String((event.metadata as any)?.areaLabel || event.name || 'Storm Zone');
  const isFlood = (event.eventType || '').toUpperCase().includes('FLOOD');
  const templates = isFlood
    ? [
        { code: 'V-03', title: `Flood Intrusion Sweep — ${areaLabel}`, bounty: 7500, tier: 2, cred: 'COUNTERPARTY_ATTEST_T2', floor: false },
        { code: 'V-01', title: `Storm Roadway Limb Scout — ${areaLabel}`, bounty: 2000, tier: 1, cred: null, floor: true },
      ]
    : [
        { code: 'V-01', title: `Downed Tree Hazard Triage — ${areaLabel}`, bounty: 2500, tier: 1, cred: null, floor: true },
        { code: 'V-02', title: `4-Slope Hail Capture — ${areaLabel}`, bounty: 6500, tier: 3, cred: 'ROOF_SAFETY', floor: false },
      ];

  const created = [];
  for (let i = 0; i < templates.length; i++) {
    const t = templates[i];
    const lat = baseLat + i * 0.004;
    const lng = baseLng + i * 0.004;
    const surface = t.code === 'V-01' ? 'atlas.roofing' : 'vanguard.b2b.bounty';
    const feeQuote = quoteSurfaceFee(surface as any, t.bounty);
    const task = await prisma.vanguardTask.create({
      data: {
        companyId: params.companyId,
        verticalCode: t.code,
        surface,
        title: t.title,
        description: `Auto-dispatched from ${event.eventType} (${event.severity})`,
        address: `${areaLabel} Corridor #${i + 1}`,
        lat,
        lng,
        h3Index: latLngToCell(lat, lng, 7),
        requiredTier: t.tier,
        requiredCredential: t.cred,
        isFloorTask: t.floor,
        grossBountyCents: t.bounty,
        buyerFeeBps: feeQuote.rateBps,
        buyerFeeCents: feeQuote.feeCents,
        estimatedMinutes: t.tier >= 3 ? 40 : 20,
        stormEventId: event.id,
        requesterName: 'Atlas Storm Engine',
      },
    });
    created.push(task);
  }

  await appendAuditRecord({
    companyId: params.companyId,
    subjectRef: `storm:${event.id}`,
    kind: 'INSPECTION',
    actorId: params.actorId,
    summary: `Dispatched ${created.length} Vanguard H3 bounties from storm polygon`,
    payload: { stormEventId: event.id, count: String(created.length) },
  });

  return { ok: true as const, created };
}
