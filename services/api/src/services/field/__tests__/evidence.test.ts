import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  GENESIS_HASH,
  buildPacketRecords,
  verifyPacketChain,
  quotePayout,
  computeBalances,
  canAdvanceQueueStatus,
  canonicalJson,
} from '../evidence';

test('canonicalJson sorts keys deterministically without whitespace', () => {
  assert.equal(canonicalJson({ b: 2, a: { d: 4, c: 3 } }), '{"a":{"c":3,"d":4},"b":2}');
});

test('buildPacketRecords links from genesis and verifies intact, detects tampering', () => {
  const records = buildPacketRecords({
    actorId: 'usr_1',
    jobId: 'job_1',
    consentName: 'Dana Whitfield',
    consentHash: 'a'.repeat(64),
    measurementMeters: 0.9,
    referenceLabel: 'Shingle course (90 cm)',
    notes: 'South slope clear',
    atIso: '2026-11-17T12:00:00.000Z',
  });
  assert.equal(records.length, 3);
  assert.equal(records[0].prevHash, GENESIS_HASH);
  assert.equal(verifyPacketChain(records).valid, true);

  const tampered = records.map((r, i) => (i === 1 ? { ...r, summary: 'Edited' } : r));
  const check = verifyPacketChain(tampered);
  assert.equal(check.valid, false);
  assert.equal(check.brokenAtSeq, 2);
});

test('quotePayout enforces integer-cent bounds and caps instant fee at $5.00', () => {
  const instantBig = quotePayout(45000, 'INSTANT');
  assert.equal(instantBig.feeCents, 500);
  assert.equal(instantBig.netCents, 44500);

  const instantMin = quotePayout(2500, 'INSTANT');
  assert.equal(instantMin.feeCents, 50);

  const standard = quotePayout(45000, 'STANDARD');
  assert.equal(standard.feeCents, 0);
  assert.equal(standard.netCents, 45000);
});

test('computeBalances derives available, pending, and paid totals from entries', () => {
  const b = computeBalances([
    { kind: 'EARNING', amountCents: 28500, status: 'AVAILABLE' },
    { kind: 'EARNING', amountCents: 15000, status: 'PENDING' },
    { kind: 'EARNING', amountCents: 10000, status: 'PAID' },
    { kind: 'PAYOUT', amountCents: -5000, status: 'AVAILABLE' },
  ]);
  assert.deepEqual(b, { availableCents: 23500, pendingCents: 15000, paidLifetimeCents: 10000 });
});

test('canAdvanceQueueStatus enforces legal lifecycle transitions', () => {
  assert.equal(canAdvanceQueueStatus('QUEUED', 'SCHEDULED'), true);
  assert.equal(canAdvanceQueueStatus('IN_PROGRESS', 'SEALED'), true);
  assert.equal(canAdvanceQueueStatus('QUEUED', 'SUBMITTED'), false);
  assert.equal(canAdvanceQueueStatus('SUBMITTED', 'QUEUED'), false);
});
