package com.odasi.fieldwright.data.pipeline

import com.odasi.fieldwright.crypto.ChainVerifier
import com.odasi.fieldwright.crypto.MerkleLog
import com.odasi.fieldwright.data.Fixtures
import com.odasi.fieldwright.data.FakeSha256
import com.odasi.fieldwright.data.FakeSigner
import com.odasi.fieldwright.model.GateCheck
import com.odasi.fieldwright.model.SealState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The capture → seal order, verified with a fake hasher and signer:
 * assemble → PacketAssembler.seal → ChainVerifier → Merkle inclusion → SealEngine.evaluate.
 * Also proves the two properties the product depends on: a blocking gate prevents a seal,
 * and editing a committed record breaks chain verification.
 */
class SealPipelineTest {

    private val hasher = Fixtures.hasher
    private val signer = Fixtures.signer
    private val pipeline = SealPipeline(hasher, signer)

    private fun seal(
        gates: List<GateCheck> = Fixtures.passingGates(),
        deviceLogHeads: List<String> = emptyList(),
    ) = pipeline.assembleAndSeal(
        job = Fixtures.job(),
        operator = Fixtures.operator(),
        consent = Fixtures.consent(),
        media = Fixtures.media(),
        measurements = listOf(Fixtures.measurement()),
        checklist = Fixtures.checklist(Fixtures.job()),
        gates = gates,
        deviceLogHeads = deviceLogHeads,
        nowEpochMs = Fixtures.NOW,
    )

    @Test
    fun `capture seals only after evidence, chain and inclusion are complete`() {
        val result = seal()

        assertEquals(SealState.EARNED, result.packet.seal, result.evaluation.reasons.toString())
        assertTrue(result.evaluation.canSeal)
        assertTrue(result.verification.valid, result.verification.detail)
        assertTrue(result.merkleIncluded)
        assertEquals(1, result.merkleTreeSize)
        assertEquals(0, result.merkleIndex)
        assertEquals(Fixtures.NOW, result.packet.sealedAtEpochMs)
        assertTrue(result.packet.records.size >= 5, "expected the capture session chain, got ${result.packet.records.size}")
        assertEquals(result.packet.packetHash, result.packet.verificationHead())
    }

    @Test
    fun `sealed packet chain re-verifies from genesis and is append-only`() {
        val result = seal()
        val verification = ChainVerifier.verify(result.packet.records, hasher, signer)
        assertTrue(verification.valid, verification.detail)
        assertEquals(result.packet.records.size, verification.entries)
        assertEquals(result.packet.packetHash, verification.headHash)

        // Every record names its signer and carries a signature the signer can verify.
        result.packet.records.forEach { record ->
            assertEquals(signer.keyId, record.signerKeyId)
            assertTrue(signer.verify(record.hash, record.signatureBase64!!))
        }
    }

    @Test
    fun `editing a committed record is detected by chain verification`() {
        val result = seal()
        val tampered = result.packet.records.toMutableList()
        tampered[2] = tampered[2].copy(summary = "A different story entirely")

        val verification = ChainVerifier.verify(tampered, hasher, signer)
        assertFalse(verification.valid)
        assertTrue(verification.brokenAtSeq != null)
        assertTrue(verification.detail.contains("does not match its hash") || verification.detail.contains("does not")
        || verification.detail.contains("not link"), verification.detail)
    }

    @Test
    fun `a blocking gate stops the seal even when every record is in place`() {
        val blockingFailure = Fixtures.passingGates().map { gate ->
            if (gate.id == "gate.redaction") gate.copy(passed = false, detail = "Faces still require redaction") else gate
        }
        val result = seal(gates = blockingFailure)

        assertEquals(SealState.PENDING, result.packet.seal)
        assertFalse(result.evaluation.canSeal)
        assertTrue(result.evaluation.reasons.any { it.contains("Redaction", ignoreCase = true) }, result.evaluation.reasons.toString())
        // The attempt is still a valid, verifiable chain: append-only means it is recorded.
        assertTrue(result.verification.valid)
    }

    @Test
    fun `a second packet is included in the device log at the next leaf`() {
        val first = seal()
        val firstHead = first.packet.packetHash!!
        assertEquals(1, first.merkleTreeSize)

        val second = seal(deviceLogHeads = listOf(firstHead))
        assertEquals(2, second.merkleTreeSize)
        assertEquals(1, second.merkleIndex)
        assertTrue(second.merkleIncluded)
        assertTrue(
            MerkleLog.verify(
                leaf = second.packet.packetHash!!,
                proof = second.packet.merkleProof,
                index = second.merkleIndex,
                treeSize = second.merkleTreeSize,
                root = second.merkleRoot!!,
            ),
        )
        // The first packet's proof remains valid at its old tree size (RFC 6962 append-only tree).
        assertTrue(
            MerkleLog.verify(
                leaf = firstHead,
                proof = first.packet.merkleProof,
                index = first.merkleIndex,
                treeSize = first.merkleTreeSize,
                root = first.merkleRoot!!,
            ),
        )
    }

    @Test
    fun `packet signature is over the sealed head hash`() {
        val result = seal()
        val head = result.packet.packetHash!!
        assertEquals(head, result.verification.headHash)
        assertTrue(signer.verify(head, result.packet.signatureBase64!!))
    }

    @Test
    fun `fake hasher round-trips the chain rule deterministically`() {
        val sha = FakeSha256()
        assertEquals(sha.sha256Hex("fieldwright"), sha.sha256Hex("fieldwright"))
        assertEquals(64, sha.sha256Hex("fieldwright").length)
        assertEquals(FakeSigner().keyId, FakeSigner().keyId)
    }
}

/** Small helper so the test reads naturally. */
private fun com.odasi.fieldwright.model.EvidencePacket.verificationHead(): String =
    records.lastOrNull()?.hash ?: ""
