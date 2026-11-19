package com.odasi.fieldwright.feature.jobs

import com.odasi.fieldwright.data.ArrivalCheck
import com.odasi.fieldwright.model.ActiveWork
import com.odasi.fieldwright.model.GeoPoint
import com.odasi.fieldwright.model.Job
import com.odasi.fieldwright.model.JobStatus
import com.odasi.fieldwright.model.Offer

/**
 * The one action a job detail screen offers for a given lifecycle status. Pure mapping so
 * both the ViewModel and the tests ask the same question (CONTRACTS §5.4: transitions come
 * from the lifecycle, the UI never invents one).
 */
enum class JobDetailAction {
    ACCEPT,
    EN_ROUTE,
    CHECK_ARRIVAL,
    START_CAPTURE,
    CONTINUE_CAPTURE,
    OPEN_PACKET,
    NONE,
}

internal fun actionFor(status: JobStatus): JobDetailAction = when (status) {
    JobStatus.AVAILABLE, JobStatus.OFFERED -> JobDetailAction.ACCEPT
    JobStatus.ACCEPTED -> JobDetailAction.EN_ROUTE
    JobStatus.EN_ROUTE -> JobDetailAction.CHECK_ARRIVAL
    JobStatus.ON_SITE -> JobDetailAction.START_CAPTURE
    JobStatus.IN_PROGRESS -> JobDetailAction.CONTINUE_CAPTURE
    JobStatus.PACKET_READY, JobStatus.SEALED, JobStatus.SUBMITTED -> JobDetailAction.OPEN_PACKET
    else -> JobDetailAction.NONE
}

internal fun labelFor(action: JobDetailAction): String = when (action) {
    JobDetailAction.ACCEPT -> "Accept offer"
    JobDetailAction.EN_ROUTE -> "En route"
    JobDetailAction.CHECK_ARRIVAL -> "I've arrived"
    JobDetailAction.START_CAPTURE -> "Start capture"
    JobDetailAction.CONTINUE_CAPTURE -> "Continue capture"
    JobDetailAction.OPEN_PACKET -> "Open packet"
    JobDetailAction.NONE -> ""
}

/** Navigation the ViewModel cannot perform itself; the route consumes and clears it. */
sealed interface JobDetailNavigation {
    /** Open the capture flow for this job. */
    data object Capture : JobDetailNavigation

    /** Open a sealed packet by id. */
    data class Packet(val packetId: String) : JobDetailNavigation
}

/** One job, as detail state. */
data class JobDetailUiState(
    val loading: Boolean = true,
    val job: Job? = null,
    val offer: Offer? = null,
    val packetId: String? = null,
    val packetHash: String? = null,
    val work: ActiveWork? = null,
    /** Result of the last geofence check; cleared once arrival is confirmed or dismissed. */
    val arrival: ArrivalCheck? = null,
    /** The trusted fix the arrival check used — reused by confirm, never re-fabricated. */
    val lastFix: GeoPoint? = null,
    val overrideReason: String = "",
    val busy: Boolean = false,
    val errorMessage: String? = null,
    val actionMessage: String? = null,
    val navigationTarget: JobDetailNavigation? = null,
    val nowEpochMs: Long = 0L,
) {
    val action: JobDetailAction get() = job?.let { actionFor(it.status) } ?: JobDetailAction.NONE

    val actionLabel: String get() = labelFor(action)

    /** Accept is only offered when a live offer for this job is actually on file. */
    val canAccept: Boolean get() = action == JobDetailAction.ACCEPT && offer != null

    /** Geofence miss (or a refused check) requires a recorded override reason. */
    val overrideRequired: Boolean
        get() = arrival?.let { check -> !check.accepted || !check.insideGeofence } == true

    /** Whether the arrival sheet is open: any check that still needs a decision. */
    val arrivalSheetVisible: Boolean get() = arrival != null

    val payoutCents: Int get() = offer?.payoutCents ?: job?.payoutCents ?: 0

    /** Surge is disclosed before acceptance, so it is shown as its own uplift line. */
    val surgeUpliftCents: Int?
        get() = offer?.let { live ->
            val base = job?.payoutCents ?: live.payoutCents
            (live.payoutCents - base).takeIf { it > 0 }
        }

    val surgeMultiplier: Double? get() = offer?.surgeMultiplier?.takeIf { it > 1.0 }

    val geofenceRadiusMeters: Int get() = job?.site?.geofenceRadiusMeters ?: 0

    val checklistProgress: Pair<Int, Int>
        get() = job?.let { job ->
            val total = job.checklist.size
            val done = work?.checklist?.size ?: 0
            done.coerceAtMost(total) to total
        } ?: (0 to 0)

    /** SLA minutes left; negative means overrun. Null when the job is not yet accepted. */
    val slaRemainingMinutes: Long?
        get() = job?.let { job ->
            val acceptedAt = job.acceptedAtEpochMs ?: return null
            ((job.slaMinutes * 60_000L) - (nowEpochMs - acceptedAt)) / 60_000L
        }
}
