import { test } from 'node:test';
import assert from 'node:assert/strict';
import { buildOffer, optimizeQueueRoute, rankOffers, applyOrder, parseLatLng, windowConflicts } from '../queue';

const base = { propertyAddress: 'x', phase: 'INSPECTION', endDate: null };
const origin = { lat: 32.78, lng: -96.8 } as any;

test('parseLatLng rejects junk', () => {
  assert.equal(parseLatLng(null), null);
  assert.equal(parseLatLng({ lat: 'a', lng: 1 }), null);
  assert.equal(parseLatLng({ lat: 120, lng: 1 }), null);
  assert.deepEqual(parseLatLng({ lat: 1, lng: 2 }), { lat: 1, lng: 2 });
});

test('rankOffers sorts by pay, distance and respects radius', () => {
  const a = buildOffer({ ...base, id: 'a', name: 'a', latLng: { lat: 32.79, lng: -96.8 } as any, budget: 300 }, origin);
  const b = buildOffer({ ...base, id: 'b', name: 'b', latLng: { lat: 33.5, lng: -96.8 } as any, budget: 900 }, origin);
  assert.deepEqual(rankOffers([a, b], 'pay').map((o) => o.id), ['b', 'a']);
  assert.deepEqual(rankOffers([a, b], 'distance').map((o) => o.id), ['a', 'b']);
  assert.deepEqual(rankOffers([a, b], 'pay', 10).map((o) => o.id), ['a']);
});

test('offers without coordinates are kept and sorted last by distance', () => {
  const a = buildOffer({ ...base, id: 'a', name: 'a', latLng: null, budget: null }, origin);
  const b = buildOffer({ ...base, id: 'b', name: 'b', latLng: { lat: 32.8, lng: -96.8 } as any, budget: 10 }, origin);
  assert.deepEqual(rankOffers([a, b], 'distance').map((o) => o.id), ['b', 'a']);
});

test('applyOrder honours operator order and ignores unknown ids', () => {
  const items = [{ id: '1' }, { id: '2' }, { id: '3' }];
  assert.deepEqual(applyOrder(items, ['3', 'zzz', '1']).map((i) => i.id), ['3', '1', '2']);
});

test('windowConflicts flags overlaps only', () => {
  const d = (h: number) => new Date(2026, 0, 1, h);
  assert.deepEqual(
    windowConflicts([
      { id: 'a', start: d(9), end: d(11) },
      { id: 'b', start: d(10), end: d(12) },
      { id: 'c', start: d(13), end: d(14) },
    ]),
    [['a', 'b']],
  );
});

test('optimizeQueueRoute reduces distance with nearest-neighbor and 2-opt', () => {
  const res = optimizeQueueRoute(
    [
      { id: 'far', latLng: { lat: 33.4, lng: -96.8 } as any },
      { id: 'near', latLng: { lat: 32.8, lng: -96.8 } as any },
      { id: 'mid', latLng: { lat: 33.0, lng: -96.8 } as any },
      { id: 'nocoord', latLng: null },
    ],
    origin
  );
  assert.deepEqual(res.orderedIds, ['near', 'mid', 'far', 'nocoord']);
  assert.ok(res.optimizedKm <= res.originalKm);
  assert.ok(res.savedKm > 0);
});
