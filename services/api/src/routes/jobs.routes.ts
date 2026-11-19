/**
 * RoofPro CRM — Jobs API Routes
 *
 * Provides job list + single-job lookup used by the estimate builder
 * (job context and job picker when creating a new estimate).
 * All queries are scoped to the authenticated company via `companyScope`.
 */
import { Router, Response, NextFunction } from 'express';
import { AuthRequest } from '../middleware/auth';
import { companyScope } from '../middleware/company';
import { prisma } from '../prisma';

const router = Router();
router.use(companyScope);

// GET /jobs — list jobs for the current company
router.get('/', async (req: AuthRequest, res: Response, next: NextFunction) => {
  try {
    const { status, q, limit = 100, offset = 0 } = req.query;

    const where: Record<string, unknown> = {
      companyId: req.user!.companyId,
    };

    if (status) {
      where.status = (status as string).toUpperCase();
    }

    if (q) {
      where.OR = [
        { name: { contains: q as string, mode: 'insensitive' } },
        { propertyAddress: { contains: q as string, mode: 'insensitive' } },
      ];
    }

    const [jobs, count] = await Promise.all([
      prisma.job.findMany({
        where,
        orderBy: { createdAt: 'desc' },
        take: Number(limit),
        skip: Number(offset),
        include: {
          lead: {
            select: { id: true, firstName: true, lastName: true, status: true },
          },
          createdBy: {
            select: { id: true, firstName: true, lastName: true, email: true },
          },
        },
      }),
      prisma.job.count({ where }),
    ]);

    res.json({ data: jobs, count });
  } catch (err) {
    next(err);
  }
});

// POST /jobs — create a job
router.post('/', async (req: AuthRequest, res: Response, next: NextFunction) => {
  try {
    const { name, propertyAddress, description, propertyLatLng } = req.body;

    if (!name || !propertyAddress) {
      return res
        .status(400)
        .json({ error: 'Missing required fields: name, propertyAddress' });
    }

    const job = await prisma.job.create({
      data: {
        companyId: req.user!.companyId,
        name,
        propertyAddress,
        description,
        propertyLatLng: propertyLatLng ?? undefined,
        createdByUserId: req.user!.id,
      },
    });

    res.status(201).json({ job });
  } catch (err) {
    next(err);
  }
});

// GET /jobs/:id — single job (used by the estimate builder + job detail page)
router.get('/:id', async (req: AuthRequest, res: Response, next: NextFunction) => {
  try {
    const job = await prisma.job.findFirst({
      where: { id: req.params.id, companyId: req.user!.companyId },
      include: {
        lead: {
          select: {
            id: true,
            firstName: true,
            lastName: true,
            phone: true,
            email: true,
            propertyAddress: true,
            status: true,
          },
        },
        createdBy: {
          select: { id: true, firstName: true, lastName: true, email: true },
        },
        estimates: {
          select: {
            id: true,
            name: true,
            status: true,
            totalPrice: true,
            totalCost: true,
            createdAt: true,
          },
        },
      },
    });

    if (!job) {
      return res.status(404).json({ error: 'Job not found' });
    }

    res.json({ job });
  } catch (err) {
    next(err);
  }
});

// PATCH /jobs/:id — update a job (e.g. advance the phase)
router.patch('/:id', async (req: AuthRequest, res: Response, next: NextFunction) => {
  try {
    const {
      name,
      propertyAddress,
      description,
      phase,
      status,
      propertyLatLng,
    } = req.body;

    const result = await prisma.job.updateMany({
      where: { id: req.params.id, companyId: req.user!.companyId },
      data: { name, propertyAddress, description, phase, status, propertyLatLng },
    });

    if (result.count === 0) {
      return res.status(404).json({ error: 'Job not found' });
    }

    const job = await prisma.job.findUnique({
      where: { id: req.params.id },
      include: { estimates: true },
    });

    res.json({ job });
  } catch (err) {
    next(err);
  }
});

// DELETE /jobs/:id
router.delete('/:id', async (req: AuthRequest, res: Response, next: NextFunction) => {
  try {
    const result = await prisma.job.deleteMany({
      where: { id: req.params.id, companyId: req.user!.companyId },
    });

    if (result.count === 0) {
      return res.status(404).json({ error: 'Job not found' });
    }

    res.json({ message: 'Job deleted', jobId: req.params.id });
  } catch (err) {
    next(err);
  }
});

export default router;
