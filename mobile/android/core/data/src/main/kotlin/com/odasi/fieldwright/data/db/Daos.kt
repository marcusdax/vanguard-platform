package com.odasi.fieldwright.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.odasi.fieldwright.model.JobStatus
import com.odasi.fieldwright.model.LedgerStatus
import com.odasi.fieldwright.model.OfferStatus
import com.odasi.fieldwright.model.PayoutStatus
import com.odasi.fieldwright.model.SyncPhase
import kotlinx.coroutines.flow.Flow

/**
 * DAO layer. Repositories own the model mapping and the multi-row transactions; DAOs stay
 * mechanical. UI never touches these — it observes [com.odasi.fieldwright.data.Contracts] flows only.
 */
private const val ACTIVE_STATUSES =
    "'ACCEPTED','EN_ROUTE','ON_SITE','IN_PROGRESS','PACKET_READY','SEALED','SUBMITTED'"

@Dao
interface JobDao {
    @Query("SELECT * FROM jobs WHERE status IN ($ACTIVE_STATUSES) ORDER BY COALESCE(acceptedAtEpochMs, scheduledForEpochMs, 0) ASC")
    fun observeActive(): Flow<List<JobEntity>>

    @Query("SELECT * FROM jobs WHERE status IN ('APPROVED','PAID','DISPUTED','CANCELLED','EXPIRED') ORDER BY COALESCE(sealedAtEpochMs, updatedAtEpochMs) DESC")
    fun observeCompleted(): Flow<List<JobEntity>>

    @Query("SELECT * FROM jobs WHERE id = :jobId")
    fun observeJob(jobId: String): Flow<JobEntity?>

    @Query("SELECT * FROM jobs WHERE id = :jobId")
    suspend fun get(jobId: String): JobEntity?

    @Query("SELECT * FROM jobs WHERE status = 'AVAILABLE' ORDER BY COALESCE(scheduledForEpochMs, 0) ASC")
    suspend fun availableJobs(): List<JobEntity>

    @Query("SELECT * FROM jobs")
    suspend fun allJobs(): List<JobEntity>

    @Query("SELECT * FROM jobs WHERE acceptedAtEpochMs >= :sinceEpochMs")
    suspend fun jobsAcceptedSince(sinceEpochMs: Long): List<JobEntity>

    @Query("SELECT COUNT(*) FROM jobs WHERE status IN ($ACTIVE_STATUSES)")
    suspend fun activeCount(): Int

    @Query("SELECT COUNT(*) FROM jobs")
    suspend fun count(): Int

    @Upsert
    suspend fun upsert(job: JobEntity)

    @Upsert
    suspend fun upsertAll(jobs: List<JobEntity>)

    @Query("UPDATE jobs SET status = :status, updatedAtEpochMs = :atEpochMs WHERE id = :jobId")
    suspend fun updateStatus(jobId: String, status: JobStatus, atEpochMs: Long)

    @Query("DELETE FROM jobs")
    suspend fun deleteAll()

    @Query("DELETE FROM jobs WHERE demo = 1")
    suspend fun deleteDemo()
}

@Dao
interface OfferDao {
    @Transaction
    @Query("SELECT * FROM offers WHERE status = 'PENDING' ORDER BY rankIndex ASC, offeredAtEpochMs DESC")
    fun observePending(): Flow<List<OfferWithJob>>

    @Transaction
    @Query("SELECT * FROM offers WHERE status = 'PENDING' ORDER BY rankIndex ASC")
    suspend fun pending(): List<OfferWithJob>

    @Transaction
    @Query("SELECT * FROM offers WHERE id = :offerId")
    suspend fun get(offerId: String): OfferWithJob?

    @Transaction
    @Query("SELECT * FROM offers WHERE jobId = :jobId AND status = 'PENDING'")
    suspend fun pendingForJob(jobId: String): List<OfferWithJob>

    @Query("SELECT * FROM offers WHERE status = 'PENDING' AND expiresAtEpochMs <= :nowEpochMs")
    suspend fun expiredPending(nowEpochMs: Long): List<OfferEntity>

    @Query("SELECT * FROM offers")
    suspend fun allOffers(): List<OfferEntity>

    @Upsert
    suspend fun upsert(offer: OfferEntity)

    @Upsert
    suspend fun upsertAll(offers: List<OfferEntity>)

    @Query("UPDATE offers SET status = :status WHERE id = :offerId")
    suspend fun updateStatus(offerId: String, status: OfferStatus)

    @Query("UPDATE offers SET status = 'WITHDRAWN' WHERE jobId = :jobId AND status = 'PENDING' AND id != :keepOfferId")
    suspend fun withdrawOtherOffersForJob(jobId: String, keepOfferId: String)

    @Query("DELETE FROM offers")
    suspend fun deleteAll()

    @Query("DELETE FROM offers WHERE demo = 1")
    suspend fun deleteDemo()
}

@Dao
interface PacketDao {
    @Transaction
    @Query("SELECT * FROM packets ORDER BY COALESCE(sealedAtEpochMs, createdAtEpochMs) DESC")
    fun observeAll(): Flow<List<PacketAggregate>>

    @Transaction
    @Query("SELECT * FROM packets WHERE id = :packetId")
    fun observeOne(packetId: String): Flow<PacketAggregate?>

    @Transaction
    @Query("SELECT * FROM packets WHERE id = :packetId")
    suspend fun get(packetId: String): PacketAggregate?

    @Transaction
    @Query("SELECT * FROM packets WHERE jobId = :jobId")
    suspend fun getForJob(jobId: String): PacketAggregate?

    @Transaction
    @Query("SELECT * FROM packets")
    suspend fun all(): List<PacketAggregate>

    /** Heads in append order; the device log (leaf hashes) is derived from this list. */
    @Query("SELECT id FROM packets WHERE seal = 'EARNED' ORDER BY COALESCE(sealedAtEpochMs, createdAtEpochMs) ASC")
    suspend fun sealedPacketIdsInLogOrder(): List<String>

    @Query("SELECT COUNT(*) FROM packets")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPacket(packet: PacketEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRecords(records: List<ChainRecordEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBadges(badges: List<BadgeEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRecord(record: ChainRecordEntity)

    @Query("UPDATE packets SET signatureBase64 = :signatureBase64 WHERE id = :packetId")
    suspend fun updateSignature(packetId: String, signatureBase64: String?)

    @Query("DELETE FROM packets")
    suspend fun deleteAll()

    @Query("DELETE FROM packets WHERE id = :packetId")
    suspend fun deleteById(packetId: String)

    @Query("DELETE FROM packets WHERE demo = 1")
    suspend fun deleteDemo()
}

@Dao
interface CaptureDao {
    @Query("SELECT * FROM media WHERE jobId = :jobId ORDER BY capturedAtEpochMs ASC")
    fun observeMedia(jobId: String): Flow<List<MediaEntity>>

    @Query("SELECT * FROM media WHERE jobId = :jobId ORDER BY capturedAtEpochMs ASC")
    suspend fun mediaForJob(jobId: String): List<MediaEntity>

    @Query("SELECT * FROM checklist WHERE jobId = :jobId")
    fun observeChecklist(jobId: String): Flow<List<ChecklistRowEntity>>

    @Query("SELECT * FROM checklist WHERE jobId = :jobId")
    suspend fun checklistForJob(jobId: String): List<ChecklistRowEntity>

    @Query("SELECT * FROM checklist WHERE packetId = :packetId")
    suspend fun checklistForPacket(packetId: String): List<ChecklistRowEntity>

    @Query("SELECT * FROM measurements WHERE jobId = :jobId ORDER BY calibratedAtEpochMs ASC")
    fun observeMeasurements(jobId: String): Flow<List<MeasurementEntity>>

    @Query("SELECT * FROM measurements WHERE jobId = :jobId ORDER BY calibratedAtEpochMs ASC")
    suspend fun measurementsForJob(jobId: String): List<MeasurementEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMedia(media: MediaEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMeasurement(measurement: MeasurementEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChecklist(row: ChecklistRowEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChecklistAll(rows: List<ChecklistRowEntity>)

    @Query("UPDATE media SET packetId = :packetId WHERE jobId = :jobId")
    suspend fun adoptMedia(jobId: String, packetId: String)

    @Query("UPDATE measurements SET packetId = :packetId WHERE jobId = :jobId")
    suspend fun adoptMeasurements(jobId: String, packetId: String)

    @Query("UPDATE checklist SET packetId = :packetId WHERE jobId = :jobId")
    suspend fun adoptChecklist(jobId: String, packetId: String)

    @Query("DELETE FROM media")
    suspend fun deleteAllMedia()

    @Query("DELETE FROM measurements")
    suspend fun deleteAllMeasurements()

    @Query("DELETE FROM checklist")
    suspend fun deleteAllChecklist()
}

@Dao
interface MoneyDao {
    @Query("SELECT * FROM ledger ORDER BY atEpochMs DESC")
    fun observeLedger(): Flow<List<LedgerEntity>>

    @Query("SELECT * FROM ledger ORDER BY atEpochMs DESC")
    suspend fun ledger(): List<LedgerEntity>

    @Query("SELECT * FROM ledger WHERE atEpochMs >= :sinceEpochMs ORDER BY atEpochMs ASC")
    suspend fun ledgerSince(sinceEpochMs: Long): List<LedgerEntity>

    @Query("SELECT * FROM ledger WHERE idempotencyKey = :key")
    suspend fun ledgerByKey(key: String): LedgerEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLedger(entry: LedgerEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLedgerAll(entries: List<LedgerEntity>): List<Long>

    @Query("UPDATE ledger SET status = :status WHERE id = :id")
    suspend fun updateLedgerStatus(id: String, status: LedgerStatus)

    @Query("SELECT * FROM payouts ORDER BY requestedAtEpochMs DESC")
    fun observePayouts(): Flow<List<PayoutEntity>>

    @Query("SELECT * FROM payouts ORDER BY requestedAtEpochMs DESC")
    suspend fun payouts(): List<PayoutEntity>

    @Query("SELECT * FROM payouts WHERE status IN ('REQUESTED','PROCESSING')")
    suspend fun openPayouts(): List<PayoutEntity>

    @Query("SELECT * FROM payouts WHERE idempotencyKey = :key")
    suspend fun payoutByKey(key: String): PayoutEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPayout(payout: PayoutEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPayouts(payouts: List<PayoutEntity>): List<Long>

    @Query("UPDATE payouts SET status = :status, completedAtEpochMs = :completedAtEpochMs, failureReason = :failureReason WHERE id = :id")
    suspend fun updatePayoutStatus(id: String, status: PayoutStatus, completedAtEpochMs: Long?, failureReason: String?)

    @Query("DELETE FROM ledger")
    suspend fun deleteAllLedger()

    @Query("DELETE FROM ledger WHERE demo = 1")
    suspend fun deleteDemoLedger()

    @Query("DELETE FROM payouts")
    suspend fun deleteAllPayouts()

    @Query("DELETE FROM payouts WHERE demo = 1")
    suspend fun deleteDemoPayouts()
}

@Dao
interface OperatorDao {
    @Query("SELECT * FROM operator LIMIT 1")
    fun observeOperator(): Flow<OperatorEntity?>

    @Query("SELECT * FROM operator LIMIT 1")
    suspend fun getOperator(): OperatorEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOperator(operator: OperatorEntity)

    @Query("SELECT * FROM availability WHERE id = 1")
    fun observeAvailability(): Flow<AvailabilityEntity?>

    @Query("SELECT * FROM availability WHERE id = 1")
    suspend fun getAvailability(): AvailabilityEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAvailability(availability: AvailabilityEntity)

    @Query("DELETE FROM operator")
    suspend fun deleteOperator()

    @Query("DELETE FROM availability")
    suspend fun deleteAvailability()
}

@Dao
interface MarketDao {
    @Query("SELECT * FROM market WHERE id = 1")
    fun observeMarket(): Flow<MarketEntity?>

    @Query("SELECT * FROM market WHERE id = 1")
    suspend fun getMarket(): MarketEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMarket(market: MarketEntity)

    @Query("DELETE FROM market")
    suspend fun deleteMarket()
}

@Dao
interface OutboxDao {
    @Query(
        "SELECT * FROM outbox WHERE status IN ('QUEUED','IN_FLIGHT') AND nextAttemptAtEpochMs <= :nowEpochMs " +
            "ORDER BY createdAtEpochMs ASC LIMIT :limit",
    )
    suspend fun dueEvents(nowEpochMs: Long, limit: Int): List<OutboxEntity>

    @Query("SELECT * FROM outbox WHERE status = :status ORDER BY createdAtEpochMs ASC")
    suspend fun eventsByStatus(status: OutboxStatus): List<OutboxEntity>

    @Query("SELECT * FROM outbox WHERE idempotencyKey = :key")
    suspend fun eventByKey(key: String): OutboxEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: OutboxEntity): Long

    @Query("UPDATE outbox SET status = :status, attemptCount = :attemptCount, nextAttemptAtEpochMs = :nextAttemptAtEpochMs, lastError = :lastError WHERE id = :id")
    suspend fun updateState(id: String, status: OutboxStatus, attemptCount: Int, nextAttemptAtEpochMs: Long, lastError: String?)

    @Query("UPDATE outbox SET status = 'SENT', lastError = NULL, attemptCount = attemptCount + 1 WHERE id = :id")
    suspend fun markSent(id: String)

    @Query("UPDATE outbox SET status = 'QUEUED', attemptCount = 0, nextAttemptAtEpochMs = :nowEpochMs, lastError = NULL WHERE status = 'DEAD_LETTER'")
    suspend fun reviveDeadLetters(nowEpochMs: Long): Int

    @Query("SELECT COUNT(*) FROM outbox WHERE status = 'QUEUED'")
    fun observeQueuedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM outbox WHERE status = 'IN_FLIGHT'")
    fun observeInFlightCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM outbox WHERE status = 'DEAD_LETTER'")
    fun observeDeadLetterCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM outbox WHERE status = 'QUEUED'")
    suspend fun queuedCount(): Int

    @Query("SELECT COUNT(*) FROM outbox WHERE status = 'IN_FLIGHT'")
    suspend fun inFlightCount(): Int

    @Query("SELECT COUNT(*) FROM outbox WHERE status = 'DEAD_LETTER'")
    suspend fun deadLetterCount(): Int

    @Query("SELECT COUNT(*) FROM outbox")
    suspend fun count(): Int

    @Query("DELETE FROM outbox WHERE status = 'SENT' AND createdAtEpochMs < :beforeEpochMs")
    suspend fun pruneSent(beforeEpochMs: Long)

    @Query("DELETE FROM outbox")
    suspend fun deleteAll()

    @Query("SELECT * FROM sync_state WHERE id = 1")
    fun observeSyncState(): Flow<SyncStateEntity?>

    @Query("SELECT * FROM sync_state WHERE id = 1")
    suspend fun getSyncState(): SyncStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSyncState(state: SyncStateEntity)

    @Query("DELETE FROM sync_state")
    suspend fun deleteSyncState()
}
