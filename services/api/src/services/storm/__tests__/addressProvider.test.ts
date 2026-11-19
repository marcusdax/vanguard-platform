import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import {
  MapMapAddressProvider,
  extractZoneAddresses,
  formatAddress,
  getAddressProvider,
  normalizeAddressKey,
  samplePointsInZone,
} from '../addressProvider';
import { approximateBufferPolygon, pointInGeometry } from '../geo';

const center = { lat: 33.1973, lng: -96.6399 };
const zone = approximateBufferPolygon(center, 1);

const feature = (type: string, lng: number, lat: number, props: Record<string, unknown> = {}) => ({
  type: 'Feature',
  geometry: { type: 'Point', coordinates: [lng, lat] as [number, number] },
  properties: { type, ...props },
});

describe('address provider: zone sampling', () => {
  it('samples roughly the requested number of points, all inside the zone', () => {
    const pts = samplePointsInZone(zone, 40);
    assert.ok(pts.length >= 24 && pts.length <= 40, String(pts.length));
    for (const p of pts) assert.ok(pointInGeometry(p, zone));
  });

  it('returns nothing for an unusable zone', () => {
    assert.deepEqual(samplePointsInZone(null, 40), []);
  });
});

describe('address provider: parsing', () => {
  it('formats a one-line address only when there is a house number and street', () => {
    assert.equal(
      formatAddress({ housenumber: '1420', street: 'West Hunt Street', city: 'McKinney', state: 'Texas', postcode: '75069' }),
      '1420 West Hunt Street, McKinney, Texas 75069'
    );
    assert.equal(formatAddress({ name: '2201 Virginia Parkway', city: 'McKinney' }), '2201 Virginia Parkway, McKinney');
    assert.equal(formatAddress({ street: 'West Hunt Street', city: 'McKinney' }), null);
    assert.equal(formatAddress({ name: 'Sprouts Farmers Market' }), null);
  });

  it('normalizes abbreviations so the same house matches an existing lead', () => {
    assert.equal(normalizeAddressKey('1420 W. Hunt St., McKinney, TX'), normalizeAddressKey('1420 West Hunt Street, McKinney, Texas 75069'));
    assert.notEqual(normalizeAddressKey('1420 W Hunt St'), normalizeAddressKey('1422 W Hunt St'));
  });

  it('keeps in-zone residential addresses, drops businesses, outside points and duplicates', () => {
    const lists = [
      [
        feature('poi', -96.6408, 33.1975, { name: 'Sprouts Farmers Market' }),
        feature('address', -96.6408, 33.1975, { housenumber: '2201', street: 'Virginia Parkway', city: 'McKinney' }), // the store
        feature('address', -96.6390, 33.1980, { housenumber: '1508', street: 'West Hunt Street', city: 'McKinney' }),
      ],
      [
        feature('address', -96.6390, 33.1980, { housenumber: '1508', street: 'W Hunt St', city: 'McKinney' }), // duplicate
        feature('address', -96.5000, 33.3000, { housenumber: '9', street: 'Far Away Road' }), // outside the zone
        feature('street', -96.6395, 33.1970, { name: 'West Hunt Street' }),
      ],
    ];
    const r = extractZoneAddresses(lists, zone);
    assert.deepEqual(r.addresses.map((a) => a.address), ['1508 West Hunt Street, McKinney']);
    assert.equal(r.droppedBusinesses, 1);
    assert.equal(r.rawAddresses, 4);
  });
});

describe('address provider: MapMap batch request', () => {
  it('sends one batch request with the key and parses nested feature collections', async () => {
    const calls: Array<{ url: string; headers: Record<string, string>; body: { queries: Array<{ lat: number; lon: number; limit: number }> } }> = [];
    const fakeFetch = async (url: string, init: { headers: Record<string, string>; body: string }) => {
      const body = JSON.parse(init.body);
      calls.push({ url, headers: init.headers, body });
      return {
        ok: true,
        status: 200,
        text: async () => '',
        json: async () => ({
          results: body.queries.map((q: { lat: number; lon: number }, index: number) => ({
            index,
            features: { type: 'FeatureCollection', features: index === 0 ? [feature('address', -96.6390, 33.1980, { housenumber: '1508', street: 'West Hunt Street' })] : [] },
          })),
        }),
      };
    };
    const provider = new MapMapAddressProvider('snk_test', 'https://api.example.test/', fakeFetch);
    const r = await provider.addressesInZone(zone, { maxPoints: 30 });
    assert.equal(calls.length, 1);
    assert.equal(calls[0].url, 'https://api.example.test/geocode/batch');
    assert.equal(calls[0].headers.Authorization, 'Bearer snk_test');
    assert.ok(calls[0].body.queries.length > 0 && calls[0].body.queries.length <= 30);
    assert.deepEqual(Object.keys(calls[0].body.queries[0]).sort(), ['lat', 'limit', 'lon']);
    assert.equal(r.sampledPoints, calls[0].body.queries.length);
    assert.deepEqual(r.addresses.map((a) => a.address), ['1508 West Hunt Street']);
  });

  it('reports an upstream error instead of returning an empty list', async () => {
    const failing = async () => ({ ok: false, status: 402, text: async () => 'quota exhausted', json: async () => ({}) });
    const provider = new MapMapAddressProvider('snk_test', 'https://api.example.test', failing);
    await assert.rejects(provider.addressesInZone(zone), /HTTP 402.*quota exhausted/);
  });

  it('is only configured when MAPMAP_API_KEY is set', () => {
    assert.equal(getAddressProvider({}), null);
    assert.ok(getAddressProvider({ MAPMAP_API_KEY: 'snk_x' }));
  });
});
