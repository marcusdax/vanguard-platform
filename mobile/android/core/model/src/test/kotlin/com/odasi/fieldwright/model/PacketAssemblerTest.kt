package com.odasi.fieldwright.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Packet assembly: the chain is append-only and starts at a 64-zero genesis; drafts are
 * intentions (unhashed), records are evidence (hashed and signed), and a seal is only
 * earned when the local evidence is complete.
 */
class PacketAssemblerTest {

    private val operator = Fixtures.operator()
    private val hasher = Fixtures.hasher
    private val signer = Fixtures.signer

    private fun draft(): EvidencePacket = PacketAssembler.draft(
        job = Fixtures.job(type = JobType.ROOF_HAIL),
        operator = operator,
        nowEpochMs = NOW,
    )

    private fun completePacket(): EvidencePacket {
        val base = draft().copy(
            consent = Fixtures.consent(),
            checklist = Fixtures.checklistResults(JobType.ROOF_HAIL),
            media = listOf(Fixtures.photo()),
            measurements = listOf(Fixtures.measurement()),
        )
        return PacketAssembler.appendRecord(
            packet = base,
            kind = RecordKind.CAPTURE,
            actorId = operator.id,
            summary = "Four slopes captured",
            payload = mapOf("captureStandardMet" to "true"),
            atEpochMs = NOW + 1_000,
            hasher = hasher,
            signer = signer,
        )
    }

    @Test
    fun `a draft is genesis-anchored and carries the job capture standard`() {
        val packet = draft()
        val seed = packet.records.single()

        assertEquals(1, seed.seq)
        assertEquals(PacketAssembler.GENESIS_HASH, seed.prevHash)
        assertEquals(PacketAssembler.DRAFT_HASH, seed.hash)
        assertEquals(RecordKind.CUSTODY, seed.kind)
        assertTrue(seed.summary.contains("Packet opened"))
        assertEquals(JobType.ROOF_HAIL.captureStandard, seed.payload.getValue("captureStandard"))
        assertEquals(JobType.ROOF_HAIL.consentScope, seed.payload.getValue("consentScope"))
        assertTrue(seed.payload.getValue("checklist").contains("footprint*"))
        assertEquals(PacketAssembler.GENESIS_HASH, "0".repeat(64))
    }

    @Test
    fun `a draft starts with pending checklist steps, no consent and no seal`() {
        val packet = draft()

        assertTrue(packet.checklist.isNotEmpty())
        assertTrue(packet.checklist.all { it.result == StepResult.PENDING })
        assertNull(packet.consent)
        assertEquals(SealState.PENDING, packet.seal)
        assertEquals(EvidenceTier.REPORTED, packet.tier)
        assertEquals(0L, packet.asOfEpochMs)
        assertTrue(packet.badges.isNotEmpty())
        assertTrue(packet.badges.filter { it.required }.all { it.state != BadgeState.VERIFIED })
    }

    @Test
    fun `draft ids are deterministic per job, not per call`() {
        val first = draft()
        val second = draft()
        val otherJob = PacketAssembler.draft(
            job = Fixtures.job(id = "job-2", type = JobType.ROOF_HAIL),
            operator = operator,
            nowEpochMs = NOW + 5_000,
        )

        assertEquals(first.id, second.id)
        assertTrue(Regex("FW-\\d{4}").matches(first.id))
        assertNotEquals(first.id, otherJob.id)
    }

    @Test
    fun `the first append materialises the draft record and chains the new one`() {
        val packet = completePacket()
        val seed = packet.records[0]
        val capture = packet.records[1]

        assertEquals(2, packet.records.size)
        assertEquals(64, seed.hash.length)
        assertFalse(seed.hash == PacketAssembler.DRAFT_HASH)
        assertEquals(signer.keyId, seed.signerKeyId)
        assertTrue(seed.signatureBase64 != null)
        assertEquals(2, capture.seq)
        assertEquals(seed.hash, capture.prevHash)
        assertEquals(capture.computeHash(hasher), capture.hash)
        assertTrue(PacketAssembler.recomputeChainValid(packet.records, hasher))
    }

    @Test
    fun `appends keep sequence, linkage and recomputed hashes intact`() {
        var packet = completePacket()
        packet = PacketAssembler.appendRecord(
            packet = packet,
            kind = RecordKind.ATTEST,
            actorId = "requester-1",
            summary = "Requester confirmed the scope",
            payload = mapOf("confirm" to "true"),
            atEpochMs = NOW + 2_000,
            hasher = hasher,
            signer = signer,
        )

        assertEquals(3, packet.records.size)
        assertEquals(3, packet.records[2].seq)
        assertEquals(packet.records[1].hash, packet.records[2].prevHash)
        assertTrue(PacketAssembler.recomputeChainValid(packet.records, hasher))
        // The tier is refreshed from the appended independent attestation, never stored by hand.
        assertTrue(packet.tier.level >= EvidenceTier.COUNTERPARTY_CONFIRMED.level)
    }

    @Test
    fun `tampering with a record breaks local chain verification`() {
        val packet = completePacket()
        val tampered = packet.records.toMutableList()
        tampered[1] = tampered[1].copy(summary = "Four slopes captured, honest")

        assertFalse(PacketAssembler.recomputeChainValid(tampered, hasher))
    }

    @Test
    fun `an unsigned append keeps the chain but leaves the omission visible`() {
        val packet = PacketAssembler.draft(
            Fixtures.job(type = JobType.ROOF_HAIL),
            operator,
            NOW,
        )
        val noSigner = TestSigner(keyId = "verify-only", canSign = false)

        val appended = PacketAssembler.appendRecord(
            packet = packet,
            kind = RecordKind.CUSTODY,
            actorId = operator.id,
            summary = "Custody noted",
            payload = emptyMap(),
            atEpochMs = NOW + 100,
            hasher = hasher,
            signer = noSigner,
        )

        assertTrue(PacketAssembler.recomputeChainValid(appended.records, hasher))
        assertTrue(appended.records.all { it.signatureBase64 == null })
        assertTrue(appended.records.all { it.signerKeyId == null })
    }

    @Test
    fun `sealing complete evidence earns the seal and signs the packet head`() {
        val sealed = PacketAssembler.seal(
            packet = completePacket(),
            gates = listOf(GateCheck("consent", "Consent on file", true, "Signed", true)),
            hasher = hasher,
            signer = signer,
            nowEpochMs = NOW + 2_000,
        )

        assertEquals(SealState.EARNED, sealed.seal)
        assertEquals(NOW + 2_000, sealed.sealedAtEpochMs)
        assertEquals(NOW + 2_000, sealed.asOfEpochMs)
        assertEquals(3, sealed.records.size)
        assertTrue(PacketAssembler.recomputeChainValid(sealed.records, hasher))
        assertEquals(sealed.records.last().hash, sealed.packetHash)
        assertTrue(sealed.signatureBase64 != null)
        assertEquals(
            MerkleHash.leafHash(sealed.packetHash!!, hasher),
            sealed.merkleLeaf,
        )
        assertEquals(PacketAssembler.SEAL_STANDARD, sealed.records.last().payload.getValue("standard"))
        assertEquals("true", sealed.records.last().payload.getValue("evidenceComplete"))
        assertEquals(EvidenceTier.VERIFIED_BY_AUCTUM, sealed.tier)
    }

    @Test
    fun `a blocking gate prevents the seal but still records the attempt`() {
        val blocked = PacketAssembler.seal(
            packet = completePacket(),
            gates = listOf(
                GateCheck("redaction", "Redaction resolved", false, "One face is unresolved", true),
            ),
            hasher = hasher,
            signer = signer,
            nowEpochMs = NOW + 2_000,
        )

        assertEquals(SealState.PENDING, blocked.seal)
        assertNull(blocked.sealedAtEpochMs)
        assertEquals(3, blocked.records.size)
        assertTrue(blocked.records.last().summary.contains("attempted"))
        // The evidence itself is complete; the gate is what blocks the seal. Both facts are
        // recorded, so an auditor can see why the packet was not sealed.
        assertEquals("true", blocked.records.last().payload.getValue("evidenceComplete"))
        assertEquals("false", blocked.records.last().payload.getValue("canEarnSeal"))
        assertEquals("1", blocked.records.last().payload.getValue("gatesBlocked"))
    }

    @Test
    fun `a flagged badge can never be sealed`() {
        val flagged = completePacket().copy(media = listOf(Fixtures.photo(redaction = RedactionState.REQUIRED)))

        val sealed = PacketAssembler.seal(
            packet = flagged,
            gates = listOf(GateCheck("redaction", "Redaction resolved", true, "claimed clean", true)),
            hasher = hasher,
            signer = signer,
            nowEpochMs = NOW + 2_000,
        )

        assertEquals(SealState.PENDING, sealed.seal)
        assertNull(sealed.sealedAtEpochMs)
        assertEquals("false", sealed.records.last().payload.getValue("evidenceComplete"))
        assertEquals("false", sealed.records.last().payload.getValue("canEarnSeal"))
    }

    @Test
    fun `incomplete evidence prevents the seal`() {
        val beforeMeasurements = completePacket().copy(measurements = emptyList())

        val sealed = PacketAssembler.seal(
            packet = beforeMeasurements,
            gates = listOf(GateCheck("g", "Gates", true, "ok", true)),
            hasher = hasher,
            signer = signer,
            nowEpochMs = NOW + 2_000,
        )

        assertEquals(SealState.PENDING, sealed.seal)
        assertNull(sealed.sealedAtEpochMs)
        assertTrue(sealed.badges.single { it.key == BadgeKey.MEASURED }.state != BadgeState.VERIFIED)
    }

    @Test
    fun `a tampered chain can never be sealed`() {
        val packet = completePacket()
        val tampered = packet.copy(
            records = packet.records.mapIndexed { index, record ->
                if (index == 1) record.copy(payload = mapOf("captureStandardMet" to "true", "edited" to "yes")) else record
            },
        )

        val sealed = PacketAssembler.seal(
            packet = tampered,
            gates = listOf(GateCheck("g", "Gates", true, "ok", true)),
            hasher = hasher,
            signer = signer,
            nowEpochMs = NOW + 2_000,
        )

        assertEquals(SealState.PENDING, sealed.seal)
        assertEquals("false", sealed.records.last().payload.getValue("chainIntact"))
    }

    @Test
    fun `sealing without prior evidence records remains honest about its as-of date`() {
        val sealed = PacketAssembler.seal(
            packet = draft(),
            gates = listOf(GateCheck("g", "Gates", true, "ok", true)),
            hasher = hasher,
            signer = signer,
            nowEpochMs = NOW + 2_000,
        )

        assertEquals(SealState.PENDING, sealed.seal)
        assertEquals(NOW + 2_000, sealed.asOfEpochMs)
        assertTrue(sealed.records.size >= 2)
        assertTrue(PacketAssembler.recomputeChainValid(sealed.records, hasher))
    }

    @Test
    fun `a draft record is not valid evidence before it is materialised`() {
        assertFalse(PacketAssembler.recomputeChainValid(draft().records, hasher))
    }
}
