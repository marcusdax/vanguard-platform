package com.odasi.fieldwright.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.odasi.fieldwright.model.BadgeKey
import com.odasi.fieldwright.model.BadgeState
import com.odasi.fieldwright.model.Credential
import com.odasi.fieldwright.model.EvidenceTier
import com.odasi.fieldwright.model.JobStatus
import com.odasi.fieldwright.model.JobType
import com.odasi.fieldwright.model.LedgerKind
import com.odasi.fieldwright.model.LedgerStatus
import com.odasi.fieldwright.model.MediaKind
import com.odasi.fieldwright.model.MediaSource
import com.odasi.fieldwright.model.OfferStatus
import com.odasi.fieldwright.model.PayoutMode
import com.odasi.fieldwright.model.PayoutStatus
import com.odasi.fieldwright.model.RecordKind
import com.odasi.fieldwright.model.RedactionState
import com.odasi.fieldwright.model.RequesterKind
import com.odasi.fieldwright.model.SealState
import com.odasi.fieldwright.model.StepResult
import com.odasi.fieldwright.model.SyncPhase

/**
 * Room schema v1. One row per concept the UI can observe; child evidence tables hang off
 * [PacketEntity] by packet id. Money and identifiers stay typed integer/string columns;
 * only genuinely opaque value objects (checklist definitions, consent, record payloads,
 * hot zones, proofs) are stored as JSON.
 *
 * Every demo-created row carries `demo = true` so the demo log can never be mistaken for
 * live evidence and can be wiped without touching a real record (CONTRACTS §1.4, §8).
 */

private const val CASCADE = ForeignKey.CASCADE

@Entity(tableName = "jobs", indices = [Index("status"), Index("packetId"), Index("demo")])
data class JobEntity(
    @PrimaryKey val id: String,
    val type: JobType,
    val title: String,
    // requester
    val requesterOrg: String,
    val requesterContactName: String?,
    val requesterKind: RequesterKind,
    val requesterBillingLabel: String,
    // site snapshot
    val siteAddress: String,
    val siteUnit: String?,
    val siteLat: Double,
    val siteLng: Double,
    val sitePointRecordedAtEpochMs: Long,
    val siteAccuracyMeters: Float,
    val siteFixSource: String,
    val geofenceRadiusMeters: Int,
    val accessNotes: String?,
    val planLabel: String,
    // lifecycle and money
    val status: JobStatus,
    val bountyCents: Int,
    val bonusCents: Int,
    val estimatedMinutes: Int,
    val scheduledForEpochMs: Long?,
    val slaMinutes: Int,
    val distanceMeters: Int,
    val etaMinutes: Int,
    val consentScope: String,
    val checklistJson: String,
    val requiredCredentialsCsv: String,
    val demo: Boolean,
    val notes: String,
    val acceptedAtEpochMs: Long?,
    val arrivedAtEpochMs: Long?,
    val startedAtEpochMs: Long?,
    val sealedAtEpochMs: Long?,
    val packetId: String?,
    /** Consent is a value object recorded once per capture session (CONTRACTS §5.1). */
    val consentJson: String? = null,
    val arrivalDistanceMeters: Double? = null,
    val arrivalAccuracyMeters: Float? = null,
    val arrivalOverrideReason: String? = null,
    val updatedAtEpochMs: Long,
)

@Entity(tableName = "offers", indices = [Index("jobId"), Index("status"), Index("expiresAtEpochMs"), Index("demo")])
data class OfferEntity(
    @PrimaryKey val id: String,
    val jobId: String,
    val operatorId: String,
    val payoutCents: Int,
    val surgeMultiplier: Double,
    val offeredAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    val score: Double,
    val scoreReasonsJson: String,
    val etaMinutes: Int,
    val autoAcceptEligible: Boolean,
    val status: OfferStatus,
    val demo: Boolean,
    val rankIndex: Int,
)

/** A packet with its append-only children. */
@Entity(tableName = "packets", indices = [Index("jobId", unique = true), Index("sealedAtEpochMs"), Index("demo")])
data class PacketEntity(
    @PrimaryKey val id: String,
    val jobId: String,
    val jobType: JobType,
    val operatorId: String,
    val title: String,
    val requesterLabel: String,
    // site snapshot (packets outlive any later site edit)
    val siteAddress: String,
    val siteUnit: String?,
    val siteLat: Double,
    val siteLng: Double,
    val sitePointRecordedAtEpochMs: Long,
    val siteAccuracyMeters: Float,
    val siteFixSource: String,
    val geofenceRadiusMeters: Int,
    val accessNotes: String?,
    val planLabel: String,
    val consentJson: String?,
    val gatesJson: String,
    val tier: EvidenceTier,
    val seal: SealState,
    val notes: String,
    val demo: Boolean,
    val createdAtEpochMs: Long,
    val sealedAtEpochMs: Long?,
    val asOfEpochMs: Long,
    val merkleLeaf: String?,
    val merkleProofJson: String,
    val merkleTreeSize: Int?,
    val merkleRoot: String?,
    /** Leaf position in the device log at seal time; needed to re-verify the stored proof. */
    val merkleIndex: Int?,
    /**
     * Chain head at the moment the seal was earned. Corrections appended later move the
     * live head, but the anchor and its Merkle proof always refer to this sealed head.
     */
    val sealedHeadHash: String?,
    val signatureBase64: String?,
)

@Entity(
    tableName = "packet_records",
    primaryKeys = ["packetId", "seq"],
    foreignKeys = [ForeignKey(entity = PacketEntity::class, parentColumns = ["id"], childColumns = ["packetId"], onDelete = CASCADE)],
)
data class ChainRecordEntity(
    val packetId: String,
    val seq: Int,
    val kind: RecordKind,
    val atEpochMs: Long,
    val actorId: String,
    val summary: String,
    val payloadJson: String,
    val prevHash: String,
    val hash: String,
    val signatureBase64: String?,
    val signerKeyId: String?,
)

@Entity(
    tableName = "badges",
    primaryKeys = ["packetId", "badgeKey"],
    foreignKeys = [ForeignKey(entity = PacketEntity::class, parentColumns = ["id"], childColumns = ["packetId"], onDelete = CASCADE)],
)
data class BadgeEntity(
    val packetId: String,
    val badgeKey: BadgeKey,
    val state: BadgeState,
    val required: Boolean,
    val rationale: String,
    val evidenceRefsJson: String,
)

/**
 * Media and measurements are recorded during capture (job-scoped) and are adopted by the
 * packet at seal time (`packetId` becomes non-null). Keeping one row avoids duplicating
 * evidence when a capture is resumed.
 */
@Entity(tableName = "media", indices = [Index("jobId"), Index("packetId")])
data class MediaEntity(
    @PrimaryKey val id: String,
    val jobId: String,
    val packetId: String?,
    val kind: MediaKind,
    val sha256: String,
    val bytes: Long,
    val capturedAtEpochMs: Long,
    val width: Int,
    val height: Int,
    val redaction: RedactionState,
    val source: MediaSource,
    val note: String,
)

@Entity(tableName = "measurements", indices = [Index("jobId"), Index("packetId")])
data class MeasurementEntity(
    @PrimaryKey val id: String,
    val jobId: String,
    val packetId: String?,
    val label: String,
    val meters: Double,
    val referenceLabel: String,
    val referenceMeters: Double,
    val pixelSpan: Double,
    val calibratedAtEpochMs: Long,
    val source: MediaSource,
)

@Entity(tableName = "checklist", primaryKeys = ["jobId", "stepKey"], indices = [Index("packetId")])
data class ChecklistRowEntity(
    val jobId: String,
    val stepKey: String,
    val result: StepResult,
    val note: String,
    val mediaIdsJson: String,
    val packetId: String?,
    val recordedAtEpochMs: Long,
)

@Entity(tableName = "ledger", indices = [Index(value = ["idempotencyKey"], unique = true), Index("status"), Index("atEpochMs"), Index("demo")])
data class LedgerEntity(
    @PrimaryKey val id: String,
    val jobId: String?,
    val packetId: String?,
    val kind: LedgerKind,
    /** Signed cents: earnings positive, fees and reversals negative. */
    val amountCents: Int,
    val status: LedgerStatus,
    val atEpochMs: Long,
    val description: String,
    val idempotencyKey: String,
    val demo: Boolean,
)

@Entity(tableName = "payouts", indices = [Index(value = ["idempotencyKey"], unique = true), Index("status"), Index("demo")])
data class PayoutEntity(
    @PrimaryKey val id: String,
    val amountCents: Int,
    val feeCents: Int,
    val netCents: Int,
    val mode: PayoutMode,
    val status: PayoutStatus,
    val requestedAtEpochMs: Long,
    val completedAtEpochMs: Long?,
    val destinationLast4: String,
    val idempotencyKey: String,
    val failureReason: String?,
    val demo: Boolean,
)

@Entity(tableName = "operator")
data class OperatorEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val initials: String,
    val credentialTier: Int,
    val ratingAvg: Double,
    val acceptanceRate: Double,
    val onTimeRate: Double,
    val completedJobs: Int,
    val streakDays: Int,
    val credentialsCsv: String,
    val payoutAccountLast4: String,
    val weeklyGoalCents: Int,
    val memberSinceLabel: String,
    val demo: Boolean,
)

@Entity(tableName = "availability")
data class AvailabilityEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val isOnline: Boolean,
    val updatedAtEpochMs: Long,
    val radiusMeters: Int,
    val autoAcceptEnabled: Boolean,
    val autoAcceptMinPayoutCents: Int,
    val autoAcceptMaxDistanceMeters: Int,
    val autoAcceptMaxActiveStack: Int,
    val autoAcceptAllowedTypesCsv: String,
    val autoAcceptRequireCredentialMatch: Boolean,
    val areaLabel: String,
    /** Device-local telemetry used by the earnings summary; not part of the market contract. */
    val onlineMinutesToday: Int,
    val onlineSinceEpochMs: Long?,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}

@Entity(tableName = "market")
data class MarketEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val nowEpochMs: Long,
    val demandIndex: Double,
    val supplyIndex: Double,
    val daylightLabel: String,
    val weatherLabel: String,
    val hotZonesJson: String,
    val tickIndex: Long,
    val updatedAtEpochMs: Long,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}

/** A queued, idempotent server mutation. One row per logical event; retries reuse the key. */
enum class OutboxKind { PACKET_SEALED, PACKET_CORRECTION, JOB_STATUS, OFFER, PAYOUT, LEDGER, AVAILABILITY, ANCHOR }

enum class OutboxStatus { QUEUED, IN_FLIGHT, SENT, DEAD_LETTER }

@Entity(
    tableName = "outbox",
    indices = [
        Index(value = ["idempotencyKey"], unique = true),
        Index("status"),
        Index("nextAttemptAtEpochMs"),
        Index("demo"),
    ],
)
data class OutboxEntity(
    @PrimaryKey val id: String,
    val idempotencyKey: String,
    val kind: OutboxKind,
    val method: String,
    val path: String,
    val payloadJson: String,
    val createdAtEpochMs: Long,
    val attemptCount: Int,
    val nextAttemptAtEpochMs: Long,
    val status: OutboxStatus,
    val lastError: String?,
    val demo: Boolean,
)

@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val phase: SyncPhase,
    val lastSyncedAtEpochMs: Long?,
    val lastError: String?,
    val updatedAtEpochMs: Long,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}

/** Relation holders for multi-table reads. */
data class OfferWithJob(
    @androidx.room.Embedded val offer: OfferEntity,
    @androidx.room.Relation(parentColumn = "jobId", entityColumn = "id") val job: JobEntity?,
)

data class PacketAggregate(
    @androidx.room.Embedded val packet: PacketEntity,
    @androidx.room.Relation(parentColumn = "id", entityColumn = "packetId") val records: List<ChainRecordEntity>,
    @androidx.room.Relation(parentColumn = "id", entityColumn = "packetId") val badges: List<BadgeEntity>,
    @androidx.room.Relation(parentColumn = "id", entityColumn = "packetId") val media: List<MediaEntity>,
    @androidx.room.Relation(parentColumn = "id", entityColumn = "packetId") val measurements: List<MeasurementEntity>,
    @androidx.room.Relation(parentColumn = "id", entityColumn = "packetId") val checklist: List<ChecklistRowEntity>,
)

/** CSV helpers for compact typed sets. Values are enum names (ASCII, comma-free). */
internal fun Set<Credential>.toCsv(): String = sortedBy { it.name }.joinToString(",") { it.name }

internal fun credentialsFromCsv(csv: String): Set<Credential> =
    csv.split(',').mapNotNull { token -> Credential.entries.firstOrNull { it.name == token.trim() } }.toSet()

internal fun Set<JobType>.toJobTypeCsv(): String = sortedBy { it.name }.joinToString(",") { it.name }

internal fun jobTypesFromCsv(csv: String): Set<JobType> =
    csv.split(',').mapNotNull { token -> JobType.entries.firstOrNull { it.name == token.trim() } }.toSet()
