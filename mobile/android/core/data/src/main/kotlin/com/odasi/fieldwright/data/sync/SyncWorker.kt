package com.odasi.fieldwright.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import com.odasi.fieldwright.data.FieldwrightContainer
import com.odasi.fieldwright.data.FieldwrightContainerHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Background flush of the outbox (CONTRACTS §8).
 *
 * WorkManager owns *when*; the outbox owns *what*. This worker is deliberately tiny: it
 * looks up the live container (the app creates one in `Application.onCreate`), calls
 * [com.odasi.fieldwright.data.SyncRepository.flushNow], and lets WorkManager's own
 * exponential backoff retry the whole pass. The per-event retry schedule lives in
 * [RetryPolicy]; a process kill therefore cannot lose the schedule because it is stored
 * on the outbox rows.
 *
 * Network constraint is CONNECTED: Fieldwright is offline-first, but a sync pass with no
 * network would only burn battery to learn what the radio already knows.
 */
class FieldwrightSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val live = FieldwrightContainerHolder.current()
        val ownsContainer = live == null
        val container = live ?: runCatching {
            withContext(Dispatchers.IO) { FieldwrightContainer(applicationContext) }
        }.getOrElse { return Result.retry() }

        return try {
            if (ownsContainer) {
                // Late-initialised process (e.g. a test harness that never started the app's
                // Application): seeding is idempotent, so this is safe and never duplicates rows.
                container.bootstrap()
            }
            val report = container.sync.flushNow()
            if (report.nextRetryAtEpochMs != null) Result.retry() else Result.success()
        } catch (failure: Throwable) {
            Result.retry()
        } finally {
            if (ownsContainer) {
                runCatching { container.close() }
            }
        }
    }
}

/**
 * Schedules the sync work. One periodic pass (the platform minimum of 15 minutes) plus an
 * on-demand one-shot used by "Submit packet" and the Brief screen's "Retry now".
 * Existing work is kept, never replaced, so repeated bootstraps are idempotent.
 */
object SyncScheduler {

    const val PERIODIC_WORK_NAME: String = "fieldwright-sync-periodic"
    const val ONE_SHOT_WORK_NAME: String = "fieldwright-sync-now"
    const val WORK_TAG: String = "fieldwright-sync"

    private const val PERIOD_MINUTES: Long = 15L

    fun ensurePeriodic(context: Context): Boolean = runCatching {
        val request = PeriodicWorkRequestBuilder<FieldwrightSyncWorker>(
            PERIOD_MINUTES,
            TimeUnit.MINUTES,
        )
            .setConstraints(networkConstraints())
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS,
            )
            .addTag(WORK_TAG)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
        true
    }.getOrDefault(false)

    fun enqueueOneShot(context: Context): Boolean = runCatching {
        val request = OneTimeWorkRequestBuilder<FieldwrightSyncWorker>()
            .setConstraints(networkConstraints())
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS,
            )
            .addTag(WORK_TAG)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            ONE_SHOT_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
        true
    }.getOrDefault(false)

    private fun networkConstraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()
}
