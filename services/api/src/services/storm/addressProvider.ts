/**
 * Real street addresses inside a storm zone.
 *
 * Source: MapMap reverse geocoding (first-party index built from OpenStreetMap).
 * MapMap has no "all addresses in this polygon" endpoint, so we sample a grid of
 * points across the zone and reverse-geocode them in ONE batch request
 * (POST /geocode/batch, billed one Standard call per point), keep results of
 * type "address" that fall inside the zone, drop addresses that sit on a
 * business (a POI at the same spot), and de-duplicate.
 *
 * Coverage is only as good as OpenStreetMap's address data for the area, so the
 * result always says how many points were sampled and how many addresses came back.
 */
import { bboxOfGeometry, pointInGeometry } from './geo';
import type { GeoPoint } from './types';

export interface ZoneAddress {
  /** One-line address, e.g. "1420 West Hunt Street, McKinney, TX 75069". */
  address: string;
  /** Normalized key for de-duplication against existing leads. */
  key: string;
  lat: number;
  lng: number;
}

export interface AddressLookupResult {
  addresses: ZoneAddress[];
  sampledPoints: number;
  rawAddresses: number;
  droppedBusinesses: number;
}

export interface AddressProvider {
  readonly source: string;
  addressesInZone(zone: unknown, opts?: { maxPoints?: number }): Promise<AddressLookupResult>;
}

const KM_PER_DEG_LAT = 110.574;
const BUSINESS_MATCH_M = 8;

/** Lowercased, punctuation-free, common suffixes unified: "1420 W. Hunt St." === "1420 west hunt street". */
export function normalizeAddressKey(address: string): string {
  const words: Record<string, string> = {
    w: 'west', e: 'east', n: 'north', s: 'south',
    st: 'street', rd: 'road', dr: 'drive', ln: 'lane', ave: 'avenue', av: 'avenue',
    blvd: 'boulevard', ct: 'court', pkwy: 'parkway', cir: 'circle', pl: 'place', hwy: 'highway', trl: 'trail',
  };
  return address
    .toLowerCase()
    .split(',')[0]
    .replace(/[.#]/g, ' ')
    .split(/\s+/)
    .filter(Boolean)
    .map((w) => words[w] ?? w)
    .join(' ');
}

/** Grid points inside the zone, spaced so roughly `maxPoints` land inside it. */
export function samplePointsInZone(zone: unknown, maxPoints = 40): GeoPoint[] {
  const bbox = bboxOfGeometry(zone);
  if (!bbox) return [];
  const { west: minLng, south: minLat, east: maxLng, north: maxLat } = bbox;
  const midLat = (minLat + maxLat) / 2;
  const kmPerDegLng = KM_PER_DEG_LAT * Math.max(0.01, Math.cos((midLat * Math.PI) / 180));
  const widthKm = (maxLng - minLng) * kmPerDegLng;
  const heightKm = (maxLat - minLat) * KM_PER_DEG_LAT;
  const cap = Math.max(1, Math.min(200, Math.floor(maxPoints)));

  // Start from a spacing that fills the bbox with ~cap points, then shrink it until
  // enough land inside the (possibly irregular) zone, or stop after a few tries.
  let spacingKm = Math.max(0.05, Math.sqrt((widthKm * heightKm) / cap));
  let points: GeoPoint[] = [];
  for (let attempt = 0; attempt < 6; attempt += 1) {
    points = [];
    const stepLat = spacingKm / KM_PER_DEG_LAT;
    const stepLng = spacingKm / kmPerDegLng;
    for (let lat = minLat + stepLat / 2; lat < maxLat; lat += stepLat) {
      for (let lng = minLng + stepLng / 2; lng < maxLng; lng += stepLng) {
        const p = { lat: Number(lat.toFixed(6)), lng: Number(lng.toFixed(6)) };
        if (pointInGeometry(p, zone)) points.push(p);
      }
    }
    if (points.length >= cap * 0.6 || spacingKm <= 0.05) break;
    spacingKm *= 0.75;
  }
  if (points.length <= cap) return points;
  const stride = points.length / cap;
  return Array.from({ length: cap }, (_, i) => points[Math.floor(i * stride)]);
}

interface PhotonFeature {
  geometry?: { coordinates?: [number, number] };
  properties?: Record<string, unknown>;
}

const str = (v: unknown) => (typeof v === 'string' ? v.trim() : '');

function metersBetween(a: GeoPoint, b: GeoPoint): number {
  const dLat = (a.lat - b.lat) * KM_PER_DEG_LAT * 1000;
  const dLng = (a.lng - b.lng) * KM_PER_DEG_LAT * 1000 * Math.cos((a.lat * Math.PI) / 180);
  return Math.hypot(dLat, dLng);
}

/** One-line address from Photon-shaped properties, or null when there is no house number + street. */
export function formatAddress(p: Record<string, unknown>): string | null {
  const house = str(p.housenumber);
  const street = str(p.street);
  const name = str(p.name);
  let line = house && street ? `${house} ${street}` : /^\d+[a-z]?\s+\S/i.test(name) ? name : '';
  if (!line) return null;
  const city = str(p.city) || str(p.locality) || str(p.district);
  const state = str(p.state);
  const postcode = str(p.postcode);
  const tail = [city, [state, postcode].filter(Boolean).join(' ')].filter(Boolean).join(', ');
  if (tail) line += `, ${tail}`;
  return line;
}

/**
 * Turn reverse-geocode feature lists into in-zone residential addresses.
 * Addresses that coincide with a POI in the same answer are treated as businesses.
 */
export function extractZoneAddresses(
  featureLists: PhotonFeature[][],
  zone: unknown
): { addresses: ZoneAddress[]; rawAddresses: number; droppedBusinesses: number } {
  const seen = new Map<string, ZoneAddress>();
  let rawAddresses = 0;
  let droppedBusinesses = 0;
  for (const features of featureLists) {
    const pois: GeoPoint[] = [];
    for (const f of features) {
      const c = f.geometry?.coordinates;
      if (str(f.properties?.type) === 'poi' && Array.isArray(c)) pois.push({ lng: c[0], lat: c[1] });
    }
    for (const f of features) {
      const c = f.geometry?.coordinates;
      const props = f.properties ?? {};
      if (str(props.type) !== 'address' || !Array.isArray(c)) continue;
      const point = { lng: Number(c[0]), lat: Number(c[1]) };
      if (!Number.isFinite(point.lat) || !Number.isFinite(point.lng)) continue;
      const address = formatAddress(props);
      if (!address) continue;
      rawAddresses += 1;
      if (!pointInGeometry(point, zone)) continue;
      if (pois.some((poi) => metersBetween(poi, point) <= BUSINESS_MATCH_M)) {
        droppedBusinesses += 1;
        continue;
      }
      const key = normalizeAddressKey(address);
      if (!seen.has(key)) seen.set(key, { address, key, lat: point.lat, lng: point.lng });
    }
  }
  return { addresses: [...seen.values()], rawAddresses, droppedBusinesses };
}

type FetchLike = (url: string, init: { method: string; headers: Record<string, string>; body: string; signal?: AbortSignal }) => Promise<{
  ok: boolean;
  status: number;
  json(): Promise<unknown>;
  text(): Promise<string>;
}>;

export class MapMapAddressProvider implements AddressProvider {
  readonly source = 'MapMap reverse geocoding (OpenStreetMap address data)';

  constructor(
    private readonly apiKey: string,
    private readonly baseUrl = 'https://api.mapmap.ai',
    private readonly fetchImpl: FetchLike = fetch as unknown as FetchLike,
    private readonly timeoutMs = 20_000
  ) {}

  async addressesInZone(zone: unknown, opts: { maxPoints?: number } = {}): Promise<AddressLookupResult> {
    const points = samplePointsInZone(zone, opts.maxPoints ?? 40);
    if (points.length === 0) return { addresses: [], sampledPoints: 0, rawAddresses: 0, droppedBusinesses: 0 };

    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), this.timeoutMs);
    try {
      const res = await this.fetchImpl(`${this.baseUrl.replace(/\/+$/, '')}/geocode/batch`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${this.apiKey}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ queries: points.map((p) => ({ lat: p.lat, lon: p.lng, limit: 10 })) }),
        signal: controller.signal,
      });
      if (!res.ok) {
        const detail = (await res.text().catch(() => '')).slice(0, 200);
        throw new Error(`MapMap address lookup failed (HTTP ${res.status})${detail ? `: ${detail}` : ''}`);
      }
      const body = (await res.json()) as { results?: Array<{ features?: unknown }> };
      const lists: PhotonFeature[][] = (body.results ?? []).map((r) => {
        const f = r?.features as { features?: unknown } | unknown[] | undefined;
        if (Array.isArray(f)) return f as PhotonFeature[];
        return Array.isArray((f as { features?: unknown })?.features) ? ((f as { features: PhotonFeature[] }).features) : [];
      });
      return { ...extractZoneAddresses(lists, zone), sampledPoints: points.length };
    } finally {
      clearTimeout(timer);
    }
  }
}

/** The configured provider, or null when MAPMAP_API_KEY is not set. */
export function getAddressProvider(env: Record<string, string | undefined> = process.env): AddressProvider | null {
  const key = env.MAPMAP_API_KEY?.trim();
  if (!key) return null;
  return new MapMapAddressProvider(key, env.MAPMAP_API_URL?.trim() || 'https://api.mapmap.ai');
}
