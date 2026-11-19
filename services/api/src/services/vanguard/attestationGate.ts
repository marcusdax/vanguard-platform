/**
 * Zero-Trust Hardware Attestation & Anti-TrickyStore Gate:
 *  - Nonce challenge generation bound to userId + subjectRef
 *  - Known-leaked OEM keybox.xml Certificate Revocation List (CRL) screening
 *  - Organic IMU hand-jitter variance check (flags synthetic/emulator zero-jitter rigs)
 */
import { randomBytes } from 'node:crypto';
import { prisma } from '../../prisma';
import { evaluateHardwareAttestation } from './rules';

export { evaluateHardwareAttestation };

export async function issueAttestationChallenge(params: {
  companyId: string;
  userId: string;
  subjectRef: string;
}) {
  const nonce = randomBytes(24).toString('hex');
  const expiresAt = new Date(Date.now() + 5 * 60_000);
  const record = await prisma.deviceAttestationNonce.create({
    data: {
      nonce,
      companyId: params.companyId,
      userId: params.userId,
      subjectRef: params.subjectRef,
      expiresAt,
    },
  });
  return {
    nonce: record.nonce,
    subjectRef: record.subjectRef,
    expiresAt: record.expiresAt.toISOString(),
  };
}

export async function verifyAttestationChallenge(params: {
  nonce: string;
  companyId: string;
  userId: string;
  verdict: string;
  keyboxSerial?: string;
  imuSamples?: number[];
}) {
  const row = await prisma.deviceAttestationNonce.findUnique({ where: { nonce: params.nonce } });
  if (!row || row.companyId !== params.companyId || row.userId !== params.userId) {
    return { ok: false, status: 404, error: 'Attestation nonce not found.' };
  }
  if (row.consumedAt) {
    return { ok: false, status: 409, error: 'Attestation nonce already consumed (replay blocked).' };
  }
  if (row.expiresAt.getTime() < Date.now()) {
    return { ok: false, status: 410, error: 'Attestation nonce expired.' };
  }

  const check = evaluateHardwareAttestation({
    verdict: params.verdict,
    keyboxSerial: params.keyboxSerial,
    imuSamples: params.imuSamples,
  });

  const updated = await prisma.deviceAttestationNonce.update({
    where: { id: row.id },
    data: {
      verdict: check.flagged ? 'FLAGGED' : params.verdict.toUpperCase(),
      keyboxSerial: params.keyboxSerial || null,
      imuJitterVariance: check.imuJitterVariance,
      flagged: check.flagged,
      flagReason: check.reason,
      consumedAt: new Date(),
    },
  });

  return {
    ok: check.passed,
    status: check.passed ? 200 : 403,
    attestation: updated,
    badgeState: check.flagged ? 'FLAGGED' : 'VERIFIED',
  };
}
