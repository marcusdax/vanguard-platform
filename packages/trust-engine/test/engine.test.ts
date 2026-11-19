import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import {
  GENESIS_HASH,
  canonicalJson,
  canonicalPreimage,
  computeRecordHash,
  verifyChain,
  hashLeaf,
  hashNode,
  mthHex,
  generateInclusionProof,
  verifyInclusionProof,
  generateConsistencyProof,
  checkCredentialsForAction,
  evaluateFloorPaymentGate,
  quoteSurfaceFee,
  computeClusterEconomics,
  computeEconomicHalfLife,
} from '../src/index';

const RECORD_A_HASH = '20f78a5f916dca1c5297e9eb4788a3ea7d170760f122270d0abf141a20c9b65e';
const RECORD_B_HASH = '7c4a8fbf2912553421dff3c9ea492da8b7908e29c3ebb57b6206fb6c1d98c78e';
const FLAT_GOLDEN =
  '{"ctrl":"\\u0001","empty":"","note":"line\\nbreak\\tand\\\\\\\\slash","quote":"x\\"y","unicode":"68 °F ✓"}';

describe('@vanguard/trust-engine Golden Vector Parity (Kotlin core:crypto)', () => {
  it('matches GoldenVectorTest RECORD_A and RECORD_B byte-for-byte', () => {
    const recA = {
      seq: 1,
      kind: 'CAPTURE' as const,
      at: '2026-01-15T12:00:00.000Z',
      actorId: 'op-1',
      summary: 'Four slopes captured',
      payload: { mediaCount: '4', captureStandardMet: 'true' },
      prevHash: GENESIS_HASH,
    };
    const hashA = computeRecordHash(recA);
    assert.equal(hashA, RECORD_A_HASH);

    const recB = {
      seq: 2,
      kind: 'ATTEST' as const,
      at: '2026-01-15T12:00:01.500Z',
      actorId: 'requester-1',
      summary: 'Quote "verified" · 68 °F',
      payload: { confirm: 'true', note: 'line\nbreak\tand\\\\slash' },
      prevHash: hashA,
    };
    const hashB = computeRecordHash(recB);
    assert.equal(hashB, RECORD_B_HASH);
    assert.equal(
      canonicalJson({
        unicode: '68 °F ✓',
        quote: 'x"y',
        note: 'line\nbreak\tand\\\\slash',
        empty: '',
        ctrl: '\u0001',
      }),
      FLAT_GOLDEN,
    );

    const verified = verifyChain([
      { ...recA, hash: hashA },
      { ...recB, hash: hashB },
    ]);
    assert.equal(verified.valid, true);
    assert.equal(verified.headHash, RECORD_B_HASH);
  });

  it('enforces exact canonicalPreimage field order', () => {
    const p = canonicalPreimage({
      seq: 1,
      kind: 'CUSTODY',
      at: '2026-01-15T12:00:00.000Z',
      actorId: 'op-1',
      summary: 's',
      payload: {},
      prevHash: GENESIS_HASH,
    });
    assert.equal(p.includes('"actorId":'), true);
    assert.equal(p.includes('"prevHash":'), true);
    assert.equal(p.includes('"actor":'), false);
  });
});

describe('@vanguard/trust-engine RFC 6962 Merkle Log (Check C-04)', () => {
  it('generates and verifies inclusion and consistency proofs', () => {
    const leaves = [
      { id: 'pkt-1', head: RECORD_A_HASH },
      { id: 'pkt-2', head: RECORD_B_HASH },
      { id: 'pkt-3', head: 'a'.repeat(64) },
      { id: 'pkt-4', head: 'b'.repeat(64) },
      { id: 'pkt-5', head: 'c'.repeat(64) },
    ];
    for (let i = 0; i < leaves.length; i++) {
      const proof = generateInclusionProof(leaves, i);
      assert.equal(verifyInclusionProof(proof), true);
    }
    const tampered = generateInclusionProof(leaves, 2);
    tampered.rootHash = 'f'.repeat(64);
    assert.equal(verifyInclusionProof(tampered), false);

    const cons = generateConsistencyProof(leaves, 3);
    assert.equal(cons.firstSize, 3);
    assert.equal(cons.secondSize, 5);
    assert.equal(cons.secondRoot, mthHex(leaves.map((l) => hashLeaf(l))));
    assert.ok(cons.proofNodes.length > 0);
    assert.equal(hashNode(hashLeaf(leaves[0]), hashLeaf(leaves[1])).length, 64);
  });
});

describe('@vanguard/trust-engine Invariants I-1 & I-5 (Checks C-11 & C-12)', () => {
  it('never blocks floor payments or uncredentialed base tasks (I-1)', () => {
    assert.equal(evaluateFloorPaymentGate().allowed, true);
    const floor = checkCredentialsForAction({
      isFloorAction: true,
      requiredCredentials: ['ROOF_SAFETY'],
      heldCredentials: [],
    });
    assert.equal(floor.allowed, true);
    assert.equal(floor.invariant, 'I-1');
  });

  it('returns explainable shortestFix with zero cost on gated actions (I-5)', () => {
    const res = checkCredentialsForAction({
      requiredCredentials: ['DART_FFI_INCLINOMETER_T3'],
      heldCredentials: ['IDENTITY_VERIFIED'],
      actionLabel: '4-Slope Roof Inspection',
    });
    assert.equal(res.allowed, false);
    if (!res.allowed) {
      assert.equal(res.missingCredential, 'DART_FFI_INCLINOMETER_T3');
      assert.equal(res.shortestFix.moduleId, 'T3-02');
      assert.equal(res.shortestFix.costCents, 0);
      assert.ok(res.shortestFix.minutes > 0);
    }
  });
});

describe('@vanguard/trust-engine Economics, Fees (C-09) & DePIN Half-Life', () => {
  it('models 4-task 1.5-mi neighborhood loop at $0.76/mi ($96.20 net, $54.97/hr)', () => {
    // 18 + 42 + 20 + 20 = $100.00; 15 + 45 + 15 + 15 = 90 task min; 5 mi / 20 mph = 15 drive min => 105 min = 1.75 hrs
    const eco = computeClusterEconomics({
      bountiesCents: [1800, 4200, 2000, 2000],
      taskMinutes: 90,
      miles: 5,
      irsCentsPerMile: 76.0,
    });
    assert.equal(eco.grossCents, 10000);
    assert.equal(eco.vehicleCostCents, 380);
    assert.equal(eco.netCents, 9620);
    assert.equal(eco.netHourlyCents, 5497);
  });

  it('quotes surface fees in integer cents with zero worker floor fee', () => {
    const lawn = quoteSurfaceFee('helios.local.b2c', 4200);
    assert.equal(lawn.feeCents, 210);
    assert.equal(lawn.totalBuyerChargeCents, 4410);
    assert.equal(lawn.workerReceivesCents, 4200);
    assert.equal(lawn.workerFeeBps, 0);

    const roof = quoteSurfaceFee('vanguard.b2b.bounty', 6500);
    assert.equal(roof.feeCents, 650);
    assert.equal(roof.totalBuyerChargeCents, 7150);
    assert.equal(roof.workerReceivesCents, 6500);
  });

  it('decays query price along 120-day half-life and auto-boosts stale parcels >= 90d', () => {
    const fresh = computeEconomicHalfLife(0);
    assert.equal(fresh.queryPriceCents, 1600);
    assert.equal(fresh.refreshMultiplier, 1.0);
    assert.equal(fresh.freshnessState, 'PEAK');

    const half = computeEconomicHalfLife(120);
    assert.equal(half.queryPriceCents, 800);
    assert.ok(half.refreshMultiplier > 1.0);
    assert.equal(half.freshnessState, 'STALE_REFRESH_TRIGGERED');
    assert.equal(
      half.workerRoyaltyCents + half.homeownerRoyaltyCents + half.treasuryBurnCents,
      half.queryPriceCents,
    );
  });
});
