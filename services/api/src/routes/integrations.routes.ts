import { Router } from 'express';
import { AuthRequest } from '../middleware/auth';
import { companyScope } from '../middleware/company';
import { prisma } from '../prisma';
import { IntegrationStatus, IntegrationType } from '@prisma/client';
import integrationService, {
  IntegrationError,
  SyncResult,
} from '../services/IntegrationService';

const router = Router();
router.use(companyScope);

const isValidType = (v: unknown): v is IntegrationType =>
  Object.values(IntegrationType).includes(v as IntegrationType);

const isValidStatus = (v: unknown): v is IntegrationStatus =>
  Object.values(IntegrationStatus).includes(v as IntegrationStatus);

// Resolve an integration that belongs to the caller's company. This
// ownership check is what enforces multi-tenant scoping (the DB layer
// update/delete requires a unique identifier, so we resolve by id after
// confirming company ownership).
async function findOwnedIntegration(id: string, companyId: string) {
  return prisma.integration.findFirst({
    where: { id, companyId },
  });
}

function emit(
  req: AuthRequest,
  companyId: string,
  event: string,
  payload: unknown
) {
  // Realtime notifications are best-effort: a Socket.IO adapter failure
  // (e.g. misconfigured Redis pub/sub) must never take down the request.
  try {
    const io = req.app.get('io');
    if (io) io.to(`company:${companyId}`).emit(event, payload);
  } catch {
    /* non-fatal — ignore broadcast errors */
  }
}

function maskCredentials(raw: unknown): Record<string, unknown> | undefined {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return undefined;
  const obj = raw as Record<string, unknown>;
  const safe: Record<string, unknown> = {};
  const maskSecret = (val: string) =>
    val.length >= 4 ? `••••${val.slice(-4)}` : '••••';

  for (const key of ['label', 'accountEmail', 'email', 'providerAccountId', 'url']) {
    if (typeof obj[key] === 'string' && obj[key]) safe[key] = obj[key];
  }
  for (const secretKey of ['apiKey', 'secret', 'token', 'webhookSecret']) {
    if (typeof obj[secretKey] === 'string' && obj[secretKey]) {
      safe[secretKey] = maskSecret(obj[secretKey] as string);
    }
  }
  return Object.keys(safe).length > 0 ? safe : undefined;
}

/**
 * GET /api/v1/integrations
 * List the company's integrations. Secret values are masked (••••last4) so
 * Settings cards can show real key status without exposing full credentials.
 */
router.get('/', async (req: AuthRequest, res, next) => {
  try {
    const integrations = await prisma.integration.findMany({
      where: { companyId: req.companyId! },
      orderBy: { createdAt: 'desc' },
    });
    const data = integrations.map((i) => {
      const { credentials, ...rest } = i;
      const masked = maskCredentials(credentials);
      return masked ? { ...rest, credentials: masked } : rest;
    });
    res.json({ data, count: data.length });
  } catch (err) {
    next(err);
  }
});

/**
 * POST /api/v1/integrations
 * Create an integration: { type, credentials: {}, settings: {} }.
 * A company may hold at most one integration of a given type.
 */
router.post('/', async (req: AuthRequest, res, next) => {
  try {
    const { type, credentials, settings } = req.body || {};
    if (!type || !isValidType(type)) {
      return res.status(400).json({
        error: 'A valid integration type is required',
        code: 'VALIDATION_ERROR',
        validTypes: Object.values(IntegrationType),
      });
    }

    const integration = await prisma.integration.create({
      data: {
        companyId: req.companyId!,
        type,
        credentials: credentials || {},
        settings: settings || {},
      },
    });

    emit(req, req.companyId!, 'integration:created', {
      integrationId: integration.id,
      type: integration.type,
    });
    res.status(201).json({ data: integration });
  } catch (err: any) {
    if (err?.code === 'P2002') {
      return res.status(409).json({
        error:
          'An integration of this type already exists for your company',
        code: 'INTEGRATION_TYPE_EXISTS',
      });
    }
    next(err);
  }
});

/**
 * GET /api/v1/integrations/:id
 * Get a single integration (including credentials).
 */
router.get('/:id', async (req: AuthRequest, res, next) => {
  try {
    const integration = await findOwnedIntegration(
      req.params.id,
      req.companyId!
    );
    if (!integration) {
      return res
        .status(404)
        .json({ error: 'Integration not found', code: 'NOT_FOUND' });
    }
    res.json({ data: integration });
  } catch (err) {
    next(err);
  }
});

/**
 * PATCH /api/v1/integrations/:id
 * Update credentials, settings, and/or status. Type may also be
 * changed (it is re-validated against the enum).
 */
router.patch('/:id', async (req: AuthRequest, res, next) => {
  try {
    // Ownership check before mutating.
    const existing = await findOwnedIntegration(req.params.id, req.companyId!);
    if (!existing) {
      return res
        .status(404)
        .json({ error: 'Integration not found', code: 'NOT_FOUND' });
    }

    const { credentials, settings, status, type } = req.body || {};
    const data: {
      credentials?: any;
      settings?: any;
      status?: IntegrationStatus;
      type?: IntegrationType;
    } = {};

    if (credentials !== undefined) data.credentials = credentials;
    if (settings !== undefined) data.settings = settings;
    if (status !== undefined) {
      if (!isValidStatus(status)) {
        return res.status(400).json({
          error: 'Invalid integration status',
          code: 'VALIDATION_ERROR',
          validStatuses: Object.values(IntegrationStatus),
        });
      }
      data.status = status;
    }
    if (type !== undefined) {
      if (!isValidType(type)) {
        return res.status(400).json({
          error: 'Invalid integration type',
          code: 'VALIDATION_ERROR',
          validTypes: Object.values(IntegrationType),
        });
      }
      data.type = type;
    }

    if (Object.keys(data).length === 0) {
      return res.status(400).json({
        error:
          'No updatable fields provided (credentials, settings, status, type)',
        code: 'VALIDATION_ERROR',
      });
    }

    const integration = await prisma.integration.update({
      where: { id: req.params.id },
      data,
    });

    emit(req, req.companyId!, 'integration:updated', {
      integrationId: integration.id,
      type: integration.type,
    });
    res.json({ data: integration });
  } catch (err: any) {
    if (err?.code === 'P2025') {
      return res
        .status(404)
        .json({ error: 'Integration not found', code: 'NOT_FOUND' });
    }
    if (err?.code === 'P2002') {
      return res.status(409).json({
        error:
          'An integration of this type already exists for your company',
        code: 'INTEGRATION_TYPE_EXISTS',
      });
    }
    next(err);
  }
});

/**
 * DELETE /api/v1/integrations/:id
 */
router.delete('/:id', async (req: AuthRequest, res, next) => {
  try {
    // Ownership check (also returns 404 for unknown ids).
    const existing = await findOwnedIntegration(
      req.params.id,
      req.companyId!
    );
    if (!existing) {
      return res
        .status(404)
        .json({ error: 'Integration not found', code: 'NOT_FOUND' });
    }

    await prisma.integration.delete({ where: { id: req.params.id } });

    emit(req, req.companyId!, 'integration:deleted', {
      integrationId: req.params.id,
    });
    res.json({ message: 'Integration deleted', id: req.params.id });
  } catch (err: any) {
    if (err?.code === 'P2025') {
      return res
        .status(404)
        .json({ error: 'Integration not found', code: 'NOT_FOUND' });
    }
    next(err);
  }
});

/**
 * POST /api/v1/integrations/:id/sync
 * Run a type-specific sync. Credentials are stored as JSON on the
 * integration record; the IntegrationService dispatches to the correct
 * handler. On success lastSyncedAt is updated and PENDING/ERROR
 * integrations are activated; on failure the integration is marked ERROR.
 *
 * Optional per-type params (sent in the body):
 *  - HOVER / EAGLEVIEW: { address: "123 Main St, City, ST" }
 *  - WEBHOOK          : { payload: { ... } }
 */
router.post('/:id/sync', async (req: AuthRequest, res, next) => {
  try {
    const integration = await findOwnedIntegration(
      req.params.id,
      req.companyId!
    );
    if (!integration) {
      return res
        .status(404)
        .json({ error: 'Integration not found', code: 'NOT_FOUND' });
    }

    let result: SyncResult;
    try {
      result = await integrationService.sync({
        integration,
        companyId: req.companyId!,
        params: req.body || undefined,
      });
    } catch (err) {
      const intErr =
        err instanceof IntegrationError
          ? err
          : new IntegrationError(
              err instanceof Error ? err.message : String(err),
              502
            );

      await prisma.integration
        .update({
          where: { id: integration.id },
          data: { status: IntegrationStatus.ERROR },
        })
        .catch(() => {
          /* integration may have been deleted mid-sync */
        });

      emit(req, req.companyId!, 'integration:sync:error', {
        integrationId: integration.id,
        type: integration.type,
        error: intErr.message,
        details: intErr.details,
      });

      return res.status(intErr.statusCode).json({
        error: intErr.message,
        code: 'SYNC_ERROR',
        details: intErr.details,
      });
    }

    // In-result failure (e.g. webhook returned an error status).
    if (!result.success) {
      await prisma.integration
        .update({
          where: { id: integration.id },
          data: { status: IntegrationStatus.ERROR },
        })
        .catch(() => {});

      const io = req.app.get('io');
      if (io)
        io.to(`company:${req.companyId!}`).emit('integration:sync:error', {
          integrationId: integration.id,
          type: integration.type,
          error: result.error,
          details: result.details,
        });

      return res.status(502).json({
        error: result.error ?? 'Integration sync completed with errors',
        code: 'SYNC_ERROR',
        details: result.details,
        data: result,
      });
    }

    // Success: record the sync time and (re)activate integrations that
    // were pending or in an error state — a successful sync means the
    // credentials are valid and the integration is live again.
    const data: { lastSyncedAt: Date; status?: IntegrationStatus } = {
      lastSyncedAt: new Date(),
    };
    if (
      integration.status === IntegrationStatus.PENDING ||
      integration.status === IntegrationStatus.ERROR
    ) {
      data.status = IntegrationStatus.ACTIVE;
    }
    const updated = await prisma.integration.update({
      where: { id: integration.id },
      data,
    });

    emit(req, req.companyId!, 'integration:sync', {
      integrationId: integration.id,
      type: integration.type,
      result,
      lastSyncedAt: updated.lastSyncedAt,
    });

    res.json({ data: result });
  } catch (err) {
    next(err);
  }
});

export default router;
