package com.odasi.fieldwright.data.sync

import com.odasi.fieldwright.data.SyncReport
import com.odasi.fieldwright.data.db.OutboxDao
import com.odasi.fieldwright.data.db.OutboxEntity
import com.odasi.fieldwright.data.db.OutboxKind
import com.odasi.fieldwright.data.db.OutboxStatus
import com.odasi.fieldwright.data.db.SyncStateEntity
import com.odasi.fieldwright.data.mapper.JsonCodec
import com.odasi.fieldwright.data.mapper.Mappers
import com.odasi.fieldwright.model.Clock
import com.odasi.fieldwright.model.SyncPhase
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Drains the outbox against a [BackendApi] (CONTRACTS §8).
 *
 * Invariants:
 * - one event is one row, keyed by an idempotency key that never changes across retries;
 * - a retryable failure schedules the next attempt with [RetryPolicy] and leaves the event
 *   visible (`RETRYING` in [SyncState]);
 * - after [RetryPolicy.MAX_ATTEMPTS] transient failures, or on a permanent 4xx rejection,
 *   the event becomes a DEAD_LETTER — visible in Brief, never silently dropped;
 * - a mutex means the in-app "Retry now" and the background worker cannot double-submit.
 *
 * Demo events are enqueued with `demo = true`; the outbox still moves them (so sync health
 * is honest) but they are flagged so the backend can exclude them from any anchor.
 */
class OutboxProcessor(
    private val dao: OutboxDao,
    private val backend: BackendApi,
    private val clock: Clock,
    private val jitter: Jitter = RandomJitter(),
) {

    private val flushMutex = Mutex()

    /** Enqueues one event. Duplicate idempotency keys are ignored at the database level. */
    suspend fun enqueue(
        idempotencyKey: String,
        kind: OutboxKind,
        method: String,
        path: String,
        payload: Map<String, String>,
        demo: Boolean,
        nowEpochMs: Long = clock.nowEpochMs(),
    ) {
        dao.insert(
            OutboxEntity(
                id = "ob-$idempotencyKey",
                idempotencyKey = idempotencyKey,
                kind = kind,
                method = method,
                path = path,
                payloadJson = JsonCodec.encodeMap(payload),
                createdAtEpochMs = nowEpochMs,
                attemptCount = 0,
                nextAttemptAtEpochMs = nowEpochMs,
                status = OutboxStatus.QUEUED,
                lastError = null,
                demo = demo,
            ),
        )
    }

    /**
     * Processes at most [maxEvents] due events and returns a report the Brief screen can
     * render. Never throws for a backend failure — a sync attempt is a status, not an
     * exception the UI has to catch.
     */
    suspend fun flush(maxEvents: Int = 20): SyncReport = flushMutex.withLock {
        val now = clock.nowEpochMs()
        val due = dao.dueEvents(now, maxEvents)
        var succeeded = 0
        var failed = 0
        var nextRetryAt: Long? = null
        var lastError: String? = null
        val detail = StringBuilder()

        for (event in due) {
            dao.updateState(event.id, OutboxStatus.IN_FLIGHT, event.attemptCount, now, null)
            val response = try {
                backend.submit(event.toEnvelope())
            } catch (failure: Throwable) {
                BackendResponse.Retryable(failure.message ?: "Transport failed")
            }
            when (response) {
                is BackendResponse.Accepted -> {
                    dao.markSent(event.id)
                    succeeded++
                }

                is BackendResponse.Rejected -> {
                    dao.updateState(
                        event.id,
                        OutboxStatus.DEAD_LETTER,
                        event.attemptCount + 1,
                        now,
                        response.reason,
                    )
                    failed++
                    lastError = response.reason
                    detail.append("Rejected ${event.kind}: ${response.reason} ")
                }

                is BackendResponse.Retryable -> {
                    val attempts = event.attemptCount + 1
                    if (RetryPolicy.isDeadLetter(attempts)) {
                        dao.updateState(event.id, OutboxStatus.DEAD_LETTER, attempts, now, response.reason)
                    } else {
                        val delayMillis = RetryPolicy.delayMillis(attempts, jitter)
                        val next = now + delayMillis
                        dao.updateState(event.id, OutboxStatus.QUEUED, attempts, next, response.reason)
                        nextRetryAt = nextRetryAt?.let { minOf(it, next) } ?: next
                    }
                    failed++
                    lastError = response.reason
                    detail.append("Retry ${event.kind}: ${response.reason} ")
                }
            }
        }

        val deadLetters = dao.deadLetterCount()
        val queued = dao.queuedCount()
        val inFlight = dao.inFlightCount()
        val phase = when {
            deadLetters > 0 -> SyncPhase.DEAD_LETTER
            failed > 0 -> SyncPhase.RETRYING
            inFlight > 0 -> SyncPhase.SYNCING
            queued > 0 -> SyncPhase.WAITING_FOR_NETWORK
            else -> SyncPhase.IDLE
        }
        val previous = dao.getSyncState()
        dao.upsertSyncState(
            SyncStateEntity(
                phase = phase,
                lastSyncedAtEpochMs = if (succeeded > 0) now else previous?.lastSyncedAtEpochMs,
                lastError = lastError ?: if (failed == 0) previous?.lastError else lastError,
                updatedAtEpochMs = now,
            ),
        )
        // Keep the outbox bounded; sent rows are audit trail, not permanent storage.
        dao.pruneSent(now - SENT_RETENTION_MS)

        SyncReport(
            attempted = due.size,
            succeeded = succeeded,
            failed = failed,
            nextRetryAtEpochMs = nextRetryAt,
            detail = when {
                due.isEmpty() -> "Outbox is empty — nothing to sync."
                failed == 0 -> "Synced ${succeeded} event(s)."
                else -> detail.toString().trim()
            },
        )
    }

    /** Puts dead letters back in the queue (Brief → "Retry dead letters"). */
    suspend fun reviveDeadLetters(): Int =
        dao.reviveDeadLetters(clock.nowEpochMs())

    private fun OutboxEntity.toEnvelope(): SyncEnvelope = SyncEnvelope(
        idempotencyKey = idempotencyKey,
        kind = kind,
        method = method,
        path = path,
        payload = JsonCodec.decodeMap(payloadJson),
        createdAtEpochMs = createdAtEpochMs,
        demo = demo,
    )

    private companion object {
        const val SENT_RETENTION_MS: Long = 7 * 24 * 60 * 60 * 1000L
    }
}

/** Maps the sync-state row for repositories (counts are derived from the outbox itself). */
internal fun SyncStateEntity.withCounts(queued: Int, inFlight: Int, deadLetters: Int) =
    with(Mappers) { this@withCounts.toModel(queued, inFlight, deadLetters) }
