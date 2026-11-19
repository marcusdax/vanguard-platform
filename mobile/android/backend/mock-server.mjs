#!/usr/bin/env node
/**
 * Fieldwright contract mock server
 * ================================
 * Zero-dependency Node (ESM) implementation of the v1 API in docs/api/fieldwright.openapi.yaml.
 *
 * What it is: a contract-faithful in-memory server for Android development and CI. It exists so
 * the app can be built against real status codes, real idempotency semantics, real ETag
 * conflicts and a deterministic seeded book of jobs, offers, packets, ledger entries and payouts.
 *
 * What it is NOT: production. It has no persistence, no TLS, no real device attestation and it
 * does not verify ECDSA signatures (it validates shape and key id only). It DOES walk the record
 * hash chain with SHA-256 and build a real RFC 6962-style Merkle tree, because those are the
 * parts the client must get exactly right.
 *
 * Canonical record preimage (the client must reproduce this exactly):
 *   hash = SHA-256( canonicalJson({
 *     seq, kind, at, actorId, summary, payload, prevHash
 *   }) )
 * where canonicalJson sorts object keys, emits no whitespace, and payload is an object of
 * string values. `at` is ISO-8601 UTC with milliseconds and a trailing Z. The genesis prevHash
 * is 64 zeros. Records are 1-based and gap-free; a record is never mutated after upload.
 *
 * Usage:
 *   node backend/mock-server.mjs --port 8787
 *   node backend/mock-server.mjs --port 8787 --latency 300
 *
 * Endpoints, error codes and shapes: docs/03-api-contract.md.
 */

import { createServer } from 'node:http';
import { createHash } from 'node:crypto';
import { parseArgs } from 'node:util';

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const GENESIS = '0'.repeat(64);
const OFFER_TTL_MS = 45_000;
const MAX_ACTIVE_JOBS = 3;
const MIN_PAYOUT_FLOOR_CENTS = 2_500;
const PLATFORM_FEE_BP = 1_500;
const INSTANT_FEE_BP = 150;
const INSTANT_MIN_FEE_CENTS = 50;
const INSTANT_MAX_FEE_CENTS = 500;
const MILEAGE_CENTS_PER_MILE = 70;
const MAX_BODY_BYTES = 5 * 1024 * 1024;
const TOKEN_TTL_MS = 60 * 60 * 1000;

const HASH_RE = /^[0-9a-f]{64}$/;
const ISO_RE = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/;

const ACTIVE_STATUSES = new Set([
  'ACCEPTED', 'EN_ROUTE', 'ON_SITE', 'IN_PROGRESS', 'PACKET_READY', 'SEALED', 'SUBMITTED',
]);

// ---------------------------------------------------------------------------
// CLI
// ---------------------------------------------------------------------------

const { values: argv } = parseArgs({
  options: {
    port: { type: 'string', short: 'p', default: '8787' },
    latency: { type: 'string', short: 'l', default: '0' },
    help: { type: 'boolean', short: 'h', default: false },
  },
  allowPositionals: false,
});

if (argv.help) {
  console.log('Usage: node backend/mock-server.mjs [--port 8787] [--latency 0]');
  process.exit(0);
}

const PORT = Number.parseInt(argv.port, 10);
const BASE_LATENCY_MS = Number.parseInt(argv.latency, 10);
if (!Number.isInteger(PORT) || PORT < 1 || PORT > 65535) {
  console.error(`Invalid --port: ${argv.port}`);
  process.exit(2);
}
if (!Number.isInteger(BASE_LATENCY_MS) || BASE_LATENCY_MS < 0) {
  console.error(`Invalid --latency: ${argv.latency}`);
  process.exit(2);
}

// ---------------------------------------------------------------------------
// Small utilities
// ---------------------------------------------------------------------------

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function sha256Hex(input) {
  return createHash('sha256').update(input, 'utf8').digest('hex');
}

/** Deterministic JSON: sorted keys, no whitespace. The hashing contract. */
function canonicalJson(value) {
  if (value === null || typeof value !== 'object') return JSON.stringify(value);
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(',')}]`;
  const keys = Object.keys(value).sort();
  return `{${keys.map((k) => `${JSON.stringify(k)}:${canonicalJson(value[k])}`).join(',')}}`;
}

function nowIso() {
  return new Date().toISOString();
}

function isoAt(epochMs) {
  return new Date(epochMs).toISOString();
}

function isIso(value) {
  return typeof value === 'string' && ISO_RE.test(value) && !Number.isNaN(Date.parse(value));
}

function haversineMeters(a, b) {
  const R = 6_371_000;
  const toRad = (deg) => (deg * Math.PI) / 180;
  const dLat = toRad(b.lat - a.lat);
  const dLng = toRad(b.lng - a.lng);
  const lat1 = toRad(a.lat);
  const lat2 = toRad(b.lat);
  const h =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLng / 2) ** 2;
  return 2 * R * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h));
}

/** Half-up basis-points rounding, applied once. Mirrors `applyBasisPoints` in Money.kt. */
function applyBasisPoints(amountCents, bp) {
  return Math.floor((amountCents * bp + 5_000) / 10_000);
}

function instantFeeCents(amountCents) {
  const raw = applyBasisPoints(amountCents, INSTANT_FEE_BP);
  return Math.min(INSTANT_MAX_FEE_CENTS, Math.max(INSTANT_MIN_FEE_CENTS, raw));
}

function platformFeeCents(payoutCents) {
  return applyBasisPoints(payoutCents, PLATFORM_FEE_BP);
}

function clamp(value, min, max) {
  return Math.min(max, Math.max(min, value));
}

/** Opaque pagination cursor: base64 of the next offset. No business state. */
function encodeCursor(offset) {
  return Buffer.from(`o:${offset}`, 'utf8').toString('base64url');
}

function decodeCursor(cursor) {
  if (cursor === undefined || cursor === null || cursor === '') return 0;
  try {
    const decoded = Buffer.from(String(cursor), 'base64url').toString('utf8');
    if (!decoded.startsWith('o:')) return 0;
    const offset = Number.parseInt(decoded.slice(2), 10);
    return Number.isInteger(offset) && offset >= 0 ? offset : 0;
  } catch {
    return 0;
  }
}

function paginate(items, cursor, limit) {
  const safeLimit = clamp(Number.isInteger(limit) ? limit : 20, 1, 100);
  const offset = decodeCursor(cursor);
  const page = items.slice(offset, offset + safeLimit);
  const next = offset + safeLimit < items.length ? encodeCursor(offset + safeLimit) : null;
  return { items: page, nextCursor: next, asOf: nowIso() };
}

function etagOf(version) {
  return `"v${version}"`;
}

function requireHash(value, field) {
  if (typeof value !== 'string' || !HASH_RE.test(value)) {
    throw new ApiError(400, 'VALIDATION_FAILED', `${field} must be 64 lowercase hex characters.`, { field });
  }
  return value;
}

function requireString(value, field, maxLength = 500) {
  if (typeof value !== 'string' || value.length === 0) {
    throw new ApiError(400, 'VALIDATION_FAILED', `${field} is required.`, { field });
  }
  if (value.length > maxLength) {
    throw new ApiError(400, 'VALIDATION_FAILED', `${field} exceeds ${maxLength} characters.`, { field });
  }
  return value;
}

function optionalString(value, field, maxLength = 500) {
  if (value === undefined || value === null) return null;
  return requireString(value, field, maxLength);
}

function requireTimestamp(value, field) {
  if (!isIso(value)) {
    throw new ApiError(400, 'VALIDATION_FAILED', `${field} must be an ISO-8601 UTC timestamp ending in Z.`, { field });
  }
  return value;
}

function requireEnum(value, allowed, field) {
  if (!allowed.includes(value)) {
    throw new ApiError(400, 'VALIDATION_FAILED', `${field} must be one of ${allowed.join(', ')}.`, { field, allowed });
  }
  return value;
}

// ---------------------------------------------------------------------------
// Chain + Merkle primitives
// ---------------------------------------------------------------------------

/**
 * The documented canonical preimage. Client and server must agree byte for byte.
 * FROZEN: domain-core reproduces this exact shape and passes a golden vector
 * (docs/03-api-contract.md §3.4). Do not add, remove, reorder or rename fields,
 * and keep `seq` numeric and `at` an ISO-8601 UTC string with milliseconds.
 */
function recordHash(record) {
  return sha256Hex(
    canonicalJson({
      seq: record.seq,
      kind: record.kind,
      at: record.at,
      actorId: record.actorId,
      summary: record.summary,
      payload: record.payload,
      prevHash: record.prevHash,
    }),
  );
}

function makeRecord({ seq, kind, at, actorId, summary, payload, prevHash, signatureBase64 = null, signerKeyId = null }) {
  const base = { seq, kind, at, actorId, summary, payload, prevHash };
  return { ...base, hash: recordHash(base), signatureBase64, signerKeyId };
}

function verifyChain(records) {
  if (!Array.isArray(records) || records.length === 0) {
    return { valid: false, entries: 0, brokenAtSeq: null, headHash: GENESIS, detail: 'No records.' };
  }
  for (let i = 0; i < records.length; i += 1) {
    const rec = records[i];
    const expectedSeq = i + 1;
    if (rec.seq !== expectedSeq) {
      return {
        valid: false,
        entries: records.length,
        brokenAtSeq: expectedSeq,
        headHash: records[records.length - 1]?.hash ?? GENESIS,
        detail: `Sequence gap: expected ${expectedSeq}, found ${rec.seq}.`,
      };
    }
    if (i === 0 && rec.prevHash !== GENESIS) {
      return {
        valid: false,
        entries: records.length,
        brokenAtSeq: 1,
        headHash: records[records.length - 1]?.hash ?? GENESIS,
        detail: 'First record does not point at GENESIS.',
      };
    }
    if (i > 0 && rec.prevHash !== records[i - 1].hash) {
      return {
        valid: false,
        entries: records.length,
        brokenAtSeq: rec.seq,
        headHash: records[records.length - 1]?.hash ?? GENESIS,
        detail: `prevHash mismatch at seq ${rec.seq}.`,
      };
    }
    if (typeof rec.hash !== 'string' || !HASH_RE.test(rec.hash)) {
      return {
        valid: false,
        entries: records.length,
        brokenAtSeq: rec.seq,
        headHash: records[records.length - 1]?.hash ?? GENESIS,
        detail: `Record ${rec.seq} hash is not 64 lowercase hex.`,
      };
    }
    let recomputed;
    try {
      recomputed = recordHash(rec);
    } catch {
      recomputed = null;
    }
    if (recomputed !== rec.hash) {
      return {
        valid: false,
        entries: records.length,
        brokenAtSeq: rec.seq,
        headHash: records[records.length - 1]?.hash ?? GENESIS,
        detail: `Hash mismatch at seq ${rec.seq}: canonical preimage does not reproduce hash.`,
      };
    }
  }
  return {
    valid: true,
    entries: records.length,
    brokenAtSeq: null,
    headHash: records[records.length - 1].hash,
    detail: `Chain verified from genesis; ${records.length} records.`,
  };
}

// RFC 6962-style Merkle helpers. leafHash = SHA-256(0x00 || data), node = SHA-256(0x01 || l || r).
function leafHashOf(packetHash) {
  return createHash('sha256').update(Buffer.concat([Buffer.from([0]), Buffer.from(packetHash, 'utf8')])).digest('hex');
}

function nodeHashOf(left, right) {
  return createHash('sha256').update(Buffer.concat([Buffer.from([1]), Buffer.from(left, 'hex'), Buffer.from(right, 'hex')])).digest('hex');
}

function largestPowerOfTwoBelow(n) {
  let k = 1;
  while (k * 2 < n) k *= 2;
  return k;
}

function mthRange(packetHashes) {
  if (packetHashes.length === 0) return sha256Hex('');
  if (packetHashes.length === 1) return leafHashOf(packetHashes[0]);
  const k = largestPowerOfTwoBelow(packetHashes.length);
  return nodeHashOf(mthRange(packetHashes.slice(0, k)), mthRange(packetHashes.slice(k)));
}

function auditPath(packetHashes, index) {
  if (packetHashes.length === 1) return [];
  const k = largestPowerOfTwoBelow(packetHashes.length);
  if (index < k) {
    return [...auditPath(packetHashes.slice(0, k), index), mthRange(packetHashes.slice(k))];
  }
  return [...auditPath(packetHashes.slice(k), index - k), mthRange(packetHashes.slice(0, k))];
}

/**
 * Verify an inclusion proof against an RFC 6962 tree.
 *
 * RFC 6962 splits a tree of size n at `split = largest power of two < n`:
 *   MTH(D[0:n]) = node(MTH(D[0:split]), MTH(D[split:n]))
 * and an audit path is ordered leaf-to-root, so at each step the last remaining
 * path node is the sibling at that level. The recursive walk below mirrors
 * `mthRange`/`auditPath` exactly. This is why right-edge leaves of
 * non-power-of-two trees verify: an iterative `idx % 2` / `ceil(size/2)` walk
 * combines `node(leaf, sibling)` where the RFC requires `node(sibling, leaf)`
 * (e.g. n=5, index=4; n=3, index=2).
 */
function verifyInclusion(packetHash, index, treeSize, path, rootHash) {
  if (typeof packetHash !== 'string' || !HASH_RE.test(packetHash)) return false;
  if (!Number.isInteger(treeSize) || treeSize < 1) return false;
  if (!Number.isInteger(index) || index < 0 || index >= treeSize) return false;
  if (!Array.isArray(path) || path.some((node) => typeof node !== 'string' || !HASH_RE.test(node))) return false;

  const walk = (idx, size, audit) => {
    if (size === 1) return idx === 0 && audit.length === 0 ? leafHashOf(packetHash) : null;
    if (audit.length === 0) return null;
    const split = largestPowerOfTwoBelow(size);
    const sibling = audit[audit.length - 1];
    const rest = audit.slice(0, -1);
    if (idx < split) {
      const left = walk(idx, split, rest);
      return left === null ? null : nodeHashOf(left, sibling);
    }
    const right = walk(idx - split, size - split, rest);
    return right === null ? null : nodeHashOf(sibling, right);
  };

  return walk(index, treeSize, path) === rootHash;
}

/**
 * Boot-time property check: sizes 1..12 x every index.
 *
 * Each generated proof must verify against the generated root, and tampering
 * with the leaf, the root, any audit node, or (for size > 1) the claimed index
 * must be rejected. The server refuses to start if this fails: a Merkle
 * verifier that accepts a forged proof is worse than no verifier at all.
 */
function runMerkleSelfTest() {
  const leaves = Array.from({ length: 12 }, (_, i) => sha256Hex(`fieldwright-merkle-self-test-${i}`));
  const flip = (hash) => `${hash[0] === '0' ? '1' : '0'}${hash.slice(1)}`;
  const counts = { valid: 0, badLeaf: 0, badRoot: 0, badIndex: 0, badNode: 0 };
  const failures = [];

  for (let size = 1; size <= 12; size += 1) {
    const tree = leaves.slice(0, size);
    const root = mthRange(tree);
    for (let index = 0; index < size; index += 1) {
      const proof = auditPath(tree, index);
      const leaf = tree[index];

      if (verifyInclusion(leaf, index, size, proof, root)) counts.valid += 1;
      else failures.push(`valid proof rejected: size=${size} index=${index}`);

      if (!verifyInclusion(leaf, index, size, proof, flip(root))) counts.badRoot += 1;
      else failures.push(`tampered root accepted: size=${size} index=${index}`);

      if (size > 1) {
        const otherLeaf = tree[(index + 1) % size];
        if (!verifyInclusion(otherLeaf, index, size, proof, root)) counts.badLeaf += 1;
        else failures.push(`tampered leaf accepted: size=${size} index=${index}`);

        const otherIndex = (index + 1) % size;
        if (!verifyInclusion(leaf, otherIndex, size, proof, root)) counts.badIndex += 1;
        else failures.push(`tampered index accepted: size=${size} index=${index} -> ${otherIndex}`);
      }

      for (let node = 0; node < proof.length; node += 1) {
        const tampered = proof.slice();
        tampered[node] = flip(tampered[node]);
        if (!verifyInclusion(leaf, index, size, tampered, root)) counts.badNode += 1;
        else failures.push(`tampered audit node accepted: size=${size} index=${index} node=${node}`);
      }
    }
  }

  const checks = Object.values(counts).reduce((sum, value) => sum + value, 0);
  return { counts, checks, failures };
}

// ---------------------------------------------------------------------------
// API error type
// ---------------------------------------------------------------------------

class ApiError extends Error {
  constructor(status, code, message, details = null, retryable = false) {
    super(message);
    this.status = status;
    this.code = code;
    this.details = details;
    this.retryable = retryable;
  }
}

// ---------------------------------------------------------------------------
// Seed data (deterministic ids and hashes; times are fixed in the past)
// ---------------------------------------------------------------------------

const OP_ID = 'op_7d1a';
const KEY_ID = 'fw-keystore-op_7d1a-1';
const SEED_SIGNATURE = Buffer.from('fieldwright-mock-signature-not-ecdsa', 'utf8').toString('base64');
const FIXED = {
  d2: '2026-11-15T09:12:00.000Z',
  d2b: '2026-11-15T09:31:44.000Z',
  d1: '2026-11-16T10:02:11.000Z',
  d1b: '2026-11-16T10:44:31.000Z',
  today: '2026-11-17T11:40:00.000Z',
};

function makeOperator() {
  return {
    id: OP_ID,
    displayName: 'Marisol Vega',
    initials: 'MV',
    credentialTier: 3,
    tierName: 'Lead inspector',
    ratingAvg: 4.9,
    acceptanceRate: 0.82,
    onTimeRate: 0.96,
    completedJobs: 214,
    streakDays: 12,
    credentials: ['RPZ_BACKFLOW_TESTER', 'ROOF_SAFETY', 'PROPERTY_INSPECTOR'],
    payoutAccountLast4: '4412',
    weeklyGoalCents: 90_000,
    memberSinceLabel: 'Mar 2025',
  };
}

const JOB_TYPES = {
  ROOF_HAIL: {
    label: 'Roof hail assessment',
    bountyCents: 42_000,
    estimatedMinutes: 55,
    requiredCredentials: ['ROOF_SAFETY'],
    consentScope: 'Roof surfaces, slopes and penetrations. Not the interior of the home. Not the street.',
    checklist: [
      { key: 'footprint', label: 'Storm footprint', hint: 'Recorded event covers this address and date.', proofKind: 'OBSERVATION', required: true },
      { key: 'slopes', label: 'Four slopes', hint: 'North, east, south, west — one frame each, sky out of frame.', proofKind: 'PHOTO', required: true },
      { key: 'square', label: 'Test square', hint: 'One 10×10 ft test square with shingle count visible.', proofKind: 'PHOTO', required: true },
      { key: 'paddle', label: 'Measured paddle', hint: 'Calibrate against a known reference, then measure the hail strike.', proofKind: 'MEASUREMENT', required: true },
      { key: 'scope', label: 'Scope agreed', hint: 'Subject confirms the capture scope before the first frame.', proofKind: 'CONSENT', required: true },
    ],
    badges: ['STORM_ATTRIBUTION', 'FIELD_CAPTURED', 'MEASURED', 'SCOPE_AGREED', 'CONSENT_ON_FILE'],
  },
  RPZ_TEST: {
    label: 'Annual RPZ test',
    bountyCents: 9_500,
    estimatedMinutes: 35,
    requiredCredentials: ['RPZ_BACKFLOW_TESTER'],
    consentScope: 'The assembly inside the service vault. Not the building interior.',
    checklist: [
      { key: 'vault', label: 'Vault', hint: 'Inside the service vault polygon.', proofKind: 'OBSERVATION', required: true },
      { key: 'body', label: 'Body', hint: 'Assembly body and tag readable, no separate tag claimed.', proofKind: 'PHOTO', required: true },
      { key: 'gauge', label: 'Gauge', hint: 'Gauge face framed; the needle is not transcribed as a value.', proofKind: 'PHOTO', required: true },
      { key: 'cocks', label: 'Test cocks', hint: 'Test cocks seated; no weep at the relief in this frame.', proofKind: 'OBSERVATION', required: true },
      { key: 'consent', label: 'Consent', hint: 'MUD annual test authorization on file for this service.', proofKind: 'CONSENT', required: true },
    ],
    badges: ['FIELD_CAPTURED', 'MEASURED', 'CONSENT_ON_FILE'],
  },
  PROPERTY_WALK: {
    label: 'Monthly property walk',
    bountyCents: 6_500,
    estimatedMinutes: 30,
    requiredCredentials: ['PROPERTY_INSPECTOR'],
    consentScope: 'Common areas and exterior envelopes named in the property brief.',
    checklist: [
      { key: 'envelope', label: 'Envelope', hint: 'Exterior envelope from the fixed corner reference.', proofKind: 'PHOTO', required: true },
      { key: 'common', label: 'Common areas', hint: 'Walk route through named common areas only.', proofKind: 'PHOTO', required: true },
      { key: 'findings', label: 'Findings', hint: 'Every finding gets a frame and a one-line note.', proofKind: 'OBSERVATION', required: true },
      { key: 'scope', label: 'Scope agreed', hint: 'Manager confirms the route before capture starts.', proofKind: 'CONSENT', required: true },
    ],
    badges: ['FIELD_CAPTURED', 'SCOPE_AGREED', 'CONSENT_ON_FILE'],
  },
  METER_CLUSTER: {
    label: 'Meter cluster read',
    bountyCents: 2_800,
    estimatedMinutes: 15,
    requiredCredentials: [],
    consentScope: 'Exterior meter bank only. Serial and read dials.',
    checklist: [
      { key: 'bank', label: 'Bank', hint: 'Exterior meter bank, wide frame.', proofKind: 'PHOTO', required: true },
      { key: 'serial', label: 'Serial', hint: 'One frame per serial, legible without enlargement.', proofKind: 'PHOTO', required: true },
      { key: 'dials', label: 'Dials', hint: 'Read dials photographed, not typed from memory.', proofKind: 'PHOTO', required: true },
      { key: 'obstructions', label: 'Obstructions', hint: 'Note anything blocking a read.', proofKind: 'OBSERVATION', required: true },
    ],
    badges: ['FIELD_CAPTURED', 'CONSENT_ON_FILE'],
  },
  PROGRESS_PROOF: {
    label: 'Contractor progress proof',
    bountyCents: 12_000,
    estimatedMinutes: 40,
    requiredCredentials: [],
    consentScope: 'The work in scope, photographed where it stands.',
    checklist: [
      { key: 'milestone', label: 'Milestone', hint: 'The milestone named in the brief, in place.', proofKind: 'PHOTO', required: true },
      { key: 'wide', label: 'Wide frame', hint: 'Wide frame with site reference for context.', proofKind: 'PHOTO', required: true },
      { key: 'reference', label: 'Reference', hint: 'Calibrated measurement of the changed dimension.', proofKind: 'MEASUREMENT', required: true },
      { key: 'scope', label: 'Scope agreed', hint: 'Contractor confirms the stated scope.', proofKind: 'CONSENT', required: true },
    ],
    badges: ['FIELD_CAPTURED', 'MEASURED', 'SCOPE_AGREED'],
  },
  CLAIM_SWEEP: {
    label: 'Claim evidence sweep',
    bountyCents: 28_000,
    estimatedMinutes: 45,
    requiredCredentials: ['PROPERTY_INSPECTOR'],
    consentScope: 'Damage and context named on the claim. Faces and plates excluded unless consented.',
    checklist: [
      { key: 'establishing', label: 'Establishing shot', hint: 'Address or unit reference in frame.', proofKind: 'PHOTO', required: true },
      { key: 'mid', label: 'Mid shot', hint: 'The claimed item in its context.', proofKind: 'PHOTO', required: true },
      { key: 'detail', label: 'Detail shot', hint: 'The claimed damage, close.', proofKind: 'PHOTO', required: true },
      { key: 'measure', label: 'Measurement', hint: 'One calibrated measurement per claimed item.', proofKind: 'MEASUREMENT', required: true },
      { key: 'scope', label: 'Scope agreed', hint: 'Subject confirms scope and exclusions.', proofKind: 'CONSENT', required: true },
    ],
    badges: ['FIELD_CAPTURED', 'MEASURED', 'SCOPE_AGREED', 'CONSENT_ON_FILE'],
  },
};

function site(address, lat, lng, radius = 75, accessNotes = null) {
  return {
    address,
    unit: null,
    point: { lat, lng, recordedAt: FIXED.d2, accuracyMeters: 6.0, source: 'GPS' },
    geofenceRadiusMeters: radius,
    accessNotes,
    planLabel: 'Creekside · MUD 14',
  };
}

function makeJob(overrides) {
  const type = JOB_TYPES[overrides.type];
  return {
    id: overrides.id,
    type: overrides.type,
    title: overrides.title,
    requester: overrides.requester,
    site: overrides.site,
    status: overrides.status,
    bountyCents: overrides.bountyCents ?? type.bountyCents,
    bonusCents: overrides.bonusCents ?? 0,
    estimatedMinutes: type.estimatedMinutes,
    scheduledFor: overrides.scheduledFor ?? null,
    slaMinutes: overrides.slaMinutes ?? 240,
    distanceMeters: overrides.distanceMeters ?? 1_800,
    etaMinutes: overrides.etaMinutes ?? 5,
    consentScope: type.consentScope,
    checklist: type.checklist,
    requiredCredentials: overrides.requiredCredentials ?? type.requiredCredentials,
    demo: overrides.demo ?? false,
    notes: overrides.notes ?? '',
    acceptedAt: overrides.acceptedAt ?? null,
    arrivedAt: overrides.arrivedAt ?? null,
    sealedAt: overrides.sealedAt ?? null,
    packetId: overrides.packetId ?? null,
    version: 1,
  };
}

const REQUESTERS = {
  northgate: { org: 'Northgate Mutual', contactName: 'Ada Reyes', kind: 'INSURER', billingLabel: 'Northgate Mutual · claim desk' },
  mgmt: { org: 'Creekside Management', contactName: 'Tomas Okafor', kind: 'PROPERTY_MANAGER', billingLabel: 'Creekside Management · ops' },
  mud14: { org: 'MUD 14', contactName: 'Elaine Park', kind: 'UTILITY', billingLabel: 'MUD 14 · compliance' },
  build: { org: 'Ridgeline Builders', contactName: 'Sam Ortiz', kind: 'CONTRACTOR', billingLabel: 'Ridgeline Builders · draw 3' },
};

function makeBadges(required, states, reasons) {
  const all = ['STORM_ATTRIBUTION', 'FIELD_CAPTURED', 'MEASURED', 'SCOPE_AGREED', 'CONSENT_ON_FILE', 'SETTLED'];
  return all
    .filter((key) => required.includes(key) || states[key])
    .map((key) => ({
      key,
      state: states[key] ?? (required.includes(key) ? 'MISSING' : 'WAIVED'),
      required: required.includes(key),
      rationale: reasons[key] ?? '',
      evidenceRefs: [],
    }));
}

function buildSealedPacket({ id, job, media, measurements, checklist, badges, tier, seal, createdAt, sealedAt, demo, anchored, issue }) {
  const records = [];
  const push = (kind, at, summary, payload) => {
    const prevHash = records.length === 0 ? GENESIS : records[records.length - 1].hash;
    const isSeal = kind === 'ATTEST' && summary.startsWith('Sealed');
    records.push(
      makeRecord({
        seq: records.length + 1,
        kind,
        at,
        actorId: OP_ID,
        summary,
        payload,
        prevHash,
        signatureBase64: isSeal ? SEED_SIGNATURE : null,
        signerKeyId: isSeal ? KEY_ID : null,
      }),
    );
  };

  push('ATTEST', createdAt, 'Scope agreed and captured in app', { consent: 'in_app_signature', subject: job.requester.org });
  push('CAPTURE', createdAt, 'Establishing site frame', { media: media[0].id, address: job.site.address });
  push('CAPTURE', createdAt, checklist[1]?.label ?? 'Checklist frame', { media: media[1]?.id ?? media[0].id });
  if (job.type === 'ROOF_HAIL' || job.type === 'CLAIM_SWEEP' || job.type === 'PROGRESS_PROOF') {
    push('INSPECTION', createdAt, 'Calibrated measurement reference', {
      reference: measurements[0]?.referenceLabel ?? 'Shingle course',
      referenceMeters: String(measurements[0]?.referenceMeters ?? 0.9),
    });
  }
  push('CUSTODY', createdAt, 'Media hashed and stored on device', { media: media.map((m) => m.id).join(',') });
  push('ATTEST', sealedAt, 'Sealed packet signed by operator key', {
    signerKeyId: KEY_ID,
    asOf: sealedAt,
    recordCount: String(records.length + 1),
  });

  const packetHash = records[records.length - 1].hash;
  const packet = {
    id,
    jobId: job.id,
    jobType: job.type,
    operatorId: OP_ID,
    title: job.title,
    requesterLabel: job.requester.billingLabel,
    site: job.site,
    consent: {
      name: 'Dana Whitfield',
      role: job.type === 'RPZ_TEST' ? 'MUD service contact' : 'Homeowner',
      scopeText: job.consentScope,
      method: 'IN_APP_SIGNATURE',
      capturedAt: createdAt,
      signatureHash: sha256Hex(`consent:${id}:Dana Whitfield`),
      verifiedByOperatorId: OP_ID,
    },
    badges,
    tier,
    tierName: ['Reported', 'Counterparty confirmed', 'Field captured', 'Payment verified', 'Verified by AUCTUM'][tier - 1],
    seal,
    records,
    media,
    checklist,
    gates: [
      { id: 'consent_on_file', label: 'Consent on file', passed: true, detail: 'Signed in app.', blocking: true },
      { id: 'geofence', label: 'Geofence satisfied', passed: true, detail: 'Arrival 41.7 m from site point.', blocking: true },
      { id: 'redaction', label: 'Redaction resolved', passed: true, detail: 'No faces or plates in frame.', blocking: true },
      { id: 'checklist', label: 'Checklist complete', passed: true, detail: 'All required steps pass.', blocking: true },
      { id: 'calibration', label: 'Measurement calibrated', passed: true, detail: 'Reference 0.9 m shingle course.', blocking: true },
      { id: 'chain', label: 'Chain verifies', passed: true, detail: 'Hash chain verified from genesis.', blocking: true },
    ],
    measurements,
    notes: issue ?? '',
    demo,
    createdAt,
    sealedAt,
    merkleLeaf: anchored ? leafHashOf(packetHash) : null,
    merkleProof: [],
    asOf: sealedAt,
    signatureBase64: SEED_SIGNATURE,
    packetHash,
    shortHash: packetHash.slice(0, 8),
    recordCount: records.length,
    version: 1,
  };
  return packet;
}

function seedWorld() {
  const operator = makeOperator();
  const availability = {
    isOnline: true,
    updatedAt: FIXED.today,
    radiusMeters: 15_000,
    autoAccept: {
      enabled: true,
      minPayoutCents: 3_000,
      maxDistanceMeters: 12_000,
      maxActiveStack: 2,
      allowedTypes: ['ROOF_HAIL', 'RPZ_TEST', 'PROPERTY_WALK', 'METER_CLUSTER', 'PROGRESS_PROOF', 'CLAIM_SWEEP'],
      requireCredentialMatch: true,
    },
    areaLabel: 'Creekside · MUD 14',
  };

  const jobs = new Map();
  const offers = new Map();
  const packets = new Map();
  const ledger = [];
  const payouts = [];
  const log = [];
  const versions = new Map();

  const jobList = [
    makeJob({
      id: 'job_fw1048',
      type: 'ROOF_HAIL',
      title: '14 Creekside Row — south slope',
      requester: REQUESTERS.northgate,
      site: site('14 Creekside Row', 30.2672, -97.7431, 75, 'Gate code 4412'),
      status: 'SEALED',
      bountyCents: 42_000,
      bonusCents: 3_000,
      distanceMeters: 1_240,
      etaMinutes: 4,
      acceptedAt: FIXED.today,
      arrivedAt: '2026-11-17T11:52:00.000Z',
      sealedAt: FIXED.today,
      notes: 'Waiting for outbox upload.',
    }),
    makeJob({
      id: 'job_fw0994',
      type: 'ROOF_HAIL',
      title: '9 Alder Bend — test square and paddle',
      requester: REQUESTERS.northgate,
      site: site('9 Alder Bend', 30.2711, -97.7399, 80, 'Dog in back yard'),
      status: 'APPROVED',
      bountyCents: 42_000,
      bonusCents: 3_000,
      distanceMeters: 2_100,
      etaMinutes: 6,
      acceptedAt: FIXED.d2,
      arrivedAt: FIXED.d2b,
      sealedAt: FIXED.d2b,
      packetId: 'pkt_fw0994',
      notes: '',
    }),
    makeJob({
      id: 'job_fw1012',
      type: 'RPZ_TEST',
      title: 'Creekside MUD 14 — RPZ station 6',
      requester: REQUESTERS.mud14,
      site: site('Creekside MUD 14 · vault 6', 30.2655, -97.7510, 60),
      status: 'PAID',
      bountyCents: 9_500,
      distanceMeters: 3_200,
      etaMinutes: 9,
      acceptedAt: FIXED.d2,
      arrivedAt: FIXED.d2b,
      sealedAt: FIXED.d2b,
      packetId: 'pkt_fw1012',
    }),
    makeJob({
      id: 'job_fw1051',
      type: 'PROPERTY_WALK',
      title: 'Alder Court — monthly walk',
      requester: REQUESTERS.mgmt,
      site: site('Alder Court · common areas', 30.2740, -97.7462, 120, 'Office key at leasing desk'),
      status: 'SCHEDULED',
      scheduledFor: isoAt(Date.now() + 2 * 60 * 60 * 1000),
      distanceMeters: 4_400,
      etaMinutes: 12,
    }),
  ];
  for (const job of jobList) jobs.set(job.id, job);

  const available = [
    makeJob({
      id: 'job_avail_2201',
      type: 'METER_CLUSTER',
      title: 'Birch Hollow — 12-meter bank',
      requester: REQUESTERS.mud14,
      site: site('Birch Hollow Dr · meter bank C', 30.2812, -97.7301, 50),
      status: 'AVAILABLE',
      distanceMeters: 900,
      etaMinutes: 3,
    }),
    makeJob({
      id: 'job_avail_2202',
      type: 'PROPERTY_WALK',
      title: 'Larkspur Flats — envelope walk',
      requester: REQUESTERS.mgmt,
      site: site('Larkspur Flats · buildings A–C', 30.2588, -97.7288, 150),
      status: 'AVAILABLE',
      distanceMeters: 2_400,
      etaMinutes: 7,
    }),
    makeJob({
      id: 'job_avail_2203',
      type: 'CLAIM_SWEEP',
      title: '62 Creekside Row — claim sweep',
      requester: REQUESTERS.northgate,
      site: site('62 Creekside Row', 30.2689, -97.7450, 75),
      status: 'AVAILABLE',
      bountyCents: 28_000,
      distanceMeters: 1_700,
      etaMinutes: 5,
    }),
    makeJob({
      id: 'job_avail_2204',
      type: 'ROOF_HAIL',
      title: '5 Alder Bend — north and west slopes',
      requester: REQUESTERS.northgate,
      site: site('5 Alder Bend', 30.2703, -97.7406, 80),
      status: 'AVAILABLE',
      bountyCents: 42_000,
      bonusCents: 3_000,
      distanceMeters: 2_000,
      etaMinutes: 6,
    }),
    makeJob({
      id: 'job_avail_2205',
      type: 'CLAIM_SWEEP',
      title: 'Creekside Row — aerial context sweep',
      requester: REQUESTERS.northgate,
      site: site('Creekside Row · lots 40–44', 30.2660, -97.7440, 200),
      status: 'AVAILABLE',
      bountyCents: 28_000,
      distanceMeters: 1_500,
      etaMinutes: 4,
      requiredCredentials: ['PROPERTY_INSPECTOR', 'DRONE_PILOT'],
      notes: 'Part 107 pilot requested for aerial context.',
    }),
  ];
  for (const job of available) jobs.set(job.id, job);

  // Two live offers, expiring 45 s after boot.
  const offerExpiry = Date.now() + OFFER_TTL_MS;
  const offer1 = {
    id: 'off_1021',
    job: jobs.get('job_avail_2204'),
    operatorId: OP_ID,
    payoutCents: Math.round(45_000 * 1.15),
    surgeMultiplier: 1.15,
    offeredAt: isoAt(Date.now() - 5_000),
    expiresAt: isoAt(offerExpiry),
    score: 0.87,
    scoreReasons: ['0.9 km detour', '$51.75/hr estimated', 'ROOF credential matched'],
    etaMinutes: 6,
    autoAcceptEligible: true,
    status: 'PENDING',
  };
  const offer2 = {
    id: 'off_1022',
    job: jobs.get('job_avail_2201'),
    operatorId: OP_ID,
    payoutCents: 2_800,
    surgeMultiplier: 1.0,
    offeredAt: isoAt(Date.now() - 8_000),
    expiresAt: isoAt(offerExpiry),
    score: 0.74,
    scoreReasons: ['0.4 km detour', 'no credential requirement', 'meter route density high'],
    etaMinutes: 3,
    autoAcceptEligible: false,
    status: 'PENDING',
  };
  offers.set(offer1.id, offer1);
  offers.set(offer2.id, offer2);
  for (const offer of offers.values()) {
    const job = jobs.get(offer.job.id);
    job.status = 'OFFERED';
    jobs.set(job.id, job);
  }

  // Sealed packets -----------------------------------------------------------
  const roofMedia = [
    { id: 'med_0994_01', kind: 'PHOTO', sha256: sha256Hex('frame:0994:establishing'), bytes: 184_320, capturedAt: FIXED.d2, width: 3024, height: 4032, redaction: 'NOT_NEEDED', source: 'CAMERA', note: 'Establishing frame; faces and plates excluded.' },
    { id: 'med_0994_02', kind: 'PHOTO', sha256: sha256Hex('frame:0994:south-slope'), bytes: 201_984, capturedAt: FIXED.d2, width: 3024, height: 4032, redaction: 'NOT_NEEDED', source: 'CAMERA', note: 'South slope, sky out of frame.' },
    { id: 'med_0994_03', kind: 'PHOTO', sha256: sha256Hex('frame:0994:test-square'), bytes: 176_224, capturedAt: FIXED.d2b, width: 3024, height: 4032, redaction: 'NOT_NEEDED', source: 'CAMERA', note: '10×10 ft test square.' },
  ];
  const roofMeasurements = [
    { id: 'msr_0994_01', label: 'Hail strike on south slope', meters: 0.041, referenceLabel: 'Shingle course', referenceMeters: 0.9, pixelSpan: 142.0, calibratedAt: FIXED.d2b, source: 'CAMERA', feet: 0.134 },
  ];
  const roofChecklist = JOB_TYPES.ROOF_HAIL.checklist.map((step, i) => ({
    key: step.key,
    result: 'PASS',
    note: step.key === 'paddle' ? 'Measured 1.6 in hail strike.' : '',
    mediaIds: [roofMedia[i % roofMedia.length].id],
  }));
  const roofBadges = makeBadges(JOB_TYPES.ROOF_HAIL.badges, {
    STORM_ATTRIBUTION: 'VERIFIED', FIELD_CAPTURED: 'VERIFIED', MEASURED: 'VERIFIED',
    SCOPE_AGREED: 'VERIFIED', CONSENT_ON_FILE: 'VERIFIED',
  }, {
    STORM_ATTRIBUTION: 'Inside the 2026-11-03 hail footprint (radius 8 km).',
    FIELD_CAPTURED: 'Four slopes captured on site at 09:12–09:31.',
    MEASURED: 'Calibrated against a 0.9 m shingle course.',
    SCOPE_AGREED: 'Subject signed the stated scope in app.',
    CONSENT_ON_FILE: 'Signed in app; signature hash stored.',
  });
  const pkt0994 = buildSealedPacket({
    id: 'pkt_fw0994',
    job: jobs.get('job_fw0994'),
    media: roofMedia,
    measurements: roofMeasurements,
    checklist: roofChecklist,
    badges: roofBadges,
    tier: 5,
    seal: 'EARNED',
    createdAt: FIXED.d2,
    sealedAt: FIXED.d2b,
    demo: false,
    anchored: true,
    issue: '',
  });
  packets.set(pkt0994.id, pkt0994);

  const rpzMedia = [
    { id: 'med_1012_01', kind: 'PHOTO', sha256: sha256Hex('frame:1012:vault'), bytes: 154_112, capturedAt: FIXED.d2, width: 3024, height: 4032, redaction: 'NOT_NEEDED', source: 'CAMERA', note: 'Vault polygon.' },
    { id: 'med_1012_02', kind: 'PHOTO', sha256: sha256Hex('frame:1012:body-tag'), bytes: 160_768, capturedAt: FIXED.d2, width: 3024, height: 4032, redaction: 'NOT_NEEDED', source: 'CAMERA', note: 'Body tag legible.' },
    { id: 'med_1012_03', kind: 'PHOTO', sha256: sha256Hex('frame:1012:gauge'), bytes: 149_504, capturedAt: FIXED.d2b, width: 3024, height: 4032, redaction: 'NOT_NEEDED', source: 'CAMERA', note: 'Gauge face; needle not transcribed.' },
  ];
  const rpzChecklist = JOB_TYPES.RPZ_TEST.checklist.map((step, i) => ({
    key: step.key,
    result: 'PASS',
    note: '',
    mediaIds: [rpzMedia[i % rpzMedia.length].id],
  }));
  const rpzBadges = makeBadges(JOB_TYPES.RPZ_TEST.badges, {
    FIELD_CAPTURED: 'VERIFIED', MEASURED: 'VERIFIED', CONSENT_ON_FILE: 'VERIFIED',
  }, {
    FIELD_CAPTURED: 'Vault, body, gauge and cocks captured on site.',
    MEASURED: 'Gauge framed; no needle transcription.',
    CONSENT_ON_FILE: 'MUD annual test authorization on file.',
  });
  const pkt1012 = buildSealedPacket({
    id: 'pkt_fw1012',
    job: jobs.get('job_fw1012'),
    media: rpzMedia,
    measurements: [
      { id: 'msr_1012_01', label: 'Vault clearance', meters: 0.62, referenceLabel: 'Site card (30 cm)', referenceMeters: 0.3, pixelSpan: 210.0, calibratedAt: FIXED.d2b, source: 'CAMERA', feet: 2.034 },
    ],
    checklist: rpzChecklist,
    badges: rpzBadges,
    tier: 5,
    seal: 'EARNED',
    createdAt: FIXED.d2,
    sealedAt: FIXED.d2b,
    demo: false,
    anchored: true,
    issue: '',
  });
  packets.set(pkt1012.id, pkt1012);

  // An attention packet with a flagged measurement badge, and a demo packet.
  const claimMedia = [
    { id: 'med_1060_01', kind: 'PHOTO', sha256: sha256Hex('frame:1060:establishing'), bytes: 190_000, capturedAt: FIXED.d1, width: 3024, height: 4032, redaction: 'NOT_NEEDED', source: 'CAMERA', note: 'Establishing frame.' },
    { id: 'med_1060_02', kind: 'PHOTO', sha256: sha256Hex('frame:1060:detail'), bytes: 188_000, capturedAt: FIXED.d1, width: 3024, height: 4032, redaction: 'REQUIRED', source: 'CAMERA', note: 'Plate in frame; redaction pending.' },
  ];
  const claimBadges = makeBadges(JOB_TYPES.CLAIM_SWEEP.badges, {
    FIELD_CAPTURED: 'VERIFIED', MEASURED: 'FLAGGED', SCOPE_AGREED: 'VERIFIED', CONSENT_ON_FILE: 'VERIFIED',
  }, {
    FIELD_CAPTURED: 'Establishing, mid and detail frames captured.',
    MEASURED: 'Flagged: measurement reference not visible in the detail frame.',
    SCOPE_AGREED: 'Subject signed the stated scope.',
    CONSENT_ON_FILE: 'Consent on file.',
  });
  const claimJob = makeJob({
    id: 'job_fw1060',
    type: 'CLAIM_SWEEP',
    title: '62 Creekside Row — claim sweep (redaction pending)',
    requester: REQUESTERS.northgate,
    site: site('62 Creekside Row', 30.2689, -97.7450, 75),
    status: 'UNDER_REVIEW',
    sealedAt: FIXED.d1b,
    acceptedAt: FIXED.d1,
    arrivedAt: FIXED.d1b,
    distanceMeters: 1_700,
    etaMinutes: 5,
  });
  jobs.set(claimJob.id, claimJob);
  const pkt1060 = buildSealedPacket({
    id: 'pkt_fw1060',
    job: claimJob,
    media: claimMedia,
    measurements: [],
    checklist: JOB_TYPES.CLAIM_SWEEP.checklist.map((step) => ({ key: step.key, result: 'PASS', note: '', mediaIds: [claimMedia[0].id] })),
    badges: claimBadges,
    tier: 4,
    seal: 'FLAGGED',
    createdAt: FIXED.d1,
    sealedAt: FIXED.d1b,
    demo: false,
    anchored: true,
    issue: 'Plate visible in detail frame; redaction must resolve before export.',
  });
  pkt1060.media[1].redaction = 'REQUIRED';
  packets.set(pkt1060.id, pkt1060);

  const demoJob = makeJob({
    id: 'job_fw1055',
    type: 'PROPERTY_WALK',
    title: 'Sample book — envelope walk',
    requester: REQUESTERS.mgmt,
    site: site('Sample site · not a live address', 30.26, -97.74, 100),
    status: 'APPROVED',
    bountyCents: 6_500,
    distanceMeters: 0,
    etaMinutes: 0,
    demo: true,
  });
  jobs.set(demoJob.id, demoJob);
  const demoPacket = buildSealedPacket({
    id: 'pkt_fw1055',
    job: demoJob,
    media: [{
      id: 'med_1055_01', kind: 'PHOTO', sha256: sha256Hex('frame:1055:sample'), bytes: 120_000,
      capturedAt: FIXED.d2, width: 1080, height: 1920, redaction: 'NOT_NEEDED', source: 'SIMULATED',
      note: 'Simulated capture; demo only.',
    }],
    measurements: [],
    checklist: JOB_TYPES.PROPERTY_WALK.checklist.map((step) => ({ key: step.key, result: 'PASS', note: 'Sample', mediaIds: ['med_1055_01'] })),
    badges: makeBadges(JOB_TYPES.PROPERTY_WALK.badges, {
      FIELD_CAPTURED: 'VERIFIED', SCOPE_AGREED: 'VERIFIED', CONSENT_ON_FILE: 'VERIFIED',
    }, { FIELD_CAPTURED: 'Sample', SCOPE_AGREED: 'Sample', CONSENT_ON_FILE: 'Sample' }),
    tier: 5,
    seal: 'EARNED',
    createdAt: FIXED.d2,
    sealedAt: FIXED.d2b,
    demo: true,
    anchored: false,
    issue: 'SAMPLE — excluded from log.',
  });
  demoPacket.media[0].source = 'SIMULATED';
  packets.set(demoPacket.id, demoPacket);

  // Ledger + payouts ---------------------------------------------------------
  ledger.push(
    { id: 'led_0994_earning', jobId: 'job_fw0994', packetId: 'pkt_fw0994', kind: 'EARNING', amountCents: 42_000, status: 'AVAILABLE', at: FIXED.d2b, description: 'Roof hail assessment · 9 Alder Bend', idempotencyKey: 'idem-seed-fw0994', demo: false },
    { id: 'led_0994_bonus', jobId: 'job_fw0994', packetId: 'pkt_fw0994', kind: 'BONUS', amountCents: 3_000, status: 'AVAILABLE', at: FIXED.d2b, description: 'Surge bonus · 1.15×', idempotencyKey: 'idem-seed-fw0994-bonus', demo: false },
    { id: 'led_1012_earning', jobId: 'job_fw1012', packetId: 'pkt_fw1012', kind: 'EARNING', amountCents: 9_500, status: 'PAID', at: FIXED.d2b, description: 'Annual RPZ test · MUD 14 station 6', idempotencyKey: 'idem-seed-fw1012', demo: false },
    { id: 'led_1012_instantfee', jobId: 'job_fw1012', packetId: 'pkt_fw1012', kind: 'INSTANT_FEE', amountCents: -143, status: 'PAID', at: FIXED.d2b, description: 'Instant payout fee · 1.5% (min $0.50, max $5.00)', idempotencyKey: 'idem-seed-fw1012-fee', demo: false },
    { id: 'led_1060_pending', jobId: 'job_fw1060', packetId: 'pkt_fw1060', kind: 'EARNING', amountCents: 28_000, status: 'PENDING', at: FIXED.d1b, description: 'Claim evidence sweep · 62 Creekside Row', idempotencyKey: 'idem-seed-fw1060', demo: false },
  );
  payouts.push(
    {
      id: 'pay_1012', amountCents: 9_500, feeCents: 143, netCents: 9_357, mode: 'INSTANT',
      status: 'PAID', requestedAt: FIXED.d2b, completedAt: FIXED.d2b, destinationLast4: '4412',
      idempotencyKey: 'idem-seed-payout-1012', failureReason: null,
    },
    {
      id: 'pay_0999', amountCents: 5_000, feeCents: 0, netCents: 5_000, mode: 'STANDARD',
      status: 'FAILED', requestedAt: FIXED.d1, completedAt: FIXED.d1, destinationLast4: '4412',
      idempotencyKey: 'idem-seed-payout-0999',
      failureReason: 'Destination account closed — update the payout account in Brief.',
    },
  );

  // Log: anchored packets, oldest first. Demo packets never enter the log.
  const anchored = [...packets.values()]
    .filter((p) => p.merkleLeaf && !p.demo)
    .sort((a, b) => a.sealedAt.localeCompare(b.sealedAt));
  for (const packet of anchored) log.push(packet.packetHash);

  const logHead = () => ({
    treeSize: log.length,
    rootHash: log.length === 0 ? sha256Hex('') : mthRange(log),
    asOf: nowIso(),
  });

  // Fill inclusion proofs for anchored packets.
  for (const packet of anchored) {
    const index = log.indexOf(packet.packetHash);
    packet.merkleProof = index >= 0 ? auditPath(log, index) : [];
    packet.merkleIndex = index;
  }

  return {
    operator,
    availability,
    jobs,
    offers,
    packets,
    ledger,
    payouts,
    log,
    logHead,
    versions,
    availabilityVersion: 1,
    idempotency: new Map(),
    tokens: new Map(),
    rate: new Map(),
  };
}

// ---------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------

const world = seedWorld();
const startedAt = nowIso();

// ---------------------------------------------------------------------------
// Derived views
// ---------------------------------------------------------------------------

function balancesView() {
  const available = world.ledger.filter((e) => e.status === 'AVAILABLE').reduce((sum, e) => sum + e.amountCents, 0);
  const pending = world.ledger.filter((e) => e.status === 'PENDING').reduce((sum, e) => sum + e.amountCents, 0);
  const paid = world.ledger.filter((e) => e.status === 'PAID' && (e.kind === 'EARNING' || e.kind === 'BONUS' || e.kind === 'TIP')).reduce((sum, e) => sum + e.amountCents, 0);
  const paidFees = world.ledger.filter((e) => e.status === 'PAID' && e.kind === 'INSTANT_FEE').reduce((sum, e) => sum + Math.abs(e.amountCents), 0);
  return {
    availableCents: available,
    pendingCents: pending,
    paidLifetimeCents: paid,
    instantFeePaidLifetimeCents: paidFees,
    currentStreakDays: world.operator.streakDays,
    weeklyGoalCents: world.operator.weeklyGoalCents,
  };
}

function earningsView() {
  const week = world.ledger
    .filter((e) => e.status !== 'REVERSED' && (e.kind === 'EARNING' || e.kind === 'BONUS' || e.kind === 'TIP'))
    .reduce((sum, e) => sum + e.amountCents, 0);
  return {
    todayCents: 0,
    weekCents: week,
    monthCents: week + 9_500,
    onlineMinutesToday: 0,
    jobsCompletedWeek: 2,
    milesDrivenWeek: 41.2,
    bonusesWeekCents: 3_000,
    tipsWeekCents: 0,
    platformFeeWeekCents: 0,
    mileageDeductionCents: Math.round(41.2 * MILEAGE_CENTS_PER_MILE),
  };
}

function packetView(packet) {
  const { merkleIndex, ...rest } = packet;
  return rest;
}

function expireOffers(now = Date.now()) {
  let changed = 0;
  for (const offer of world.offers.values()) {
    if (offer.status !== 'PENDING') continue;
    if (Date.parse(offer.expiresAt) > now) continue;
    offer.status = 'EXPIRED';
    changed += 1;
    const job = world.jobs.get(offer.job.id);
    if (job && job.status === 'OFFERED') {
      job.status = 'AVAILABLE';
      job.version += 1;
    }
  }
  return changed;
}

function offersView() {
  expireOffers();
  return [...world.offers.values()]
    .filter((offer) => offer.status === 'PENDING')
    .sort((a, b) => b.score - a.score);
}

function activeJobCount() {
  let count = 0;
  for (const job of world.jobs.values()) {
    if (ACTIVE_STATUSES.has(job.status)) count += 1;
  }
  return count;
}

function credentialsMissing(job) {
  const have = new Set(world.operator.credentials);
  return (job.requiredCredentials ?? []).filter((credential) => !have.has(credential));
}

function shortestFixFor(credential) {
  const fixes = {
    RPZ_BACKFLOW_TESTER: 'Upload your RPZ/backflow tester certificate in Brief › Credentials.',
    ROOF_SAFETY: 'Upload your roof-safety certificate in Brief › Credentials.',
    PROPERTY_INSPECTOR: 'Upload your property-inspector credential in Brief › Credentials.',
    NOTARY: 'Upload your notary commission in Brief › Credentials.',
    DRONE_PILOT: 'Upload your Part 107 remote-pilot certificate in Brief › Credentials.',
  };
  return fixes[credential] ?? 'Upload the required credential in Brief › Credentials.';
}

function getJobOr404(jobId) {
  const job = world.jobs.get(jobId);
  if (!job) throw new ApiError(404, 'NOT_FOUND', `No job ${jobId}.`);
  return job;
}

function getPacketOr404(packetId) {
  const packet = world.packets.get(packetId);
  if (!packet) throw new ApiError(404, 'NOT_FOUND', `No packet ${packetId}.`);
  return packet;
}

function requireJobStatus(job, allowed, action) {
  if (!allowed.includes(job.status)) {
    throw new ApiError(409, 'JOB_STATE_INVALID', `Job ${job.id} is ${job.status}; ${action} requires ${allowed.join(' or ')}.`, {
      currentStatus: job.status,
      allowed,
    });
  }
}

function bumpJob(job) {
  job.version += 1;
  world.versions.set(`job:${job.id}`, job.version);
  return job;
}

// ---------------------------------------------------------------------------
// Idempotency, ETag/version and rate-limit helpers
// ---------------------------------------------------------------------------

function fingerprintOf(req, body) {
  const path = req.url.split('?')[0];
  return sha256Hex(`${req.method} ${path} ${canonicalJson(body ?? null)}`);
}

function idempotentResult(req, body, handler) {
  const key = req.headers['idempotency-key'];
  if (typeof key !== 'string' || key.length < 8 || key.length > 128) {
    throw new ApiError(400, 'IDEMPOTENCY_KEY_REQUIRED', 'Idempotency-Key header (8-128 chars) is required for this request.');
  }
  const fingerprint = fingerprintOf(req, body);
  const previous = world.idempotency.get(key);
  if (previous) {
    if (previous.fingerprint !== fingerprint) {
      throw new ApiError(422, 'IDEMPOTENCY_KEY_REUSED', 'This Idempotency-Key was already used with a different request body.', {
        idempotencyKey: key,
      });
    }
    return {
      status: previous.status,
      body: previous.body,
      headers: { ...(previous.headers ?? {}), 'Idempotent-Replayed': 'true' },
    };
  }
  const result = handler();
  world.idempotency.set(key, { fingerprint, status: result.status, body: result.body, headers: result.headers ?? {} });
  return { ...result, headers: { ...(result.headers ?? {}), 'Idempotent-Replayed': 'false' } };
}

function checkVersion(req, resourceVersion) {
  if (!req || typeof req.headers !== 'object') return;
  const ifMatch = req.headers['if-match'];
  if (typeof ifMatch === 'string' && ifMatch.trim() !== '') {
    const expected = etagOf(resourceVersion);
    if (ifMatch.trim() !== expected) {
      throw new ApiError(412, 'PRECONDITION_FAILED', `If-Match ${ifMatch} does not match the current version ${expected}.`, {
        currentETag: expected,
      });
    }
  }
  const baseVersion = req.headers['x-fw-base-version'];
  if (typeof baseVersion === 'string' && baseVersion.trim() !== '') {
    const parsed = Number.parseInt(baseVersion, 10);
    if (!Number.isInteger(parsed) || parsed !== resourceVersion) {
      throw new ApiError(409, 'VERSION_CONFLICT', 'The resource changed since this client read it. Refetch and re-apply the intent.', {
        currentVersion: resourceVersion,
        baseVersion: baseVersion,
      });
    }
  }
}

function bucketFor(method, path) {
  if (path.includes('/payouts/quote')) return { name: 'payouts-quote', limit: 30 };
  if (path.includes('/offers/') && path.endsWith('/accept')) return { name: 'offers-accept', limit: 12 };
  if (path.includes('/packets')) return { name: 'packets', limit: 20 };
  if (path.includes('/payouts')) return { name: 'payouts', limit: 6 };
  if (path.includes('/sync/batch')) return { name: 'sync', limit: 60 };
  return { name: 'default', limit: 120 };
}

function checkRateLimit(token, method, path) {
  const bucket = bucketFor(method, path);
  const key = `${token}:${bucket.name}`;
  const windowMs = 60_000;
  const now = Date.now();
  const current = world.rate.get(key);
  if (!current || now - current.windowStart >= windowMs) {
    world.rate.set(key, { windowStart: now, count: 1 });
    return { limit: bucket.limit, remaining: bucket.limit - 1, resetSeconds: 60 };
  }
  current.count += 1;
  const resetSeconds = Math.max(1, Math.ceil((current.windowStart + windowMs - now) / 1000));
  if (current.count > bucket.limit) {
    const error = new ApiError(429, 'RATE_LIMITED', `Rate limit for ${bucket.name} exhausted.`, { bucket: bucket.name, limit: bucket.limit });
    error.headers = { 'Retry-After': String(resetSeconds) };
    throw error;
  }
  return { limit: bucket.limit, remaining: Math.max(0, bucket.limit - current.count), resetSeconds };
}

// ---------------------------------------------------------------------------
// HTTP plumbing
// ---------------------------------------------------------------------------

const requestCounters = { total: 0 };

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on('data', (chunk) => {
      size += chunk.length;
      if (size > MAX_BODY_BYTES) {
        reject(new ApiError(413, 'VALIDATION_FAILED', 'Request body exceeds 5 MB.'));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => {
      const raw = Buffer.concat(chunks).toString('utf8');
      if (!raw) return resolve(null);
      try {
        resolve(JSON.parse(raw));
      } catch {
        reject(new ApiError(400, 'VALIDATION_FAILED', 'Request body is not valid JSON.'));
      }
    });
    req.on('error', reject);
  });
}

function sendJson(res, status, body, headers = {}) {
  const payload = body === null || body === undefined ? '' : JSON.stringify(body);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'X-Request-Id': res.locals.requestId,
    'Cache-Control': 'no-store',
    ...res.locals.rateHeaders,
    ...headers,
  });
  res.end(payload);
}

function sendError(res, error) {
  const body = {
    error: {
      code: error.code ?? 'INTERNAL',
      message: error.message ?? 'Unexpected error.',
      retryable: Boolean(error.retryable),
      requestId: res.locals.requestId,
      ...(error.details ? { details: error.details } : {}),
    },
  };
  const headers = {
    ...(error.headers ?? {}),
  };
  sendJson(res, error.status ?? 500, body, headers);
}

// ---------------------------------------------------------------------------
// Domain actions
// ---------------------------------------------------------------------------

function actionAcceptOffer(offerId) {
  const offer = world.offers.get(offerId);
  if (!offer) throw new ApiError(404, 'NOT_FOUND', `No offer ${offerId}.`);
  if (offer.operatorId !== OP_ID) throw new ApiError(403, 'FORBIDDEN', 'This offer is not yours.');
  if (offer.status === 'EXPIRED' || Date.parse(offer.expiresAt) <= Date.now()) {
    if (offer.status === 'PENDING') {
      offer.status = 'EXPIRED';
      const job = world.jobs.get(offer.job.id);
      if (job && job.status === 'OFFERED') { job.status = 'AVAILABLE'; job.version += 1; }
    }
    throw new ApiError(409, 'OFFER_EXPIRED', 'This offer expired before acceptance.', { expiresAt: offer.expiresAt });
  }
  if (offer.status === 'WITHDRAWN' || offer.status === 'ACCEPTED') {
    throw new ApiError(409, 'OFFER_TAKEN', 'This offer is no longer available.', { offerStatus: offer.status });
  }
  if (offer.status !== 'PENDING') {
    throw new ApiError(409, 'JOB_STATE_INVALID', `Offer is ${offer.status}.`, { offerStatus: offer.status });
  }
  const job = getJobOr404(offer.job.id);
  requireJobStatus(job, ['OFFERED', 'AVAILABLE'], 'accept');
  const missing = credentialsMissing(job);
  if (missing.length > 0) {
    throw new ApiError(403, 'CREDENTIAL_REQUIRED', `This job requires ${missing.join(', ')}.`, {
      missingCredentials: missing,
      credential: missing[0],
      shortestFix: shortestFixFor(missing[0]),
    });
  }
  if (activeJobCount() >= MAX_ACTIVE_JOBS) {
    throw new ApiError(409, 'JOB_STATE_INVALID', `You already have ${MAX_ACTIVE_JOBS} active jobs.`, {
      reason: 'MAX_ACTIVE_JOBS',
      maxActiveJobs: MAX_ACTIVE_JOBS,
    });
  }
  offer.status = 'ACCEPTED';
  job.status = 'ACCEPTED';
  job.acceptedAt = nowIso();
  bumpJob(job);
  for (const other of world.offers.values()) {
    if (other.id !== offer.id && other.job.id === job.id && other.status === 'PENDING') {
      other.status = 'WITHDRAWN';
    }
  }
  return { status: 200, body: { offer, job, acceptedAt: job.acceptedAt } };
}

function actionDeclineOffer(offerId, body) {
  const offer = world.offers.get(offerId);
  if (!offer) throw new ApiError(404, 'NOT_FOUND', `No offer ${offerId}.`);
  if (offer.operatorId !== OP_ID) throw new ApiError(403, 'FORBIDDEN', 'This offer is not yours.');
  requireString(body?.reason, 'reason', 200);
  if (offer.status === 'PENDING') {
    offer.status = 'DECLINED';
    const job = world.jobs.get(offer.job.id);
    if (job && job.status === 'OFFERED') { job.status = 'AVAILABLE'; job.version += 1; }
  }
  return { status: 204, body: null };
}

function actionEnRoute(jobId, req) {
  const job = getJobOr404(jobId);
  checkVersion(req, job.version);
  requireJobStatus(job, ['ACCEPTED'], 'going en route');
  job.status = 'EN_ROUTE';
  bumpJob(job);
  return { status: 200, body: job };
}

function actionArrival(jobId, body, req) {
  const job = getJobOr404(jobId);
  checkVersion(req, job.version);
  requireJobStatus(job, ['EN_ROUTE'], 'arrival');
  const fix = body?.fix;
  if (!fix || typeof fix !== 'object') throw new ApiError(400, 'VALIDATION_FAILED', 'fix is required.');
  if (typeof fix.lat !== 'number' || typeof fix.lng !== 'number') {
    throw new ApiError(400, 'VALIDATION_FAILED', 'fix.lat and fix.lng must be numbers.');
  }
  requireTimestamp(fix.recordedAt, 'fix.recordedAt');
  requireEnum(fix.source, ['GPS', 'NETWORK', 'SIMULATED'], 'fix.source');
  const source = fix.source;
  if (source === 'SIMULATED' && !job.demo) {
    throw new ApiError(409, 'JOB_STATE_INVALID', 'A simulated fix cannot confirm arrival for a live job.', {
      reason: 'SIMULATED_FIX_NOT_ADMISSIBLE',
    });
  }
  if (typeof fix.accuracyMeters !== 'number' || fix.accuracyMeters < 0) {
    throw new ApiError(400, 'VALIDATION_FAILED', 'fix.accuracyMeters must be a non-negative number.');
  }
  const distanceMeters = Number(haversineMeters(fix, job.site.point).toFixed(1));
  const accuracyMeters = Number(fix.accuracyMeters.toFixed(1));
  const withinTolerance = distanceMeters <= job.site.geofenceRadiusMeters + Math.min(accuracyMeters, 100) && accuracyMeters <= 100;
  const overrideReason = optionalString(body?.overrideReason, 'overrideReason', 300);
  if (withinTolerance) {
    job.status = 'ON_SITE';
    job.arrivedAt = nowIso();
    bumpJob(job);
    return {
      status: 200,
      body: {
        accepted: true,
        insideGeofence: true,
        distanceMeters,
        accuracyMeters,
        detail: `Inside the ${job.site.geofenceRadiusMeters} m geofence.`,
        overrideRequired: false,
      },
    };
  }
  if (!overrideReason) {
    return {
      status: 200,
      body: {
        accepted: false,
        insideGeofence: false,
        distanceMeters,
        accuracyMeters,
        detail: `Outside the ${job.site.geofenceRadiusMeters} m geofence by ${Math.round(distanceMeters - job.site.geofenceRadiusMeters)} m. Supply a reason to override; the packet will be flagged for review.`,
        overrideRequired: true,
      },
    };
  }
  job.status = 'ON_SITE';
  job.arrivedAt = nowIso();
  job.notes = `${job.notes ? `${job.notes} ` : ''}Arrival override: ${overrideReason}`;
  bumpJob(job);
  return {
    status: 200,
    body: {
      accepted: true,
      insideGeofence: false,
      distanceMeters,
      accuracyMeters,
      detail: `Arrival accepted by override: ${overrideReason}. Packet flagged for review.`,
      overrideRequired: false,
    },
  };
}

function actionStartCapture(jobId, req) {
  const job = getJobOr404(jobId);
  checkVersion(req, job.version);
  requireJobStatus(job, ['ON_SITE'], 'starting capture');
  job.status = 'IN_PROGRESS';
  bumpJob(job);
  return { status: 200, body: job };
}

function deriveTierFromSubmission(body, chainResult) {
  const records = body.records ?? [];
  const kinds = new Set(records.map((r) => r.kind));
  const required = new Set(JOB_TYPES[body.jobType]?.badges ?? []);
  const badges = (body.badges ?? []).map((badge) => ({
    ...badge,
    required: required.has(badge.key),
  }));
  const haveAll = [...required].every((key) => badges.some((b) => b.key === key && b.state === 'VERIFIED'));
  const flagged = badges.some((b) => b.state === 'FLAGGED');
  let tier = 1;
  if (kinds.has('ATTEST')) tier = 2;
  if (kinds.has('CAPTURE')) tier = Math.max(tier, 3);
  if (kinds.has('PAYMENT')) tier = Math.max(tier, 4);
  // A packet seals on the evidence it carries, not on when the money arrives: all required
  // badges verified, nothing flagged and a valid chain is the server's seal derivation.
  if (haveAll && !flagged && chainResult.valid) tier = 5;
  const seal = flagged ? 'FLAGGED' : tier === 5 ? 'EARNED' : 'PENDING';
  return { tier, seal, badges, flagged, haveAll };
}

function actionUploadPacket(jobId, body, req) {
  const job = getJobOr404(jobId);
  checkVersion(req, job.version);
  if (job.packetId && world.packets.has(job.packetId)) {
    const existing = world.packets.get(job.packetId);
    const claimedHash = body?.records?.[body.records.length - 1]?.hash;
    if (claimedHash !== existing.packetHash) {
      throw new ApiError(409, 'PACKET_CONFLICT', 'This job already has a sealed packet with a different head hash.', {
        existingPacketId: existing.id,
        existingPacketHash: existing.packetHash,
        claimedPacketHash: claimedHash ?? null,
      });
    }
    return { status: 200, body: {
      packetId: existing.id,
      packetHash: existing.packetHash,
      merkleLeaf: existing.merkleLeaf,
      recordCount: existing.recordCount,
      uploadedAt: existing.sealedAt ?? existing.createdAt,
      status: 'SUBMITTED',
      anchored: Boolean(existing.merkleLeaf),
    } };
  }
  requireJobStatus(job, ['SEALED', 'PACKET_READY'], 'packet upload');
  if (!body || typeof body !== 'object') throw new ApiError(400, 'VALIDATION_FAILED', 'Packet body is required.');
  requireEnum(body.jobType, Object.keys(JOB_TYPES), 'jobType');
  requireString(body.operatorId, 'operatorId', 64);
  requireString(body.title, 'title', 200);
  requireString(body.requesterLabel, 'requesterLabel', 200);
  requireTimestamp(body.asOf, 'asOf');
  requireString(body.signatureBase64, 'signatureBase64', 4096);
  requireString(body.signerKeyId, 'signerKeyId', 128);
  if (!Array.isArray(body.records) || body.records.length === 0) {
    throw new ApiError(400, 'VALIDATION_FAILED', 'records must be a non-empty array.');
  }
  for (const [index, record] of body.records.entries()) {
    if (!record || typeof record !== 'object') throw new ApiError(400, 'VALIDATION_FAILED', `records[${index}] must be an object.`);
    if (typeof record.seq !== 'number') throw new ApiError(400, 'VALIDATION_FAILED', `records[${index}].seq must be a number.`);
    requireEnum(record.kind, ['CAPTURE', 'ATTEST', 'CUSTODY', 'INSPECTION', 'PAYMENT', 'CORRECTION'], `records[${index}].kind`);
    requireTimestamp(record.at, `records[${index}].at`);
    requireString(record.actorId, `records[${index}].actorId`, 64);
    requireString(record.summary, `records[${index}].summary`, 300);
    if (!record.payload || typeof record.payload !== 'object' || Array.isArray(record.payload)) {
      throw new ApiError(400, 'VALIDATION_FAILED', `records[${index}].payload must be an object of string values.`);
    }
    for (const [key, value] of Object.entries(record.payload)) {
      if (typeof value !== 'string') throw new ApiError(400, 'VALIDATION_FAILED', `records[${index}].payload.${key} must be a string.`);
    }
    requireHash(record.prevHash, `records[${index}].prevHash`);
    requireHash(record.hash, `records[${index}].hash`);
    if (!record.signatureBase64) {
      // null is allowed; the seal record signature is the packet-level signature.
    }
  }
  if (!body.consent || typeof body.consent !== 'object') {
    throw new ApiError(400, 'VALIDATION_FAILED', 'consent is required to seal a packet.');
  }
  if (body.media?.some((media) => media.redaction === 'REQUIRED')) {
    throw new ApiError(422, 'VALIDATION_FAILED', 'Redaction is REQUIRED on at least one frame; resolve it before sealing.', {
      reason: 'REDACTION_UNRESOLVED',
    });
  }
  const chainResult = verifyChain(body.records);
  if (!chainResult.valid) {
    throw new ApiError(422, 'CHAIN_INVALID', chainResult.detail, {
      seq: chainResult.brokenAtSeq,
      headHash: chainResult.headHash,
      canonicalPreimage: 'sha256(canonicalJson({seq,kind,at,actorId,summary,payload,prevHash}))',
    });
  }
  const derived = deriveTierFromSubmission(body, chainResult);
  if (body.tier !== undefined && body.tier !== derived.tier) {
    throw new ApiError(422, 'DERIVATION_MISMATCH', `Claimed tier ${body.tier} but server derivation is ${derived.tier}.`, {
      claimedTier: body.tier,
      derivedTier: derived.tier,
    });
  }
  if (body.seal !== undefined && body.seal !== derived.seal) {
    throw new ApiError(422, 'DERIVATION_MISMATCH', `Claimed seal ${body.seal} but server derivation is ${derived.seal}.`, {
      claimedSeal: body.seal,
      derivedSeal: derived.seal,
    });
  }
  if (derived.seal !== 'EARNED') {
    throw new ApiError(409, 'JOB_STATE_INVALID', 'The packet is not sealable: required badges are not all verified.', {
      reason: 'SEAL_NOT_EARNED',
      derivedSeal: derived.seal,
      derivedTier: derived.tier,
    });
  }
  const packetHash = chainResult.headHash;
  const anchored = !job.demo;
  const merkleIndex = anchored ? world.log.length : -1;
  if (anchored) world.log.push(packetHash);
  const packet = {
    id: `pkt_${jobId.replace(/^job_/, '')}`,
    jobId,
    jobType: body.jobType,
    operatorId: body.operatorId,
    title: body.title,
    requesterLabel: body.requesterLabel,
    site: body.site ?? job.site,
    consent: body.consent,
    badges: derived.badges,
    tier: derived.tier,
    tierName: ['Reported', 'Counterparty confirmed', 'Field captured', 'Payment verified', 'Verified by AUCTUM'][derived.tier - 1],
    seal: derived.seal,
    records: body.records,
    media: body.media ?? [],
    checklist: body.checklist ?? [],
    gates: [
      { id: 'consent_on_file', label: 'Consent on file', passed: true, detail: 'Consent record present.', blocking: true },
      { id: 'redaction', label: 'Redaction resolved', passed: true, detail: 'No unresolved redaction.', blocking: true },
      { id: 'chain', label: 'Chain verifies', passed: true, detail: chainResult.detail, blocking: true },
    ],
    measurements: body.measurements ?? [],
    notes: '',
    demo: Boolean(job.demo),
    createdAt: body.records[0].at,
    sealedAt: body.asOf,
    merkleLeaf: anchored ? leafHashOf(packetHash) : null,
    merkleProof: [],
    asOf: body.asOf,
    signatureBase64: body.signatureBase64,
    packetHash,
    shortHash: packetHash.slice(0, 8),
    recordCount: body.records.length,
    version: 1,
    merkleIndex,
  };
  job.status = 'SUBMITTED';
  job.packetId = packet.id;
  bumpJob(job);
  world.packets.set(packet.id, packet);
  const index = world.log.indexOf(packetHash);
  if (index >= 0) packet.merkleProof = auditPath(world.log, index);
  return {
    status: 201,
    body: {
      packetId: packet.id,
      packetHash,
      merkleLeaf: packet.merkleLeaf,
      recordCount: packet.recordCount,
      uploadedAt: nowIso(),
      status: 'SUBMITTED',
      anchored,
      reason: anchored ? null : 'DEMO_EXCLUDED_FROM_LOG',
    },
  };
}

function actionVerify(packetId) {
  const packet = getPacketOr404(packetId);
  const chain = verifyChain(packet.records);
  const included = Boolean(packet.merkleLeaf) && world.log.includes(packet.packetHash);
  return {
    status: 200,
    body: {
      ...chain,
      signatureValid: typeof packet.signatureBase64 === 'string' && packet.signatureBase64.length > 0,
      signerKeyId: KEY_ID,
      included,
      verifiedAt: nowIso(),
    },
  };
}

function actionExport(packetId) {
  const packet = getPacketOr404(packetId);
  if (packet.seal !== 'EARNED') {
    throw new ApiError(409, 'JOB_STATE_INVALID', 'Only a sealed packet can be exported.', {
      reason: 'PACKET_NOT_SEALED',
      seal: packet.seal,
    });
  }
  if (packet.media.some((media) => media.redaction === 'REQUIRED')) {
    throw new ApiError(409, 'JOB_STATE_INVALID', 'A frame still requires redaction; export is blocked.', {
      reason: 'REDACTION_UNRESOLVED',
    });
  }
  const wire = packetView(packet);
  const canonical = canonicalJson(wire);
  const custodyLines = [
    'FIELDWRIGHT · CUSTODY RECORD',
    `Packet: ${packet.id}`,
    `Job: ${packet.jobId} · ${packet.jobType}`,
    `Requester: ${packet.requesterLabel}`,
    `Sealed: ${packet.sealedAt}`,
    `As of: ${packet.asOf}`,
    `Records: ${packet.recordCount}`,
    `Head hash: ${packet.packetHash}`,
    '',
    ...packet.records.map((r) =>
      `seq ${r.seq}  ${r.kind.padEnd(10)} ${r.at}  ${r.actorId}  ${r.hash.slice(0, 16)}…  ${r.signatureBase64 ? 'signed' : 'linked'}`),
    '',
    packet.demo ? 'SAMPLE — excluded from log.' : `Inclusion: ${packet.merkleLeaf ? 'leaf in log' : 'not anchored'}`,
  ];
  return {
    status: 200,
    body: {
      fileName: `${packet.id.replace(/^pkt_/, 'FW-').toUpperCase()}.fieldwright.json`,
      canonicalJson: canonical,
      custodyText: custodyLines.join('\n'),
      chainHead: packet.packetHash,
      recordCount: packet.recordCount,
    },
    headers: { ETag: etagOf(packet.version) },
  };
}

function actionAnchor(packetId) {
  const packet = getPacketOr404(packetId);
  if (packet.demo) {
    throw new ApiError(409, 'JOB_STATE_INVALID', 'Demo and sample packets never enter the transparency log.', {
      reason: 'DEMO_EXCLUDED_FROM_LOG',
    });
  }
  if (packet.seal !== 'EARNED') {
    throw new ApiError(409, 'JOB_STATE_INVALID', 'Only a sealed packet can be anchored.', {
      reason: 'PACKET_NOT_SEALED',
      seal: packet.seal,
    });
  }
  if (!packet.merkleLeaf) {
    packet.merkleLeaf = leafHashOf(packet.packetHash);
    world.log.push(packet.packetHash);
  }
  const index = world.log.indexOf(packet.packetHash);
  packet.merkleProof = auditPath(world.log, index);
  return {
    status: 200,
    body: {
      packetId: packet.id,
      merkleLeaf: packet.merkleLeaf,
      logHead: world.logHead(),
      inclusionProof: {
        leafIndex: index,
        treeSize: world.log.length,
        auditPath: packet.merkleProof,
        rootHash: mthRange(world.log),
      },
      anchoredAt: nowIso(),
    },
  };
}

function actionCorrection(packetId, body) {
  const packet = getPacketOr404(packetId);
  requireString(body?.note, 'note', 500);
  const supersedesSeq = body?.supersedesSeq;
  if (!Number.isInteger(supersedesSeq) || supersedesSeq < 1 || supersedesSeq > packet.records.length) {
    throw new ApiError(400, 'VALIDATION_FAILED', 'supersedesSeq must reference an existing record.', { supersedesSeq });
  }
  const previous = packet.records[packet.records.length - 1];
  const record = makeRecord({
    seq: packet.records.length + 1,
    kind: 'CORRECTION',
    at: nowIso(),
    actorId: OP_ID,
    summary: `Correction of seq ${supersedesSeq}`,
    payload: { note: body.note, supersedesSeq: String(supersedesSeq) },
    prevHash: previous.hash,
    signatureBase64: SEED_SIGNATURE,
    signerKeyId: KEY_ID,
  });
  packet.records.push(record);
  packet.recordCount = packet.records.length;
  packet.packetHash = record.hash;
  packet.shortHash = record.hash.slice(0, 8);
  packet.seal = 'FLAGGED';
  packet.notes = body.note;
  packet.merkleLeaf = null;
  packet.merkleProof = [];
  packet.version += 1;
  return { status: 200, body: packetView(packet), headers: { ETag: etagOf(packet.version) } };
}

function actionQuotePayout(body) {
  if (!body || !Number.isInteger(body.amountCents)) {
    throw new ApiError(400, 'VALIDATION_FAILED', 'amountCents must be an integer.');
  }
  requireEnum(body.mode, ['INSTANT', 'STANDARD'], 'mode');
  const amountCents = body.amountCents;
  if (amountCents <= 0) throw new ApiError(422, 'INVALID_AMOUNT', 'amountCents must be greater than zero.', { amountCents });
  const available = balancesView().availableCents;
  if (amountCents > available) {
    throw new ApiError(422, 'INVALID_AMOUNT', 'Amount exceeds available balance.', { amountCents, availableCents: available });
  }
  const feeCents = body.mode === 'INSTANT' ? instantFeeCents(amountCents) : 0;
  return {
    status: 200,
    body: {
      amountCents,
      feeCents,
      netCents: amountCents - feeCents,
      mode: body.mode,
      etaLabel: body.mode === 'INSTANT' ? 'Minutes to debit card' : '1–3 business days',
      feeDisclosure:
        body.mode === 'INSTANT'
          ? `Instant payout fee: 1.5% (capped at $5.00). You receive ${formatMoney(amountCents - feeCents)}.`
          : `Standard ACH is free. You receive ${formatMoney(amountCents)} in 1–3 business days.`,
    },
  };
}

function formatMoney(cents) {
  const negative = cents < 0;
  const abs = Math.abs(cents);
  const whole = Math.floor(abs / 100);
  const frac = String(abs % 100).padStart(2, '0');
  const grouped = whole.toString().replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  return `${negative ? '−' : ''}$${grouped}.${frac}`;
}

function actionRequestPayout(body, req) {
  if (!body || !Number.isInteger(body.amountCents)) {
    throw new ApiError(400, 'VALIDATION_FAILED', 'amountCents must be an integer.');
  }
  requireEnum(body.mode, ['INSTANT', 'STANDARD'], 'mode');
  const balances = balancesView();
  if (body.amountCents <= 0) throw new ApiError(422, 'INVALID_AMOUNT', 'amountCents must be greater than zero.');
  if (body.amountCents > balances.availableCents) {
    throw new ApiError(422, 'INVALID_AMOUNT', 'Amount exceeds available balance.', {
      amountCents: body.amountCents,
      availableCents: balances.availableCents,
    });
  }
  const feeCents = body.mode === 'INSTANT' ? instantFeeCents(body.amountCents) : 0;
  const payoutId = `pay_${String(world.payouts.length + 1).padStart(4, '0')}`;
  const requestedAt = nowIso();
  const status = body.mode === 'INSTANT' ? 'PAID' : 'PROCESSING';
  const payout = {
    id: payoutId,
    amountCents: body.amountCents,
    feeCents,
    netCents: body.amountCents - feeCents,
    mode: body.mode,
    status,
    requestedAt,
    completedAt: status === 'PAID' ? requestedAt : null,
    destinationLast4: world.operator.payoutAccountLast4,
    idempotencyKey: req?.headers?.['idempotency-key'] ?? 'idem-unknown',
    failureReason: null,
  };
  world.payouts.push(payout);
  // Allocate whole ledger entries to the payout. When an entry is larger than the amount
  // requested, the entry is marked PAID and the unspent remainder is booked as a new
  // AVAILABLE ADJUSTMENT — append-only, never an edit to an existing amount.
  let remaining = body.amountCents;
  for (const entry of world.ledger) {
    if (remaining <= 0) break;
    if (entry.status !== 'AVAILABLE' || entry.amountCents <= 0) continue;
    if (entry.amountCents <= remaining) {
      entry.status = 'PAID';
      remaining -= entry.amountCents;
      continue;
    }
    const change = entry.amountCents - remaining;
    entry.status = 'PAID';
    world.ledger.push({
      id: `led_change_${payoutId}_${entry.id}`,
      jobId: entry.jobId,
      packetId: entry.packetId,
      kind: 'ADJUSTMENT',
      amountCents: change,
      status: 'AVAILABLE',
      at: requestedAt,
      description: `Unpaid remainder from ${entry.description}`,
      idempotencyKey: `${payout.idempotencyKey}:change:${entry.id}`,
      demo: entry.demo,
    });
    remaining = 0;
  }
  if (remaining > 0) {
    payout.status = 'FAILED';
    payout.failureReason = 'Ledger allocation failed; no payout was made.';
  }
  if (feeCents > 0) {
    world.ledger.push({
      id: `led_fee_${payoutId}`,
      jobId: null,
      packetId: null,
      kind: 'INSTANT_FEE',
      amountCents: -feeCents,
      status: 'PAID',
      at: requestedAt,
      description: 'Instant payout fee · 1.5% (min $0.50, max $5.00)',
      idempotencyKey: `${payout.idempotencyKey}-fee`,
      demo: false,
    });
  }
  return { status: 201, body: payout };
}

// ---------------------------------------------------------------------------
// Sync batch
// ---------------------------------------------------------------------------

function applySyncEvent(event) {
  const payload = event.payload ?? {};
  try {
    switch (event.type) {
      case 'job.en_route':
        if (!payload.jobId) throw new ApiError(400, 'VALIDATION_FAILED', 'jobId is required.');
        return { status: 'APPLIED', result: actionEnRoute(payload.jobId, null).body };
      case 'arrival.confirm':
        if (!payload.jobId) throw new ApiError(400, 'VALIDATION_FAILED', 'jobId is required.');
        return { status: 'APPLIED', result: actionArrival(payload.jobId, payload, null).body };
      case 'capture.start':
        if (!payload.jobId) throw new ApiError(400, 'VALIDATION_FAILED', 'jobId is required.');
        return { status: 'APPLIED', result: actionStartCapture(payload.jobId, null).body };
      case 'packet.submit': {
        if (!payload.jobId) throw new ApiError(400, 'VALIDATION_FAILED', 'jobId is required.');
        const upload = actionUploadPacket(payload.jobId, payload, null);
        return { status: 'APPLIED', result: upload.body };
      }
      case 'correction.append': {
        if (!payload.packetId) throw new ApiError(400, 'VALIDATION_FAILED', 'packetId is required.');
        return { status: 'APPLIED', result: actionCorrection(payload.packetId, payload).body };
      }
      case 'payout.request':
        return { status: 'APPLIED', result: actionRequestPayout(payload, null).body };
      case 'availability.upsert': {
        const availability = payload.availability;
        if (!availability) throw new ApiError(400, 'VALIDATION_FAILED', 'availability is required.');
        world.availability = { ...availability, updatedAt: nowIso() };
        return { status: 'APPLIED', result: world.availability };
      }
      case 'offer.accept':
        if (!payload.offerId) throw new ApiError(400, 'VALIDATION_FAILED', 'offerId is required.');
        return { status: 'APPLIED', result: actionAcceptOffer(payload.offerId).body };
      case 'offer.decline':
        if (!payload.offerId) throw new ApiError(400, 'VALIDATION_FAILED', 'offerId is required.');
        return { status: 'APPLIED', result: actionDeclineOffer(payload.offerId, payload).body };
      default:
        return { status: 'REJECTED', error: { code: 'VALIDATION_FAILED', message: `Unknown event type ${event.type}.`, retryable: false } };
    }
  } catch (error) {
    if (error instanceof ApiError) {
      const status = error.retryable || error.status >= 500 ? 'RETRY' : error.status === 409 ? 'CONFLICT' : 'REJECTED';
      return {
        status,
        error: {
          code: error.code,
          message: error.message,
          retryable: Boolean(error.retryable),
          details: error.details ?? undefined,
        },
      };
    }
    return { status: 'RETRY', error: { code: 'INTERNAL', message: 'Unexpected sync error.', retryable: true } };
  }
}

function actionSyncBatch(body) {
  if (!body || typeof body !== 'object') throw new ApiError(400, 'VALIDATION_FAILED', 'Body is required.');
  requireString(body.deviceId, 'deviceId', 64);
  requireTimestamp(body.sentAt, 'sentAt');
  if (!Array.isArray(body.events) || body.events.length === 0) {
    throw new ApiError(400, 'VALIDATION_FAILED', 'events must be a non-empty array.');
  }
  if (body.events.length > 50) throw new ApiError(400, 'VALIDATION_FAILED', 'At most 50 events per batch.');
  const results = body.events.map((event) => {
    if (!event || typeof event !== 'object') {
      return { idempotencyKey: 'unknown', eventId: 'unknown', status: 'REJECTED', error: { code: 'VALIDATION_FAILED', message: 'Event must be an object.', retryable: false } };
    }
    const key = event.idempotencyKey;
    const fingerprint = sha256Hex(`sync ${event.type} ${canonicalJson(event.payload ?? {})}`);
    const previous = world.idempotency.get(key);
    if (previous) {
      if (previous.fingerprint !== fingerprint) {
        return {
          idempotencyKey: key,
          eventId: event.eventId,
          status: 'CONFLICT',
          error: { code: 'IDEMPOTENCY_KEY_REUSED', message: 'This key was used with a different event body.', retryable: false },
        };
      }
      return { idempotencyKey: key, eventId: event.eventId, status: 'DUPLICATE', result: previous.body };
    }
    const outcome = applySyncEvent(event);
    if (outcome.status === 'APPLIED') {
      world.idempotency.set(key, { fingerprint, status: 200, body: outcome.result });
    }
    return { idempotencyKey: key, eventId: event.eventId, ...outcome };
  });
  return { status: 200, body: { results, serverTime: nowIso(), nextRetryAfterMs: null } };
}

// ---------------------------------------------------------------------------
// Routing
// ---------------------------------------------------------------------------

const routes = [];

function route(method, pattern, handler, { publicRoute = false } = {}) {
  const keys = [];
  const regex = new RegExp(
    `^${pattern.replace(/:[A-Za-z]+/g, (token) => {
      keys.push(token.slice(1));
      return '([^/]+)';
    })}$`,
  );
  routes.push({ method, regex, keys, handler, publicRoute });
}

// System ----------------------------------------------------------------------
route('GET', '/healthz', () => ({
  status: 200,
  body: { status: 'ok', now: nowIso(), seeded: true, startedAt, operatorId: OP_ID },
}), { publicRoute: true });

route('GET', '/v1/healthz', () => ({
  status: 200,
  body: { status: 'ok', now: nowIso(), seeded: true, startedAt, operatorId: OP_ID },
}), { publicRoute: true });

route('POST', '/v1/auth/token', (ctx) => {
  const body = ctx.body;
  requireString(body?.deviceId, 'deviceId', 64);
  requireString(body?.attestation, 'attestation', 4096);
  requireString(body?.appVersion, 'appVersion', 20);
  if (!Number.isInteger(body?.sdkInt) || body.sdkInt < 24) {
    throw new ApiError(400, 'VALIDATION_FAILED', 'sdkInt must be an integer >= 24.');
  }
  if (body.attestation === 'reject') {
    throw new ApiError(403, 'ATTESTATION_REJECTED', 'Device attestation was rejected.');
  }
  const token = `fw_dev_${OP_ID}_${sha256Hex(`${body.deviceId}:${nowIso()}`).slice(0, 16)}`;
  world.tokens.set(token, { deviceId: body.deviceId, issuedAt: Date.now() });
  return { status: 201, body: { token, expiresAt: isoAt(Date.now() + TOKEN_TTL_MS), operatorId: OP_ID } };
}, { publicRoute: true });

// Operator --------------------------------------------------------------------
route('GET', '/v1/me', () => ({ status: 200, body: world.operator }));

route('GET', '/v1/me/availability', () => ({
  status: 200,
  body: world.availability,
  headers: { ETag: etagOf(world.availabilityVersion) },
}));

route('PUT', '/v1/me/availability', (ctx) => {
  const ifMatch = ctx.req.headers['if-match'];
  if (typeof ifMatch !== 'string' || ifMatch.trim() === '') {
    throw new ApiError(412, 'PRECONDITION_FAILED', 'If-Match is required for availability updates.', {
      currentETag: etagOf(world.availabilityVersion),
      reason: 'IF_MATCH_REQUIRED',
    });
  }
  checkVersion(ctx.req, world.availabilityVersion);
  const body = ctx.body ?? {};
  requireEnum(body.radiusMeters, [5000, 10000, 15000, 25000], 'radiusMeters');
  if (typeof body.isOnline !== 'boolean') throw new ApiError(400, 'VALIDATION_FAILED', 'isOnline must be a boolean.');
  requireString(body.areaLabel, 'areaLabel', 120);
  const policy = body.autoAccept;
  if (!policy || typeof policy !== 'object') throw new ApiError(400, 'VALIDATION_FAILED', 'autoAccept is required.');
  if (!Number.isInteger(policy.minPayoutCents) || policy.minPayoutCents < MIN_PAYOUT_FLOOR_CENTS) {
    throw new ApiError(422, 'VALIDATION_FAILED', `autoAccept.minPayoutCents must be at least ${MIN_PAYOUT_FLOOR_CENTS}.`, {
      minPayoutFloorCents: MIN_PAYOUT_FLOOR_CENTS,
    });
  }
  if (!Number.isInteger(policy.maxActiveStack) || policy.maxActiveStack < 1 || policy.maxActiveStack > MAX_ACTIVE_JOBS) {
    throw new ApiError(422, 'VALIDATION_FAILED', 'autoAccept.maxActiveStack must be between 1 and 3.');
  }
  world.availability = {
    isOnline: body.isOnline,
    updatedAt: nowIso(),
    radiusMeters: body.radiusMeters,
    autoAccept: {
      enabled: Boolean(policy.enabled),
      minPayoutCents: policy.minPayoutCents,
      maxDistanceMeters: Number.isInteger(policy.maxDistanceMeters) ? policy.maxDistanceMeters : 12000,
      maxActiveStack: policy.maxActiveStack,
      allowedTypes: Array.isArray(policy.allowedTypes) && policy.allowedTypes.length > 0 ? policy.allowedTypes : ['ROOF_HAIL', 'RPZ_TEST', 'PROPERTY_WALK', 'METER_CLUSTER', 'PROGRESS_PROOF', 'CLAIM_SWEEP'],
      requireCredentialMatch: Boolean(policy.requireCredentialMatch ?? true),
    },
    areaLabel: body.areaLabel,
  };
  world.availabilityVersion += 1;
  return { status: 200, body: world.availability, headers: { ETag: etagOf(world.availabilityVersion) } };
});

route('GET', '/v1/me/balances', () => ({ status: 200, body: balancesView() }));
route('GET', '/v1/me/earnings', () => ({ status: 200, body: earningsView() }));

route('GET', '/v1/me/ledger', (ctx) => {
  const items = [...world.ledger].sort((a, b) => b.at.localeCompare(a.at));
  return { status: 200, body: paginate(items, ctx.query.get('cursor') ?? undefined, Number(ctx.query.get('limit') ?? 20)) };
});

// Jobs ------------------------------------------------------------------------
route('GET', '/v1/jobs', (ctx) => {
  expireOffers();
  const scope = ctx.query.get('scope') ?? 'active';
  requireEnum(scope, ['offers', 'active', 'done'], 'scope');
  const all = [...world.jobs.values()];
  let items;
  if (scope === 'offers') items = all.filter((job) => job.status === 'AVAILABLE' || job.status === 'OFFERED');
  else if (scope === 'done') items = all.filter((job) => ['APPROVED', 'PAID', 'CANCELLED', 'EXPIRED'].includes(job.status));
  else items = all.filter((job) => ACTIVE_STATUSES.has(job.status));
  items.sort((a, b) => a.id.localeCompare(b.id));
  return { status: 200, body: paginate(items, ctx.query.get('cursor') ?? undefined, Number(ctx.query.get('limit') ?? 20)) };
});

route('GET', '/v1/jobs/:jobId', (ctx) => {
  const job = getJobOr404(ctx.params.jobId);
  return { status: 200, body: job, headers: { ETag: etagOf(job.version) } };
});

route('POST', '/v1/jobs/:jobId/en-route', (ctx) => {
  const result = idempotentResult(ctx.req, ctx.body, () => actionEnRoute(ctx.params.jobId, ctx.req));
  return { status: result.status, body: result.body, headers: result.headers };
});

route('POST', '/v1/jobs/:jobId/arrivals', (ctx) => {
  const result = idempotentResult(ctx.req, ctx.body, () => actionArrival(ctx.params.jobId, ctx.body, ctx.req));
  return { status: result.status, body: result.body, headers: result.headers };
});

route('POST', '/v1/jobs/:jobId/capture/start', (ctx) => {
  const result = idempotentResult(ctx.req, ctx.body, () => actionStartCapture(ctx.params.jobId, ctx.req));
  return { status: result.status, body: result.body, headers: result.headers };
});

// Offers ----------------------------------------------------------------------
route('GET', '/v1/offers', () => ({ status: 200, body: { items: offersView(), asOf: nowIso() } }));

route('POST', '/v1/offers/:offerId/accept', (ctx) => {
  const result = idempotentResult(ctx.req, ctx.body, () => actionAcceptOffer(ctx.params.offerId));
  return { status: result.status, body: result.body, headers: result.headers };
});

route('POST', '/v1/offers/:offerId/decline', (ctx) => {
  const result = idempotentResult(ctx.req, ctx.body, () => actionDeclineOffer(ctx.params.offerId, ctx.body));
  return { status: result.status, body: result.body, headers: result.headers };
});

// Packets ---------------------------------------------------------------------
route('GET', '/v1/packets', (ctx) => {
  const filter = ctx.query.get('filter') ?? 'all';
  requireEnum(filter, ['all', 'sealed', 'attention'], 'filter');
  let items = [...world.packets.values()];
  if (filter === 'sealed') items = items.filter((packet) => packet.seal === 'EARNED');
  if (filter === 'attention') {
    items = items.filter((packet) =>
      packet.seal === 'FLAGGED' ||
      packet.media.some((media) => media.redaction === 'REQUIRED') ||
      packet.badges.some((badge) => badge.state === 'FLAGGED'));
  }
  items.sort((a, b) => b.sealedAt.localeCompare(a.sealedAt));
  return { status: 200, body: paginate(items.map(packetView), ctx.query.get('cursor') ?? undefined, Number(ctx.query.get('limit') ?? 20)) };
});

route('GET', '/v1/packets/:packetId', (ctx) => {
  const packet = getPacketOr404(ctx.params.packetId);
  return { status: 200, body: packetView(packet), headers: { ETag: etagOf(packet.version) } };
});

route('POST', '/v1/jobs/:jobId/packets', (ctx) => {
  const result = idempotentResult(ctx.req, ctx.body, () => actionUploadPacket(ctx.params.jobId, ctx.body, ctx.req));
  return { status: result.status, body: result.body, headers: result.headers };
});

route('POST', '/v1/packets/:packetId/verify', (ctx) => {
  const packet = getPacketOr404(ctx.params.packetId);
  return actionVerify(packet.id);
});

route('GET', '/v1/packets/:packetId/export', (ctx) => actionExport(ctx.params.packetId));

route('POST', '/v1/packets/:packetId/anchor', (ctx) => {
  const result = idempotentResult(ctx.req, ctx.body, () => actionAnchor(ctx.params.packetId));
  return { status: result.status, body: result.body, headers: result.headers };
});

route('POST', '/v1/packets/:packetId/corrections', (ctx) => {
  const result = idempotentResult(ctx.req, ctx.body, () => {
    checkVersion(ctx.req, getPacketOr404(ctx.params.packetId).version);
    return actionCorrection(ctx.params.packetId, ctx.body);
  });
  return { status: result.status, body: result.body, headers: result.headers };
});

route('GET', '/v1/log/head', () => ({ status: 200, body: world.logHead() }));

// Money -----------------------------------------------------------------------
route('POST', '/v1/payouts/quote', (ctx) => actionQuotePayout(ctx.body));

route('GET', '/v1/payouts', (ctx) => {
  const items = [...world.payouts].sort((a, b) => b.requestedAt.localeCompare(a.requestedAt));
  return { status: 200, body: paginate(items, ctx.query.get('cursor') ?? undefined, Number(ctx.query.get('limit') ?? 20)) };
});

route('GET', '/v1/payouts/:payoutId', (ctx) => {
  const payout = world.payouts.find((p) => p.id === ctx.params.payoutId);
  if (!payout) throw new ApiError(404, 'NOT_FOUND', `No payout ${ctx.params.payoutId}.`);
  return { status: 200, body: payout };
});

route('POST', '/v1/payouts', (ctx) => {
  const result = idempotentResult(ctx.req, ctx.body, () => actionRequestPayout(ctx.body, ctx.req));
  return { status: result.status, body: result.body, headers: result.headers };
});

// Sync ------------------------------------------------------------------------
route('POST', '/v1/sync/batch', (ctx) => actionSyncBatch(ctx.body));

// ---------------------------------------------------------------------------
// Request handling
// ---------------------------------------------------------------------------

async function handle(req, res) {
  const requestStart = Date.now();
  requestCounters.total += 1;
  res.locals = {
    requestId: sha256Hex(`${Date.now()}:${requestCounters.total}:${Math.random()}`).slice(0, 12),
    rateHeaders: {},
  };

  const url = new URL(req.url, `http://${req.headers.host ?? 'localhost'}`);
  let path = url.pathname;
  // Accept both /v1/... and /... so curl and the emulator can use either base URL.
  if (!path.startsWith('/v1') && path !== '/healthz') path = `/v1${path}`;
  try {
    if (BASE_LATENCY_MS > 0) {
      await sleep(Math.round(BASE_LATENCY_MS * (0.5 + Math.random())));
    }

    // Authentication (all /v1 routes except token issuance).
    let token = null;
    if (path !== '/v1/auth/token' && path !== '/healthz' && path !== '/v1/healthz') {
      const header = req.headers.authorization;
      if (typeof header !== 'string' || !header.startsWith('Bearer ')) {
        throw new ApiError(401, 'UNAUTHENTICATED', 'Missing Authorization: Bearer <token>.');
      }
      token = header.slice('Bearer '.length).trim();
      if (!world.tokens.has(token) && !token.startsWith('fw_dev_')) {
        throw new ApiError(401, 'UNAUTHENTICATED', 'Unknown or expired token.');
      }
    }

    if (token) {
      const rate = checkRateLimit(token, req.method, path);
      res.locals.rateHeaders = {
        'RateLimit-Limit': String(rate.limit),
        'RateLimit-Remaining': String(rate.remaining),
        'RateLimit-Reset': String(rate.resetSeconds),
      };
    }

    // Route match.
    for (const candidate of routes) {
      if (candidate.method !== req.method) continue;
      const match = candidate.regex.exec(path);
      if (!match) continue;
      const params = {};
      candidate.keys.forEach((key, index) => { params[key] = decodeURIComponent(match[index + 1]); });
      const body = ['POST', 'PUT', 'PATCH'].includes(req.method) ? await readBody(req) : null;
      const ctx = { req, res, params, body, query: url.searchParams, url };
      const result = await candidate.handler(ctx);
      const headers = { ...(result.headers ?? {}) };
      sendJson(res, result.status, result.body, headers);
      logLine(req, res, requestStart);
      return;
    }

    // Wrong method on a known path → 405, unknown path → 404.
    const pathExists = routes.some((candidate) => candidate.regex.test(path));
    if (pathExists) throw new ApiError(405, 'METHOD_NOT_ALLOWED', `${req.method} is not supported on ${path}.`);
    throw new ApiError(404, 'NOT_FOUND', `No route for ${req.method} ${path}.`);
  } catch (error) {
    if (error instanceof ApiError) {
      sendError(res, error);
    } else {
      console.error('[fieldwright-mock] unhandled:', error);
      sendError(res, new ApiError(500, 'INTERNAL', 'Unexpected server error.', null, true));
    }
    logLine(req, res, requestStart);
  }
}

function logLine(req, res, started) {
  if (process.env.FIELDWRIGHT_QUIET === '1') return;
  const ms = Date.now() - started;
  console.log(`${new Date().toISOString()} ${req.method} ${req.url} → ${res.statusCode} ${ms}ms`);
}

const server = createServer((req, res) => {
  handle(req, res).catch((error) => {
    console.error('[fieldwright-mock] fatal:', error);
    if (!res.headersSent) {
      res.writeHead(500, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ error: { code: 'INTERNAL', message: 'Fatal error.', retryable: true, requestId: 'n/a' } }));
    } else {
      res.end();
    }
  });
});

// Fail fast on a broken proof verifier; this runs in a few milliseconds.
const merkleSelfTest = runMerkleSelfTest();
if (merkleSelfTest.failures.length > 0) {
  console.error('[fieldwright-mock] Merkle self-test FAILED:');
  for (const failure of merkleSelfTest.failures.slice(0, 10)) console.error(`  - ${failure}`);
  process.exit(1);
}

server.listen(PORT, '0.0.0.0', () => {
  const head = world.logHead();
  console.log('');
  console.log('  Fieldwright contract mock — v1');
  console.log(`  URL (emulator):   http://10.0.2.2:${PORT}/v1`);
  console.log(`  URL (host):       http://127.0.0.1:${PORT}/v1`);
  console.log(`  Latency:          ${BASE_LATENCY_MS === 0 ? 'none' : `${BASE_LATENCY_MS} ms p50`}`);
  console.log(`  Seeded operator:  ${world.operator.displayName} (${world.operator.tierName}) · ${world.operator.id}`);
  console.log(`  Seeded packets:   ${[...world.packets.keys()].join(', ')}`);
  console.log(`  Live offers:      ${offersView().map((o) => o.id).join(', ') || 'none'}`);
  console.log(`  Log head:         treeSize ${head.treeSize} · ${head.rootHash.slice(0, 16)}…`);
  console.log(`  Merkle self-test: ${merkleSelfTest.checks} checks passed (sizes 1–12, every index; forged proofs rejected)`);
  console.log('');
  console.log('  Try:  curl -s http://127.0.0.1:' + PORT + '/v1/me/balances -H "Authorization: Bearer fw_dev_op_7d1a_0000000000000000"');
  console.log('  Docs: docs/03-api-contract.md · docs/api/fieldwright.openapi.yaml · backend/README.md');
  console.log('');
});

function shutdown(signal) {
  console.log(`\n[fieldwright-mock] ${signal} received, closing.`);
  server.close(() => process.exit(0));
  setTimeout(() => process.exit(0), 1_500).unref();
}
process.on('SIGINT', () => shutdown('SIGINT'));
process.on('SIGTERM', () => shutdown('SIGTERM'));

