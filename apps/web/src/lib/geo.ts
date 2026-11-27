export function createCircleRing(lng: number, lat: number, radiusMeters: number): [number, number][] {
  const pts = 64;
  const coords: [number, number][] = [];
  const dLat = radiusMeters / 111320;
  const dLng = radiusMeters / (111320 * Math.max(0.15, Math.cos((lat * Math.PI) / 180)));
  for (let i = 0; i <= pts; i++) {
    const t = (i / pts) * Math.PI * 2;
    coords.push([lng + dLng * Math.cos(t), lat + dLat * Math.sin(t)]);
  }
  return coords;
}

export function extractGeoCenter(geometry: any): { lat: number; lng: number } | null {
  if (!geometry) return null;
  if (geometry.type === 'Point' && Array.isArray(geometry.coordinates)) {
    const lng = Number(geometry.coordinates[0]);
    const lat = Number(geometry.coordinates[1]);
    return Number.isFinite(lat) && Number.isFinite(lng) ? { lat, lng } : null;
  }
  const ring =
    geometry.type === 'Polygon'
      ? geometry.coordinates?.[0]
      : geometry.type === 'MultiPolygon'
        ? geometry.coordinates?.[0]?.[0]
        : null;
  if (!Array.isArray(ring) || ring.length === 0) return null;
  const lat = ring.reduce((s: number, p: number[]) => s + Number(p[1] || 0), 0) / ring.length;
  const lng = ring.reduce((s: number, p: number[]) => s + Number(p[0] || 0), 0) / ring.length;
  return Number.isFinite(lat) && Number.isFinite(lng) ? { lat, lng } : null;
}
