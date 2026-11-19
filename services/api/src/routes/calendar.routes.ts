import { Router, Response, NextFunction } from 'express';
import { AuthRequest } from '../middleware/auth';
import { companyScope } from '../middleware/company';
import { prisma } from '../prisma';

const router = Router();
router.use(companyScope);

// ─── Helpers ────────────────────────────────────────────

/**
 * Detect events that overlap in time for any of the given assignee user IDs.
 * Returns an array of conflict descriptors.
 */
async function detectConflicts(
  companyId: string,
  excludeEventId: string | null,
  startTime: Date,
  endTime: Date,
  assigneeUserIds: string[]
): Promise<Array<{ eventId: string; title: string; startTime: string; endTime: string }>> {
  if (assigneeUserIds.length === 0) return [];

  const where: {
    companyId: string;
    startTime: { lt: Date };
    endTime: { gt: Date };
    id?: { not: string };
    assignees: { some: { userId: { in: string[] } } };
  } = {
    companyId,
    startTime: { lt: endTime },
    endTime: { gt: startTime },
    assignees: {
      some: { userId: { in: assigneeUserIds } },
    },
  };

  if (excludeEventId) {
    where.id = { not: excludeEventId };
  }

  const conflicts = await prisma.calendarEvent.findMany({
    where,
    select: {
      id: true,
      title: true,
      startTime: true,
      endTime: true,
    },
  });

  return conflicts.map((c) => ({
    eventId: c.id,
    title: c.title,
    startTime: c.startTime.toISOString(),
    endTime: c.endTime.toISOString(),
  }));
}

// ─── Routes ────────────────────────────────────────────

// GET /api/v1/calendar?start=&end=
router.get(
  '/',
  async (req: AuthRequest, res: Response, next: NextFunction) => {
    try {
      const { start, end } = req.query;
      const where: {
        companyId: string;
        startTime?: { gte: Date; lt: Date };
      } = { companyId: req.companyId };

      if (start && end) {
        where.startTime = {
          gte: new Date(start as string),
          lt: new Date(end as string),
        };
      }

      const events = await prisma.calendarEvent.findMany({
        where,
        include: {
          assignees: { include: { user: true } },
          job: { select: { id: true, name: true, propertyAddress: true } },
          createdBy: {
            select: { id: true, firstName: true, lastName: true, email: true },
          },
        },
        orderBy: { startTime: 'asc' },
      });

      res.json({ data: events, count: events.length });
    } catch (err) {
      next(err);
    }
  }
);

// GET /api/v1/calendar/users — company users for assignee picker
router.get(
  '/users',
  async (req: AuthRequest, res: Response, next: NextFunction) => {
    try {
      const users = await prisma.user.findMany({
        where: { companyId: req.companyId, isActive: true },
        select: {
          id: true,
          email: true,
          firstName: true,
          lastName: true,
          role: true,
          phone: true,
          avatarUrl: true,
          isActive: true,
          createdAt: true,
          updatedAt: true,
        },
        orderBy: { firstName: 'asc' },
      });

      res.json({ data: users, count: users.length });
    } catch (err) {
      next(err);
    }
  }
);

// GET /api/v1/calendar/availability?start=&end=&userId=
router.get(
  '/availability',
  async (req: AuthRequest, res: Response, next: NextFunction) => {
    try {
      const { start, end, userId } = req.query;

      if (!start || !end) {
        return res
          .status(400)
          .json({ error: 'start and end query params are required' });
      }

      const where: {
        companyId: string;
        startTime: { gte: Date; lte: Date };
        assignees?: { some: { userId: string } };
      } = {
        companyId: req.companyId,
        startTime: {
          gte: new Date(start as string),
          lte: new Date(end as string),
        },
      };

      if (userId) {
        where.assignees = { some: { userId: userId as string } };
      }

      const events = await prisma.calendarEvent.findMany({
        where,
        include: {
          assignees: { include: { user: true } },
        },
        orderBy: { startTime: 'asc' },
      });

      const busySlots = events.map((e) => ({
        eventId: e.id,
        title: e.title,
        eventType: e.eventType,
        startTime: e.startTime,
        endTime: e.endTime,
        assignees: e.assignees.map((a) => a.userId),
      }));

      res.json({ data: busySlots, count: busySlots.length });
    } catch (err) {
      next(err);
    }
  }
);

// GET /api/v1/calendar/:id
router.get(
  '/:id',
  async (req: AuthRequest, res: Response, next: NextFunction) => {
    try {
      const event = await prisma.calendarEvent.findFirst({
        where: { id: req.params.id, companyId: req.companyId },
        include: {
          assignees: { include: { user: true } },
          job: {
            select: {
              id: true,
              name: true,
              propertyAddress: true,
            },
          },
          createdBy: {
            select: { id: true, firstName: true, lastName: true, email: true },
          },
        },
      });

      if (!event) {
        return res
          .status(404)
          .json({ error: 'Event not found', code: 'CALENDAR_EVENT_NOT_FOUND' });
      }

      res.json({ data: event });
    } catch (err) {
      next(err);
    }
  }
);

// POST /api/v1/calendar
router.post(
  '/',
  async (req: AuthRequest, res: Response, next: NextFunction) => {
    try {
      const {
        title,
        description,
        eventType = 'OTHER',
        startTime,
        endTime,
        allDay = false,
        location,
        propertyLatLng,
        jobId,
        assigneeUserIds = [],
      } = req.body;

      if (!title || !startTime || !endTime) {
        return res
          .status(400)
          .json({ error: 'title, startTime, and endTime are required' });
      }

      const start = new Date(startTime);
      const end = new Date(endTime);

      if (end <= start) {
        return res
          .status(400)
          .json({ error: 'endTime must be after startTime' });
      }

      const event = await prisma.calendarEvent.create({
        data: {
          companyId: req.companyId,
          title,
          description,
          eventType,
          startTime: start,
          endTime: end,
          allDay: Boolean(allDay),
          location,
          propertyLatLng,
          jobId: jobId || undefined,
          createdByUserId: req.user!.id,
          assignees: {
            create: assigneeUserIds.map((userId: string) => ({ userId })),
          },
        },
        include: {
          assignees: { include: { user: true } },
          job: { select: { id: true, name: true } },
          createdBy: {
            select: { id: true, firstName: true, lastName: true, email: true },
          },
        },
      });

      const conflicts = await detectConflicts(
        req.companyId,
        event.id,
        event.startTime,
        event.endTime,
        assigneeUserIds
      );

      const io = req.app.get('io');
      if (io) {
        io.to(`company:${req.companyId}`).emit('calendar:eventCreated', event);
      }

      res.status(201).json({ data: event, conflicts });
    } catch (err) {
      next(err);
    }
  }
);

// PATCH /api/v1/calendar/:id
router.patch(
  '/:id',
  async (req: AuthRequest, res: Response, next: NextFunction) => {
    try {
      const existing = await prisma.calendarEvent.findFirst({
        where: { id: req.params.id, companyId: req.companyId },
        include: { assignees: true },
      });

      if (!existing) {
        return res
          .status(404)
          .json({ error: 'Event not found', code: 'CALENDAR_EVENT_NOT_FOUND' });
      }

      const {
        title,
        description,
        eventType,
        startTime,
        endTime,
        allDay,
        location,
        propertyLatLng,
        jobId,
        assigneeUserIds,
      } = req.body;

      const updateData: Record<string, unknown> = {};

      if (title !== undefined) updateData.title = title;
      if (description !== undefined) updateData.description = description;
      if (eventType !== undefined) updateData.eventType = eventType;
      if (startTime !== undefined) updateData.startTime = new Date(startTime);
      if (endTime !== undefined) updateData.endTime = new Date(endTime);
      if (allDay !== undefined) updateData.allDay = allDay;
      if (location !== undefined) updateData.location = location;
      if (propertyLatLng !== undefined) updateData.propertyLatLng = propertyLatLng;
      if (jobId !== undefined) updateData.jobId = jobId || undefined;

      if (assigneeUserIds !== undefined) {
        updateData.assignees = {
          deleteMany: {},
          create: assigneeUserIds.map((userId: string) => ({ userId })),
        };
      }

      const event = await prisma.calendarEvent.update({
        where: { id: req.params.id },
        data: updateData,
        include: {
          assignees: { include: { user: true } },
          job: { select: { id: true, name: true } },
          createdBy: {
            select: { id: true, firstName: true, lastName: true, email: true },
          },
        },
      });

      // Conflict detection with updated assignees
      const currentAssigneeIds = event.assignees.map((a) => a.userId);
      let conflicts: Array<{
        eventId: string;
        title: string;
        startTime: string;
        endTime: string;
      }> = [];

      if (event.startTime && event.endTime) {
        conflicts = await detectConflicts(
          req.companyId,
          event.id,
          event.startTime,
          event.endTime,
          currentAssigneeIds
        );
      }

      const io = req.app.get('io');
      if (io) {
        io.to(`company:${req.companyId}`).emit('calendar:eventUpdated', event);
      }

      res.json({ data: event, conflicts });
    } catch (err) {
      next(err);
    }
  }
);

// DELETE /api/v1/calendar/:id
router.delete(
  '/:id',
  async (req: AuthRequest, res: Response, next: NextFunction) => {
    try {
      const existing = await prisma.calendarEvent.findFirst({
        where: { id: req.params.id, companyId: req.companyId },
      });

      if (!existing) {
        return res
          .status(404)
          .json({ error: 'Event not found', code: 'CALENDAR_EVENT_NOT_FOUND' });
      }

      await prisma.calendarEvent.delete({
        where: { id: req.params.id },
      });

      const io = req.app.get('io');
      if (io) {
        io.to(`company:${req.companyId}`).emit('calendar:eventDeleted', {
          id: req.params.id,
        });
      }

      res.json({ data: { id: req.params.id }, message: 'Event deleted' });
    } catch (err) {
      next(err);
    }
  }
);

export default router;
