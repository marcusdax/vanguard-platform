/**
 * H3 Geospatial Hex Territory Grid (powered by uber/h3-js).
 * Buckets active storm corridors, leads, jobs, and AUCTUM Spaces into H3 Resolution 7
 * hexagons (~5.16 km^2 neighborhood sectors) for territory ownership and saturation tracking.
 */
import { cellToBoundary, cellToLatLng, gridDisk, latLngToCell } from 'h3-js';

export const DEFAULT_H3_RESOLUTION = 7;

export interface HexPointInput {
  lat: number;
  lng: number;
  kind: 'storm' | 'lead' | 'job' | 'space';
  severity?: string;
  score?: number;
  label?: string;
}

export interface HexCellProperties {
  h3Index: string;
  resolution: number;
  center: { lat: number; lng: number };
  stormCount: number;
  leadCount: number;
  jobCount: number;
  spaceCount: number;
  maxScore: number;
  severity: string;
  saturationPct: number;
  labels: string[];
}

export interface HexFeature {
  type: 'Feature';
  geometry: {
    type: 'Polygon';
    coordinates: [number, number][][];
  };
  properties: HexCellProperties;
}

const SEVERITY_ORDER: Record<string, number> = {
  MINOR: 1,
  MODERATE: 2,
  SEVERE: 3,
  EXTREME: 4,
};

function higherSeverity(a: string, b?: string): string {
  if (!b) return a;
  return (SEVERITY_ORDER[b.toUpperCase()] ?? 0) > (SEVERITY_ORDER[a.toUpperCase()] ?? 0)
    ? b.toUpperCase()
    : a;
}

/**
 * Build GeoJSON H3 hex cells from spatial entities. For each storm point, also seeds
 * the immediate 1-ring (7 hexes total) so active swaths show contiguous neighborhood cells.
 */
export function buildH3TerritoryGrid(
  points: HexPointInput[],
  resolution = DEFAULT_H3_RESOLUTION
): {
  type: 'FeatureCollection';
  resolution: number;
  features: HexFeature[];
  summary: { totalHexes: number; activeStormHexes: number; totalLeads: number; workedJobs: number };
} {
  const buckets = new Map<
    string,
    {
      stormCount: number;
      leadCount: number;
      jobCount: number;
      spaceCount: number;
      maxScore: number;
      severity: string;
      labels: Set<string>;
    }
  >();

  const ensureBucket = (h3Index: string) => {
    let b = buckets.get(h3Index);
    if (!b) {
      b = {
        stormCount: 0,
        leadCount: 0,
        jobCount: 0,
        spaceCount: 0,
        maxScore: 0,
        severity: 'MINOR',
        labels: new Set<string>(),
      };
      buckets.set(h3Index, b);
    }
    return b;
  };

  for (const pt of points) {
    if (!Number.isFinite(pt.lat) || !Number.isFinite(pt.lng)) continue;
    const cell = latLngToCell(pt.lat, pt.lng, resolution);

    if (pt.kind === 'storm') {
      const ring = gridDisk(cell, 1);
      for (const hex of ring) {
        const b = ensureBucket(hex);
        b.stormCount += 1;
        b.severity = higherSeverity(b.severity, pt.severity);
        b.maxScore = Math.max(b.maxScore, pt.score ?? (hex === cell ? 85 : 65));
        if (pt.label && hex === cell) b.labels.add(pt.label);
      }
    } else {
      const b = ensureBucket(cell);
      if (pt.kind === 'lead') b.leadCount += 1;
      else if (pt.kind === 'job') b.jobCount += 1;
      else if (pt.kind === 'space') b.spaceCount += 1;
      if (typeof pt.score === 'number') b.maxScore = Math.max(b.maxScore, pt.score);
      if (pt.severity) b.severity = higherSeverity(b.severity, pt.severity);
      if (pt.label) b.labels.add(pt.label);
    }
  }

  let totalLeads = 0;
  let workedJobs = 0;
  let activeStormHexes = 0;
  const features: HexFeature[] = [];

  for (const [h3Index, b] of buckets.entries()) {
    const boundary = cellToBoundary(h3Index, true) as [number, number][];
    if (boundary.length > 0) {
      const first = boundary[0];
      const last = boundary[boundary.length - 1];
      if (first[0] !== last[0] || first[1] !== last[1]) boundary.push([first[0], first[1]]);
    }
    const [cLat, cLng] = cellToLatLng(h3Index);
    const saturationPct =
      b.leadCount > 0 ? Math.min(100, Math.round((b.jobCount / b.leadCount) * 100)) : b.jobCount > 0 ? 100 : 0;

    if (b.stormCount > 0) activeStormHexes += 1;
    totalLeads += b.leadCount;
    workedJobs += b.jobCount;

    features.push({
      type: 'Feature',
      geometry: { type: 'Polygon', coordinates: [boundary] },
      properties: {
        h3Index,
        resolution,
        center: { lat: Number(cLat.toFixed(5)), lng: Number(cLng.toFixed(5)) },
        stormCount: b.stormCount,
        leadCount: b.leadCount,
        jobCount: b.jobCount,
        spaceCount: b.spaceCount,
        maxScore: b.maxScore,
        severity: b.severity,
        saturationPct,
        labels: Array.from(b.labels).slice(0, 4),
      },
    });
  }

  return {
    type: 'FeatureCollection',
    resolution,
    features,
    summary: {
      totalHexes: features.length,
      activeStormHexes,
      totalLeads,
      workedJobs,
    },
  };
}
