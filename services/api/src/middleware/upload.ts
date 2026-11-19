import multer from 'multer';
import path from 'path';
import fs from 'fs';
import { randomUUID } from 'crypto';

// Ensure upload directory exists
const uploadDir = path.join(process.cwd(), 'uploads');
if (!fs.existsSync(uploadDir)) {
  fs.mkdirSync(uploadDir, { recursive: true });
}

// Allowed upload types and the ONLY extension each may be stored with.
// The extension comes from this map, never from the client's filename: a file
// declared as image/png but named evil.html must not be stored (and later served
// from /uploads) as HTML on the app's own origin.
export const MIME_EXTENSIONS: Readonly<Record<string, string>> = Object.freeze({
  'image/jpeg': '.jpg',
  'image/png': '.png',
  'image/webp': '.webp',
  'image/gif': '.gif',
  'video/mp4': '.mp4',
  'video/webm': '.webm',
  'video/quicktime': '.mov',
  'application/pdf': '.pdf',
  'text/plain': '.txt',
  'application/msword': '.doc',
  'application/vnd.openxmlformats-officedocument.wordprocessingml.document': '.docx',
  'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet': '.xlsx',
  'application/vnd.openxmlformats-officedocument.presentationml.presentation': '.pptx',
});

/** Extensions /uploads may serve (see server.ts); everything else is refused. */
export const SERVABLE_UPLOAD_EXTENSIONS: ReadonlySet<string> = new Set([
  ...Object.values(MIME_EXTENSIONS),
  '.jpeg', // legacy uploads stored before extensions were normalized
]);

/** A readable, filesystem-safe stem from the client's filename (no path, no extension). */
export function safeUploadStem(originalName: string): string {
  const base = path.basename(originalName || '', path.extname(originalName || ''));
  const stem = base
    .normalize('NFKD')
    .replace(/[^\w.-]+/g, '-')
    .replace(/^[.-]+|[.-]+$/g, '')
    .slice(0, 60);
  return stem || 'file';
}

/** Stored filename: safe stem + random id + the extension implied by the MIME type. */
export function storedUploadName(originalName: string, mimetype: string): string {
  const ext = MIME_EXTENSIONS[mimetype];
  if (!ext) throw new Error('File type not allowed');
  return `${safeUploadStem(originalName)}-${randomUUID()}${ext}`;
}

// Disk storage (for local development; use S3/MinIO in production)
const storage = multer.diskStorage({
  destination: (_req, _file, cb) => {
    cb(null, uploadDir);
  },
  filename: (_req, file, cb) => {
    try {
      cb(null, storedUploadName(file.originalname, file.mimetype));
    } catch (err) {
      cb(err as Error, '');
    }
  },
});

const fileFilter = (req: Express.Request, file: Express.Multer.File, cb: multer.FileFilterCallback) => {
  if (Object.prototype.hasOwnProperty.call(MIME_EXTENSIONS, file.mimetype)) {
    cb(null, true);
  } else {
    cb(new Error('File type not allowed') as any, false);
  }
};

export const upload = multer({
  storage,
  fileFilter,
  limits: {
    fileSize: 50 * 1024 * 1024, // 50MB
  },
});

export const uploadSingle = (fieldName: string) => upload.single(fieldName);
export const uploadMultiple = (fieldName: string, maxCount: number) => upload.array(fieldName, maxCount);
