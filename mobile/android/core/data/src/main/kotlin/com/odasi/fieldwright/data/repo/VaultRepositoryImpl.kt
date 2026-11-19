package com.odasi.fieldwright.data.repo

import com.odasi.fieldwright.crypto.ChainVerifier
import com.odasi.fieldwright.crypto.MerkleLog
import com.odasi.fieldwright.data.VaultRepository
import com.odasi.fieldwright.data.db.OutboxKind
import com.odasi.fieldwright.data.db.PacketAggregate
import com.odasi.fieldwright.data.demo.DemoCatalog
import com.odasi.fieldwright.data.mapper.Mappers
import com.odasi.fieldwright.model.CanonicalJson
import com.odasi.fieldwright.model.ChainVerification
import com.odasi.fieldwright.model.EvidencePacket
import com.odasi.fieldwright.model.Idempotency
import com.odasi.fieldwright.model.PacketAssembler
import com.odasi.fieldwright.model.PacketExport
import com.odasi.fieldwright.model.RecordKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The vault: read-only packet surface, chain verification, canonical export, and
 * append-only corrections.
 *
 * Two rules are visible in the code:
 * - **Nothing is edited.** [appendCorrection] appends a CORRECTION record; the packet row
 *   and every earlier record stay byte-identical.
 * - **Seal integrity is separate from log inclusion.** A packet reports chain integrity
 *   (recomputed from genesis) and, separately, whether its stored Merkle proof verifies
 *   against the head it sealed at. Corrections move the live head but never the sealed
 *   head, which is why Room keeps `sealedHeadHash` next to the packet.
 */
internal class VaultRepositoryImpl(private val stores: DataStores) : VaultRepository {

    override fun observePackets(): Flow<List<EvidencePacket>> =
        stores.packets.observeAll().map { rows -> rows.map { row -> with(Mappers) { row.toModel() } } }

    override fun observePacket(packetId: String): Flow<EvidencePacket?> =
        stores.packets.observeOne(packetId).map { row -> row?.let { aggregate -> with(Mappers) { aggregate.toModel() } } }

    override suspend fun verifyChain(packetId: String?): Result<ChainVerification> = runCatching {
        if (packetId != null) {
            val aggregate = stores.packets.get(packetId)
                ?: throw RepositoryException("Packet $packetId is not in the vault.")
            verifyAggregate(aggregate)
        } else {
            val aggregates = stores.packets.all()
            if (aggregates.isEmpty()) {
                return@runCatching ChainVerification(
                    valid = true,
                    entries = 0,
                    brokenAtSeq = null,
                    headHash = PacketAssembler.GENESIS_HASH,
                    detail = "No packets in the vault yet — nothing to verify.",
                )
            }
            var entries = 0
            var broken: ChainVerification? = null
            var head = PacketAssembler.GENESIS_HASH
            for (aggregate in aggregates.sortedBy { it.packet.createdAtEpochMs }) {
                val verification = verifyAggregate(aggregate)
                entries += verification.entries
                head = verification.headHash
                if (!verification.valid && broken == null) broken = verification
            }
            if (broken != null) {
                broken.copy(
                    entries = entries,
                    detail = "Vault chain health failed: ${broken.detail}",
                )
            } else {
                ChainVerification(
                    valid = true,
                    entries = entries,
                    brokenAtSeq = null,
                    headHash = head,
                    detail = "Vault verifies — integrity and ordering across ${aggregates.size} packet(s), " +
                        "$entries record(s) from genesis.",
                )
            }
        }
    }

    override suspend fun exportPacket(packetId: String): Result<PacketExport> = runCatching {
        val aggregate = stores.packets.get(packetId)
            ?: throw RepositoryException("Packet $packetId is not in the vault.")
        val packet = with(Mappers) { aggregate.toModel() }
        val sealedHead = aggregate.packet.sealedHeadHash ?: packet.packetHash ?: ""
        val fields = linkedMapOf(
            "standard" to PacketAssembler.SEAL_STANDARD,
            "packetId" to packet.id,
            "jobId" to packet.jobId,
            "jobType" to packet.jobType.code,
            "title" to packet.title,
            "requester" to packet.requesterLabel,
            "operatorId" to packet.operatorId,
            "siteAddress" to packet.site.address,
            "sitePlan" to packet.site.planLabel,
            "geofenceRadiusMeters" to packet.site.geofenceRadiusMeters.toString(),
            "tierLevel" to packet.tier.level.toString(),
            "tierLabel" to packet.tier.label,
            "sealState" to packet.seal.name,
            "recordCount" to packet.records.size.toString(),
            "chainHead" to sealedHead,
            "packetSignature" to (packet.signatureBase64 ?: ""),
            "merkleLeaf" to (aggregate.packet.merkleLeaf ?: ""),
            "merkleRoot" to (aggregate.packet.merkleRoot ?: ""),
            "merkleTreeSize" to (aggregate.packet.merkleTreeSize?.toString() ?: ""),
            "merkleIndex" to (aggregate.packet.merkleIndex?.toString() ?: ""),
            "merkleProof" to packet.merkleProof.joinToString(","),
            "consentName" to (packet.consent?.name ?: ""),
            "consentRole" to (packet.consent?.role ?: ""),
            "consentMethod" to (packet.consent?.method?.name ?: ""),
            "consentScope" to (packet.consent?.scopeText ?: ""),
            "asOfEpochMs" to packet.asOfEpochMs.toString(),
            "createdAtEpochMs" to packet.createdAtEpochMs.toString(),
            "sealedAtEpochMs" to (packet.sealedAtEpochMs?.toString() ?: ""),
            "demo" to packet.demo.toString(),
        )
        val canonical = CanonicalJson.encode(fields)
        val recordCount = packet.records.size
        val custodyText = buildString {
            appendLine("Vanguard Field — chain of custody")
            appendLine("Packet ${packet.id} · ${packet.title}")
            appendLine("Requester: ${packet.requesterLabel}")
            appendLine("Site: ${packet.site.address} · ${packet.site.planLabel}")
            appendLine(
                "Consent: ${packet.consent?.let { "${it.name} (${it.role}) · ${it.method.label}" } ?: "none on file"}",
            )
            appendLine("Records: $recordCount · sealed head ${sealedHead.take(16)}")
            appendLine(
                "Chain: ${if (packet.seal.name == "EARNED") "sealed" else packet.seal.name.lowercase()} " +
                    "· tier ${packet.tier.level} (${packet.tier.label})",
            )
            append(
                if (packet.demo) {
                    "SAMPLE — demo evidence, excluded from any log anchor."
                } else {
                    "Verify: recompute each record hash from genesis and compare the head."
                },
            )
        }
        PacketExport(
            fileName = "${packet.id}.packet.json",
            canonicalJson = canonical,
            custodyText = custodyText,
            chainHead = sealedHead,
            recordCount = recordCount,
        )
    }

    override suspend fun appendCorrection(
        packetId: String,
        note: String,
        atEpochMs: Long,
    ): Result<EvidencePacket> = runCatching {
        require(note.isNotBlank()) { "A correction needs a note explaining what is being corrected." }
        val aggregate = stores.packets.get(packetId)
            ?: throw RepositoryException("Packet $packetId is not in the vault.")
        val packet = with(Mappers) { aggregate.toModel() }
        if (packet.seal != com.odasi.fieldwright.model.SealState.EARNED) {
            throw RepositoryException("Only a sealed packet accepts corrections; ${packet.id} is ${packet.seal.name}.")
        }
        val operatorId = stores.operator.getOperator()?.id ?: DemoCatalog.DEMO_OPERATOR_ID
        val corrected = PacketAssembler.appendRecord(
            packet = packet,
            kind = RecordKind.CORRECTION,
            actorId = operatorId,
            summary = "Correction — $note",
            payload = mapOf("note" to note, "appendedAt" to atEpochMs.toString()),
            atEpochMs = atEpochMs,
            hasher = stores.hasher,
            signer = stores.signer,
        )
        stores.transaction {
            stores.packets.insertRecord(with(Mappers) { corrected.records.last().toEntity(packetId) })
            stores.outbox.enqueue(
                idempotencyKey = Idempotency.key("packet.correction", packetId, corrected.records.size.toString()),
                kind = OutboxKind.PACKET_CORRECTION,
                method = "POST",
                path = "/packets/$packetId/corrections",
                payload = mapOf(
                    "packetId" to packetId,
                    "seq" to corrected.records.last().seq.toString(),
                    "recordHash" to corrected.records.last().hash,
                    "at" to atEpochMs.toString(),
                    "demo" to packet.demo.toString(),
                ),
                demo = packet.demo,
                nowEpochMs = atEpochMs,
            )
        }
        corrected
    }

    private fun verifyAggregate(aggregate: PacketAggregate): ChainVerification {
        val packet = with(Mappers) { aggregate.toModel() }
        val verification = ChainVerifier.verify(packet.records, stores.hasher, stores.signer)
        val sealedHead = aggregate.packet.sealedHeadHash ?: packet.packetHash
        val treeSize = aggregate.packet.merkleTreeSize
        val index = aggregate.packet.merkleIndex
        val root = aggregate.packet.merkleRoot
        val merkleIncluded = sealedHead != null && treeSize != null && index != null && root != null &&
            MerkleLog.verify(sealedHead, packet.merkleProof, index, treeSize, root)
        val signed = packet.records.count { it.signatureBase64 != null }
        val unsigned = packet.records.size - signed
        val packetSignatureValid = ChainVerifier.verifyPacketSignature(packet, stores.signer)
        val detail = buildString {
            append(verification.detail)
            append(" Signatures: $signed signed, $unsigned unsigned.")
            append(
                if (packetSignatureValid) {
                    " Packet signature verified against this device key."
                } else {
                    " Packet signature not verifiable with this device key (absent, tampered, or signed elsewhere)."
                },
            )
            append(
                if (merkleIncluded) {
                    " Log inclusion verified (leaf $index of $treeSize)."
                } else {
                    " Log inclusion not present/valid for this device log."
                },
            )
        }
        return verification.copy(detail = detail)
    }
}
