/**
 * Notifications API Routes
 *
 * User-scoped notification inbox for RoofPro CRM.
 * All queries are scoped to the authenticated user and their company
 * (multi-tenant). Real-time delivery is handled via Socket.IO rooms
 * keyed `user:${userId}`; the connection handler in server.ts joins
 * each user to that room on connect.
 */
import { Router } from 'express';
import { NotificationType } from '@prisma/client';
import { AuthRequest } from '../middleware/auth';
import { companyScope } from '../middleware/company';
import { prisma } from '../prisma';

const router = Router();
router.use(companyScope);

const VALID_NOTIFICATION_TYPES = new Set<string>(
  Object.values(NotificationType) as string[]
);

const MAX_PAGE_SIZE = 100;
const DEFAULT_PAGE_SIZE = 20;

/**
 * GET /notifications
 * List the current user's notifications.
 *   Query: page (default 1), limit (default 20, max 100), isRead (true|false)
 */
router.get(
  '/',
  async (req: AuthRequest, res, next) => {
    try {
      const page = Math.max(1, parseInt(req.query.page as string) || 1);
      const limit = Math.min(
        MAX_PAGE_SIZE,
        Math.max(1, parseInt(req.query.limit as string) || DEFAULT_PAGE_SIZE)
      );
      const skip = (page - 1) * limit;

      const where = {
        userId: req.user!.id,
        companyId: req.companyId,
        ...(req.query.isRead !== undefined
          ? { isRead: req.query.isRead === 'true' }
          : {}),
      };

      const [notifications, total] = await Promise.all([
        prisma.notification.findMany({
          where,
          orderBy: { createdAt: 'desc' },
          take: limit,
          skip,
        }),
        prisma.notification.count({ where }),
      ]);

      res.json({
        notifications,
        count: total,
        page,
        limit,
        totalPages: Math.ceil(total / limit),
        hasMore: page * limit < total,
      });
    } catch (err) {
      next(err);
    }
  }
);

/**
 * GET /notifications/unread
 * Count of unread notifications for the current user.
 */
router.get(
  '/unread',
  async (req: AuthRequest, res, next) => {
    try {
      const count = await prisma.notification.count({
        where: {
          userId: req.user!.id,
          companyId: req.companyId,
          isRead: false,
        },
      });
      res.json({ count });
    } catch (err) {
      next(err);
    }
  }
);

/**
 * PATCH /notifications/read
 * Mark notifications as read.
 *   Body: { ids: string[] } | { all: true }
 */
router.patch(
  '/read',
  async (req: AuthRequest, res, next) => {
    try {
      const { ids, all } = req.body;

      if (all === true) {
        const result = await prisma.notification.updateMany({
          where: {
            userId: req.user!.id,
            companyId: req.companyId,
            isRead: false,
          },
          data: { isRead: true },
        });
        return res.json({ count: result.count });
      }

      if (!Array.isArray(ids) || ids.length === 0) {
        return res.status(400).json({
          error: 'Request body must include "ids" array or { all: true }',
        });
      }

      await prisma.notification.updateMany({
        where: {
          userId: req.user!.id,
          companyId: req.companyId,
          id: { in: ids },
        },
        data: { isRead: true },
      });

      const notifications = await prisma.notification.findMany({
        where: {
          userId: req.user!.id,
          companyId: req.companyId,
          id: { in: ids },
        },
        orderBy: { createdAt: 'desc' },
      });

      res.json({ count: notifications.length, notifications });
    } catch (err) {
      next(err);
    }
  }
);

/**
 * PATCH /notifications/:id/read
 * Mark a single notification as read.
 */
router.patch(
  '/:id/read',
  async (req: AuthRequest, res, next) => {
    try {
      const result = await prisma.notification.updateMany({
        where: {
          id: req.params.id,
          userId: req.user!.id,
          companyId: req.companyId,
        },
        data: { isRead: true },
      });

      if (result.count === 0) {
        return res.status(404).json({ error: 'Notification not found' });
      }

      const notification = await prisma.notification.findUnique({
        where: {
          id: req.params.id,
          userId: req.user!.id,
          companyId: req.companyId,
        },
      });

      res.json({ notification });
    } catch (err) {
      next(err);
    }
  }
);

/**
 * PATCH /notifications/:id/unread
 * Mark a single notification as unread.
 */
router.patch(
  '/:id/unread',
  async (req: AuthRequest, res, next) => {
    try {
      const result = await prisma.notification.updateMany({
        where: {
          id: req.params.id,
          userId: req.user!.id,
          companyId: req.companyId,
        },
        data: { isRead: false },
      });

      if (result.count === 0) {
        return res.status(404).json({ error: 'Notification not found' });
      }

      const notification = await prisma.notification.findUnique({
        where: {
          id: req.params.id,
          userId: req.user!.id,
          companyId: req.companyId,
        },
      });

      res.json({ notification });
    } catch (err) {
      next(err);
    }
  }
);

/**
 * POST /notifications
 * Create notification(s). The `userId` field may be a single id or an
 * array of ids to fan out to multiple recipients. Recipients are validated
 * to belong to the caller's company. Emits `notification.new` via Socket.IO
 * to each recipient's `user:${userId}` room.
 *
 *   Body: { userId: string | string[], type, title, body?, data? }
 */
router.post(
  '/',
  async (req: AuthRequest, res, next) => {
    try {
      let { userId, type, title, body, data } = req.body as {
        userId: string | string[];
        type: string;
        title: string;
        body?: string;
        data?: unknown;
      };

      if (!userId) {
        return res.status(400).json({ error: 'userId is required' });
      }
      if (!type) {
        return res.status(400).json({ error: 'type is required' });
      }
      if (!VALID_NOTIFICATION_TYPES.has(type)) {
        return res.status(400).json({
          error: `Invalid type. Must be one of: ${[...VALID_NOTIFICATION_TYPES].join(', ')}`,
        });
      }
      if (!title) {
        return res.status(400).json({ error: 'title is required' });
      }

      const userIds = Array.isArray(userId)
        ? userId
        : [userId];

      // Ensure every recipient belongs to the caller's company.
      const recipients = await prisma.user.findMany({
        where: {
          id: { in: userIds },
          companyId: req.companyId,
          isActive: true,
        },
        select: { id: true },
      });

      const invalidIds = userIds.filter(
        (id) => !recipients.some((r) => r.id === id)
      );
      if (invalidIds.length > 0) {
        return res.status(400).json({
          error: 'One or more recipient userIds are invalid or not in this company',
          invalidIds,
        });
      }

      const now = new Date();
      const notifications = await prisma.notification.createManyAndReturn({
        data: userIds.map((id) => ({
          companyId: req.companyId,
          userId: id,
          type: type as NotificationType,
          title,
          body,
          data: data as any,
          createdAt: now,
        })),
      } as any);

      // Real-time delivery to each recipient's user room.
      const io = req.app.get('io') as any;
      if (io) {
        notifications.forEach((notification) => {
          io.to(`user:${notification.userId}`).emit(
            'notification.new',
            notification
          );
        });
      }

      res.status(201).json({ notifications, count: notifications.length });
    } catch (err) {
      next(err);
    }
  }
);

/**
 * DELETE /notifications/:id
 * Delete a notification owned by the current user.
 */
router.delete(
  '/:id',
  async (req: AuthRequest, res, next) => {
    try {
      const result = await prisma.notification.deleteMany({
        where: {
          id: req.params.id,
          userId: req.user!.id,
          companyId: req.companyId,
        },
      });

      if (result.count === 0) {
        return res.status(404).json({ error: 'Notification not found' });
      }

      res.json({ message: 'Notification deleted' });
    } catch (err) {
      next(err);
    }
  }
);

export default router;
