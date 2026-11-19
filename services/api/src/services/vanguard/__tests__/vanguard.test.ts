import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { sanitizeNeutralTelemetry, evaluateHardwareAttestation } from '../rules';
import { DEMAND_VERTICALS, HARDWARE_GEAR_CATALOG, VANGUARD_TIERS } from '../catalog';
import { checkCredentialsForAction } from '../../../engine/credentials';

describe('Vanguard Services & Trust Engine Parity', () => {
  it('keeps services/api/src/engine byte-for-byte synced with packages/trust-engine/src', () => {
    const pkgDir = resolve(process.cwd(), '../../packages/trust-engine/src');
    const beDir = resolve(process.cwd(), 'src/engine');
    for (const file of ['canonical.ts', 'merkle.ts', 'credentials.ts', 'economics.ts', 'index.ts']) {
      assert.equal(
        readFileSync(resolve(pkgDir, file), 'utf8'),
        readFileSync(resolve(beDir, file), 'utf8'),
        `${file} must match @vanguard/trust-engine source`,
      );
    }
  });

  it('enforces Reconciliation R-08 & TREC 531.20 neutrality boundary on Tier 1-3 telemetry', () => {
    const { sanitized, strippedKeys } = sanitizeNeutralTelemetry(
      {
        pitchRatio: '7/12',
        shingleWearScore: 4,
        estimatedClaimValueCents: 1850000,
        appraisalOpinion: 'Undervalued by 15%',
        notes: '4 slopes photographed in daylight',
      },
      false,
    );
    assert.deepEqual(strippedKeys.sort(), ['appraisalOpinion', 'estimatedClaimValueCents']);
    assert.equal(sanitized.pitchRatio, '7/12');
    assert.equal((sanitized as any).estimatedClaimValueCents, undefined);

    const commander = sanitizeNeutralTelemetry(
      { estimatedClaimValueCents: 1850000, pitchRatio: '7/12' },
      true,
    );
    assert.equal(commander.strippedKeys.length, 0);
  });

  it('flags TrickyStore leaked OEM keybox serials and zero-jitter emulator IMU trajectories', () => {
    const leaked = evaluateHardwareAttestation({
      verdict: 'MEETS_STRONG_INTEGRITY',
      keyboxSerial: 'LEAKED_OEM_KEYBOX_001',
      imuSamples: [0.1, 0.14, 0.08, 0.16, 0.11],
    });
    assert.equal(leaked.passed, false);
    assert.equal(leaked.flagged, true);

    const emulator = evaluateHardwareAttestation({
      verdict: 'MEETS_STRONG_INTEGRITY',
      keyboxSerial: 'CLEAN_OEM_KEYBOX_9921',
      imuSamples: [1.0, 1.0, 1.0, 1.0, 1.0],
    });
    assert.equal(emulator.passed, false);
    assert.equal(emulator.flagged, true);

    const organic = evaluateHardwareAttestation({
      verdict: 'MEETS_STRONG_INTEGRITY',
      keyboxSerial: 'CLEAN_OEM_KEYBOX_9921',
      imuSamples: [0.12, 0.18, 0.05, 0.24, 0.09, 0.21],
    });
    assert.equal(organic.passed, true);
    assert.equal(organic.flagged, false);
  });

  it('covers 4 Career Tiers, 10 Verticals (T0-T3), 4 Hardware Kits, and Invariant I-1/I-5', () => {
    assert.equal(VANGUARD_TIERS.length, 4);
    assert.equal(DEMAND_VERTICALS.length, 10);
    assert.equal(HARDWARE_GEAR_CATALOG.length, 4);
    for (const v of DEMAND_VERTICALS.filter((d) => d.tierGroup === 'T0')) {
      const check = checkCredentialsForAction({
        isFloorAction: true,
        requiredCredentials: v.requiredCredential ? [v.requiredCredential] : [],
        heldCredentials: [],
      });
      assert.equal(check.allowed, true);
    }
  });
});
