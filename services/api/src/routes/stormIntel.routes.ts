/**
 * Storm intelligence routes — /api/v1/storm-intel (docs/AI_STORM_ARCHITECTURE.md §5.7).
 *
 * Owner: backend-storm. Mounted by the Lead in backend/src/routes/index.ts.
 *
 * Endpoints
 *   GET  /overview?days=7            → { generatedAt, days, events, leads, stats }
 *   GET  /events.geojson?days=7      → FeatureCollection (usable-geometry events only)
 *   GET  /impact?lat=&lng=           → ImpactResult (400 on invalid coordinates)
 *   POST /sync { limit?, bbox? }     → { created, updated, events } (degraded on NOAA outage)
 *   POST /rescore-leads { eventId? } → { scored, updated, leads }
 *
 * Tenant scoping always uses req.user.companyId. Errors use the existing
 * `{ error }` shape. NOAA outages never surface as 500s.
 */
import { Router } from 'express';
import { AuthRequest, authMiddleware } from '../middleware/auth';
import { prisma } from '../prisma';
import {
  convertStormZoneToLeads,
  getImpactForPoint,
  getStormContext,
  rescoreLeadsForCompany,
  syncNoaaEvents,
} from '../services/storm/stormIntelligence';
import { centroid as geometryCentroid, normalizeGeometry } from '../services/storm/geo';
import { buildH3TerritoryGrid, type HexPointInput } from '../services/storm/hexGrid';
import { extractLatLng } from '../services/storm/impact';
import type { BBox } from '../services/storm/types';

const router = Router();

// The Lead mounts this router behind authMiddleware; re-applying it keeps the
// module self-contained (same pattern as storms.routes.ts).
router.use(authMiddleware);

const UUID_RE =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const DEFAULT_DAYS = 7;
const MAX_DAYS = 90;

function parseDays(raw: unknown): number {
  if (raw === undefined || raw === null || Array.isArray(raw)) return DEFAULT_DAYS;
  const text = String(raw).trim();
  if (!text) return DEFAULT_DAYS;
  const num = Number(text);
  if (!Number.isFinite(num) || num < 1) return DEFAULT_DAYS;
  return Math.min(MAX_DAYS, Math.floor(num));
}

function parseCoordinate(raw: unknown): number | null {
  if (raw === undefined || raw === null || Array.isArray(raw)) return null;
  const text = String(raw).trim();
  if (!text) return null;
  const num = Number(text);
  return Number.isFinite(num) ? num : null;
}

function parseBBox(raw: unknown): { value?: BBox; error?: string } {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) {
    return { error: 'bbox must be an object with north, south, east and west' };
  }
  const obj = raw as Record<string, unknown>;
  const north = Number(obj.north);
  const south = Number(obj.south);
  const east = Number(obj.east);
  const west = Number(obj.west);
  if (![north, south, east, west].every((value) => Number.isFinite(value))) {
    return { error: 'bbox must contain finite numeric north, south, east and west' };
  }
  if (lat(north) === false || lat(south) === false || lng(east) === false || lng(west) === false) {
    return { error: 'bbox coordinates are out of range' };
  }
  if (north < south) return { error: 'bbox north must be >= south' };
  if (east < west) return { error: 'bbox east must be >= west' };
  return { value: { north, south, east, west } };
}

function lat(value: number): boolean {
  return value >= -90 && value <= 90;
}

function lng(value: number): boolean {
  return value >= -180 && value <= 180;
}

function readBody(req: AuthRequest): Record<string, unknown> {
  const body = req.body;
  if (!body || typeof body !== 'object' || Array.isArray(body)) return {};
  return body as Record<string, unknown>;
}

/** GET /overview?days=7 — persisted storm events + company stats. */
router.get('/overview', async (req: AuthRequest, res, next) => {
  try {
    const companyId = req.user!.companyId;
    const days = parseDays(req.query.days);
    const context = await getStormContext(companyId, { days });
    res.json({
      generatedAt: context.generatedAt,
      days,
      events: context.events,
      leads: context.leads,
      stats: context.stats,
    });
  } catch (error) {
    next(error);
  }
});

/** GET /events.geojson?days=7 — only events with usable polygon geometry. */
router.get('/events.geojson', async (req: AuthRequest, res, next) => {
  try {
    const companyId = req.user!.companyId;
    const days = parseDays(req.query.days);
    const context = await getStormContext(companyId, { days });
    const features = context.events
      .filter((event) => !!event.geometry)
      .map((event) => ({
        type: 'Feature' as const,
        geometry: event.geometry,
        properties: {
          id: event.id,
          externalId: event.externalId ?? null,
          name: event.name,
          severity: event.severity,
          eventType: event.eventType,
          leadCount: event.leadCount,
          jobCount: event.jobCount,
          startDate: event.startDate,
          endDate: event.endDate,
          area: event.area ?? null,
          source: event.source,
        },
      }));
    res.json({ type: 'FeatureCollection', features });
  } catch (error) {
    next(error);
  }
});

/** GET /hex-grid?days=7 — Uber H3 Res-7 territory saturation hexagons. */
router.get('/hex-grid', async (req: AuthRequest, res, next) => {
  try {
    const companyId = req.user!.companyId;
    const days = parseDays(req.query.days);
    const [context, jobs, spaces] = await Promise.all([
      getStormContext(companyId, { days }),
      prisma.job.findMany({ where: { companyId }, select: { propertyLatLng: true, name: true }, take: 300 }),
      prisma.fieldSpace.findMany({ where: { companyId }, select: { lat: true, lng: true, name: true }, take: 50 }),
    ]);

    const pts: HexPointInput[] = [];
    for (const ev of context.events) {
      const center = ev.geometry ? geometryCentroid(normalizeGeometry(ev.geometry)) : null;
      if (center) {
        pts.push({
          lat: center.lat,
          lng: center.lng,
          kind: 'storm',
          severity: ev.severity,
          label: ev.name,
        });
      }
    }
    for (const lead of context.leads) {
      pts.push({
        lat: lead.lat,
        lng: lead.lng,
        kind: 'lead',
        score: lead.score,
        label: lead.name,
      });
    }
    for (const job of jobs) {
      const p = extractLatLng(job.propertyLatLng);
      if (p) pts.push({ lat: p.lat, lng: p.lng, kind: 'job', label: job.name });
    }
    for (const sp of spaces) {
      if (sp.lat != null && sp.lng != null) {
        pts.push({ lat: sp.lat, lng: sp.lng, kind: 'space', label: sp.name });
      }
    }

    res.json(buildH3TerritoryGrid(pts));
  } catch (error) {
    next(error);
  }
});

/** GET /impact?lat=&lng= — deterministic point impact (400 invalid input). */
router.get('/impact', async (req: AuthRequest, res, next) => {
  const rawLat = req.query.lat;
  const rawLng = req.query.lng;
  const latValue = parseCoordinate(rawLat);
  const lngValue = parseCoordinate(rawLng);
  if (latValue === null || lngValue === null) {
    return res.status(400).json({ error: 'Query params lat and lng are required and must be numeric' });
  }
  if (!lat(latValue)) {
    return res.status(400).json({ error: 'lat must be between -90 and 90' });
  }
  if (!lng(lngValue)) {
    return res.status(400).json({ error: 'lng must be between -180 and 180' });
  }
  try {
    const result = await getImpactForPoint(latValue, lngValue, {
      companyId: req.user!.companyId,
    });
    res.json(result);
  } catch (error) {
    next(error);
  }
});

/** POST /sync { limit?, bbox? } — NOAA alert sync; degrades to cached events. */
router.post('/sync', async (req: AuthRequest, res, next) => {
  const companyId = req.user!.companyId;
  const body = readBody(req);

  let limit = 100;
  if (body.limit !== undefined && body.limit !== null && body.limit !== '') {
    const parsed = Number(body.limit);
    if (!Number.isFinite(parsed) || parsed < 1 || parsed > 500) {
      return res.status(400).json({ error: 'limit must be a number between 1 and 500' });
    }
    limit = Math.floor(parsed);
  }

  let bbox: BBox | undefined;
  if (body.bbox !== undefined && body.bbox !== null) {
    const parsed = parseBBox(body.bbox);
    if (parsed.error) return res.status(400).json({ error: parsed.error });
    bbox = parsed.value;
  }

  try {
    const result = await syncNoaaEvents(companyId, { limit, ...(bbox ? { bbox } : {}) });
    return res.json(result);
  } catch (error) {
    // Last-resort degradation: DB-backed cached events, still HTTP 200.
    try {
      const cached = await getStormContext(companyId, { days: 14 });
      return res.json({
        created: 0,
        updated: 0,
        events: cached.events,
        degraded: true,
        error: 'NOAA sync unavailable; returning cached storm events.',
      });
    } catch {
      return next(error);
    }
  }
});

/** POST /rescore-leads { eventId? } — tenant-scoped rescore of geocoded leads. */
router.post('/rescore-leads', async (req: AuthRequest, res, next) => {
  try {
    const companyId = req.user!.companyId;
    const body = readBody(req);

    let eventId: string | undefined;
    if (body.eventId !== undefined && body.eventId !== null && body.eventId !== '') {
      if (typeof body.eventId !== 'string' || !UUID_RE.test(body.eventId)) {
        return res.status(400).json({ error: 'eventId must be a valid UUID' });
      }
      const event = await prisma.stormEvent.findFirst({
        where: { id: body.eventId, companyId },
        select: { id: true },
      });
      if (!event) return res.status(404).json({ error: 'Storm event not found' });
      eventId = event.id;
    }

    const result = await rescoreLeadsForCompany(companyId, {
      ...(eventId ? { eventId } : {}),
    });
    res.json(result);
  } catch (error) {
    next(error);
  }
});

/**
 * POST /convert-to-leads { eventId?, lat?, lng?, radiusKm?, label?, count? }
 * Storm zone -> leads: flags existing leads inside the zone and turns real street
 * addresses there into canvass leads (see convertStormZoneToLeads). Nothing is invented.
 */
router.post('/convert-to-leads', async (req: AuthRequest, res, next) => {
  try {
    const companyId = req.user!.companyId;
    const body = readBody(req);

    let eventId: string | undefined;
    if (body.eventId !== undefined && body.eventId !== null && body.eventId !== '') {
      if (typeof body.eventId !== 'string' || !UUID_RE.test(body.eventId)) {
        return res.status(400).json({ error: 'eventId must be a valid UUID' });
      }
      eventId = body.eventId;
    }

    const latValue = body.lat !== undefined && body.lat !== null && body.lat !== '' ? Number(body.lat) : undefined;
    const lngValue = body.lng !== undefined && body.lng !== null && body.lng !== '' ? Number(body.lng) : undefined;
    if (latValue !== undefined && (!Number.isFinite(latValue) || !lat(latValue))) {
      return res.status(400).json({ error: 'lat must be between -90 and 90' });
    }
    if (lngValue !== undefined && (!Number.isFinite(lngValue) || !lng(lngValue))) {
      return res.status(400).json({ error: 'lng must be between -180 and 180' });
    }

    const radiusRaw = body.radiusKm !== undefined && body.radiusKm !== null && body.radiusKm !== '' ? Number(body.radiusKm) : undefined;
    if (radiusRaw !== undefined && (!Number.isFinite(radiusRaw) || radiusRaw < 0.2 || radiusRaw > 5)) {
      return res.status(400).json({ error: 'radiusKm must be between 0.2 and 5' });
    }

    const label = typeof body.label === 'string' ? body.label.slice(0, 120) : undefined;
    const countRaw = body.count !== undefined ? Number(body.count) : undefined;
    const count = countRaw !== undefined && Number.isFinite(countRaw) ? Math.floor(countRaw) : undefined;

    const result = await convertStormZoneToLeads(companyId, {
      ...(eventId ? { eventId } : {}),
      ...(latValue !== undefined && lngValue !== undefined ? { lat: latValue, lng: lngValue } : {}),
      ...(radiusRaw !== undefined ? { radiusKm: radiusRaw } : {}),
      ...(label ? { label } : {}),
      ...(count ? { count } : {}),
    });
    res.json(result);
  } catch (error) {
    const status = (error as { status?: number }).status;
    if (status === 400 || status === 404) return res.status(status).json({ error: (error as Error).message });
    next(error);
  }
});

export default router;
