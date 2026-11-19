/**
 * Marketing Studio routes (authenticated, company-scoped).
 *
 * Campaigns hold printed pieces (yard signs, door hangers, flyers, postcards,
 * truck magnets). Each piece gets a random public code; its QR points at
 * /m/<code>, and leads captured there are attributed to the piece
 * (see publicMarketing.routes.ts). Pieces are rendered and exported on the
 * user's device; the server stores only the text, the brand snapshot and the
 * attribution counts.
 */
import { Router } from 'express';
import { Prisma } from '@prisma/client';
import { AuthRequest } from '../middleware/auth';
import { companyScope } from '../middleware/company';
import { prisma } from '../prisma';
import {
  DEFAULT_BRAND,
  generatePieceCode,
  normalizeBrand,
  validatePieceInput,
  type Brand,
} from '../services/marketing/studio';

const router = Router();
router.use(companyScope);

const companyBrand = (settings: unknown): Brand =>
  normalizeBrand((settings as Record<string, unknown> | null)?.brand, DEFAULT_BRAND);

const pieceSelect = {
  id: true,
  campaignId: true,
  kind: true,
  title: true,
  headline: true,
  body: true,
  cta: true,
  design: true,
  code: true,
  scanCount: true,
  lastScanAt: true,
  createdAt: true,
  updatedAt: true,
  _count: { select: { leads: true } },
} satisfies Prisma.MarketingPieceSelect;

type PieceRow = Prisma.MarketingPieceGetPayload<{ select: typeof pieceSelect }>;
const toPiece = ({ _count, ...p }: PieceRow) => ({ ...p, leadCount: _count.leads });

// ─── Brand ──────────────────────────────────────────────────────────────────

/** GET /marketing/brand — what every piece is printed with. */
router.get('/brand', async (req: AuthRequest, res, next) => {
  try {
    const company = await prisma.company.findUnique({
      where: { id: req.companyId! },
      select: { name: true, phone: true, address: true, logoUrl: true, settings: true },
    });
    if (!company) return res.status(404).json({ error: 'Company not found' });
    res.json({
      data: {
        companyName: company.name,
        phone: company.phone,
        address: company.address,
        logoUrl: company.logoUrl,
        colors: companyBrand(company.settings),
      },
    });
  } catch (err) {
    next(err);
  }
});

/** PUT /marketing/brand — save brand colors (owner/manager only); merged into Company.settings.brand. */
router.put('/brand', async (req: AuthRequest, res, next) => {
  try {
    if (!['OWNER', 'MANAGER'].includes(req.user!.role)) {
      return res.status(403).json({ error: 'Only an owner or manager can change brand colors' });
    }
    const company = await prisma.company.findUnique({ where: { id: req.companyId! }, select: { settings: true } });
    if (!company) return res.status(404).json({ error: 'Company not found' });
    const current = (company.settings as Record<string, unknown>) ?? {};
    const colors = normalizeBrand(req.body?.colors, companyBrand(current));
    await prisma.company.update({
      where: { id: req.companyId! },
      data: { settings: { ...current, brand: { ...colors } } as Prisma.InputJsonValue },
    });
    res.json({ data: { colors } });
  } catch (err) {
    next(err);
  }
});

// ─── Campaigns ──────────────────────────────────────────────────────────────

/** GET /marketing/campaigns — campaigns with their pieces and attribution counts. */
router.get('/campaigns', async (req: AuthRequest, res, next) => {
  try {
    const campaigns = await prisma.marketingCampaign.findMany({
      where: { companyId: req.companyId! },
      orderBy: { createdAt: 'desc' },
      select: {
        id: true,
        name: true,
        stormEventId: true,
        createdAt: true,
        pieces: { select: pieceSelect, orderBy: { createdAt: 'asc' } },
      },
    });
    res.json({
      data: campaigns.map((c) => {
        const pieces = c.pieces.map(toPiece);
        return {
          ...c,
          pieces,
          totals: {
            pieces: pieces.length,
            scans: pieces.reduce((n, p) => n + p.scanCount, 0),
            leads: pieces.reduce((n, p) => n + p.leadCount, 0),
          },
        };
      }),
    });
  } catch (err) {
    next(err);
  }
});

/** POST /marketing/campaigns — { name, stormEventId? } */
router.post('/campaigns', async (req: AuthRequest, res, next) => {
  try {
    const name = typeof req.body?.name === 'string' ? req.body.name.trim() : '';
    if (!name || name.length > 80) return res.status(400).json({ error: 'name is required (at most 80 characters)' });
    let stormEventId: string | null = null;
    if (req.body?.stormEventId) {
      const storm = await prisma.stormEvent.findFirst({
        where: { id: String(req.body.stormEventId), companyId: req.companyId! },
        select: { id: true },
      });
      if (!storm) return res.status(400).json({ error: 'stormEventId does not belong to your company' });
      stormEventId = storm.id;
    }
    const campaign = await prisma.marketingCampaign.create({
      data: { companyId: req.companyId!, name, stormEventId, createdById: req.user!.id },
      select: { id: true, name: true, stormEventId: true, createdAt: true },
    });
    res.status(201).json({ data: { ...campaign, pieces: [], totals: { pieces: 0, scans: 0, leads: 0 } } });
  } catch (err) {
    next(err);
  }
});

/** DELETE /marketing/campaigns/:id — only while none of its pieces has produced a lead. */
router.delete('/campaigns/:id', async (req: AuthRequest, res, next) => {
  try {
    const campaign = await prisma.marketingCampaign.findFirst({
      where: { id: req.params.id, companyId: req.companyId! },
      select: { id: true, pieces: { select: { _count: { select: { leads: true } } } } },
    });
    if (!campaign) return res.status(404).json({ error: 'Campaign not found' });
    if (campaign.pieces.some((p) => p._count.leads > 0)) {
      return res.status(409).json({ error: 'This campaign has produced leads; keep it so their attribution stays intact' });
    }
    await prisma.marketingCampaign.delete({ where: { id: campaign.id } });
    res.status(204).end();
  } catch (err) {
    next(err);
  }
});

// ─── Pieces ─────────────────────────────────────────────────────────────────

/** POST /marketing/campaigns/:id/pieces — { kind, title, headline, body?, cta? } */
router.post('/campaigns/:id/pieces', async (req: AuthRequest, res, next) => {
  try {
    const campaign = await prisma.marketingCampaign.findFirst({
      where: { id: req.params.id, companyId: req.companyId! },
      select: { id: true },
    });
    if (!campaign) return res.status(404).json({ error: 'Campaign not found' });
    const parsed = validatePieceInput(req.body);
    if (!parsed.ok) return res.status(400).json({ error: 'Invalid piece', details: parsed.errors });

    const company = await prisma.company.findUnique({ where: { id: req.companyId! }, select: { settings: true } });
    const design = { colors: companyBrand(company?.settings) };

    // A 50-bit random code basically never collides; retry a few times anyway.
    for (let attempt = 0; attempt < 5; attempt += 1) {
      try {
        const piece = await prisma.marketingPiece.create({
          data: {
            ...(parsed.value as Required<typeof parsed.value>),
            design,
            code: generatePieceCode(),
            companyId: req.companyId!,
            campaignId: campaign.id,
            createdById: req.user!.id,
          },
          select: pieceSelect,
        });
        return res.status(201).json({ data: toPiece(piece as unknown as PieceRow) });
      } catch (err) {
        if (err instanceof Prisma.PrismaClientKnownRequestError && err.code === 'P2002') continue;
        throw err;
      }
    }
    res.status(503).json({ error: 'Could not allocate a unique code; try again' });
  } catch (err) {
    next(err);
  }
});

/** PATCH /marketing/pieces/:id — edit text, or refresh its colors from the current brand. The code never changes. */
router.patch('/pieces/:id', async (req: AuthRequest, res, next) => {
  try {
    const existing = await prisma.marketingPiece.findFirst({
      where: { id: req.params.id, companyId: req.companyId! },
      select: { id: true },
    });
    if (!existing) return res.status(404).json({ error: 'Piece not found' });
    const parsed = validatePieceInput(req.body, true);
    if (!parsed.ok) return res.status(400).json({ error: 'Invalid piece', details: parsed.errors });

    const data: Prisma.MarketingPieceUpdateInput = { ...parsed.value };
    if (req.body?.refreshBrand === true) {
      const company = await prisma.company.findUnique({ where: { id: req.companyId! }, select: { settings: true } });
      data.design = { colors: companyBrand(company?.settings) };
    }
    const piece = await prisma.marketingPiece.update({ where: { id: existing.id }, data, select: pieceSelect });
    res.json({ data: toPiece(piece) });
  } catch (err) {
    next(err);
  }
});

/** DELETE /marketing/pieces/:id — only while it has produced no leads (its code may already be printed). */
router.delete('/pieces/:id', async (req: AuthRequest, res, next) => {
  try {
    const piece = await prisma.marketingPiece.findFirst({
      where: { id: req.params.id, companyId: req.companyId! },
      select: { id: true, _count: { select: { leads: true } } },
    });
    if (!piece) return res.status(404).json({ error: 'Piece not found' });
    if (piece._count.leads > 0) {
      return res.status(409).json({ error: 'This piece has produced leads; keep it so their attribution stays intact' });
    }
    await prisma.marketingPiece.delete({ where: { id: piece.id } });
    res.status(204).end();
  } catch (err) {
    next(err);
  }
});

/** GET /marketing/pieces/:id/leads — the leads this piece produced. */
router.get('/pieces/:id/leads', async (req: AuthRequest, res, next) => {
  try {
    const piece = await prisma.marketingPiece.findFirst({
      where: { id: req.params.id, companyId: req.companyId! },
      select: { id: true },
    });
    if (!piece) return res.status(404).json({ error: 'Piece not found' });
    const leads = await prisma.lead.findMany({
      where: { companyId: req.companyId!, marketingPieceId: piece.id },
      orderBy: { createdAt: 'desc' },
      select: { id: true, firstName: true, lastName: true, propertyAddress: true, status: true, createdAt: true },
    });
    res.json({ data: leads });
  } catch (err) {
    next(err);
  }
});

export default router;
