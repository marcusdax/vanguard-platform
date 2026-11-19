package com.odasi.fieldwright.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier and badge derivation: both are recomputed from records and evidence every time.
 * A stored badge is a display cache; if it disagrees with the evidence, the evidence wins.
 */
class TierEngineTest {

    private fun packetWith(
        records: List<ChainRecord>,
        consent: Consent? = null,
        media: List<MediaRef> = emptyList(),
        measurements: List<Measurement> = emptyList(),
        checklist: List<ChecklistResult> = Fixtures.checklistResults(JobType.ROOF_HAIL),
        badges: List<Badge> = emptyList(),
    ): EvidencePacket = Fixtures.packet(
        records = records,
        consent = consent,
        media = media,
        measurements = measurements,
        checklist = checklist,
        badges = badges,
    )

    private val captureStandard = Fixtures.RecordSpec(
        kind = RecordKind.CAPTURE,
        summary = "Four slopes captured",
        payload = mapOf("captureStandardMet" to "true"),
    )

    @Test
    fun `an empty record set is only tier one, reported`() {
        val packet = Fixtures.packet(records = emptyList(), consent = null, media = emptyList(), measurements = emptyList())

        assertEquals(EvidenceTier.REPORTED, TierEngine.derive(packet))
        assertEquals(1, TierEngine.derive(packet).level)
    }

    @Test
    fun `tier two requires an independent actor, not the operator attesting to itself`() {
        val selfAttestation = packetWith(
            records = Fixtures.chain(Fixtures.RecordSpec(kind = RecordKind.CUSTODY)),
        )
        val independent = packetWith(
            records = Fixtures.chain(
                Fixtures.RecordSpec(kind = RecordKind.CUSTODY),
                Fixtures.RecordSpec(kind = RecordKind.ATTEST, actorId = "requester-1", summary = "Scope confirmed"),
            ),
        )

        assertEquals(EvidenceTier.REPORTED, TierEngine.derive(selfAttestation))
        assertEquals(EvidenceTier.COUNTERPARTY_CONFIRMED, TierEngine.derive(independent))
        assertEquals(2, TierEngine.derive(independent).level)
    }

    @Test
    fun `tier three is a capture that met the market capture standard`() {
        val notStandard = packetWith(
            records = Fixtures.chain(
                Fixtures.RecordSpec(kind = RecordKind.CUSTODY),
                Fixtures.RecordSpec(kind = RecordKind.CAPTURE, payload = mapOf("mediaCount" to "3")),
            ),
        )
        val standard = packetWith(records = Fixtures.chain(Fixtures.RecordSpec(kind = RecordKind.CUSTODY), captureStandard))

        assertEquals(EvidenceTier.REPORTED, TierEngine.derive(notStandard))
        assertEquals(EvidenceTier.FIELD_CAPTURED, TierEngine.derive(standard))
        assertEquals(3, TierEngine.derive(standard).level)
    }

    @Test
    fun `tier four is a settled payment, deliberately stronger than an unconfirmed payment record`() {
        val unpaid = packetWith(
            records = Fixtures.chain(Fixtures.RecordSpec(kind = RecordKind.CUSTODY), captureStandard),
        )
        val pendingPayment = packetWith(
            records = Fixtures.chain(
                Fixtures.RecordSpec(kind = RecordKind.CUSTODY),
                captureStandard,
                Fixtures.RecordSpec(kind = RecordKind.PAYMENT, payload = mapOf("settled" to "false")),
            ),
        )
        val settled = packetWith(
            records = Fixtures.chain(
                Fixtures.RecordSpec(kind = RecordKind.CUSTODY),
                captureStandard,
                Fixtures.RecordSpec(kind = RecordKind.PAYMENT, payload = mapOf("settled" to "true")),
            ),
        )

        assertTrue(TierEngine.derive(unpaid).level < 4)
        assertTrue(TierEngine.derive(pendingPayment).level < 4)
        assertEquals(EvidenceTier.PAYMENT_VERIFIED, TierEngine.derive(settled))
        assertEquals(4, TierEngine.derive(settled).level)
    }

    @Test
    fun `tier five is every required badge independently re-derived and verified`() {
        val full = Fixtures.packet()

        assertEquals(EvidenceTier.VERIFIED_BY_AUCTUM, TierEngine.derive(full))
        assertEquals(5, TierEngine.derive(full).level)
    }

    @Test
    fun `a flagged capture can never reach tier five`() {
        val flagged = Fixtures.packet(media = listOf(Fixtures.photo(redaction = RedactionState.REQUIRED)))

        assertEquals(EvidenceTier.FIELD_CAPTURED, TierEngine.derive(flagged))
        assertEquals(BadgeState.FLAGGED, TierEngine.badgesFor(flagged).single { it.key == BadgeKey.FIELD_CAPTURED }.state)
    }

    @Test
    fun `derived tiers never leave the 1 to 5 ladder`() {
        val variants = listOf(
            Fixtures.packet(),
            Fixtures.packet(consent = null, media = emptyList(), measurements = emptyList()),
            packetWith(records = emptyList()),
        )

        for (packet in variants) {
            assertTrue(TierEngine.derive(packet).level in 1..5)
        }
    }

    @Test
    fun `badges for a job family are exactly its required set plus settled`() {
        val required = BadgeKey.requiredFor(JobType.ROOF_HAIL)
        val badges = TierEngine.badgesFor(Fixtures.packet(type = JobType.ROOF_HAIL))

        assertEquals(required + BadgeKey.SETTLED, badges.map { it.key }.toSet())
        assertTrue(badges.filter { it.key in required }.all { it.required })
        assertFalse(badges.single { it.key == BadgeKey.SETTLED }.required)
    }

    @Test
    fun `a forged stored badge list is ignored in favour of the evidence`() {
        val forged = BadgeKey.requiredFor(JobType.ROOF_HAIL).map { key ->
            Badge(key, BadgeState.VERIFIED, required = true, rationale = "trust me", evidenceRefs = emptyList())
        }
        val packet = packetWith(
            records = Fixtures.chain(Fixtures.RecordSpec(kind = RecordKind.CUSTODY)),
            badges = forged,
            consent = null,
            measurements = emptyList(),
            checklist = Fixtures.checklistResults(JobType.ROOF_HAIL, passed = emptySet()),
        )

        val derived = TierEngine.badgesFor(packet)

        assertTrue(derived.none { it.state == BadgeState.VERIFIED })
        assertEquals(BadgeState.MISSING, derived.single { it.key == BadgeKey.FIELD_CAPTURED }.state)
        assertEquals(BadgeState.MISSING, derived.single { it.key == BadgeKey.CONSENT_ON_FILE }.state)
        assertTrue(TierEngine.derive(packet).level < 5)
    }

    @Test
    fun `unresolved redaction flags the field-captured claim`() {
        val packet = Fixtures.packet(media = listOf(Fixtures.photo(redaction = RedactionState.REQUIRED)))
        val badge = TierEngine.badgesFor(packet).single { it.key == BadgeKey.FIELD_CAPTURED }

        assertEquals(BadgeState.FLAGGED, badge.state)
        assertTrue(badge.rationale.contains("redaction"))
    }

    @Test
    fun `an inspection flag is carried onto the badge it names`() {
        val flagRecord = Fixtures.RecordSpec(
            kind = RecordKind.INSPECTION,
            actorId = "auctum-reviewer",
            summary = "Gauge frame is blurry",
            payload = mapOf("flag" to "true", "flagBadge" to BadgeKey.FIELD_CAPTURED.name),
        )
        val packet = Fixtures.packet(records = Fixtures.chain(
            Fixtures.RecordSpec(kind = RecordKind.CUSTODY),
            captureStandard,
            flagRecord,
        ))

        val badge = TierEngine.badgesFor(packet).single { it.key == BadgeKey.FIELD_CAPTURED }
        assertEquals(BadgeState.FLAGGED, badge.state)
    }

    @Test
    fun `a waived optional claim is reported as waived, never verified`() {
        val waiveSettled = Fixtures.RecordSpec(
            kind = RecordKind.CUSTODY,
            summary = "No payment due on this sample",
            payload = mapOf("waivedBadges" to "SETTLED"),
        )
        val packet = Fixtures.packet(records = Fixtures.chain(
            Fixtures.RecordSpec(kind = RecordKind.CUSTODY),
            captureStandard,
            waiveSettled,
        ))

        assertEquals(BadgeState.WAIVED, TierEngine.badgesFor(packet).single { it.key == BadgeKey.SETTLED }.state)
    }

    @Test
    fun `consent states distinguish missing, incomplete and verified`() {
        val missing = Fixtures.packet(consent = null)
        val incomplete = Fixtures.packet(consent = Fixtures.consent(name = " "))
        val unsigned = Fixtures.packet(consent = Fixtures.consent(signatureHash = null))
        val valid = Fixtures.packet()

        fun consentState(packet: EvidencePacket) =
            TierEngine.badgesFor(packet).single { it.key == BadgeKey.CONSENT_ON_FILE }.state

        assertEquals(BadgeState.MISSING, consentState(missing))
        assertEquals(BadgeState.IN_REVIEW, consentState(incomplete))
        assertEquals(BadgeState.IN_REVIEW, consentState(unsigned))
        assertEquals(BadgeState.VERIFIED, consentState(valid))
    }

    @Test
    fun `measured needs a calibrated reference, not just a number`() {
        val uncalibrated = Fixtures.packet(
            measurements = listOf(Fixtures.measurement().copy(referenceMeters = 0.0)),
        )
        val missing = Fixtures.packet(measurements = emptyList())
        val calibrated = Fixtures.packet()

        fun measuredState(packet: EvidencePacket) =
            TierEngine.badgesFor(packet).single { it.key == BadgeKey.MEASURED }.state

        assertEquals(BadgeState.IN_REVIEW, measuredState(uncalibrated))
        assertEquals(BadgeState.MISSING, measuredState(missing))
        assertEquals(BadgeState.VERIFIED, measuredState(calibrated))
    }

    @Test
    fun `storm attribution waits for the footprint confirmation`() {
        val noFootprint = Fixtures.packet(
            checklist = Fixtures.checklistResults(JobType.ROOF_HAIL, passed = setOf("scope")),
        )

        val badge = TierEngine.badgesFor(noFootprint).single { it.key == BadgeKey.STORM_ATTRIBUTION }
        assertEquals(BadgeState.IN_REVIEW, badge.state)
        assertTrue(badge.rationale.contains("storm attribution", ignoreCase = true))
    }

    @Test
    fun `settled verifies only on a settled payment record`() {
        val pending = Fixtures.packet(records = Fixtures.chain(
            Fixtures.RecordSpec(kind = RecordKind.CUSTODY),
            Fixtures.RecordSpec(kind = RecordKind.PAYMENT, payload = mapOf("settled" to "false")),
        ))
        val settled = Fixtures.packet(records = Fixtures.chain(
            Fixtures.RecordSpec(kind = RecordKind.CUSTODY),
            Fixtures.RecordSpec(kind = RecordKind.PAYMENT, payload = mapOf("settled" to "true")),
        ))

        assertEquals(BadgeState.IN_REVIEW, TierEngine.badgesFor(pending).single { it.key == BadgeKey.SETTLED }.state)
        assertEquals(BadgeState.VERIFIED, TierEngine.badgesFor(settled).single { it.key == BadgeKey.SETTLED }.state)
    }

    @Test
    fun `badge rationales are always human-readable`() {
        val badges = TierEngine.badgesFor(Fixtures.packet(consent = null, measurements = emptyList()))

        assertTrue(badges.isNotEmpty())
        assertTrue(badges.all { it.rationale.isNotBlank() })
        assertTrue(badges.all { it.key.label.isNotBlank() })
    }
}
