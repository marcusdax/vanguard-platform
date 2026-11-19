/**
 * NOAA alert normalisation + deterministic clustering for the RoofPro storm
 * intelligence engine (docs/AI_STORM_ARCHITECTURE.md §4.3).
 *
 * Pure module: no I/O, no Prisma, no new dependencies. Unit-tested with
 * `node:test`.
 */
import { createHash } from 'node:crypto';
import { centroid as geometryCentroid, mergeGeometries, normalizeGeometry } from './geo';
import type { NormalizedGeometry } from './geo';
import type { GeoPoint, StormSeverity } from './types';

/** The six StormEventType enum values from prisma/schema.prisma. */
export type StormEventTypeValue =
  | 'TORNADO'
  | 'HURRICANE'
  | 'SEVERE_THUNDERSTORM'
  | 'FLASH_FLOOD'
  | 'WINTER_STORM'
  | 'WILDFIRE';

export const STORM_EVENT_TYPES: StormEventTypeValue[] = [
  'TORNADO',
  'HURRICANE',
  'SEVERE_THUNDERSTORM',
  'FLASH_FLOOD',
  'WINTER_STORM',
  'WILDFIRE',
];

export const SEVERITY_ORDER: StormSeverity[] = ['MINOR', 'MODERATE', 'SEVERE', 'EXTREME'];

export const EVENT_TYPE_LABELS: Record<StormEventTypeValue, string> = {
  TORNADO: 'Tornado',
  HURRICANE: 'Hurricane',
  SEVERE_THUNDERSTORM: 'Severe Thunderstorm',
  FLASH_FLOOD: 'Flash Flood',
  WINTER_STORM: 'Winter Storm',
  WILDFIRE: 'Wildfire',
};

/** Minimum shape of a NOAA alert consumed by the engine. */
export interface WeatherAlertLike {
  id?: string | null;
  type?: string | null;
  severity?: string | null;
  effective?: string | null;
  onset?: string | null;
  expires?: string | null;
  ends?: string | null;
  headline?: string | null;
  area?: string | null;
  geometry?: unknown;
  parameters?: unknown;
}

export interface NormalizedAlert {
  id: string;
  eventType: StormEventTypeValue;
  severity: StormSeverity;
  startMs: number;
  endMs: number;
  headline: string;
  area: string;
  geometry: NormalizedGeometry | null;
  centroid: GeoPoint | null;
  parameters: Record<string, unknown>;
}

export interface AlertCluster {
  externalId: string;
  eventType: StormEventTypeValue;
  severity: StormSeverity;
  startMs: number;
  endMs: number;
  name: string;
  area: string;
  headline: string;
  geometry: NormalizedGeometry | null;
  centroid: GeoPoint | null;
  alertIds: string[];
  eventTypes: StormEventTypeValue[];
  parameters: Record<string, unknown>;
  alertCount: number;
}

/** Alerts in the same 6 hour window (and ~0.25° centroid cell) cluster together. */
export const CLUSTER_WINDOW_MS = 6 * 60 * 60 * 1000;
/** Grid size (degrees) used to round alert centroids before clustering. */
export const CENTROID_GRID_DEGREES = 0.25;

function parseTimeMs(value: string | null | undefined): number | null {
  if (!value) return null;
  const parsed = Date.parse(value);
  return Number.isFinite(parsed) ? parsed : null;
}

/** NOAA severity string → StormSeverity. Unknown severities degrade to MINOR. */
export function mapSeverity(value: string | null | undefined): StormSeverity {
  const normalized = String(value ?? '').trim().toLowerCase();
  if (normalized === 'extreme') return 'EXTREME';
  if (normalized === 'severe') return 'SEVERE';
  if (normalized === 'moderate') return 'MODERATE';
  if (normalized === 'minor') return 'MINOR';
  return 'MINOR';
}

/** NOAA event name → StormEventType per §4.3. Unmatched events default to SEVERE_THUNDERSTORM. */
export function mapEventType(value: string | null | undefined): StormEventTypeValue {
  const label = String(value ?? '');
  if (/tornado/i.test(label)) return 'TORNADO';
  if (/hurricane|tropical/i.test(label)) return 'HURRICANE';
  if (/flood/i.test(label)) return 'FLASH_FLOOD';
  if (/winter|blizzard|ice\b|freez|sleet|snow/i.test(label)) return 'WINTER_STORM';
  if (/fire|red\s*flag|smoke/i.test(label)) return 'WILDFIRE';
  if (/thunderstorm|wind|hail|storm/i.test(label)) return 'SEVERE_THUNDERSTORM';
  return 'SEVERE_THUNDERSTORM';
}

export function maxSeverity(severities: StormSeverity[]): StormSeverity {
  let best: StormSeverity = 'MINOR';
  let bestIndex = SEVERITY_ORDER.indexOf('MINOR');
  for (const severity of severities) {
    const index = SEVERITY_ORDER.indexOf(severity);
    if (index > bestIndex) {
      best = severity;
      bestIndex = index;
    }
  }
  return best;
}

/**
 * Human-friendly place label from a NOAA areaDesc.
 * "Collin, TX" → "Collin County, TX"; "Collin County, TX" is preserved.
 */
export function parseAreaLabel(area: string | null | undefined): string | null {
  const raw = String(area ?? '').trim();
  if (!raw) return null;
  const first = raw.split(';')[0].trim();
  if (!first) return null;
  const hasCounty = /county|parish|borough/i.test(first);
  const match = first.match(/^(.*?),\s*([A-Z]{2})$/);
  if (match) {
    const name = match[1].trim();
    if (!name) return first;
    if (hasCounty) return `${name}, ${match[2]}`;
    return `${name} County, ${match[2]}`;
  }
  return first;
}

function mergeParameters(alerts: NormalizedAlert[]): Record<string, unknown> {
  const merged: Record<string, unknown> = {};
  for (const alert of alerts) {
    for (const [key, value] of Object.entries(alert.parameters ?? {})) {
      if (!(key in merged)) merged[key] = value;
    }
  }
  return merged;
}

function buildHeadline(alerts: NormalizedAlert[]): string {
  for (const alert of alerts) {
    if (alert.headline) return alert.headline;
  }
  return '';
}

/**
 * Normalise one raw NOAA alert. Returns null only when the alert has no usable
 * identity at all (no id and no event/area combination).
 */
export function normalizeAlert(
  alert: WeatherAlertLike,
  options: { nowMs?: number } = {}
): NormalizedAlert | null {
  if (!alert || typeof alert !== 'object') return null;
  const eventType = mapEventType(alert.type);
  const severity = mapSeverity(alert.severity);
  const geometry = normalizeGeometry(alert.geometry);
  const centroid = geometry ? geometryCentroid(geometry) : null;

  const nowMs = options.nowMs ?? Date.now();
  const startMs = parseTimeMs(alert.effective) ?? parseTimeMs(alert.onset) ?? nowMs;
  const endMs = parseTimeMs(alert.expires) ?? parseTimeMs(alert.ends) ?? startMs + CLUSTER_WINDOW_MS;
  const area = String(alert.area ?? '').trim();
  const headline = String(alert.headline ?? '').trim();

  const id =
    alert.id && String(alert.id).trim()
      ? String(alert.id).trim()
      : `alert:${createHash('sha1')
          .update(`${eventType}|${severity}|${area}|${alert.type ?? ''}|${startMs}`)
          .digest('hex')
          .slice(0, 16)}`;

  const parameters =
    alert.parameters && typeof alert.parameters === 'object' && !Array.isArray(alert.parameters)
      ? (alert.parameters as Record<string, unknown>)
      : {};

  return {
    id,
    eventType,
    severity,
    startMs,
    endMs: Math.max(endMs, startMs),
    headline,
    area,
    geometry,
    centroid,
    parameters,
  };
}

function roundCoord(value: number): string {
  return (Math.round(value / CENTROID_GRID_DEGREES) * CENTROID_GRID_DEGREES).toFixed(2);
}

function centroidKey(alert: NormalizedAlert): string {
  if (!alert.centroid) return `area:${alert.area.toLowerCase() || 'unknown'}`;
  return `${roundCoord(alert.centroid.lat)},${roundCoord(alert.centroid.lng)}`;
}

/** Deterministic cluster key: event type + rounded centroid (0.25°) + 6h window. */
export function alertClusterKey(alert: NormalizedAlert): string {
  const window = Math.floor(alert.startMs / CLUSTER_WINDOW_MS);
  return `${alert.eventType}|${centroidKey(alert)}|${window}`;
}

/** Stable event external id: `noaa:` + sha1 of the sorted alert ids. */
export function buildExternalId(alertIds: string[]): string {
  const sorted = [...alertIds].sort();
  const digest = createHash('sha1').update(sorted.join('|')).digest('hex');
  return `noaa:${digest}`;
}

function buildClusterName(area: string, eventType: StormEventTypeValue, centroid: GeoPoint | null): string {
  const label = EVENT_TYPE_LABELS[eventType];
  const place = parseAreaLabel(area);
  if (place) return `${label} — ${place}`.slice(0, 180);
  if (centroid) return `${label} — ${centroid.lat.toFixed(2)}, ${centroid.lng.toFixed(2)}`.slice(0, 180);
  return `${label} — Regional`.slice(0, 180);
}

interface PendingCluster {
  alerts: NormalizedAlert[];
  anchorCentroid: GeoPoint | null;
  anchorArea: string;
  startMs: number;
  eventType: StormEventTypeValue;
}

/**
 * Group normalised alerts into StormEvent clusters.
 *
 * Rules (§4.3): same event type, centroids within the 0.25° rounding cell, and
 * alert start times within a 6 h window of the cluster's first alert. Alerts
 * without geometry fall back to matching areaDesc (same warning zone).
 *
 * Deterministic: alerts are sorted by (startMs, id) before grouping so the same
 * input in any order produces the same externalIds, names, and geometry.
 */
export function clusterAlerts(alerts: NormalizedAlert[]): AlertCluster[] {
  const sorted = [...alerts].sort((a, b) =>
    a.startMs === b.startMs ? a.id.localeCompare(b.id) : a.startMs - b.startMs
  );

  const groups: PendingCluster[] = [];
  for (const alert of sorted) {
    const group = groups.find((candidate) => {
      if (candidate.eventType !== alert.eventType) return false;
      if (Math.abs(alert.startMs - candidate.startMs) > CLUSTER_WINDOW_MS) return false;
      if (alert.centroid && candidate.anchorCentroid) {
        return (
          Math.abs(alert.centroid.lat - candidate.anchorCentroid.lat) <= CENTROID_GRID_DEGREES &&
          Math.abs(alert.centroid.lng - candidate.anchorCentroid.lng) <= CENTROID_GRID_DEGREES
        );
      }
      return (alert.area || '').toLowerCase() === candidate.anchorArea.toLowerCase();
    });

    if (group) {
      group.alerts.push(alert);
    } else {
      groups.push({
        alerts: [alert],
        anchorCentroid: alert.centroid ?? null,
        anchorArea: alert.area ?? '',
        startMs: alert.startMs,
        eventType: alert.eventType,
      });
    }
  }

  const clusters: AlertCluster[] = [];
  for (const group of groups) {
    const groupAlerts = group.alerts;
    const alertIds = groupAlerts.map((alert) => alert.id).sort();
    const eventTypes = Array.from(new Set(groupAlerts.map((alert) => alert.eventType))).sort() as StormEventTypeValue[];
    const eventType = eventTypes[0] ?? 'SEVERE_THUNDERSTORM';
    const severity = maxSeverity(groupAlerts.map((alert) => alert.severity));
    const startMs = Math.min(...groupAlerts.map((alert) => alert.startMs));
    const endMs = Math.max(...groupAlerts.map((alert) => alert.endMs));
    const area = groupAlerts.find((alert) => alert.area)?.area ?? '';
    const geometry = mergeGeometries(groupAlerts.map((alert) => alert.geometry).filter(Boolean));
    const centroid = geometry
      ? geometryCentroid(geometry)
      : groupAlerts.find((alert) => alert.centroid)?.centroid ?? null;
    const parameters = mergeParameters(groupAlerts);
    clusters.push({
      externalId: buildExternalId(alertIds),
      eventType,
      severity,
      startMs,
      endMs,
      name: buildClusterName(area, eventType, centroid),
      area,
      headline: buildHeadline(groupAlerts),
      geometry,
      centroid,
      alertIds,
      eventTypes,
      parameters,
      alertCount: groupAlerts.length,
    });
  }

  // Stable output order: newest first, then external id.
  clusters.sort((a, b) => (a.startMs === b.startMs ? a.externalId.localeCompare(b.externalId) : b.startMs - a.startMs));
  return clusters;
}

/** Convenience: normalise + cluster raw NOAA alerts. */
export function clusterWeatherAlerts(
  alerts: WeatherAlertLike[],
  options: { nowMs?: number } = {}
): AlertCluster[] {
  const normalized: NormalizedAlert[] = [];
  const seenIds = new Set<string>();
  for (const alert of alerts ?? []) {
    const item = normalizeAlert(alert, options);
    if (!item) continue;
    if (seenIds.has(item.id)) continue;
    seenIds.add(item.id);
    normalized.push(item);
  }
  return clusterAlerts(normalized);
}

/** Deterministic headline helper shared with persistence code. */
export function clusterHeadline(alerts: NormalizedAlert[]): string {
  return buildHeadline(alerts);
}
