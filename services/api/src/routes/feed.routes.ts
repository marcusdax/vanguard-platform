/**
 * Job Feed API Routes
 *
 * Real-time job communication hub for RoofPro CRM.
 * All queries are scoped to the user's company (multi-tenant via companyScope).
 * Real-time delivery is handled via Socket.IO rooms keyed `job:${jobId}`.
 *
 * Routes:
 *   GET    /feed/:jobId/posts              — Fetch all threaded posts for a job
 *   POST   /feed/:jobId/upload             — Upload attachments (multipart)
 *   POST   /feed/:jobId/posts              — Create a new top-level post
 *   POST   /feed/:jobId/posts/:postId/reply — Reply to an existing post
 *   DELETE /feed/:jobId/posts/:postId      — Delete a post (owner or system)
 *   POST   /feed/:jobId/system-posts       — Create a system post (events)
 */
import { Router, Response, NextFunction } from 'express';
import { AuthRequest } from '../middleware/auth';
import { companyScope } from '../middleware/company';
import { prisma } from '../prisma';
import { uploadMultiple } from '../middleware/upload';

const router = Router();
router.use(companyScope);

// ─── Shared select / include objects ─────────────────────────

const USER_SELECT = {
  id: true,
  firstName: true,
  lastName: true,
  role: true,
  avatarUrl: true,
};

const MENTION_INCLUDE = {
  user: { select: USER_SELECT },
};

const POST_INCLUDE = {
  user: { select: USER_SELECT },
  attachments: true,
  mentions: { include: MENTION_INCLUDE },
  replies: {
    orderBy: { createdAt: 'asc' },
    include: {
      user: { select: USER_SELECT },
      attachments: true,
      mentions: { include: MENTION_INCLUDE },
    },
  },
};

// ─── Helpers ─────────────────────────────────────────────────

const getFileType = (mimetype: string): string => {
  if (mimetype.startsWith('image/')) return 'IMAGE';
  if (mimetype.startsWith('video/')) return 'VIDEO';
  return 'DOCUMENT';
};

/** Emit an event to every socket in the `job:${jobId}` room. */
const emitToJob = (req: AuthRequest, jobId: string, event: string, payload: unknown) => {
  const io = req.app.get('io');
  if (io) {
    io.to(`job:${jobId}`).emit(event, payload);
  }
};

/** Emit a notification event to a specific user's room. */
const emitToUser = (req: AuthRequest, userId: string, event: string, payload: unknown) => {
  const io = req.app.get('io');
  if (io) {
    io.to(`user:${userId}`).emit(event, payload);
  }
};

/**
 * Validate that mentioned user IDs belong to the caller's company and are active.
 * Returns the list of valid user IDs.
 */
const validateMentions = async (
  companyId: string,
  mentionedUserIds: string[] | undefined
): Promise<string[]> => {
  if (!mentionedUserIds || !Array.isArray(mentionedUserIds) || mentionedUserIds.length === 0) {
    return [];
  }

  const valid = await prisma.user.findMany({
    where: {
      id: { in: mentionedUserIds },
      companyId,
      isActive: true,
    },
    select: { id: true },
  });

  return valid.map((u) => u.id);
};

/**
 * Validate that attachment IDs belong to the caller's company.
 * Returns the list of valid attachment IDs.
 */
const validateAttachments = async (
  companyId: string,
  attachmentIds: string[] | undefined
): Promise<string[]> => {
  if (!attachmentIds || !Array.isArray(attachmentIds) || attachmentIds.length === 0) {
    return [];
  }

  const valid = await prisma.feedAttachment.findMany({
    where: {
      id: { in: attachmentIds },
      companyId,
    },
    select: { id: true },
  });

  return valid.map((a) => a.id);
};

/**
 * Create notification records + emit real-time events for mentioned users.
 * The posting user is excluded from notifications (they don't get notified
 * about their own mention).
 */
const notifyMentions = async (
  req: AuthRequest,
  post: { id: string; jobId: string },
  mentionedUserIds: string[]
) => {
  const recipients = mentionedUserIds.filter((uid) => uid !== req.user!.id);
  if (recipients.length === 0) return;

  const notifications = await prisma.notification.createManyAndReturn({
    data: recipients.map((uid) => ({
      companyId: req.companyId!,
      userId: uid,
      type: 'FEED_MENTION',
      title: 'You were mentioned in a job post',
      body: `${req.user!.firstName} ${req.user!.lastName} mentioned you`,
      data: { postId: post.id, jobId: post.jobId },
    })),
  } as any);

  // Emit real-time notification to each recipient's user room
  for (const notification of notifications) {
    emitToUser(req, notification.userId, 'notification.new', notification);
  }
};

// ─── Routes ──────────────────────────────────────────────────

// GET /api/v1/feed/:jobId/posts
// Fetch all top-level posts for a job, with nested replies, attachments, and mentions.
router.get(
  '/:jobId/posts',
  async (req: AuthRequest, res, next) => {
    try {
      const { jobId } = req.params;

      // Verify the job belongs to the caller's company
      const jobExists = await prisma.job.findFirst({
        where: { id: jobId, companyId: req.companyId },
        select: { id: true, name: true },
      });

      if (!jobExists) {
        return res.status(404).json({ error: 'Job not found' });
      }

      const posts = await prisma.feedPost.findMany({
        where: {
          jobId,
          companyId: req.companyId,
          parentPostId: null,
        },
        orderBy: { createdAt: 'desc' },
        include: POST_INCLUDE as any,
      });

      res.json({ posts, jobName: jobExists.name });
    } catch (err) {
      next(err);
    }
  }
);

// POST /api/v1/feed/:jobId/upload
// Upload files as draft attachments (postId is null until linked).
router.post(
  '/:jobId/upload',
  uploadMultiple('files', 10),
  async (req: AuthRequest, res: Response, next: NextFunction) => {
    try {
      const { jobId } = req.params;

      // Verify the job belongs to the caller's company
      const jobExists = await prisma.job.findFirst({
        where: { id: jobId, companyId: req.companyId },
        select: { id: true },
      });

      if (!jobExists) {
        return res.status(404).json({ error: 'Job not found' });
      }

      const files = req.files as Express.Multer.File[] | undefined;
      if (!files || files.length === 0) {
        return res.status(400).json({ error: 'No files uploaded' });
      }

      const attachments = await Promise.all(
        files.map((file) =>
          prisma.feedAttachment.create({
            data: {
              postId: null,
              userId: req.user!.id,
              companyId: req.companyId!,
              fileUrl: `/uploads/${file.filename}`,
              fileType: getFileType(file.mimetype),
              fileName: file.originalname,
              size: file.size,
            },
            select: {
              id: true,
              fileUrl: true,
              fileType: true,
              fileName: true,
              size: true,
            },
          })
        )
      );

      res.status(201).json({ attachments });
    } catch (err) {
      next(err);
    }
  }
);

// POST /api/v1/feed/:jobId/posts
// Create a new top-level feed post with optional content, attachments, and mentions.
router.post(
  '/:jobId/posts',
  async (req: AuthRequest, res, next) => {
    try {
      const { jobId } = req.params;
      const { content, attachmentIds, mentionedUserIds } = req.body as {
        content?: string;
        attachmentIds?: string[];
        mentionedUserIds?: string[];
      };

      // Verify the job belongs to the caller's company
      const jobExists = await prisma.job.findFirst({
        where: { id: jobId, companyId: req.companyId },
        select: { id: true },
      });

      if (!jobExists) {
        return res.status(404).json({ error: 'Job not found' });
      }

      const validMentions = await validateMentions(req.companyId!, mentionedUserIds);
      const validAttachments = await validateAttachments(req.companyId!, attachmentIds);

      // Require at least content or attachments
      const hasContent = content && content.trim().length > 0;
      if (!hasContent && validAttachments.length === 0) {
        return res.status(400).json({ error: 'Post content or attachments are required' });
      }

      const post = await prisma.feedPost.create({
        data: {
          jobId,
          userId: req.user!.id,
          companyId: req.companyId!,
          content: content?.trim() || null,
          isSystem: false,
          attachments: {
            connect: validAttachments.map((id) => ({ id })),
          },
          mentions: {
            create: validMentions.map((uid) => ({ userId: uid })),
          },
        },
        include: POST_INCLUDE as any,
      });

      // Notify mentioned users (real-time + DB)
      await notifyMentions(req, post, validMentions);

      // Real-time broadcast to the job room
      emitToJob(req, jobId, 'feed.new_post', post);

      res.status(201).json({ post });
    } catch (err) {
      next(err);
    }
  }
);

// POST /api/v1/feed/:jobId/posts/:postId/reply
// Reply to an existing top-level post, creating a threaded reply.
router.post(
  '/:jobId/posts/:postId/reply',
  async (req: AuthRequest, res, next) => {
    try {
      const { jobId, postId } = req.params;
      const { content, mentionedUserIds } = req.body as {
        content?: string;
        mentionedUserIds?: string[];
      };

      // Verify the parent post exists and belongs to the company's job
      const parentPost = await prisma.feedPost.findFirst({
        where: { id: postId, jobId, companyId: req.companyId },
        select: { id: true, userId: true, jobId: true },
      });

      if (!parentPost) {
        return res.status(404).json({ error: 'Post not found' });
      }

      if (!content || !content.trim()) {
        return res.status(400).json({ error: 'Reply content is required' });
      }

      const validMentions = await validateMentions(req.companyId!, mentionedUserIds);

      const reply = await prisma.feedPost.create({
        data: {
          jobId,
          userId: req.user!.id,
          companyId: req.companyId!,
          content: content.trim(),
          parentPostId: postId,
          isSystem: false,
          mentions: {
            create: validMentions.map((uid) => ({ userId: uid })),
          },
        },
        include: POST_INCLUDE as any,
      });

      // Notify mentioned users
      await notifyMentions(req, reply, validMentions);

      // Notify the original poster that they got a reply
      if (parentPost.userId && parentPost.userId !== req.user!.id) {
        const replyNotification = await prisma.notification.create({
          data: {
            companyId: req.companyId!,
            userId: parentPost.userId,
            type: 'FEED_REPLY',
            title: 'New reply on your post',
            body: `${req.user!.firstName} ${req.user!.lastName} replied to your post`,
            data: { postId: reply.id, jobId, replyId: reply.id },
          },
        });
        emitToUser(req, parentPost.userId, 'notification.new', replyNotification);
      }

      // Real-time broadcast — includes parentId so clients know which thread to update
      emitToJob(req, jobId, 'feed.new_reply', {
        parentId: postId,
        reply,
      });

      res.status(201).json({ post: reply });
    } catch (err) {
      next(err);
    }
  }
);

// DELETE /api/v1/feed/:jobId/posts/:postId
// Delete a post. Only the original author may delete their own post.
router.delete(
  '/:jobId/posts/:postId',
  async (req: AuthRequest, res, next) => {
    try {
      const { jobId, postId } = req.params;

      const post = await prisma.feedPost.findFirst({
        where: { id: postId, jobId, companyId: req.companyId },
        select: { id: true, userId: true, isSystem: true, parentPostId: true },
      });

      if (!post) {
        return res.status(404).json({ error: 'Post not found' });
      }

      // Only the original author can delete (system posts can only be deleted by system)
      if (post.isSystem) {
        return res.status(403).json({ error: 'Cannot delete system posts manually' });
      }

      if (post.userId !== req.user!.id) {
        return res.status(403).json({ error: 'You can only delete your own posts' });
      }

      await prisma.feedPost.delete({
        where: { id: postId },
      });

      emitToJob(req, jobId, 'feed.post_deleted', { postId });

      res.json({ message: 'Post deleted' });
    } catch (err) {
      next(err);
    }
  }
);

// POST /api/v1/feed/:jobId/system-posts
// Create a system post (used by internal jobs/events, e.g. status changes, estimate approvals).
// Requires MANAGER+ role.
router.post(
  '/:jobId/system-posts',
  async (req: AuthRequest, res, next) => {
    try {
      const { jobId } = req.params;
      const { content, eventType, data } = req.body as {
        content?: string;
        eventType?: string;
        data?: Record<string, unknown>;
      };

      // Verify the job belongs to the caller's company
      const jobExists = await prisma.job.findFirst({
        where: { id: jobId, companyId: req.companyId },
        select: { id: true },
      });

      if (!jobExists) {
        return res.status(404).json({ error: 'Job not found' });
      }

      const post = await prisma.feedPost.create({
        data: {
          jobId,
          userId: null,
          companyId: req.companyId!,
          content: content || null,
          isSystem: true,
          data: {
            eventType: eventType || 'SYSTEM',
            triggeredByUserId: req.user!.id,
            ...(data || {}),
          },
        },
        include: POST_INCLUDE as any,
      });

      emitToJob(req, jobId, 'feed.new_post', post);

      res.status(201).json({ post });
    } catch (err) {
      next(err);
    }
  }
);

export default router;
