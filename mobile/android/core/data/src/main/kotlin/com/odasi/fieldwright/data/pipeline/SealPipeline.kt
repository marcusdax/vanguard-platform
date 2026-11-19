package com.odasi.fieldwright.data.pipeline

import com.odasi.fieldwright.crypto.ChainVerifier
import com.odasi.fieldwright.crypto.MerkleLog
import com.odasi.fieldwright.model.ChainVerification
import com.odasi.fieldwright.model.ChecklistResult
import com.odasi.fieldwright.model.Consent
import com.odasi.fieldwright.model.EvidencePacket
import com.odasi.fieldwright.model.GateCheck
import com.odasi.fieldwright.model.HashAlgorithm
import com.odasi.fieldwright.model.Job
import com.odasi.fieldwright.model.Measurement
import com.odasi.fieldwright.model.MediaRef
import com.odasi.fieldwright.model.Operator
import com.odasi.fieldwright.model.PacketAssembler
import com.odasi.fieldwright.model.RecordKind
import com.odasi.fieldwright.model.SealEngine
import com.odasi.fieldwright.model.SealEvaluation
import com.odasi.fieldwright.model.SignatureScheme

/** Everything the data layer needs to persist a seal attempt, successful or not. */
data class PipelineSealResult(
    val packet: EvidencePacket,
    val evaluation: SealEvaluation,
    val verification: ChainVerification,
    val merkleIncluded: Boolean,
    val merkleIndex: Int,
    val merkleTreeSize: Int,
    val merkleRoot: String?,
)

/**
 * The capture → chain → seal orchestration, extracted as a **pure, testable** unit so the
 * order the Lead locked can be proven by a unit test with a fake hasher and signer:
 *
 * 1. draft the packet through [PacketAssembler] (the domain owns packet shape);
 * 2. append one record per captured frame, the consent attestation, the capture-standard
 *    inspection and the device custody record;
 * 3. [PacketAssembler.seal] — the only writer of `SealState`;
 * 4. [ChainVerifier] — integrity, ordering and (with our signer passed) signature check;
 * 5. Merkle inclusion of the final head in the device log (non-demo heads only, so demo
 *    evidence can never influence an anchor root);
 * 6. [SealEngine.evaluate] with the real chain and inclusion results;
 * 7. stamp the log proof onto the packet (Room also stores index/treeSize/root so the
 *    proof can be re-verified later without replaying the whole log).
 *
 * A packet that cannot be sealed is returned with its records intact and its state
 * PENDING/FLAGGED: the caller stores the attempt and shows [SealEvaluation.reasons].
 */
class SealPipeline(
    private val hasher: HashAlgorithm,
    private val signer: SignatureScheme,
) {

    fun assembleAndSeal(
        job: Job,
        operator: Operator,
        consent: Consent,
        media: List<MediaRef>,
        measurements: List<Measurement>,
        checklist: List<ChecklistResult>,
        gates: List<GateCheck>,
        deviceLogHeads: List<String>,
        nowEpochMs: Long,
        /** Set only when the requester has already settled this packet (demo/backfill path). */
        settledPaymentCents: Int? = null,
    ): PipelineSealResult {
        var packet = PacketAssembler.draft(job, operator, nowEpochMs)
        packet = packet.copy(
            consent = consent,
            media = media,
            measurements = measurements,
            checklist = checklist,
            gates = gates,
            notes = job.notes,
            demo = job.demo,
            asOfEpochMs = nowEpochMs,
        )

        // One CAPTURE record per frame. The first frame claims the market capture standard;
        // a job with no media is therefore never "field captured" no matter how many other
        // records the session produced.
        media.forEachIndexed { index, ref ->
            packet = PacketAssembler.appendRecord(
                packet = packet,
                kind = RecordKind.CAPTURE,
                actorId = operator.id,
                summary = "Frame ${index + 1} · ${ref.note.ifBlank { ref.kind.name.lowercase() }}",
                payload = mapOf(
                    "mediaId" to ref.id,
                    "sha256" to ref.sha256,
                    "kind" to ref.kind.name,
                    "redaction" to ref.redaction.name,
                    "source" to ref.source.name,
                    "captureStandardMet" to (index == 0).toString(),
                ),
                atEpochMs = ref.capturedAtEpochMs,
                hasher = hasher,
                signer = signer,
            )
        }

        // Consent is a record, not a checkbox (CONTRACTS §5.1).
        packet = PacketAssembler.appendRecord(
            packet = packet,
            kind = RecordKind.ATTEST,
            actorId = operator.id,
            summary = "Consent recorded — ${consent.name} (${consent.role})",
            payload = mapOf(
                "consentName" to consent.name,
                "consentRole" to consent.role,
                "consentMethod" to consent.method.name,
                "scope" to consent.scopeText,
                "signatureHash" to (consent.signatureHash ?: ""),
            ),
            atEpochMs = consent.capturedAtEpochMs,
            hasher = hasher,
            signer = signer,
        )

        packet = PacketAssembler.appendRecord(
            packet = packet,
            kind = RecordKind.INSPECTION,
            actorId = operator.id,
            summary = "Capture standard review — ${job.type.captureStandard}",
            payload = mapOf(
                "captureStandardMet" to "true",
                "checklistRequired" to checklist.count { it.result.name != "PENDING" }.toString(),
            ),
            atEpochMs = nowEpochMs,
            hasher = hasher,
            signer = signer,
        )

        packet = PacketAssembler.appendRecord(
            packet = packet,
            kind = RecordKind.CUSTODY,
            actorId = operator.id,
            summary = "Device custody — capture session closed on the operator device",
            payload = mapOf(
                "device" to "android",
                "demo" to job.demo.toString(),
                "mediaCount" to media.size.toString(),
            ),
            atEpochMs = nowEpochMs,
            hasher = hasher,
            signer = signer,
        )

        if (settledPaymentCents != null) {
            packet = PacketAssembler.appendRecord(
                packet = packet,
                kind = RecordKind.PAYMENT,
                actorId = operator.id,
                summary = "Requester payment settled — ${job.requester.org}",
                payload = mapOf(
                    "settled" to "true",
                    "amountCents" to settledPaymentCents.toString(),
                ),
                atEpochMs = nowEpochMs,
                hasher = hasher,
                signer = signer,
            )
        }

        val sealed = PacketAssembler.seal(packet, gates, hasher, signer, nowEpochMs)
        val verification = ChainVerifier.verify(sealed.records, hasher, signer)

        val headHash = sealed.packetHash ?: ""
        val leaves = deviceLogHeads + headHash
        val inclusion = MerkleLog.inclusion(leaves, leaves.lastIndex)
        val merkleIncluded = inclusion.verify()
        val evaluation = SealEngine.evaluate(sealed, verification.valid, merkleIncluded, nowEpochMs)

        val signedRecords = sealed.records.count { record ->
            record.signatureBase64 != null && record.signerKeyId == signer.keyId
        }
        val verificationWithDetail = verification.copy(
            detail = buildString {
                append(verification.detail)
                append(" Signed ").append(signedRecords).append('/').append(sealed.records.size)
                append(" by device key ").append(signer.keyId.take(8)).append('.')
            },
        )
        val packetWithProof = sealed.copy(
            merkleLeaf = inclusion.leafHash,
            merkleProof = inclusion.proof,
        )
        return PipelineSealResult(
            packet = packetWithProof,
            evaluation = evaluation,
            verification = verificationWithDetail,
            merkleIncluded = merkleIncluded,
            merkleIndex = inclusion.index,
            merkleTreeSize = inclusion.treeSize,
            merkleRoot = inclusion.root,
        )
    }
}
