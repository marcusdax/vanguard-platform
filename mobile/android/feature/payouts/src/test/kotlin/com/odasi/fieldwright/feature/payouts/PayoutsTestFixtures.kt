package com.odasi.fieldwright.feature.payouts

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
import com.odasi.fieldwright.model.Consent
import com.odasi.fieldwright.model.EarningsSummary
import com.odasi.fieldwright.model.EvidencePacket
import com.odasi.fieldwright.model.FeeEngine
import com.odasi.fieldwright.model.FeeSchedule
import com.odasi.fieldwright.model.GateCheck
import com.odasi.fieldwright.model.GeoPoint
import com.odasi.fieldwright.model.Job
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
import com.odasi.fieldwright.model.PayoutStatus
import com.odasi.fieldwright.model.StepResult
import com.odasi.fieldwright.model.SyncPhase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

internal const val TEST_NOW_MS: Long = 1_763_251_200_000L
internal const val ONE_DAY_MS: Long = 86_400_000L
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

internal fun balancesFixture(
    availableCents: Int = 20_000,
    pendingCents: Int = 0,
    paidLifetimeCents: Int = 0,
    weeklyGoalCents: Int = 30_000,
): Balances = Balances(
    availableCents = availableCents,
    pendingCents = pendingCents,
    paidLifetimeCents = paidLifetimeCents,
    instantFeePaidLifetimeCents = 0,
    currentStreakDays = 4,
    weeklyGoalCents = weeklyGoalCents,
)

internal fun summaryFixture(
    todayCents: Int = 9_500,
    weekCents: Int = 18_642,
    monthCents: Int = 74_300,
): EarningsSummary = EarningsSummary(
    todayCents = todayCents,
    weekCents = weekCents,
    monthCents = monthCents,
    onlineMinutesToday = 212,
    jobsCompletedWeek = 5,
    milesDrivenWeek = 61.4,
    bonusesWeekCents = 1_500,
    tipsWeekCents = 800,
    platformFeeWeekCents = 3_291,
    mileageDeductionCents = 4_298,
)

internal fun ledgerEntry(
    id: String,
    kind: LedgerKind,
    amountCents: Int,
    status: LedgerStatus,
    atEpochMs: Long,
    description: String = "Ledger line",
    demo: Boolean = true,
): LedgerEntry = LedgerEntry(
    id = id,
    jobId = "job-1",
    packetId = "FW-0994",
    kind = kind,
    amountCents = amountCents,
    status = status,
    atEpochMs = atEpochMs,
    description = description,
    idempotencyKey = "idem-$id",
    demo = demo,
)

internal fun payoutRequest(
    id: String,
    status: PayoutStatus,
    mode: PayoutMode = PayoutMode.INSTANT,
    amountCents: Int = 42_000,
    feeCents: Int = 315,
    requestedAtEpochMs: Long = TEST_NOW_MS - ONE_DAY_MS,
    failureReason: String? = null,
): PayoutRequest = PayoutRequest(
    id = id,
    amountCents = amountCents,
    feeCents = feeCents,
    netCents = amountCents - feeCents,
    mode = mode,
    status = status,
    requestedAtEpochMs = requestedAtEpochMs,
    completedAtEpochMs = if (status == PayoutStatus.PAID) requestedAtEpochMs + 60_000 else null,
    destinationLast4 = "4242",
    idempotencyKey = "idem-$id",
    failureReason = failureReason,
)

internal class FakeFieldwrightForPayouts(
    balances: Balances = balancesFixture(),
    summary: EarningsSummary = summaryFixture(),
    ledger: List<LedgerEntry> = emptyList(),
    payouts: List<PayoutRequest> = emptyList(),
    syncState: SyncState = SyncState(SyncPhase.IDLE, 0, 0, 0, TEST_NOW_MS, null),
) : FieldwrightRepositories {

    val earningsRepo = FakeEarningsRepository(balances, summary, ledger, payouts)
    val syncFlow = MutableStateFlow(syncState)

    override val operator: OperatorRepository = object : OperatorRepository {
        override fun observeOperator(): Flow<Operator> = MutableStateFlow(operatorFixture())
        override fun observeAvailability(): Flow<Availability> = MutableStateFlow(Availability())
        override suspend fun setOnline(online: Boolean, atEpochMs: Long): Result<Availability> = unsupported()
        override suspend fun updateRadius(radiusMeters: Int): Result<Availability> = unsupported()
        override suspend fun updateAutoAccept(policy: AutoAcceptPolicy): Result<Availability> = unsupported()
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
        override suspend fun checkArrival(jobId: String, fix: GeoPoint, atEpochMs: Long): Result<ArrivalCheck> = unsupported()
        override suspend fun confirmArrival(
            jobId: String,
            fix: GeoPoint,
            overrideReason: String?,
            atEpochMs: Long,
        ): Result<Job> = unsupported()
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

    override val vault: VaultRepository = object : VaultRepository {
        override fun observePackets(): Flow<List<EvidencePacket>> = MutableStateFlow(emptyList())
        override fun observePacket(packetId: String): Flow<EvidencePacket?> = MutableStateFlow(null)
        override suspend fun verifyChain(packetId: String?): Result<ChainVerification> = unsupported()
        override suspend fun exportPacket(packetId: String): Result<PacketExport> = unsupported()
        override suspend fun appendCorrection(packetId: String, note: String, atEpochMs: Long): Result<EvidencePacket> =
            unsupported()
    }

    override val earnings: EarningsRepository get() = earningsRepo

    override val sync: SyncRepository = object : SyncRepository {
        override fun observeSyncState(): Flow<SyncState> = syncFlow
        override suspend fun flushNow(): SyncReport = SyncReport(0, 0, 0, null, "no-op")
    }
}

internal class FakeEarningsRepository(
    balances: Balances,
    summary: EarningsSummary,
    ledger: List<LedgerEntry>,
    payouts: List<PayoutRequest>,
) : EarningsRepository {

    val balancesFlow = MutableStateFlow(balances)
    val summaryFlow = MutableStateFlow(summary)
    val ledgerFlow = MutableStateFlow(ledger)
    val payoutsFlow = MutableStateFlow(payouts)
    val requestCalls = mutableListOf<RequestCall>()
    var requestResult: (Int, PayoutMode) -> Result<PayoutRequest> = { amount, mode ->
        Result.success(
            PayoutRequest(
                id = "payout-${requestCalls.size}",
                amountCents = amount,
                feeCents = 0,
                netCents = amount,
                mode = mode,
                status = PayoutStatus.REQUESTED,
                requestedAtEpochMs = TEST_NOW_MS,
                completedAtEpochMs = null,
                destinationLast4 = "4242",
                idempotencyKey = "idem-request-${requestCalls.size}",
            ),
        )
    }

    override fun observeBalances(): Flow<Balances> = balancesFlow
    override fun observeSummary(): Flow<EarningsSummary> = summaryFlow
    override fun observeLedger(): Flow<List<LedgerEntry>> = ledgerFlow
    override fun observePayouts(): Flow<List<PayoutRequest>> = payoutsFlow

    override fun quote(amountCents: Int, mode: PayoutMode): PayoutQuote =
        FeeEngine.quote(amountCents, mode, FeeSchedule())

    override suspend fun requestPayout(amountCents: Int, mode: PayoutMode, atEpochMs: Long): Result<PayoutRequest> {
        requestCalls += RequestCall(amountCents, mode, atEpochMs)
        return requestResult(amountCents, mode)
    }

    data class RequestCall(val amountCents: Int, val mode: PayoutMode, val atEpochMs: Long)
}

private fun <T> unsupported(): Result<T> =
    Result.failure(UnsupportedOperationException("not used by the Payouts tests"))
