/**
 * Lead Management Routes — RoofPro CRM
 *
 * Full lead lifecycle for roofing contractors:
 *  - List with filtering (status, source, assignedToUserId, search) + pagination
 *  - Create (auto-calculates storm impact score via NOAA when propertyLatLng given)
 *  - Detail, update, delete — all company-scoped (multi-tenant)
 *  - Kanban pipeline view
 *  - Storm impact score lookup by lat/lon
 *
 * All queries are scoped to req.companyId via the companyScope middleware.
 */
import { Router } from 'express';
import { body, query, param, validationResult } from 'express-validator';
import { Prisma } from '@prisma/client';
import { AuthRequest } from '../middleware/auth';
import { companyScope } from '../middleware/company';
import { prisma } from '../prisma';
import noaaService from '../services/NOAAWeatherService';
import { cacheGet, cacheSet } from '../config/redis';

const router = Router();
router.use(companyScope);

// ─── Constants ───────────────────────────────────────────────
export const LEAD_SOURCES = [
  'WEBSITE',
  'PHONE',
  'REFERRAL',
  'STORM_EVENT',
  'MANUAL',
  'IMPORT',
  'MARKETING_QR',
] as const;

export const LEAD_STATUSES = [
  'NEW',
  'CONTACTED',
  'INSPECTION_SCHEDULED',
  'ESTIMATE_SENT',
  'WON',
  'LOST',
] as const;

type LeadSource = (typeof LEAD_SOURCES)[number];
type LeadStatus = (typeof LEAD_STATUSES)[number];

// Relations returned alongside a lead everywhere
const LEAD_INCLUDE = {
  assignedTo: {
    select: {
      id: true,
      firstName: true,
      lastName: true,
      email: true,
      role: true,
      phone: true,
    },
  },
  stormEvent: {
    select: {
      id: true,
      name: true,
      severity: true,
      eventType: true,
      startDate: true,
      endDate: true,
    },
  },
  job: {
    select: {
      id: true,
      name: true,
      status: true,
      phase: true,
    },
  },
} satisfies Prisma.LeadInclude;

// Lightweight projection used for the pipeline kanban view
const LEAD_PIPELINE_SELECT = {
  id: true,
  firstName: true,
  lastName: true,
  email: true,
  phone: true,
  propertyAddress: true,
  propertyLatLng: true,
  source: true,
  status: true,
  assignedToUserId: true,
  score: true,
  createdAt: true,
  updatedAt: true,
} satisfies Prisma.LeadSelect;

// ─── Helpers ─────────────────────────────────────────────────
/**
 * Normalize the persisted propertyLatLng JSON into { lat, lon }.
 * Accepts the frontend `{ lat, lng }` shape, a `{ lat, lon }` shape,
 * or a GeoJSON Point `{ type: 'Point', coordinates: [lon, lat] }`.
 */
function extractLatLng(propertyLatLng: unknown): { lat: number; lon: number } | null {
  if (!propertyLatLng) return null;
  const obj = propertyLatLng as Record<string, unknown>;

  // GeoJSON Point: coordinates are [lon, lat]
  if (obj.type === 'Point' && Array.isArray(obj.coordinates)) {
    const [lon, lat] = obj.coordinates as number[];
    if (typeof lat === 'number' && typeof lon === 'number') {
      return { lat, lon };
    }
  }

  const lat = obj.lat;
  const lon = obj.lon ?? obj.lng ?? obj.longitude;
  if (typeof lat === 'number' && typeof lon === 'number') {
    return { lat, lon };
  }
  return null;
}

/**
 * Compute the storm impact score for a property, caching the NOAA result
 * in Redis so repeated lookups for the same coordinates are fast.
 */
async function getStormScoreCached(lat: number, lon: number) {
  const cacheKey = `storm-score:${lat}:${lon}`;
  const cached = await cacheGet(cacheKey);
  if (cached) {
    try {
      return JSON.parse(cached);
    } catch {
      // corrupt cache entry — ignore and recompute
    }
  }
  const score = await noaaService.getStormImpactScore(lat, lon);
  await cacheSet(cacheKey, score, 300);
  return score;
}

/** Auto-calculate a lead's storm impact score from its property location. */
async function calculateStormScore(propertyLatLng: unknown): Promise<number | null> {
  const coords = extractLatLng(propertyLatLng);
  if (!coords) return null;
  try {
    const impact = await getStormScoreCached(coords.lat, coords.lon);
    return typeof impact.score === 'number' ? impact.score : null;
  } catch {
    // NOAA API unavailable — do not block lead creation/update; leave score unset
    return null;
  }
}

async function emitLeadEvent(io: any, companyId: string, event: string, payload: unknown) {
  if (io) {
    io.to(`company:${companyId}`).emit(event, payload);
  }
}

// ─── Validation chains ──────────────────────────────────────
const createLeadValidation = [
  body('firstName').trim().notEmpty().withMessage('firstName is required'),
  body('lastName').trim().notEmpty().withMessage('lastName is required'),
  body('email').optional({ checkFalsy: true }).isEmail().withMessage('email must be valid'),
  body('phone').optional({ checkFalsy: true }).isString(),
  body('propertyAddress').trim().notEmpty().withMessage('propertyAddress is required'),
  body('propertyLatLng').optional({ checkFalsy: true }).custom((value) => {
    if (typeof value !== 'object' || value === null) {
      throw new Error('propertyLatLng must be an object');
    }
    return true;
  }),
  body('source').isIn(LEAD_SOURCES).withMessage(`source must be one of: ${LEAD_SOURCES.join(', ')}`),
  body('status').optional().isIn(LEAD_STATUSES).withMessage(`status must be one of: ${LEAD_STATUSES.join(', ')}`),
  body('assignedToUserId').optional({ nullable: true }).isUUID().withMessage('assignedToUserId must be a valid UUID'),
  body('stormEventId').optional({ nullable: true }).isUUID().withMessage('stormEventId must be a valid UUID'),
  body('score').optional({ checkFalsy: true }).isInt({ min: 0, max: 100 }).withMessage('score must be 0-100'),
  body('notes').optional({ checkFalsy: true }).isString(),
];

const updateLeadValidation = [
  body('firstName').optional({ checkFalsy: true }).isString(),
  body('lastName').optional({ checkFalsy: true }).isString(),
  body('email').optional({ checkFalsy: true }).isEmail().withMessage('email must be valid'),
  body('phone').optional({ checkFalsy: true }).isString(),
  body('propertyAddress').optional({ checkFalsy: true }).isString(),
  body('propertyLatLng').optional().custom((value) => {
    if (value === null) return true;
    if (typeof value !== 'object') throw new Error('propertyLatLng must be an object');
    return true;
  }),
  body('source').optional({ checkFalsy: true }).isIn(LEAD_SOURCES).withMessage(`source must be one of: ${LEAD_SOURCES.join(', ')}`),
  body('status').optional({ checkFalsy: true }).isIn(LEAD_STATUSES).withMessage(`status must be one of: ${LEAD_STATUSES.join(', ')}`),
  body('assignedToUserId').optional().custom((value) => {
    if (value === null) return true;
    if (typeof value === 'string' && value.trim() !== '') return true;
    throw new Error('assignedToUserId must be a valid id or null');
  }),
  body('stormEventId').optional().custom((value) => {
    if (value === null) return true;
    if (typeof value === 'string' && value.trim() !== '') return true;
    throw new Error('stormEventId must be a valid id or null');
  }),
  body('score').optional().isInt({ min: 0, max: 100 }).withMessage('score must be 0-100'),
  body('notes').optional().isString(),
];

// ─── Routes ──────────────────────────────────────────────────

/**
 * GET /leads/storm-score?lat=&lon=
 * Return the NOAA storm impact score for a property location.
 */
router.get(
  '/storm-score',
  [
    query('lat').notEmpty().isFloat({ min: -90, max: 90 }).withMessage('lat must be a number between -90 and 90'),
    query('lon').notEmpty().isFloat({ min: -180, max: 180 }).withMessage('lon must be a number between -180 and 180'),
  ],
  async (req: AuthRequest, res, next) => {
    const errors = validationResult(req);
    if (!errors.isEmpty()) {
      return res.status(400).json({ errors: errors.array() });
    }
    try {
      const lat = Number(req.query.lat);
      const lon = Number(req.query.lon);
      const score = await getStormScoreCached(lat, lon);
      res.json({ ...score, timestamp: new Date().toISOString() });
    } catch (err) {
      next(err);
    }
  }
);

/**
 * GET /leads/pipeline
 * Kanban pipeline: one bucket per LeadStatus with counts and lightweight lead rows.
 */
router.get('/pipeline', async (req: AuthRequest, res, next) => {
  try {
    const companyId = req.companyId!;
    const allLeads = await prisma.lead.findMany({
      where: { companyId },
      orderBy: { createdAt: 'desc' },
      select: LEAD_PIPELINE_SELECT,
    });

    const pipeline = LEAD_STATUSES.map((status) => {
      const leads = allLeads.filter((l) => l.status === status);
      return {
        status,
        count: leads.length,
        leads,
      };
    });

    res.json({ data: pipeline, count: pipeline.length });
  } catch (err) {
    next(err);
  }
});

/**
 * GET /leads
 * List leads with filters + pagination.
 * Query: status, source, assignedToUserId, search, page, limit
 */
router.get(
  '/',
  [
    query('status').optional().isIn(LEAD_STATUSES),
    query('source').optional().isIn(LEAD_SOURCES),
    query('assignedToUserId').optional().isUUID().withMessage('assignedToUserId must be a UUID'),
    query('search').optional().isString(),
    query('page').optional().isInt({ min: 1 }).toInt(),
    query('limit').optional().isInt({ min: 1, max: 100 }).toInt(),
  ],
  async (req: AuthRequest, res, next) => {
    const errors = validationResult(req);
    if (!errors.isEmpty()) {
      return res.status(400).json({ errors: errors.array() });
    }

    try {
      const companyId = req.companyId!;
      const { status, source, assignedToUserId, search, page, limit } = req.query as {
        status?: string;
        source?: string;
        assignedToUserId?: string;
        search?: string;
        page?: string;
        limit?: string;
      };

      const pageNum = Math.max(1, Number(page) || 1);
      const limitNum = Math.min(100, Math.max(1, Number(limit) || 20));
      const skip = (pageNum - 1) * limitNum;

      const where: Prisma.LeadWhereInput = { companyId };

      if (status) where.status = status as LeadStatus;
      if (source) where.source = source as LeadSource;
      if (assignedToUserId) where.assignedToUserId = assignedToUserId;

      if (search) {
        where.OR = [
          { firstName: { contains: search, mode: 'insensitive' } },
          { lastName: { contains: search, mode: 'insensitive' } },
          { email: { contains: search, mode: 'insensitive' } },
          { phone: { contains: search, mode: 'insensitive' } },
          { propertyAddress: { contains: search, mode: 'insensitive' } },
        ];
      }

      const [leads, total] = await prisma.$transaction([
        prisma.lead.findMany({
          where,
          orderBy: { createdAt: 'desc' },
          skip,
          take: limitNum,
          include: LEAD_INCLUDE,
        }),
        prisma.lead.count({ where }),
      ]);

      res.json({
        data: leads,
        count: leads.length,
        total,
        pagination: {
          page: pageNum,
          limit: limitNum,
          totalPages: Math.ceil(total / limitNum),
        },
      });
    } catch (err) {
      next(err);
    }
  }
);

/**
 * GET /leads/:id
 * Full lead detail with assignedTo, stormEvent, and job relations.
 */
router.get('/:id', param('id').isUUID().withMessage('id must be a UUID'), async (req: AuthRequest, res, next) => {
  const errors = validationResult(req);
  if (!errors.isEmpty()) {
    return res.status(400).json({ errors: errors.array() });
  }

  try {
    const companyId = req.companyId!;
    const lead = await prisma.lead.findFirst({
      where: { id: req.params.id, companyId },
      include: LEAD_INCLUDE,
    });

    if (!lead) {
      return res.status(404).json({ error: 'Lead not found' });
    }

    res.json({ lead });
  } catch (err) {
    next(err);
  }
});

/**
 * POST /leads
 * Create a lead. When propertyLatLng is provided, the storm impact score is
 * auto-calculated via NOAAWeatherService.getStormImpactScore.
 */
router.post(
  '/',
  createLeadValidation,
  async (req: AuthRequest, res, next) => {
    const errors = validationResult(req);
    if (!errors.isEmpty()) {
      return res.status(400).json({ errors: errors.array() });
    }

    try {
      const companyId = req.companyId!;
      const {
        firstName,
        lastName,
        email,
        phone,
        propertyAddress,
        propertyLatLng,
        source,
        status,
        assignedToUserId,
        stormEventId,
        score: explicitScore,
        notes,
      } = req.body;

      // Verify referenced stormEvent belongs to this company
      if (stormEventId) {
        const stormEvent = await prisma.stormEvent.findUnique({
          where: { id: stormEventId },
          select: { companyId: true },
        });
        if (!stormEvent) {
          return res.status(404).json({ error: `StormEvent ${stormEventId} not found` });
        }
        if (stormEvent.companyId !== companyId) {
          return res.status(403).json({ error: 'StormEvent does not belong to your company' });
        }
      }

      // Verify referenced assignee belongs to this company
      if (assignedToUserId) {
        const assignee = await prisma.user.findUnique({
          where: { id: assignedToUserId },
          select: { companyId: true, isActive: true },
        });
        if (!assignee) {
          return res.status(404).json({ error: `User ${assignedToUserId} not found` });
        }
        if (assignee.companyId !== companyId) {
          return res.status(403).json({ error: 'Assigned user does not belong to your company' });
        }
        if (!assignee.isActive) {
          return res.status(400).json({ error: 'Cannot assign to an inactive user' });
        }
      }

      // Auto-calculate storm impact score when propertyLatLng is provided
      let score: number | null = null;
      if (explicitScore !== undefined && explicitScore !== null) {
        score = Number(explicitScore);
      } else if (propertyLatLng) {
        score = await calculateStormScore(propertyLatLng);
      }

      const data: Prisma.LeadUncheckedCreateInput = {
        companyId,
        firstName,
        lastName,
        ...(email && { email }),
        ...(phone && { phone }),
        propertyAddress,
        ...(propertyLatLng && { propertyLatLng: propertyLatLng as Prisma.InputJsonValue }),
        source: source as LeadSource,
        ...(status && { status: status as LeadStatus }),
        ...(stormEventId && { stormEventId }),
        ...(assignedToUserId && { assignedToUserId }),
        ...(score !== null && { score }),
        ...(notes && { notes }),
      };

      const lead = await prisma.lead.create({
        data,
        include: LEAD_INCLUDE,
      });

      // Emit realtime event to the company room
      const io = req.app.get('io');
      await emitLeadEvent(io, companyId, 'lead:created', lead);

      res.status(201).json({ lead });
    } catch (err) {
      next(err);
    }
  }
);

/**
 * PATCH /leads/:id
 * Update any lead fields (status, assignment, notes, score, etc.).
 * When propertyLatLng changes and score is not explicitly set, the storm
 * impact score is re-calculated.
 */
router.patch(
  '/:id',
  param('id').isUUID().withMessage('id must be a UUID'),
  updateLeadValidation,
  async (req: AuthRequest, res, next) => {
    const errors = validationResult(req);
    if (!errors.isEmpty()) {
      return res.status(400).json({ errors: errors.array() });
    }

    try {
      const companyId = req.companyId!;
      const { body: bodyData } = req;
      const {
        firstName,
        lastName,
        email,
        phone,
        propertyAddress,
        propertyLatLng,
        source,
        status,
        stormEventId,
        assignedToUserId,
        score: explicitScore,
        notes,
      } = bodyData;

      // Verify references where provided
      if (stormEventId) {
        const stormEvent = await prisma.stormEvent.findUnique({
          where: { id: stormEventId },
          select: { companyId: true },
        });
        if (!stormEvent) {
          return res.status(404).json({ error: `StormEvent ${stormEventId} not found` });
        }
        if (stormEvent.companyId !== companyId) {
          return res.status(403).json({ error: 'StormEvent does not belong to your company' });
        }
      }
      if (assignedToUserId) {
        const assignee = await prisma.user.findUnique({
          where: { id: assignedToUserId },
          select: { companyId: true, isActive: true },
        });
        if (!assignee) {
          return res.status(404).json({ error: `User ${assignedToUserId} not found` });
        }
        if (assignee.companyId !== companyId) {
          return res.status(403).json({ error: 'Assigned user does not belong to your company' });
        }
        if (!assignee.isActive) {
          return res.status(400).json({ error: 'Cannot assign to an inactive user' });
        }
      }

      // Re-calculate storm score when the property location changes
      let recomputedScore: number | null = null;
      if ('propertyLatLng' in bodyData && bodyData.propertyLatLng !== undefined) {
        if (!('score' in bodyData) || bodyData.score === undefined) {
          recomputedScore = await calculateStormScore(bodyData.propertyLatLng);
        }
      }

      const data: Prisma.LeadUncheckedUpdateInput = {
        ...(firstName !== undefined && { firstName }),
        ...(lastName !== undefined && { lastName }),
        ...(email !== undefined && { email: email ?? null }),
        ...(phone !== undefined && { phone: phone ?? null }),
        ...(propertyAddress !== undefined && { propertyAddress }),
        ...(propertyLatLng !== undefined && { propertyLatLng: propertyLatLng as Prisma.InputJsonValue }),
        ...(source !== undefined && { source: source as LeadSource }),
        ...(status !== undefined && { status: status as LeadStatus }),
        ...(stormEventId !== undefined && { stormEventId: stormEventId ?? null }),
        ...(assignedToUserId !== undefined && { assignedToUserId: assignedToUserId ?? null }),
        ...(notes !== undefined && { notes: notes ?? null }),
      };

      // Explicit score wins; otherwise use the recomputed value when location changed
      if (explicitScore !== undefined) {
        data.score = explicitScore;
      } else if (recomputedScore !== null) {
        data.score = recomputedScore;
      }

      const lead = await prisma.lead.update({
        where: { id: req.params.id },
        data,
        include: LEAD_INCLUDE,
      });

      // Emit realtime event to the company room
      const io = req.app.get('io');
      await emitLeadEvent(io, companyId, 'lead:updated', lead);

      res.json({ lead });
    } catch (err: any) {
      if (err?.code === 'P2025') {
        return res.status(404).json({ error: 'Lead not found' });
      }
      next(err);
    }
  }
);

/**
 * DELETE /leads/:id
 * Delete a lead (company-scoped).
 */
router.delete('/:id', param('id').isUUID().withMessage('id must be a UUID'), async (req: AuthRequest, res, next) => {
  const errors = validationResult(req);
  if (!errors.isEmpty()) {
    return res.status(400).json({ errors: errors.array() });
  }

  try {
    const companyId = req.companyId!;

    // Confirm ownership before deleting (update/delete are scoped by id only)
    const existing = await prisma.lead.findFirst({
      where: { id: req.params.id, companyId },
      select: { id: true },
    });
    if (!existing) {
      return res.status(404).json({ error: 'Lead not found' });
    }

    const lead = await prisma.lead.delete({
      where: { id: req.params.id },
      include: LEAD_INCLUDE,
    });

    // Emit realtime event to the company room
    const io = req.app.get('io');
    await emitLeadEvent(io, companyId, 'lead:deleted', { id: req.params.id });

    res.json({ lead, message: 'Lead deleted successfully' });
  } catch (err: any) {
    if (err?.code === 'P2025') {
      return res.status(404).json({ error: 'Lead not found' });
    }
    next(err);
  }
});

export default router;
