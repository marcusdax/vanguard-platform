import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import vm from 'node:vm';
import { BRAND_CONFIG } from './brand/brandConfig';
import { extractGeoCenter } from './lib/geo';

const RECORD_A_HASH = '20f78a5f916dca1c5297e9eb4788a3ea7d170760f122270d0abf141a20c9b65e';
const RECORD_B_HASH = '7c4a8fbf2912553421dff3c9ea492da8b7908e29c3ebb57b6206fb6c1d98c78e';

describe('@vanguard/web Standalone Verifier & Brand Config', () => {
  it('exposes clean Vanguard brand tokens free of legacy marks', () => {
    const serialized = JSON.stringify(BRAND_CONFIG).toLowerCase();
    expect(serialized).not.toContain('auc' + 'tum');
    expect(serialized).not.toContain('roof' + 'pro');
    expect(BRAND_CONFIG.platformName).toBe('Vanguard');
    expect(BRAND_CONFIG.mobileAppName).toBe('Vanguard Field');
  });

  it('ships vanguard-verify.min.js under 5 KB and validates golden chain records', async () => {
    const verifierPath = resolve(process.cwd(), 'public/verifier/vanguard-verify.min.js');
    const code = readFileSync(verifierPath, 'utf8');
    expect(Buffer.byteLength(code, 'utf8')).toBeLessThan(5120);

    const sandbox: any = {
      TextEncoder,
      crypto: globalThis.crypto,
    };
    sandbox.globalThis = sandbox;
    vm.runInNewContext(code, sandbox);

    const verifier = sandbox.VanguardVerifier;
    expect(verifier).toBeDefined();

    const records = [
      {
        seq: 1,
        kind: 'CAPTURE',
        at: '2026-01-15T12:00:00.000Z',
        actorId: 'op-1',
        summary: 'Four slopes captured',
        payload: { mediaCount: '4', captureStandardMet: 'true' },
        prevHash: '0'.repeat(64),
        hash: RECORD_A_HASH,
      },
      {
        seq: 2,
        kind: 'ATTEST',
        at: '2026-01-15T12:00:01.500Z',
        actorId: 'requester-1',
        summary: 'Quote "verified" · 68 °F',
        payload: { confirm: 'true', note: 'line\nbreak\tand\\\\slash' },
        prevHash: RECORD_A_HASH,
        hash: RECORD_B_HASH,
      },
    ];

    const chainRes = await verifier.verifyChain(records);
    expect(chainRes.valid).toBe(true);
    expect(chainRes.headHash).toBe(RECORD_B_HASH);
  });

  it('computes accurate centroids for NOAA Point, Polygon, and MultiPolygon storm alerts', () => {
    expect(extractGeoCenter({ type: 'Point', coordinates: [-97.32, 32.54] })).toEqual({
      lat: 32.54,
      lng: -97.32,
    });
    const poly = extractGeoCenter({
      type: 'Polygon',
      coordinates: [[[-97.0, 32.0], [-97.0, 33.0], [-98.0, 33.0], [-98.0, 32.0]]],
    });
    expect(poly?.lat).toBeCloseTo(32.5, 4);
    expect(poly?.lng).toBeCloseTo(-97.5, 4);

    const multi = extractGeoCenter({
      type: 'MultiPolygon',
      coordinates: [[[[-96.0, 33.0], [-96.0, 33.2], [-96.2, 33.2], [-96.2, 33.0]]]],
    });
    expect(multi?.lat).toBeCloseTo(33.1, 4);
    expect(multi?.lng).toBeCloseTo(-96.1, 4);
  });
});
