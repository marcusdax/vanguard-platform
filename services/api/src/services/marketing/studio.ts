/**
 * Marketing Studio — pure helpers (no DB, no Express).
 *
 * The AUCTUM pattern: branded print pieces carry a scannable link, and every
 * lead that link produces is attributed to the exact piece that was printed.
 * Codes are random (not sequential) so a printed code reveals nothing about
 * other pieces or how many exist.
 */
import { randomBytes } from 'node:crypto';

export const PIECE_KINDS = ['YARD_SIGN', 'DOOR_HANGER', 'FLYER', 'POSTCARD', 'TRUCK_MAGNET'] as const;
export type PieceKind = (typeof PIECE_KINDS)[number];

// Crockford base32 without I, L, O, U: easy to read off a sign and type by hand.
const ALPHABET = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';
export const CODE_LENGTH = 10; // 50 bits

export function generatePieceCode(bytes: Buffer = randomBytes(CODE_LENGTH)): string {
  let out = '';
  for (let i = 0; i < CODE_LENGTH; i += 1) out += ALPHABET[bytes[i] % 32];
  return out;
}

/** Normalises a code typed by a person (lowercase, dashes, O for 0, I/L for 1). */
export function normalizePieceCode(input: unknown): string | null {
  if (typeof input !== 'string') return null;
  const s = input.toUpperCase().replace(/[\s-]/g, '').replace(/O/g, '0').replace(/[IL]/g, '1');
  if (s.length !== CODE_LENGTH) return null;
  for (const ch of s) if (!ALPHABET.includes(ch)) return null;
  return s;
}

// ─── Brand ──────────────────────────────────────────────────────────────────

export type Brand = {
  primary: string; // main background color of a piece
  accent: string; // highlights, CTA bar
};

export const DEFAULT_BRAND: Brand = { primary: '#17231D', accent: '#8C2F22' };

const HEX = /^#?([0-9a-f]{3}|[0-9a-f]{6})$/i;

export function normalizeHex(value: unknown): string | null {
  if (typeof value !== 'string') return null;
  const m = value.trim().match(HEX);
  if (!m) return null;
  const h = m[1].length === 3 ? m[1].split('').map((c) => c + c).join('') : m[1];
  return `#${h.toUpperCase()}`;
}

/** Merges partial brand input over a base, ignoring invalid colors. */
export function normalizeBrand(input: unknown, base: Brand = DEFAULT_BRAND): Brand {
  const src = (input && typeof input === 'object' ? input : {}) as Record<string, unknown>;
  return {
    primary: normalizeHex(src.primary) ?? base.primary,
    accent: normalizeHex(src.accent) ?? base.accent,
  };
}

function relativeLuminance(hex: string): number {
  const n = parseInt(hex.slice(1), 16);
  const ch = [(n >> 16) & 255, (n >> 8) & 255, n & 255].map((v) => {
    const c = v / 255;
    return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * ch[0] + 0.7152 * ch[1] + 0.0722 * ch[2];
}

export function contrastRatio(a: string, b: string): number {
  const [l1, l2] = [relativeLuminance(a), relativeLuminance(b)].sort((x, y) => y - x);
  return (l1 + 0.05) / (l2 + 0.05);
}

/** Black or white, whichever reads better on the given background (WCAG ratio). */
export function readableTextOn(background: string): '#FFFFFF' | '#111111' {
  return contrastRatio(background, '#FFFFFF') >= contrastRatio(background, '#111111') ? '#FFFFFF' : '#111111';
}

// ─── Piece input ────────────────────────────────────────────────────────────

export interface PieceInput {
  kind: PieceKind;
  title: string;
  headline: string;
  body: string;
  cta: string;
}

const LIMITS = { title: 80, headline: 90, body: 600, cta: 60 } as const;

export type Validation<T> =
  | { ok: true; value: T; errors?: undefined }
  | { ok: false; value?: undefined; errors: string[] };

const str = (v: unknown) => (typeof v === 'string' ? v.trim() : '');

export function validatePieceInput(raw: unknown, partial = false): Validation<Partial<PieceInput>> {
  const src = (raw && typeof raw === 'object' ? raw : {}) as Record<string, unknown>;
  const errors: string[] = [];
  const out: Partial<PieceInput> = {};

  if (src.kind !== undefined || !partial) {
    if (!PIECE_KINDS.includes(src.kind as PieceKind)) errors.push(`kind must be one of ${PIECE_KINDS.join(', ')}`);
    else out.kind = src.kind as PieceKind;
  }
  for (const key of ['title', 'headline', 'body', 'cta'] as const) {
    if (src[key] === undefined) {
      if (!partial && (key === 'title' || key === 'headline')) errors.push(`${key} is required`);
      if (!partial && (key === 'body' || key === 'cta')) out[key] = '';
      continue;
    }
    const v = str(src[key]);
    if ((key === 'title' || key === 'headline') && !v) errors.push(`${key} is required`);
    else if (v.length > LIMITS[key]) errors.push(`${key} must be at most ${LIMITS[key]} characters`);
    else out[key] = v;
  }
  return errors.length ? { ok: false, errors } : { ok: true, value: out };
}

// ─── Public scan-to-lead capture ────────────────────────────────────────────

export interface CaptureInput {
  firstName: string;
  lastName: string;
  phone: string | null;
  email: string | null;
  propertyAddress: string;
  message: string;
}

const EMAIL = /^[^\s@]{1,64}@[^\s@]{1,190}\.[^\s@]{2,}$/;

/**
 * Validates what a homeowner submits after scanning. Requires a name, the
 * property address, consent to be contacted, and at least one way to reach
 * them. `website` is a honeypot: humans never see it, bots fill it in.
 */
export function validateCapture(raw: unknown): Validation<CaptureInput> & { bot?: boolean } {
  const src = (raw && typeof raw === 'object' ? raw : {}) as Record<string, unknown>;
  if (str(src.website)) return { ok: false, bot: true, errors: ['rejected'] };

  const errors: string[] = [];
  const name = str(src.name);
  const firstName = str(src.firstName) || name.split(/\s+/)[0] || '';
  const lastName = str(src.lastName) || name.split(/\s+/).slice(1).join(' ');
  const phoneRaw = str(src.phone);
  const digits = phoneRaw.replace(/\D/g, '');
  const email = str(src.email).toLowerCase();
  const propertyAddress = str(src.propertyAddress);
  const message = str(src.message);

  if (!firstName) errors.push('name is required');
  if (firstName.length > 60 || lastName.length > 60) errors.push('name is too long');
  if (!propertyAddress) errors.push('property address is required');
  if (propertyAddress.length > 200) errors.push('property address is too long');
  if (phoneRaw && (digits.length < 10 || digits.length > 15)) errors.push('phone number looks incomplete');
  if (email && !EMAIL.test(email)) errors.push('email address looks invalid');
  if (!phoneRaw && !email) errors.push('a phone number or email is required');
  if (message.length > 1000) errors.push('message must be at most 1000 characters');
  if (src.consent !== true) errors.push('consent to be contacted is required');

  if (errors.length) return { ok: false, errors };
  return {
    ok: true,
    value: { firstName, lastName: lastName || '(scan)', phone: phoneRaw || null, email: email || null, propertyAddress, message },
  };
}

const KIND_LABEL: Record<PieceKind, string> = {
  YARD_SIGN: 'yard sign',
  DOOR_HANGER: 'door hanger',
  FLYER: 'flyer',
  POSTCARD: 'postcard',
  TRUCK_MAGNET: 'truck magnet',
};

/** The note written on a scan-created lead, so attribution is visible in the lead itself. */
export function attributionNote(piece: { kind: PieceKind; title: string; code: string }, campaignName: string, message: string): string {
  const head = `Scanned ${KIND_LABEL[piece.kind]} "${piece.title}" (code ${piece.code}) from campaign "${campaignName}".`;
  return message ? `${head}\n\nHomeowner message: ${message}` : head;
}
