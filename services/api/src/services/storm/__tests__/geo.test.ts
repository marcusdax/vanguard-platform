/**
 * Unit tests for services/storm/geo.ts — pure GeoJSON helpers.
 * Run: cd backend && npx tsx --test src/services/storm/__tests__/geo.test.ts
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  approximateBufferPolygon,
  bboxFromFeatureCollection,
  bboxIntersects,
  bboxOfGeometry,
  centroid,
  eventDistanceKmToPoint,
  geometryDistanceKmToPoint,
  haversineKm,
  mergeGeometries,
  normalizeGeometry,
  pointInGeometry,
  pointInPolygonRings,
  polygonAreaKm2,
} from '../geo';

const MCKINNEY = { lat: 33.1972, lng: -96.6397 };
const DALLAS = { lat: 32.7767, lng: -96.797 };

function square(centerLng: number, centerLat: number, halfDeg: number) {
  const ring: Array<[number, number]> = [
    [centerLng - halfDeg, centerLat - halfDeg],
    [centerLng + halfDeg, centerLat - halfDeg],
    [centerLng + halfDeg, centerLat + halfDeg],
    [centerLng - halfDeg, centerLat + halfDeg],
    [centerLng - halfDeg, centerLat - halfDeg],
  ];
  return { type: 'Polygon', coordinates: [ring] };
}

test('haversineKm: known DFW distance + symmetry + same point', () => {
  const distance = haversineKm(MCKINNEY, DALLAS);
  assert.ok(distance > 46 && distance < 52, `expected ~49 km, got ${distance}`);
  assert.ok(Math.abs(distance - haversineKm(DALLAS, MCKINNEY)) < 1e-9);
  assert.equal(haversineKm(MCKINNEY, MCKINNEY), 0);
});

test('pointInGeometry: Polygon containment and exclusion', () => {
  const polygon = square(-96, 33, 0.1);
  assert.equal(pointInGeometry({ lat: 33, lng: -96 }, polygon), true);
  assert.equal(pointInGeometry({ lat: 33.05, lng: -96.05 }, polygon), true);
  assert.equal(pointInGeometry({ lat: 33.2, lng: -96 }, polygon), false);
  assert.equal(pointInGeometry({ lat: 33, lng: -96.2 }, polygon), false);
});

test('pointInGeometry: MultiPolygon + hole exclusion', () => {
  const outer = square(-96, 33, 0.1).coordinates[0];
  const hole = square(-96, 33, 0.02).coordinates[0];
  const donut = { type: 'Polygon', coordinates: [outer, hole] };
  assert.equal(pointInPolygonRings({ lat: 33, lng: -96 }, [outer, hole]), false);
  assert.equal(pointInPolygonRings({ lat: 33.05, lng: -96 }, [outer, hole]), true);
  assert.equal(pointInGeometry({ lat: 33, lng: -96 }, donut), false);

  const multi = {
    type: 'MultiPolygon',
    coordinates: [square(-96, 33, 0.05).coordinates, square(-95, 34, 0.05).coordinates],
  };
  assert.equal(pointInGeometry({ lat: 34, lng: -95 }, multi), true);
  assert.equal(pointInGeometry({ lat: 33.5, lng: -95.5 }, multi), false);
});

test('normalizeGeometry: Feature, unclosed rings, JSON, invalids', () => {
  const polygon = square(-96, 33, 0.1);
  const fromFeature = normalizeGeometry({ type: 'Feature', properties: {}, geometry: polygon });
  assert.equal(fromFeature?.type, 'Polygon');

  const unclosed = {
    type: 'Polygon',
    coordinates: [
      [
        [-96.1, 32.9],
        [-95.9, 32.9],
        [-95.9, 33.1],
        [-96.1, 33.1],
      ],
    ],
  };
  const normalized = normalizeGeometry(unclosed);
  assert.equal(normalized?.type, 'Polygon');
  const ring = (normalized as { coordinates: number[][][] }).coordinates[0];
  assert.deepEqual(ring[0], ring[ring.length - 1]);

  const fromJson = normalizeGeometry(JSON.stringify(polygon));
  assert.equal(fromJson?.type, 'Polygon');

  assert.equal(normalizeGeometry(null), null);
  assert.equal(normalizeGeometry('not json'), null);
  assert.equal(normalizeGeometry({ type: 'Point', coordinates: [-96, 33] }), null);
  assert.equal(
    normalizeGeometry({ type: 'Polygon', coordinates: [[[-500, 33], [-96, 33], [-96, 34]]] }),
    null
  );
});

test('mergeGeometries: two polygons become one MultiPolygon', () => {
  const merged = mergeGeometries([square(-96, 33, 0.05), square(-95, 34, 0.05)]);
  assert.equal(merged?.type, 'MultiPolygon');
  assert.equal(merged?.coordinates.length, 2);
  assert.equal(pointInGeometry({ lat: 34, lng: -95 }, merged), true);
  assert.equal(mergeGeometries([]), null);
  assert.equal(mergeGeometries([null, { type: 'Point', coordinates: [0, 0] }]), null);
});

test('bboxOfGeometry + bboxFromFeatureCollection', () => {
  const first = square(-96.1, 33.1, 0.1); // north 33.2, south 33.0, east -96.0, west -96.2
  const second = square(-95.5, 33.5, 0.1); // north 33.6, south 33.4, east -95.4, west -95.6
  const bbox = bboxFromFeatureCollection({
    type: 'FeatureCollection',
    features: [
      { type: 'Feature', geometry: first, properties: {} },
      { type: 'Feature', geometry: second, properties: {} },
      { type: 'Feature', geometry: null, properties: {} },
    ],
  });
  assert.ok(bbox);
  const approx = (actual: number, expected: number) => Math.abs(actual - expected) < 1e-9;
  assert.ok(approx(bbox!.north, 33.6), `north ${bbox!.north}`);
  assert.ok(approx(bbox!.south, 33.0), `south ${bbox!.south}`);
  assert.ok(approx(bbox!.east, -95.4), `east ${bbox!.east}`);
  assert.ok(approx(bbox!.west, -96.2), `west ${bbox!.west}`);
  assert.equal(bboxFromFeatureCollection({ type: 'FeatureCollection' }), null);

  const single = bboxOfGeometry(first);
  assert.ok(approx(single!.north, 33.2), `single north ${single!.north}`);
  assert.equal(bboxIntersects(single!, bbox!), true);
  assert.equal(bboxIntersects(single!, { north: 40, south: 39, east: -90, west: -91 }), false);
});

test('centroid: deterministic bbox centre', () => {
  const centre = centroid(square(-96, 33, 0.1));
  assert.ok(centre);
  assert.ok(Math.abs(centre!.lat - 33) < 1e-9);
  assert.ok(Math.abs(centre!.lng - -96) < 1e-9);
  assert.equal(centroid(null), null);
});

test('approximateBufferPolygon: closed ring, contains centre, radius respected', () => {
  const buffer = approximateBufferPolygon(MCKINNEY, 10, 48);
  assert.equal(buffer.type, 'Polygon');
  const ring = buffer.coordinates[0];
  assert.equal(ring.length, 49);
  assert.deepEqual(ring[0], ring[ring.length - 1]);
  assert.equal(pointInGeometry(MCKINNEY, buffer), true);

  const vertexDistance = haversineKm(MCKINNEY, { lat: ring[0][1], lng: ring[0][0] });
  assert.ok(
    Math.abs(vertexDistance - 10) < 0.5,
    `buffer vertex should be ~10 km, got ${vertexDistance}`
  );
  assert.equal(pointInGeometry({ lat: 33.1972 + 1, lng: -96.6397 }, buffer), false);
});

test('polygonAreaKm2: known extents', () => {
  // 0.04° x 0.04° box near McKinney.
  const small = polygonAreaKm2(square(-96.64, 33.19, 0.02));
  assert.ok(small > 15.5 && small < 18, `expected ~16.6 km², got ${small}`);

  // 1° x 1° box near the equator is ~12,392 km².
  const large = polygonAreaKm2(square(0, 0.5, 0.5));
  assert.ok(large > 12000 && large < 12600, `expected ~12392 km², got ${large}`);

  assert.equal(polygonAreaKm2(null), 0);
});

test('geometryDistanceKmToPoint: containment zero + edge distance', () => {
  const polygon = square(0, 0, 0.01); // 0.02° wide box around origin
  assert.equal(geometryDistanceKmToPoint(polygon, { lat: 0, lng: 0 }), 0);
  // 0.04° north of the north edge ~4.45 km.
  const distance = geometryDistanceKmToPoint(polygon, { lat: 0.05, lng: 0 });
  assert.ok(distance > 4 && distance < 5, `expected ~4.45 km, got ${distance}`);
  assert.equal(geometryDistanceKmToPoint({ type: 'Point', coordinates: [0, 0] }, { lat: 0, lng: 0 }), Infinity);

  const event = { geometry: polygon };
  assert.equal(eventDistanceKmToPoint({ lat: 0, lng: 0 }, event), 0);
  const centroidFallback = eventDistanceKmToPoint({ lat: 0, lng: 0 }, { centroid: { lat: 0, lng: 0.1 } });
  assert.ok(centroidFallback > 10 && centroidFallback < 12, `expected ~11.1 km, got ${centroidFallback}`);
});
