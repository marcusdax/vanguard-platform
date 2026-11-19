import { Router, Response, NextFunction } from 'express';
import { EstimateStatus } from '@prisma/client';
import { AuthRequest } from '../middleware/auth';
import { companyScope } from '../middleware/company';
import { prisma } from '../prisma';
import { uploadSingle } from '../middleware/upload';
import { cacheGet, cacheSet, cacheDel } from '../config/redis';
import {
  generateEstimatePdf,
  PdfEstimate,
  PdfItem,
  PdfCompany,
} from '../services/EstimatePdfService';
import path from 'path';

/**
 * Estimates API Routes — RoofPro CRM
 *
 * Full CRUD for estimates:
 *   GET  /                  list (optionally filtered by jobId; includes items + job)
 *   POST /                  create with line items
 *   GET  /:id               fetch with items, changeOrders, job
 *   PATCH /:id               update name, status, notes
 *   DELETE /:id             delete
 *
 * Line-item sub-resources:
 *   POST   /:id/items               add a line item
 *   PATCH  /:id/items/:itemId       update a line item
 *   DELETE /:id/items/:itemId       delete a line item
 *
 * Lifecycle actions:
 *   POST /:id/send      → status SENT,     sentAt = now,  emit "estimate.sent"
 *   POST /:id/approve   → status APPROVED,  approvedAt = now, emit "estimate.approved"
 *   POST /:id/reject    → status REJECTED,  emit "estimate.rejected"
 *
 * PDF generation:
 *   POST /:id/pdf       → generate PDF via pdfkit (EstimatePdfService), store via uploadSingle, return { pdfUrl }
 *
 * Totals (totalCost, totalPrice) are recalculated automatically whenever items change.
 */
const router = Router();
router.use(companyScope);

const UPLOADS_DIR = path.join(process.cwd(), 'uploads');

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

const VALID_STATUSES = new Set<string>(Object.values(EstimateStatus));

const estimateCacheKey = (companyId: string, estimateId: string) =>
  `estimate:${companyId}:${estimateId}`;
const listCacheKey = (companyId: string) => `estimates:${companyId}:list`;

/** Lightweight scalar fields selected for every estimate listing. */
const estimateBaseSelect = {
  id: true,
  jobId: true,
  name: true,
  totalCost: true,
  totalPrice: true,
  status: true,
  pdfUrl: true,
  sentAt: true,
  approvedAt: true,
  notes: true,
  createdById: true,
  createdAt: true,
  updatedAt: true,
};

/**
 * Detailed include shared across routes (items ordered by sortOrder + job).
 * Typed as `any` because the Prisma-generated include type widens string
 * literals in `orderBy` to `string`, which the generated `SortOrder` enum
 * rejects.  The inline usage inside each Prisma call is already correctly
 * contextually typed — this constant is only used for convenience.
 */
const estimateDetailInclude: any = {
  items: { orderBy: { sortOrder: 'asc' } },
  job: {
    select: { id: true, name: true, propertyAddress: true, status: true },
  },
};

/** Recalculate totalCost / totalPrice from the estimate's line items. */
async function recalculateEstimateTotals(estimateId: string) {
  const items = await prisma.estimateItem.findMany({
    where: { estimateId },
    select: { quantity: true, unitCost: true, unitPrice: true },
  });

  const totalCost = items.reduce(
    (sum, item) => sum + item.quantity * item.unitCost,
    0
  );
  const totalPrice = items.reduce(
    (sum, item) => sum + item.quantity * item.unitPrice,
    0
  );

  await prisma.estimate.update({
    where: { id: estimateId },
    data: { totalCost, totalPrice },
  });

  return { totalCost, totalPrice };
}

/** Emit a Socket.IO event to the company room and (optionally) the job room. */
function emitEstimateEvent(
  req: AuthRequest,
  companyId: string,
  jobId: string | null,
  event: string,
  payload: unknown
) {
  const io = req.app.get('io') as any;
  if (io) {
    io.to(`company:${companyId}`).emit(event, payload);
    if (jobId) {
      io.to(`job:${jobId}`).emit(event, payload);
    }
  }
}

/** Invalidate cached estimate + list entries after a mutation. */
async function invalidateEstimateCache(companyId: string, estimateId: string) {
  await cacheDel(estimateCacheKey(companyId, estimateId));
  await cacheDel(listCacheKey(companyId));
}

/** Build the typed input objects expected by EstimatePdfService. */
function buildPdfInput(estimate: any, company: any, createdBy: any | null) {
  const pdfEstimate: PdfEstimate = {
    id: estimate.id,
    name: estimate.name,
    totalCost: Number(estimate.totalCost) || 0,
    totalPrice: Number(estimate.totalPrice) || 0,
    status: estimate.status,
    notes: estimate.notes ?? null,
    sentAt: estimate.sentAt ? new Date(estimate.sentAt).toISOString() : null,
    approvedAt: estimate.approvedAt ? new Date(estimate.approvedAt).toISOString() : null,
    createdAt: new Date(estimate.createdAt).toISOString(),
    job: estimate.job
      ? {
          id: estimate.job.id,
          name: estimate.job.name,
          propertyAddress: estimate.job.propertyAddress ?? '',
        }
      : undefined,
    createdBy: createdBy
      ? {
          firstName: createdBy.firstName,
          lastName: createdBy.lastName,
          email: createdBy.email,
        }
      : undefined,
  };

  const pdfItems: PdfItem[] = (estimate.items || []).map((item: any) => ({
    description: item.description || '',
    quantity: Number(item.quantity),
    unit: item.unit || '',
    unitCost: Number(item.unitCost),
    unitPrice: Number(item.unitPrice),
    category: item.category ?? null,
  }));

  const pdfCompany: PdfCompany = {
    id: company?.id ?? '',
    name: company?.name ?? null,
    address: company?.address ?? null,
    phone: company?.phone ?? null,
    logoUrl: company?.logoUrl ?? null,
  };

  return { pdfEstimate, pdfItems, pdfCompany };
}

// ---------------------------------------------------------------------------
// Routes
// ---------------------------------------------------------------------------

/**
 * GET /estimates
 * List the calling company's estimates.
 *   Query: jobId (filter to a specific job)
 */
router.get(
  '/',
  async (req: AuthRequest, res: Response, next: NextFunction) => {
    try {
      const { jobId } = req.query;
      const where: Record<string, unknown> = { companyId: req.companyId };
      if (jobId) where.jobId = jobId as string;

      const estimates = await prisma.estimate.findMany({
        where,
        select: {
          ...estimateBaseSelect,
          items: {
            select: {
              id: true,
              description: true,
              quantity: true,
              unit: true,
              unitCost: true,
              unitPrice: true,
              category: true,
              sortOrder: true,
              createdAt: true,
            },
          },
          job: {
            select: { id: true, name: true, propertyAddress: true },
          },
        },
        orderBy: { createdAt: 'desc' },
      });

      res.json({ estimates, count: estimates.length });
    } catch (err) {
      next(err);
    }
  }
);

/**
 * POST /estimates
 * Create a new estimate for a job.
 *   Body: { jobId, name, notes?, items: [{ description, quantity, unit, unitCost, unitPrice, category }] }
 */
router.post(
  '/',
  async (req: AuthRequest, res: Response, next: NextFunction) => {
    try {
      const { jobId, name, items = [], notes } = req.body;

      if (!jobId || !name) {
        return res.status(400).json({
          error: 'jobId and name are required',
          code: 'VALIDATION_ERROR',
        });
      }

      // Verify the job belongs to this company
      const job = await prisma.job.findFirst({
        where: { id: jobId, companyId: req.companyId },
      });
      if (!job) {
        return res
          .status(404)
          .json({ error: 'Job not found', code: 'JOB_NOT_FOUND' });
      }

      const estimate = await prisma.estimate.create({
        data: {
          companyId: req.companyId,
          jobId,
          name,
          notes,
          createdById: req.user!.id,
          items: {
            create: (items as any[]).map((item, idx) => ({
              description: item.description,
              quantity: Number(item.quantity) || 0,
              unit: item.unit || 'ea',
              unitCost: Number(item.unitCost) || 0,
              unitPrice: Number(item.unitPrice) || 0,
              category: item.category,
              sortOrder: idx,
            })),
          },
        },
        include: estimateDetailInclude,
      });

      // Auto-recalculate totals after creating with items
      await recalculateEstimateTotals(estimate.id);

      const fresh = await prisma.estimate.findUnique({
        where: { id: estimate.id },
        include: estimateDetailInclude,
      });

      await invalidateEstimateCache(req.companyId!, estimate.id);
      emitEstimateEvent(
        req,
        req.companyId!,
        estimate.jobId,
        'estimate.created',
        { estimateId: estimate.id, name: estimate.name }
      );

      res.status(201).json({ estimate: fresh });
    } catch (err) {
      next(err);
    }
  }
);

/**
 * GET /estimates/:id
 * Fetch a single estimate with its items, changeOrders, and job.
 */
router.get(
  '/:id',
  async (req: AuthRequest, res: Response, next: NextFunction) => {
    try {
      const ckey = estimateCacheKey(req.companyId!, req.params.id);

      const cached = await cacheGet(ckey);
      if (cached) {
        try {
          const estimate = JSON.parse(cached);
          return res.json({ estimate });
        } catch {
          // cache corrupted — fall through to DB
        }
      }

      const estimate = await prisma.estimate.findFirst({
        where: { id: req.params.id, companyId: req.companyId },
        include: {
          items: { orderBy: { sortOrder: 'asc' } },
          changeOrders: true,
          job: {
            select: { id: true, name: true, propertyAddress: true, status: true },
          },
        },
      });

      if (!estimate) {
        return res
          .status(404)
          .json({ error: 'Estimate not found', code: 'ESTIMATE_NOT_FOUND' });
      }

      await cacheSet(ckey, estimate);
      res.json({ estimate });
    } catch (err) {
      next(err);
    }
  }
);

/**
 * PATCH /estimates/:id
 * Update an estimate's name, status, and/or notes.
 *   Body: { name?, status?, notes? }
 */
router.patch(
  '/:id',
  async (req: AuthRequest, res: Response, next: NextFunction) => {
    try {
      const { name, status, notes } = req.body;

      if (status !== undefined && !VALID_STATUSES.has(status)) {
        return res.status(400).json({
          error: `Invalid status. Allowed: ${[...VALID_STATUSES].join(', ')}`,
          code: 'VALIDATION_ERROR',
        });
      }

      const existing = await prisma.estimate.findFirst({
        where: { id: req.params.id, companyId: req.companyId },
      });
      if (!existing) {
        return res
          .status(404)
          .json({ error: 'Estimate not found', code: 'ESTIMATE_NOT_FOUND' });
      }

      const estimate = await prisma.estimate.update({
        where: { id: req.params.id },
        data: {
          ...(name !== undefined && { name }),
          ...(status !== undefined && { status: status as EstimateStatus }),
          ...(notes !== undefined && { notes }),
        },
        include: estimateDetailInclude,
      });

      await invalidateEstimateCache(req.companyId!, req.params.id);
      emitEstimateEvent(
        req,
        req.companyId!,
        estimate.jobId,
        'estimate.updated',
        { estimateId: estimate.id }
      );

      res.json({ estimate });
    } catch (err: any) {
      if (err?.code === 'P2025') {
        return res
          .status(404)
          .json({ error: 'Estimate not found', code: 'ESTIMATE_NOT_FOUND' });
      }
      next(err);
    }
  }
);

/**
 * DELETE /estimates/:id
 * Delete an estimate (and cascade its items).
 */
router.delete(
  '/:id',
  async (req: AuthRequest, res: Response, next: NextFunction) => {
    try {
      const existing = await prisma.estimate.findFirst({
        where: { id: req.params.id, companyId: req.companyId },
      });
      if (!existing) {
        return res
          .status(404)
          .json({ error: 'Estimate not found', code: 'ESTIMATE_NOT_FOUND' });
      }

      await prisma.estimate.delete({ where: { id: req.params.id } });
      await invalidateEstimateCache(req.companyId!, req.params.id);
      emitEstimateEvent(
        req,
        req.companyId!,
        existing.jobId,
        'estimate.deleted',
        { estimateId: req.params.id }
      );

      res.json({ message: 'Estimate deleted', estimateId: req.params.id });
    } catch (err: any) {
      if (err?.code === 'P2025') {
        return res
          .status(404)
          .json({ error: 'Estimate not found', code: 'ESTIMATE_NOT_FOUND' });
      }
      next(err);
    }
  }
);

// ── Line-item sub-routes ───────────────────────────────────────────────────

/**
 * POST /estimates/:id/items
 * Add a line item to an estimate.
 *   Body: { description, quantity, unit, unitCost, unitPrice, category? }
 */
router.post(
  '/:id/items',
  async (req: AuthRequest, res: Response, next: NextFunction) => {
    try {
      const estimate = await prisma.estimate.findFirst({
        where: { id: req.params.id, companyId: req.companyId },
      });
      if (!estimate) {
        return res
          .status(404)
          .json({ error: 'Estimate not found', code: 'ESTIMATE_NOT_FOUND' });
      }

      const { description, quantity, unit, unitCost, unitPrice, category } =
        req.body;

      if (!description) {
        return res.status(400).json({
          error: 'description is required',
          code: 'VALIDATION_ERROR',
        });
      }

      const maxSortResult = await prisma.estimateItem.aggregate({
        where: { estimateId: req.params.id },
        _max: { sortOrder: true },
      });
      const sortOrder = (maxSortResult._max.sortOrder ?? -1) + 1;

      const item = await prisma.estimateItem.create({
        data: {
          estimateId: req.params.id,
          description,
          quantity: Number(quantity) || 0,
          unit: unit || 'ea',
          unitCost: Number(unitCost) || 0,
          unitPrice: Number(unitPrice) || 0,
          category,
          sortOrder,
        },
      });

      // Recalculate totals after item change
      await recalculateEstimateTotals(req.params.id);
      await invalidateEstimateCache(req.companyId!, req.params.id);

      const fresh = await prisma.estimate.findUnique({
        where: { id: req.params.id },
        include: estimateDetailInclude,
      });

      emitEstimateEvent(
        req,
        req.companyId!,
        estimate.jobId,
        'estimate.itemAdded',
        { estimateId: req.params.id, item }
      );

      res.status(201).json({ item, estimate: fresh });
    } catch (err) {
      next(err);
    }
  }
);

/**
 * PATCH /estimates/:id/items/:itemId
 * Update a single line item's fields.
 *   Body: { description?, quantity?, unit?, unitCost?, unitPrice?, category?, sortOrder? }
 */
router.patch(
  '/:id/items/:itemId',
  async (req: AuthRequest, res: Response, next: NextFunction) => {
    try {
      const {
        description,
        quantity,
        unit,
        unitCost,
        unitPrice,
        category,
        sortOrder,
      } = req.body;

      // Verify the item belongs to an estimate in this company
      const existing = await prisma.estimateItem.findFirst({
        where: {
          id: req.params.itemId,
          estimate: { companyId: req.companyId },
        },
        include: {
          estimate: { select: { id: true, jobId: true } },
        },
      });
      if (!existing) {
        return res
          .status(404)
          .json({ error: 'Item not found', code: 'ITEM_NOT_FOUND' });
      }

      const item = await prisma.estimateItem.update({
        where: { id: req.params.itemId },
        data: {
          ...(description !== undefined && { description }),
          ...(quantity !== undefined && { quantity: Number(quantity) }),
          ...(unit !== undefined && { unit }),
          ...(unitCost !== undefined && { unitCost: Number(unitCost) }),
          ...(unitPrice !== undefined && { unitPrice: Number(unitPrice) }),
          ...(category !== undefined && { category }),
          ...(sortOrder !== undefined && { sortOrder: Number(sortOrder) }),
        },
      });

      // Recalculate totals after item change
      await recalculateEstimateTotals(req.params.id);
      await invalidateEstimateCache(req.companyId!, req.params.id);

      emitEstimateEvent(
        req,
        req.companyId!,
        existing.estimate.jobId,
        'estimate.itemUpdated',
        { estimateId: req.params.id, item }
      );

      res.json({ item });
    } catch (err: any) {
      if (err?.code === 'P2025') {
        return res
          .status(404)
          .json({ error: 'Item not found', code: 'ITEM_NOT_FOUND' });
      }
      next(err);
    }
  }
);

/**
 * DELETE /estimates/:id/items/:itemId
 * Remove a line item from an estimate and recalculate totals.
 */
router.delete(
  '/:id/items/:itemId',
  async (req: AuthRequest, res: Response, next: NextFunction) => {
    try {
      const existing = await prisma.estimateItem.findFirst({
        where: {
          id: req.params.itemId,
          estimate: { companyId: req.companyId },
        },
        include: {
          estimate: { select: { id: true, jobId: true } },
        },
      });
      if (!existing) {
        return res
          .status(404)
          .json({ error: 'Item not found', code: 'ITEM_NOT_FOUND' });
      }

      await prisma.estimateItem.delete({ where: { id: req.params.itemId } });

      // Recalculate totals after item change
      await recalculateEstimateTotals(req.params.id);
      await invalidateEstimateCache(req.companyId!, req.params.id);

      emitEstimateEvent(
        req,
        req.companyId!,
        existing.estimate.jobId,
        'estimate.itemDeleted',
        { estimateId: req.params.id, itemId: req.params.itemId }
      );

      res.json({ message: 'Item deleted', itemId: req.params.itemId });
    } catch (err: any) {
      if (err?.code === 'P2025') {
        return res
          .status(404)
          .json({ error: 'Item not found', code: 'ITEM_NOT_FOUND' });
      }
      next(err);
    }
  }
);

// ── Lifecycle actions ──────────────────────────────────────────────────────

/**
 * POST /estimates/:id/send
 * Mark the estimate as SENT and record sentAt.
 */
router.post(
  '/:id/send',
  async (req: AuthRequest, res: Response, next: NextFunction) => {
    try {
      const estimate = await prisma.estimate.findFirst({
        where: { id: req.params.id, companyId: req.companyId },
      });
      if (!estimate) {
        return res
          .status(404)
          .json({ error: 'Estimate not found', code: 'ESTIMATE_NOT_FOUND' });
      }

      const updated = await prisma.estimate.update({
        where: { id: req.params.id },
        data: { status: 'SENT', sentAt: new Date() },
        include: estimateDetailInclude,
      });

      await invalidateEstimateCache(req.companyId!, req.params.id);
      emitEstimateEvent(
        req,
        req.companyId!,
        estimate.jobId,
        'estimate.sent',
        { estimateId: estimate.id, name: estimate.name, jobId: estimate.jobId }
      );

      res.json({ estimate: updated });
    } catch (err) {
      next(err);
    }
  }
);

/**
 * POST /estimates/:id/approve
 * Mark the estimate as APPROVED and record approvedAt.
 */
router.post(
  '/:id/approve',
  async (req: AuthRequest, res: Response, next: NextFunction) => {
    try {
      const estimate = await prisma.estimate.findFirst({
        where: { id: req.params.id, companyId: req.companyId },
      });
      if (!estimate) {
        return res
          .status(404)
          .json({ error: 'Estimate not found', code: 'ESTIMATE_NOT_FOUND' });
      }

      const updated = await prisma.estimate.update({
        where: { id: req.params.id },
        data: { status: 'APPROVED', approvedAt: new Date() },
        include: estimateDetailInclude,
      });

      await invalidateEstimateCache(req.companyId!, req.params.id);
      emitEstimateEvent(
        req,
        req.companyId!,
        estimate.jobId,
        'estimate.approved',
        { estimateId: estimate.id, name: estimate.name, jobId: estimate.jobId }
      );

      res.json({ estimate: updated });
    } catch (err) {
      next(err);
    }
  }
);

/**
 * POST /estimates/:id/reject
 * Mark the estimate as REJECTED.
 */
router.post(
  '/:id/reject',
  async (req: AuthRequest, res: Response, next: NextFunction) => {
    try {
      const estimate = await prisma.estimate.findFirst({
        where: { id: req.params.id, companyId: req.companyId },
      });
      if (!estimate) {
        return res
          .status(404)
          .json({ error: 'Estimate not found', code: 'ESTIMATE_NOT_FOUND' });
      }

      const updated = await prisma.estimate.update({
        where: { id: req.params.id },
        data: { status: 'REJECTED' },
        include: estimateDetailInclude,
      });

      await invalidateEstimateCache(req.companyId!, req.params.id);
      emitEstimateEvent(
        req,
        req.companyId!,
        estimate.jobId,
        'estimate.rejected',
        { estimateId: estimate.id, name: estimate.name, jobId: estimate.jobId }
      );

      res.json({ estimate: updated });
    } catch (err) {
      next(err);
    }
  }
);

// ── PDF generation ─────────────────────────────────────────────────────────

/**
 * POST /estimates/:id/pdf
 * Generate a printable PDF using pdfkit (via EstimatePdfService) with an
 * itemized line-items table, totals, margin %, and company branding.
 *
 * Uses uploadSingle('pdf') as middleware so that an externally-provided PDF
 * can optionally be stored instead of a generated one. In the common case
 * (no file field) the PDF is generated server-side and written to the
 * uploads directory, and the resulting URL is saved on the estimate's
 * `pdfUrl` field.
 */
router.post(
  '/:id/pdf',
  (req: AuthRequest, res: Response, next: NextFunction) => {
    uploadSingle('pdf')(req, res, (err: any) => {
      if (err) {
        return res.status(400).json({
          error: err.message ?? 'PDF upload failed',
          code: 'UPLOAD_ERROR',
        });
      }
      next();
    });
  },
  async (req: AuthRequest, res: Response, next: NextFunction) => {
    try {
      // Fetch the full estimate with items, job, and createdBy user
      const estimate = await prisma.estimate.findFirst({
        where: { id: req.params.id, companyId: req.companyId },
        include: {
          items: { orderBy: { sortOrder: 'asc' } },
          job: {
            select: { id: true, name: true, propertyAddress: true, status: true },
          },
        },
      });
      if (!estimate) {
        return res
          .status(404)
          .json({ error: 'Estimate not found', code: 'ESTIMATE_NOT_FOUND' });
      }

      // Fetch the user who created the estimate (for "Prepared by")
      const createdBy = await prisma.user.findUnique({
        where: { id: estimate.createdById, companyId: req.companyId },
        select: { id: true, firstName: true, lastName: true, email: true },
      });

      // Fetch company branding
      const company = await prisma.company.findUnique({
        where: { id: req.companyId },
        select: { id: true, name: true, address: true, phone: true, logoUrl: true },
      });

      let pdfUrl: string;

      const uploadedFile = req.file as Express.Multer.File | undefined;
      if (uploadedFile) {
        // Client supplied a pre-made PDF via uploadSingle
        pdfUrl = `/uploads/${uploadedFile.filename}`;
      } else {
        // Generate the PDF server-side using pdfkit (via EstimatePdfService)
        const { pdfEstimate, pdfItems, pdfCompany } = buildPdfInput(
          estimate,
          company,
          createdBy
        );

        const filename = `estimate-${estimate.id}-${Date.now()}.pdf`;
        const outputPath = path.join(UPLOADS_DIR, filename);
        await generateEstimatePdf(pdfEstimate, pdfItems, pdfCompany, outputPath);
        pdfUrl = `/uploads/${filename}`;
      }

      const updated = await prisma.estimate.update({
        where: { id: req.params.id },
        data: { pdfUrl },
        include: estimateDetailInclude,
      });

      await invalidateEstimateCache(req.companyId!, req.params.id);
      emitEstimateEvent(
        req,
        req.companyId!,
        estimate.jobId,
        'estimate.pdfGenerated',
        { estimateId: estimate.id, pdfUrl }
      );

      res.json({ pdfUrl, estimate: updated });
    } catch (err) {
      next(err);
    }
  }
);

export default router;
