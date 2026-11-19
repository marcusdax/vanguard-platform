/**
 * Deterministic UTF-8 NFC Canonical JSON + SHA-256 Chain Preimage.
 * Matches fieldwright/core/model Engines.kt + GoldenVectorTest.kt byte-for-byte.
 */
import { createHash } from 'node:crypto';

export const GENESIS_HASH = '0'.repeat(64);

export type RecordKind =
  | 'CAPTURE'
  | 'ATTEST'
  | 'CUSTODY'
  | 'INSPECTION'
  | 'PAYMENT'
  | 'CORRECTION';

export interface EngineChainRecord {
  seq: number;
  kind: RecordKind;
  at: string;
  actorId: string;
  summary: string;
  payload: Record<string, string>;
  prevHash: string;
  hash?: string;
}

export function normalizeNfc(value: unknown): unknown {
  if (typeof value === 'string') return value.normalize('NFC');
  if (Array.isArray(value)) return value.map(normalizeNfc);
  if (value !== null && typeof value === 'object') {
    const out: Record<string, unknown> = {};
    for (const k of Object.keys(value as Record<string, unknown>).sort()) {
      out[k.normalize('NFC')] = normalizeNfc((value as Record<string, unknown>)[k]);
    }
    return out;
  }
  return value;
}

export function canonicalJson(value: unknown): string {
  const nfc = normalizeNfc(value);
  if (nfc === null || typeof nfc !== 'object') return JSON.stringify(nfc);
  if (Array.isArray(nfc)) return `[${nfc.map((item) => canonicalJson(item)).join(',')}]`;
  const obj = nfc as Record<string, unknown>;
  const keys = Object.keys(obj).sort();
  return `{${keys.map((k) => `${JSON.stringify(k)}:${canonicalJson(obj[k])}`).join(',')}}`;
}

export function sha256Hex(input: string | Uint8Array): string {
  return createHash('sha256').update(input).digest('hex');
}

export function canonicalPreimage(rec: Omit<EngineChainRecord, 'hash'>): string {
  return canonicalJson({
    seq: rec.seq,
    kind: rec.kind,
    at: rec.at,
    actorId: rec.actorId,
    summary: rec.summary,
    payload: rec.payload,
    prevHash: rec.prevHash,
  });
}

export function computeRecordHash(rec: Omit<EngineChainRecord, 'hash'>): string {
  return sha256Hex(canonicalPreimage(rec));
}

export function verifyChain(records: EngineChainRecord[]): {
  valid: boolean;
  entries: number;
  brokenAtSeq: number | null;
  headHash: string;
  detail: string;
} {
  if (!Array.isArray(records) || records.length === 0) {
    return { valid: false, entries: 0, brokenAtSeq: 1, headHash: GENESIS_HASH, detail: 'Empty chain.' };
  }
  let expectedPrev = GENESIS_HASH;
  for (let i = 0; i < records.length; i++) {
    const r = records[i];
    if (r.seq !== i + 1 || r.prevHash !== expectedPrev) {
      return {
        valid: false,
        entries: records.length,
        brokenAtSeq: r.seq ?? i + 1,
        headHash: records[records.length - 1]?.hash || GENESIS_HASH,
        detail: `Chain continuity error at seq ${i + 1}.`,
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
    if (r.hash !== recomputed) {
      return {
        valid: false,
        entries: records.length,
        brokenAtSeq: r.seq,
        headHash: records[records.length - 1]?.hash || GENESIS_HASH,
        detail: `Hash mismatch at seq ${r.seq}.`,
      };
    }
    expectedPrev = recomputed;
  }
  return {
    valid: true,
    entries: records.length,
    brokenAtSeq: null,
    headHash: expectedPrev,
    detail: `Verified ${records.length} canonical records from genesis.`,
  };
}
