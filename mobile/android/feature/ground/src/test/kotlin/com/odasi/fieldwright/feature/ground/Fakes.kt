package com.odasi.fieldwright.feature.ground

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
import com.odasi.fieldwright.model.AutoAcceptPolicy
import com.odasi.fieldwright.model.Balances
import com.odasi.fieldwright.model.ChainVerification
import com.odasi.fieldwright.model.ChecklistStep
import com.odasi.fieldwright.model.Consent
import com.odasi.fieldwright.model.EarningsSummary
import com.odasi.fieldwright.model.EvidencePacket
import com.odasi.fieldwright.model.GateCheck
import com.odasi.fieldwright.model.GeoPoint
import com.odasi.fieldwright.model.HotZone
import com.odasi.fieldwright.model.Job
import com.odasi.fieldwright.model.JobStatus
import com.odasi.fieldwright.model.JobType
import com.odasi.fieldwright.model.LedgerEntry
import com.odasi.fieldwright.model.MarketSnapshot
import com.odasi.fieldwright.model.Measurement
import com.odasi.fieldwright.model.MediaRef
import com.odasi.fieldwright.model.Offer
import com.odasi.fieldwright.model.Operator
import com.odasi.fieldwright.model.PacketExport
import com.odasi.fieldwright.model.PayoutMode
import com.odasi.fieldwright.model.PayoutQuote
import com.odasi.fieldwright.model.PayoutRequest
import com.odasi.fieldwright.model.ProofKind
import com.odasi.fieldwright.model.Requester
import com.odasi.fieldwright.model.RequesterKind
import com.odasi.fieldwright.model.SiteRef
import com.odasi.fieldwright.model.StepResult
import com.odasi.fieldwright.model.SyncPhase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import java.util.concurrent.atomic.AtomicInteger

/**
 * Deterministic sample book for the ViewModel tests. Same fixtures the demo market uses in
 * spirit: always `demo = true`, never a fabricated live record.
 */
object TestBook {
    const val NOW: Long = 1_700_000_000_000L

    val operator = Operator(
        id = "op-1",
        displayName = "Tam Nguyen",
        initials = "TN",
        credentialTier = 3,
        ratingAvg = 4.92,
        acceptanceRate = 0.86,
        onTimeRate = 0.94,
        completedJobs = 412,
        streakDays = 12,
        credentials = JobType.RPZ_TEST.requiredCredentials,
        payoutAccountLast4 = "4412",
        weeklyGoalCents = 120_000,
        memberSinceLabel = "Since Mar 2023",
    )

    fun point(lat: Double = 30.2691, lng: Double = -97.7421, accuracy: Float = 9f) = GeoPoint(
        lat = lat,
        lng = lng,
        recordedAtEpochMs = NOW,
        accuracyMeters = accuracy,
    )

    val site = SiteRef(
        address = "1841 Creekside Dr",
        unit = null,
        point = point(),
        geofenceRadiusMeters = 120,
        accessNotes = "Gate code 4412.",
        planLabel = "Creekside plat 14",
    )

    val requester = Requester(
        org = "Meridian Mutual",
        contactName = "Dana Reyes",
        kind = RequesterKind.INSURER,
        billingLabel = "MERIDIAN · claims",
    )

    val checklist = listOf(
        ChecklistStep("slope", "Four slopes", "Photo · required", ProofKind.PHOTO),
        ChecklistStep("paddle", "Measured paddle", "Measurement · required", ProofKind.MEASUREMENT),
    )

    fun job(
        id: String = "job-1",
        type: JobType = JobType.ROOF_HAIL,
        status: JobStatus = JobStatus.AVAILABLE,
        site: SiteRef = this.site,
        distanceMeters: Int = 2_400,
        etaMinutes: Int = 8,
        acceptedAtEpochMs: Long? = null,
        demo: Boolean = true,
    ) = Job(
        id = id,
        type = type,
        title = "${type.label} · ${site.address}",
        requester = requester,
        site = site,
        status = status,
        bountyCents = type.baseBountyCents,
        bonusCents = 1_500,
        estimatedMinutes = type.estimatedMinutes,
        scheduledForEpochMs = null,
        slaMinutes = 90,
        distanceMeters = distanceMeters,
        etaMinutes = etaMinutes,
        consentScope = type.consentScope,
        checklist = checklist,
        requiredCredentials = type.requiredCredentials,
        demo = demo,
        notes = "Sample book job.",
        acceptedAtEpochMs = acceptedAtEpochMs,
    )

    fun offer(
        id: String = "offer-1",
        job: Job = job(),
        payoutCents: Int = 42_000,
        surgeMultiplier: Double = 1.4,
        offeredAtEpochMs: Long = NOW,
        expiresAtEpochMs: Long = NOW + 45_000L,
    ) = Offer(
        id = id,
        job = job,
        operatorId = operator.id,
        payoutCents = payoutCents,
        surgeMultiplier = surgeMultiplier,
        offeredAtEpochMs = offeredAtEpochMs,
        expiresAtEpochMs = expiresAtEpochMs,
        score = 0.84,
        scoreReasons = listOf("3 min from your last fix"),
        etaMinutes = job.etaMinutes,
        autoAcceptEligible = true,
    )

    val market = MarketSnapshot(
        nowEpochMs = NOW,
        demandIndex = 0.72,
        supplyIndex = 0.31,
        hotZones = listOf(
            HotZone(
                label = "Creekside",
                point = point(30.2701, -97.7435),
                radiusMeters = 850,
                surgeMultiplier = 1.4,
                reason = "Two inspectors offline after the hail event",
            ),
        ),
        daylightLabel = "2h 40m",
        weatherLabel = "Clear 78°",
    )
}

/** Fakes are strict: they record every write so a test can assert what the UI asked for. */
class FakeOperatorRepository(
    val operatorFlow: MutableStateFlow<Operator> = MutableStateFlow(TestBook.operator),
    val availabilityFlow: MutableStateFlow<Availability> = MutableStateFlow(
        Availability(isOnline = true, updatedAtEpochMs = TestBook.NOW, radiusMeters = 15_000),
    ),
) : OperatorRepository {

    val setOnlineCalls = mutableListOf<Pair<Boolean, Long>>()
    val radiusCalls = mutableListOf<Int>()
    val autoAcceptCalls = mutableListOf<AutoAcceptPolicy>()
    var failNextWrite: Throwable? = null

    override fun observeOperator(): Flow<Operator> = operatorFlow
    override fun observeAvailability(): Flow<Availability> = availabilityFlow

    override suspend fun setOnline(online: Boolean, atEpochMs: Long): Result<Availability> {
        setOnlineCalls += online to atEpochMs
        failNextWrite?.let { error ->
            failNextWrite = null
            return Result.failure(error)
        }
        val next = availabilityFlow.value.copy(isOnline = online, updatedAtEpochMs = atEpochMs)
        availabilityFlow.value = next
        return Result.success(next)
    }

    override suspend fun updateRadius(radiusMeters: Int): Result<Availability> {
        radiusCalls += radiusMeters
        val next = availabilityFlow.value.copy(radiusMeters = radiusMeters)
        availabilityFlow.value = next
        return Result.success(next)
    }

    override suspend fun updateAutoAccept(policy: AutoAcceptPolicy): Result<Availability> {
        autoAcceptCalls += policy
        val next = availabilityFlow.value.copy(autoAccept = policy)
        availabilityFlow.value = next
        return Result.success(next)
    }
}

class FakeDispatchRepository(
    val offersFlow: MutableStateFlow<List<Offer>> = MutableStateFlow(emptyList()),
    val marketFlow: MutableStateFlow<MarketSnapshot> = MutableStateFlow(TestBook.market),
) : DispatchRepository {

    val acceptedOfferIds = mutableListOf<String>()
    val declinedOfferIds = mutableListOf<Pair<String, String>>()
    val refreshFixes = mutableListOf<GeoPoint>()
    val expireCalls = mutableListOf<Long>()

    var acceptResult: (String) -> Result<Job> = { Result.success(TestBook.job(status = JobStatus.ACCEPTED)) }
    var declineResult: () -> Result<Unit> = { Result.success(Unit) }
    var refreshResult: Result<Int> = Result.success(2)

    override fun observeOffers(): Flow<List<Offer>> = offersFlow
    override fun observeMarket(): Flow<MarketSnapshot> = marketFlow
    override fun observeHotZonesZone(): Flow<Int> = flowOf(1)

    override suspend fun refresh(operatorPoint: GeoPoint, atEpochMs: Long): Result<Int> {
        refreshFixes += operatorPoint
        return refreshResult
    }

    override suspend fun acceptOffer(offerId: String, atEpochMs: Long): Result<Job> {
        acceptedOfferIds += offerId
        return acceptResult(offerId)
    }

    override suspend fun declineOffer(offerId: String, reason: String, atEpochMs: Long): Result<Unit> {
        declinedOfferIds += offerId to reason
        return declineResult()
    }

    override suspend fun expireOffers(nowEpochMs: Long): Result<Int> {
        expireCalls += nowEpochMs
        return Result.success(1)
    }
}

class FakeJobRepository(
    val activeJobsFlow: MutableStateFlow<List<Job>> = MutableStateFlow(emptyList()),
    val completedJobsFlow: MutableStateFlow<List<Job>> = MutableStateFlow(emptyList()),
    val jobsById: MutableMap<String, MutableStateFlow<Job?>> = mutableMapOf(),
) : JobRepository {

    val enRouteCalls = mutableListOf<String>()
    val checkArrivalCalls = mutableListOf<Pair<String, GeoPoint>>()
    val confirmArrivalCalls = mutableListOf<Triple<String, GeoPoint, String?>>()
    val startCaptureCalls = mutableListOf<String>()

    var arrivalCheck: (String, GeoPoint) -> Result<ArrivalCheck> = { _, _ ->
        Result.success(
            ArrivalCheck(
                accepted = true,
                insideGeofence = true,
                distanceMeters = 14.0,
                accuracyMeters = 9f,
                detail = "Inside the 120 m geofence.",
                overrideRequired = false,
            ),
        )
    }
    var confirmArrivalResult: (String) -> Result<Job> = { jobId ->
        Result.success(TestBook.job(id = jobId, status = JobStatus.ON_SITE, acceptedAtEpochMs = TestBook.NOW))
    }
    var startCaptureResult: (String) -> Result<Job> = { jobId ->
        Result.success(TestBook.job(id = jobId, status = JobStatus.IN_PROGRESS, acceptedAtEpochMs = TestBook.NOW))
    }

    override fun observeActiveJobs(): Flow<List<Job>> = activeJobsFlow
    override fun observeCompletedJobs(): Flow<List<Job>> = completedJobsFlow
    override fun observeJob(jobId: String): Flow<Job?> =
        jobsById.getOrPut(jobId) { MutableStateFlow<Job?>(null) }

    override suspend fun markEnRoute(jobId: String, atEpochMs: Long): Result<Job> {
        enRouteCalls += jobId
        val job = TestBook.job(id = jobId, status = JobStatus.EN_ROUTE, acceptedAtEpochMs = atEpochMs)
        jobsById.getOrPut(jobId) { MutableStateFlow<Job?>(null) }.value = job
        return Result.success(job)
    }

    override suspend fun checkArrival(jobId: String, fix: GeoPoint, atEpochMs: Long): Result<ArrivalCheck> {
        checkArrivalCalls += jobId to fix
        return arrivalCheck(jobId, fix)
    }

    override suspend fun confirmArrival(jobId: String, fix: GeoPoint, overrideReason: String?, atEpochMs: Long): Result<Job> {
        confirmArrivalCalls += Triple(jobId, fix, overrideReason)
        return confirmArrivalResult(jobId).also { result ->
            result.getOrNull()?.let { job ->
            jobsById.getOrPut(jobId) { MutableStateFlow<Job?>(null) }.value = job
            }
        }
    }

    override suspend fun startCapture(jobId: String, atEpochMs: Long): Result<Job> {
        startCaptureCalls += jobId
        return startCaptureResult(jobId)
    }

    override suspend fun markCompletedLocally(jobId: String, atEpochMs: Long): Result<Job> =
        Result.success(TestBook.job(id = jobId, status = JobStatus.PACKET_READY))
}

class FakeCaptureRepository : CaptureRepository {
    override fun observeWork(jobId: String): Flow<ActiveWork?> = flowOf(null)
    override suspend fun begin(jobId: String, atEpochMs: Long): Result<ActiveWork> = unused()
    override suspend fun addMedia(jobId: String, media: MediaRef, atEpochMs: Long): Result<ActiveWork> = unused()
    override suspend fun addMeasurement(jobId: String, measurement: Measurement): Result<ActiveWork> = unused()
    override suspend fun setConsent(jobId: String, consent: Consent): Result<ActiveWork> = unused()
    override suspend fun completeStep(
        jobId: String,
        stepKey: String,
        result: StepResult,
        note: String,
        mediaIds: List<String>,
    ): Result<ActiveWork> = unused()

    override suspend fun runGates(jobId: String, nowEpochMs: Long): Result<List<GateCheck>> = unused()
    override suspend fun seal(jobId: String, nowEpochMs: Long): Result<SealOutcome> = unused()

    private fun <T> unused(): Result<T> = Result.failure(UnsupportedOperationException("not used by these tests"))
}

class FakeVaultRepository : VaultRepository {
    override fun observePackets(): Flow<List<EvidencePacket>> = flowOf(emptyList())
    override fun observePacket(packetId: String): Flow<EvidencePacket?> = flowOf(null)
    override suspend fun verifyChain(packetId: String?): Result<ChainVerification> =
        Result.success(ChainVerification(valid = true, entries = 0, brokenAtSeq = null, headHash = "00", detail = ""))

    override suspend fun exportPacket(packetId: String): Result<PacketExport> =
        Result.failure(UnsupportedOperationException("not used by these tests"))

    override suspend fun appendCorrection(packetId: String, note: String, atEpochMs: Long): Result<EvidencePacket> =
        Result.failure(UnsupportedOperationException("not used by these tests"))
}

class FakeEarningsRepository(
    val summaryFlow: MutableStateFlow<EarningsSummary> = MutableStateFlow(
        EarningsSummary(
            todayCents = 18_500,
            weekCents = 96_000,
            monthCents = 340_000,
            onlineMinutesToday = 214,
            jobsCompletedWeek = 9,
            milesDrivenWeek = 61.4,
            bonusesWeekCents = 4_000,
            tipsWeekCents = 1_200,
            platformFeeWeekCents = 0,
            mileageDeductionCents = 4_298,
        ),
    ),
) : EarningsRepository {
    override fun observeBalances(): Flow<Balances> = flowOf(
        Balances(
            availableCents = 18_500,
            pendingCents = 7_000,
            paidLifetimeCents = 1_240_000,
            instantFeePaidLifetimeCents = 1_850,
            currentStreakDays = 12,
            weeklyGoalCents = 120_000,
        ),
    )

    override fun observeSummary(): Flow<EarningsSummary> = summaryFlow
    override fun observeLedger(): Flow<List<LedgerEntry>> = flowOf(emptyList())
    override fun observePayouts(): Flow<List<PayoutRequest>> = flowOf(emptyList())
    override fun quote(amountCents: Int, mode: PayoutMode): PayoutQuote = PayoutQuote(
        amountCents = amountCents,
        feeCents = 0,
        netCents = amountCents,
        mode = mode,
        etaLabel = "1–2 business days",
        feeDisclosure = "Standard ACH is free.",
    )

    override suspend fun requestPayout(amountCents: Int, mode: PayoutMode, atEpochMs: Long): Result<PayoutRequest> =
        Result.failure(UnsupportedOperationException("not used by these tests"))
}

class FakeSyncRepository(
    val syncFlow: MutableStateFlow<SyncState> = MutableStateFlow(
        SyncState(
            phase = SyncPhase.IDLE,
            queued = 0,
            inFlight = 0,
            deadLetters = 0,
            lastSyncedAtEpochMs = TestBook.NOW,
            lastError = null,
        ),
    ),
    var report: SyncReport = SyncReport(
        attempted = 0,
        succeeded = 0,
        failed = 0,
        nextRetryAtEpochMs = null,
        detail = "Everything was already in sync.",
    ),
) : SyncRepository {
    val flushCalls = AtomicInteger(0)
    override fun observeSyncState(): Flow<SyncState> = syncFlow
    override suspend fun flushNow(): SyncReport {
        flushCalls.incrementAndGet()
        return report
    }
}

/** One container so a test builds the ViewModel with everything faked in one line. */
class FakeFieldwrightRepositories(
    override val operator: FakeOperatorRepository = FakeOperatorRepository(),
    override val dispatch: FakeDispatchRepository = FakeDispatchRepository(),
    override val jobs: FakeJobRepository = FakeJobRepository(),
    override val capture: FakeCaptureRepository = FakeCaptureRepository(),
    override val vault: FakeVaultRepository = FakeVaultRepository(),
    override val earnings: FakeEarningsRepository = FakeEarningsRepository(),
    override val sync: FakeSyncRepository = FakeSyncRepository(),
) : FieldwrightRepositories
