/**
 * Materials & Price Database API Routes — RoofPro CRM
 *
 * Order management (MaterialOrder / MaterialOrderItem) and price database
 * (PriceDatabaseEntry). All queries are scoped to the authenticated user's
 * company (multi-tenant isolation via companyScope). Socket.IO events are
 * emitted on every create / update / delete so connected clients stay in sync
 * in real time.
 */
import { Router } from 'express';
import { AuthRequest } from '../middleware/auth';
import { companyScope } from '../middleware/company';
import { prisma } from '../prisma';
import { cacheGet, cacheSet, cacheDel } from '../config/redis';

const router = Router();
router.use(companyScope);

// ─────────────────────────────────────────────────────────────
// Constants & Helpers
// ─────────────────────────────────────────────────────────────

const VALID_STATUSES = ['PENDING', 'ORDERED', 'DELIVERED', 'BACKORDERED', 'CANCELLED'];

/** Resolve the Socket.IO server instance from the Express app. */
const getIO = (req: AuthRequest) => req.app.get('io');

/** Emit an event to the company-wide room. */
const emitToCompany = (req: AuthRequest, event: string, payload: unknown) => {
  const io = getIO(req);
  if (io) {
    io.to(`company:${req.companyId}`).emit(event, payload);
  }
};

/** Emit an event to a specific job room. */
const emitToJob = (req: AuthRequest, jobId: string, event: string, payload: unknown) => {
  const io = getIO(req);
  if (io) {
    io.to(`job:${jobId}`).emit(event, payload);
  }
};

/** Emit an event to both the company room and the job room for a order. */
const emitMaterialEvent = (req: AuthRequest, jobId: string, event: string, payload: unknown) => {
  emitToCompany(req, event, payload);
  emitToJob(req, jobId, event, payload);
};

/** Recalculate a material order's totalCost from its line items. */
const recalculateOrderTotal = async (orderId: string): Promise<number> => {
  const items = await prisma.materialOrderItem.findMany({
    where: { materialOrderId: orderId },
    select: { totalCost: true },
  });
  return items.reduce((sum, item) => sum + Number(item.totalCost), 0);
};

// ─────────────────────────────────────────────────────────────
// Price Database Routes
// Defined BEFORE /:id so that "/price-database" is not
// swallowed by the /:id parameterised route.
// ─────────────────────────────────────────────────────────────

/**
 * GET /price-database — List price database entries
 * Query: ?category=SHINGLES
 */
router.get('/price-database', async (req: AuthRequest, res, next) => {
  try {
    const { category, search } = req.query;

    // Cache the full entry list and apply filters in-memory for consistent
    // invalidation across all write operations.
    const cacheKey = `material:price-db:${req.companyId!}:list`;
    let entries: any[] | null = null;
    const cached = await cacheGet(cacheKey);
    if (cached) {
      entries = JSON.parse(cached) as any[];
    } else {
      entries = await prisma.priceDatabaseEntry.findMany({
        where: { companyId: req.companyId! },
        orderBy: [{ category: 'asc' }, { name: 'asc' }],
      });
      await cacheSet(cacheKey, entries, 300);
    }

    // Apply query filters in-memory
    let filtered = entries;
    if (category) {
      filtered = filtered.filter((e) => e.category === category);
    }
    if (search) {
      const lower = (search as string).toLowerCase();
      filtered = filtered.filter(
        (e) =>
          e.name?.toLowerCase().includes(lower) ||
          e.category?.toLowerCase().includes(lower)
      );
    }

    res.json({ data: filtered, count: filtered.length });
  } catch (error) {
    next(error);
  }
});

/**
 * POST /price-database — Create a price database entry
 * Body: { category, name, unit, defaultCost?, defaultPrice?, notes? }
 */
router.post('/price-database', async (req: AuthRequest, res, next) => {
  try {
    const { category, name, unit, defaultCost, defaultPrice, notes } = req.body;

    if (!category || !name || !unit) {
      return res.status(400).json({
        error: 'Missing required fields: category, name, unit',
      });
    }

    const entry = await prisma.priceDatabaseEntry.create({
      data: {
        companyId: req.companyId!,
        category,
        name,
        unit,
        defaultCost: defaultCost !== undefined ? Number(defaultCost) : undefined,
        defaultPrice: defaultPrice !== undefined ? Number(defaultPrice) : undefined,
        notes,
      },
    });

    await cacheDel(`material:price-db:${req.companyId!}:list`);

    emitToCompany(req, 'material:price-database:created', { entry });

    res.status(201).json({ data: entry, message: 'Price database entry created' });
  } catch (error) {
    next(error);
  }
});

/**
 * PATCH /price-database/:id — Update a price database entry
 * Body: any of { category, name, unit, defaultCost, defaultPrice, notes }
 */
router.patch('/price-database/:id', async (req: AuthRequest, res, next) => {
  try {
    const { category, name, unit, defaultCost, defaultPrice, notes } = req.body;

    const existing = await prisma.priceDatabaseEntry.findFirst({
      where: { id: req.params.id, companyId: req.companyId! },
      select: { id: true },
    });
    if (!existing) {
      return res.status(404).json({ error: 'Price database entry not found' });
    }

    const data: any = {};
    if (category !== undefined) data.category = category;
    if (name !== undefined) data.name = name;
    if (unit !== undefined) data.unit = unit;
    if (defaultCost !== undefined) data.defaultCost = Number(defaultCost);
    if (defaultPrice !== undefined) data.defaultPrice = Number(defaultPrice);
    if (notes !== undefined) data.notes = notes;

    const updated = await prisma.priceDatabaseEntry.update({
      where: { id: req.params.id },
      data,
    });

    await cacheDel(`material:price-db:${req.companyId!}:list`);

    emitToCompany(req, 'material:price-database:updated', { entry: updated });

    res.json({ data: updated });
  } catch (error) {
    next(error);
  }
});

/**
 * DELETE /price-database/:id — Delete a price database entry
 */
router.delete('/price-database/:id', async (req: AuthRequest, res, next) => {
  try {
    const existing = await prisma.priceDatabaseEntry.findFirst({
      where: { id: req.params.id, companyId: req.companyId! },
      select: { id: true },
    });
    if (!existing) {
      return res.status(404).json({ error: 'Price database entry not found' });
    }

    await prisma.priceDatabaseEntry.delete({
      where: { id: req.params.id },
    });

    await cacheDel(`material:price-db:${req.companyId!}:list`);

    emitToCompany(req, 'material:price-database:deleted', { id: req.params.id });

    res.json({ message: 'Price database entry deleted', id: req.params.id });
  } catch (error) {
    next(error);
  }
});

// ─────────────────────────────────────────────────────────────
// Material Order Routes
// ─────────────────────────────────────────────────────────────

/**
 * GET / — List material orders
 * Query: ?status=ORDERED&jobId=<uuid>&supplier=ABC Supply
 */
router.get('/', async (req: AuthRequest, res, next) => {
  try {
    const { status, jobId, supplier } = req.query;

    if (status && !VALID_STATUSES.includes(status as string)) {
      return res.status(400).json({
        error: `Invalid status. Must be one of: ${VALID_STATUSES.join(', ')}`,
      });
    }

    // Cache the full (unfiltered) order list and apply filters in-memory.
    // This keeps cache invalidation simple: any write just invalidates one key.
    const cacheKey = `materials:orders:${req.companyId!}:list`;
    let orders: any[] | null = null;
    const cached = await cacheGet(cacheKey);
    if (cached) {
      orders = JSON.parse(cached) as any[];
    } else {
      orders = await prisma.materialOrder.findMany({
        where: { companyId: req.companyId! },
        include: {
          items: true,
          job: { select: { id: true, name: true, propertyAddress: true } },
          orderedBy: { select: { id: true, firstName: true, lastName: true, email: true } },
        },
        orderBy: { createdAt: 'desc' },
      });
      await cacheSet(cacheKey, orders, 60);
    }

    // Apply query filters in-memory
    let filtered = orders;
    if (status) {
      filtered = filtered.filter((o) => o.status === status);
    }
    if (jobId) {
      filtered = filtered.filter((o) => o.jobId === jobId);
    }
    if (supplier) {
      const lower = (supplier as string).toLowerCase();
      filtered = filtered.filter((o) => o.supplierName?.toLowerCase().includes(lower));
    }

    res.json({ data: filtered, count: filtered.length });
  } catch (error) {
    next(error);
  }
});

/**
 * POST / — Create a material order
 * Body: { jobId, supplierName, items: [{ name, quantity, unit, unitCost }], notes?, trackingNumber? }
 */
router.post('/', async (req: AuthRequest, res, next) => {
  try {
    const { jobId, supplierName, items, notes, trackingNumber, status } = req.body;

    if (!jobId || !supplierName) {
      return res.status(400).json({
        error: 'Missing required fields: jobId, supplierName',
      });
    }

    if (!Array.isArray(items) || items.length === 0) {
      return res.status(400).json({
        error: 'items array is required and must not be empty',
      });
    }

    // Validate each item has required fields
    for (const item of items) {
      if (!item.name || item.quantity === undefined || !item.unit || item.unitCost === undefined) {
        return res.status(400).json({
          error: 'Each item requires: name, quantity, unit, unitCost',
        });
      }
    }

    // Verify the job belongs to this company
    const job = await prisma.job.findFirst({
      where: { id: jobId, companyId: req.companyId! },
      select: { id: true },
    });
    if (!job) {
      return res.status(404).json({ error: 'Job not found' });
    }

    const validatedStatus = status || 'PENDING';
    if (!VALID_STATUSES.includes(validatedStatus)) {
      return res.status(400).json({
        error: `Invalid status. Must be one of: ${VALID_STATUSES.join(', ')}`,
      });
    }

    // Build line items with calculated totals
    const itemData = items.map((item: any) => ({
      name: item.name as string,
      quantity: Number(item.quantity),
      unit: item.unit as string,
      unitCost: Number(item.unitCost),
      totalCost: Number(item.quantity) * Number(item.unitCost),
    }));

    const totalCost = itemData.reduce((sum, item) => sum + item.totalCost, 0);

    const order = await prisma.materialOrder.create({
      data: {
        companyId: req.companyId!,
        jobId,
        supplierName,
        notes,
        trackingNumber,
        status: validatedStatus,
        orderedByUserId: req.user!.id,
        totalCost,
        orderedAt: validatedStatus === 'ORDERED' ? new Date() : undefined,
        deliveredAt: validatedStatus === 'DELIVERED' ? new Date() : undefined,
        items: { create: itemData },
      },
      include: {
        items: true,
        job: { select: { id: true, name: true, propertyAddress: true } },
        orderedBy: { select: { id: true, firstName: true, lastName: true, email: true } },
      },
    });

    await cacheDel(`materials:orders:${req.companyId!}:list`);

    emitMaterialEvent(req, jobId, 'material:order:created', { order });

    res.status(201).json({ data: order, message: 'Material order created' });
  } catch (error) {
    next(error);
  }
});

/**
 * GET /:id — Get a single material order (with items)
 */
router.get('/:id', async (req: AuthRequest, res, next) => {
  try {
    const cacheKey = `material:order:${req.params.id}`;
    const cached = await cacheGet(cacheKey);
    if (cached) {
      return res.json(JSON.parse(cached));
    }

    const order = await prisma.materialOrder.findFirst({
      where: { id: req.params.id, companyId: req.companyId! },
      include: {
        items: true,
        job: { select: { id: true, name: true, propertyAddress: true, status: true, phase: true } },
        orderedBy: { select: { id: true, firstName: true, lastName: true, email: true } },
      },
    });
    if (!order) {
      return res.status(404).json({ error: 'Material order not found' });
    }

    const result = { data: order, itemCount: order.items.length };
    await cacheSet(cacheKey, result, 120);
    res.json(result);
  } catch (error) {
    next(error);
  }
});

/**
 * PATCH /:id — Update a material order
 * Body: any of { status, totalCost, trackingNumber, notes }
 * Emits material:order:status_changed when the status changes.
 */
router.patch('/:id', async (req: AuthRequest, res, next) => {
  try {
    const { status, totalCost, trackingNumber, notes } = req.body;

    const existing = await prisma.materialOrder.findFirst({
      where: { id: req.params.id, companyId: req.companyId! },
      select: { id: true, jobId: true, status: true },
    });
    if (!existing) {
      return res.status(404).json({ error: 'Material order not found' });
    }

    const data: any = {};
    let statusChanged = false;

    if (status !== undefined) {
      if (!VALID_STATUSES.includes(status)) {
        return res.status(400).json({
          error: `Invalid status. Must be one of: ${VALID_STATUSES.join(', ')}`,
        });
      }
      data.status = status;
      statusChanged = status !== existing.status;

      // Auto-manage timestamps on status transitions
      if (status === 'ORDERED' && existing.status !== 'ORDERED') {
        data.orderedAt = new Date();
      }
      if (status === 'DELIVERED' && existing.status !== 'DELIVERED') {
        data.deliveredAt = new Date();
      }
      if (status === 'CANCELLED') {
        data.deliveredAt = null;
      }
    }
    if (totalCost !== undefined) data.totalCost = Number(totalCost);
    if (trackingNumber !== undefined) data.trackingNumber = trackingNumber;
    if (notes !== undefined) data.notes = notes;

    const updated = await prisma.materialOrder.update({
      where: { id: req.params.id },
      data,
      include: {
        items: true,
        job: { select: { id: true, name: true, propertyAddress: true } },
        orderedBy: { select: { id: true, firstName: true, lastName: true, email: true } },
      },
    });

    await cacheDel(`materials:orders:${req.companyId!}:list`);
    await cacheDel(`material:order:${req.params.id}`);

    if (statusChanged) {
      emitMaterialEvent(req, existing.jobId, 'material:order:status_changed', {
        orderId: req.params.id,
        previousStatus: existing.status,
        newStatus: status,
        order: updated,
      });
    }
    emitMaterialEvent(req, existing.jobId, 'material:order:updated', { order: updated });

    res.json({ data: updated });
  } catch (error) {
    next(error);
  }
});

/**
 * DELETE /:id — Delete a material order (and its line items cascade)
 */
router.delete('/:id', async (req: AuthRequest, res, next) => {
  try {
    const existing = await prisma.materialOrder.findFirst({
      where: { id: req.params.id, companyId: req.companyId! },
      select: { id: true, jobId: true },
    });
    if (!existing) {
      return res.status(404).json({ error: 'Material order not found' });
    }

    await prisma.materialOrder.delete({
      where: { id: req.params.id },
    });

    await cacheDel(`materials:orders:${req.companyId!}:list`);
    await cacheDel(`material:order:${req.params.id}`);

    emitMaterialEvent(req, existing.jobId, 'material:order:deleted', {
      id: req.params.id,
    });

    res.json({ message: 'Material order deleted', id: req.params.id });
  } catch (error) {
    next(error);
  }
});

// ─────────────────────────────────────────────────────────────
// Order Item Routes
// Defined before /:id for clarity and safety.
// ─────────────────────────────────────────────────────────────

/**
 * POST /:id/items — Add a line item to a material order
 * Body: { name, quantity, unit, unitCost }
 */
router.post('/:id/items', async (req: AuthRequest, res, next) => {
  try {
    const { name, quantity, unit, unitCost } = req.body;

    if (!name || quantity === undefined || !unit || unitCost === undefined) {
      return res.status(400).json({
        error: 'Missing required fields: name, quantity, unit, unitCost',
      });
    }

    const order = await prisma.materialOrder.findFirst({
      where: { id: req.params.id, companyId: req.companyId! },
      select: { id: true, jobId: true },
    });
    if (!order) {
      return res.status(404).json({ error: 'Material order not found' });
    }

    const item = await prisma.materialOrderItem.create({
      data: {
        materialOrderId: req.params.id,
        name,
        quantity: Number(quantity),
        unit,
        unitCost: Number(unitCost),
        totalCost: Number(quantity) * Number(unitCost),
      },
    });

    // Recalculate the order's totalCost
    const totalCost = await recalculateOrderTotal(req.params.id);
    await prisma.materialOrder.update({
      where: { id: req.params.id },
      data: { totalCost },
    });

    await cacheDel(`materials:orders:${req.companyId!}:list`);
    await cacheDel(`material:order:${req.params.id}`);

    emitMaterialEvent(req, order.jobId, 'material:order:item:created', {
      orderId: req.params.id,
      item,
    });

    res.status(201).json({ data: item });
  } catch (error) {
    next(error);
  }
});

/**
 * PATCH /:id/items/:itemId — Update a line item on a material order
 * Body: any of { name, quantity, unit, unitCost }
 */
router.patch('/:id/items/:itemId', async (req: AuthRequest, res, next) => {
  try {
    const { name, quantity, unit, unitCost } = req.body;

    // Verify the order belongs to the company
    const order = await prisma.materialOrder.findFirst({
      where: { id: req.params.id, companyId: req.companyId! },
      select: { id: true, jobId: true },
    });
    if (!order) {
      return res.status(404).json({ error: 'Material order not found' });
    }

    // Verify the item belongs to this specific order
    const existingItem = await prisma.materialOrderItem.findUnique({
      where: { id: req.params.itemId },
      select: { materialOrderId: true, quantity: true, unitCost: true },
    });
    if (!existingItem || existingItem.materialOrderId !== req.params.id) {
      return res.status(404).json({ error: 'Material order item not found' });
    }

    const data: any = {};
    if (name !== undefined) data.name = name;
    if (quantity !== undefined) data.quantity = Number(quantity);
    if (unit !== undefined) data.unit = unit;
    if (unitCost !== undefined) data.unitCost = Number(unitCost);

    // Recalculate totalCost when quantity or unitCost changes
    if (quantity !== undefined || unitCost !== undefined) {
      const newQuantity = quantity !== undefined ? Number(quantity) : existingItem.quantity;
      const newUnitCost = unitCost !== undefined ? Number(unitCost) : existingItem.unitCost;
      data.totalCost = newQuantity * newUnitCost;
    }

    const item = await prisma.materialOrderItem.update({
      where: { id: req.params.itemId },
      data,
    });

    // Recalculate the order's totalCost
    const totalCost = await recalculateOrderTotal(req.params.id);
    await prisma.materialOrder.update({
      where: { id: req.params.id },
      data: { totalCost },
    });

    await cacheDel(`materials:orders:${req.companyId!}:list`);
    await cacheDel(`material:order:${req.params.id}`);

    emitMaterialEvent(req, order.jobId, 'material:order:item:updated', {
      orderId: req.params.id,
      itemId: req.params.itemId,
      item,
    });

    res.json({ data: item });
  } catch (error) {
    next(error);
  }
});

/**
 * DELETE /:id/items/:itemId — Remove a line item from a material order
 */
router.delete('/:id/items/:itemId', async (req: AuthRequest, res, next) => {
  try {
    const order = await prisma.materialOrder.findFirst({
      where: { id: req.params.id, companyId: req.companyId! },
      select: { id: true, jobId: true },
    });
    if (!order) {
      return res.status(404).json({ error: 'Material order not found' });
    }

    const existingItem = await prisma.materialOrderItem.findUnique({
      where: { id: req.params.itemId },
      select: { materialOrderId: true },
    });
    if (!existingItem || existingItem.materialOrderId !== req.params.id) {
      return res.status(404).json({ error: 'Material order item not found' });
    }

    await prisma.materialOrderItem.delete({
      where: { id: req.params.itemId },
    });

    // Recalculate the order's totalCost after removal
    const totalCost = await recalculateOrderTotal(req.params.id);
    await prisma.materialOrder.update({
      where: { id: req.params.id },
      data: { totalCost },
    });

    await cacheDel(`materials:orders:${req.companyId!}:list`);
    await cacheDel(`material:order:${req.params.id}`);

    emitMaterialEvent(req, order.jobId, 'material:order:item:deleted', {
      orderId: req.params.id,
      itemId: req.params.itemId,
    });

    res.json({ message: 'Material order item deleted', id: req.params.itemId });
  } catch (error) {
    next(error);
  }
});

export default router;
