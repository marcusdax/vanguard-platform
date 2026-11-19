/**
 * Unit tests for services/storm/clustering.ts — NOAA mapping, deterministic
 * clustering, external-id derivation, and upsert idempotency semantics.
 * Run: cd backend && npx tsx --test src/services/storm/__tests__/clustering.test.ts
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  buildExternalId,
  clusterWeatherAlerts,
  mapEventType,
  mapSeverity,
  maxSeverity,
  parseAreaLabel,
  type WeatherAlertLike,
} from '../clustering';

const FIXED_NOW = Date.parse('2026-11-16T12:00:00Z');

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

function alert(overrides: Partial<WeatherAlertLike> & { id: string }): WeatherAlertLike {
  return {
    type: 'Severe Thunderstorm Warning',
    severity: 'Severe',
    effective: '2026-11-16T06:00:00Z',
    expires: '2026-11-16T12:00:00Z',
    headline: 'Severe Thunderstorm Warning',
    area: 'Collin, TX',
    geometry: square(-96.64, 33.19, 0.02),
    parameters: { hailSize: ['1.75'] },
    ...overrides,
  };
}

test('mapSeverity: contract mapping + unknown fallback', () => {
  assert.equal(mapSeverity('Extreme'), 'EXTREME');
  assert.equal(mapSeverity('Severe'), 'SEVERE');
  assert.equal(mapSeverity('Moderate'), 'MODERATE');
  assert.equal(mapSeverity('Minor'), 'MINOR');
  assert.equal(mapSeverity('Unknown'), 'MINOR');
  assert.equal(mapSeverity(undefined), 'MINOR');
});

test('mapEventType: contract event-type mapping', () => {
  assert.equal(mapEventType('Tornado Warning'), 'TORNADO');
  assert.equal(mapEventType('Hurricane Warning'), 'HURRICANE');
  assert.equal(mapEventType('Tropical Storm Warning'), 'HURRICANE');
  assert.equal(mapEventType('Severe Thunderstorm Warning'), 'SEVERE_THUNDERSTORM');
  assert.equal(mapEventType('High Wind Warning'), 'SEVERE_THUNDERSTORM');
  assert.equal(mapEventType('Flash Flood Warning'), 'FLASH_FLOOD');
  assert.equal(mapEventType('Areal Flood Advisory'), 'FLASH_FLOOD');
  assert.equal(mapEventType('Winter Storm Warning'), 'WINTER_STORM');
  assert.equal(mapEventType('Blizzard Warning'), 'WINTER_STORM');
  assert.equal(mapEventType('Red Flag Warning'), 'WILDFIRE');
  assert.equal(mapEventType('Special Weather Statement'), 'SEVERE_THUNDERSTORM');
});

test('parseAreaLabel: "Collin, TX" → "Collin County, TX"', () => {
  assert.equal(parseAreaLabel('Collin, TX'), 'Collin County, TX');
  assert.equal(parseAreaLabel('Collin County, TX'), 'Collin County, TX');
  assert.equal(parseAreaLabel('Dallas, TX; Tarrant, TX'), 'Dallas County, TX');
  assert.equal(parseAreaLabel('Cook County, IL'), 'Cook County, IL');
  assert.equal(parseAreaLabel(''), null);
  assert.equal(parseAreaLabel(null), null);
});

test('maxSeverity: highest severity wins', () => {
  assert.equal(maxSeverity(['MINOR', 'MODERATE', 'SEVERE']), 'SEVERE');
  assert.equal(maxSeverity(['MINOR', 'EXTREME', 'MODERATE']), 'EXTREME');
  assert.equal(maxSeverity([]), 'MINOR');
});

test('buildExternalId: order-independent sha1 of alert ids', () => {
  const a = buildExternalId(['alert-b', 'alert-a']);
  const b = buildExternalId(['alert-a', 'alert-b']);
  assert.equal(a, b);
  assert.ok(a.startsWith('noaa:'));
  assert.match(a, /^noaa:[0-9a-f]{40}$/);
});

test('clusterWeatherAlerts: nearby same-type alerts merge into one MultiPolygon event', () => {
  const alerts = [
    alert({ id: 'A', severity: 'Severe', geometry: square(-96.64, 33.19, 0.02) }),
    alert({
      id: 'B',
      severity: 'Moderate',
      geometry: square(-96.6, 33.2, 0.02),
      area: 'Collin, TX; Dallas, TX',
    }),
  ];
  const clusters = clusterWeatherAlerts(alerts, { nowMs: FIXED_NOW });
  assert.equal(clusters.length, 1);
  const [cluster] = clusters;
  assert.equal(cluster.eventType, 'SEVERE_THUNDERSTORM');
  assert.equal(cluster.severity, 'SEVERE');
  assert.equal(cluster.alertCount, 2);
  assert.deepEqual(cluster.alertIds, ['A', 'B']);
  assert.equal(cluster.geometry?.type, 'MultiPolygon');
  assert.equal((cluster.geometry as { coordinates: unknown[] }).coordinates.length, 2);
  assert.equal(cluster.name, 'Severe Thunderstorm — Collin County, TX');
  assert.equal(cluster.area, 'Collin, TX');
  assert.ok(cluster.centroid);
});

test('clusterWeatherAlerts: distinct centroid cell, event type, or 6h window split', () => {
  const base = alert({ id: 'A', geometry: square(-96.64, 33.19, 0.02) });

  // ~0.7° away → different 0.25° centroid cell.
  const far = alert({ id: 'B', geometry: square(-95.9, 33.8, 0.02) });
  // Different event type, same area/window.
  const differentType = alert({ id: 'C', type: 'Flash Flood Warning', severity: 'Moderate' });
  // Same type and centroid but 8 hours later → different 6h window.
  const later = alert({
    id: 'D',
    effective: '2026-11-16T14:00:00Z',
    expires: '2026-11-16T20:00:00Z',
  });

  const clusters = clusterWeatherAlerts([base, far, differentType, later], { nowMs: FIXED_NOW });
  assert.equal(clusters.length, 4);
  const types = clusters.map((cluster) => cluster.eventType).sort();
  assert.deepEqual(types, ['FLASH_FLOOD', 'SEVERE_THUNDERSTORM', 'SEVERE_THUNDERSTORM', 'SEVERE_THUNDERSTORM']);
});

test('clusterWeatherAlerts: geometry-less alerts still cluster by area + window', () => {
  const alerts = [
    alert({ id: 'X', geometry: null }),
    alert({ id: 'Y', geometry: null, severity: 'Extreme' }),
  ];
  const clusters = clusterWeatherAlerts(alerts, { nowMs: FIXED_NOW });
  assert.equal(clusters.length, 1);
  assert.equal(clusters[0].geometry, null);
  assert.equal(clusters[0].severity, 'EXTREME');
  assert.ok(clusters[0].name.includes('Collin County, TX'));
});

test('upsert idempotency: same alerts re-cluster to same externalId (created then updated)', () => {
  const alerts = [
    alert({ id: 'A' }),
    alert({ id: 'B', geometry: square(-96.6, 33.2, 0.02) }),
    alert({ id: 'C', type: 'Tornado Warning', severity: 'Extreme', geometry: square(-96.7, 33.1, 0.02) }),
  ];

  const first = clusterWeatherAlerts(alerts, { nowMs: FIXED_NOW });
  const shuffled = clusterWeatherAlerts([...alerts].reverse(), { nowMs: FIXED_NOW });

  assert.deepEqual(
    first.map((cluster) => cluster.externalId),
    shuffled.map((cluster) => cluster.externalId)
  );
  assert.deepEqual(
    first.map((cluster) => cluster.name),
    shuffled.map((cluster) => cluster.name)
  );

  // Simulate the DB unique key (companyId, externalId) against a simple store.
  const store = new Map<string, string>();
  let created = 0;
  let updated = 0;
  const applyClusters = (clusters: typeof first) => {
    for (const cluster of clusters) {
      if (store.has(cluster.externalId)) updated++;
      else {
        store.set(cluster.externalId, cluster.name);
        created++;
      }
    }
  };

  applyClusters(first);
  assert.equal(created, 2);
  assert.equal(updated, 0);

  applyClusters(shuffled);
  assert.equal(created, 2, 'second sync must not create new events');
  assert.equal(updated, 2, 'second sync updates the existing events');
  assert.equal(store.size, 2);
});

test('clusterAlerts: newest cluster first, deterministic ordering', () => {
  const older = alert({
    id: 'OLD',
    effective: '2026-11-10T06:00:00Z',
    expires: '2026-11-10T12:00:00Z',
  });
  const newer = alert({
    id: 'NEW',
    type: 'Tornado Warning',
    severity: 'Extreme',
    geometry: square(-96.7, 33.1, 0.02),
    effective: '2026-11-16T06:00:00Z',
    expires: '2026-11-16T12:00:00Z',
  });
  const clusters = clusterWeatherAlerts([older, newer], { nowMs: FIXED_NOW });
  assert.equal(clusters.length, 2);
  assert.equal(clusters[0].eventType, 'TORNADO');
});
