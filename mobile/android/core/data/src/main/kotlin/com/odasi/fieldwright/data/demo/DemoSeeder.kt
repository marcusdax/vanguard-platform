package com.odasi.fieldwright.data.demo

import androidx.room.withTransaction
import com.odasi.fieldwright.data.db.FieldwrightDatabase
import com.odasi.fieldwright.data.mapper.Mappers
import com.odasi.fieldwright.data.prefs.FieldwrightPreferences
import com.odasi.fieldwright.model.Availability
import com.odasi.fieldwright.model.Clock
import com.odasi.fieldwright.model.EvidencePacket
import com.odasi.fieldwright.model.Job
import com.odasi.fieldwright.model.LedgerEntry
import com.odasi.fieldwright.model.MarketSnapshot
import com.odasi.fieldwright.model.Offer
import com.odasi.fieldwright.model.Operator
import com.odasi.fieldwright.model.PayoutRequest

/**
 * Everything the sample book writes, as a tiny sink. Keeping persistence behind this
 * interface is what makes "seed twice = same rows" a **pure unit test**: the production
 * implementation is [RoomDemoSink], the test one is an in-memory map.
 */
interface DemoSink {
    /** True when a previous seed is present (operator row exists). */
    suspend fun isSeeded(): Boolean

    /** Demo-build reset: wipes the local book so it can be re-seeded. */
    suspend fun clearAll()

    suspend fun saveOperator(operator: Operator)
    suspend fun saveAvailability(availability: Availability, onlineMinutesToday: Int, nowEpochMs: Long)
    suspend fun saveMarket(market: MarketSnapshot, tickIndex: Long, nowEpochMs: Long)
    suspend fun saveJobs(jobs: List<Job>)
    suspend fun saveOffers(offers: List<Offer>)
    suspend fun saveLedger(entries: List<LedgerEntry>)
    suspend fun savePayouts(payouts: List<PayoutRequest>)
    suspend fun savePacket(packet: EvidencePacket, merkleTreeSize: Int, merkleRoot: String?, merkleIndex: Int)
}

/**
 * Seeds the sample book on first launch (CONTRACTS §8) and on the Brief screen's explicit
 * "reset sample book". Seed order is dependency order: operator → availability/market →
 * jobs → offers → money → sealed packets (packets read their job from the catalog, not
 * from the database, so a failed packet build can never half-write evidence).
 *
 * Idempotency: without [force] a present seed is left untouched; with [force] the demo
 * rows are wiped first. Both paths are covered by unit tests against a fake sink.
 */
class DemoSeeder(
    private val sink: DemoSink,
    private val packetFactory: DemoPacketFactory?,
    private val clock: Clock,
) {

    /** @return true when rows were written by this call. */
    suspend fun seed(force: Boolean = false): Boolean {
        if (!force && sink.isSeeded()) return false
        if (force) sink.clearAll()

        val now = clock.nowEpochMs()
        val operator = DemoCatalog.operator()
        val jobs = DemoCatalog.jobs(now)

        sink.saveOperator(operator)
        sink.saveAvailability(DemoCatalog.availability(now), onlineMinutesToday = 187, nowEpochMs = now)
        sink.saveMarket(DemoCatalog.market(now), tickIndex = 0, nowEpochMs = now)
        sink.saveJobs(jobs)
        sink.saveOffers(DemoCatalog.offers(now))
        sink.saveLedger(DemoCatalog.ledger(now))
        sink.savePayouts(DemoCatalog.payouts(now))
        packetFactory?.build(jobs, operator, now)?.forEach { sealed ->
            sink.savePacket(sealed.packet, sealed.merkleTreeSize, sealed.merkleRoot, sealed.merkleIndex)
        }
        return true
    }
}

/**
 * Room-backed sink. All writes of one seed pass run in a single transaction so a device
 * that dies mid-seed never leaves a half-book behind; because every save is an upsert,
 * re-running after the crash is safe.
 */
class RoomDemoSink(
    private val database: FieldwrightDatabase,
    private val preferences: FieldwrightPreferences,
) : DemoSink {

    private val jobDao = database.jobDao()
    private val offerDao = database.offerDao()
    private val packetDao = database.packetDao()
    private val captureDao = database.captureDao()
    private val moneyDao = database.moneyDao()
    private val operatorDao = database.operatorDao()
    private val marketDao = database.marketDao()
    private val outboxDao = database.outboxDao()

    override suspend fun isSeeded(): Boolean = operatorDao.getOperator() != null

    override suspend fun clearAll() {
        database.withTransaction {
            // Child evidence before parents; packets cascade their records and badges.
            packetDao.deleteAll()
            captureDao.deleteAllMedia()
            captureDao.deleteAllMeasurements()
            captureDao.deleteAllChecklist()
            offerDao.deleteAll()
            jobDao.deleteAll()
            moneyDao.deleteAllLedger()
            moneyDao.deleteAllPayouts()
            operatorDao.deleteOperator()
            operatorDao.deleteAvailability()
            marketDao.deleteMarket()
            outboxDao.deleteAll()
            outboxDao.deleteSyncState()
        }
        preferences.clearSeedMarker()
    }

    override suspend fun saveOperator(operator: Operator) {
        operatorDao.upsertOperator(with(Mappers) { operator.toEntity(demo = true) })
    }

    override suspend fun saveAvailability(
        availability: Availability,
        onlineMinutesToday: Int,
        nowEpochMs: Long,
    ) {
        operatorDao.upsertAvailability(
            with(Mappers) {
                availability.toEntity(
                    onlineMinutesToday = onlineMinutesToday,
                    onlineSinceEpochMs = if (availability.isOnline) nowEpochMs - 2 * 60 * 60_000L else null,
                )
            },
        )
        // DataStore is the durable owner of the operator's own availability toggle.
        preferences.saveAvailability(availability)
    }

    override suspend fun saveMarket(market: MarketSnapshot, tickIndex: Long, nowEpochMs: Long) {
        marketDao.upsertMarket(with(Mappers) { market.toEntity(tickIndex, nowEpochMs) })
    }

    override suspend fun saveJobs(jobs: List<Job>) {
        val now = clockNow()
        jobDao.upsertAll(jobs.map { job -> with(Mappers) { job.toEntity(updatedAtEpochMs = now) } })
    }

    override suspend fun saveOffers(offers: List<Offer>) {
        offerDao.upsertAll(offers.mapIndexed { index, offer -> with(Mappers) { offer.toEntity(index) } })
    }

    override suspend fun saveLedger(entries: List<LedgerEntry>) {
        moneyDao.insertLedgerAll(entries.map { entry -> with(Mappers) { entry.toEntity() } })
    }

    override suspend fun savePayouts(payouts: List<PayoutRequest>) {
        moneyDao.insertPayouts(payouts.map { payout -> with(Mappers) { payout.toEntity(demo = true) } })
    }

    override suspend fun savePacket(
        packet: EvidencePacket,
        merkleTreeSize: Int,
        merkleRoot: String?,
        merkleIndex: Int,
    ) {
        database.withTransaction {
            packetDao.insertPacket(with(Mappers) { packet.toEntity(merkleTreeSize, merkleRoot, merkleIndex, packet.packetHash) })
            packetDao.insertRecords(packet.records.map { record -> with(Mappers) { record.toEntity(packet.id) } })
            packetDao.insertBadges(packet.badges.map { badge -> with(Mappers) { badge.toEntity(packet.id) } })
            packet.media.forEach { ref ->
                captureDao.upsertMedia(with(Mappers) { ref.toEntity(jobId = packet.jobId, packetId = packet.id) })
            }
            packet.measurements.forEach { measurement ->
                captureDao.upsertMeasurement(with(Mappers) { measurement.toEntity(jobId = packet.jobId, packetId = packet.id) })
            }
            packet.checklist.forEach { result ->
                captureDao.upsertChecklist(
                    with(Mappers) {
                        result.toEntity(
                            jobId = packet.jobId,
                            packetId = packet.id,
                            recordedAtEpochMs = packet.sealedAtEpochMs ?: packet.createdAtEpochMs,
                        )
                    },
                )
            }
        }
    }

    private fun clockNow(): Long = System.currentTimeMillis()
}
