package com.odasi.fieldwright.data.repo

import com.odasi.fieldwright.data.ArrivalCheck
import com.odasi.fieldwright.data.JobRepository
import com.odasi.fieldwright.data.db.JobEntity
import com.odasi.fieldwright.data.db.OutboxKind
import com.odasi.fieldwright.data.mapper.Mappers
import com.odasi.fieldwright.model.GeoMath
import com.odasi.fieldwright.model.GeoPoint
import com.odasi.fieldwright.model.Idempotency
import com.odasi.fieldwright.model.Job
import com.odasi.fieldwright.model.JobLifecycle
import com.odasi.fieldwright.model.JobStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Locale

/**
 * Job lifecycle from the worker's side. Every transition is checked against
 * [JobLifecycle] before it is written — the UI asks, it never assumes — and every write
 * enqueues one idempotent status event.
 *
 * Arrival is a geofence check, never a tap: [checkArrival] recomputes the distance from
 * the supplied device fix, and [confirmArrival] refuses to move the job to ON_SITE outside
 * the fence unless the operator records an override reason, which is stored and later
 * surfaces as a non-blocking review flag on the packet gates.
 */
internal class JobRepositoryImpl(private val stores: DataStores) : JobRepository {

    override fun observeActiveJobs(): Flow<List<Job>> =
        stores.jobs.observeActive().map { rows -> rows.map { row -> with(Mappers) { row.toModel() } } }

    override fun observeCompletedJobs(): Flow<List<Job>> =
        stores.jobs.observeCompleted().map { rows -> rows.map { row -> with(Mappers) { row.toModel() } } }

    override fun observeJob(jobId: String): Flow<Job?> =
        stores.jobs.observeJob(jobId).map { row -> row?.let { with(Mappers) { it.toModel() } } }

    override suspend fun markEnRoute(jobId: String, atEpochMs: Long): Result<Job> = runCatching {
        mutate(jobId) { entity ->
            when (entity.status) {
                JobStatus.EN_ROUTE -> entity
                JobStatus.ACCEPTED -> entity.copy(status = JobStatus.EN_ROUTE)
                else -> throw RepositoryException(
                    "Job ${entity.id} is ${entity.status.label}; it cannot move to ${JobStatus.EN_ROUTE.label}.",
                )
            }
        }
    }

    override suspend fun checkArrival(jobId: String, fix: GeoPoint, atEpochMs: Long): Result<ArrivalCheck> = runCatching {
        val entity = stores.jobs.get(jobId) ?: throw RepositoryException("Job $jobId not found.")
        arrivalCheck(entity, fix)
    }

    override suspend fun confirmArrival(
        jobId: String,
        fix: GeoPoint,
        overrideReason: String?,
        atEpochMs: Long,
    ): Result<Job> = runCatching {
        val updated = mutate(jobId) { entity ->
            if (entity.status == JobStatus.ON_SITE) return@mutate entity
            if (!JobLifecycle.canTransition(entity.status, JobStatus.ON_SITE)) {
                throw RepositoryException(
                    "Job ${entity.id} is ${entity.status.label}; it cannot record an arrival from there.",
                )
            }
            val check = arrivalCheck(entity, fix)
            if (!check.insideGeofence && overrideReason.isNullOrBlank()) {
                throw ArrivalRejectedException(
                    "You are ${meters(check.distanceMeters)} from the site, outside the " +
                        "${entity.geofenceRadiusMeters} m fence. Record an override reason to continue; " +
                        "the packet will be flagged for review.",
                )
            }
            entity.copy(
                status = JobStatus.ON_SITE,
                arrivedAtEpochMs = atEpochMs,
                arrivalDistanceMeters = check.distanceMeters,
                arrivalAccuracyMeters = fix.accuracyMeters,
                arrivalOverrideReason = overrideReason?.takeIf { !check.insideGeofence }?.trim(),
            )
        }
        updated
    }

    override suspend fun startCapture(jobId: String, atEpochMs: Long): Result<Job> = runCatching {
        mutate(jobId) { entity ->
            when (entity.status) {
                JobStatus.IN_PROGRESS, JobStatus.PACKET_READY -> entity
                JobStatus.ON_SITE -> entity.copy(
                    status = JobStatus.IN_PROGRESS,
                    startedAtEpochMs = entity.startedAtEpochMs ?: atEpochMs,
                )
                else -> throw RepositoryException(
                    "Job ${entity.id} is ${entity.status.label}; capture starts on site.",
                )
            }
        }
    }

    /**
     * Marks the sealed packet as submitted locally. Submission is queued offline-first by
     * the outbox; this only reflects the worker's intent in the job lifecycle.
     */
    override suspend fun markCompletedLocally(jobId: String, atEpochMs: Long): Result<Job> = runCatching {
        mutate(jobId) { entity ->
            when (entity.status) {
                JobStatus.SUBMITTED -> entity
                JobStatus.SEALED -> entity.copy(status = JobStatus.SUBMITTED)
                else -> throw RepositoryException(
                    "Job ${entity.id} is ${entity.status.label}; only a sealed packet can be submitted.",
                )
            }
        }
    }

    private fun arrivalCheck(entity: JobEntity, fix: GeoPoint): ArrivalCheck {
        val job = with(Mappers) { entity.toModel() }
        val distance = GeoMath.distanceMeters(fix, job.site.point)
        val inside = distance <= job.site.geofenceRadiusMeters.toDouble()
        val detail = if (inside) {
            "Inside the ${job.site.geofenceRadiusMeters} m fence (${meters(distance)} from the site point)."
        } else {
            "Outside the ${job.site.geofenceRadiusMeters} m fence by ${meters(distance - job.site.geofenceRadiusMeters)}."
        }
        return ArrivalCheck(
            accepted = inside,
            insideGeofence = inside,
            distanceMeters = distance,
            accuracyMeters = fix.accuracyMeters,
            detail = detail,
            overrideRequired = !inside,
        )
    }

    private suspend fun mutate(jobId: String, block: (JobEntity) -> JobEntity): Job {
        var updated: JobEntity? = null
        stores.transaction {
            val current = stores.jobs.get(jobId) ?: throw RepositoryException("Job $jobId not found.")
            val next = block(current)
            val entity = next.copy(updatedAtEpochMs = stores.clock.nowEpochMs())
            updated = entity
            if (entity != current) {
                stores.jobs.upsert(entity)
                stores.outbox.enqueue(
                    idempotencyKey = Idempotency.key("job.status", jobId, entity.status.name, entity.updatedAtEpochMs.toString()),
                    kind = OutboxKind.JOB_STATUS,
                    method = "PATCH",
                    path = "/jobs/$jobId",
                    payload = mapOf(
                        "jobId" to jobId,
                        "status" to entity.status.name,
                        "at" to entity.updatedAtEpochMs.toString(),
                        "packetId" to (entity.packetId ?: ""),
                    ),
                    demo = entity.demo,
                    nowEpochMs = entity.updatedAtEpochMs,
                )
            }
        }
        val result = updated ?: throw RepositoryException("Job $jobId could not be updated.")
        return with(Mappers) { result.toModel() }
    }

    private fun meters(value: Double): String = String.format(Locale.US, "%.0f m", value)
}
