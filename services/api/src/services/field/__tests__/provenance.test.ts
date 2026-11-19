import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { anchorToTransparencyLog, buildC2PAManifest } from '../provenance';

describe('Wave B C2PA manifest and transparency log anchoring', () => {
  it('builds a deterministic C2PA manifest with custody & sensor assertions', () => {
    const manifest = buildC2PAManifest({
      packetCode: 'FW-ABCD',
      jobId: 'job-1',
      actorId: 'user-1',
      consentName: 'Jordan Homeowner',
      consentHash: 'chash',
      headHash: 'hhash',
      merkleLeaf: 'mleaf123456',
      measurementMeters: 1.2,
      demo: true,
      atIso: '2026-11-17T12:00:00.000Z',
      sensorWitness: { pitchDeg: 32.5, baroHpa: 1011.4, wifiBssidDigest: 'bssid-abc' },
    });

    assert.equal(manifest.specVersion, '2.1');
    assert.equal(manifest.assertions.length, 3);
    assert.equal(manifest.demoExcludedFromPublicLog, true);
    assert.equal(manifest.signature.manifestDigest.length, 64);
  });

  it('produces an honest LOCAL_SAMPLE_LOG receipt for demo packets', async () => {
    const receipt = await anchorToTransparencyLog({
      merkleLeaf: 'abcdef1234567890',
      headHash: 'head999',
      demo: true,
      atIso: '2026-11-17T12:00:00.000Z',
    });
    assert.equal(receipt.mode, 'LOCAL_SAMPLE_LOG');
    assert.equal(receipt.merkleLeaf, 'abcdef1234567890');
    assert.equal(receipt.inclusionDigest.length, 64);
  });
});
