package com.odasi.fieldwright.feature.jobs

import com.odasi.fieldwright.model.Job
import com.odasi.fieldwright.model.JobStatus
import com.odasi.fieldwright.model.Offer

/** The three ways to look at the same work: what is offered, what is live, what is finished. */
enum class JobsFilter(val label: String) {
    OFFERS("Offers"),
    ACTIVE("Active"),
    DONE("Done"),
}

/** A live offer plus the countdown the list renders. */
data class JobsOffer(
    val offer: Offer,
    val remainingMillis: Long,
) {
    val job: Job get() = offer.job
}

/**
 * Jobs list as a value. Offers mirror Ground (with the 45 s ring); Active shows lifecycle
 * progress and the next action; Done shows payout, the packet fingerprint and a way back
 * into the job.
 */
data class JobsUiState(
    val loading: Boolean = true,
    val filter: JobsFilter = JobsFilter.OFFERS,
    val offers: List<JobsOffer> = emptyList(),
    val activeJobs: List<Job> = emptyList(),
    val completedJobs: List<Job> = emptyList(),
    /** jobId → packet id, so a Done card can open the right packet from the job. */
    val packetIds: Map<String, String> = emptyMap(),
    /** jobId → packet short hash. The hash lives on the packet, never on the job. */
    val packetHashes: Map<String, String> = emptyMap(),
    val acceptingOfferId: String? = null,
    val refreshing: Boolean = false,
    val errorMessage: String? = null,
    val actionMessage: String? = null,
    val nowEpochMs: Long = 0L,
) {
    val visibleCount: Int
        get() = when (filter) {
            JobsFilter.OFFERS -> offers.size
            JobsFilter.ACTIVE -> activeJobs.size
            JobsFilter.DONE -> completedJobs.size
        }
}

/** Ordered lifecycle labels for [com.odasi.fieldwright.design.FwProgressSteps]. */
internal val WORKFLOW_STEPS: List<String> = listOf("Accepted", "En route", "On site", "Capture", "Packet")

/** 0-based index into [WORKFLOW_STEPS] for a job status. Pure, so the mapping is testable. */
internal fun workflowStepIndex(status: JobStatus): Int = when (status) {
    JobStatus.ACCEPTED -> 0
    JobStatus.EN_ROUTE -> 1
    JobStatus.ON_SITE -> 2
    JobStatus.IN_PROGRESS -> 3
    JobStatus.PACKET_READY, JobStatus.SEALED, JobStatus.SUBMITTED -> 4
    else -> 0
}

/** What the one primary button on an Active card does. */
enum class JobsListAction {
    OPEN_JOB,
    START_CAPTURE,
    CONTINUE_CAPTURE,
}

internal fun listActionFor(status: JobStatus): JobsListAction = when (status) {
    JobStatus.ON_SITE -> JobsListAction.START_CAPTURE
    JobStatus.IN_PROGRESS, JobStatus.PACKET_READY -> JobsListAction.CONTINUE_CAPTURE
    else -> JobsListAction.OPEN_JOB
}

internal fun listActionLabel(action: JobsListAction): String = when (action) {
    JobsListAction.OPEN_JOB -> "Open job"
    JobsListAction.START_CAPTURE -> "Start capture"
    JobsListAction.CONTINUE_CAPTURE -> "Continue capture"
}
