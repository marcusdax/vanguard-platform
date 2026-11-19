/**
 * RFC 6962 Certificate Transparency Merkle Log engine.
 * leafHash = SHA-256(0x00 || canonicalBytes(leaf))
 * nodeHash = SHA-256(0x01 || leftHash || rightHash)
 * Empty tree root = SHA-256('')
 */
import { createHash } from 'node:crypto';
import { canonicalJson } from './canonical';

function sha256Bytes(bytes: Uint8Array): Buffer {
  return createHash('sha256').update(bytes).digest();
}

export function hexToBytes(hex: string): Buffer {
  return Buffer.from(hex, 'hex');
}

export function hashLeaf(leaf: unknown): string {
  const payload =
    typeof leaf === 'string' && /^[0-9a-f]{64}$/i.test(leaf)
      ? hexToBytes(leaf)
      : Buffer.from(canonicalJson(leaf), 'utf8');
  return sha256Bytes(Buffer.concat([Buffer.from([0x00]), payload])).toString('hex');
}

export function hashNode(leftHex: string, rightHex: string): string {
  return sha256Bytes(
    Buffer.concat([Buffer.from([0x01]), hexToBytes(leftHex), hexToBytes(rightHex)]),
  ).toString('hex');
}

function largestPowerOfTwoLessThan(n: number): number {
  let k = 1;
  while (k * 2 < n) k *= 2;
  return k;
}

/** Recursive RFC 6962 Merkle Tree Hash over leafHashes[start..end). */
export function mthHex(leafHashes: string[]): string {
  const n = leafHashes.length;
  if (n === 0) return sha256Bytes(Buffer.alloc(0)).toString('hex');
  if (n === 1) return leafHashes[0];
  const k = largestPowerOfTwoLessThan(n);
  return hashNode(mthHex(leafHashes.slice(0, k)), mthHex(leafHashes.slice(k)));
}

export interface InclusionProof {
  leafIndex: number;
  treeSize: number;
  leafHash: string;
  rootHash: string;
  auditPath: string[];
}

function buildAuditPath(leafHashes: string[], index: number): string[] {
  const n = leafHashes.length;
  if (n <= 1) return [];
  const k = largestPowerOfTwoLessThan(n);
  if (index < k) {
    return [...buildAuditPath(leafHashes.slice(0, k), index), mthHex(leafHashes.slice(k))];
  }
  return [...buildAuditPath(leafHashes.slice(k), index - k), mthHex(leafHashes.slice(0, k))];
}

export function generateInclusionProof(leaves: unknown[], index: number): InclusionProof {
  if (index < 0 || index >= leaves.length) {
    throw new RangeError(`Leaf index ${index} out of bounds for treeSize ${leaves.length}`);
  }
  const leafHashes = leaves.map((l) => hashLeaf(l));
  return {
    leafIndex: index,
    treeSize: leaves.length,
    leafHash: leafHashes[index],
    rootHash: mthHex(leafHashes),
    auditPath: buildAuditPath(leafHashes, index),
  };
}

export function verifyInclusionProof(proof: InclusionProof): boolean {
  if (proof.treeSize <= 0 || proof.leafIndex < 0 || proof.leafIndex >= proof.treeSize) {
    return false;
  }
  if (proof.treeSize === 1) {
    return proof.auditPath.length === 0 && proof.leafHash === proof.rootHash;
  }
  let fn = proof.leafIndex;
  let sn = proof.treeSize - 1;
  let r = proof.leafHash;
  for (const p of proof.auditPath) {
    if (sn === 0) return false;
    if ((fn & 1) === 1 || fn === sn) {
      r = hashNode(p, r);
      while (fn !== 0 && (fn & 1) === 0) {
        fn >>= 1;
        sn >>= 1;
      }
    } else {
      r = hashNode(r, p);
    }
    fn >>= 1;
    sn >>= 1;
  }
  return sn === 0 && r === proof.rootHash;
}

export interface ConsistencyProof {
  firstSize: number;
  secondSize: number;
  firstRoot: string;
  secondRoot: string;
  proofNodes: string[];
}

function subproof(m: number, leafHashes: string[], startFromOld: boolean): string[] {
  const n = leafHashes.length;
  if (m === n) {
    return startFromOld ? [] : [mthHex(leafHashes)];
  }
  const k = largestPowerOfTwoLessThan(n);
  if (m <= k) {
    return [...subproof(m, leafHashes.slice(0, k), startFromOld), mthHex(leafHashes.slice(k))];
  }
  return [...subproof(m - k, leafHashes.slice(k), false), mthHex(leafHashes.slice(0, k))];
}

export function generateConsistencyProof(leaves: unknown[], firstSize: number): ConsistencyProof {
  const secondSize = leaves.length;
  if (firstSize <= 0 || firstSize > secondSize) {
    throw new RangeError(`Invalid firstSize=${firstSize} for secondSize=${secondSize}`);
  }
  const leafHashes = leaves.map((l) => hashLeaf(l));
  return {
    firstSize,
    secondSize,
    firstRoot: mthHex(leafHashes.slice(0, firstSize)),
    secondRoot: mthHex(leafHashes),
    proofNodes: subproof(firstSize, leafHashes, true),
  };
}
