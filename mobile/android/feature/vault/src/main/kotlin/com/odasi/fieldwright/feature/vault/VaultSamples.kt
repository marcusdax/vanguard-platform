package com.odasi.fieldwright.feature.vault

import com.odasi.fieldwright.model.Badge
import com.odasi.fieldwright.model.BadgeKey
import com.odasi.fieldwright.model.BadgeState
import com.odasi.fieldwright.model.ChainRecord
import com.odasi.fieldwright.model.ChecklistResult
import com.odasi.fieldwright.model.Consent
import com.odasi.fieldwright.model.ConsentMethod
import com.odasi.fieldwright.model.EvidencePacket
import com.odasi.fieldwright.model.EvidenceTier
import com.odasi.fieldwright.model.GateCheck
import com.odasi.fieldwright.model.GeoPoint
import com.odasi.fieldwright.model.JobType
import com.odasi.fieldwright.model.LedgerEntry
import com.odasi.fieldwright.model.LedgerKind
import com.odasi.fieldwright.model.LedgerStatus
import com.odasi.fieldwright.model.Measurement
import com.odasi.fieldwright.model.MediaKind
import com.odasi.fieldwright.model.MediaRef
import com.odasi.fieldwright.model.MediaSource
import com.odasi.fieldwright.model.RecordKind
import com.odasi.fieldwright.model.RedactionState
import com.odasi.fieldwright.model.SealState
import com.odasi.fieldwright.model.SiteRef
import com.odasi.fieldwright.model.StepResult

/**
 * Deterministic sample content for `@Preview`. It mirrors the demo sample book (FW-0994) so
 * the previews and the seeded app look like the same product, but it is never read at runtime.
 */
internal object VaultSamples {

    /** 2026-11-16T00:00:00Z — a fixed "now" so preview labels do not drift. */
    const val NOW: Long = 1_763_251_200_000L

    private val point = GeoPoint(30.2672, -97.7431, NOW, 8f)

    private fun badge(key: BadgeKey, state: BadgeState = BadgeState.VERIFIED, required: Boolean = true) = Badge(
        key = key,
        state = state,
        required = required,
        rationale = "${key.label} evidence is on file for this site.",
        evidenceRefs = listOf("rec-2"),
    )

    private fun record(seq: Int, kind: RecordKind, at: Long, hash: String, signed: Boolean = true) = ChainRecord(
        seq = seq,
        kind = kind,
        atEpochMs = at,
        actorId = "op-elena",
        summary = when (kind) {
            RecordKind.CAPTURE -> "Four slopes and a test square captured with a calibrated paddle."
            RecordKind.ATTEST -> "Chris Adeyemi accepted the capture scope before the first frame."
            RecordKind.CUSTODY -> "Frames hashed and stored in the vault; one plate redacted."
            RecordKind.PAYMENT -> "Lamar Tilt Partners settled the packet."
            RecordKind.CORRECTION -> "South slope label corrected from east."
            RecordKind.INSPECTION -> "Desk inspection upheld the record set."
        },
        payload = mapOf("seq" to seq.toString()),
        prevHash = "0".repeat(64),
        hash = hash,
        signatureBase64 = if (signed) "c2lnbmF0dXJl" else null,
        signerKeyId = if (signed) "fieldwright-keystore-1" else null,
    )

    fun sealedDemoPacket(): EvidencePacket = EvidencePacket(
        id = "FW-0994",
        jobId = "job-FW-0994",
        jobType = JobType.ROOF_HAIL,
        operatorId = "op-elena",
        title = "Panel T-14 is set",
        requesterLabel = "Lamar Tilt Partners · Insurer",
        site = SiteRef(
            address = "14 Creekside Mews",
            unit = null,
            point = point,
            geofenceRadiusMeters = 120,
            accessNotes = "Side gate, code 4412.",
            planLabel = "Panel T-14",
        ),
        consent = Consent(
            name = "Chris Adeyemi",
            role = "Homeowner",
            scopeText = "Roof surfaces, slopes and penetrations. Not the interior of the home.",
            method = ConsentMethod.IN_APP_SIGNATURE,
            capturedAtEpochMs = NOW - 7_200_000,
            signatureHash = "a".repeat(64),
            verifiedByOperatorId = "op-elena",
        ),
        badges = listOf(
            badge(BadgeKey.STORM_ATTRIBUTION),
            badge(BadgeKey.FIELD_CAPTURED),
            badge(BadgeKey.MEASURED),
            badge(BadgeKey.SCOPE_AGREED),
            badge(BadgeKey.CONSENT_ON_FILE),
        ),
        tier = EvidenceTier.VERIFIED_BY_AUCTUM,
        seal = SealState.EARNED,
        records = listOf(
            record(1, RecordKind.CAPTURE, NOW - 7_000_000, "1".repeat(64)),
            record(2, RecordKind.ATTEST, NOW - 6_000_000, "2".repeat(64)),
            record(3, RecordKind.CUSTODY, NOW - 5_000_000, "3".repeat(64)),
            record(4, RecordKind.PAYMENT, NOW - 4_000_000, "9f2c1a04".padEnd(64, '0')),
        ),
        media = listOf(
            MediaRef(
                id = "media-1",
                kind = MediaKind.PHOTO,
                sha256 = "e".repeat(64),
                bytes = 221_000,
                capturedAtEpochMs = NOW - 7_000_000,
                width = 1920,
                height = 1080,
                redaction = RedactionState.APPLIED,
                source = MediaSource.CAMERA,
                note = "South slope, daylight, test square in frame.",
            ),
            MediaRef(
                id = "media-2",
                kind = MediaKind.PHOTO,
                sha256 = "d".repeat(64),
                bytes = 188_000,
                capturedAtEpochMs = NOW - 6_900_000,
                width = 1920,
                height = 1080,
                redaction = RedactionState.NOT_NEEDED,
                source = MediaSource.SIMULATED,
                note = "Calibration frame from the simulated source.",
            ),
        ),
        checklist = listOf(
            ChecklistResult("footprint", StepResult.PASS, "Event footprint covers the address."),
            ChecklistResult("slopes", StepResult.PASS, "Four slopes captured."),
            ChecklistResult("square", StepResult.PASS, "Test square counted."),
            ChecklistResult("paddle", StepResult.PASS, "Paddle measured against the site card."),
            ChecklistResult("scope", StepResult.PASS, "Scope accepted in app."),
        ),
        gates = listOf(
            GateCheck("consent", "Consent on file", true, "Signed in app by the homeowner.", blocking = true),
            GateCheck("geofence", "Geofence satisfied", true, "Inside 120 m at capture.", blocking = true),
            GateCheck("redaction", "Redaction resolved", true, "One plate redacted.", blocking = true),
            GateCheck("checklist", "Checklist complete", true, "All required steps pass.", blocking = true),
            GateCheck("measurement", "Measurement calibrated", true, "Site card reference.", blocking = true),
            GateCheck("chain", "Chain verifies", true, "Four records from genesis.", blocking = true),
        ),
        measurements = listOf(
            Measurement(
                id = "m-1",
                label = "Hail strike",
                meters = 0.42,
                referenceLabel = "Site card 30 cm",
                referenceMeters = 0.30,
                pixelSpan = 140.0,
                calibratedAtEpochMs = NOW - 6_500_000,
                source = MediaSource.CAMERA,
            ),
        ),
        notes = "Panel T-14 was set before the storm; strikes are on the south slope only.",
        demo = true,
        createdAtEpochMs = NOW - 8_000_000,
        sealedAtEpochMs = NOW - 4_000_000,
        merkleLeaf = null,
        merkleProof = emptyList(),
        asOfEpochMs = NOW - 3_600_000,
        signatureBase64 = "c2lnbmF0dXJl",
    )

    fun pendingPacket(): EvidencePacket {
        val base = sealedDemoPacket()
        return base.copy(
            id = "FW-1048",
            jobId = "job-FW-1048",
            title = "Streak on the tank, feeder 14",
            requesterLabel = "Creekside MUD · Utility district",
            tier = EvidenceTier.FIELD_CAPTURED,
            seal = SealState.PENDING,
            badges = base.badges.map { b ->
                if (b.key == BadgeKey.MEASURED) {
                    b.copy(state = BadgeState.MISSING, rationale = "No calibrated measurement recorded yet.")
                } else {
                    b
                }
            },
            records = base.records.take(3),
            media = base.media.take(1),
            gates = base.gates.map { gate ->
                if (gate.id == "measurement") {
                    gate.copy(passed = false, detail = "No calibrated measurement recorded.")
                } else {
                    gate
                }
            },
            measurements = emptyList(),
            sealedAtEpochMs = null,
            asOfEpochMs = NOW - 600_000,
        )
    }

    fun demoLedger(): List<LedgerEntry> = listOf(
        LedgerEntry(
            id = "led-1",
            jobId = "job-FW-0994",
            packetId = "FW-0994",
            kind = LedgerKind.EARNING,
            amountCents = 42_000,
            status = LedgerStatus.PAID,
            atEpochMs = NOW - 4_000_000,
            description = "Roof hail assessment · Panel T-14",
            idempotencyKey = "idem-FW-0994",
            demo = true,
        ),
        LedgerEntry(
            id = "led-2",
            jobId = "job-FW-1048",
            packetId = "FW-1048",
            kind = LedgerKind.EARNING,
            amountCents = 9_500,
            status = LedgerStatus.PENDING,
            atEpochMs = NOW - 600_000,
            description = "Annual RPZ test · feeder 14",
            idempotencyKey = "idem-FW-1048",
            demo = true,
        ),
        LedgerEntry(
            id = "led-3",
            jobId = null,
            packetId = "FW-0994",
            kind = LedgerKind.INSTANT_FEE,
            amountCents = -315,
            status = LedgerStatus.PAID,
            atEpochMs = NOW - 3_900_000,
            description = "Instant payout fee",
            idempotencyKey = "idem-fee-FW-0994",
            demo = true,
        ),
    )

    fun cards(): List<PacketCard> = listOf(pendingPacket(), sealedDemoPacket())
        .map { it.toCard(demoLedger()) }

    fun verifiedChain(): ChainHealth = ChainHealth(
        state = ChainHealthState.VERIFIED,
        entries = 7,
        headHash = "9f2c1a04".padEnd(64, '0'),
        detail = "7 records check from genesis.",
        checkedAtEpochMs = NOW - 120_000,
    )

    fun failedChain(): ChainHealth = ChainHealth(
        state = ChainHealthState.FAILED,
        entries = 4,
        headHash = "9f2c1a04".padEnd(64, '0'),
        brokenAtSeq = 2,
        detail = "Record 2's prev hash does not match record 1.",
        checkedAtEpochMs = NOW - 120_000,
    )

    fun vaultState(
        cards: List<PacketCard> = cards(),
        filter: VaultFilter = VaultFilter.ALL,
        chain: ChainHealth = verifiedChain(),
        demo: Boolean = true,
    ): VaultUiState = VaultUiState(
        loading = false,
        filter = filter,
        cards = cards,
        totalCards = cards.size,
        demo = demo,
        chain = chain,
    )

    fun detailState(
        packet: EvidencePacket = sealedDemoPacket(),
        chain: ChainHealth = verifiedChain(),
    ): PacketDetailUiState = PacketDetailUiState(
        loading = false,
        packet = packet,
        sealEvaluation = null,
        chain = chain,
        payoutCents = 42_000,
        payoutLabel = PayoutLabel.PAID,
        packetLedger = demoLedger().filter { it.packetId == packet.id },
    )
}
