/**
 * Unit tests for services/storm/impact.ts — deterministic impact scoring.
 * Run: cd backend && npx tsx --test src/services/storm/__tests__/impact.test.ts
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  buildRecommendations,
  computeImpact,
  evaluateEventImpact,
  extractLatLng,
  IMPACT_RADIUS_KM,
  isPointInEventImpact,
  rankEventImpacts,
  SEVERITY_WEIGHTS,
  severityWeight,
  type ImpactEvent,
} from '../impact';

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

function event(overrides: Partial<ImpactEvent> & { id: string }): ImpactEvent {
  return {
    name: 'Severe Thunderstorm — Collin County, TX',
    severity: 'SEVERE',
    geometry: square(-96, 33, 0.05),
    ...overrides,
  };
}

test('extractLatLng: supports {lat,lng}, {lat,lon}, GeoJSON Point, invalid input', () => {
  assert.deepEqual(extractLatLng({ lat: 33.1, lng: -96.6 }), { lat: 33.1, lng: -96.6 });
  assert.deepEqual(extractLatLng({ lat: 33.1, lon: -96.6 }), { lat: 33.1, lng: -96.6 });
  assert.deepEqual(extractLatLng({ type: 'Point', coordinates: [-96.6, 33.1] }), {
    lat: 33.1,
    lng: -96.6,
  });
  assert.deepEqual(extractLatLng({ lat: 33.1, longitude: -96.6 }), { lat: 33.1, lng: -96.6 });
  assert.deepEqual(extractLatLng([33.1, -96.6]), { lat: 33.1, lng: -96.6 });
  assert.equal(extractLatLng(null), null);
  assert.equal(extractLatLng({ lat: 200, lng: 0 }), null);
  assert.equal(extractLatLng({ lat: 'x', lng: 0 }), null);
  assert.equal(extractLatLng('{bad json'), null);
});

test('severityWeight: contract weights', () => {
  assert.equal(severityWeight('EXTREME'), 1);
  assert.equal(severityWeight('SEVERE'), 0.8);
  assert.equal(severityWeight('MODERATE'), 0.6);
  assert.equal(severityWeight('MINOR'), 0.4);
  assert.equal(severityWeight(undefined), 0.4);
  assert.deepEqual(SEVERITY_WEIGHTS, { EXTREME: 1, SEVERE: 0.8, MODERATE: 0.6, MINOR: 0.4 });
});

test('containment scores are 100 * severity weight (capped at 100)', () => {
  const point = { lat: 33, lng: -96 };
  assert.equal(evaluateEventImpact(point, event({ id: 'e', severity: 'EXTREME' }))?.score, 100);
  assert.equal(evaluateEventImpact(point, event({ id: 'e', severity: 'SEVERE' }))?.score, 80);
  assert.equal(evaluateEventImpact(point, event({ id: 'e', severity: 'MODERATE' }))?.score, 60);
  assert.equal(evaluateEventImpact(point, event({ id: 'e', severity: 'MINOR' }))?.score, 40);

  const contained = evaluateEventImpact(point, event({ id: 'e', severity: 'EXTREME' }))!;
  assert.equal(contained.contained, true);
  assert.equal(contained.distanceKm, 0);
  assert.equal(contained.rawScore, 100);
  assert.ok(contained.score <= 100);
});

test('distance decay: max(0, 100 - distanceKm * 2.2) weighted by severity', () => {
  const point = { lat: 33.06, lng: -96 }; // 0.01° north of the 33.05 north edge (~1.1 km)
  // Square is centred (33,-96) with half 0.05 → north edge at 33.05, so point is ~1.1 km out.
  const severe = evaluateEventImpact(point, event({ id: 'severe', severity: 'SEVERE' }))!;
  assert.equal(severe.contained, false);
  assert.ok(severe.distanceKm > 0.5 && severe.distanceKm < 1.6);
  const expected = Math.round(Math.max(0, 100 - severe.distanceKm * 2.2) * 0.8);
  assert.equal(severe.score, expected);

  const farther = { lat: 33.2, lng: -96 };
  const farImpact = evaluateEventImpact(farther, event({ id: 'severe', severity: 'SEVERE' }))!;
  assert.ok(farImpact.score < severe.score, 'farther point must score lower');
});

test('rankEventImpacts: best severity-weighted event wins; ties deterministic', () => {
  const point = { lat: 33, lng: -96 };
  const events: ImpactEvent[] = [
    event({ id: 'minor-contained', severity: 'MINOR' }),
    event({
      id: 'severe-near',
      severity: 'SEVERE',
      geometry: square(-96, 33.2, 0.05), // ~22 km north
    }),
  ];
  // MINOR containment = 40; SEVERE ~17 km out ≈ (100 - 17*2.2)*0.8 ≈ 50.
  const ranked = rankEventImpacts(point, events);
  assert.equal(ranked[0].event.id, 'severe-near');
  assert.ok(ranked[0].score > 40);

  const ordered = rankEventImpacts(point, [...events].reverse());
  assert.deepEqual(
    ordered.map((impact) => impact.event.id),
    ranked.map((impact) => impact.event.id)
  );
});

test('computeImpact: severity, drivers, nearbyEvents sorted by distance', () => {
  const point = { lat: 33, lng: -96 };
  const computation = computeImpact(point, [
    event({ id: 'a', name: 'Contained Storm', severity: 'MINOR' }),
    event({ id: 'b', name: 'Near Storm', severity: 'SEVERE', geometry: square(-96, 33.2, 0.05) }),
    event({ id: 'c', name: 'Very Far Storm', severity: 'EXTREME', geometry: square(-96, 35, 0.05) }),
  ]);

  assert.ok(computation.result.score > 0);
  assert.equal(computation.result.severity, 'SEVERE');
  assert.equal(computation.bestEventId, 'b');
  assert.ok(computation.result.drivers.length >= 1);
  assert.ok(computation.result.drivers[0].label.includes('Near Storm'));
  assert.ok(computation.result.drivers[0].weight > 0);

  const distances = computation.result.nearbyEvents.map((nearby) => nearby.distanceKm);
  const sorted = [...distances].sort((a, b) => a - b);
  assert.deepEqual(distances, sorted);
  assert.ok(computation.result.nearbyEvents.every((nearby) => nearby.distanceKm <= 50));
  assert.equal(computation.result.nearbyEvents.length, 2, 'the 222 km event is excluded');
  assert.ok(computation.result.recommendations.length >= 1);
});

test('computeImpact: best event is capped at 100 and keeps event id', () => {
  const computation = computeImpact({ lat: 33, lng: -96 }, [
    event({ id: 'extreme', severity: 'EXTREME' }),
  ]);
  assert.equal(computation.result.score, 100);
  assert.equal(computation.result.severity, 'EXTREME');
  assert.equal(computation.bestEventId, 'extreme');
});

test('computeImpact: no events → NONE with fallback recommendation', () => {
  const computation = computeImpact({ lat: 33, lng: -96 }, []);
  assert.equal(computation.result.score, 0);
  assert.equal(computation.result.severity, 'NONE');
  assert.deepEqual(computation.result.drivers, []);
  assert.deepEqual(computation.result.nearbyEvents, []);
  assert.deepEqual(computation.result.recommendations, [
    'No active storm impact detected for this location.',
  ]);
  assert.equal(computation.bestEventId, undefined);
});

test('computeImpact: event without geometry uses centroid fallback', () => {
  const withCentroid = event({
    id: 'centroid-only',
    severity: 'SEVERE',
    geometry: undefined,
    centroid: { lat: 33, lng: -96 },
  });
  const computation = computeImpact({ lat: 33, lng: -96 }, [withCentroid]);
  assert.equal(computation.result.score, 80);
  assert.equal(computation.bestEventId, 'centroid-only');

  const noFootprint = event({ id: 'nowhere', geometry: undefined, centroid: null });
  assert.equal(computeImpact({ lat: 33, lng: -96 }, [noFootprint]).result.score, 0);
});

test('isPointInEventImpact: containment or <= 25 km', () => {
  const eventAtMcKinney = event({
    id: 'mckinney',
    geometry: square(-96.64, 33.19, 0.05),
  });
  assert.equal(isPointInEventImpact({ lat: 33.19, lng: -96.64 }, eventAtMcKinney), true);
  // ~11 km north.
  assert.equal(isPointInEventImpact({ lat: 33.29, lng: -96.64 }, eventAtMcKinney), true);
  // ~55 km north.
  assert.equal(isPointInEventImpact({ lat: 33.69, lng: -96.64 }, eventAtMcKinney), false);
  assert.ok(IMPACT_RADIUS_KM === 25);
});

test('buildRecommendations: deterministic, unique, severity-aware', () => {
  const extreme = buildRecommendations('EXTREME', 95, 2);
  assert.ok(extreme.includes('Prioritize canvassing and roof inspections within 48 hours.'));
  assert.ok(extreme.some((text) => text.includes('safety plans')));
  assert.equal(new Set(extreme).size, extreme.length);

  const medium = buildRecommendations('MODERATE', 55, 1);
  assert.ok(medium.length >= 1 && medium.length <= 5);

  const low = buildRecommendations('MINOR', 12, 0);
  assert.ok(low.length >= 1 && low.length <= 5);

  assert.deepEqual(buildRecommendations('NONE', 0, 0), [
    'No active storm impact detected for this location.',
  ]);
});
