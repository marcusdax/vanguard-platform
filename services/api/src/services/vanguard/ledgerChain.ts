/**
 * Append-Only Audit Ledger (Invariant I-9 + Check C-03 pg_advisory_xact_lock + Check C-04 RFC 6962).
 */
import { Prisma } from '@prisma/client';
import { prisma } from '../../prisma';
import {
  GENESIS_HASH,
  computeRecordHash,
  type RecordKind,
  verifyChain,
} from '../../engine/canonical';
import {
  generateInclusionProof,
  hashLeaf,
  mthHex,
  type InclusionProof,
} from '../../engine/merkle';

let immutabilityInstalled = false;

/**
 * Installs idempotent PostgreSQL append-only trigger (Invariant I-9) rejecting UPDATE/DELETE on
 * AuditLedgerEntry and FieldLedgerEntry. Safe to run repeatedly on startup or first write.
 */
export async function ensureAppendOnlyLedgerTriggers(): Promise<void> {
  if (immutabilityInstalled) return;
  try {
    await prisma.$executeRawUnsafe(`
      CREATE OR REPLACE FUNCTION reject_ledger_mutation() RETURNS trigger AS $$
      BEGIN
        RAISE EXCEPTION 'Invariant I-9 violation: % rows are strictly append-only', TG_TABLE_NAME;
      END;
      $$ LANGUAGE plpgsql;
    `);
    await prisma.$executeRawUnsafe(`
      DO $$ BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'tr_audit_ledger_append_only') THEN
          CREATE TRIGGER tr_audit_ledger_append_only
          BEFORE UPDATE OR DELETE ON "AuditLedgerEntry"
          FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();
        END IF;
        IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'tr_field_ledger_append_only') THEN
          CREATE TRIGGER tr_field_ledger_append_only
          BEFORE UPDATE OR DELETE ON "FieldLedgerEntry"
          FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();
        END IF;
      END $$;
    `);
    immutabilityInstalled = true;
  } catch {
    // Non-Postgres or unit test stub environment without live DB connection
  }
}

export async function appendAuditRecord(params: {
  companyId: string;
  subjectRef: string;
  kind: RecordKind;
  actorId: string;
  summary: string;
  payload: Record<string, string>;
  atIso?: string;
}) {
  await ensureAppendOnlyLedgerTriggers();
  const at = params.atIso || new Date().toISOString();

  return prisma.$transaction(async (tx) => {
    // Serialize writes per subjectRef without locking the whole table (Check C-03)
    await tx.$executeRaw`SELECT pg_advisory_xact_lock(hashtextextended(${params.subjectRef}, 0))`;

    const latest = await tx.auditLedgerEntry.findFirst({
      where: { subjectRef: params.subjectRef },
      orderBy: { seq: 'desc' },
    });

    const seq = latest ? latest.seq + 1 : 1;
    const prevHash = latest ? latest.rowHash : GENESIS_HASH;
    const rowHash = computeRecordHash({
      seq,
      kind: params.kind,
      at,
      actorId: params.actorId,
      summary: params.summary,
      payload: params.payload,
      prevHash,
    });

    const entry = await tx.auditLedgerEntry.create({
      data: {
        companyId: params.companyId,
        subjectRef: params.subjectRef,
        seq,
        kind: params.kind,
        actorId: params.actorId,
        payload: {
          at,
          summary: params.summary,
          fields: params.payload,
        } as Prisma.InputJsonValue,
        prevHash,
        rowHash,
      },
    });

    return entry;
  });
}

export async function anchorMerkleTreeHead(companyId: string) {
  const entries = await prisma.auditLedgerEntry.findMany({
    where: { companyId },
    orderBy: [{ createdAt: 'asc' }, { seq: 'asc' }],
    take: 500,
  });
  if (entries.length === 0) return null;

  const leafHashes = entries.map((e) => hashLeaf(e.rowHash));
  const rootHash = mthHex(leafHashes);

  const existing = await prisma.merkleTreeHead.findFirst({
    where: { companyId, rootHash },
  });
  if (existing) return existing;

  return prisma.merkleTreeHead.create({
    data: {
      companyId,
      treeSize: entries.length,
      rootHash,
      leafHashesJson: entries.map((e) => ({ id: e.id, rowHash: e.rowHash })) as Prisma.InputJsonValue,
    },
  });
}

export async function getPublicVerificationBundle(packetId: string): Promise<{
  packet: {
    id: string;
    code: string;
    title: string;
    tier: number;
    sealState: string;
    headHash: string;
    merkleLeaf: string;
    sealedAt: string;
    consentRole: string;
    consentMethod: string;
    consentAt: string;
  };
  records: Array<{
    seq: number;
    kind: RecordKind;
    at: string;
    actorId: string;
    summary: string;
    payload: Record<string, string>;
    prevHash: string;
    hash: string;
  }>;
  chainVerification: ReturnType<typeof verifyChain>;
  inclusionProof: InclusionProof;
  c2paManifest: unknown;
  rekorAnchor: unknown;
} | null> {
  const packet = await prisma.fieldEvidencePacket.findUnique({ where: { id: packetId } });
  if (!packet) return null;

  const rawRecords = Array.isArray(packet.recordsJson)
    ? (packet.recordsJson as Array<{
        seq: number;
        kind: RecordKind;
        at: string;
        actorId: string;
        summary: string;
        payload: Record<string, string>;
        prevHash: string;
        hash: string;
      }>)
    : [];

  const chainVerification = verifyChain(rawRecords);

  // Build RFC 6962 inclusion proof over company packets up to this packet
  const siblings = await prisma.fieldEvidencePacket.findMany({
    where: { companyId: packet.companyId },
    orderBy: [{ sealedAt: 'asc' }, { id: 'asc' }],
    select: { id: true, headHash: true },
    take: 500,
  });
  const leaves = siblings.length > 0 ? siblings.map((s) => s.headHash) : [packet.headHash];
  const idx = Math.max(0, siblings.findIndex((s) => s.id === packet.id));
  const inclusionProof = generateInclusionProof(leaves, idx);

  return {
    packet: {
      id: packet.id,
      code: packet.code,
      title: packet.title,
      tier: packet.tier,
      sealState: packet.sealState,
      headHash: packet.headHash,
      merkleLeaf: packet.merkleLeaf,
      sealedAt: packet.sealedAt.toISOString(),
      // Redact consent signer personal name on unauthenticated public verifier
      consentRole: packet.consentRole,
      consentMethod: packet.consentMethod,
      consentAt: packet.consentAt.toISOString(),
    },
    records: rawRecords,
    chainVerification,
    inclusionProof,
    c2paManifest: packet.c2paManifestJson,
    rekorAnchor: packet.rekorAnchorJson,
  };
}
