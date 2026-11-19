/**
 * Pure AI-context helpers — no database, no network, no storm-engine imports.
 *
 * Keeping these separate means:
 *  - deterministic heuristics are unit-testable in isolation;
 *  - /api/v1/ai routes still boot and degrade gracefully while the storm engine
 *    module is unavailable;
 *  - the context loader (`context.ts`) and the feature services share one shape.
 */
import type { BBox, StormContext, StormEventSummary, StormLeadRef } from '../storm/types';

export const LEAD_STATUSES = [
  'NEW',
  'CONTACTED',
  'INSPECTION_SCHEDULED',
  'ESTIMATE_SENT',
  'WON',
  'LOST',
] as const;

export const OPEN_LEAD_STATUSES: string[] = ['NEW', 'CONTACTED', 'INSPECTION_SCHEDULED', 'ESTIMATE_SENT'];

export interface LeadDetail {
  id: string;
  name: string;
  address: string;
  status: string;
  lat?: number;
  lng?: number;
  /** Lead.score (legacy lead score) */
  score: number | null;
  stormImpactScore: number | null;
  stormEventId: string | null;
  createdAt: string;
}

export interface CompanyCounts {
  leadTotal: number;
  openLeads: number;
  leadsByStatus: Record<string, number>;
  jobTotal: number;
  activeJobs: number;
  jobsByStatus: Record<string, number>;
  companyName?: string;
}

export interface AiContextBundle {
  generatedAt: string;
  storm: StormContext;
  counts: CompanyCounts;
  /** true when the storm engine failed and we served an empty context */
  degraded: boolean;
}

export function emptyStormContext(): StormContext {
  return {
    generatedAt: new Date().toISOString(),
    events: [],
    leads: [],
    stats: {
      activeEvents: 0,
      extremeCount: 0,
      leadsInImpact: 0,
      jobsInImpact: 0,
      bySeverity: {},
    },
  };
}

export function emptyCompanyCounts(): CompanyCounts {
  return {
    leadTotal: 0,
    openLeads: 0,
    leadsByStatus: {},
    jobTotal: 0,
    activeJobs: 0,
    jobsByStatus: {},
  };
}

/** A complete bundle used when context loading fails (fail open, never hang). */
export function emptyAiContextBundle(): AiContextBundle {
  return {
    generatedAt: new Date().toISOString(),
    storm: emptyStormContext(),
    counts: emptyCompanyCounts(),
    degraded: true,
  };
}

function asFiniteNumber(value: unknown, fallback: number): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback;
}

export function computeBySeverity(events: StormEventSummary[]): Record<string, number> {
  const bySeverity: Record<string, number> = {};
  for (const event of events) {
    const severity = (event?.severity as string) || 'MINOR';
    bySeverity[severity] = (bySeverity[severity] ?? 0) + 1;
  }
  return bySeverity;
}

function computeLeadsInImpact(events: StormEventSummary[], leads: StormLeadRef[]): number {
  const eventIds = new Set(events.map((event) => event.id));
  return leads.filter((lead) => {
    if (lead.stormEventId) return eventIds.has(lead.stormEventId);
    return typeof lead.score === 'number' && lead.score > 0;
  }).length;
}

/** Defensive normalisation: tolerate a storm engine that is still being built. */
export function normalizeStormContext(raw: StormContext | null | undefined): StormContext {
  const events: StormEventSummary[] = Array.isArray(raw?.events) ? raw.events : [];
  const leads: StormLeadRef[] = Array.isArray(raw?.leads) ? raw.leads : [];
  const bySeverity = raw?.stats?.bySeverity ?? computeBySeverity(events);
  return {
    generatedAt: raw?.generatedAt || new Date().toISOString(),
    ...(raw?.bbox ? { bbox: raw.bbox } : {}),
    events,
    leads,
    stats: {
      activeEvents: asFiniteNumber(raw?.stats?.activeEvents, events.length),
      extremeCount: asFiniteNumber(
        raw?.stats?.extremeCount,
        events.filter((event) => event.severity === 'EXTREME').length
      ),
      leadsInImpact: asFiniteNumber(raw?.stats?.leadsInImpact, computeLeadsInImpact(events, leads)),
      jobsInImpact: asFiniteNumber(
        raw?.stats?.jobsInImpact,
        events.reduce((sum, event) => sum + asFiniteNumber(event.jobCount, 0), 0)
      ),
      bySeverity,
    },
  };
}

/** Restrict a context to a set of storm ids (used by the storm brief request). */
export function filterStormContext(context: StormContext, stormIds: string[]): StormContext {
  if (!Array.isArray(stormIds) || stormIds.length === 0) return context;
  const wanted = new Set(stormIds);
  const events = context.events.filter((event) => wanted.has(event.id));
  const leads = context.leads.filter((lead) => !lead.stormEventId || wanted.has(lead.stormEventId));
  return {
    generatedAt: context.generatedAt,
    ...(context.bbox ? { bbox: context.bbox } : {}),
    events,
    leads,
    stats: {
      activeEvents: events.length,
      extremeCount: events.filter((event) => event.severity === 'EXTREME').length,
      leadsInImpact: leads.filter((lead) => lead.stormEventId && wanted.has(lead.stormEventId)).length,
      jobsInImpact: events.reduce((sum, event) => sum + asFiniteNumber(event.jobCount, 0), 0),
      bySeverity: computeBySeverity(events),
    },
  };
}

/** Extract {lat,lng} from the shapes Lead.propertyLatLng can hold. */
export function extractLatLng(propertyLatLng: unknown): { lat: number; lng: number } | null {
  if (!propertyLatLng || typeof propertyLatLng !== 'object') return null;
  const obj = propertyLatLng as Record<string, unknown>;
  if (obj.type === 'Point' && Array.isArray(obj.coordinates)) {
    const [lng, lat] = obj.coordinates as number[];
    if (typeof lat === 'number' && typeof lng === 'number') return { lat, lng };
    return null;
  }
  const lat = typeof obj.lat === 'number' ? obj.lat : null;
  const lng = typeof obj.lng === 'number' ? obj.lng : typeof obj.lon === 'number' ? obj.lon : null;
  if (lat === null || lng === null) return null;
  if (!Number.isFinite(lat) || !Number.isFinite(lng)) return null;
  return { lat, lng };
}

export type { BBox };
