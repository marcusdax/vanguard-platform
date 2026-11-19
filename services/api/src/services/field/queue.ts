/**
 * Field Hub — pure offer ranking / queue helpers (no DB, unit-testable).
 * Operators choose their own sort; the platform never dictates sequence.
 */
import { haversineKm } from '../storm/geo';

type GeoPoint = Parameters<typeof haversineKm>[0];

export type OfferSort = 'pay' | 'distance' | 'deadline';

export interface OfferInput {
  id: string;
  name: string;
  propertyAddress: string;
  latLng: GeoPoint | null;
  budget: number | null;
  endDate: Date | null;
  phase: string;
}

export interface Offer extends OfferInput {
  distanceKm: number | null;
  etaMin: number | null;
  payPerHour: number | null;
}

const AVG_KMH = 40;
const DEFAULT_JOB_HOURS = 1.5;

export function parseLatLng(value: unknown): GeoPoint | null {
  if (!value || typeof value !== 'object') return null;
  const v = value as Record<string, unknown>;
  const lat = Number(v.lat);
  const lng = Number(v.lng);
  if (!Number.isFinite(lat) || !Number.isFinite(lng)) return null;
  if (Math.abs(lat) > 90 || Math.abs(lng) > 180) return null;
  return { lat, lng } as GeoPoint;
}

export function buildOffer(input: OfferInput, origin: GeoPoint | null): Offer {
  const distanceKm = origin && input.latLng ? haversineKm(origin, input.latLng) : null;
  const etaMin = distanceKm == null ? null : Math.round((distanceKm / AVG_KMH) * 60);
  const hours = DEFAULT_JOB_HOURS + (etaMin ?? 0) / 60;
  const payPerHour = input.budget != null ? Math.round((input.budget / hours) * 100) / 100 : null;
  return { ...input, distanceKm: distanceKm == null ? null : Math.round(distanceKm * 10) / 10, etaMin, payPerHour };
}

export function rankOffers(offers: Offer[], sort: OfferSort, radiusKm?: number): Offer[] {
  const within = radiusKm != null ? offers.filter((o) => o.distanceKm == null || o.distanceKm <= radiusKm) : offers.slice();
  const nullLast = (a: number | null, b: number | null, dir: 1 | -1) =>
    a == null ? 1 : b == null ? -1 : (a - b) * dir;
  return within.sort((a, b) => {
    if (sort === 'distance') return nullLast(a.distanceKm, b.distanceKm, 1);
    if (sort === 'deadline') return nullLast(a.endDate?.getTime() ?? null, b.endDate?.getTime() ?? null, 1);
    return nullLast(a.payPerHour, b.payPerHour, -1);
  });
}

/** Apply an operator-chosen order; unknown ids are ignored, omitted ids keep relative order after. */
export function applyOrder<T extends { id: string }>(items: T[], orderedIds: string[]): T[] {
  const byId = new Map(items.map((i) => [i.id, i]));
  const picked = orderedIds.filter((id) => byId.has(id)).map((id) => byId.get(id)!);
  const seen = new Set(picked.map((p) => p.id));
  return [...picked, ...items.filter((i) => !seen.has(i.id))];
}

/** Flag queue items whose scheduled windows overlap (advisory only). */
export function windowConflicts(items: { id: string; start: Date | null; end: Date | null }[]): string[][] {
  const timed = items.filter((i) => i.start && i.end).sort((a, b) => a.start!.getTime() - b.start!.getTime());
  const out: string[][] = [];
  for (let i = 0; i < timed.length - 1; i++) {
    if (timed[i].end! > timed[i + 1].start!) out.push([timed[i].id, timed[i + 1].id]);
  }
  return out;
}

export interface RouteStopInput {
  id: string;
  latLng: GeoPoint | null;
}

export interface OptimizedRouteResult {
  orderedIds: string[];
  originalKm: number;
  optimizedKm: number;
  savedKm: number;
}

function routeLengthKm(stops: GeoPoint[], origin: GeoPoint | null): number {
  if (stops.length === 0) return 0;
  let total = origin ? haversineKm(origin, stops[0]) : 0;
  for (let i = 0; i < stops.length - 1; i++) {
    total += haversineKm(stops[i], stops[i + 1]);
  }
  return total;
}

/**
 * Nearest-neighbor + 2-opt TSP route optimizer for Field Queue stops (VROOM-inspired).
 * Preserves any stops missing coordinates cleanly at the end of the route.
 */
export function optimizeQueueRoute(
  items: RouteStopInput[],
  origin: GeoPoint | null
): OptimizedRouteResult {
  const geoStops = items.filter((i): i is { id: string; latLng: GeoPoint } => i.latLng != null);
  const nonGeo = items.filter((i) => i.latLng == null).map((i) => i.id);

  if (geoStops.length <= 1) {
    return {
      orderedIds: items.map((i) => i.id),
      originalKm: 0,
      optimizedKm: 0,
      savedKm: 0,
    };
  }

  const originalKm = Math.round(routeLengthKm(geoStops.map((s) => s.latLng), origin) * 10) / 10;

  // Greedy nearest-neighbor from origin (or first stop)
  const remaining = geoStops.slice();
  const route: typeof geoStops = [];
  let cursor: GeoPoint | null = origin;

  while (remaining.length > 0) {
    let bestIdx = 0;
    if (cursor) {
      let bestDist = Infinity;
      for (let i = 0; i < remaining.length; i++) {
        const d = haversineKm(cursor, remaining[i].latLng);
        if (d < bestDist) {
          bestDist = d;
          bestIdx = i;
        }
      }
    }
    const [picked] = remaining.splice(bestIdx, 1);
    route.push(picked);
    cursor = picked.latLng;
  }

  // 2-opt local refinement
  let improved = true;
  let pass = 0;
  while (improved && pass < 20) {
    improved = false;
    pass++;
    const currentLen = routeLengthKm(route.map((s) => s.latLng), origin);
    for (let i = 0; i < route.length - 1; i++) {
      for (let j = i + 1; j < route.length; j++) {
        const candidate = [
          ...route.slice(0, i),
          ...route.slice(i, j + 1).reverse(),
          ...route.slice(j + 1),
        ];
        const candLen = routeLengthKm(candidate.map((s) => s.latLng), origin);
        if (candLen + 0.05 < currentLen) {
          route.splice(0, route.length, ...candidate);
          improved = true;
        }
      }
    }
  }

  const optimizedKm = Math.round(routeLengthKm(route.map((s) => s.latLng), origin) * 10) / 10;
  const savedKm = Math.max(0, Math.round((originalKm - optimizedKm) * 10) / 10);

  return {
    orderedIds: [...route.map((s) => s.id), ...nonGeo],
    originalKm,
    optimizedKm,
    savedKm,
  };
}
