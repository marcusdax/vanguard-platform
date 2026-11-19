package com.odasi.fieldwright.data.demo

import com.odasi.fieldwright.data.pipeline.SealPipeline
import com.odasi.fieldwright.model.ChecklistResult
import com.odasi.fieldwright.model.Consent
import com.odasi.fieldwright.model.ConsentMethod
import com.odasi.fieldwright.model.EvidencePacket
import com.odasi.fieldwright.model.GateCheck
import com.odasi.fieldwright.model.HashAlgorithm
import com.odasi.fieldwright.model.Job
import com.odasi.fieldwright.model.Measurement
import com.odasi.fieldwright.model.MediaKind
import com.odasi.fieldwright.model.MediaRef
import com.odasi.fieldwright.model.MediaSource
import com.odasi.fieldwright.model.Operator
import com.odasi.fieldwright.model.ProofKind
import com.odasi.fieldwright.model.RedactionState
import com.odasi.fieldwright.model.SealState
import com.odasi.fieldwright.model.SignatureScheme
import com.odasi.fieldwright.model.StepResult

/**
 * Builds the two sealed sample-book packets through the **real** capture pipeline
 * ([SealPipeline] → `PacketAssembler` → `ChainVerifier` → `MerkleLog` → `SealEngine`).
 *
 * This matters for honesty: if the demo packet could be fabricated straight into Room, the
 * Vault screen would be showing theatre. Instead the sample packet carries a genuine
 * genesis-anchored, signed record chain and a genuine single-leaf inclusion proof; the only
 * thing that makes it demo data is the `demo = true` flag carried from the job into every
 * record and excluded from any anchor payload.
 */
class DemoPacketFactory(
    private val hasher: HashAlgorithm,
    private val signer: SignatureScheme,
) {

    data class DemoSealedPacket(
        val packet: EvidencePacket,
        val merkleTreeSize: Int,
        val merkleRoot: String?,
        val merkleIndex: Int,
    )

    fun build(jobs: List<Job>, operator: Operator, nowEpochMs: Long): List<DemoSealedPacket> {
        val pipeline = SealPipeline(hasher, signer)
        return listOf(PACKET_0994, PACKET_1012).mapNotNull { packetId ->
            val fixture = DemoCatalog.packetFixture(packetId, nowEpochMs) ?: return@mapNotNull null
            val job = jobs.firstOrNull { it.id == fixture.jobId } ?: return@mapNotNull null
            val sealedAt = job.sealedAtEpochMs ?: nowEpochMs
            val consent = Consent(
                name = fixture.consentName,
                role = fixture.consentRole,
                scopeText = job.consentScope,
                method = ConsentMethod.IN_APP_SIGNATURE,
                capturedAtEpochMs = fixture.consentCapturedAtEpochMs,
                signatureHash = hasher.sha256Hex("demo-consent:${fixture.consentName}:${job.id}"),
                verifiedByOperatorId = operator.id,
            )
            val media = fixture.photoLabels.mapIndexed { index, label ->
                MediaRef(
                    id = "MD-${job.id.removePrefix("FW-")}-${(index + 1).toString().padStart(2, '0')}",
                    kind = MediaKind.PHOTO,
                    sha256 = hasher.sha256Hex("demo-frame:${job.id}:$index:$label"),
                    bytes = 1_840_000L + index * 4_096L,
                    capturedAtEpochMs = sealedAt - (fixture.photoLabels.size - index) * 60_000L,
                    width = 4_032,
                    height = 3_024,
                    redaction = RedactionState.NONE,
                    source = MediaSource.SIMULATED,
                    note = label,
                )
            }
            val measurement = Measurement(
                id = "MS-${job.id.removePrefix("FW-")}-01",
                label = fixture.measurement.label,
                meters = fixture.measurement.meters,
                referenceLabel = fixture.measurement.referenceLabel,
                referenceMeters = fixture.measurement.referenceMeters,
                pixelSpan = fixture.measurement.pixelSpan,
                calibratedAtEpochMs = sealedAt - 2 * 60 * 60_000L,
                source = MediaSource.SIMULATED,
            )
            val checklist = job.checklist.mapIndexed { index, step ->
                ChecklistResult(
                    key = step.key,
                    result = StepResult.PASS,
                    note = if (step.proofKind == ProofKind.PHOTO) {
                        media[index % media.size].note
                    } else {
                        "Confirmed on site."
                    },
                    mediaIds = if (step.proofKind == ProofKind.PHOTO) listOf(media[index % media.size].id) else emptyList(),
                )
            }
            val gates = demoGates(job, consent, media.size)
            val result = pipeline.assembleAndSeal(
                job = job,
                operator = operator,
                consent = consent,
                media = media,
                measurements = listOf(measurement),
                checklist = checklist,
                gates = gates,
                // Demo packets are never anchor inputs: their proof is the single-leaf proof
                // PacketAssembler.seal stamps, i.e. a one-leaf log rooted at this packet.
                deviceLogHeads = emptyList(),
                nowEpochMs = sealedAt,
                settledPaymentCents = if (fixture.paid) job.payoutCents else null,
            )
            check(result.verification.valid) {
                "Demo packet $packetId produced an invalid chain: ${result.verification.detail}"
            }
            check(result.packet.seal == SealState.EARNED) {
                "Demo packet $packetId failed to seal: ${result.evaluation.reasons.joinToString(" · ")}"
            }
            DemoSealedPacket(
                packet = result.packet.copy(
                    id = packetId,
                    createdAtEpochMs = sealedAt - 3 * 60 * 60_000L,
                ),
                merkleTreeSize = result.merkleTreeSize,
                merkleRoot = result.merkleRoot,
                merkleIndex = result.merkleIndex,
            )
        }
    }

    private fun demoGates(job: Job, consent: Consent, mediaCount: Int): List<GateCheck> = listOf(
        GateCheck(
            id = "gate.consent",
            label = "Consent on file",
            passed = true,
            detail = "${consent.name} · ${consent.method.label} · scope: ${job.type.shortLabel}",
            blocking = true,
        ),
        GateCheck(
            id = "gate.geofence",
            label = "Geofence satisfied",
            passed = true,
            detail = "Arrived inside the ${job.site.geofenceRadiusMeters} m fence; no override.",
            blocking = true,
        ),
        GateCheck(
            id = "gate.redaction",
            label = "Redaction resolved",
            passed = true,
            detail = "$mediaCount frame(s); no faces or plates in frame.",
            blocking = true,
        ),
        GateCheck(
            id = "gate.checklist",
            label = "Required checklist",
            passed = true,
            detail = "${job.checklist.size} of ${job.checklist.size} required steps recorded.",
            blocking = true,
        ),
        GateCheck(
            id = "gate.measurement",
            label = "Measurement calibrated",
            passed = true,
            detail = "Reference scale recorded on site.",
            blocking = true,
        ),
        GateCheck(
            id = "gate.chain",
            label = "Chain verifies",
            passed = true,
            detail = "Genesis-anchored chain materialised and signed at seal.",
            blocking = true,
        ),
    )

    private companion object {
        const val PACKET_0994 = "FW-0994"
        const val PACKET_1012 = "FW-1012"
    }
}
