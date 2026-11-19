/**
 * Public scan-to-lead routes (no auth). Mounted at /api/v1/public/m.
 *
 *   GET  /:code  — what the scan landing page shows (company branding + piece
 *                  headline). Counts the scan.
 *   POST /:code  — a homeowner asks to be contacted; creates a lead attributed
 *                  to the piece and notifies the company in real time.
 *
 * Exposes only what is already printed on the piece: company name, phone,
 * logo, colors and the piece's own text. Never internal IDs or other pieces.
 */
import { Router } from 'express';
import rateLimit from 'express-rate-limit';
import { prisma } from '../prisma';
import {
  attributionNote,
  DEFAULT_BRAND,
  normalizeBrand,
  normalizePieceCode,
  type PieceKind,
  validateCapture,
} from '../services/marketing/studio';

const router = Router();

const viewLimiter = rateLimit({ windowMs: 60_000, limit: 60, standardHeaders: 'draft-7', legacyHeaders: false });
const submitLimiter = rateLimit({
  windowMs: 15 * 60_000,
  limit: 5,
  standardHeaders: 'draft-7',
  legacyHeaders: false,
  message: { error: 'Too many requests from this device; please call us instead.' },
});

const findPiece = (code: string) =>
  prisma.marketingPiece.findUnique({
    where: { code },
    select: {
      id: true,
      companyId: true,
      kind: true,
      title: true,
      headline: true,
      cta: true,
      code: true,
      design: true,
      campaign: { select: { name: true, stormEventId: true } },
      company: { select: { name: true, phone: true, logoUrl: true } },
    },
  });

router.get('/:code', viewLimiter, async (req, res, next) => {
  try {
    const code = normalizePieceCode(req.params.code);
    const piece = code ? await findPiece(code) : null;
    if (!piece) return res.status(404).json({ error: 'This code is not active' });

    await prisma.marketingPiece.update({
      where: { id: piece.id },
      data: { scanCount: { increment: 1 }, lastScanAt: new Date() },
    });
    res.json({
      data: {
        code: piece.code,
        headline: piece.headline,
        cta: piece.cta,
        companyName: piece.company.name,
        phone: piece.company.phone,
        logoUrl: piece.company.logoUrl,
        colors: normalizeBrand((piece.design as Record<string, unknown>)?.colors, DEFAULT_BRAND),
      },
    });
  } catch (err) {
    next(err);
  }
});

router.post('/:code', submitLimiter, async (req, res, next) => {
  try {
    const code = normalizePieceCode(req.params.code);
    const piece = code ? await findPiece(code) : null;
    if (!piece) return res.status(404).json({ error: 'This code is not active' });

    const parsed = validateCapture(req.body);
    if (!parsed.ok) {
      // A filled honeypot gets the same "thanks" a person would, and nothing is stored.
      if (parsed.bot) return res.status(202).json({ data: { received: true } });
      return res.status(400).json({ error: 'Please check the form', details: parsed.errors });
    }
    const c = parsed.value!;

    const lead = await prisma.lead.create({
      data: {
        companyId: piece.companyId,
        firstName: c.firstName,
        lastName: c.lastName,
        phone: c.phone,
        email: c.email,
        propertyAddress: c.propertyAddress,
        source: 'MARKETING_QR',
        status: 'NEW',
        stormEventId: piece.campaign.stormEventId,
        marketingPieceId: piece.id,
        notes: attributionNote(
          { kind: piece.kind as PieceKind, title: piece.title, code: piece.code },
          piece.campaign.name,
          c.message,
        ),
      },
      select: { id: true, firstName: true, lastName: true, propertyAddress: true, source: true, status: true, createdAt: true },
    });

    const io = req.app.get('io') as { to(room: string): { emit(e: string, p: unknown): void } } | undefined;
    io?.to(`company:${piece.companyId}`).emit('lead:created', lead);

    // Only confirm receipt: never echo the lead or internal IDs back to the public page.
    res.status(201).json({ data: { received: true, companyName: piece.company.name, phone: piece.company.phone } });
  } catch (err) {
    next(err);
  }
});

export default router;
