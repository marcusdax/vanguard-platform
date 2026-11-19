package com.odasi.fieldwright.data.demo

import com.odasi.fieldwright.crypto.ChainVerifier
import com.odasi.fieldwright.crypto.MerkleLog
import com.odasi.fieldwright.data.Fixtures
import com.odasi.fieldwright.model.BadgeKey
import com.odasi.fieldwright.model.BadgeState
import com.odasi.fieldwright.model.EvidenceTier
import com.odasi.fieldwright.model.RecordKind
import com.odasi.fieldwright.model.SealState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The demo packets are built through the **real** capture pipeline, so this test is an
 * integration proof: genuine genesis-anchored signed chains, genuine single-leaf inclusion
 * proofs, and demo flags that keep them out of any anchor.
 */
class DemoPacketFactoryTest {

    private val now = Fixtures.NOW
    private val factory = DemoPacketFactory(Fixtures.hasher, Fixtures.signer)

    private fun build(): Map<String, DemoPacketFactory.DemoSealedPacket> =
        factory.build(DemoCatalog.jobs(now), DemoCatalog.operator(), now).associateBy { it.packet.id }

    @Test
    fun `both sample packets are sealed with verifiable chains`() {
        val packets = build()
        assertEquals(setOf("FW-0994", "FW-1012"), packets.keys)

        packets.values.forEach { sealed ->
            val packet = sealed.packet
            assertTrue(packet.demo, "${packet.id} must be flagged demo")
            assertEquals(SealState.EARNED, packet.seal)
            assertTrue(packet.records.size >= 7, "${packet.id} has only ${packet.records.size} records")
            assertEquals(1, sealed.merkleTreeSize)
            assertEquals(0, sealed.merkleIndex)
            assertNotNull(sealed.merkleRoot)

            val verification = ChainVerifier.verify(packet.records, Fixtures.hasher, Fixtures.signer)
            assertTrue(verification.valid, verification.detail)
            assertEquals(packet.packetHash, verification.headHash)

            assertTrue(
                MerkleLog.verify(
                    leaf = packet.packetHash!!,
                    proof = packet.merkleProof,
                    index = sealed.merkleIndex,
                    treeSize = sealed.merkleTreeSize,
                    root = sealed.merkleRoot!!,
                ),
            )
        }
    }

    @Test
    fun `FW-0994 carries the supplied copy and Chris Adeyemi's consent`() {
        val packet = build().getValue("FW-0994").packet
        assertEquals("Panel T-14 is set", packet.title)
        assertEquals("Lamar Tilt Partners", packet.requesterLabel)
        assertEquals("Chris Adeyemi", packet.consent?.name)
        assertEquals("Roof hail assessment", packet.jobType.label)
        assertEquals(EvidenceTier.VERIFIED_BY_AUCTUM, packet.tier)
        assertTrue(packet.badges.filter { it.required }.all { it.state == BadgeState.VERIFIED })
        assertTrue(packet.media.isNotEmpty())
        assertTrue(packet.measurements.isNotEmpty())
        assertTrue(packet.records.any { it.kind == RecordKind.CAPTURE })
        assertTrue(packet.records.any { it.kind == RecordKind.ATTEST })
        assertTrue(packet.records.none { it.kind == RecordKind.PAYMENT }, "FW-0994 is sealed but not paid")
    }

    @Test
    fun `FW-1012 is the paid RPZ packet`() {
        val packet = build().getValue("FW-1012").packet
        assertEquals("Annual RPZ test", packet.title)
        assertEquals("Creekside MUD", packet.requesterLabel)
        assertEquals(EvidenceTier.VERIFIED_BY_AUCTUM, packet.tier)
        assertTrue(packet.records.any { it.kind == RecordKind.PAYMENT })
        assertTrue(
            packet.badges.any { it.key == BadgeKey.SETTLED && it.state == BadgeState.VERIFIED },
            "the paid sample packet must carry a verified SETTLED badge",
        )
        assertEquals(9_500, packet.records.last { it.kind == RecordKind.PAYMENT }.payload["amountCents"]?.toInt())
    }
}
