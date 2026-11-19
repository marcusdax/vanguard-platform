/**
 * Vanguard API (/api/v1/vanguard) — authenticated operations for:
 *  - Academy curriculum & shortestFix progression (I-1 / I-5 / C-11)
 *  - H3 Micro-Zone batch dispatch & IRS $0.76/mi netting
 *  - Mode A Silver-Friendly senior/lawn booking + 4-digit verbal PIN handshake
 *  - Earn-to-Learn micro-escrow hardware vault
 *  - DePIN Spatial Asset half-life royalties & Hardware Vault pools
 *  - Play Integrity + Keybox CRL + IMU Jitter attestation gate
 *  - Tier 4 Vanguard Commander Dual-Sign Co-Signing (RecordKind: ATTEST)
 *  - Storm Event -> Vanguard H3 bounty emission
 */
import { Router, Response, NextFunction } from 'express';
import { AuthRequest } from '../middleware/auth';
import { companyScope } from '../middleware/company';
import { prisma } from '../prisma';
import { checkCredentialsForAction } from '../engine/credentials';
import {
  claimVanguardTask,
  confirmTaskPinHandshake,
  createSeniorCommunityBooking,
  dispatchBountiesFromStormEvent,
  getMicroZoneClusters,
} from '../services/vanguard/microZoneDispatch';
import {
  completeAcademyModule,
  completeAndSettleVanguardTask,
  getAcademyState,
  getDepinPortfolio,
  getOrSeedEarnToLearnVault,
  licenseSpatialAssetQuery,
  redeemHardwareWithVault,
  updateEarnToLearnRouting,
} from '../services/vanguard/escrowAndDepin';
import {
  issueAttestationChallenge,
  verifyAttestationChallenge,
} from '../services/vanguard/attestationGate';
import { anchorMerkleTreeHead, appendAuditRecord } from '../services/vanguard/ledgerChain';

const router = Router();
router.use(companyScope);

// ── Academy & shortestFix Progression ────────────────────────────────────

router.get('/academy', async (req: AuthRequest, res: Response, next: NextFunction) => {
  try {
    const state = await getAcademyState(req.user!.companyId, req.user!.id);
    res.json(state);
  } catch (err) {
    next(err);
  }
});

router.post('/academy/modules/:code/complete', async (req: AuthRequest, res: Response, next: NextFunction) => {
  try {
    const result = await completeAcademyModule({
      companyId: req.user!.companyId,
      userId: req.user!.id,
      moduleCode: req.params.code,
      licenseNumber: typeof req.body?.licenseNumber === 'string' ? req.body.licenseNumber : undefined,
      instructorUserId: typeof req.body?.instructorUserId === 'string' ? req.body.instructorUserId : undefined,
    });
    if (!result.ok) {
      return res.status(result.status).json({ error: result.error });
    }
    const state = await getAcademyState(req.user!.companyId, req.user!.id);
    res.json({ completed: result.module, credential: result.credential, academy: state });
  } catch (err) {
    next(err);
  }
});

// ── H3 Micro-Zone Clusters & Task Claim / PIN / Settlement ───────────────

router.get('/dispatch/clusters', async (req: AuthRequest, res: Response, next: NextFunction) => {
  try {
    const irs = Number(req.query.irsRateCents);
    const data = await getMicroZoneClusters({
      companyId: req.user!.companyId,
      userId: req.user!.id,
      irsCentsPerMile: Number.isFinite(irs) && irs > 0 ? irs : undefined,
    });
    res.json(data);
  } catch (err) {
    next(err);
  }
});

router.post('/dispatch/tasks/:id/claim', async (req: AuthRequest, res: Response, next: NextFunction) => {
  try {
    const result = await claimVanguardTask({
      taskId: req.params.id,
      companyId: req.user!.companyId,
      userId: req.user!.id,
    });
    if (!result.ok) {
      if ('body' in result) return res.status(result.status).json(result.body);
      return res.status(result.status).json({ error: result.error });
    }
    res.json(result);
  } catch (err) {
    next(err);
  }
});

router.post('/dispatch/tasks/:id/confirm-pin', async (req: AuthRequest, res: Response, next: NextFunction) => {
  try {
    const result = await confirmTaskPinHandshake({
      taskId: req.params.id,
      companyId: req.user!.companyId,
      userId: req.user!.id,
      pin: String(req.body?.pin || ''),
    });
    if (!result.ok) return res.status(result.status).json({ error: result.error });
    res.json(result);
  } catch (err) {
    next(err);
  }
});

router.post('/dispatch/tasks/:id/settle', async (req: AuthRequest, res: Response, next: NextFunction) => {
  try {
    const payload =
      req.body?.telemetryPayload && typeof req.body.telemetryPayload === 'object'
        ? (req.body.telemetryPayload as Record<string, unknown>)
        : {};
    const result = await completeAndSettleVanguardTask({
      taskId: req.params.id,
      companyId: req.user!.companyId,
      userId: req.user!.id,
      telemetryPayload: payload,
    });
    if (!result.ok) return res.status(result.status).json({ error: result.error });
    res.json(result);
  } catch (err) {
    next(err);
  }
});

// ── Mode A: Silver-Friendly Community Booking (Helios Local) ─────────────

router.get('/community/bookings', async (req: AuthRequest, res: Response, next: NextFunction) => {
  try {
    const items = await prisma.vanguardTask.findMany({
      where: { companyId: req.user!.companyId, surface: 'helios.local.b2c' },
      orderBy: { createdAt: 'desc' },
      take: 20,
    });
    res.json({ data: items });
  } catch (err) {
    next(err);
  }
});

router.post('/community/bookings', async (req: AuthRequest, res: Response, next: NextFunction) => {
  try {
    const kind =
      req.body?.kind === 'LAWN_MOW_QUARTER_ACRE' ? 'LAWN_MOW_QUARTER_ACRE' : 'GROCERY_CARRY_IN';
    const address = String(req.body?.address || '').trim();
    const requesterName = String(req.body?.requesterName || '').trim();
    if (!address || !requesterName) {
      return res.status(400).json({ error: 'address and requesterName are required.' });
    }
    const created = await createSeniorCommunityBooking({
      companyId: req.user!.companyId,
      kind,
      address,
      requesterName,
      familyProxyName: typeof req.body?.familyProxyName === 'string' ? req.body.familyProxyName.trim() : undefined,
      consentOptInTelemetry: req.body?.consentOptInTelemetry !== false,
      lat: typeof req.body?.lat === 'number' ? req.body.lat : undefined,
      lng: typeof req.body?.lng === 'number' ? req.body.lng : undefined,
    });
    res.status(201).json(created);
  } catch (err) {
    next(err);
  }
});

// ── Earn-to-Learn Hardware Vault ─────────────────────────────────────────

router.get('/vault/earn-to-learn', async (req: AuthRequest, res: Response, next: NextFunction) => {
  try {
    const data = await getOrSeedEarnToLearnVault(req.user!.companyId, req.user!.id);
    res.json(data);
  } catch (err) {
    next(err);
  }
});

router.put('/vault/earn-to-learn/routing', async (req: AuthRequest, res: Response, next: NextFunction) => {
  try {
    const routingBps = Number(req.body?.routingBps);
    if (!Number.isFinite(routingBps)) {
      return res.status(400).json({ error: 'routingBps must be a finite number (0-2500).' });
    }
    const updated = await updateEarnToLearnRouting({
      companyId: req.user!.companyId,
      userId: req.user!.id,
      routingBps,
    });
    res.json({ vault: updated });
  } catch (err) {
    next(err);
  }
});

router.post('/vault/earn-to-learn/redeem', async (req: AuthRequest, res: Response, next: NextFunction) => {
  try {
    const sku = String(req.body?.sku || '').trim();
    if (!sku) return res.status(400).json({ error: 'sku is required.' });
    const result = await redeemHardwareWithVault({
      companyId: req.user!.companyId,
      userId: req.user!.id,
      sku,
    });
    if (!result.ok) return res.status(result.status).json({ error: result.error });
    res.json(result);
  } catch (err) {
    next(err);
  }
});

// ── DePIN Spatial Assets, Half-Life Royalties & Hardware Vault Pools ─────

router.get('/depin/portfolio', async (req: AuthRequest, res: Response, next: NextFunction) => {
  try {
    const data = await getDepinPortfolio(req.user!.companyId, req.user!.id);
    res.json(data);
  } catch (err) {
    next(err);
  }
});

router.post('/depin/assets/:id/license-query', async (req: AuthRequest, res: Response, next: NextFunction) => {
  try {
    const result = await licenseSpatialAssetQuery({
      assetId: req.params.id,
      companyId: req.user!.companyId,
      buyerName: String(req.body?.buyerName || 'FACET Underwriting Engine'),
    });
    if (!result.ok) return res.status(result.status).json({ error: result.error });
    res.status(result.status).json(result);
  } catch (err) {
    next(err);
  }
});

router.post('/depin/pools/:id/invest', async (req: AuthRequest, res: Response, next: NextFunction) => {
  try {
    const sharesCount = Math.max(1, Math.min(20, Math.round(Number(req.body?.shares) || 1)));
    const pool = await prisma.hardwareVaultPool.findFirst({
      where: { id: req.params.id, companyId: req.user!.companyId },
    });
    if (!pool) return res.status(404).json({ error: 'Hardware pool not found.' });
    if (pool.fundedShares + sharesCount > pool.totalShares) {
      return res.status(409).json({ error: 'Not enough unallocated shares remaining in pool.' });
    }
    const share = await prisma.hardwareVaultShare.upsert({
      where: { poolId_userId: { poolId: pool.id, userId: req.user!.id } },
      create: {
        poolId: pool.id,
        companyId: req.user!.companyId,
        userId: req.user!.id,
        shares: sharesCount,
      },
      update: { shares: { increment: sharesCount } },
    });
    const updatedPool = await prisma.hardwareVaultPool.update({
      where: { id: pool.id },
      data: { fundedShares: { increment: sharesCount } },
    });
    res.status(201).json({ pool: updatedPool, share });
  } catch (err) {
    next(err);
  }
});

// ── Play Integrity + Keybox CRL + IMU Jitter Hardware Gate ───────────────

router.post('/attest/challenge', async (req: AuthRequest, res: Response, next: NextFunction) => {
  try {
    const subjectRef = String(req.body?.subjectRef || `operator:${req.user!.id}`);
    const challenge = await issueAttestationChallenge({
      companyId: req.user!.companyId,
      userId: req.user!.id,
      subjectRef,
    });
    res.status(201).json(challenge);
  } catch (err) {
    next(err);
  }
});

router.post('/attest/verify', async (req: AuthRequest, res: Response, next: NextFunction) => {
  try {
    const result = await verifyAttestationChallenge({
      nonce: String(req.body?.nonce || ''),
      companyId: req.user!.companyId,
      userId: req.user!.id,
      verdict: String(req.body?.verdict || 'MEETS_STRONG_INTEGRITY'),
      keyboxSerial: typeof req.body?.keyboxSerial === 'string' ? req.body.keyboxSerial : undefined,
      imuSamples: Array.isArray(req.body?.imuSamples) ? req.body.imuSamples.map(Number) : undefined,
    });
    res.status(result.status).json(result);
  } catch (err) {
    next(err);
  }
});

// ── Tier 4 Commander Dual-Sign Co-Signing Desk (RecordKind: ATTEST) ──────

router.get('/commander/dossiers', async (req: AuthRequest, res: Response, next: NextFunction) => {
  try {
    const packets = await prisma.fieldEvidencePacket.findMany({
      where: { companyId: req.user!.companyId },
      orderBy: { sealedAt: 'desc' },
      take: 25,
    });
    const attestLogs = await prisma.auditLedgerEntry.findMany({
      where: { companyId: req.user!.companyId, kind: 'ATTEST' },
      orderBy: { createdAt: 'desc' },
      take: 50,
    });
    res.json({ packets, attestLogs });
  } catch (err) {
    next(err);
  }
});

router.post('/commander/dossiers/:packetId/attest', async (req: AuthRequest, res: Response, next: NextFunction) => {
  try {
    const creds = await prisma.operatorCredential.findMany({
      where: { companyId: req.user!.companyId, userId: req.user!.id },
      select: { credentialCode: true },
    });
    const gate = checkCredentialsForAction({
      isFloorAction: false,
      requiredCredentials: ['VANGUARD_COMMANDER_T4'],
      heldCredentials: creds.map((c) => c.credentialCode),
      actionLabel: 'Dual-Sign Desk Co-Signing (RecordKind: ATTEST)',
    });
    if (!gate.allowed) {
      return res.status(403).json(gate);
    }

    const packet = await prisma.fieldEvidencePacket.findFirst({
      where: { id: req.params.packetId, companyId: req.user!.companyId },
    });
    if (!packet) return res.status(404).json({ error: 'Evidence packet not found.' });

    const statement = String(
      req.body?.statement ||
        'Co-signed objective capture dossier completeness under TX Ch. 4102 Attestor scope.',
    );

    const entry = await appendAuditRecord({
      companyId: req.user!.companyId,
      subjectRef: `packet:${packet.id}`,
      kind: 'ATTEST',
      actorId: req.user!.id,
      summary: `Tier 4 Dual-Sign Co-Sign: ${packet.code}`,
      payload: {
        packetId: packet.id,
        statement,
        statementVersion: '1.0',
        headHash: packet.headHash,
      },
    });
    const head = await anchorMerkleTreeHead(req.user!.companyId);

    res.status(201).json({ entry, merkleHead: head });
  } catch (err) {
    next(err);
  }
});

// ── Storm Polygon -> Vanguard H3 Bounty Emission ─────────────────────────

router.post('/dispatch/from-storm/:stormEventId', async (req: AuthRequest, res: Response, next: NextFunction) => {
  try {
    const result = await dispatchBountiesFromStormEvent({
      companyId: req.user!.companyId,
      stormEventId: req.params.stormEventId,
      actorId: req.user!.id,
    });
    if (!result.ok) return res.status(404).json({ error: result.error });
    res.status(201).json(result);
  } catch (err) {
    next(err);
  }
});

export default router;
