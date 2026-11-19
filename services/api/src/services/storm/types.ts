/**
 * Storm intelligence shared types — CONTRACT FILE.
 *
 * Authored by the Lead (see docs/AI_STORM_ARCHITECTURE.md §4.2).
 * Owner: backend-storm. Extend additively; do not rename/remove fields that
 * /api/v1/ai/* consumes — backend-ai imports these types.
 */

export interface BBox {
  north: number;
  south: number;
  east: number;
  west: number;
}

export interface GeoPoint {
  lat: number;
  lng: number;
}

export type StormSeverity = 'MINOR' | 'MODERATE' | 'SEVERE' | 'EXTREME';

export interface StormEventSummary {
  id: string;
  externalId?: string | null;
  name: string;
  eventType: string;
  severity: StormSeverity;
  startDate: string;
  endDate: string;
  area?: string | null;
  source: 'NOAA' | 'MANUAL' | 'IMPORT';
  /** GeoJSON Polygon/MultiPolygon when available */
  geometry?: unknown;
  leadCount: number;
  jobCount: number;
  aiBrief?: unknown;
}

export interface StormLeadRef {
  id: string;
  name: string;
  address: string;
  lat?: number;
  lng?: number;
  status: string;
  score?: number | null;
  stormEventId?: string | null;
}

export interface StormStats {
  activeEvents: number;
  extremeCount: number;
  leadsInImpact: number;
  jobsInImpact: number;
  bySeverity: Record<string, number>;
}

export interface StormContext {
  generatedAt: string;
  bbox?: BBox;
  events: StormEventSummary[];
  leads: StormLeadRef[];
  stats: StormStats;
}

export interface ImpactResult {
  score: number;
  severity: 'NONE' | StormSeverity;
  drivers: Array<{ label: string; weight: number }>;
  nearbyEvents: Array<{ id: string; name: string; severity: string; distanceKm: number }>;
  recommendations: string[];
}
