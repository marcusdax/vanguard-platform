/**
 * Vanguard H3 Spaces, Chronological Field Feed, Image Uploads & Live Streaming
 * Mounted at /api/v1/field/social (protected by authMiddleware + companyScope).
 */
import { Router, Response, NextFunction } from 'express';
import { Prisma } from '@prisma/client';
import { AuthRequest } from '../middleware/auth';
import { companyScope } from '../middleware/company';
import { prisma } from '../prisma';
import { uploadMultiple } from '../middleware/upload';
import {
  computeSpaceTrust,
  sanitizeMediaUrls,
  slugifySpaceName,
} from '../services/field/social';
import {
  analyzeRoofPhotoAsync,
  APERTURE_CATALOG,
  estimateRoofPhotogrammetryAsync,
} from '../services/ai/apertureInspector';

const router = Router();
router.use(companyScope);

const POST_KINDS = ['NOTE', 'LOT_HANDOFF', 'LIVE_CLIP'] as const;

async function ensureStarterSpaces(companyId: string, fallbackUserId: string) {
  const existingCount = await prisma.fieldSpace.count({ where: { companyId } });
  if (existingCount > 0) return;

  const [sampleJob, sampleStorm] = await Promise.all([
    prisma.job.findFirst({ where: { companyId }, select: { id: true, name: true } }),
    prisma.stormEvent.findFirst({ where: { companyId }, orderBy: { startDate: 'desc' }, select: { id: true, name: true } }),
  ]);

  await prisma.fieldSpace.createMany({
    data: [
      {
        companyId,
        slug: 'north-texas-hail-desk',
        name: 'North Texas Hail Desk',
        tagline: 'Live field inspections, shingle test-square captures, and verified adjuster packets across Collin & Denton Counties.',
        district: 'Plano · Frisco · McKinney',
        roleLabel: 'Storm Crew',
        lat: 33.05,
        lng: -96.75,
        jobId: sampleJob?.id ?? null,
        stormEventId: sampleStorm?.id ?? null,
        createdById: fallbackUserId,
      },
      {
        companyId,
        slug: 'rapid-tarp-response',
        name: 'Rapid Tarp & Drone Dispatch',
        tagline: 'On-site Part 107 aerial sweeps, ridge-cap damage logs, and live roof-walk briefings.',
        district: 'DFW Metro Swath',
        roleLabel: 'Drone Unit',
        lat: 33.15,
        lng: -96.82,
        jobId: sampleJob?.id ?? null,
        createdById: fallbackUserId,
      },
    ],
    skipDuplicates: true,
  });
}

async function hydrateSpaceSummaries(companyId: string, viewerUserId: string) {
  const [spaces, follows, postCounts, liveSessions, jobs, packets] = await Promise.all([
    prisma.fieldSpace.findMany({
      where: { companyId },
      orderBy: { createdAt: 'desc' },
    }),
    prisma.fieldSpaceFollow.findMany({
      where: { companyId },
      select: { spaceId: true, userId: true },
    }),
    prisma.fieldSocialPost.groupBy({
      by: ['spaceId'],
      where: { companyId, spaceId: { not: null } },
      _count: { _all: true },
    }),
    prisma.fieldLiveSession.findMany({
      where: { companyId, status: 'LIVE' },
      orderBy: { startedAt: 'desc' },
    }),
    prisma.job.findMany({
      where: { companyId },
      select: { id: true, name: true, propertyAddress: true, phase: true, budget: true, status: true },
      take: 100,
    }),
    prisma.fieldEvidencePacket.findMany({
      where: { companyId },
      select: { jobId: true },
    }),
  ]);

  const followerCountBySpace = new Map<string, number>();
  const followedByViewer = new Set<string>();
  for (const f of follows) {
    followerCountBySpace.set(f.spaceId, (followerCountBySpace.get(f.spaceId) ?? 0) + 1);
    if (f.userId === viewerUserId) followedByViewer.add(f.spaceId);
  }

  const postsBySpace = new Map<string, number>();
  for (const p of postCounts) {
    if (p.spaceId) postsBySpace.set(p.spaceId, p._count._all);
  }

  const packetsByJob = new Map<string, number>();
  for (const pk of packets) {
    packetsByJob.set(pk.jobId, (packetsByJob.get(pk.jobId) ?? 0) + 1);
  }

  const jobById = new Map(jobs.map((j) => [j.id, j]));
  const liveBySpace = new Map<string, (typeof liveSessions)[number]>();
  for (const s of liveSessions) {
    if (s.spaceId && !liveBySpace.has(s.spaceId)) liveBySpace.set(s.spaceId, s);
  }

  return spaces.map((sp) => {
    const job = sp.jobId ? jobById.get(sp.jobId) ?? null : null;
    const sealedPackets = sp.jobId ? packetsByJob.get(sp.jobId) ?? 0 : 0;
    const trust = computeSpaceTrust(sealedPackets, job ? 1 : 0);
    const live = liveBySpace.get(sp.id) ?? null;
    return {
      ...sp,
      followerCount: followerCountBySpace.get(sp.id) ?? 0,
      isFollowing: followedByViewer.has(sp.id),
      postCount: postsBySpace.get(sp.id) ?? 0,
      trust,
      job,
      activeLive: live,
    };
  });
}

// ── Spaces ───────────────────────────────────────────────────────────────

router.get('/spaces', async (req: AuthRequest, res: Response, next: NextFunction) => {
  try {
    await ensureStarterSpaces(req.companyId!, req.user!.id);
    const data = await hydrateSpaceSummaries(req.companyId!, req.user!.id);
    res.json({ data, count: data.length });
  } catch (err) {
    next(err);
  }
});

router.post('/spaces', async (req: AuthRequest, res: Response, next: NextFunction) => {
  try {
    const body = (req.body ?? {}) as Record<string, unknown>;
    const name = typeof body.name === 'string' ? body.name.trim() : '';
    if (name.length < 2 || name.length > 80) {
      return res.status(400).json({ error: 'Space name must be between 2 and 80 characters' });
    }

    const baseSlug = slugifySpaceName(name);
    let slug = baseSlug;
    const conflict = await prisma.fieldSpace.findUnique({
      where: { companyId_slug: { companyId: req.companyId!, slug } },
      select: { id: true },
    });
    if (conflict) {
      slug = `${baseSlug}-${Date.now().toString(36).slice(-4)}`;
    }

    const latVal = body.lat != null ? Number(body.lat) : null;
    const lngVal = body.lng != null ? Number(body.lng) : null;
    const coverUrl =
      typeof body.coverUrl === 'string' && body.coverUrl.trim()
        ? body.coverUrl.trim().slice(0, 500)
        : null;

    const space = await prisma.fieldSpace.create({
      data: {
        companyId: req.companyId!,
        slug,
        name,
        tagline: typeof body.tagline === 'string' ? body.tagline.trim().slice(0, 280) : null,
        district: typeof body.district === 'string' ? body.district.trim().slice(0, 120) : 'Field Sector',
        roleLabel: typeof body.roleLabel === 'string' ? body.roleLabel.trim().slice(0, 48) : 'Territory',
        coverUrl,
        lat: latVal != null && Number.isFinite(latVal) ? latVal : null,
        lng: lngVal != null && Number.isFinite(lngVal) ? lngVal : null,
        jobId: typeof body.jobId === 'string' && body.jobId.trim() ? body.jobId.trim() : null,
        stormEventId: typeof body.stormEventId === 'string' && body.stormEventId.trim() ? body.stormEventId.trim() : null,
        createdById: req.user!.id,
      },
    });

    await prisma.fieldSpaceFollow.create({
      data: { spaceId: space.id, userId: req.user!.id, companyId: req.companyId! },
    });

    const io = req.app.get('io');
    if (io) io.to(`company:${req.companyId!}`).emit('field:space:created', space);

    res.status(201).json({ data: space });
  } catch (err) {
    next(err);
  }
});

router.get('/spaces/:slug', async (req: AuthRequest, res: Response, next: NextFunction) => {
  try {
    await ensureStarterSpaces(req.companyId!, req.user!.id);
    const list = await hydrateSpaceSummaries(req.companyId!, req.user!.id);
    const found = list.find((s) => s.slug === req.params.slug || s.id === req.params.slug);
    if (!found) {
      return res.status(404).json({ error: 'Space not found' });
    }
    res.json({ data: found });
  } catch (err) {
    next(err);
  }
});

router.post('/spaces/:id/follow', async (req: AuthRequest, res: Response, next: NextFunction) => {
  try {
    const space = await prisma.fieldSpace.findFirst({
      where: { id: req.params.id, companyId: req.companyId! },
      select: { id: true },
    });
    if (!space) return res.status(404).json({ error: 'Space not found' });

    const existing = await prisma.fieldSpaceFollow.findUnique({
      where: { spaceId_userId: { spaceId: space.id, userId: req.user!.id } },
    });
    let following: boolean;
    if (existing) {
      await prisma.fieldSpaceFollow.delete({ where: { id: existing.id } });
      following = false;
    } else {
      await prisma.fieldSpaceFollow.create({
        data: { spaceId: space.id, userId: req.user!.id, companyId: req.companyId! },
      });
      following = true;
    }
    const followerCount = await prisma.fieldSpaceFollow.count({ where: { spaceId: space.id } });
    res.json({ following, followerCount });
  } catch (err) {
    next(err);
  }
});

// ── Media Upload (Images / Video Clips) ──────────────────────────────────

router.post('/upload', uploadMultiple('files', 6), async (req: AuthRequest, res: Response, next: NextFunction) => {
  try {
    const uploaded = (req.files as Express.Multer.File[] | undefined) ?? [];
    if (uploaded.length === 0) {
      return res.status(400).json({ error: 'No files uploaded' });
    }
    const files = uploaded.map((f) => ({
      url: `/uploads/${f.filename}`,
      fileName: f.originalname,
      mimeType: f.mimetype,
      size: f.size,
      fileType: f.mimetype.startsWith('video/')
        ? 'VIDEO'
        : f.mimetype.startsWith('image/')
          ? 'IMAGE'
          : 'DOCUMENT',
    }));
    res.status(201).json({ files });
  } catch (err) {
    next(err);
  }
});

// ── Chronological Social Feed ────────────────────────────────────────────

router.get('/posts', async (req: AuthRequest, res: Response, next: NextFunction) => {
  try {
    const companyId = req.companyId!;
    await ensureStarterSpaces(companyId, req.user!.id);

    const spaceId = typeof req.query.spaceId === 'string' && req.query.spaceId ? req.query.spaceId : undefined;
    const followingOnly = req.query.followingOnly === 'true';

    let spaceFilterIds: string[] | undefined;
    if (followingOnly) {
      const followed = await prisma.fieldSpaceFollow.findMany({
        where: { companyId, userId: req.user!.id },
        select: { spaceId: true },
      });
      spaceFilterIds = followed.map((f) => f.spaceId);
      if (spaceFilterIds.length === 0) {
        return res.json({ data: [], count: 0 });
      }
    }

    const posts = await prisma.fieldSocialPost.findMany({
      where: {
        companyId,
        ...(spaceId ? { spaceId } : {}),
        ...(spaceFilterIds ? { spaceId: { in: spaceFilterIds } } : {}),
      },
      include: {
        space: { select: { id: true, slug: true, name: true, district: true, roleLabel: true } },
        replies: { orderBy: { createdAt: 'asc' } },
        keeps: { select: { userId: true } },
      },
      orderBy: { createdAt: 'desc' },
      take: 60,
    });

    const userIds = new Set<string>();
    const jobIds = new Set<string>();
    for (const p of posts) {
      userIds.add(p.authorId);
      if (p.jobId) jobIds.add(p.jobId);
      for (const r of p.replies) userIds.add(r.authorId);
    }

    const [users, jobs] = await Promise.all([
      prisma.user.findMany({
        where: { id: { in: Array.from(userIds) } },
        select: { id: true, firstName: true, lastName: true, role: true, avatarUrl: true },
      }),
      prisma.job.findMany({
        where: { id: { in: Array.from(jobIds) }, companyId },
        select: { id: true, name: true, propertyAddress: true, phase: true, budget: true, status: true },
      }),
    ]);

    const userById = new Map<string, (typeof users)[number]>();
    for (const u of users) userById.set(u.id, u);
    const jobById = new Map<string, (typeof jobs)[number]>();
    for (const j of jobs) jobById.set(j.id, j);

    const data = posts.map((p) => ({
      id: p.id,
      spaceId: p.spaceId,
      space: p.space,
      kind: p.kind,
      title: p.title,
      body: p.body,
      jobId: p.jobId,
      job: p.jobId ? jobById.get(p.jobId) ?? null : null,
      mediaUrls: Array.isArray(p.mediaUrls) ? p.mediaUrls : [],
      createdAt: p.createdAt,
      author: userById.get(p.authorId) ?? {
        id: p.authorId,
        firstName: 'Field',
        lastName: 'Operator',
        role: 'FIELD_TECH',
        avatarUrl: null,
      },
      keepCount: p.keeps.length,
      keptByMe: p.keeps.some((k) => k.userId === req.user!.id),
      replies: p.replies.map((r) => ({
        id: r.id,
        body: r.body,
        createdAt: r.createdAt,
        author: userById.get(r.authorId) ?? {
          id: r.authorId,
          firstName: 'Field',
          lastName: 'Operator',
          role: 'FIELD_TECH',
          avatarUrl: null,
        },
      })),
    }));

    res.json({ data, count: data.length });
  } catch (err) {
    next(err);
  }
});

router.post('/posts', async (req: AuthRequest, res: Response, next: NextFunction) => {
  try {
    const body = (req.body ?? {}) as Record<string, unknown>;
    const text = typeof body.body === 'string' ? body.body.trim() : '';
    if (text.length < 2 || text.length > 4000) {
      return res.status(400).json({ error: 'Post body must be between 2 and 4000 characters' });
    }

    const kindRaw = typeof body.kind === 'string' ? body.kind.toUpperCase() : 'NOTE';
    const kind = (POST_KINDS as readonly string[]).includes(kindRaw) ? kindRaw : 'NOTE';
    const title = typeof body.title === 'string' && body.title.trim() ? body.title.trim().slice(0, 140) : null;
    const spaceId = typeof body.spaceId === 'string' && body.spaceId.trim() ? body.spaceId.trim() : null;
    const jobId = typeof body.jobId === 'string' && body.jobId.trim() ? body.jobId.trim() : null;

    if (spaceId) {
      const sp = await prisma.fieldSpace.findFirst({ where: { id: spaceId, companyId: req.companyId! }, select: { id: true } });
      if (!sp) return res.status(404).json({ error: 'Target Space not found' });
    }
    if (jobId) {
      const jb = await prisma.job.findFirst({ where: { id: jobId, companyId: req.companyId! }, select: { id: true } });
      if (!jb) return res.status(404).json({ error: 'Referenced Job not found' });
    }

    const mediaUrls = sanitizeMediaUrls(body.mediaUrls) as unknown as Prisma.InputJsonValue;

    const post = await prisma.fieldSocialPost.create({
      data: {
        companyId: req.companyId!,
        spaceId,
        authorId: req.user!.id,
        kind,
        title,
        body: text,
        jobId,
        mediaUrls,
      },
    });

    const io = req.app.get('io');
    if (io) {
      io.to(`company:${req.companyId!}`).emit('field:social:post', { id: post.id, spaceId });
      if (spaceId) io.to(`space:${spaceId}`).emit('field:social:post', { id: post.id, spaceId });
    }

    res.status(201).json({ data: post });
  } catch (err) {
    next(err);
  }
});

router.post('/posts/:id/reply', async (req: AuthRequest, res: Response, next: NextFunction) => {
  try {
    const post = await prisma.fieldSocialPost.findFirst({
      where: { id: req.params.id, companyId: req.companyId! },
      select: { id: true, spaceId: true },
    });
    if (!post) return res.status(404).json({ error: 'Post not found' });

    const text = typeof req.body?.body === 'string' ? req.body.body.trim() : '';
    if (!text || text.length > 2000) {
      return res.status(400).json({ error: 'Reply body must be 1 to 2000 characters' });
    }

    const reply = await prisma.fieldSocialReply.create({
      data: {
        postId: post.id,
        companyId: req.companyId!,
        authorId: req.user!.id,
        body: text,
      },
    });

    const io = req.app.get('io');
    if (io) {
      io.to(`company:${req.companyId!}`).emit('field:social:reply', { postId: post.id, replyId: reply.id });
    }

    res.status(201).json({ data: reply });
  } catch (err) {
    next(err);
  }
});

router.post('/posts/:id/keep', async (req: AuthRequest, res: Response, next: NextFunction) => {
  try {
    const post = await prisma.fieldSocialPost.findFirst({
      where: { id: req.params.id, companyId: req.companyId! },
      select: { id: true },
    });
    if (!post) return res.status(404).json({ error: 'Post not found' });

    const existing = await prisma.fieldSocialKeep.findUnique({
      where: { postId_userId: { postId: post.id, userId: req.user!.id } },
    });
    let kept: boolean;
    if (existing) {
      await prisma.fieldSocialKeep.delete({ where: { id: existing.id } });
      kept = false;
    } else {
      await prisma.fieldSocialKeep.create({
        data: { postId: post.id, userId: req.user!.id, companyId: req.companyId! },
      });
      kept = true;
    }
    const keepCount = await prisma.fieldSocialKeep.count({ where: { postId: post.id } });
    res.json({ kept, keepCount });
  } catch (err) {
    next(err);
  }
});

// ── Live Streaming Sessions (WebRTC + Frame Relay) ───────────────────────

router.get('/live/active', async (req: AuthRequest, res: Response, next: NextFunction) => {
  try {
    const sessions = await prisma.fieldLiveSession.findMany({
      where: { companyId: req.companyId!, status: 'LIVE' },
      include: {
        space: { select: { id: true, slug: true, name: true, district: true } },
      },
      orderBy: { startedAt: 'desc' },
      take: 20,
    });
    const hostIds = Array.from(new Set(sessions.map((s) => s.hostUserId)));
    const hosts = hostIds.length
      ? await prisma.user.findMany({
          where: { id: { in: hostIds } },
          select: { id: true, firstName: true, lastName: true, role: true },
        })
      : [];
    const hostById = new Map(hosts.map((h) => [h.id, h]));

    res.json({
      data: sessions.map((s) => ({
        ...s,
        host: hostById.get(s.hostUserId) ?? null,
      })),
    });
  } catch (err) {
    next(err);
  }
});

router.post('/live/start', async (req: AuthRequest, res: Response, next: NextFunction) => {
  try {
    const body = (req.body ?? {}) as Record<string, unknown>;
    const title =
      typeof body.title === 'string' && body.title.trim()
        ? body.title.trim().slice(0, 120)
        : 'Field Roof Walkthrough · Live';
    const spaceId = typeof body.spaceId === 'string' && body.spaceId.trim() ? body.spaceId.trim() : null;
    const transport = body.transport === 'WEBRTC' ? 'WEBRTC' : 'FRAME_RELAY';

    await prisma.fieldLiveSession.updateMany({
      where: { companyId: req.companyId!, hostUserId: req.user!.id, status: 'LIVE' },
      data: { status: 'ENDED', endedAt: new Date() },
    });

    const session = await prisma.fieldLiveSession.create({
      data: {
        companyId: req.companyId!,
        spaceId,
        hostUserId: req.user!.id,
        title,
        status: 'LIVE',
        transport,
        viewerCount: 1,
      },
      include: { space: { select: { id: true, slug: true, name: true } } },
    });

    const io = req.app.get('io');
    if (io) {
      io.to(`company:${req.companyId!}`).emit('field:live:started', session);
      if (spaceId) io.to(`space:${spaceId}`).emit('field:live:started', session);
    }

    res.status(201).json({ data: session });
  } catch (err) {
    next(err);
  }
});

router.post('/live/:id/frame', async (req: AuthRequest, res: Response, next: NextFunction) => {
  try {
    const session = await prisma.fieldLiveSession.findFirst({
      where: { id: req.params.id, companyId: req.companyId!, status: 'LIVE' },
    });
    if (!session) return res.status(404).json({ error: 'Active stream not found' });

    const frame = typeof req.body?.frame === 'string' ? req.body.frame : '';
    if (!frame || (!frame.startsWith('data:image/') && !frame.startsWith('/uploads/'))) {
      return res.status(400).json({ error: 'Valid image frame required' });
    }
    if (frame.length > 350_000) {
      return res.status(413).json({ error: 'Frame payload exceeds 350KB cap' });
    }

    const io = req.app.get('io');
    const payload = {
      sessionId: session.id,
      spaceId: session.spaceId,
      frame,
      at: new Date().toISOString(),
    };
    if (io) {
      io.to(`company:${req.companyId!}`).emit('field:live:frame', payload);
      if (session.spaceId) io.to(`space:${session.spaceId}`).emit('field:live:frame', payload);
    }
    res.json({ ok: true });
  } catch (err) {
    next(err);
  }
});

router.post('/live/:id/end', async (req: AuthRequest, res: Response, next: NextFunction) => {
  try {
    const session = await prisma.fieldLiveSession.findFirst({
      where: { id: req.params.id, companyId: req.companyId! },
    });
    if (!session) return res.status(404).json({ error: 'Live session not found' });

    const ended = await prisma.fieldLiveSession.update({
      where: { id: session.id },
      data: { status: 'ENDED', endedAt: new Date() },
    });

    const io = req.app.get('io');
    if (io) {
      io.to(`company:${req.companyId!}`).emit('field:live:ended', { id: session.id, spaceId: session.spaceId });
      if (session.spaceId) io.to(`space:${session.spaceId}`).emit('field:live:ended', { id: session.id });
    }

    res.json({ data: ended });
  } catch (err) {
    next(err);
  }
});

// ── APERTURE Visual Intelligence & Roof Damage CV ────────────────────────

router.get('/aperture-catalog', (_req: AuthRequest, res: Response) => {
  res.json({
    engine: 'aperture-visual-intelligence',
    catalog: APERTURE_CATALOG,
  });
});

router.post('/analyze-photo', async (req: AuthRequest, res: Response, next: NextFunction) => {
  try {
    const body = (req.body ?? {}) as Record<string, unknown>;
    const analysis = await analyzeRoofPhotoAsync({
      fileUrl: typeof body.fileUrl === 'string' ? body.fileUrl : undefined,
      fileName: typeof body.fileName === 'string' ? body.fileName : undefined,
      notes: typeof body.notes === 'string' ? body.notes : undefined,
    });
    res.json({ data: analysis });
  } catch (err) {
    next(err);
  }
});

router.post('/photogrammetry', async (req: AuthRequest, res: Response, next: NextFunction) => {
  try {
    const body = (req.body ?? {}) as Record<string, unknown>;
    const fileUrls = Array.isArray(body.fileUrls)
      ? body.fileUrls.filter((u): u is string => typeof u === 'string').slice(0, 24)
      : [];
    if (fileUrls.length === 0) {
      return res.status(400).json({ error: 'At least one uploaded aerial image URL is required' });
    }
    const result = await estimateRoofPhotogrammetryAsync({
      fileUrls,
      propertyAddress: typeof body.propertyAddress === 'string' ? body.propertyAddress.slice(0, 200) : undefined,
      pitchHint: typeof body.pitchHint === 'string' ? body.pitchHint.slice(0, 24) : undefined,
    });
    res.json({ data: result });
  } catch (err) {
    next(err);
  }
});

export default router;
