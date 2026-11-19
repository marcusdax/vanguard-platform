/**
 * Deterministic storm-impact scoring for the RoofPro storm-intelligence engine
 * (docs/AI_STORM_ARCHITECTURE.md §4.3).
 *
 * Formula: polygon containment = 100, otherwise max(0, 100 - distanceKm * 2.2),
 * weighted by severity (EXTREME 1.0 / SEVERE 0.8 / MODERATE 0.6 / MINOR 0.4),
 * capped at 100. Pure module (no I/O), unit-tested with `node:test`.
 */
import { eventDistanceKmToPoint, normalizeGeometry, pointInGeometry } from './geo';
import type { GeoPoint, ImpactResult, StormSeverity } from './types';

export const SEVERITY_WEIGHTS: Record<StormSeverity, number> = {
  EXTREME: 1,
  SEVERE: 0.8,
  MODERATE: 0.6,
  MINOR: 0.4,
};

/** Points/km used by the linear distance decay. */
export const IMPACT_DISTANCE_DECAY_PER_KM = 2.2;
/** "Inside impact" radius for lead/job context counts (containment, or ≤25 km). */
export const IMPACT_RADIUS_KM = 25;
/** Radius used to report nearby events in an impact response. */
export const NEARBY_EVENT_RADIUS_KM = 50;
export const MAX_NEARBY_EVENTS = 8;
export const MAX_IMPACT_DRIVERS = 6;

export interface ImpactEvent {
  id: string;
  name: string;
  severity: StormSeverity;
  geometry?: unknown;
  centroid?: GeoPoint | null;
  area?: string | null;
  source?: string;
}

export interface ScoredImpactEvent {
  event: ImpactEvent;
  /** Score before the severity weight is applied. */
  rawScore: number;
  /** Final 0–100 weighted score (rounded, capped). */
  score: number;
  contained: boolean;
  distanceKm: number;
  severity: StormSeverity;
}

export interface ImpactComputation {
  result: ImpactResult;
  bestEventId?: string;
  bestSeverity: 'NONE' | StormSeverity;
}

export function severityWeight(severity: string | null | undefined): number {
  const key = String(severity ?? '').trim().toUpperCase() as StormSeverity;
  return SEVERITY_WEIGHTS[key] ?? SEVERITY_WEIGHTS.MINOR;
}

/** Extract a { lat, lng } point from the persisted Lead.propertyLatLng shapes. */
export function extractLatLng(value: unknown): GeoPoint | null {
  let raw = value;
  if (typeof raw === 'string') {
    try {
      raw = JSON.parse(raw);
    } catch {
      return null;
    }
  }
  if (!raw) return null;

  const validate = (lat: unknown, lng: unknown): GeoPoint | null => {
    const latNum = Number(lat);
    const lngNum = Number(lng);
    if (!Number.isFinite(latNum) || !Number.isFinite(lngNum)) return null;
    if (latNum < -90 || latNum > 90 || lngNum < -180 || lngNum > 180) return null;
    return { lat: latNum, lng: lngNum };
  };

  if (Array.isArray(raw)) {
    if (raw.length < 2) return null;
    // Legacy plain array is interpreted as [lat, lng].
    return validate(raw[0], raw[1]);
  }
  if (typeof raw !== 'object') return null;
  const obj = raw as Record<string, unknown>;

  // GeoJSON Point: coordinates are [lng, lat].
  if (obj.type === 'Point' && Array.isArray(obj.coordinates) && obj.coordinates.length >= 2) {
    return validate(obj.coordinates[1], obj.coordinates[0]);
  }

  const lat = obj.lat ?? obj.latitude;
  const lng = obj.lng ?? obj.lon ?? obj.longitude;
  return validate(lat, lng);
}

/** Score a single event against a point; null when the event has no locatable footprint. */
export function evaluateEventImpact(point: GeoPoint, event: ImpactEvent): ScoredImpactEvent | null {
  if (!event || !event.id) return null;
  const severity = event.severity;
  const hasGeometry = !!normalizeGeometry(event.geometry);
  const hasCentroid =
    !!event.centroid && Number.isFinite(event.centroid.lat) && Number.isFinite(event.centroid.lng);
  if (!hasGeometry && !hasCentroid) return null;

  const distanceKm = eventDistanceKmToPoint(point, event);
  if (!Number.isFinite(distanceKm)) return null;

  const contained = hasGeometry ? pointInGeometry(point, event.geometry) : distanceKm === 0;
  const rawScore = contained ? 100 : Math.max(0, 100 - distanceKm * IMPACT_DISTANCE_DECAY_PER_KM);
  const weighted = Math.min(100, Math.round(rawScore * severityWeight(severity)));

  return {
    event,
    rawScore,
    score: Math.max(0, weighted),
    contained,
    distanceKm,
    severity,
  };
}

/**
 * Score a point against all candidate events and rank best-first.
 * Deterministic tie-break: score desc, containment first, distance asc, id asc.
 */
export function rankEventImpacts(point: GeoPoint, events: ImpactEvent[]): ScoredImpactEvent[] {
  const scored: ScoredImpactEvent[] = [];
  for (const event of events ?? []) {
    const impact = evaluateEventImpact(point, event);
    if (impact) scored.push(impact);
  }
  scored.sort((a, b) => {
    if (b.score !== a.score) return b.score - a.score;
    if (a.contained !== b.contained) return a.contained ? -1 : 1;
    if (a.distanceKm !== b.distanceKm) return a.distanceKm - b.distanceKm;
    return a.event.id.localeCompare(b.event.id);
  });
  return scored;
}

function round1(value: number): number {
  return Math.round(value * 10) / 10;
}

function impactDriverLabel(scored: ScoredImpactEvent): string {
  const distance = scored.contained ? 'inside storm footprint' : `${round1(scored.distanceKm)} km away`;
  return `${scored.event.name} (${scored.severity}) — ${distance}`;
}

export function buildRecommendations(
  severity: 'NONE' | StormSeverity,
  score: number,
  nearbyEventCount: number
): string[] {
  if (severity === 'NONE' || score <= 0) {
    return ['No active storm impact detected for this location.'];
  }
  const recommendations: string[] = [];
  if (score >= 80) {
    recommendations.push('Prioritize canvassing and roof inspections within 48 hours.');
    recommendations.push('Check for shingle, gutter, and roof-deck damage before the next rain.');
    recommendations.push('Document existing conditions with dated photos before any repair.');
  } else if (score >= 50) {
    recommendations.push('Schedule inspections for impacted properties this week.');
    recommendations.push('Cross-check nearby leads against the storm footprint and update statuses.');
  } else {
    recommendations.push('Monitor the storm track and re-score this location if the footprint shifts closer.');
  }
  if (severity === 'EXTREME' || severity === 'SEVERE') {
    recommendations.push('Confirm crew safety plans and material staging before dispatching.');
  }
  if (nearbyEventCount > 1) {
    recommendations.push(`Compare ${nearbyEventCount} storm events for overlapping damage areas.`);
  }
  return Array.from(new Set(recommendations)).slice(0, 5);
}

/** Full deterministic ImpactResult plus the best event id (for Lead persistence). */
export function computeImpact(point: GeoPoint, events: ImpactEvent[]): ImpactComputation {
  const ranked = rankEventImpacts(point, events);
  const best = ranked.length > 0 ? ranked[0] : undefined;
  const hasImpact = !!best && best.score > 0;
  const severity: 'NONE' | StormSeverity = hasImpact ? best!.severity : 'NONE';
  const score = hasImpact ? best!.score : 0;

  const drivers = ranked
    .filter((impact) => impact.score > 0)
    .slice(0, MAX_IMPACT_DRIVERS)
    .map((impact) => ({ label: impactDriverLabel(impact), weight: impact.score }));

  const nearby = ranked
    .filter((impact) => impact.contained || impact.distanceKm <= NEARBY_EVENT_RADIUS_KM)
    .sort((a, b) => {
      if (a.distanceKm !== b.distanceKm) return a.distanceKm - b.distanceKm;
      return a.event.id.localeCompare(b.event.id);
    })
    .slice(0, MAX_NEARBY_EVENTS)
    .map((impact) => ({
      id: impact.event.id,
      name: impact.event.name,
      severity: impact.severity,
      distanceKm: round1(impact.distanceKm),
    }));

  const recommendations = buildRecommendations(severity, score, nearby.length);

  return {
    result: {
      score,
      severity,
      drivers,
      nearbyEvents: nearby,
      recommendations,
    },
    bestEventId: hasImpact ? best!.event.id : undefined,
    bestSeverity: severity,
  };
}

/** True when a point is inside the event footprint or within IMPACT_RADIUS_KM of it. */
export function isPointInEventImpact(point: GeoPoint, event: ImpactEvent): boolean {
  const scored = evaluateEventImpact(point, event);
  if (!scored) return false;
  return scored.contained || scored.distanceKm <= IMPACT_RADIUS_KM;
}
