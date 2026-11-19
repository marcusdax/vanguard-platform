/**
 * Field Hub API — operator-only Sprint 2.
 *
 * Covers:
 *  - Operator onboarding profile (W-9, independent-operator agreement SHA-256, tools, radius, pairing)
 *  - Ground offers (ranked by operator-chosen sort, radius-filtered)
 *  - Operator queue (order, status lifecycle, overlap advisories, no-penalty skip/remove)
 *  - Consent-gated evidence packet sealing (SHA-256 hash chain from genesis) + Vault verify/export
 *  - Integer-cent ledger & fee-disclosed payouts (separate from evidence state)
 */
import { Router, Response, NextFunction } from 'express';
import { Prisma } from '@prisma/client';
import { AuthRequest } from '../middleware/auth';
import { companyScope } from '../middleware/company';
import { prisma } from '../prisma';
import {
  buildOffer,
  optimizeQueueRoute,
  rankOffers,
  parseLatLng,
  windowConflicts,
  OfferSort,
} from '../services/field/queue';
import {
  buildPacketRecords,
  canAdvanceQueueStatus,
  canonicalJson,
  computeBalances,
  quotePayout,
  sha256Hex,
  verifyPacketChain,
  type ChainRecord,
  type PayoutMode,
  type QueueStatus,
} from '../services/field/evidence';
import {
  anchorToTransparencyLog,
  buildC2PAManifest,
} from '../services/field/provenance';

const router = Router();
router.use(companyScope);

const SORTS: OfferSort[] = ['pay', 'distance', 'deadline'];
const W9_STATES = ['UNSUBMITTED', 'SUBMITTED', 'VERIFIED'];
const ALLOWED_TOOLS = ['PHONE_CAMERA', 'DRONE_PART_107', 'LIDAR_RIG', 'THERMAL_CAM', 'RPZ_GAUGE'];

// ── Active-workers fleet view (manager / owner) ───────────────────────────

router.get('/active-workers', async (req: AuthRequest, res: Response, next: NextFunction) => {
  try {
    if (!['OWNER', 'MANAGER'].includes(req.user!.role)) {
      return res.status(403).json({ error: 'Manager or owner access required' });
    }
    const items = await prisma.fieldQueueItem.findMany({
      where: {
        companyId: req.user!.companyId,
        status: { in: ['IN_PROGRESS', 'SCHEDULED', 'QUEUED'] },
      },
      select: { id: true, userId: true, jobId: true, status: true, updatedAt: true },
      orderBy: { updatedAt: 'desc' },
    });
    if (!items.length) return res.json({ data: [] });

    const userIds = [...new Set(items.map((i) => i.userId))];
    const jobIds = [...new Set(items.map((i) => i.jobId))];

    const [users, jobs] = await Promise.all([
      prisma.user.findMany({
        where: { id: { in: userIds }, companyId: req.user!.companyId },
        select: { id: true, firstName: true, lastName: true, avatarUrl: true },
      }),
      prisma.job.findMany({
        where: { id: { in: jobIds }, companyId: req.user!.companyId },
        select: { id: true, name: true, propertyAddress: true, propertyLatLng: true },
      }),
    ]);

    const userById = new Map(users.map((u) => [u.id, u]));
    const jobById = new Map(jobs.map((j) => [j.id, j]));

    // One entry per worker — most recent item first (already ordered by updatedAt desc)
    const seen = new Set<string>();
    const workers = items.flatMap((item) => {
      if (seen.has(item.userId)) return [];
      seen.add(item.userId);
      const user = userById.get(item.userId);
      const job = jobById.get(item.jobId);
      const latLng = parseLatLng(job?.propertyLatLng);
      return [{
        userId: item.userId,
        name: user ? `${user.firstName} ${user.lastName}` : 'Worker',
        avatarUrl: user?.avatarUrl ?? null,
        queueStatus: item.status,
        lastActiveAt: item.updatedAt,
        job: job
          ? { id: job.id, name: job.name, propertyAddress: job.propertyAddress, latLng }
          : null,
      }];
    });

    return res.json({ data: workers });
  } catch (err) {
    return next(err);
  }
});

// ── Operator Profile & 5-Step Onboarding ─────────────────────────────────

router.get('/profile', async (req: AuthRequest, res: Response, next: NextFunction) => {
  try {
    let profile = await prisma.fieldOperatorProfile.findUnique({
      where: { userId: req.user!.id },
    });
    if (!profile) {
      profile = await prisma.fieldOperatorProfile.create({
        data: {
          companyId: req.user!.companyId,
          userId: req.user!.id,
          tools: ['PHONE_CAMERA'],
          serviceRadiusKm: 80,
        },
      });
    }
    res.json({ data: profile });
  } catch (err) {
    next(err);
  }
});

router.put('/profile', async (req: AuthRequest, res: Response, next: NextFunction) => {
  try {
    const body = (req.body ?? {}) as Record<string, unknown>;
    const existing = await prisma.fieldOperatorProfile.findUnique({
      where: { userId: req.user!.id },
    });

    const data: Record<string, unknown> = {
      companyId: req.user!.companyId,
      userId: req.user!.id,
    };

    if (typeof body.w9Status === 'string' && W9_STATES.includes(body.w9Status)) {
      data.w9Status = body.w9Status;
    }
    if (Array.isArray(body.tools)) {
      data.tools = body.tools.filter((t): t is string => typeof t === 'string' && ALLOWED_TOOLS.includes(t));
    }
    if (body.serviceRadiusKm != null) {
      const r = Math.round(Number(body.serviceRadiusKm));
      if (!Number.isFinite(r) || r < 5 || r > 200) {
        return res.status(400).json({ error: 'serviceRadiusKm must be between 5 and 200' });
      }
      data.serviceRadiusKm = r;
    }
    if (body.signAgreement === true && !existing?.agreementHash) {
      const signedAt = new Date();
      data.agreementSignedAt = signedAt;
      data.agreementHash = sha256Hex(
        canonicalJson({
          userId: req.user!.id,
          companyId: req.user!.companyId,
          signedAt: signedAt.toISOString(),
          terms: 'INDEPENDENT_OPERATOR_NO_EXCLUSIVITY_NO_PENALTY_V1',
        }),
      );
    }
    if (body.generatePairing === true) {
      const code = `FW-${sha256Hex(`${req.user!.id}:${Date.now()}`).slice(0, 6).toUpperCase()}`;
      data.pairingCode = code;
    }
    if (body.confirmPairing === true) {
      data.devicePaired = true;
    }

    const profile = await prisma.fieldOperatorProfile.upsert({
      where: { userId: req.user!.id },
      create: data as Parameters<typeof prisma.fieldOperatorProfile.create>[0]['data'],
      update: data,
    });
    res.json({ data: profile });
  } catch (err) {
    next(err);
  }
});

// ── Ground Offers ────────────────────────────────────────────────────────

router.get('/offers', async (req: AuthRequest, res: Response, next: NextFunction) => {
  try {
    const { lat, lng, radiusKm, sort } = req.query;
    const origin = parseLatLng({ lat, lng });
    const sortKey: OfferSort = SORTS.includes(sort as OfferSort) ? (sort as OfferSort) : 'pay';
    const radius = radiusKm != null && radiusKm !== '' ? Number(radiusKm) : undefined;
    if (radius != null && (!Number.isFinite(radius) || radius <= 0)) {
      return res.status(400).json({ error: 'radiusKm must be a positive number' });
    }

    const queued = await prisma.fieldQueueItem.findMany({
      where: { userId: req.user!.id },
      select: { jobId: true },
    });
    const jobs = await prisma.job.findMany({
      where: {
        companyId: req.user!.companyId,
        status: 'ACTIVE',
        id: { notIn: queued.map((q) => q.jobId) },
      },
      orderBy: { createdAt: 'desc' },
      take: 200,
    });

    const offers = jobs.map((j) =>
      buildOffer(
        {
          id: j.id,
          name: j.name,
          propertyAddress: j.propertyAddress,
          latLng: parseLatLng(j.propertyLatLng),
          budget: j.budget,
          endDate: j.endDate,
          phase: j.phase,
        },
        origin,
      ),
    );
    res.json({ data: rankOffers(offers, sortKey, radius) });
  } catch (err) {
    next(err);
  }
});

// ── Operator Queue & Lifecycle ───────────────────────────────────────────

router.get('/queue', async (req: AuthRequest, res: Response, next: NextFunction) => {
  try {
    const items = await prisma.fieldQueueItem.findMany({
      where: { userId: req.user!.id },
      orderBy: [{ position: 'asc' }, { createdAt: 'asc' }],
    });
    const jobs = await prisma.job.findMany({
      where: { id: { in: items.map((i) => i.jobId) }, companyId: req.user!.companyId },
    });
    const byId = new Map(jobs.map((j) => [j.id, j]));
    const hydrated = items
      .filter((i) => byId.has(i.jobId))
      .map((i) => {
        const j = byId.get(i.jobId)!;
        return {
          ...i,
          job: {
            id: j.id,
            name: j.name,
            propertyAddress: j.propertyAddress,
            latLng: parseLatLng(j.propertyLatLng),
            budget: j.budget,
            startDate: j.startDate,
            endDate: j.endDate,
          },
        };
      });

    const conflicts = windowConflicts(
      hydrated.map((h) => ({ id: h.id, start: h.job.startDate, end: h.job.endDate })),
    );
    res.json({ data: hydrated, conflicts });
  } catch (err) {
    next(err);
  }
});

router.post('/queue', async (req: AuthRequest, res: Response, next: NextFunction) => {
  try {
    const { jobId, proposedPrice } = req.body ?? {};
    if (typeof jobId !== 'string' || !jobId) return res.status(400).json({ error: 'jobId required' });
    if (proposedPrice != null && (!Number.isFinite(Number(proposedPrice)) || Number(proposedPrice) < 0)) {
      return res.status(400).json({ error: 'proposedPrice must be a non-negative number' });
    }
    const job = await prisma.job.findFirst({
      where: { id: jobId, companyId: req.user!.companyId, status: 'ACTIVE' },
    });
    if (!job) return res.status(404).json({ error: 'Job not available' });

    const existing = await prisma.fieldQueueItem.findUnique({
      where: { userId_jobId: { userId: req.user!.id, jobId } },
    });
    if (existing) return res.status(409).json({ error: 'Already in queue' });

    const last = await prisma.fieldQueueItem.findFirst({
      where: { userId: req.user!.id },
      orderBy: { position: 'desc' },
    });
    const item = await prisma.fieldQueueItem.create({
      data: {
        companyId: req.user!.companyId,
        userId: req.user!.id,
        jobId,
        position: (last?.position ?? -1) + 1,
        proposedPrice: proposedPrice != null ? Number(proposedPrice) : null,
      },
    });
    res.status(201).json({ data: item });
  } catch (err) {
    next(err);
  }
});

router.put('/queue/order', async (req: AuthRequest, res: Response, next: NextFunction) => {
  try {
    const ids = req.body?.ids;
    if (!Array.isArray(ids) || ids.some((i) => typeof i !== 'string')) {
      return res.status(400).json({ error: 'ids must be string[]' });
    }
    const mine = await prisma.fieldQueueItem.findMany({
      where: { userId: req.user!.id, id: { in: ids } },
      select: { id: true },
    });
    const owned = new Set(mine.map((m) => m.id));
    const ordered = ids.filter((i: string) => owned.has(i));
    await prisma.$transaction(
      ordered.map((id: string, position: number) =>
        prisma.fieldQueueItem.update({ where: { id }, data: { position } }),
      ),
    );
    res.json({ ok: true, count: ordered.length });
  } catch (err) {
    next(err);
  }
});

router.post('/queue/optimize', async (req: AuthRequest, res: Response, next: NextFunction) => {
  try {
    const origin = parseLatLng({ lat: req.body?.lat, lng: req.body?.lng });
    const items = await prisma.fieldQueueItem.findMany({
      where: { userId: req.user!.id },
      orderBy: [{ position: 'asc' }, { createdAt: 'asc' }],
    });
    const jobIds = items.map((i) => i.jobId);
    const jobs = jobIds.length
      ? await prisma.job.findMany({
          where: { id: { in: jobIds }, companyId: req.companyId! },
          select: { id: true, propertyLatLng: true },
        })
      : [];
    const jobById = new Map<string, (typeof jobs)[number]>(jobs.map((j) => [j.id, j] as const));

    const routeResult = optimizeQueueRoute(
      items.map((item) => ({
        id: item.id,
        latLng: parseLatLng(jobById.get(item.jobId)?.propertyLatLng),
      })),
      origin
    );

    await prisma.$transaction(
      routeResult.orderedIds.map((id, position) =>
        prisma.fieldQueueItem.update({ where: { id }, data: { position } }),
      ),
    );

    res.json({
      ok: true,
      ...routeResult,
    });
  } catch (err) {
    next(err);
  }
});

router.patch('/queue/:id/status', async (req: AuthRequest, res: Response, next: NextFunction) => {
  try {
    const nextStatus = req.body?.status as QueueStatus | undefined;
    const item = await prisma.fieldQueueItem.findFirst({
      where: { id: req.params.id, userId: req.user!.id },
    });
    if (!item) return res.status(404).json({ error: 'Queue item not found' });
    if (!nextStatus || !canAdvanceQueueStatus(item.status as QueueStatus, nextStatus)) {
      return res.status(409).json({
        error: `Cannot transition from ${item.status} to ${String(nextStatus)}`,
      });
    }
    const updated = await prisma.fieldQueueItem.update({
      where: { id: item.id },
      data: { status: nextStatus },
    });
    res.json({ data: updated });
  } catch (err) {
    next(err);
  }
});

router.delete('/queue/:id', async (req: AuthRequest, res: Response, next: NextFunction) => {
  try {
    const result = await prisma.fieldQueueItem.deleteMany({
      where: { id: req.params.id, userId: req.user!.id },
    });
    if (result.count === 0) return res.status(404).json({ error: 'Not found' });
    res.status(204).end();
  } catch (err) {
    next(err);
  }
});

// ── Consent-Gated Evidence Sealing & Vault ───────────────────────────────

router.post('/queue/:id/seal', async (req: AuthRequest, res: Response, next: NextFunction) => {
  try {
    const {
      consentName,
      consentRole = 'Property owner',
      consentScope = 'Exterior roof & site surfaces. Not the interior. Not faces.',
      consentMethod = 'IN_APP_SIGNATURE',
      measurementMeters = 0.9,
      referenceLabel = 'Shingle course (90 cm)',
      notes = '',
    } = (req.body ?? {}) as Record<string, unknown>;

    if (typeof consentName !== 'string' || !consentName.trim()) {
      return res.status(400).json({ error: 'Consent blocked: consentName is required before sealing' });
    }
    const meters = Number(measurementMeters);
    if (!Number.isFinite(meters) || meters <= 0) {
      return res.status(400).json({ error: 'Calibrated measurementMeters must be positive' });
    }

    const item = await prisma.fieldQueueItem.findFirst({
      where: { id: req.params.id, userId: req.user!.id },
    });
    if (!item) return res.status(404).json({ error: 'Queue item not found' });

    const job = await prisma.job.findFirst({
      where: { id: item.jobId, companyId: req.user!.companyId },
    });
    if (!job) return res.status(404).json({ error: 'Associated job not found' });

    const atIso = new Date().toISOString();
    const consentHash = sha256Hex(
      canonicalJson({
        name: consentName.trim(),
        role: String(consentRole),
        scope: String(consentScope),
        method: String(consentMethod),
        at: atIso,
      }),
    );

    const records = buildPacketRecords({
      actorId: req.user!.id,
      jobId: job.id,
      consentName: consentName.trim(),
      consentHash,
      measurementMeters: meters,
      referenceLabel: String(referenceLabel),
      notes: String(notes ?? ''),
      atIso,
    });

    const headHash = records[records.length - 1].hash;
    const merkleLeaf = sha256Hex(`leaf:${headHash}`);
    const code = `FW-${sha256Hex(`${job.id}:${atIso}`).slice(0, 4).toUpperCase()}`;

    const c2paManifest = buildC2PAManifest({
      packetCode: code,
      jobId: job.id,
      actorId: req.user!.id,
      consentName: consentName.trim(),
      consentHash,
      headHash,
      merkleLeaf,
      measurementMeters: meters,
      demo: true,
      atIso,
      sensorWitness: {
        pitchDeg: typeof req.body?.pitchDeg === 'number' ? req.body.pitchDeg : undefined,
        baroHpa: typeof req.body?.baroHpa === 'number' ? req.body.baroHpa : undefined,
        wifiBssidDigest: typeof req.body?.wifiBssidDigest === 'string' ? req.body.wifiBssidDigest : undefined,
      },
    });
    const rekorAnchor = await anchorToTransparencyLog({
      merkleLeaf,
      headHash,
      demo: true,
      atIso,
    });

    // Integer-cent earning derived from operator's proposedPrice or job budget (capped to sample field fee $125-$450 or job budget)
    const rawDollars = item.proposedPrice ?? (job.budget ? Math.min(450, Math.max(95, Math.round(job.budget * 0.015))) : 175);
    const amountCents = Math.max(2500, Math.round(rawDollars * 100));
    const idemKey = `seal:${req.user!.id}:${item.id}`;

    const result = await prisma.$transaction(async (tx) => {
      // Serialize ledger & packet seal writes per user via Postgres advisory xact lock
      await tx.$executeRaw`SELECT pg_advisory_xact_lock(hashtext(${req.user!.id}))`;

      const packet = await tx.fieldEvidencePacket.create({
        data: {
          companyId: req.user!.companyId,
          userId: req.user!.id,
          jobId: job.id,
          queueItemId: item.id,
          code,
          title: job.name,
          consentName: consentName.trim(),
          consentRole: String(consentRole),
          consentScope: String(consentScope),
          consentMethod: String(consentMethod),
          consentHash,
          consentAt: new Date(atIso),
          recordsJson: records as unknown as Prisma.InputJsonValue,
          headHash,
          merkleLeaf,
          c2paManifestJson: c2paManifest as unknown as Prisma.InputJsonValue,
          rekorAnchorJson: rekorAnchor as unknown as Prisma.InputJsonValue,
          tier: 5,
          sealState: 'EARNED',
          demo: true,
        },
      });

      await tx.fieldQueueItem.update({
        where: { id: item.id },
        data: { status: 'SEALED' },
      });

      const existingLedger = await tx.fieldLedgerEntry.findUnique({
        where: { idempotencyKey: idemKey },
      });
      if (!existingLedger) {
        await tx.fieldLedgerEntry.create({
          data: {
            companyId: req.user!.companyId,
            userId: req.user!.id,
            jobId: job.id,
            packetId: packet.id,
            kind: 'EARNING',
            amountCents,
            status: 'AVAILABLE',
            description: `${code} · ${job.name}`,
            idempotencyKey: idemKey,
          },
        });
      }
      return packet;
    });

    res.status(201).json({ data: result });
  } catch (err) {
    next(err);
  }
});

router.get('/packets', async (req: AuthRequest, res: Response, next: NextFunction) => {
  try {
    const packets = await prisma.fieldEvidencePacket.findMany({
      where: { userId: req.user!.id },
      orderBy: { sealedAt: 'desc' },
    });
    res.json({ data: packets });
  } catch (err) {
    next(err);
  }
});

router.post('/packets/:id/verify', async (req: AuthRequest, res: Response, next: NextFunction) => {
  try {
    const packet = await prisma.fieldEvidencePacket.findFirst({
      where: { id: req.params.id, userId: req.user!.id },
    });
    if (!packet) return res.status(404).json({ error: 'Packet not found' });
    const records = (packet.recordsJson as unknown as ChainRecord[]) ?? [];
    const verification = verifyPacketChain(records);
    res.json({
      data: {
        ...verification,
        packetId: packet.id,
        code: packet.code,
        merkleLeaf: packet.merkleLeaf,
        verifiedAt: new Date().toISOString(),
      },
    });
  } catch (err) {
    next(err);
  }
});

router.get('/packets/:id/export', async (req: AuthRequest, res: Response, next: NextFunction) => {
  try {
    const packet = await prisma.fieldEvidencePacket.findFirst({
      where: { id: req.params.id, userId: req.user!.id },
    });
    if (!packet) return res.status(404).json({ error: 'Packet not found' });
    const records = (packet.recordsJson as unknown as ChainRecord[]) ?? [];
    const verification = verifyPacketChain(records);
    const custodyText = [
      `VANGUARD FIELD EVIDENCE PACKET ${packet.code}`,
      packet.demo ? 'SAMPLE — excluded from public transparency log' : 'LIVE PACKET',
      `Title: ${packet.title}`,
      `Consent: ${packet.consentName} (${packet.consentRole}) via ${packet.consentMethod}`,
      `Consent Scope: ${packet.consentScope}`,
      `Consent SHA-256: ${packet.consentHash}`,
      `Head Hash: ${packet.headHash}`,
      `Chain Check: ${verification.detail}`,
    ].join('\n');

    res.json({
      data: {
        fileName: `${packet.code.toLowerCase()}-custody.json`,
        canonicalJson: canonicalJson({
          code: packet.code,
          title: packet.title,
          demo: packet.demo,
          consentHash: packet.consentHash,
          headHash: packet.headHash,
          c2paManifest: packet.c2paManifestJson,
          rekorAnchor: packet.rekorAnchorJson,
          records,
        }),
        custodyText,
      },
    });
  } catch (err) {
    next(err);
  }
});

// ── Integer-Cent Ledger & Fee-Disclosed Payouts ──────────────────────────

router.get('/payouts', async (req: AuthRequest, res: Response, next: NextFunction) => {
  try {
    const entries = await prisma.fieldLedgerEntry.findMany({
      where: { userId: req.user!.id },
      orderBy: { createdAt: 'desc' },
    });
    const balances = computeBalances(entries);
    res.json({ data: { balances, entries } });
  } catch (err) {
    next(err);
  }
});

router.post('/payouts/quote', async (req: AuthRequest, res: Response, next: NextFunction) => {
  try {
    const amountCents = Number(req.body?.amountCents);
    const mode: PayoutMode = req.body?.mode === 'INSTANT' ? 'INSTANT' : 'STANDARD';
    if (!Number.isInteger(amountCents) || amountCents <= 0) {
      return res.status(400).json({ error: 'amountCents must be a positive integer' });
    }
    res.json({ data: quotePayout(amountCents, mode) });
  } catch (err) {
    if (err instanceof Error) return res.status(422).json({ error: err.message });
    next(err);
  }
});

router.post('/payouts', async (req: AuthRequest, res: Response, next: NextFunction) => {
  try {
    const amountCents = Number(req.body?.amountCents);
    const mode: PayoutMode = req.body?.mode === 'INSTANT' ? 'INSTANT' : 'STANDARD';
    const idemKey =
      typeof req.body?.idempotencyKey === 'string' && req.body.idempotencyKey.length >= 8
        ? req.body.idempotencyKey
        : `payout:${req.user!.id}:${amountCents}:${mode}:${Math.floor(Date.now() / 5000)}`;

    const quote = quotePayout(amountCents, mode);

    const entry = await prisma.$transaction(async (tx) => {
      await tx.$executeRaw`SELECT pg_advisory_xact_lock(hashtext(${req.user!.id}))`;

      const existing = await tx.fieldLedgerEntry.findUnique({
        where: { idempotencyKey: idemKey },
      });
      if (existing) return existing;

      const entries = await tx.fieldLedgerEntry.findMany({
        where: { userId: req.user!.id },
        select: { kind: true, amountCents: true, status: true },
      });
      const balances = computeBalances(entries);
      if (amountCents > balances.availableCents) {
        throw Object.assign(new Error('Requested amount exceeds available balance'), { statusCode: 422 });
      }

      // Mark earnings paid up to amountCents or record negative payout + fee disclosure entry
      return tx.fieldLedgerEntry.create({
        data: {
          companyId: req.user!.companyId,
          userId: req.user!.id,
          kind: 'PAYOUT',
          amountCents: -amountCents,
          status: 'AVAILABLE',
          description: `${mode} payout (${quote.feeDisclosure})`,
          idempotencyKey: idemKey,
        },
      });
    });

    res.status(201).json({ data: { entry, quote } });
  } catch (err: any) {
    if (err?.statusCode === 422) return res.status(422).json({ error: err.message });
    if (err instanceof Error && err.message.includes('amountCents')) {
      return res.status(422).json({ error: err.message });
    }
    next(err);
  }
});

export default router;
