/**
 * Storm intelligence service — real engine (docs/AI_STORM_ARCHITECTURE.md §4.2/§4.3).
 *
 * Responsibilities
 *  - Pull NOAA active alerts (via the existing NOAAWeatherService) and cluster
 *    them into company-scoped StormEvent rows (idempotent upsert on
 *    (companyId, externalId)).
 *  - Degrade gracefully when NOAA is unreachable: sync returns cached events and
 *    never throws for a network failure.
 *  - Deterministic point impact scoring (geo containment / distance decay),
 *    persisted on Lead.stormImpactScore + stormScoredAt.
 *  - Company-scoped storm context for /api/v1/storm-intel and /api/v1/ai.
 *
 * Consumers: backend/src/routes/stormIntel.routes.ts and backend/src/routes/ai.routes.ts
 * (backend-ai imports getStormContext / scoreLeadAgainstStorms — signatures kept stable).
 */
import type { StormEvent } from '@prisma/client';
import { Prisma } from '@prisma/client';
import noaaService from '../NOAAWeatherService';
import type { WeatherAlert } from '../NOAAWeatherService';
import { prisma } from '../../prisma';
import {
  bboxIntersects,
  bboxOfGeometry,
  centroid as geometryCentroid,
  haversineKm,
  normalizeGeometry,
  approximateBufferPolygon,
  pointInGeometry,
  polygonAreaKm2,
} from './geo';
import { getAddressProvider, normalizeAddressKey, type AddressProvider } from './addressProvider';
import { clusterWeatherAlerts } from './clustering';
import type { AlertCluster, WeatherAlertLike } from './clustering';
import {
  computeImpact,
  evaluateEventImpact,
  extractLatLng,
  IMPACT_RADIUS_KM,
  type ImpactEvent,
} from './impact';
import type {
  BBox,
  GeoPoint,
  ImpactResult,
  StormContext,
  StormEventSummary,
  StormLeadRef,
  StormSeverity,
  StormStats,
} from './types';

const DAY_MS = 86_400_000;
const DEFAULT_CONTEXT_DAYS = 7;
const DEFAULT_SCORING_DAYS = 14;
const DEFAULT_EVENT_LIMIT = 50;
const DEFAULT_LEAD_LIMIT = 100;
const LIVE_EVENT_ID_PREFIX = 'live:';

/**
 * A roofing contractor cares about storms over their book of business, not every
 * national marine warning. NOAA sync therefore keeps clusters that intersect a
 * footprint built from the company's geocoded leads/jobs (plus a margin), or the
 * 12 most severe clusters for a brand-new company with no coordinates yet.
 */
const FOOTPRINT_MARGIN_DEG = 1.5;
const FOOTPRINT_MAX_KM = 250;
const MAX_FOOTPRINT_EVENTS = 40;
const MAX_EVENTS_WITHOUT_FOOTPRINT = 12;

const SEVERITY_RANK: Record<string, number> = {
  MINOR: 1,
  MODERATE: 2,
  SEVERE: 3,
  EXTREME: 4,
};

function pointBBox(point: GeoPoint, halfSizeDeg = 0.05): BBox {
  return {
    north: point.lat + halfSizeDeg,
    south: point.lat - halfSizeDeg,
    east: point.lng + halfSizeDeg,
    west: point.lng - halfSizeDeg,
  };
}

async function loadCompanyFootprint(companyId: string): Promise<{ points: GeoPoint[]; bbox: BBox | null }> {
  const [leads, jobs] = await Promise.all([
    prisma.lead.findMany({ where: { companyId }, select: { propertyLatLng: true }, take: 500 }),
    prisma.job.findMany({ where: { companyId }, select: { propertyLatLng: true }, take: 500 }),
  ]);
  const points: GeoPoint[] = [];
  for (const row of [...leads, ...jobs]) {
    const point = extractLatLng(row.propertyLatLng);
    if (point) points.push(point);
  }
  if (points.length === 0) return { points, bbox: null };

  let north = -90;
  let south = 90;
  let east = -180;
  let west = 180;
  for (const point of points) {
    north = Math.max(north, point.lat);
    south = Math.min(south, point.lat);
    east = Math.max(east, point.lng);
    west = Math.min(west, point.lng);
  }
  return {
    points,
    bbox: {
      north: Math.min(90, north + FOOTPRINT_MARGIN_DEG),
      south: Math.max(-90, south - FOOTPRINT_MARGIN_DEG),
      east: Math.min(180, east + FOOTPRINT_MARGIN_DEG),
      west: Math.max(-180, west - FOOTPRINT_MARGIN_DEG),
    },
  };
}

/** True when a live cluster overlaps the company footprint (or any point is nearby). */
function clusterMatchesFootprint(
  cluster: AlertCluster,
  footprint: { points: GeoPoint[]; bbox: BBox | null }
): boolean {
  if (!footprint.bbox) return true;
  const box = cluster.geometry
    ? bboxOfGeometry(cluster.geometry)
    : cluster.centroid
      ? pointBBox(cluster.centroid)
      : null;
  // Alerts without any geometry can't be placed on the company map; skip them.
  if (!box) return false;
  if (bboxIntersects(box, footprint.bbox)) return true;
  const center =
    cluster.centroid ??
    ({
      lat: (box.north + box.south) / 2,
      lng: (box.east + box.west) / 2,
    } as GeoPoint);
  return footprint.points.some((point) => haversineKm(point, center) <= FOOTPRINT_MAX_KM);
}

/** Keep the company-relevant clusters; without a footprint keep the worst 12. */
function selectRelevantClusters(
  clusters: AlertCluster[],
  footprint: { points: GeoPoint[]; bbox: BBox | null }
): AlertCluster[] {
  if (!footprint.bbox) {
    return [...clusters]
      .sort(
        (a, b) =>
          (SEVERITY_RANK[b.severity] ?? 0) - (SEVERITY_RANK[a.severity] ?? 0) ||
          b.startMs - a.startMs
      )
      .slice(0, MAX_EVENTS_WITHOUT_FOOTPRINT);
  }
  return clusters.filter((cluster) => clusterMatchesFootprint(cluster, footprint)).slice(0, MAX_FOOTPRINT_EVENTS);
}

const configuredTimeout = Number(process.env.STORM_NOAA_TIMEOUT_MS);
const NOAA_TIMEOUT_MS =
  Number.isFinite(configuredTimeout) && configuredTimeout > 0 ? configuredTimeout : 6000;

function clamp(value: number | undefined, min: number, max: number, fallback: number): number {
  const num = Number(value);
  if (!Number.isFinite(num)) return fallback;
  return Math.min(max, Math.max(min, Math.floor(num)));
}

function errorMessage(error: unknown): string {
  if (error instanceof Error) return error.message;
  return String(error ?? 'Unknown NOAA error');
}

/** Reject after `ms` so a hanging NOAA request can never hang an HTTP handler. */
function withTimeout<T>(promise: Promise<T>, ms: number, label: string): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`${label} timed out after ${ms}ms`)), ms);
    promise.then(
      (value) => {
        clearTimeout(timer);
        resolve(value);
      },
      (error) => {
        clearTimeout(timer);
        reject(error);
      }
    );
  });
}

interface LiveAlertResult {
  alerts: WeatherAlertLike[];
  degraded: boolean;
  error?: string;
}

function toAlertLike(alert: WeatherAlert): WeatherAlertLike {
  return {
    id: alert.id,
    type: alert.type,
    severity: alert.severity,
    effective: alert.effective,
    onset: alert.onset,
    expires: alert.expires,
    ends: alert.ends,
    headline: alert.headline,
    area: alert.area,
    geometry: alert.geometry,
    parameters: alert.parameters,
  };
}

/**
 * Fetch live NOAA alerts with a hard timeout. Any failure (network, DNS, 5xx,
 * malformed payload) resolves to an empty degraded result instead of throwing.
 */
async function fetchLiveAlerts(opts: { limit?: number; bbox?: BBox } = {}): Promise<LiveAlertResult> {
  const limit = clamp(opts.limit, 1, 500, 100);
  try {
    const result = await withTimeout(
      noaaService.getActiveAlerts({ limit }),
      NOAA_TIMEOUT_MS,
      'NOAA active alerts'
    );
    let alerts = (result?.alerts ?? []).slice(0, limit).map(toAlertLike);
    if (opts.bbox) {
      alerts = alerts.filter((alert) => {
        const bbox = bboxOfGeometry(alert.geometry);
        return bbox ? bboxIntersects(bbox, opts.bbox as BBox) : false;
      });
    }
    return { alerts, degraded: false };
  } catch (error) {
    return { alerts: [], degraded: true, error: errorMessage(error) };
  }
}

function metadataOf(row: StormEvent): Record<string, unknown> {
  const metadata = row.metadata;
  if (!metadata || typeof metadata !== 'object' || Array.isArray(metadata)) return {};
  return metadata as Record<string, unknown>;
}

function readArea(row: StormEvent): string | null {
  const metadata = metadataOf(row);
  if (typeof metadata.area === 'string' && metadata.area.trim()) return metadata.area.trim();
  const affected = row.affectedArea;
  if (typeof affected === 'string' && affected.trim()) return affected.trim();
  if (affected && typeof affected === 'object' && !Array.isArray(affected)) {
    const areaDesc = (affected as Record<string, unknown>).areaDesc;
    if (typeof areaDesc === 'string' && areaDesc.trim()) return areaDesc.trim();
  }
  return null;
}

function readCentroid(row: StormEvent): GeoPoint | null {
  const metadata = metadataOf(row);
  const raw = metadata.centroid;
  if (raw && typeof raw === 'object' && !Array.isArray(raw)) {
    const lat = Number((raw as Record<string, unknown>).lat);
    const lng = Number((raw as Record<string, unknown>).lng);
    if (Number.isFinite(lat) && Number.isFinite(lng)) return { lat, lng };
  }
  if (row.geometry) {
    const centroid = geometryCentroid(row.geometry);
    if (centroid) return centroid;
  }
  return null;
}

function rowToImpactEvent(row: StormEvent): ImpactEvent {
  return {
    id: row.id,
    name: row.name,
    severity: row.severity as StormSeverity,
    geometry: row.geometry ?? undefined,
    centroid: readCentroid(row),
    area: readArea(row),
    source: row.source,
  };
}

function liveClustersToImpactEvents(clusters: AlertCluster[]): ImpactEvent[] {
  return clusters.map((cluster) => ({
    id: `${LIVE_EVENT_ID_PREFIX}${cluster.externalId}`,
    name: cluster.name,
    severity: cluster.severity,
    geometry: cluster.geometry ?? undefined,
    centroid: cluster.centroid,
    area: cluster.area,
    source: 'NOAA',
  }));
}

async function fetchLiveImpactEvents(limit = 150): Promise<ImpactEvent[]> {
  const live = await fetchLiveAlerts({ limit });
  if (live.alerts.length === 0) return [];
  try {
    return liveClustersToImpactEvents(clusterWeatherAlerts(live.alerts));
  } catch {
    return [];
  }
}

/**
 * Candidate footprints used by impact scoring: persisted company StormEvents plus
 * live NOAA alert clusters. Live clusters are ephemeral (not persisted here).
 */
export async function buildImpactCandidates(
  companyId?: string,
  opts: { includeLiveAlerts?: boolean; days?: number } = {}
): Promise<ImpactEvent[]> {
  const candidates: ImpactEvent[] = [];
  if (companyId) {
    const rows = await loadCompanyEventRows(companyId, {
      days: opts.days ?? DEFAULT_SCORING_DAYS,
      limit: 100,
    });
    for (const row of rows) candidates.push(rowToImpactEvent(row));
  }
  if (opts.includeLiveAlerts !== false) {
    candidates.push(...(await fetchLiveImpactEvents()));
  }
  return candidates;
}

async function loadCompanyEventRows(
  companyId: string,
  opts: { days?: number; bbox?: BBox; limit?: number } = {}
): Promise<StormEvent[]> {
  const days = clamp(opts.days, 1, 365, DEFAULT_CONTEXT_DAYS);
  const limit = clamp(opts.limit, 1, 200, DEFAULT_EVENT_LIMIT);
  const since = new Date(Date.now() - days * DAY_MS);
  const where: Prisma.StormEventWhereInput = {
    companyId,
    OR: [{ startDate: { gte: since } }, { endDate: { gte: since } }],
  };
  let rows = await prisma.stormEvent.findMany({
    where,
    orderBy: [{ startDate: 'desc' }, { id: 'asc' }],
    take: opts.bbox ? Math.max(limit * 5, 250) : limit,
  });
  if (opts.bbox) {
    const bbox = opts.bbox;
    rows = rows
      .filter((row) => {
        const geometryBox = row.geometry ? bboxOfGeometry(row.geometry) : null;
        return geometryBox ? bboxIntersects(geometryBox, bbox) : false;
      })
      .slice(0, limit);
  }
  return rows;
}

interface GeoLead {
  id: string;
  name: string;
  address: string;
  status: string;
  point: GeoPoint;
  stormEventId: string | null;
  persistedScore: number | null;
}

interface GeoJob {
  id: string;
  point: GeoPoint;
}

interface CompanyGeoData {
  leads: GeoLead[];
  jobs: GeoJob[];
}

interface LeadImpactStat {
  score: number;
  eventId?: string;
  inside: boolean;
}

interface SummarizeResult {
  events: StormEventSummary[];
  leads: GeoLead[];
  leadStats: Map<string, LeadImpactStat>;
  jobsInImpact: Set<string>;
  stats: StormStats;
}

async function loadCompanyGeoData(companyId: string): Promise<CompanyGeoData> {
  const [leadRows, jobRows] = await Promise.all([
    prisma.lead.findMany({
      where: { companyId },
      select: {
        id: true,
        firstName: true,
        lastName: true,
        propertyAddress: true,
        propertyLatLng: true,
        status: true,
        stormEventId: true,
        stormImpactScore: true,
      },
    }),
    prisma.job.findMany({
      where: { companyId },
      select: { id: true, propertyLatLng: true },
    }),
  ]);

  const leads: GeoLead[] = [];
  for (const row of leadRows) {
    const point = extractLatLng(row.propertyLatLng);
    if (!point) continue;
    leads.push({
      id: row.id,
      name: `${row.firstName} ${row.lastName}`.trim(),
      address: row.propertyAddress,
      status: row.status,
      point,
      stormEventId: row.stormEventId ?? null,
      persistedScore: row.stormImpactScore ?? null,
    });
  }
  const jobs: GeoJob[] = [];
  for (const row of jobRows) {
    const point = extractLatLng(row.propertyLatLng);
    if (point) jobs.push({ id: row.id, point });
  }
  return { leads, jobs };
}

function toEventSummary(
  row: StormEvent,
  leadCount: number,
  jobCount: number
): StormEventSummary {
  return {
    id: row.id,
    externalId: row.externalId ?? null,
    name: row.name,
    eventType: row.eventType,
    severity: row.severity as StormSeverity,
    startDate: row.startDate.toISOString(),
    endDate: row.endDate.toISOString(),
    area: readArea(row),
    source: (row.source as StormEventSummary['source']) ?? 'MANUAL',
    geometry: normalizeGeometry(row.geometry) ?? undefined,
    leadCount,
    jobCount,
    aiBrief: row.aiBrief ?? undefined,
  };
}

/**
 * Count leads/jobs inside each event footprint (containment or ≤ IMPACT_RADIUS_KM)
 * and compute each lead's best deterministic impact score against the event set.
 */
async function summarizeCompanyEvents(companyId: string, rows: StormEvent[]): Promise<SummarizeResult> {
  const geo = await loadCompanyGeoData(companyId);
  const candidates = rows.map(rowToImpactEvent);
  const leadCounts = new Map<string, number>();
  const jobCounts = new Map<string, number>();
  const leadStats = new Map<string, LeadImpactStat>();
  const jobsInImpact = new Set<string>();

  for (let i = 0; i < rows.length; i++) {
    const candidate = candidates[i];
    let leadCount = 0;
    let jobCount = 0;

    for (const lead of geo.leads) {
      const impact = evaluateEventImpact(lead.point, candidate);
      if (!impact) continue;
      const inside = impact.contained || impact.distanceKm <= IMPACT_RADIUS_KM;
      if (inside) leadCount++;

      let stat = leadStats.get(lead.id);
      if (!stat) {
        stat = { score: 0, inside: false };
        leadStats.set(lead.id, stat);
      }
      if (inside) stat.inside = true;
      const better =
        impact.score > stat.score ||
        (impact.score === stat.score &&
          impact.score > 0 &&
          (!stat.eventId || candidate.id < stat.eventId));
      if (better) {
        stat.score = impact.score;
        stat.eventId = candidate.id;
      }
    }

    for (const job of geo.jobs) {
      const impact = evaluateEventImpact(job.point, candidate);
      if (!impact) continue;
      if (impact.contained || impact.distanceKm <= IMPACT_RADIUS_KM) {
        jobCount++;
        jobsInImpact.add(job.id);
      }
    }

    leadCounts.set(candidate.id, leadCount);
    jobCounts.set(candidate.id, jobCount);
  }

  const events = rows.map((row) =>
    toEventSummary(row, leadCounts.get(row.id) ?? 0, jobCounts.get(row.id) ?? 0)
  );

  const bySeverity: Record<string, number> = {};
  let extremeCount = 0;
  let activeEvents = 0;
  const now = Date.now();
  for (const row of rows) {
    const severity = String(row.severity);
    bySeverity[severity] = (bySeverity[severity] ?? 0) + 1;
    if (severity === 'EXTREME') extremeCount++;
    if (row.endDate.getTime() >= now) activeEvents++;
  }

  let leadsInImpact = 0;
  for (const stat of leadStats.values()) if (stat.inside) leadsInImpact++;

  return {
    events,
    leads: geo.leads,
    leadStats,
    jobsInImpact,
    stats: {
      activeEvents,
      extremeCount,
      leadsInImpact,
      jobsInImpact: jobsInImpact.size,
      bySeverity,
    },
  };
}

/** Company-scoped storm context used by /storm-intel/overview and /ai/*. */
export async function getStormContext(
  companyId: string,
  opts: { days?: number; bbox?: BBox; limit?: number } = {}
): Promise<StormContext> {
  const rows = await loadCompanyEventRows(companyId, opts);
  const summarized = await summarizeCompanyEvents(companyId, rows);
  const leadLimit = clamp(opts.limit, 1, 500, DEFAULT_LEAD_LIMIT);

  const leads: StormLeadRef[] = summarized.leads
    .map((lead) => ({ lead, stat: summarized.leadStats.get(lead.id) }))
    .sort((a, b) => {
      const aInside = a.stat?.inside ? 1 : 0;
      const bInside = b.stat?.inside ? 1 : 0;
      if (aInside !== bInside) return bInside - aInside;
      const aScore = a.stat?.score ?? 0;
      const bScore = b.stat?.score ?? 0;
      if (aScore !== bScore) return bScore - aScore;
      return a.lead.name.localeCompare(b.lead.name);
    })
    .slice(0, leadLimit)
    .map(({ lead, stat }) => ({
      id: lead.id,
      name: lead.name,
      address: lead.address,
      lat: lead.point.lat,
      lng: lead.point.lng,
      status: lead.status,
      score: stat?.score ?? lead.persistedScore ?? 0,
      stormEventId: stat?.eventId ?? lead.stormEventId ?? null,
    }));

  return {
    generatedAt: new Date().toISOString(),
    ...(opts.bbox ? { bbox: opts.bbox } : {}),
    events: summarized.events,
    leads,
    stats: summarized.stats,
  };
}

/**
 * Deterministic impact for a coordinate. When a companyId is supplied, persisted
 * company StormEvents are included alongside live NOAA alert clusters.
 */
export async function getImpactForPoint(
  lat: number,
  lng: number,
  opts: { companyId?: string; includeLiveAlerts?: boolean; days?: number } = {}
): Promise<ImpactResult> {
  const point: GeoPoint = { lat: Number(lat), lng: Number(lng) };
  if (!Number.isFinite(point.lat) || !Number.isFinite(point.lng)) {
    return computeImpact({ lat: 0, lng: 0 }, []).result;
  }
  const candidates = await buildImpactCandidates(opts.companyId, {
    includeLiveAlerts: opts.includeLiveAlerts,
    days: opts.days,
  });
  return computeImpact(point, candidates).result;
}

/** Deterministic storm-impact score for one lead, persisted on Lead.stormImpactScore. */
export async function scoreLeadAgainstStorms(
  companyId: string,
  leadId: string
): Promise<{ score: number; eventId?: string; drivers: Array<{ label: string; weight: number }> }> {
  const lead = await prisma.lead.findFirst({
    where: { id: leadId, companyId },
    select: { id: true, propertyLatLng: true },
  });
  if (!lead) throw new Error('Lead not found');

  const point = extractLatLng(lead.propertyLatLng);
  let score = 0;
  let drivers: Array<{ label: string; weight: number }> = [];
  let eventId: string | undefined;

  if (point) {
    const candidates = await buildImpactCandidates(companyId, {
      includeLiveAlerts: true,
      days: DEFAULT_SCORING_DAYS,
    });
    const computation = computeImpact(point, candidates);
    score = computation.result.score;
    drivers = computation.result.drivers;
    if (computation.bestEventId && !computation.bestEventId.startsWith(LIVE_EVENT_ID_PREFIX)) {
      eventId = computation.bestEventId;
    }
  }

  await prisma.lead.update({
    where: { id: lead.id },
    data: { stormImpactScore: score, stormScoredAt: new Date() },
  });

  return { score, ...(eventId ? { eventId } : {}), drivers };
}

/**
 * Re-score every company lead that has coordinates. Used by
 * POST /storm-intel/rescore-leads and by the demo seed.
 */
export async function rescoreLeadsForCompany(
  companyId: string,
  opts: { eventId?: string; includeLiveAlerts?: boolean; limit?: number } = {}
): Promise<{
  scored: number;
  updated: number;
  leads: Array<{ id: string; score: number; eventId?: string }>;
}> {
  const candidates = await buildImpactCandidates(companyId, {
    includeLiveAlerts: opts.includeLiveAlerts !== false,
    days: DEFAULT_SCORING_DAYS,
  });

  const rows = await prisma.lead.findMany({
    where: {
      companyId,
      ...(opts.eventId ? { stormEventId: opts.eventId } : {}),
    },
    select: {
      id: true,
      propertyLatLng: true,
      stormImpactScore: true,
      stormScoredAt: true,
    },
    orderBy: { createdAt: 'asc' },
    ...(opts.limit ? { take: clamp(opts.limit, 1, 5000, 5000) } : {}),
  });

  const leads: Array<{ id: string; score: number; eventId?: string }> = [];
  let updated = 0;

  for (const row of rows) {
    const point = extractLatLng(row.propertyLatLng);
    if (!point) continue;
    const computation = computeImpact(point, candidates);
    const score = computation.result.score;
    const bestEventId =
      computation.bestEventId && !computation.bestEventId.startsWith(LIVE_EVENT_ID_PREFIX)
        ? computation.bestEventId
        : undefined;

    if (row.stormImpactScore !== score || row.stormScoredAt == null) {
      await prisma.lead.update({
        where: { id: row.id },
        data: { stormImpactScore: score, stormScoredAt: new Date() },
      });
      updated++;
    }
    leads.push({ id: row.id, score, ...(bestEventId ? { eventId: bestEventId } : {}) });
  }

  return { scored: leads.length, updated, leads };
}

export interface SyncNoaaEventsResult {
  created: number;
  updated: number;
  events: StormEventSummary[];
  degraded?: boolean;
  error?: string;
}

function clusterToEventData(cluster: AlertCluster): {
  create: Omit<Prisma.StormEventUncheckedCreateInput, 'companyId' | 'externalId'>;
  update: Prisma.StormEventUpdateInput;
} {
  const metadata = {
    alertIds: cluster.alertIds,
    headline: cluster.headline,
    severity: cluster.severity,
    eventTypes: cluster.eventTypes,
    parameters: cluster.parameters,
    area: cluster.area,
    alertCount: cluster.alertCount,
    centroid: cluster.centroid,
    syncedAt: new Date().toISOString(),
  } as unknown as Prisma.InputJsonValue;

  const affectedArea = {
    areaDesc: cluster.area,
    alertCount: cluster.alertCount,
  } as unknown as Prisma.InputJsonValue;

  const geometry = cluster.geometry
    ? (cluster.geometry as unknown as Prisma.InputJsonValue)
    : Prisma.JsonNull;

  const shared = {
    name: cluster.name,
    description: cluster.headline || cluster.name,
    startDate: new Date(cluster.startMs),
    endDate: new Date(cluster.endMs),
    severity: cluster.severity as Prisma.StormEventUncheckedCreateInput['severity'],
    eventType: cluster.eventType as Prisma.StormEventUncheckedCreateInput['eventType'],
    affectedArea,
    source: 'NOAA',
    geometry,
    metadata,
  };

  return {
    create: shared,
    update: {
      ...shared,
      updatedAt: new Date(),
    },
  };
}

/**
 * Fetch live NOAA alerts and idempotently upsert company StormEvent rows.
 *
 * Idempotency: externalId = 'noaa:' + sha1(sorted alert ids); re-running sync for
 * the same alert set updates the existing rows (created: 0).
 * Degradation: any NOAA network failure returns cached company events with
 * degraded: true instead of throwing.
 */
export async function syncNoaaEvents(
  companyId: string,
  opts: { limit?: number; bbox?: BBox } = {}
): Promise<SyncNoaaEventsResult> {
  const limit = clamp(opts.limit, 1, 500, 100);
  const live = await fetchLiveAlerts({ limit, bbox: opts.bbox });

  let clusters: AlertCluster[] = [];
  if (live.alerts.length > 0) {
    try {
      clusters = clusterWeatherAlerts(live.alerts);
    } catch {
      clusters = [];
    }
  }

  // National NOAA feeds are noisy: persist only storms over the company's own
  // footprint (or the most severe dozen while the company has no coordinates).
  if (clusters.length > 0) {
    try {
      const footprint = await loadCompanyFootprint(companyId);
      clusters = selectRelevantClusters(clusters, footprint);
    } catch {
      // If footprint loading fails, fall back to a bounded subset rather than all.
      clusters = clusters
        .sort(
          (a, b) =>
            (SEVERITY_RANK[b.severity] ?? 0) - (SEVERITY_RANK[a.severity] ?? 0) ||
            b.startMs - a.startMs
        )
        .slice(0, MAX_EVENTS_WITHOUT_FOOTPRINT);
    }
  }

  if (clusters.length === 0) {
    const cachedRows = await loadCompanyEventRows(companyId, {
      days: DEFAULT_SCORING_DAYS,
      limit,
      ...(opts.bbox ? { bbox: opts.bbox } : {}),
    });
    const summarized = await summarizeCompanyEvents(companyId, cachedRows);
    return {
      created: 0,
      updated: 0,
      events: summarized.events,
      degraded: live.degraded,
      ...(live.error ? { error: live.error } : {}),
    };
  }

  const existing = await prisma.stormEvent.findMany({
    where: { companyId, externalId: { in: clusters.map((cluster) => cluster.externalId) } },
    select: { externalId: true },
  });
  const existingExternalIds = new Set(
    existing.map((row) => row.externalId).filter((value): value is string => !!value)
  );

  let created = 0;
  let updated = 0;
  const savedRows: StormEvent[] = [];

  for (const cluster of clusters) {
    const data = clusterToEventData(cluster);
    try {
      const saved = await prisma.stormEvent.upsert({
        where: {
          companyId_externalId: { companyId, externalId: cluster.externalId },
        },
        create: {
          ...data.create,
          companyId,
          externalId: cluster.externalId,
        },
        update: data.update,
      });
      if (existingExternalIds.has(cluster.externalId)) updated++;
      else created++;
      savedRows.push(saved);
    } catch {
      // A single malformed cluster must not abort the whole sync.
    }
  }

  const summarized = await summarizeCompanyEvents(companyId, savedRows);
  return {
    created,
    updated,
    events: summarized.events,
    degraded: live.degraded,
    ...(live.error ? { error: live.error } : {}),
  };
}

export interface ConvertZoneLead {
  id: string;
  firstName: string;
  lastName: string;
  propertyAddress: string;
  score: number;
  stormImpactScore: number;
  status: string;
}

export interface ConvertZoneResult {
  /** New canvass leads created from real addresses in the zone. */
  created: number;
  /** Leads already in the CRM that sit inside the zone (flagged and rescored). */
  existing: number;
  zone: {
    name: string;
    severity: string;
    lat: number;
    lng: number;
    impactScore: number;
    eventId?: string;
    /** "event" = the storm's own outline; "radius" = a circle around the clicked point. */
    shape: 'event' | 'radius';
    radiusKm?: number;
    areaKm2: number;
  };
  /** Kept for older clients: existing leads first, then new ones. */
  leads: ConvertZoneLead[];
  existingLeads: ConvertZoneLead[];
  newLeads: ConvertZoneLead[];
  addressLookup: {
    status: 'ok' | 'unconfigured' | 'failed' | 'skipped';
    source: string | null;
    sampledPoints: number;
    addressesFound: number;
    alreadyLeads: number;
    droppedBusinesses: number;
    capped: boolean;
    message?: string;
  };
}

const DEFAULT_ZONE_RADIUS_KM = 1;
const MAX_NEW_LEADS = 100;
const UNKNOWN_HOMEOWNER = { firstName: 'Homeowner', lastName: '(not yet identified)' } as const;

/**
 * Pull leads for a storm zone: the storm's own outline (eventId) or a circle around
 * a clicked point (lat/lng + radiusKm).
 *
 *  1. Every existing lead inside the zone is linked to the storm and rescored.
 *  2. Real street addresses inside the zone (MapMap / OpenStreetMap) that are not
 *     already leads become NEW canvass leads with the homeowner marked as not yet
 *     identified. Nothing is invented: no names, no phone numbers.
 *
 * Idempotent: running it again rescored the same leads and creates no duplicates.
 */
export async function convertStormZoneToLeads(
  companyId: string,
  opts: { eventId?: string; lat?: number; lng?: number; radiusKm?: number; label?: string; count?: number } = {},
  deps: { addressProvider?: AddressProvider | null } = {}
): Promise<ConvertZoneResult> {
  const maxNew = clamp(opts.count ?? 25, 1, MAX_NEW_LEADS, 25);
  const radiusKm = clamp(opts.radiusKm ?? DEFAULT_ZONE_RADIUS_KM, 0.2, 5, DEFAULT_ZONE_RADIUS_KM);
  const hasPoint = typeof opts.lat === 'number' && typeof opts.lng === 'number';

  const candidates = await buildImpactCandidates(companyId, { includeLiveAlerts: true, days: DEFAULT_SCORING_DAYS });

  let eventRow: { id: string; name: string; severity: string; geometry: unknown } | null = null;
  if (opts.eventId) {
    eventRow = await prisma.stormEvent.findFirst({
      where: { id: opts.eventId, companyId },
      select: { id: true, name: true, severity: true, geometry: true },
    });
    if (!eventRow) throw Object.assign(new Error('Storm event not found'), { status: 404 });
  } else if (!hasPoint) {
    eventRow = await prisma.stormEvent.findFirst({
      where: { companyId },
      orderBy: { startDate: 'desc' },
      select: { id: true, name: true, severity: true, geometry: true },
    });
  }

  // Zone: the event's own outline when it has one, otherwise a circle.
  const eventGeometry = eventRow?.geometry ? normalizeGeometry(eventRow.geometry) : null;
  const eventCentroid = eventGeometry ? geometryCentroid(eventGeometry) : null;
  const center: GeoPoint | null = hasPoint
    ? { lat: opts.lat!, lng: opts.lng! }
    : eventCentroid;
  if (!center) {
    throw Object.assign(new Error('Pick a point on the map or a storm event with an outline'), { status: 400 });
  }
  const useEventShape = !hasPoint && !!eventGeometry;
  const zoneGeometry = useEventShape ? eventGeometry! : approximateBufferPolygon(center, radiusKm);

  const centerImpact = computeImpact(center, candidates);
  const resolvedEventId =
    eventRow?.id ??
    (centerImpact.bestEventId && !centerImpact.bestEventId.startsWith(LIVE_EVENT_ID_PREFIX)
      ? centerImpact.bestEventId
      : undefined);
  const zoneName =
    opts.label?.trim() ||
    eventRow?.name ||
    centerImpact.result.nearbyEvents[0]?.name ||
    `Map zone (${center.lat.toFixed(3)}, ${center.lng.toFixed(3)})`;
  const zoneSeverity = eventRow?.severity || centerImpact.result.nearbyEvents[0]?.severity || 'MODERATE';
  const scoreAt = (p: GeoPoint) => Math.max(computeImpact(p, candidates).result.score, Math.round(centerImpact.result.score * 0.9));

  const select = {
    id: true, firstName: true, lastName: true, propertyAddress: true, score: true, stormImpactScore: true, status: true,
  } as const;
  const toLead = (row: { id: string; firstName: string; lastName: string; propertyAddress: string; score: number | null; stormImpactScore: number | null; status: string }, fallback: number): ConvertZoneLead => ({
    ...row,
    score: row.score ?? fallback,
    stormImpactScore: row.stormImpactScore ?? fallback,
  });

  // 1. Existing leads inside the zone.
  const { leads: geoLeads } = await loadCompanyGeoData(companyId);
  const insideLeads = geoLeads.filter((l) => pointInGeometry(l.point, zoneGeometry));
  const existingLeads: ConvertZoneLead[] = [];
  for (const lead of insideLeads) {
    const score = scoreAt(lead.point);
    const updated = await prisma.lead.update({
      where: { id: lead.id },
      data: {
        stormImpactScore: Math.max(lead.persistedScore ?? 0, score),
        stormScoredAt: new Date(),
        ...(resolvedEventId ? { stormEventId: resolvedEventId } : {}),
      },
      select,
    });
    existingLeads.push(toLead(updated, score));
  }

  // 2. Real addresses that are not leads yet.
  const provider = deps.addressProvider === undefined ? getAddressProvider() : deps.addressProvider;
  const newLeads: ConvertZoneLead[] = [];
  const lookup: ConvertZoneResult['addressLookup'] = {
    status: provider ? 'ok' : 'unconfigured',
    source: provider?.source ?? null,
    sampledPoints: 0,
    addressesFound: 0,
    alreadyLeads: 0,
    droppedBusinesses: 0,
    capped: false,
    ...(provider ? {} : { message: 'Address lookup is not configured (set MAPMAP_API_KEY). Existing leads in the zone were still flagged.' }),
  };

  if (provider) {
    try {
      const found = await provider.addressesInZone(zoneGeometry, { maxPoints: Math.min(200, Math.max(20, maxNew * 2)) });
      lookup.sampledPoints = found.sampledPoints;
      lookup.addressesFound = found.addresses.length;
      lookup.droppedBusinesses = found.droppedBusinesses;

      const allLeads = await prisma.lead.findMany({ where: { companyId }, select: { propertyAddress: true } });
      const known = new Set(allLeads.map((l) => normalizeAddressKey(l.propertyAddress)));
      const fresh = found.addresses.filter((a) => {
        if (known.has(a.key)) {
          lookup.alreadyLeads += 1;
          return false;
        }
        return true;
      });
      lookup.capped = fresh.length > maxNew;

      for (const a of fresh.slice(0, maxNew)) {
        const score = scoreAt({ lat: a.lat, lng: a.lng });
        const created = await prisma.lead.create({
          data: {
            companyId,
            ...UNKNOWN_HOMEOWNER,
            propertyAddress: a.address,
            propertyLatLng: { lat: a.lat, lng: a.lng },
            source: 'STORM_EVENT',
            status: 'NEW',
            stormEventId: resolvedEventId ?? null,
            score,
            stormImpactScore: score,
            stormScoredAt: new Date(),
            notes:
              `Canvass lead from storm zone "${zoneName}" (${zoneSeverity}, impact score ${score}/100). ` +
              `Address from ${provider.source}; homeowner not yet identified.`,
          },
          select,
        });
        newLeads.push(toLead(created, score));
        known.add(a.key);
      }
      if (found.addresses.length === 0) {
        lookup.message = `No street addresses found in this zone (${found.sampledPoints} points checked). OpenStreetMap may not have house numbers here yet.`;
      }
    } catch (error) {
      lookup.status = 'failed';
      lookup.message = errorMessage(error);
    }
  }

  return {
    created: newLeads.length,
    existing: existingLeads.length,
    zone: {
      name: zoneName,
      severity: zoneSeverity,
      lat: Number(center.lat.toFixed(5)),
      lng: Number(center.lng.toFixed(5)),
      impactScore: centerImpact.result.score,
      ...(resolvedEventId ? { eventId: resolvedEventId } : {}),
      shape: useEventShape ? 'event' : 'radius',
      ...(useEventShape ? {} : { radiusKm }),
      areaKm2: Number(polygonAreaKm2(zoneGeometry).toFixed(2)),
    },
    leads: [...existingLeads, ...newLeads],
    existingLeads,
    newLeads,
    addressLookup: lookup,
  };
}
