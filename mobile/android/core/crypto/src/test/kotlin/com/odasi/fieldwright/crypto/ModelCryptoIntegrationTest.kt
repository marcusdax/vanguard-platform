package com.odasi.fieldwright.crypto

import com.odasi.fieldwright.model.ChainRecord
import com.odasi.fieldwright.model.EvidenceTier
import com.odasi.fieldwright.model.MerkleHash
import com.odasi.fieldwright.model.PacketAssembler
import com.odasi.fieldwright.model.SealEngine
import com.odasi.fieldwright.model.SealState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end wiring of model writers (`PacketAssembler`) with crypto readers
 * (`ChainVerifier`, `MerkleLog`). This is the contract the seal repository relies on:
 * seal offline, prove inclusion, then let `SealEngine` be the authority on "sealed".
 */
class ModelCryptoIntegrationTest {

    private val hasher = Sha256
    private val signer = CryptoFixtures.signer

    @Test
    fun `a sealed packet verifies end to end`() {
        val packet = CryptoFixtures.sealedPacket()

        val chain = ChainVerifier.verify(packet, hasher)
        val signature = ChainVerifier.verifyPacketSignature(packet, signer)
        val singleLeafIncluded = MerkleLog.verify(
            leaf = packet.packetHash!!,
            proof = packet.merkleProof,
            index = 0,
            root = packet.merkleLeaf!!,
        )
        val evaluation = SealEngine.evaluate(packet, chain.valid, singleLeafIncluded, TEST_NOW + 3_000)

        assertTrue(chain.valid)
        assertTrue(signature)
        assertTrue(singleLeafIncluded)
        assertEquals(packet.packetHash, chain.headHash)
        assertEquals(SealState.EARNED, evaluation.state)
        assertTrue(evaluation.canSeal)
        assertTrue(evaluation.reasons.isEmpty())
        assertEquals(EvidenceTier.VERIFIED_BY_AUCTUM, evaluation.tier)
    }

    @Test
    fun `the sealed merkle leaf is exactly the log leaf for the head hash`() {
        val packet = CryptoFixtures.sealedPacket()

        assertEquals(MerkleHash.leafHash(packet.packetHash!!, hasher), packet.merkleLeaf)
        assertEquals(MerkleLog.leafHash(packet.packetHash!!), packet.merkleLeaf)
    }

    @Test
    fun `every packet in a multi-packet device log has a verifiable inclusion proof`() {
        val sealed = (1..3).map { index ->
            CryptoFixtures.sealedPacket(jobId = "job-$index")
        }
        val leaves = sealed.map { it.packetHash!! }
        val root = MerkleLog.root(leaves)

        sealed.forEachIndexed { index, packet ->
            val inclusion = MerkleLog.inclusion(leaves, index)

            assertEquals(root, inclusion.root)
            assertEquals(merkleLeafOf(packet), inclusion.leafHash)
            assertTrue("inclusion failed at index $index", inclusion.verify())
            assertTrue(
                "4-arg inclusion failed at index $index",
                MerkleLog.verify(packet.packetHash!!, inclusion.proof, index, root),
            )
        }
    }

    @Test
    fun `a proof from one packet does not vouch for another packet`() {
        val sealed = (1..3).map { index -> CryptoFixtures.sealedPacket(jobId = "job-$index") }
        val leaves = sealed.map { it.packetHash!! }
        val root = MerkleLog.root(leaves)
        val proofForFirst = MerkleLog.proof(leaves, 0)

        assertFalse(MerkleLog.verify(leaves[1], proofForFirst, 0, root))
        assertFalse(MerkleLog.verify(leaves[1], proofForFirst, 1, root))
    }

    @Test
    fun `a tampered packet fails both chain and seal evaluation`() {
        val packet = CryptoFixtures.sealedPacket()
        val tampered = packet.copy(
            records = packet.records.mapIndexed { index, record ->
                if (index == 1) record.copy(summary = "Edited after sealing") else record
            },
        )

        val chain = ChainVerifier.verify(tampered, hasher)
        val evaluation = SealEngine.evaluate(tampered, chain.valid, merkleIncluded = true, nowEpochMs = TEST_NOW)

        assertFalse(chain.valid)
        assertFalse(evaluation.canSeal)
        assertNotEquals(SealState.EARNED, evaluation.state)
        assertTrue(evaluation.reasons.any { it.contains("chain", ignoreCase = true) })
    }

    @Test
    fun `missing log inclusion alone prevents the seal evaluation`() {
        val packet = CryptoFixtures.sealedPacket()

        val evaluation = SealEngine.evaluate(packet, chainValid = true, merkleIncluded = false, nowEpochMs = TEST_NOW)

        assertFalse(evaluation.canSeal)
        assertTrue(evaluation.reasons.any { it.contains("Merkle") })
    }

    @Test
    fun `an unsigned draft is not sealable and not verifiable`() {
        val draft = PacketAssembler.draft(
            job = CryptoFixtures.job(),
            operator = CryptoFixtures.operator(),
            nowEpochMs = TEST_NOW,
        )

        assertFalse(ChainVerifier.verify(draft, hasher).valid)
        assertFalse(ChainVerifier.verifyPacketSignature(draft, signer))
        assertTrue(draft.records.single().hash.isBlank())
    }

    @Test
    fun `the recorded chain is append-only across two appends`() {
        val base = CryptoFixtures.completePacket()
        val appended = PacketAssembler.appendRecord(
            packet = base,
            kind = com.odasi.fieldwright.model.RecordKind.ATTEST,
            actorId = "requester-1",
            summary = "Requester confirmed the packet",
            payload = mapOf("confirmed" to "true"),
            atEpochMs = TEST_NOW + 1_500,
            hasher = hasher,
            signer = signer,
        )

        val chain = ChainVerifier.verify(appended, hasher)

        assertTrue(chain.valid)
        assertEquals(base.records.size + 1, chain.entries)
        assertEquals(appended.records.last().hash, chain.headHash)
        assertTrue(appended.records.take(base.records.size).map { it.hash } == base.records.map { it.hash })
    }

    private fun merkleLeafOf(packet: com.odasi.fieldwright.model.EvidencePacket): String =
        MerkleLog.leafHash(packet.packetHash!!)

    @Suppress("unused")
    private fun chainOf(packet: com.odasi.fieldwright.model.EvidencePacket): List<ChainRecord> = packet.records
}
