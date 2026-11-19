package com.odasi.fieldwright.data

import com.odasi.fieldwright.model.ActiveWork
import com.odasi.fieldwright.model.Availability
import com.odasi.fieldwright.model.AutoAcceptPolicy
import com.odasi.fieldwright.model.Badge
import com.odasi.fieldwright.model.Balances
import com.odasi.fieldwright.model.ChainVerification
import com.odasi.fieldwright.model.Consent
import com.odasi.fieldwright.model.EarningsSummary
import com.odasi.fieldwright.model.EvidencePacket
import com.odasi.fieldwright.model.GateCheck
import com.odasi.fieldwright.model.GeoPoint
import com.odasi.fieldwright.model.Job
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
import com.odasi.fieldwright.model.SealEvaluation
import com.odasi.fieldwright.model.StepResult
import com.odasi.fieldwright.model.SyncPhase
import kotlinx.coroutines.flow.Flow

/**
 * Repository contracts between the data layer and the feature modules.
 *
 * **Implemented by the data-sync workstream.** The UI depends on these interfaces only,
 * never on Room, WorkManager, or HTTP types. All suspension points return [Result] so a
 * screen must render failure, and every write is idempotent at the repository boundary.
 */

data class ArrivalCheck(
    val accepted: Boolean,
    val insideGeofence: Boolean,
    val distanceMeters: Double,
    val accuracyMeters: Float,
    val detail: String,
    val overrideRequired: Boolean,
)

data class SealOutcome(
    val packet: EvidencePacket,
    val evaluation: SealEvaluation,
    val verification: ChainVerification,
    val badges: List<Badge>,
    /** True when the packet's Merkle inclusion proof verified against the device log. */
    val merkleIncluded: Boolean = false,
)

data class SyncState(
    val phase: SyncPhase,
    val queued: Int,
    val inFlight: Int,
    val deadLetters: Int,
    val lastSyncedAtEpochMs: Long?,
    val lastError: String?,
)

data class SyncReport(
    val attempted: Int,
    val succeeded: Int,
    val failed: Int,
    val nextRetryAtEpochMs: Long?,
    val detail: String,
)

interface OperatorRepository {
    fun observeOperator(): Flow<Operator>
    fun observeAvailability(): Flow<Availability>
    suspend fun setOnline(online: Boolean, atEpochMs: Long): Result<Availability>
    suspend fun updateRadius(radiusMeters: Int): Result<Availability>
    suspend fun updateAutoAccept(policy: AutoAcceptPolicy): Result<Availability>
}

interface DispatchRepository {
    fun observeOffers(): Flow<List<Offer>>
    fun observeMarket(): Flow<MarketSnapshot>
    fun observeHotZonesZone(): Flow<Int>
    /** Pulls fresh job supply and re-ranks for the current operator fix. */
    suspend fun refresh(operatorPoint: GeoPoint, atEpochMs: Long): Result<Int>
    suspend fun acceptOffer(offerId: String, atEpochMs: Long): Result<Job>
    suspend fun declineOffer(offerId: String, reason: String, atEpochMs: Long): Result<Unit>
    suspend fun expireOffers(nowEpochMs: Long): Result<Int>
}

interface JobRepository {
    fun observeActiveJobs(): Flow<List<Job>>
    fun observeCompletedJobs(): Flow<List<Job>>
    fun observeJob(jobId: String): Flow<Job?>
    suspend fun markEnRoute(jobId: String, atEpochMs: Long): Result<Job>
    suspend fun checkArrival(jobId: String, fix: GeoPoint, atEpochMs: Long): Result<ArrivalCheck>
    suspend fun confirmArrival(jobId: String, fix: GeoPoint, overrideReason: String?, atEpochMs: Long): Result<Job>
    suspend fun startCapture(jobId: String, atEpochMs: Long): Result<Job>
    suspend fun markCompletedLocally(jobId: String, atEpochMs: Long): Result<Job>
}

interface CaptureRepository {
    fun observeWork(jobId: String): Flow<ActiveWork?>
    suspend fun begin(jobId: String, atEpochMs: Long): Result<ActiveWork>
    suspend fun addMedia(jobId: String, media: MediaRef, atEpochMs: Long): Result<ActiveWork>
    suspend fun addMeasurement(jobId: String, measurement: Measurement): Result<ActiveWork>
    suspend fun setConsent(jobId: String, consent: Consent): Result<ActiveWork>
    suspend fun completeStep(
        jobId: String,
        stepKey: String,
        result: StepResult,
        note: String,
        mediaIds: List<String>,
    ): Result<ActiveWork>
    /** Runs the blocking capture gates (scope, redaction, quality, geofence, calibration). */
    suspend fun runGates(jobId: String, nowEpochMs: Long): Result<List<GateCheck>>
    /**
     * Appends the seal record, signs the packet hash, stores the packet, moves the job to
     * SEALED, and enqueues the outbox upload. Idempotent per (jobId, recordCount).
     */
    suspend fun seal(jobId: String, nowEpochMs: Long): Result<SealOutcome>
}

interface VaultRepository {
    fun observePackets(): Flow<List<EvidencePacket>>
    fun observePacket(packetId: String): Flow<EvidencePacket?>
    suspend fun verifyChain(packetId: String?): Result<ChainVerification>
    suspend fun exportPacket(packetId: String): Result<PacketExport>
    suspend fun appendCorrection(packetId: String, note: String, atEpochMs: Long): Result<EvidencePacket>
}

interface EarningsRepository {
    fun observeBalances(): Flow<Balances>
    fun observeSummary(): Flow<EarningsSummary>
    fun observeLedger(): Flow<List<LedgerEntry>>
    fun observePayouts(): Flow<List<PayoutRequest>>
    fun quote(amountCents: Int, mode: PayoutMode): PayoutQuote
    suspend fun requestPayout(amountCents: Int, mode: PayoutMode, atEpochMs: Long): Result<PayoutRequest>
}

interface SyncRepository {
    fun observeSyncState(): Flow<SyncState>
    suspend fun flushNow(): SyncReport
}

/** One container so the app has a single composition root and tests can swap it wholesale. */
interface FieldwrightRepositories {
    val operator: OperatorRepository
    val dispatch: DispatchRepository
    val jobs: JobRepository
    val capture: CaptureRepository
    val vault: VaultRepository
    val earnings: EarningsRepository
    val sync: SyncRepository
}

/** Device fixes are supplied by the app layer; the data layer never talks to LocationManager. */
interface LocationProvider {
    fun currentFix(): GeoPoint?
    fun observeFix(): Flow<GeoPoint>
}
