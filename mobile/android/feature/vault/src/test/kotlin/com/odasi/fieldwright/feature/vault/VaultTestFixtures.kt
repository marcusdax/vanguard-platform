package com.odasi.fieldwright.feature.vault

import com.odasi.fieldwright.data.ArrivalCheck
import com.odasi.fieldwright.data.CaptureRepository
import com.odasi.fieldwright.data.DispatchRepository
import com.odasi.fieldwright.data.EarningsRepository
import com.odasi.fieldwright.data.FieldwrightRepositories
import com.odasi.fieldwright.data.JobRepository
import com.odasi.fieldwright.data.OperatorRepository
import com.odasi.fieldwright.data.SealOutcome
import com.odasi.fieldwright.data.SyncReport
import com.odasi.fieldwright.data.SyncRepository
import com.odasi.fieldwright.data.SyncState
import com.odasi.fieldwright.data.VaultRepository
import com.odasi.fieldwright.model.ActiveWork
import com.odasi.fieldwright.model.Availability
import com.odasi.fieldwright.model.Badge
import com.odasi.fieldwright.model.BadgeKey
import com.odasi.fieldwright.model.BadgeState
import com.odasi.fieldwright.model.Balances
import com.odasi.fieldwright.model.ChainRecord
import com.odasi.fieldwright.model.ChainVerification
import com.odasi.fieldwright.model.ChecklistResult
import com.odasi.fieldwright.model.Consent
import com.odasi.fieldwright.model.ConsentMethod
import com.odasi.fieldwright.model.EarningsSummary
import com.odasi.fieldwright.model.EvidencePacket
import com.odasi.fieldwright.model.EvidenceTier
import com.odasi.fieldwright.model.GateCheck
import com.odasi.fieldwright.model.GeoPoint
import com.odasi.fieldwright.model.Job
import com.odasi.fieldwright.model.JobStatus
import com.odasi.fieldwright.model.JobType
import com.odasi.fieldwright.model.LedgerEntry
import com.odasi.fieldwright.model.LedgerKind
import com.odasi.fieldwright.model.LedgerStatus
import com.odasi.fieldwright.model.MarketSnapshot
import com.odasi.fieldwright.model.Measurement
import com.odasi.fieldwright.model.MediaRef
import com.odasi.fieldwright.model.Offer
import com.odasi.fieldwright.model.Operator
import com.odasi.fieldwright.model.PacketExport
import com.odasi.fieldwright.model.PayoutMode
import com.odasi.fieldwright.model.PayoutQuote
import com.odasi.fieldwright.model.PayoutRequest
import com.odasi.fieldwright.model.RecordKind
import com.odasi.fieldwright.model.SealState
import com.odasi.fieldwright.model.SiteRef
import com.odasi.fieldwright.model.StepResult
import com.odasi.fieldwright.model.SyncPhase
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** A fixed "now" for the tests: 2026-11-16T00:00:00Z. */
internal const val TEST_NOW_MS: Long = 1_763_251_200_000L

internal val TEST_POINT = GeoPoint(30.2672, -97.7431, TEST_NOW_MS, 8f)

internal fun operatorFixture(): Operator = Operator(
    id = "op-elena",
    displayName = "Elena Voss",
    initials = "EV",
    credentialTier = 3,
    ratingAvg = 4.9,
    acceptanceRate = 0.92,
    onTimeRate = 0.97,
    completedJobs = 38,
    streakDays = 12,
    credentials = emptySet(),
    payoutAccountLast4 = "4242",
    weeklyGoalCents = 30_000,
    memberSinceLabel = "March 2025",
)

private fun siteFixture(): SiteRef = SiteRef(
    address = "14 Creekside Mews",
    unit = null,
    point = TEST_POINT,
    geofenceRadiusMeters = 120,
    accessNotes = "Side gate, code 4412.",
    planLabel = "Panel T-14",
)

private fun record(
    seq: Int,
    kind: RecordKind,
    atEpochMs: Long,
    hash: String,
    signed: Boolean = true,
): ChainRecord = ChainRecord(
    seq = seq,
    kind = kind,
    atEpochMs = atEpochMs,
    actorId = "op-elena",
    summary = "Record $seq · ${kind.label}",
    payload = mapOf("seq" to seq.toString()),
    prevHash = "0".repeat(64),
    hash = hash,
    signatureBase64 = if (signed) "c2lnbmF0dXJl" else null,
    signerKeyId = if (signed) "key-1" else null,
)

private fun verifiedBadge(key: BadgeKey, required: Boolean = true): Badge = Badge(
    key = key,
    state = BadgeState.VERIFIED,
    required = required,
    rationale = "${key.label} evidence verified.",
    evidenceRefs = listOf("rec-1"),
)

internal fun sealedPacketFixture(
    id: String = "FW-0994",
    title: String = "Panel T-14 is set",
    demo: Boolean = false,
    payoutHash: String = "9f2c1a04" + "0".repeat(56),
): EvidencePacket = EvidencePacket(
    id = id,
    jobId = "job-$id",
    jobType = JobType.ROOF_HAIL,
    operatorId = "op-elena",
    title = title,
    requesterLabel = "Lamar Tilt Partners · Insurer",
    site = siteFixture(),
    consent = Consent(
        name = "Chris Adeyemi",
        role = "Homeowner",
        scopeText = "Roof surfaces, slopes and penetrations. Not the interior of the home.",
        method = ConsentMethod.IN_APP_SIGNATURE,
        capturedAtEpochMs = TEST_NOW_MS - 7_200_000,
        signatureHash = "a".repeat(64),
        verifiedByOperatorId = "op-elena",
    ),
    badges = listOf(
        verifiedBadge(BadgeKey.STORM_ATTRIBUTION),
        verifiedBadge(BadgeKey.FIELD_CAPTURED),
        verifiedBadge(BadgeKey.MEASURED),
        verifiedBadge(BadgeKey.SCOPE_AGREED),
        verifiedBadge(BadgeKey.CONSENT_ON_FILE),
    ),
    tier = EvidenceTier.VERIFIED_BY_AUCTUM,
    seal = SealState.EARNED,
    records = listOf(
        record(1, RecordKind.CAPTURE, TEST_NOW_MS - 7_000_000, "1".repeat(64)),
        record(2, RecordKind.ATTEST, TEST_NOW_MS - 6_000_000, "2".repeat(64)),
        record(3, RecordKind.CUSTODY, TEST_NOW_MS - 5_000_000, "3".repeat(64)),
        record(4, RecordKind.PAYMENT, TEST_NOW_MS - 4_000_000, payoutHash),
    ),
    media = listOf(
        MediaRef(
            id = "media-1",
            kind = com.odasi.fieldwright.model.MediaKind.PHOTO,
            sha256 = "e".repeat(64),
            bytes = 221_000,
            capturedAtEpochMs = TEST_NOW_MS - 7_000_000,
            width = 1920,
            height = 1080,
            redaction = com.odasi.fieldwright.model.RedactionState.APPLIED,
            source = com.odasi.fieldwright.model.MediaSource.CAMERA,
            note = "South slope, daylight.",
        ),
    ),
    checklist = listOf(
        ChecklistResult("footprint", StepResult.PASS, "Event footprint recorded."),
        ChecklistResult("slopes", StepResult.PASS, "Four slopes captured."),
        ChecklistResult("square", StepResult.PASS, "Test square counted."),
        ChecklistResult("paddle", StepResult.PASS, "Paddle measured."),
        ChecklistResult("scope", StepResult.PASS, "Scope accepted."),
    ),
    gates = listOf(
        GateCheck("consent", "Consent on file", true, "Signed in app.", blocking = true),
        GateCheck("geofence", "Geofence satisfied", true, "Inside 120 m.", blocking = true),
        GateCheck("redaction", "Redaction resolved", true, "One plate redacted.", blocking = true),
        GateCheck("measurement", "Measurement calibrated", true, "Site card reference.", blocking = true),
        GateCheck("chain", "Chain verifies", true, "4 records.", blocking = true),
    ),
    measurements = listOf(
        Measurement(
            id = "m-1",
            label = "Hail strike",
            meters = 0.42,
            referenceLabel = "Site card 30 cm",
            referenceMeters = 0.30,
            pixelSpan = 140.0,
            calibratedAtEpochMs = TEST_NOW_MS - 6_500_000,
            source = com.odasi.fieldwright.model.MediaSource.CAMERA,
        ),
    ),
    notes = "Panel T-14 was set before the storm; strikes are on the south slope only.",
    demo = demo,
    createdAtEpochMs = TEST_NOW_MS - 8_000_000,
    sealedAtEpochMs = TEST_NOW_MS - 4_000_000,
    merkleLeaf = "b".repeat(64),
    merkleProof = listOf("c".repeat(64), "d".repeat(64)),
    asOfEpochMs = TEST_NOW_MS - 3_600_000,
    signatureBase64 = "c2lnbmF0dXJl",
)

internal fun pendingPacketFixture(
    id: String = "FW-1048",
    title: String = "Streak on the tank, feeder 14",
    seal: SealState = SealState.PENDING,
    flagged: Boolean = false,
): EvidencePacket {
    val base = sealedPacketFixture(id = id, title = title)
    val badges = base.badges.map { badge ->
        when {
            flagged && badge.key == BadgeKey.MEASURED -> badge.copy(
                state = BadgeState.FLAGGED,
                rationale = "The measurement contradicts the reference card.",
            )
            seal == SealState.PENDING && badge.key == BadgeKey.MEASURED -> badge.copy(
                state = BadgeState.MISSING,
                rationale = "No calibrated measurement recorded yet.",
            )
            else -> badge
        }
    }
    return base.copy(
        seal = seal,
        tier = if (seal == SealState.EARNED) EvidenceTier.VERIFIED_BY_AUCTUM else EvidenceTier.FIELD_CAPTURED,
        badges = badges,
        records = base.records.take(2),
        sealedAtEpochMs = if (seal == SealState.EARNED) base.sealedAtEpochMs else null,
        merkleLeaf = if (seal == SealState.EARNED) base.merkleLeaf else null,
        merkleProof = if (seal == SealState.EARNED) base.merkleProof else emptyList(),
        gates = base.gates.map { gate ->
            if (gate.id == "measurement" && seal != SealState.EARNED) {
                gate.copy(passed = false, detail = "No calibrated measurement recorded.")
            } else {
                gate
            }
        },
    )
}

internal fun ledgerFixture(packetId: String = "FW-0994", amountCents: Int = 42_000): List<LedgerEntry> =
    listOf(
        LedgerEntry(
            id = "led-1",
            jobId = "job-FW-0994",
            packetId = packetId,
            kind = LedgerKind.EARNING,
            amountCents = amountCents,
            status = LedgerStatus.PAID,
            atEpochMs = TEST_NOW_MS - 4_000_000,
            description = "Roof hail assessment · Panel T-14",
            idempotencyKey = "idem-1",
            demo = true,
        ),
    )

/**
 * Minimal repository container for the Vault ViewModel tests. Only the vault and ledger flows
 * carry fixture data; the rest refuse politely so a stray call fails the test instead of
 * pretending to work.
 */
internal class FakeFieldwrightForVault(
    packets: List<EvidencePacket> = emptyList(),
    ledger: List<LedgerEntry> = emptyList(),
) : FieldwrightRepositories {

    val vaultRepo = FakeVaultRepository(packets)
    val earningsRepo = FakeEarningsRepository(ledger)

    override val operator: OperatorRepository = object : OperatorRepository {
        override fun observeOperator(): Flow<Operator> = MutableStateFlow(operatorFixture())
        override fun observeAvailability(): Flow<Availability> = MutableStateFlow(Availability())
        override suspend fun setOnline(online: Boolean, atEpochMs: Long): Result<Availability> =
            unsupported()
        override suspend fun updateRadius(radiusMeters: Int): Result<Availability> = unsupported()
        override suspend fun updateAutoAccept(policy: com.odasi.fieldwright.model.AutoAcceptPolicy): Result<Availability> =
            unsupported()
    }

    override val dispatch: DispatchRepository = object : DispatchRepository {
        override fun observeOffers(): Flow<List<Offer>> = MutableStateFlow(emptyList())
        override fun observeMarket(): Flow<MarketSnapshot> = MutableStateFlow(
            MarketSnapshot(TEST_NOW_MS, 0.5, 0.5, emptyList(), "3 h daylight", "Clear"),
        )
        override fun observeHotZonesZone(): Flow<Int> = MutableStateFlow(0)
        override suspend fun refresh(operatorPoint: GeoPoint, atEpochMs: Long): Result<Int> = unsupported()
        override suspend fun acceptOffer(offerId: String, atEpochMs: Long): Result<Job> = unsupported()
        override suspend fun declineOffer(offerId: String, reason: String, atEpochMs: Long): Result<Unit> = unsupported()
        override suspend fun expireOffers(nowEpochMs: Long): Result<Int> = unsupported()
    }

    override val jobs: JobRepository = object : JobRepository {
        override fun observeActiveJobs(): Flow<List<Job>> = MutableStateFlow(emptyList())
        override fun observeCompletedJobs(): Flow<List<Job>> = MutableStateFlow(emptyList())
        override fun observeJob(jobId: String): Flow<Job?> = MutableStateFlow(null)
        override suspend fun markEnRoute(jobId: String, atEpochMs: Long): Result<Job> = unsupported()
        override suspend fun checkArrival(jobId: String, fix: GeoPoint, atEpochMs: Long): Result<ArrivalCheck> =
            unsupported()
        override suspend fun confirmArrival(jobId: String, fix: GeoPoint, overrideReason: String?, atEpochMs: Long): Result<Job> =
            unsupported()
        override suspend fun startCapture(jobId: String, atEpochMs: Long): Result<Job> = unsupported()
        override suspend fun markCompletedLocally(jobId: String, atEpochMs: Long): Result<Job> = unsupported()
    }

    override val capture: CaptureRepository = object : CaptureRepository {
        override fun observeWork(jobId: String): Flow<ActiveWork?> = MutableStateFlow(null)
        override suspend fun begin(jobId: String, atEpochMs: Long): Result<ActiveWork> = unsupported()
        override suspend fun addMedia(jobId: String, media: MediaRef, atEpochMs: Long): Result<ActiveWork> = unsupported()
        override suspend fun addMeasurement(jobId: String, measurement: Measurement): Result<ActiveWork> = unsupported()
        override suspend fun setConsent(jobId: String, consent: Consent): Result<ActiveWork> = unsupported()
        override suspend fun completeStep(
            jobId: String,
            stepKey: String,
            result: StepResult,
            note: String,
            mediaIds: List<String>,
        ): Result<ActiveWork> = unsupported()
        override suspend fun runGates(jobId: String, nowEpochMs: Long): Result<List<GateCheck>> = unsupported()
        override suspend fun seal(jobId: String, nowEpochMs: Long): Result<SealOutcome> = unsupported()
    }

    override val vault: VaultRepository get() = vaultRepo
    override val earnings: EarningsRepository get() = earningsRepo

    override val sync: SyncRepository = object : SyncRepository {
        override fun observeSyncState(): Flow<SyncState> = MutableStateFlow(
            SyncState(SyncPhase.IDLE, 0, 0, 0, TEST_NOW_MS, null),
        )
        override suspend fun flushNow(): SyncReport = SyncReport(0, 0, 0, null, "no-op")
    }
}

internal class FakeVaultRepository(packets: List<EvidencePacket>) : VaultRepository {
    val packetFlow = MutableStateFlow(packets)
    var verifyResult: Result<ChainVerification> = Result.success(
        ChainVerification(
            valid = true,
            entries = 4,
            brokenAtSeq = null,
            headHash = "9f2c1a04".padEnd(64, '0'),
            detail = "4 records check from genesis.",
        ),
    )
    var exportHandler: (String) -> Result<PacketExport> = { id ->
        Result.success(
            PacketExport(
                fileName = "$id.json",
                canonicalJson = "{\"packet\":\"$id\"}",
                custodyText = "custody: $id",
                chainHead = "9f2c1a04".padEnd(64, '0'),
                recordCount = 4,
            ),
        )
    }
    var appendHandler: ((String, String) -> Result<EvidencePacket>)? = null
    val verificationCalls = mutableListOf<String?>()
    val exportCalls = mutableListOf<String>()
    val correctionCalls = mutableListOf<Pair<String, String>>()
    private val correctionSeq = AtomicInteger(0)

    override fun observePackets(): Flow<List<EvidencePacket>> = packetFlow
    override fun observePacket(packetId: String): Flow<EvidencePacket?> =
        MutableStateFlow(packetFlow.value.firstOrNull { it.id == packetId })

    override suspend fun verifyChain(packetId: String?): Result<ChainVerification> {
        verificationCalls += packetId
        return verifyResult
    }

    override suspend fun exportPacket(packetId: String): Result<PacketExport> {
        exportCalls += packetId
        return exportHandler(packetId)
    }

    override suspend fun appendCorrection(packetId: String, note: String, atEpochMs: Long): Result<EvidencePacket> {
        correctionCalls += packetId to note
        appendHandler?.let { return it(packetId, note) }
        val current = packetFlow.value.firstOrNull { it.id == packetId }
            ?: return Result.failure(IllegalStateException("packet $packetId not found"))
        val appended = current.copy(
            records = current.records + ChainRecord(
                seq = current.records.size + 1 + correctionSeq.getAndIncrement(),
                kind = RecordKind.CORRECTION,
                atEpochMs = atEpochMs,
                actorId = "op-elena",
                summary = "Correction: $note",
                payload = mapOf("note" to note),
                prevHash = current.records.lastOrNull()?.hash ?: "0".repeat(64),
                hash = "f".repeat(64),
                signatureBase64 = "c2lnbmF0dXJl",
                signerKeyId = "key-1",
            ),
        )
        packetFlow.value = packetFlow.value.map { if (it.id == packetId) appended else it }
        return Result.success(appended)
    }
}

internal class FakeEarningsRepository(ledger: List<LedgerEntry>) : EarningsRepository {
    val ledgerFlow = MutableStateFlow(ledger)
    override fun observeBalances(): Flow<Balances> = MutableStateFlow(
        Balances(0, 0, 0, 0, 0, 0),
    )
    override fun observeSummary(): Flow<EarningsSummary> = MutableStateFlow(
        EarningsSummary(0, 0, 0, 0, 0, 0.0, 0, 0, 0, 0),
    )
    override fun observeLedger(): Flow<List<LedgerEntry>> = ledgerFlow
    override fun observePayouts(): Flow<List<PayoutRequest>> = MutableStateFlow(emptyList())
    override fun quote(amountCents: Int, mode: PayoutMode): PayoutQuote =
        PayoutQuote(amountCents, 0, amountCents, mode, "1–2 business days", "No fee.")
    override suspend fun requestPayout(amountCents: Int, mode: PayoutMode, atEpochMs: Long): Result<PayoutRequest> =
        unsupported()
}

private fun <T> unsupported(): Result<T> =
    Result.failure(UnsupportedOperationException("not used by the Vault tests"))
