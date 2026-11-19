/**
 * Dependency-free GeoJSON helpers for the RoofPro storm-intelligence engine.
 *
 * Owner: backend-storm. This module is intentionally pure (no Prisma, no HTTP,
 * no new npm dependencies) so it can be unit-tested with `node:test`.
 *
 * Conventions
 *  - Coordinates follow GeoJSON: [longitude, latitude].
 *  - Public point arguments use { lat, lng } (see ./types).
 *  - Polygons are normalised to GeoJSON Polygon / MultiPolygon only. Point,
 *    LineString, invalid rings, and malformed JSON are dropped (null).
 */
import type { BBox, GeoPoint } from './types';

export type Position = [number, number];

export interface PolygonGeometry {
  type: 'Polygon';
  coordinates: Position[][];
}

export interface MultiPolygonGeometry {
  type: 'MultiPolygon';
  coordinates: Position[][][];
}

export type NormalizedGeometry = PolygonGeometry | MultiPolygonGeometry;

/** Mean Earth radius in kilometres (IUGG). */
const EARTH_RADIUS_KM = 6371.0088;
/** Kilometres per degree of latitude (mean). */
const KM_PER_DEGREE_LAT = 111.32;

const toRad = (deg: number): number => (deg * Math.PI) / 180;
const round6 = (n: number): number => Math.round(n * 1e6) / 1e6;

function samePosition(a: Position, b: Position): boolean {
  return a[0] === b[0] && a[1] === b[1];
}

function coercePosition(value: unknown): Position | null {
  if (!Array.isArray(value) || value.length < 2) return null;
  const lng = Number(value[0]);
  const lat = Number(value[1]);
  if (!Number.isFinite(lng) || !Number.isFinite(lat)) return null;
  if (lng < -180 || lng > 180 || lat < -90 || lat > 90) return null;
  return [lng, lat];
}

function coerceRing(value: unknown): Position[] | null {
  if (!Array.isArray(value)) return null;
  const ring: Position[] = [];
  for (const item of value) {
    const pos = coercePosition(item);
    if (!pos) continue;
    const prev = ring[ring.length - 1];
    if (prev && samePosition(prev, pos)) continue; // drop consecutive duplicates
    ring.push(pos);
  }
  if (ring.length < 3) return null;
  if (!samePosition(ring[0], ring[ring.length - 1])) {
    ring.push([ring[0][0], ring[0][1]]); // GeoJSON linear rings must be closed
  }
  return ring.length >= 4 ? ring : null;
}

function coercePolygonCoordinates(value: unknown): Position[][] | null {
  if (!Array.isArray(value) || value.length === 0) return null;
  // A bare linear ring: [[lng, lat], ...]
  if (coercePosition(value[0])) {
    const ring = coerceRing(value);
    return ring ? [ring] : null;
  }
  const rings: Position[][] = [];
  for (const candidate of value) {
    const ring = coerceRing(candidate);
    if (ring) rings.push(ring);
  }
  return rings.length > 0 ? rings : null;
}

/**
 * Normalise any GeoJSON-ish input (object, JSON string, Feature,
 * GeometryCollection, Polygon, MultiPolygon) into a Polygon/MultiPolygon.
 * Returns null when no usable polygonal geometry can be recovered.
 */
export function normalizeGeometry(input: unknown): NormalizedGeometry | null {
  let value = input;
  if (typeof value === 'string') {
    try {
      value = JSON.parse(value);
    } catch {
      return null;
    }
  }
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null;
  const obj = value as Record<string, unknown>;
  const type = obj.type;

  if (type === 'Feature') return normalizeGeometry(obj.geometry);
  if (type === 'GeometryCollection') {
    const geometries = Array.isArray(obj.geometries) ? obj.geometries : [];
    return mergeGeometries(geometries);
  }
  if (type === 'Polygon') {
    const rings = coercePolygonCoordinates(obj.coordinates);
    return rings ? { type: 'Polygon', coordinates: rings } : null;
  }
  if (type === 'MultiPolygon') {
    if (!Array.isArray(obj.coordinates)) return null;
    const polygons: Position[][][] = [];
    for (const polygon of obj.coordinates) {
      const rings = coercePolygonCoordinates(polygon);
      if (rings) polygons.push(rings);
    }
    return polygons.length > 0 ? { type: 'MultiPolygon', coordinates: polygons } : null;
  }
  return null;
}

/** Merge any number of polygonal geometries into a single MultiPolygon. */
export function mergeGeometries(geometries: unknown[]): MultiPolygonGeometry | null {
  const polygons: Position[][][] = [];
  for (const geometry of geometries) {
    const normalized = normalizeGeometry(geometry);
    if (!normalized) continue;
    if (normalized.type === 'Polygon') polygons.push(normalized.coordinates);
    else polygons.push(...normalized.coordinates);
  }
  if (polygons.length === 0) return null;
  return { type: 'MultiPolygon', coordinates: polygons };
}

/** Every polygon (outer ring + holes) in a normalised geometry. */
export function geometryPolygons(geometry: unknown): Position[][][] {
  const normalized = normalizeGeometry(geometry);
  if (!normalized) return [];
  return normalized.type === 'Polygon' ? [normalized.coordinates] : normalized.coordinates;
}

/** Ray-casting containment test for a single closed ring. */
export function pointInRing(point: GeoPoint, ring: Position[]): boolean {
  if (!ring || ring.length < 3) return false;
  const { lat, lng } = point;
  let inside = false;
  for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
    const xi = ring[i][0];
    const yi = ring[i][1];
    const xj = ring[j][0];
    const yj = ring[j][1];
    const crosses = yi > lat !== yj > lat;
    if (crosses && lng < ((xj - xi) * (lat - yi)) / (yj - yi) + xi) {
      inside = !inside;
    }
  }
  return inside;
}

/** Polygon containment incl. hole exclusion. */
export function pointInPolygonRings(point: GeoPoint, rings: Position[][]): boolean {
  if (!Array.isArray(rings) || rings.length === 0) return false;
  if (!pointInRing(point, rings[0])) return false;
  for (let i = 1; i < rings.length; i++) {
    if (pointInRing(point, rings[i])) return false;
  }
  return true;
}

/** Point-in-Polygon / point-in-MultiPolygon test. */
export function pointInGeometry(point: GeoPoint, geometry: unknown): boolean {
  const normalized = normalizeGeometry(geometry);
  if (!normalized) return false;
  const polygons = normalized.type === 'Polygon' ? [normalized.coordinates] : normalized.coordinates;
  for (const rings of polygons) {
    if (pointInPolygonRings(point, rings)) return true;
  }
  return false;
}

/** Bounding box of a set of positions. */
export function bboxOfPositions(positions: Position[]): BBox | null {
  if (!positions || positions.length === 0) return null;
  let north = -90;
  let south = 90;
  let east = -180;
  let west = 180;
  for (const pos of positions) {
    const lng = pos[0];
    const lat = pos[1];
    if (lat > north) north = lat;
    if (lat < south) south = lat;
    if (lng > east) east = lng;
    if (lng < west) west = lng;
  }
  return { north, south, east, west };
}

/** Bounding box of any normalised polygonal geometry. */
export function bboxOfGeometry(geometry: unknown): BBox | null {
  const positions: Position[] = [];
  for (const rings of geometryPolygons(geometry)) {
    for (const ring of rings) {
      for (const pos of ring) positions.push(pos);
    }
  }
  return bboxOfPositions(positions);
}

/** Bounding box spanning every usable feature geometry in a FeatureCollection. */
export function bboxFromFeatureCollection(collection: unknown): BBox | null {
  const obj = collection as { features?: unknown } | null;
  if (!obj || !Array.isArray(obj.features)) return null;
  const positions: Position[] = [];
  for (const feature of obj.features as Array<{ geometry?: unknown }>) {
    for (const rings of geometryPolygons(feature?.geometry)) {
      for (const ring of rings) {
        for (const pos of ring) positions.push(pos);
      }
    }
  }
  return bboxOfPositions(positions);
}

export function bboxContainsPoint(bbox: BBox, point: GeoPoint): boolean {
  return (
    point.lat <= bbox.north &&
    point.lat >= bbox.south &&
    point.lng <= bbox.east &&
    point.lng >= bbox.west
  );
}

export function bboxIntersects(a: BBox, b: BBox): boolean {
  return !(a.north < b.south || a.south > b.north || a.east < b.west || a.west > b.east);
}

export function bboxCenter(bbox: BBox): GeoPoint {
  return {
    lat: (bbox.north + bbox.south) / 2,
    lng: (bbox.east + bbox.west) / 2,
  };
}

/**
 * Approximate centroid used by the clustering algorithm. Deterministic: the
 * centre of the geometry bounding box (stable for multi-county footprints).
 */
export function centroid(geometry: unknown): GeoPoint | null {
  const bbox = bboxOfGeometry(geometry);
  return bbox ? bboxCenter(bbox) : null;
}

/** Great-circle distance in kilometres. */
export function haversineKm(a: GeoPoint, b: GeoPoint): number {
  const dLat = toRad(b.lat - a.lat);
  const dLng = toRad(b.lng - a.lng);
  const lat1 = toRad(a.lat);
  const lat2 = toRad(b.lat);
  const sinLat = Math.sin(dLat / 2);
  const sinLng = Math.sin(dLng / 2);
  const h = sinLat * sinLat + Math.cos(lat1) * Math.cos(lat2) * sinLng * sinLng;
  return 2 * EARTH_RADIUS_KM * Math.asin(Math.min(1, Math.sqrt(h)));
}

/**
 * Circular GeoJSON Polygon approximation (used for seeded demo storm footprints
 * and for synthetic buffers when only a centre + radius are known).
 */
export function approximateBufferPolygon(
  center: GeoPoint,
  radiusKm: number,
  steps = 64
): PolygonGeometry {
  const count = Math.max(8, Math.floor(steps));
  const radius = Math.max(0.01, Number(radiusKm) || 0);
  const latDelta = radius / KM_PER_DEGREE_LAT;
  const lngScale = Math.max(0.01, Math.cos(toRad(center.lat)));
  const lngDelta = radius / (KM_PER_DEGREE_LAT * lngScale);
  const ring: Position[] = [];
  for (let i = 0; i < count; i++) {
    const angle = (2 * Math.PI * i) / count;
    const lat = center.lat + Math.sin(angle) * latDelta;
    const lng = center.lng + Math.cos(angle) * lngDelta;
    ring.push([round6(lng), round6(lat)]);
  }
  ring.push([ring[0][0], ring[0][1]]);
  return { type: 'Polygon', coordinates: [ring] };
}

function ringAreaKm2(ring: Position[]): number {
  if (!ring || ring.length < 3) return 0;
  let meanLat = 0;
  for (const pos of ring) meanLat += pos[1];
  meanLat /= ring.length;
  const cosLat = Math.max(0.01, Math.cos(toRad(meanLat)));
  let sum = 0;
  for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
    const xi = ring[i][0] * cosLat * KM_PER_DEGREE_LAT;
    const yi = ring[i][1] * KM_PER_DEGREE_LAT;
    const xj = ring[j][0] * cosLat * KM_PER_DEGREE_LAT;
    const yj = ring[j][1] * KM_PER_DEGREE_LAT;
    sum += xj * yi - xi * yj;
  }
  return Math.abs(sum) / 2;
}

/** Approximate surface area in square kilometres (outer rings minus holes). */
export function polygonAreaKm2(geometry: unknown): number {
  const normalized = normalizeGeometry(geometry);
  if (!normalized) return 0;
  const polygons = normalized.type === 'Polygon' ? [normalized.coordinates] : normalized.coordinates;
  let total = 0;
  for (const rings of polygons) {
    let area = ringAreaKm2(rings[0]);
    for (let i = 1; i < rings.length; i++) area -= ringAreaKm2(rings[i]);
    if (area > 0) total += area;
  }
  return total;
}

function projectRelativeToPoint(point: GeoPoint, position: Position): [number, number] {
  const cosLat = Math.max(0.01, Math.cos(toRad(point.lat)));
  return [
    (position[0] - point.lng) * cosLat * KM_PER_DEGREE_LAT,
    (position[1] - point.lat) * KM_PER_DEGREE_LAT,
  ];
}

function distanceToSegmentKm(px: number, py: number, ax: number, ay: number, bx: number, by: number): number {
  const dx = bx - ax;
  const dy = by - ay;
  const lengthSq = dx * dx + dy * dy;
  let t = lengthSq === 0 ? 0 : ((px - ax) * dx + (py - ay) * dy) / lengthSq;
  t = Math.max(0, Math.min(1, t));
  const cx = ax + t * dx;
  const cy = ay + t * dy;
  return Math.hypot(px - cx, py - cy);
}

/**
 * Distance in km from a point to the nearest polygon edge (or 0 when inside).
 * Returns Infinity when the geometry is unusable.
 */
export function geometryDistanceKmToPoint(geometry: unknown, point: GeoPoint): number {
  const normalized = normalizeGeometry(geometry);
  if (!normalized) return Infinity;
  if (pointInGeometry(point, normalized)) return 0;
  const polygons = normalized.type === 'Polygon' ? [normalized.coordinates] : normalized.coordinates;
  let best = Infinity;
  for (const rings of polygons) {
    for (const ring of rings) {
      for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
        const [ax, ay] = projectRelativeToPoint(point, ring[j]);
        const [bx, by] = projectRelativeToPoint(point, ring[i]);
        const distance = distanceToSegmentKm(0, 0, ax, ay, bx, by);
        if (distance < best) best = distance;
      }
    }
  }
  return best;
}

/**
 * Distance to an event footprint. Uses polygon edges when geometry exists and
 * falls back to the centroid when only a centre is known. Infinity when neither
 * is available.
 */
export function eventDistanceKmToPoint(
  point: GeoPoint,
  event: { geometry?: unknown; centroid?: GeoPoint | null }
): number {
  if (event.geometry) {
    const distance = geometryDistanceKmToPoint(event.geometry, point);
    if (Number.isFinite(distance)) return distance;
  }
  if (event.centroid && Number.isFinite(event.centroid.lat) && Number.isFinite(event.centroid.lng)) {
    return haversineKm(point, event.centroid);
  }
  return Infinity;
}
