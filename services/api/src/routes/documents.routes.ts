import { Router, Response, NextFunction } from 'express';
import path from 'path';
import fs from 'fs';
import fsPromises from 'fs/promises';
import multer from 'multer';
import { prisma } from '../prisma';
import { AuthRequest } from '../middleware/auth';
import { companyScope } from '../middleware/company';
import { uploadSingle, uploadMultiple } from '../middleware/upload';
import { cacheGet, cacheSet, cacheDel } from '../config/redis';

const router = Router();
router.use(companyScope);

// Local upload directory (multer writes here from the backend working dir)
const UPLOADS_DIR = path.join(process.cwd(), 'uploads');

// Allowed document classifications (matches schema comment + frontend types)
const ALLOWED_FILE_TYPES = [
  'CONTRACT',
  'PERMIT',
  'INVOICE',
  'PHOTO',
  'ESTIMATE_PDF',
  'OTHER',
] as const;
type DocumentFileType = (typeof ALLOWED_FILE_TYPES)[number];

// Pagination defaults
const DEFAULT_LIMIT = 50;
const MAX_LIMIT = 100;
const LIST_CACHE_TTL = 300; // seconds

// Helpers ---------------------------------------------------------------------

/** Infer a file type from a mimetype when the caller does not provide one. */
const inferFileType = (mimetype: string): DocumentFileType => {
  if (mimetype.startsWith('image/')) return 'PHOTO';
  return 'OTHER';
};

/** Normalize a client-supplied file type, falling back to inference. */
const resolveFileType = (val: unknown, mimetype: string): DocumentFileType => {
  if (typeof val === 'string' && (ALLOWED_FILE_TYPES as readonly string[]).includes(val)) {
    return val as DocumentFileType;
  }
  return inferFileType(mimetype);
};

/** Project a stored fileUrl (e.g. `uploads/<file>`) into an absolute path on disk. */
const resolveUploadPath = (fileUrl: string): string => {
  const rel = fileUrl.replace(/^\/+/, '');
  return path.join(process.cwd(), rel);
};

/** Ensure a resolved path stays within the uploads directory (traversal guard). */
const isWithinUploads = (filePath: string): boolean => {
  const resolved = path.resolve(filePath);
  return resolved.startsWith(path.resolve(UPLOADS_DIR) + path.sep);
};

const listCacheKey = (companyId: string) => `documents:${companyId}:list`;
const jobListCacheKey = (companyId: string, jobId: string) =>
  `documents:${companyId}:job:${jobId}`;

const documentInclude = {
  job: { select: { id: true, name: true, propertyAddress: true } },
  uploadedBy: {
    select: { id: true, firstName: true, lastName: true, email: true },
  },
};

const toApiResponse = (doc: any) => ({
  ...doc,
  createdAt: new Date(doc.createdAt).toISOString(),
});

const companyEmit = (
  req: AuthRequest,
  event: string,
  payload: unknown,
  jobId?: string | null
) => {
  try {
    const io = req.app?.get('io');
    if (io) {
      io.to(`company:${req.companyId}`).emit(event, payload);
      if (jobId) {
        io.to(`job:${jobId}`).emit(event, payload);
      }
    }
  } catch {
    /* real-time updates are best-effort */
  }
};

/**
 * GET /documents
 * List the calling company's documents.
 *   ?type     — filter by fileType (CONTRACT, PERMIT, INVOICE, PHOTO, ESTIMATE_PDF, OTHER)
 *   ?search   — case-insensitive name search (alias: ?query)
 *   ?jobId    — filter to a specific job
 *   ?page     — page number (default 1)
 *   ?limit    — page size (default 50, max 100)
 */
router.get(
  '/',
  async (req: AuthRequest, res: Response, next: NextFunction) => {
    try {
      const companyId = req.companyId as string;
      const { type, search, query: queryParam, jobId, page, limit } = req.query;

      if (type && !ALLOWED_FILE_TYPES.includes(type as DocumentFileType)) {
        return res.status(400).json({
          error: `Invalid fileType. Allowed: ${ALLOWED_FILE_TYPES.join(', ')}`,
          code: 'VALIDATION_ERROR',
        });
      }

      const pageNum = Math.max(1, Number(page) || 1);
      const limitNum = Math.min(
        MAX_LIMIT,
        Math.max(1, Number(limit) || DEFAULT_LIMIT)
      );

      // Cache the full company document set; filter/paginate in memory.
      const cacheKey = listCacheKey(companyId);
      let docs: any[] | null = null;
      const cached = await cacheGet(cacheKey);
      if (cached) {
        try {
          docs = JSON.parse(cached);
        } catch {
          docs = null;
        }
      }

      if (!docs) {
        docs = await prisma.document.findMany({
          where: { companyId },
          orderBy: { createdAt: 'desc' },
          include: documentInclude,
        });
        await cacheSet(cacheKey, docs, LIST_CACHE_TTL);
      }

      const searchTerm = (search as string) || (queryParam as string);

      let filtered = docs;
      if (type) {
        filtered = filtered.filter((d) => d.fileType === type);
      }
      if (jobId) {
        filtered = filtered.filter((d) => d.jobId === jobId);
      }
      if (searchTerm) {
        const term = searchTerm.toLowerCase();
        filtered = filtered.filter((d) =>
          (d.name ?? '').toLowerCase().includes(term)
        );
      }

      const total = filtered.length;
      const skip = (pageNum - 1) * limitNum;
      const data = filtered.slice(skip, skip + limitNum).map(toApiResponse);

      res.json({
        data,
        count: total,
        page: pageNum,
        limit: limitNum,
        hasMore: skip + limitNum < total,
      });
    } catch (err) {
      next(err);
    }
  }
);

/**
 * POST /documents
 * Upload a single file (multipart field "file") and create a Document record.
 *   form fields: name, fileType (optional), jobId (optional)
 */
router.post(
  '/',
  (req: AuthRequest, res: Response, next: NextFunction) => {
    uploadSingle('file')(req, res, (err: any) => {
      if (err) {
        return res.status(400).json({
          error: err.message ?? 'File upload failed',
          code:
            err instanceof multer.MulterError ? `UPLOAD_${err.code}` : 'UPLOAD_ERROR',
        });
      }
      next();
    });
  },
  async (req: AuthRequest, res: Response, next: NextFunction) => {
    try {
      const companyId = req.companyId as string;
      const file = req.file as Express.Multer.File | undefined;

      if (!file) {
        return res.status(400).json({
          error: 'No file provided. Use the "file" field.',
          code: 'UPLOAD_NO_FILE',
        });
      }

      const { name, fileType, jobId } = req.body;

      const docName = (name as string) || file.originalname;
      const normalizedType = resolveFileType(fileType, file.mimetype);

      if (jobId) {
        const job = await prisma.job.findUnique({
          where: { id: jobId, companyId },
          select: { id: true },
        });
        if (!job) {
          return res
            .status(404)
            .json({ error: 'Job not found', code: 'JOB_NOT_FOUND' });
        }
      }

      const fileUrl = `uploads/${file.filename}`;

      const document = await prisma.document.create({
        data: {
          companyId,
          name: docName,
          fileUrl,
          fileType: normalizedType,
          size: file.size,
          uploadedByUserId: req.user!.id,
          jobId: jobId || undefined,
        },
        include: documentInclude,
      });

      // Invalidate cached lists
      await cacheDel(listCacheKey(companyId));
      if (jobId) {
        await cacheDel(jobListCacheKey(companyId, jobId));
      }

      companyEmit(req, 'document:created', { document: toApiResponse(document) }, jobId);

      res.status(201).json({
        data: toApiResponse(document),
        message: 'Document uploaded successfully',
      });
    } catch (err) {
      next(err);
    }
  }
);

/**
 * POST /documents/upload
 * Upload multiple files (multipart field "files") and create a Document record per file.
 */
router.post(
  '/upload',
  (req: AuthRequest, res: Response, next: NextFunction) => {
    uploadMultiple('files', 20)(req, res, (err: any) => {
      if (err) {
        return res.status(400).json({
          error: err.message ?? 'File upload failed',
          code:
            err instanceof multer.MulterError ? `UPLOAD_${err.code}` : 'UPLOAD_ERROR',
        });
      }
      next();
    });
  },
  async (req: AuthRequest, res: Response, next: NextFunction) => {
    try {
      const companyId = req.companyId as string;
      const files = (req.files as Express.Multer.File[] | undefined) ?? [];

      if (!files.length) {
        return res.status(400).json({
          error: 'No files provided. Use the "files" field.',
          code: 'UPLOAD_NO_FILES',
        });
      }

      const { fileType, jobId } = req.body;
      const resolvedType = fileType ? resolveFileType(fileType, '') : undefined;

      if (jobId) {
        const job = await prisma.job.findUnique({
          where: { id: jobId, companyId },
          select: { id: true },
        });
        if (!job) {
          return res
            .status(404)
            .json({ error: 'Job not found', code: 'JOB_NOT_FOUND' });
        }
      }

      const created = await prisma.$transaction(
        files.map((file) =>
          prisma.document.create({
            data: {
              companyId,
              name: file.originalname,
              fileUrl: `uploads/${file.filename}`,
              fileType: resolvedType || inferFileType(file.mimetype),
              size: file.size,
              uploadedByUserId: req.user!.id,
              jobId: jobId || undefined,
            },
          })
        )
      );

      const documents = await prisma.document.findMany({
        where: { id: { in: created.map((d) => d.id) } },
        include: documentInclude,
      });

      // Invalidate cached lists
      await cacheDel(listCacheKey(companyId));
      if (jobId) {
        await cacheDel(jobListCacheKey(companyId, jobId));
      }

      companyEmit(
        req,
        'documents:created',
        { documents: documents.map(toApiResponse) },
        jobId
      );

      res.status(201).json({
        data: documents.map(toApiResponse),
        count: documents.length,
        message: `Uploaded ${documents.length} document(s)`,
      });
    } catch (err) {
      next(err);
    }
  }
);

/**
 * GET /documents/:id
 * Fetch a single document owned by the calling company.
 */
router.get(
  '/:id',
  async (req: AuthRequest, res: Response, next: NextFunction) => {
    try {
      const companyId = req.companyId as string;
      const document = await prisma.document.findUnique({
        where: { id: req.params.id },
        include: documentInclude,
      });

      if (!document || document.companyId !== companyId) {
        return res.status(404).json({
          error: 'Document not found',
          code: 'DOCUMENT_NOT_FOUND',
        });
      }

      res.json({ data: toApiResponse(document) });
    } catch (err) {
      next(err);
    }
  }
);

/**
 * GET /documents/:id/download
 * Stream a company-owned document file for download.
 */
router.get(
  '/:id/download',
  async (req: AuthRequest, res: Response, next: NextFunction) => {
    try {
      const companyId = req.companyId as string;
      const document = await prisma.document.findUnique({
        where: { id: req.params.id },
      });

      if (!document || document.companyId !== companyId) {
        return res.status(404).json({
          error: 'Document not found',
          code: 'DOCUMENT_NOT_FOUND',
        });
      }

      const filePath = resolveUploadPath(document.fileUrl);

      if (!isWithinUploads(filePath) || !fs.existsSync(filePath)) {
        return res.status(404).json({
          error: 'File not found on disk',
          code: 'FILE_NOT_FOUND',
        });
      }

      res.download(filePath, document.name);
    } catch (err) {
      next(err);
    }
  }
);

/**
 * POST /documents/:id/annotate
 * Attach annotations to a company-owned document.
 * Body: { annotations: [...] } — stored under document.metadata.annotations.
 */
router.post(
  '/:id/annotate',
  async (req: AuthRequest, res: Response, next: NextFunction) => {
    try {
      const companyId = req.companyId as string;
      const { annotations } = req.body;

      if (!Array.isArray(annotations)) {
        return res.status(400).json({
          error: '"annotations" must be an array',
          code: 'VALIDATION_ERROR',
        });
      }

      const document = await prisma.document.findUnique({
        where: { id: req.params.id },
        select: { id: true, companyId: true, metadata: true, jobId: true },
      });

      if (!document || document.companyId !== companyId) {
        return res.status(404).json({
          error: 'Document not found',
          code: 'DOCUMENT_NOT_FOUND',
        });
      }

      const existing = (document.metadata as Record<string, unknown>) ?? {};

      const updated = await prisma.document.update({
        where: { id: req.params.id },
        data: {
          metadata: { ...existing, annotations },
        },
        include: documentInclude,
      });

      // Invalidate cached lists
      await cacheDel(listCacheKey(companyId));
      if (document.jobId) {
        await cacheDel(jobListCacheKey(companyId, document.jobId));
      }

      companyEmit(req, 'document:annotated', { id: req.params.id }, document.jobId);

      res.json({
        data: toApiResponse(updated),
        message: 'Annotations saved',
      });
    } catch (err) {
      next(err);
    }
  }
);

/**
 * DELETE /documents/:id
 * Remove a company-owned document (DB record + file on disk).
 */
router.delete(
  '/:id',
  async (req: AuthRequest, res: Response, next: NextFunction) => {
    try {
      const companyId = req.companyId as string;
      const document = await prisma.document.findUnique({
        where: { id: req.params.id },
      });

      if (!document || document.companyId !== companyId) {
        return res.status(404).json({
          error: 'Document not found',
          code: 'DOCUMENT_NOT_FOUND',
        });
      }

      const filePath = resolveUploadPath(document.fileUrl);
      if (isWithinUploads(filePath) && fs.existsSync(filePath)) {
        try {
          await fsPromises.unlink(filePath);
        } catch {
          /* file may already be gone; DB removal still proceeds */
        }
      }

      await prisma.document.delete({ where: { id: req.params.id } });

      // Invalidate cached lists
      await cacheDel(listCacheKey(companyId));
      if (document.jobId) {
        await cacheDel(jobListCacheKey(companyId, document.jobId));
      }

      companyEmit(req, 'document:deleted', { id: req.params.id }, document.jobId);

      res.json({ success: true, id: req.params.id, message: 'Document deleted' });
    } catch (err) {
      next(err);
    }
  }
);

/**
 * GET /documents/jobs/:jobId
 * List documents for a specific, company-scoped job.
 */
router.get(
  '/jobs/:jobId',
  async (req: AuthRequest, res: Response, next: NextFunction) => {
    try {
      const companyId = req.companyId as string;
      const { jobId } = req.params;
      const { type } = req.query;

      if (type && !ALLOWED_FILE_TYPES.includes(type as DocumentFileType)) {
        return res.status(400).json({
          error: `Invalid fileType. Allowed: ${ALLOWED_FILE_TYPES.join(', ')}`,
          code: 'VALIDATION_ERROR',
        });
      }

      // Ensure the job belongs to this company (avoids leaking another
      // company's documents via an unrelated job id).
      const job = await prisma.job.findUnique({
        where: { id: jobId, companyId },
        select: { id: true },
      });
      if (!job) {
        return res.status(404).json({ error: 'Job not found', code: 'JOB_NOT_FOUND' });
      }

      const cacheKey = jobListCacheKey(companyId, jobId);
      let docs: any[] | null = null;
      const cached = await cacheGet(cacheKey);
      if (cached) {
        try {
          docs = JSON.parse(cached);
        } catch {
          docs = null;
        }
      }

      if (!docs) {
        const where: any = { companyId, jobId };
        if (type) where.fileType = type;
        docs = await prisma.document.findMany({
          where,
          orderBy: { createdAt: 'desc' },
          include: documentInclude,
        });
        await cacheSet(cacheKey, docs, LIST_CACHE_TTL);
      }

      res.json({
        data: docs.map(toApiResponse),
        count: docs.length,
      });
    } catch (err) {
      next(err);
    }
  }
);

export default router;
