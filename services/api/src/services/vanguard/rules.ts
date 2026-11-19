/**
 * Pure stateless Vanguard safety & compliance evaluators (zero DB / IO imports):
 *  - Reconciliation R-08 & TREC 531.20 Neutrality Schema Firewall
 *  - Anti-TrickyStore Keybox CRL & Organic IMU Jitter Variance Evaluator
 */

export const NEUTRALITY_FORBIDDEN_FIELDS = [
  'estimatedClaimValueCents',
  'repairEstimateDollars',
  'appraisalOpinion',
  'brokerPriceOpinion',
  'coverageRecommendation',
  'settlementOfferCents',
];

export function sanitizeNeutralTelemetry(
  payload: Record<string, unknown>,
  allowAttestation: boolean,
): { sanitized: Record<string, string>; strippedKeys: string[] } {
  const sanitized: Record<string, string> = {};
  const strippedKeys: string[] = [];
  for (const [k, v] of Object.entries(payload || {})) {
    if (!allowAttestation && NEUTRALITY_FORBIDDEN_FIELDS.includes(k)) {
      strippedKeys.push(k);
      continue;
    }
    if (v !== undefined && v !== null) {
      sanitized[k] = typeof v === 'string' ? v : JSON.stringify(v);
    }
  }
  return { sanitized, strippedKeys };
}

const REVOKED_KEYBOX_SERIALS = new Set([
  'LEAKED_OEM_KEYBOX_001',
  'TRICKYSTORE_AOSP_TESTKEY',
  '00DEADFACEBEEF01',
  'CHERRY_LEAKED_ECDSA_44',
]);

export function evaluateHardwareAttestation(params: {
  verdict: string;
  keyboxSerial?: string | null;
  imuSamples?: number[];
}): {
  passed: boolean;
  flagged: boolean;
  reason: string | null;
  imuJitterVariance: number;
} {
  const serial = (params.keyboxSerial || '').trim().toUpperCase();
  if (serial && REVOKED_KEYBOX_SERIALS.has(serial)) {
    return {
      passed: false,
      flagged: true,
      reason: `Keybox CRL match (${serial}): known leaked attestation key blocked.`,
      imuJitterVariance: 0,
    };
  }

  const samples = Array.isArray(params.imuSamples) ? params.imuSamples.filter(Number.isFinite) : [];
  let variance = 0.018;
  if (samples.length >= 4) {
    const mean = samples.reduce((a, b) => a + b, 0) / samples.length;
    variance = samples.reduce((acc, v) => acc + (v - mean) ** 2, 0) / samples.length;
    if (variance < 0.0005) {
      return {
        passed: false,
        flagged: true,
        reason: `IMU jitter variance (${variance.toFixed(6)}) below organic human threshold (emulator/tripod injection suspected).`,
        imuJitterVariance: Number(variance.toFixed(6)),
      };
    }
  }

  const normalizedVerdict = params.verdict.toUpperCase();
  if (
    !['MEETS_STRONG_INTEGRITY', 'MEETS_DEVICE_INTEGRITY', 'WEB_PASSKEY_ATTESTED'].includes(
      normalizedVerdict,
    )
  ) {
    return {
      passed: false,
      flagged: true,
      reason: `Integrity verdict "${normalizedVerdict}" insufficient for DePIN minting.`,
      imuJitterVariance: Number(variance.toFixed(6)),
    };
  }

  return {
    passed: true,
    flagged: false,
    reason: null,
    imuJitterVariance: Number(variance.toFixed(6)),
  };
}
