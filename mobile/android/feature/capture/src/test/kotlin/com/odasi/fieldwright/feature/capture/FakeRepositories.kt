package com.odasi.fieldwright.feature.capture

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
import com.odasi.fieldwright.model.AutoAcceptPolicy
import com.odasi.fieldwright.model.Availability
import com.odasi.fieldwright.model.Balances
import com.odasi.fieldwright.model.ChainVerification
import com.odasi.fieldwright.model.ChecklistResult
import com.odasi.fieldwright.model.Consent
import com.odasi.fieldwright.model.EarningsSummary
import com.odasi.fieldwright.model.EvidencePacket
import com.odasi.fieldwright.model.GateCheck
import com.odasi.fieldwright.model.GeoPoint
import com.odasi.fieldwright.model.Job
import com.odasi.fieldwright.model.JobStatus
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
import com.odasi.fieldwright.model.StepResult
import com.odasi.fieldwright.model.SyncPhase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * Hand-written fakes for the capture ViewModel tests.
 *
 * They implement the repository contracts only, so the ViewModel state machine is tested
 * without Room, WorkManager, Compose or a camera. Every fake records what the ViewModel
 * asked for, which is how the tests assert that a blocking decision really prevented a
 * write instead of merely disabling a button.
 */
class FakeFieldwrightRepositories(
    initialJob: Job?,
    initialWork: ActiveWork? = null,
    operator: Operator = defaultOperator,
) : FieldwrightRepositories {

    val operatorRepo = FakeOperatorRepository(operator)
    val jobRepo = FakeJobRepository(initialJob)
    val captureRepo = FakeCaptureRepository(initialJob?.id ?: "job", initialWork)
    val syncRepo = FakeSyncRepository()

    override val operator: OperatorRepository = operatorRepo
    override val dispatch: DispatchRepository = FakeDispatchRepository()
    override val jobs: JobRepository = jobRepo
    override val capture: CaptureRepository = captureRepo
    override val vault: VaultRepository = FakeVaultRepository()
    override val earnings: EarningsRepository = FakeEarningsRepository()
    override val sync: SyncRepository = syncRepo

    companion object {
        val defaultOperator = Operator(
            id = "op-test",
            displayName = "Test Operator",
            initials = "TO",
            credentialTier = 2,
            ratingAvg = 4.8,
            acceptanceRate = 0.9,
            onTimeRate = 0.95,
            completedJobs = 40,
            streakDays = 3,
            credentials = emptySet(),
            payoutAccountLast4 = "0000",
            weeklyGoalCents = 100_000,
            memberSinceLabel = "Jan 2025",
        )
    }
}

class FakeOperatorRepository(initial: Operator) : OperatorRepository {
    val operatorFlow = MutableStateFlow(initial)

    override fun observeOperator(): Flow<Operator> = operatorFlow

    override fun observeAvailability(): Flow<Availability> = flowOf(Availability())

    override suspend fun setOnline(online: Boolean, atEpochMs: Long): Result<Availability> =
        Result.success(Availability(isOnline = online, updatedAtEpochMs = atEpochMs))

    override suspend fun updateRadius(radiusMeters: Int): Result<Availability> =
        Result.success(Availability(radiusMeters = radiusMeters))

    override suspend fun updateAutoAccept(policy: AutoAcceptPolicy): Result<Availability> =
        Result.success(Availability(autoAccept = policy))
}

class FakeJobRepository(initial: Job?) : JobRepository {
    val jobFlow = MutableStateFlow(initial)
    val activeJobs = MutableStateFlow(listOfNotNull(initial))

    override fun observeActiveJobs(): Flow<List<Job>> = activeJobs

    override fun observeCompletedJobs(): Flow<List<Job>> = flowOf(emptyList())

    override fun observeJob(jobId: String): Flow<Job?> = jobFlow

    override suspend fun markEnRoute(jobId: String, atEpochMs: Long): Result<Job> = unused()

    override suspend fun checkArrival(jobId: String, fix: GeoPoint, atEpochMs: Long): Result<ArrivalCheck> = unused()

    override suspend fun confirmArrival(
        jobId: String,
        fix: GeoPoint,
        overrideReason: String?,
        atEpochMs: Long,
    ): Result<Job> = unused()

    override suspend fun startCapture(jobId: String, atEpochMs: Long): Result<Job> = unused()

    override suspend fun markCompletedLocally(jobId: String, atEpochMs: Long): Result<Job> = unused()

    private fun <T> unused(): Result<T> =
        Result.failure(UnsupportedOperationException("Not used by the capture tests."))
}

class FakeCaptureRepository(
    private val jobId: String,
    initialWork: ActiveWork? = null,
) : CaptureRepository {

    val work = MutableStateFlow(initialWork)
    val mediaAdded = mutableListOf<MediaRef>()
    val measurementsAdded = mutableListOf<Measurement>()
    val consentsRecorded = mutableListOf<Consent>()
    val completedSteps = mutableListOf<String>()

    var beginCalls: Int = 0
    var gatesCalls: Int = 0
    var sealCalls: Int = 0

    var beginFailure: Throwable? = null
    var setConsentFailure: Throwable? = null
    var addMediaFailure: Throwable? = null
    var addMeasurementFailure: Throwable? = null
    var completeStepFailure: Throwable? = null
    var gatesResult: Result<List<GateCheck>> = Result.success(passingGates())
    var sealResult: Result<SealOutcome> = Result.failure(UnsupportedOperationException("Seal result not configured."))

    private fun active(): ActiveWork = work.value ?: ActiveWork(
        jobId = jobId,
        status = JobStatus.IN_PROGRESS,
        acceptedAtEpochMs = 0L,
    )

    override fun observeWork(jobId: String): Flow<ActiveWork?> = work

    override suspend fun begin(jobId: String, atEpochMs: Long): Result<ActiveWork> {
        beginCalls++
        beginFailure?.let { return Result.failure(it) }
        val current = work.value
        val next = current ?: ActiveWork(
            jobId = jobId,
            status = JobStatus.IN_PROGRESS,
            acceptedAtEpochMs = atEpochMs,
            startedAtEpochMs = atEpochMs,
        )
        work.value = next
        return Result.success(next)
    }

    override suspend fun addMedia(jobId: String, media: MediaRef, atEpochMs: Long): Result<ActiveWork> {
        addMediaFailure?.let { return Result.failure(it) }
        mediaAdded += media
        val next = active().copy(media = active().media + media)
        work.value = next
        return Result.success(next)
    }

    override suspend fun addMeasurement(jobId: String, measurement: Measurement): Result<ActiveWork> {
        addMeasurementFailure?.let { return Result.failure(it) }
        measurementsAdded += measurement
        val next = active().copy(measurements = active().measurements + measurement)
        work.value = next
        return Result.success(next)
    }

    override suspend fun setConsent(jobId: String, consent: Consent): Result<ActiveWork> {
        setConsentFailure?.let { return Result.failure(it) }
        consentsRecorded += consent
        val next = active().copy(consent = consent)
        work.value = next
        return Result.success(next)
    }

    override suspend fun completeStep(
        jobId: String,
        stepKey: String,
        result: StepResult,
        note: String,
        mediaIds: List<String>,
    ): Result<ActiveWork> {
        completeStepFailure?.let { return Result.failure(it) }
        completedSteps += stepKey
        val next = active().copy(
            checklist = active().checklist + (stepKey to ChecklistResult(stepKey, result, note, mediaIds)),
        )
        work.value = next
        return Result.success(next)
    }

    override suspend fun runGates(jobId: String, nowEpochMs: Long): Result<List<GateCheck>> {
        gatesCalls++
        return gatesResult
    }

    override suspend fun seal(jobId: String, nowEpochMs: Long): Result<SealOutcome> {
        sealCalls++
        return sealResult
    }

    companion object {
        fun passingGates(): List<GateCheck> = listOf(
            GateCheck("consent_on_file", "Consent on file", true, "Signed in app.", true),
            GateCheck("geofence", "Geofence satisfied", true, "84 m from centre.", true),
            GateCheck("media_hashed", "Media hashed", true, "3 frames.", true),
            GateCheck("checklist", "Checklist complete", true, "All required steps.", true),
            GateCheck("measurement", "Measurement calibrated", true, "0.42 m ± 0.03 m.", true),
        )
    }
}

class FakeDispatchRepository : DispatchRepository {
    override fun observeOffers(): Flow<List<Offer>> = flowOf(emptyList())

    override fun observeMarket(): Flow<MarketSnapshot> = flowOf(
        MarketSnapshot(
            nowEpochMs = 0L,
            demandIndex = 0.5,
            supplyIndex = 0.4,
            hotZones = emptyList(),
            daylightLabel = "Dry",
            weatherLabel = "Clear",
        ),
    )

    override fun observeHotZonesZone(): Flow<Int> = flowOf(0)

    override suspend fun refresh(operatorPoint: GeoPoint, atEpochMs: Long): Result<Int> = unused()

    override suspend fun acceptOffer(offerId: String, atEpochMs: Long): Result<Job> = unused()

    override suspend fun declineOffer(offerId: String, reason: String, atEpochMs: Long): Result<Unit> = unused()

    override suspend fun expireOffers(nowEpochMs: Long): Result<Int> = unused()

    private fun <T> unused(): Result<T> =
        Result.failure(UnsupportedOperationException("Not used by the capture tests."))
}

class FakeVaultRepository : VaultRepository {
    override fun observePackets(): Flow<List<EvidencePacket>> = flowOf(emptyList())

    override fun observePacket(packetId: String): Flow<EvidencePacket?> = flowOf(null)

    override suspend fun verifyChain(packetId: String?): Result<ChainVerification> = Result.success(
        ChainVerification(valid = true, entries = 0, brokenAtSeq = null, headHash = "", detail = "Empty chain."),
    )

    override suspend fun exportPacket(packetId: String): Result<PacketExport> = unused()

    override suspend fun appendCorrection(packetId: String, note: String, atEpochMs: Long): Result<EvidencePacket> = unused()

    private fun <T> unused(): Result<T> =
        Result.failure(UnsupportedOperationException("Not used by the capture tests."))
}

class FakeEarningsRepository : EarningsRepository {
    override fun observeBalances(): Flow<Balances> = flowOf(
        Balances(
            availableCents = 0,
            pendingCents = 0,
            paidLifetimeCents = 0,
            instantFeePaidLifetimeCents = 0,
            currentStreakDays = 0,
            weeklyGoalCents = 0,
        ),
    )

    override fun observeSummary(): Flow<EarningsSummary> = flowOf(
        EarningsSummary(
            todayCents = 0,
            weekCents = 0,
            monthCents = 0,
            onlineMinutesToday = 0,
            jobsCompletedWeek = 0,
            milesDrivenWeek = 0.0,
            bonusesWeekCents = 0,
            tipsWeekCents = 0,
            platformFeeWeekCents = 0,
            mileageDeductionCents = 0,
        ),
    )

    override fun observeLedger(): Flow<List<LedgerEntry>> = flowOf(emptyList())

    override fun observePayouts(): Flow<List<PayoutRequest>> = flowOf(emptyList())

    override fun quote(amountCents: Int, mode: PayoutMode): PayoutQuote = PayoutQuote(
        amountCents = amountCents,
        feeCents = 0,
        netCents = amountCents,
        mode = mode,
        etaLabel = "Instant",
        feeDisclosure = "No fee.",
    )

    override suspend fun requestPayout(amountCents: Int, mode: PayoutMode, atEpochMs: Long): Result<PayoutRequest> = unused()

    private fun <T> unused(): Result<T> =
        Result.failure(UnsupportedOperationException("Not used by the capture tests."))
}

class FakeSyncRepository : SyncRepository {
    val syncState = MutableStateFlow(
        SyncState(
            phase = SyncPhase.IDLE,
            queued = 0,
            inFlight = 0,
            deadLetters = 0,
            lastSyncedAtEpochMs = null,
            lastError = null,
        ),
    )

    fun setPhase(phase: SyncPhase, queued: Int = 0) {
        syncState.value = syncState.value.copy(phase = phase, queued = queued)
    }

    override fun observeSyncState(): Flow<SyncState> = syncState

    override suspend fun flushNow(): SyncReport = SyncReport(0, 0, 0, null, "Nothing to do.")
}

internal fun checklistOf(vararg results: ChecklistResult): Map<String, ChecklistResult> =
    results.associateBy { it.key }

internal fun stepResult(
    key: String,
    status: StepResult = StepResult.PASS,
    note: String = "",
    mediaIds: List<String> = emptyList(),
): ChecklistResult = ChecklistResult(key = key, result = status, note = note, mediaIds = mediaIds)

internal fun workWith(
    jobId: String,
    checklist: Map<String, ChecklistResult> = emptyMap(),
    media: List<MediaRef> = emptyList(),
    consent: Consent? = null,
): ActiveWork = ActiveWork(
    jobId = jobId,
    status = JobStatus.IN_PROGRESS,
    acceptedAtEpochMs = 0L,
    checklist = checklist,
    media = media,
    consent = consent,
)
