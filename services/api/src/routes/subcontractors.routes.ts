/**
 * Subcontractor Management Routes
 *
 * CRUD for subcontractors (scoped to the authenticated company) plus
 * assignment of subcontractors to jobs. Real-time updates are broadcast to
 * the company room (`company:${companyId}`) and job-specific rooms
 * (`job:${jobId}`) via Socket.IO, and list responses are cached in Redis.
 */
import { Router } from 'express';
import { AuthRequest } from '../middleware/auth';
import { companyScope } from '../middleware/company';
import { prisma } from '../prisma';
import { cacheGet, cacheSet, cacheDel } from '../config/redis';

const router = Router();
router.use(companyScope);

// ─── Helpers ─────────────────────────────────────────────────────────────

// Socket.IO instance exposed on the express app via `app.set('io', io)` in server.ts
type SocketIO = { to(room: string): { emit(event: string, ...args: unknown[]): void } };
const getIO = (req: AuthRequest): SocketIO | undefined => {
  const io = req.app.get('io');
  return io ? (io as SocketIO) : undefined;
};

const invalidateSubcontractorList = (companyId: string) =>
  cacheDel(`subcontractors:list:${companyId}`);

const invalidateJobSubcontractors = (jobId: string) =>
  cacheDel(`subcontractors:job:${jobId}`);

const parseSpecialties = (value: unknown): string[] => {
  if (Array.isArray(value)) return value.map(String);
  if (typeof value === 'string') {
    return value
      .split(',')
      .map((s) => s.trim())
      .filter(Boolean);
  }
  return [];
};

const parseInsuranceInfo = (value: unknown): unknown => {
  if (value === undefined || value === null) return undefined;
  return typeof value === 'string' ? JSON.parse(value) : value;
};

// ─── Subcontractor CRUD ────────────────────────────────────────────────────

/**
 * GET /subcontractors
 * List the company's subcontractors.
 * Optional `?specialties=` (comma-separated or repeatable) filters by one or more specialties;
 * filtered queries bypass the cache.
 */
router.get('/', async (req: AuthRequest, res, next) => {
  try {
    const companyId = req.user!.companyId;
    const { specialties } = req.query;

    const specialtiesFilter =
      specialties !== undefined ? parseSpecialties(specialties) : undefined;
    const hasFilter = specialtiesFilter && specialtiesFilter.length > 0;

    const where = hasFilter
      ? {
          companyId,
          specialties: { hasSome: specialtiesFilter as string[] },
        }
      : { companyId };

    const select = {
      id: true,
      companyId: true,
      name: true,
      phone: true,
      email: true,
      companyName: true,
      licenseNumber: true,
      insuranceInfo: true,
      specialties: true,
      rating: true,
      notes: true,
      createdAt: true,
      updatedAt: true,
    };

    if (!hasFilter) {
      const cacheKey = `subcontractors:list:${companyId}`;
      const cached = await cacheGet(cacheKey);
      if (cached) {
        return res.json(JSON.parse(cached));
      }

      const subcontractors = await prisma.subcontractor.findMany({
        where,
        orderBy: { name: 'asc' },
        select,
      });

      const result = { data: subcontractors, count: subcontractors.length };
      await cacheSet(cacheKey, result, 300);
      return res.json(result);
    }

    const subcontractors = await prisma.subcontractor.findMany({
      where,
      orderBy: { name: 'asc' },
      select,
    });
    res.json({ data: subcontractors, count: subcontractors.length });
  } catch (err) {
    next(err);
  }
});

/**
 * POST /subcontractors
 * Create a subcontractor for the authenticated company.
 * Body: { name, phone?, email?, companyName?, licenseNumber?, insuranceInfo?, specialties[], rating?, notes? }
 */
router.post('/', async (req: AuthRequest, res, next) => {
  try {
    const companyId = req.user!.companyId;
    const {
      name,
      phone,
      email,
      companyName,
      licenseNumber,
      insuranceInfo,
      specialties,
      rating,
      notes,
    } = req.body;

    if (!name) {
      return res.status(400).json({ error: 'name is required' });
    }

    const subcontractor = await prisma.subcontractor.create({
      data: {
        companyId,
        name,
        phone,
        email,
        companyName,
        licenseNumber,
        insuranceInfo: parseInsuranceInfo(insuranceInfo) as any,
        specialties: parseSpecialties(specialties),
        rating: rating != null ? Number(rating) : undefined,
        notes,
      },
    });

    await invalidateSubcontractorList(companyId);

    const io = getIO(req);
    io?.to(`company:${companyId}`).emit('subcontractor:created', { subcontractor });

    res.status(201).json({ subcontractor });
  } catch (err) {
    next(err);
  }
});

/**
 * GET /subcontractors/:id
 * Get a single subcontractor owned by the authenticated company.
 */
router.get('/:id', async (req: AuthRequest, res, next) => {
  try {
    const subcontractor = await prisma.subcontractor.findUnique({
      where: { id: req.params.id, companyId: req.user!.companyId },
    });

    if (!subcontractor) {
      return res.status(404).json({ error: 'Subcontractor not found' });
    }

    res.json({ subcontractor });
  } catch (err) {
    next(err);
  }
});

/**
 * PATCH /subcontractors/:id
 * Update fields on a subcontractor owned by the authenticated company.
 */
router.patch('/:id', async (req: AuthRequest, res, next) => {
  try {
    const companyId = req.user!.companyId;

    const existing = await prisma.subcontractor.findUnique({
      where: { id: req.params.id, companyId },
    });
    if (!existing) {
      return res.status(404).json({ error: 'Subcontractor not found' });
    }

    const {
      name,
      phone,
      email,
      companyName,
      licenseNumber,
      insuranceInfo,
      specialties,
      rating,
      notes,
    } = req.body;

    const data: Record<string, unknown> = {};
    if (name !== undefined) data.name = name;
    if (phone !== undefined) data.phone = phone;
    if (email !== undefined) data.email = email;
    if (companyName !== undefined) data.companyName = companyName;
    if (licenseNumber !== undefined) data.licenseNumber = licenseNumber;
    if (insuranceInfo !== undefined) data.insuranceInfo = parseInsuranceInfo(insuranceInfo);
    if (specialties !== undefined) data.specialties = parseSpecialties(specialties);
    if (rating !== undefined) data.rating = Number(rating);
    if (notes !== undefined) data.notes = notes;

    const subcontractor = await prisma.subcontractor.update({
      where: { id: req.params.id },
      data,
    });

    await invalidateSubcontractorList(companyId);

    const io = getIO(req);
    io?.to(`company:${companyId}`).emit('subcontractor:updated', { subcontractor });

    res.json({ subcontractor });
  } catch (err) {
    next(err);
  }
});

/**
 * DELETE /subcontractors/:id
 * Delete a subcontractor (and clear any of its existing assignments).
 */
router.delete('/:id', async (req: AuthRequest, res, next) => {
  try {
    const companyId = req.user!.companyId;

    const existing = await prisma.subcontractor.findUnique({
      where: { id: req.params.id, companyId },
    });
    if (!existing) {
      return res.status(404).json({ error: 'Subcontractor not found' });
    }

    await prisma.$transaction([
      prisma.subcontractorAssignment.deleteMany({
        where: { subcontractorId: req.params.id },
      }),
      prisma.subcontractor.delete({ where: { id: req.params.id } }),
    ]);

    await invalidateSubcontractorList(companyId);

    const io = getIO(req);
    io?.to(`company:${companyId}`).emit('subcontractor:deleted', {
      id: req.params.id,
    });

    res.json({ message: 'Subcontractor deleted', id: req.params.id });
  } catch (err) {
    next(err);
  }
});

// ─── Job Assignments ───────────────────────────────────────────────────────

/**
 * GET /subcontractors/jobs/:jobId
 * List subcontractors assigned to a job, including assignment notes.
 */
router.get('/jobs/:jobId', async (req: AuthRequest, res, next) => {
  try {
    const { jobId } = req.params;
    const companyId = req.user!.companyId;

    const job = await prisma.job.findUnique({
      where: { id: jobId, companyId },
    });
    if (!job) {
      return res.status(404).json({ error: 'Job not found' });
    }

    const cacheKey = `subcontractors:job:${jobId}`;
    const cached = await cacheGet(cacheKey);
    if (cached) {
      return res.json(JSON.parse(cached));
    }

    const assignments = await prisma.subcontractorAssignment.findMany({
      where: { jobId },
      orderBy: { assignedAt: 'desc' },
      select: {
        id: true,
        notes: true,
        assignedAt: true,
        userId: true,
        subcontractor: {
          select: {
            id: true,
            name: true,
            phone: true,
            email: true,
            companyName: true,
            licenseNumber: true,
            insuranceInfo: true,
            specialties: true,
            rating: true,
            notes: true,
            createdAt: true,
            updatedAt: true,
          },
        },
        user: {
          select: { id: true, firstName: true, lastName: true, email: true },
        },
      },
    });

    const subcontractors = assignments.map((a) => ({
      assignmentId: a.id,
      assignedAt: a.assignedAt,
      notes: a.notes,
      assignedByUserId: a.userId,
      subcontractor: a.subcontractor,
      assignedBy: a.user,
    }));

    const result = { subcontractors, count: subcontractors.length };
    await cacheSet(cacheKey, result, 300);
    res.json(result);
  } catch (err) {
    next(err);
  }
});

/**
 * POST /subcontractors/jobs/:jobId/assign
 * Assign a (company-owned) subcontractor to a (company-owned) job.
 * Re-assigning an already-assigned subcontractor updates its notes.
 * Body: { subcontractorId, notes?, userId? }
 */
router.post('/jobs/:jobId/assign', async (req: AuthRequest, res, next) => {
  try {
    const { jobId } = req.params;
    const { subcontractorId, notes, userId } = req.body;
    const companyId = req.user!.companyId;

    if (!subcontractorId) {
      return res.status(400).json({ error: 'subcontractorId is required' });
    }

    const job = await prisma.job.findUnique({
      where: { id: jobId, companyId },
    });
    if (!job) {
      return res.status(404).json({ error: 'Job not found' });
    }

    const subcontractor = await prisma.subcontractor.findUnique({
      where: { id: subcontractorId, companyId },
    });
    if (!subcontractor) {
      return res.status(404).json({ error: 'Subcontractor not found' });
    }

    if (userId) {
      const user = await prisma.user.findUnique({
        where: { id: userId, companyId },
      });
      if (!user) {
        return res.status(404).json({ error: 'Assigned user not found' });
      }
    }

    const assignment = await prisma.subcontractorAssignment.upsert({
      where: { jobId_subcontractorId: { jobId, subcontractorId } },
      update: { notes: notes ?? undefined, userId: userId ?? undefined },
      create: {
        jobId,
        subcontractorId,
        notes: notes ?? undefined,
        userId: userId ?? undefined,
      },
      include: {
        subcontractor: {
          select: {
            id: true,
            name: true,
            phone: true,
            email: true,
            specialties: true,
            rating: true,
          },
        },
        user: { select: { id: true, firstName: true, lastName: true } },
      },
    });

    await invalidateJobSubcontractors(jobId);

    const io = getIO(req);
    io?.to(`job:${jobId}`).emit('subcontractor:assigned', { assignment });
    io?.to(`company:${companyId}`).emit('subcontractor:assigned', {
      jobId,
      assignment,
    });

    res.status(201).json({ assignment });
  } catch (err) {
    next(err);
  }
});

/**
 * DELETE /subcontractors/jobs/:jobId/assign/:subId
 * Remove a subcontractor's assignment from a job.
 */
router.delete('/jobs/:jobId/assign/:subId', async (req: AuthRequest, res, next) => {
  try {
    const { jobId, subId } = req.params;
    const companyId = req.user!.companyId;

    const job = await prisma.job.findUnique({
      where: { id: jobId, companyId },
    });
    if (!job) {
      return res.status(404).json({ error: 'Job not found' });
    }

    const deleted = await prisma.subcontractorAssignment.deleteMany({
      where: { jobId, subcontractorId: subId },
    });

    if (deleted.count === 0) {
      return res.status(404).json({ error: 'Assignment not found' });
    }

    await invalidateJobSubcontractors(jobId);

    const io = getIO(req);
    io?.to(`job:${jobId}`).emit('subcontractor:unassigned', {
      subcontractorId: subId,
    });
    io?.to(`company:${companyId}`).emit('subcontractor:unassigned', {
      jobId,
      subcontractorId: subId,
    });

    res.json({
      message: 'Subcontractor unassigned from job',
      subcontractorId: subId,
    });
  } catch (err) {
    next(err);
  }
});

export default router;
