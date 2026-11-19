/**
 * Field Hub Sprint 2 — pure evidence chain, fee disclosure & lifecycle helpers.
 * Canonical JSON and SHA-256 chain rules match fieldwright/backend/mock-server.mjs byte-for-byte.
 */
import { createHash } from 'node:crypto';

export const GENESIS_HASH = '0'.repeat(64);
export const PLATFORM_FEE_BP = 1_500;
export const INSTANT_FEE_BP = 150;
export const INSTANT_MIN_FEE_CENTS = 50;
export const INSTANT_MAX_FEE_CENTS = 500;

export type QueueStatus = 'QUEUED' | 'SCHEDULED' | 'IN_PROGRESS' | 'SEALED' | 'SUBMITTED';
export type PayoutMode = 'STANDARD' | 'INSTANT';

export interface ChainRecord {
  seq: number;
  kind: 'CAPTURE' | 'ATTEST' | 'CUSTODY' | 'CORRECTION';
  at: string;
  actorId: string;
  summary: string;
  payload: Record<string, string>;
  prevHash: string;
  hash: string;
}

export function sha256Hex(input: string): string {
  return createHash('sha256').update(input, 'utf8').digest('hex');
}

export function canonicalJson(value: unknown): string {
  if (value === null || typeof value !== 'object') return JSON.stringify(value);
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(',')}]`;
  const obj = value as Record<string, unknown>;
  const keys = Object.keys(obj).sort();
  return `{${keys.map((k) => `${JSON.stringify(k)}:${canonicalJson(obj[k])}`).join(',')}}`;
}

export function computeRecordHash(
  rec: Omit<ChainRecord, 'hash'>,
): string {
  return sha256Hex(
    canonicalJson({
      seq: rec.seq,
      kind: rec.kind,
      at: rec.at,
      actorId: rec.actorId,
      summary: rec.summary,
      payload: rec.payload,
      prevHash: rec.prevHash,
    }),
  );
}

export function buildPacketRecords(params: {
  actorId: string;
  jobId: string;
  consentName: string;
  consentHash: string;
  measurementMeters: number;
  referenceLabel: string;
  notes: string;
  atIso: string;
}): ChainRecord[] {
  const steps: Array<{
    kind: ChainRecord['kind'];
    summary: string;
    payload: Record<string, string>;
  }> = [
    {
      kind: 'ATTEST',
      summary: `Consent on file (${params.consentName})`,
      payload: { jobId: params.jobId, consentHash: params.consentHash },
    },
    {
      kind: 'CAPTURE',
      summary: `Objective capture (${params.measurementMeters} m vs ${params.referenceLabel})`,
      payload: {
        jobId: params.jobId,
        measurementMeters: String(params.measurementMeters),
        referenceLabel: params.referenceLabel,
        notes: params.notes || 'none',
      },
    },
    {
      kind: 'CUSTODY',
      summary: 'Packet sealed and custody transferred',
      payload: { jobId: params.jobId, sealState: 'EARNED', tier: '5' },
    },
  ];

  const records: ChainRecord[] = [];
  let prevHash = GENESIS_HASH;
  steps.forEach((s, idx) => {
    const base: Omit<ChainRecord, 'hash'> = {
      seq: idx + 1,
      kind: s.kind,
      at: params.atIso,
      actorId: params.actorId,
      summary: s.summary,
      payload: s.payload,
      prevHash,
    };
    const hash = computeRecordHash(base);
    records.push({ ...base, hash });
    prevHash = hash;
  });
  return records;
}

export function verifyPacketChain(records: ChainRecord[]): {
  valid: boolean;
  entries: number;
  brokenAtSeq: number | null;
  headHash: string;
  detail: string;
} {
  if (!Array.isArray(records) || records.length === 0) {
    return {
      valid: false,
      entries: 0,
      brokenAtSeq: 1,
      headHash: GENESIS_HASH,
      detail: 'Packet contains no records.',
    };
  }
  let expectedPrev = GENESIS_HASH;
  for (let i = 0; i < records.length; i++) {
    const r = records[i];
    if (r.seq !== i + 1 || r.prevHash !== expectedPrev) {
      return {
        valid: false,
        entries: records.length,
        brokenAtSeq: r.seq ?? i + 1,
        headHash: records[records.length - 1].hash,
        detail: `Chain link mismatch at seq ${i + 1}.`,
      };
    }
    const recomputed = computeRecordHash({
      seq: r.seq,
      kind: r.kind,
      at: r.at,
      actorId: r.actorId,
      summary: r.summary,
      payload: r.payload,
      prevHash: r.prevHash,
    });
    if (recomputed !== r.hash) {
      return {
        valid: false,
        entries: records.length,
        brokenAtSeq: r.seq,
        headHash: records[records.length - 1].hash,
        detail: `Hash mismatch at seq ${r.seq}.`,
      };
    }
    expectedPrev = r.hash;
  }
  return {
    valid: true,
    entries: records.length,
    brokenAtSeq: null,
    headHash: expectedPrev,
    detail: `Chain verified from genesis; ${records.length} records intact.`,
  };
}

export function applyBasisPoints(amountCents: number, bp: number): number {
  return Math.floor((amountCents * bp + 5_000) / 10_000);
}

export function quotePayout(amountCents: number, mode: PayoutMode) {
  if (!Number.isInteger(amountCents) || amountCents <= 0) {
    throw new Error('amountCents must be a positive integer');
  }
  const feeCents =
    mode === 'INSTANT'
      ? Math.min(
          INSTANT_MAX_FEE_CENTS,
          Math.max(INSTANT_MIN_FEE_CENTS, applyBasisPoints(amountCents, INSTANT_FEE_BP)),
        )
      : 0;
  const netCents = amountCents - feeCents;
  if (netCents <= 0) {
    throw new Error('Payout amount too small to cover minimum fee');
  }
  const fmt = (c: number) => `$${(c / 100).toFixed(2)}`;
  const feeDisclosure =
    mode === 'INSTANT'
      ? `Instant payout fee: 1.5% (min $0.50, capped at $5.00) = ${fmt(feeCents)}. You receive ${fmt(netCents)}.`
      : `Standard ACH is free ($0.00 fee, 1–3 business days). You receive ${fmt(netCents)}.`;

  return {
    amountCents,
    feeCents,
    netCents,
    mode,
    etaLabel: mode === 'INSTANT' ? 'Minutes to debit card' : '1–3 business days (ACH)',
    feeDisclosure,
  };
}

export function computeBalances(
  entries: Array<{ kind: string; amountCents: number; status: string }>,
) {
  let availableCents = 0;
  let pendingCents = 0;
  let paidLifetimeCents = 0;
  for (const e of entries) {
    if (e.status === 'AVAILABLE') availableCents += e.amountCents;
    else if (e.status === 'PENDING') pendingCents += e.amountCents;
    else if (e.status === 'PAID' && e.amountCents > 0) paidLifetimeCents += e.amountCents;
  }
  return { availableCents, pendingCents, paidLifetimeCents };
}

const TRANSITIONS: Record<QueueStatus, QueueStatus[]> = {
  QUEUED: ['SCHEDULED', 'IN_PROGRESS'],
  SCHEDULED: ['QUEUED', 'IN_PROGRESS'],
  IN_PROGRESS: ['SEALED'],
  SEALED: ['SUBMITTED'],
  SUBMITTED: [],
};

export function canAdvanceQueueStatus(from: QueueStatus, to: QueueStatus): boolean {
  return TRANSITIONS[from]?.includes(to) ?? false;
}
