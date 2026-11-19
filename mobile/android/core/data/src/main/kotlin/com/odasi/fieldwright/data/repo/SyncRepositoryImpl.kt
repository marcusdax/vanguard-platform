package com.odasi.fieldwright.data.repo

import com.odasi.fieldwright.data.SyncReport
import com.odasi.fieldwright.data.SyncRepository
import com.odasi.fieldwright.data.SyncState
import com.odasi.fieldwright.data.mapper.Mappers
import com.odasi.fieldwright.data.sync.OutboxProcessor
import com.odasi.fieldwright.model.SyncPhase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Sync health for the Brief screen. The phase is derived from the outbox itself (counts by
 * status) rather than trusted from a mutable flag, so a crash cannot leave the UI claiming
 * "synced" while events are still queued.
 */
internal class SyncRepositoryImpl(
    private val stores: DataStores,
    private val processor: OutboxProcessor,
) : SyncRepository {

    override fun observeSyncState(): Flow<SyncState> = combine(
        stores.outboxDao.observeQueuedCount(),
        stores.outboxDao.observeInFlightCount(),
        stores.outboxDao.observeDeadLetterCount(),
        stores.outboxDao.observeSyncState(),
    ) { queued, inFlight, deadLetters, stateRow ->
        val row = stateRow ?: with(Mappers) { defaultSyncState(stores.clock.nowEpochMs()) }
        val model = with(Mappers) { row.toModel(queued, inFlight, deadLetters) }
        model.copy(
            phase = when {
                deadLetters > 0 -> SyncPhase.DEAD_LETTER
                inFlight > 0 -> SyncPhase.SYNCING
                queued > 0 && model.phase == SyncPhase.IDLE -> SyncPhase.WAITING_FOR_NETWORK
                else -> model.phase
            },
        )
    }

    override suspend fun flushNow(): SyncReport = processor.flush()

    /** Brief → "Retry dead letters": puts permanently-failed events back in the queue. */
    suspend fun retryDeadLetters(): Int = processor.reviveDeadLetters()
}
