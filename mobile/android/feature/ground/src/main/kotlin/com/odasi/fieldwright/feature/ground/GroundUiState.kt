package com.odasi.fieldwright.feature.ground

import com.odasi.fieldwright.data.SyncState
import com.odasi.fieldwright.design.FwGeofence
import com.odasi.fieldwright.design.MapPin
import com.odasi.fieldwright.model.Availability
import com.odasi.fieldwright.model.GeoPoint
import com.odasi.fieldwright.model.HotZone
import com.odasi.fieldwright.model.Job
import com.odasi.fieldwright.model.JobStatus
import com.odasi.fieldwright.model.MarketSnapshot
import com.odasi.fieldwright.model.Offer
import com.odasi.fieldwright.model.Operator
import com.odasi.fieldwright.model.SyncPhase

/**
 * One dispatched offer plus the countdown the screen renders. The countdown is recomputed
 * from `expiresAtEpochMs` on every tick — never decremented locally — so a paused UI cannot
 * claim an offer is alive after it expired on the server.
 */
data class GroundOffer(
    val offer: Offer,
    val remainingMillis: Long,
) {
    val job: Job get() = offer.job
}

/**
 * The whole Ground screen as a value. Every field comes from a repository flow or a
 * ViewModel action; the screen is a pure function of this state.
 */
data class GroundUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val operator: Operator? = null,
    val availability: Availability = Availability(),
    val market: MarketSnapshot? = null,
    val hotZones: List<HotZone> = emptyList(),
    val operatorFix: GeoPoint? = null,
    val offers: List<GroundOffer> = emptyList(),
    val activeJobs: List<Job> = emptyList(),
    val todayEarningsCents: Int = 0,
    val selectedPinJobId: String? = null,
    val sync: SyncState? = null,
    /** Blocking, inline error (Lead owns the app snackbar; the screen owns its own notice). */
    val errorMessage: String? = null,
    /** Transient confirmation of an action ("Offer accepted · …"). */
    val actionMessage: String? = null,
    val acceptingOfferId: String? = null,
    val nowEpochMs: Long = 0L,
    val demo: Boolean = false,
) {
    val isOnline: Boolean get() = availability.isOnline

    val isOffline: Boolean
        get() = sync?.let { state ->
            state.phase == SyncPhase.WAITING_FOR_NETWORK ||
                state.phase == SyncPhase.RETRYING ||
                state.deadLetters > 0
        } == true

    val queuedMutations: Int get() = sync?.queued ?: 0

    /** The active job the operator should look at first: earliest lifecycle stage wins. */
    val nextUp: Job? get() = activeJobs.minByOrNull { it.status.ordinal }

    /** Every job that deserves a pin: live offers plus work already accepted. */
    val mapJobs: List<Job>
        get() = (offers.map { it.job } + activeJobs).distinctBy { it.id }

    val mapPins: List<MapPin>
        get() = mapJobs.map { job ->
            MapPin(
                id = job.id,
                point = job.site.point,
                label = "${job.title} · ${job.site.address}",
                payoutCents = job.payoutCents,
                selected = job.id == selectedPinJobId,
            )
        }

    val selectedOffer: GroundOffer?
        get() = selectedPinJobId?.let { id -> offers.firstOrNull { it.job.id == id } }

    val selectedActiveJob: Job?
        get() = selectedPinJobId?.let { id -> activeJobs.firstOrNull { it.id == id } }

    /** Route polyline target: the next accepted job's site, when there is one. */
    val routeTo: GeoPoint? get() = nextUp?.site?.point

    val nextUpGeofence: FwGeofence?
        get() = nextUp?.let { job ->
            FwGeofence(
                id = job.id,
                point = job.site.point,
                radiusMeters = job.site.geofenceRadiusMeters,
            )
        }

    val checklistProgress: Pair<Int, Int>?
        get() = nextUp?.let { job -> 0 to job.checklist.size }

    companion object {
        /** The lifecycle stages that count as "next up" on Ground, in priority order. */
        val NEXT_UP_STATUSES: Set<JobStatus> = setOf(
            JobStatus.ACCEPTED,
            JobStatus.EN_ROUTE,
            JobStatus.ON_SITE,
            JobStatus.IN_PROGRESS,
            JobStatus.PACKET_READY,
        )
    }
}

/**
 * A cheap "where am I" for the map when the app has not supplied a fix. The locked
 * `GroundRoute` signature has no `LocationProvider`, so the demo market is centred on the
 * jobs and hot zones the repositories returned and labelled as an estimated fix. The app
 * layer can pass a real provider through the ViewModel's `fixProvider` once it owns one.
 */
internal fun List<Job>.centroidFix(nowEpochMs: Long): GeoPoint? {
    if (isEmpty()) return null
    val lat = sumOf { it.site.point.lat } / size.toDouble()
    val lng = sumOf { it.site.point.lng } / size.toDouble()
    return GeoPoint(
        lat = lat,
        lng = lng,
        recordedAtEpochMs = nowEpochMs,
        accuracyMeters = ESTIMATED_FIX_ACCURACY_METERS,
        source = GeoPoint.FixSource.SIMULATED,
    )
}

/** Accuracy shown for a derived fix so the halo is honest about being an estimate. */
internal const val ESTIMATED_FIX_ACCURACY_METERS = 18f
