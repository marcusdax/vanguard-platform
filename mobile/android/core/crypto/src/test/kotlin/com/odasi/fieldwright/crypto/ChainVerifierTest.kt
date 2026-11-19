package com.odasi.fieldwright.crypto

import com.odasi.fieldwright.model.ChainRecord
import com.odasi.fieldwright.model.PacketAssembler
import com.odasi.fieldwright.model.computeHash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Chain verification must detect every edit an attacker could make to an append-only
 * record list, and it must report *where* the break is. Truncation of the tail is the one
 * thing a self-contained chain cannot detect — the anchored head hash does, so the tests
 * pin both behaviours honestly.
 */
class ChainVerifierTest {

    private val hasher = Sha256
    private val signer = CryptoFixtures.signer

    @Test
    fun `a sealed packet chain verifies from genesis`() {
        val packet = CryptoFixtures.sealedPacket()

        val verification = ChainVerifier.verify(packet, hasher)

        assertTrue(verification.valid)
        assertNull(verification.brokenAtSeq)
        assertEquals(packet.records.size, verification.entries)
        assertEquals(packet.packetHash, verification.headHash)
        assertTrue(verification.detail.contains("verifies"))
    }

    @Test
    fun `record signatures verify when the signer key is supplied`() {
        val packet = CryptoFixtures.sealedPacket()

        val verification = ChainVerifier.verify(packet.records, hasher, signer)

        assertTrue(verification.valid)
        assertEquals(packet.records.size, verification.entries)
    }

    @Test
    fun `editing a summary is detected at the edited record`() {
        val packet = CryptoFixtures.sealedPacket()
        val tampered = packet.records.toMutableList()
        tampered[1] = tampered[1].copy(summary = "Four slopes captured, all honest")

        val verification = ChainVerifier.verify(tampered, hasher)

        assertFalse(verification.valid)
        assertEquals(2, verification.brokenAtSeq)
        assertTrue(verification.detail.contains("content does not match"))
    }

    @Test
    fun `editing one payload entry is detected`() {
        val packet = CryptoFixtures.sealedPacket()
        val tampered = packet.records.toMutableList()
        tampered[1] = tampered[1].copy(
            payload = tampered[1].payload + ("gauge" to "transcribed from memory"),
        )

        assertFalse(ChainVerifier.verify(tampered, hasher).valid)
    }

    @Test
    fun `rewriting the stored hash of a record is detected`() {
        val packet = CryptoFixtures.sealedPacket()
        val tampered = packet.records.toMutableList()
        tampered[1] = tampered[1].copy(hash = "f".repeat(64))

        val verification = ChainVerifier.verify(tampered, hasher)

        assertFalse(verification.valid)
        assertEquals(2, verification.brokenAtSeq)
    }

    @Test
    fun `reordering records is detected as out of order`() {
        val packet = CryptoFixtures.sealedPacket()
        val tampered = packet.records.toMutableList()
        val second = tampered[1]
        tampered[1] = tampered[2]
        tampered[2] = second

        val verification = ChainVerifier.verify(tampered, hasher)

        assertFalse(verification.valid)
        assertEquals(3, verification.brokenAtSeq)
        assertTrue(verification.detail.contains("out of order"))
    }

    @Test
    fun `a broken genesis link is detected at seq one`() {
        val packet = CryptoFixtures.sealedPacket()
        val rebuilt = rebuildWithGenesis(packet.records, genesis = "1".repeat(64))

        val verification = ChainVerifier.verify(rebuilt, hasher)

        assertFalse(verification.valid)
        assertEquals(1, verification.brokenAtSeq)
        assertTrue(verification.detail.contains("prevHash"))
    }

    @Test
    fun `a spliced earlier record breaks the link at the later record`() {
        val packet = CryptoFixtures.sealedPacket()
        val otherChainSeed = CryptoFixtures.completePacket(jobId = "job-2").records[0]

        // A validly hashed seed from another packet cannot be spliced in: record 2 still
        // commits to the original seed's hash, so the link breaks at record 2.
        val spliced = listOf(otherChainSeed, packet.records[1], packet.records[2])

        val verification = ChainVerifier.verify(spliced, hasher)

        assertFalse(verification.valid)
        assertEquals(2, verification.brokenAtSeq)
        assertTrue(verification.detail.contains("prevHash"))
    }

    @Test
    fun `truncating the tail produces a shorter chain with a different head, which an anchor detects`() {
        val packet = CryptoFixtures.sealedPacket()
        val truncated = packet.records.dropLast(1)

        val verification = ChainVerifier.verify(truncated, hasher)

        assertTrue(verification.valid) // a prefix is internally consistent…
        assertNotEquals(packet.packetHash, verification.headHash) // …but it is not the anchored head
        assertEquals(packet.records.size - 1, verification.entries)
    }

    @Test
    fun `an unmaterialised draft is never valid evidence`() {
        val draft = PacketAssembler.draft(
            job = CryptoFixtures.job(),
            operator = CryptoFixtures.operator(),
            nowEpochMs = TEST_NOW,
        )

        val verification = ChainVerifier.verify(draft, hasher)

        assertFalse(verification.valid)
        assertTrue(verification.detail.contains("draft"))
    }

    @Test
    fun `an empty chain is invalid rather than vacuously true`() {
        val verification = ChainVerifier.verify(emptyList<ChainRecord>(), hasher)

        assertFalse(verification.valid)
        assertEquals(0, verification.entries)
        assertEquals(PacketAssembler.GENESIS_HASH, verification.headHash)
        assertTrue(verification.detail.contains("empty"))
    }

    @Test
    fun `a forged signature that claims our key fails verification`() {
        val packet = CryptoFixtures.sealedPacket()
        val tampered = packet.records.toMutableList()
        tampered[1] = tampered[1].copy(signatureBase64 = "AAAA")

        val verification = ChainVerifier.verify(tampered, hasher, signer)

        assertFalse(verification.valid)
        assertEquals(2, verification.brokenAtSeq)
        assertTrue(verification.detail.contains("signature does not verify"))
    }

    @Test
    fun `records signed by another key are skipped, not failed`() {
        val packet = CryptoFixtures.sealedPacket()
        val otherDevice = SoftwareEcdsaSigner.generate()

        val verification = ChainVerifier.verify(packet.records, hasher, otherDevice)

        assertTrue(verification.valid)
    }

    @Test
    fun `the packet-level seal signature verifies with the signing key`() {
        val packet = CryptoFixtures.sealedPacket()

        assertTrue(ChainVerifier.verifyPacketSignature(packet, signer))
    }

    @Test
    fun `the packet-level seal signature fails for a different key`() {
        val packet = CryptoFixtures.sealedPacket()

        assertFalse(ChainVerifier.verifyPacketSignature(packet, SoftwareEcdsaSigner.generate()))
    }

    @Test
    fun `a tampered packet signature is rejected`() {
        val packet = CryptoFixtures.sealedPacket()

        assertFalse(ChainVerifier.verifyPacketSignature(packet.copy(signatureBase64 = "AAAA"), signer))
        assertFalse(ChainVerifier.verifyPacketSignature(packet.copy(signatureBase64 = null), signer))
    }

    @Test
    fun `an unsigned packet cannot claim a signature`() {
        val packet = CryptoFixtures.sealedPacket()

        assertFalse(ChainVerifier.verifyPacketSignature(packet.copy(records = emptyList()), signer))
    }

    private fun rebuildWithGenesis(
        records: List<ChainRecord>,
        genesis: String,
    ): List<ChainRecord> {
        var previous = genesis
        return records.mapIndexed { index, record ->
            val unsigned = record.copy(
                seq = index + 1,
                prevHash = previous,
                hash = "",
                signatureBase64 = null,
                signerKeyId = null,
            )
            val hash = unsigned.computeHash(hasher)
            val signed = unsigned.copy(
                hash = hash,
                signatureBase64 = signer.sign(hash),
                signerKeyId = signer.keyId,
            )
            previous = hash
            signed
        }
    }
}
