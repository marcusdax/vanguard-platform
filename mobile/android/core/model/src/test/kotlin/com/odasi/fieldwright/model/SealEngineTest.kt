package com.odasi.fieldwright.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seal preconditions. The seal is the product's promise, so every precondition is
 * checked independently and every refusal carries a reason the reader can act on.
 */
class SealEngineTest {

    private fun evaluate(
        packet: EvidencePacket,
        chainValid: Boolean = true,
        merkleIncluded: Boolean = true,
        nowEpochMs: Long = NOW,
    ): SealEvaluation = SealEngine.evaluate(packet, chainValid, merkleIncluded, nowEpochMs)

    @Test
    fun `a complete packet with a valid chain and log inclusion earns the seal`() {
        val evaluation = evaluate(Fixtures.packet())

        assertEquals(SealState.EARNED, evaluation.state)
        assertTrue(evaluation.canSeal)
        assertTrue(evaluation.reasons.isEmpty())
        assertEquals(EvidenceTier.VERIFIED_BY_AUCTUM, evaluation.tier)
        assertEquals(NOW, evaluation.asOfEpochMs)
        assertEquals(Fixtures.packet().packetHash, evaluation.packetHash)
    }

    @Test
    fun `no consent means no seal, whichever way the rest of the evidence looks`() {
        val evaluation = evaluate(Fixtures.packet(consent = null))

        assertEquals(SealState.PENDING, evaluation.state)
        assertFalse(evaluation.canSeal)
        assertTrue(evaluation.reasons.any { it.contains("consent", ignoreCase = true) })
    }

    @Test
    fun `a chain that does not verify blocks the seal`() {
        val evaluation = evaluate(Fixtures.packet(), chainValid = false)

        assertFalse(evaluation.canSeal)
        assertTrue(evaluation.reasons.any { it.contains("chain", ignoreCase = true) })
    }

    @Test
    fun `log inclusion is a precondition, not an optional extra`() {
        val evaluation = evaluate(Fixtures.packet(), merkleIncluded = false)

        assertFalse(evaluation.canSeal)
        assertTrue(evaluation.reasons.any { it.contains("Merkle", ignoreCase = true) })
    }

    @Test
    fun `an as-of date is required`() {
        val evaluation = evaluate(Fixtures.packet(asOfEpochMs = 0L))

        assertFalse(evaluation.canSeal)
        assertTrue(evaluation.reasons.any { it.contains("as-of", ignoreCase = true) })
        assertEquals(NOW, evaluation.asOfEpochMs) // display fallback only, never invented evidence
    }

    @Test
    fun `tier below five blocks the seal with the ladder position in the reason`() {
        val evaluation = evaluate(Fixtures.packet(consent = null))

        assertFalse(evaluation.canSeal)
        assertTrue(evaluation.reasons.any { it.contains("Evidence tier") })
    }

    @Test
    fun `a missing required badge names the badge and its state`() {
        val evaluation = evaluate(Fixtures.packet(measurements = emptyList()))

        assertFalse(evaluation.canSeal)
        assertTrue(evaluation.reasons.any {
            it.contains("Measured") && it.contains("missing", ignoreCase = true)
        })
    }

    @Test
    fun `a flagged badge turns the evaluation flagged rather than pending`() {
        val evaluation = evaluate(Fixtures.packet(media = listOf(Fixtures.photo(redaction = RedactionState.REQUIRED))))

        assertEquals(SealState.FLAGGED, evaluation.state)
        assertFalse(evaluation.canSeal)
        assertTrue(evaluation.reasons.any { it.contains("flagged", ignoreCase = true) })
    }

    @Test
    fun `a blocking gate prevents the seal and is quoted`() {
        val packet = Fixtures.packet(
            gates = listOf(
                GateCheck("redaction", "Redaction resolved", false, "One face is unresolved", true),
                GateCheck("consent", "Consent on file", true, "Signed in app", true),
            ),
        )

        val evaluation = evaluate(packet)

        assertFalse(evaluation.canSeal)
        assertTrue(evaluation.reasons.any { it.contains("Redaction resolved") })
    }

    @Test
    fun `a non-blocking failed gate does not stop the seal`() {
        val packet = Fixtures.packet(
            gates = listOf(GateCheck("quality", "Sharpness", false, "Soft frame — note added", blocking = false)),
        )

        assertTrue(evaluate(packet).canSeal)
    }

    @Test
    fun `a withdrawn seal stays withdrawn and cannot be re-sealed by clean evidence`() {
        val evaluation = evaluate(Fixtures.packet(seal = SealState.WITHDRAWN))

        assertEquals(SealState.WITHDRAWN, evaluation.state)
        assertFalse(evaluation.canSeal)
        assertEquals(1, evaluation.reasons.size)
        assertTrue(evaluation.reasons.single().contains("withdrawn"))
    }

    @Test
    fun `evaluation returns explicit seal gates for the UI`() {
        val evaluation = evaluate(Fixtures.packet(), chainValid = false, merkleIncluded = false)
        val ids = evaluation.gates.map { it.id }

        assertTrue(ids.containsAll(listOf("seal.tier", "seal.badges", "seal.consent", "seal.chain", "seal.log", "seal.asof")))
        assertTrue(evaluation.gates.filter { it.id.startsWith("seal.") }.all { it.blocking })
        assertFalse(evaluation.gates.single { it.id == "seal.chain" }.passed)
        assertFalse(evaluation.gates.single { it.id == "seal.log" }.passed)
        assertTrue(evaluation.gates.single { it.id == "seal.asof" }.passed)
    }

    @Test
    fun `a packet with no records can never be sealed`() {
        val evaluation = evaluate(
            Fixtures.packet(records = emptyList(), consent = null, media = emptyList(), measurements = emptyList()),
        )

        assertFalse(evaluation.canSeal)
        assertNull(evaluation.packetHash)
        assertTrue(evaluation.reasons.any { it.contains("no records", ignoreCase = true) })
    }

    @Test
    fun `a packet hash is the final record hash`() {
        val packet = Fixtures.packet()

        assertEquals(packet.records.last().hash, evaluate(packet).packetHash)
    }

    @Test
    fun `canSeal is exactly equivalent to an earned state and no reasons`() {
        val cases = listOf(
            Fixtures.packet(),
            Fixtures.packet(consent = null),
            Fixtures.packet(asOfEpochMs = 0L),
            Fixtures.packet(seal = SealState.WITHDRAWN),
        )

        for (packet in cases) {
            val evaluation = evaluate(packet)
            assertEquals(evaluation.reasons.isEmpty(), evaluation.canSeal)
            assertEquals(evaluation.canSeal, evaluation.state == SealState.EARNED)
        }
    }

    @Test
    fun `a flagged non-required badge also blocks the seal`() {
        val flagSettled = Fixtures.RecordSpec(
            kind = RecordKind.INSPECTION,
            actorId = "auctum-reviewer",
            summary = "Payment evidence disputed",
            payload = mapOf("flag" to "true", "flagBadge" to BadgeKey.SETTLED.name),
        )
        val packet = Fixtures.packet(records = Fixtures.chain(
            Fixtures.RecordSpec(kind = RecordKind.CUSTODY),
            Fixtures.RecordSpec(
                kind = RecordKind.CAPTURE,
                payload = mapOf("captureStandardMet" to "true"),
            ),
            flagSettled,
        ))

        val evaluation = evaluate(packet)

        assertEquals(SealState.FLAGGED, evaluation.state)
        assertFalse(evaluation.canSeal)
        assertTrue(evaluation.reasons.any { it.contains("Settled") })
    }
}
