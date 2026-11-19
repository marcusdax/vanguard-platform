package com.odasi.fieldwright.feature.jobs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.odasi.fieldwright.data.FieldwrightRepositories
import com.odasi.fieldwright.model.GeoPoint
import com.odasi.fieldwright.model.OfferStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One job, with the actions its lifecycle actually allows.
 *
 * Arrival is never fabricated: [checkArrival] asks the repository with a trusted device fix
 * and shows the geofence result; a miss requires a recorded [overrideReason] before
 * [confirmArrival] will write. Both calls go through `JobRepository`, which is the only
 * place allowed to move a job's status.
 *
 * @param fixProvider device fix supplied by the app. Without it the arrival check refuses
 * with an honest message instead of inventing a position.
 */
class JobDetailViewModel(
    private val repos: FieldwrightRepositories,
    private val jobId: String,
    private val now: () -> Long = System::currentTimeMillis,
    private val fixProvider: (() -> GeoPoint?)? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(JobDetailUiState(nowEpochMs = now()))
    val state: StateFlow<JobDetailUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repos.jobs.observeJob(jobId)
                .catch { error -> reportError(error) }
                .collect { job -> _state.update { it.copy(job = job, loading = false) } }
        }
        viewModelScope.launch {
            repos.dispatch.observeOffers()
                .catch { error -> reportError(error) }
                .collect { offers ->
                    val live = offers.firstOrNull { offer ->
                        offer.job.id == jobId && offer.status == OfferStatus.PENDING
                    }
                    _state.update { it.copy(offer = live) }
                }
        }
        viewModelScope.launch {
            repos.vault.observePackets()
                .catch { error -> reportError(error) }
                .collect { packets ->
                    val packet = packets.firstOrNull { packet -> packet.jobId == jobId }
                    _state.update { current ->
                        current.copy(
                            packetId = packet?.id ?: current.job?.packetId,
                            packetHash = packet?.shortHash?.takeIf { hash -> hash.isNotBlank() },
                        )
                    }
                }
        }
        viewModelScope.launch {
            repos.capture.observeWork(jobId)
                .catch { error -> reportError(error) }
                .collect { work -> _state.update { it.copy(work = work) } }
        }
    }

    /** Drives the SLA clock; the route calls this while the detail is composed. */
    fun onTick() {
        _state.update { it.copy(nowEpochMs = now()) }
    }

    fun acceptOffer() {
        val offer = _state.value.offer
        if (offer == null) {
            reportMessage("There is no live offer for this job. Offers expire after 45 seconds.")
            return
        }
        val at = now()
        runBusy {
            repos.dispatch.acceptOffer(offer.id, at).fold(
                onSuccess = { job ->
                    _state.update {
                        it.copy(
                            job = job,
                            offer = null,
                            actionMessage = "Offer accepted · ${job.title}",
                        )
                    }
                },
                onFailure = { error ->
                    reportError(
                        error,
                        "That offer could not be accepted. It may have expired or been taken.",
                    )
                },
            )
        }
    }

    fun markEnRoute() {
        val at = now()
        runBusy {
            repos.jobs.markEnRoute(jobId, at).fold(
                onSuccess = { job ->
                    _state.update {
                        it.copy(
                            job = job,
                            actionMessage = "You are en route. The site geofence is ${job.site.geofenceRadiusMeters} m.",
                        )
                    }
                },
                onFailure = { error -> reportError(error, "Could not mark you en route.") },
            )
        }
    }

    /**
     * Geofence check. Refuses rather than fabricates when no trusted fix is available; the
     * ViewModel never substitutes the site coordinates for a device position.
     */
    fun checkArrival() {
        val fix = fixProvider?.invoke()
        if (fix == null) {
            reportMessage(
                "No trusted GPS fix yet. Arrival is a geofence check, so it needs a real location — step outside and retry.",
            )
            return
        }
        val at = now()
        runBusy {
            repos.jobs.checkArrival(jobId, fix, at).fold(
                onSuccess = { check ->
                    _state.update {
                        it.copy(arrival = check, lastFix = fix, overrideReason = "", errorMessage = null)
                    }
                },
                onFailure = { error ->
                    reportError(error, "Could not check the geofence. Retry when you have coverage.")
                },
            )
        }
    }

    fun dismissArrival() {
        _state.update { it.copy(arrival = null, lastFix = null, overrideReason = "") }
    }

    fun setOverrideReason(reason: String) {
        _state.update { it.copy(overrideReason = reason) }
    }

    /** Confirm arrival, with a mandatory reason when the check was outside the geofence. */
    fun confirmArrival() {
        val current = _state.value
        val check = current.arrival ?: return
        val fix = current.lastFix
        if (fix == null) {
            reportMessage("Arrival needs the location fix from the arrival check. Run the check again.")
            return
        }
        val requiresOverride = !check.accepted || !check.insideGeofence
        val reason = current.overrideReason.trim()
        if (requiresOverride && reason.isBlank()) {
            reportMessage(
                "An override needs a recorded reason. It is appended to the packet and flags it for review.",
            )
            return
        }
        val at = now()
        runBusy {
            repos.jobs.confirmArrival(
                jobId = jobId,
                fix = fix,
                overrideReason = reason.takeIf { requiresOverride },
                atEpochMs = at,
            ).fold(
                onSuccess = { job ->
                    _state.update {
                        it.copy(
                            job = job,
                            arrival = null,
                            lastFix = null,
                            overrideReason = "",
                            actionMessage = if (requiresOverride) {
                                "Arrival recorded with an override. The packet is flagged for review."
                            } else {
                                "Arrival confirmed inside the geofence."
                            },
                        )
                    }
                },
                onFailure = { error -> reportError(error, "Could not record your arrival.") },
            )
        }
    }

    /** Start capture on site: the repository moves the job, then the route opens capture. */
    fun startCapture() {
        val at = now()
        runBusy {
            repos.jobs.startCapture(jobId, at).fold(
                onSuccess = { job ->
                    _state.update {
                        it.copy(
                            job = job,
                            navigationTarget = JobDetailNavigation.Capture,
                        )
                    }
                },
                onFailure = { error -> reportError(error, "Could not start the capture.") },
            )
        }
    }

    /** Continuing an already-started capture needs no write; it is a navigation. */
    fun continueCapture() {
        _state.update { it.copy(navigationTarget = JobDetailNavigation.Capture) }
    }

    fun openPacket() {
        val packetId = _state.value.packetId ?: _state.value.job?.packetId
        if (packetId == null) {
            reportMessage("The packet is not sealed yet, so there is nothing to open.")
            return
        }
        _state.update { it.copy(navigationTarget = JobDetailNavigation.Packet(packetId)) }
    }

    fun consumeNavigation() {
        _state.update { it.copy(navigationTarget = null) }
    }

    fun dismissError() {
        _state.update { it.copy(errorMessage = null) }
    }

    fun dismissMessage() {
        _state.update { it.copy(actionMessage = null) }
    }

    private fun runBusy(block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, errorMessage = null, actionMessage = null) }
        viewModelScope.launch {
            try {
                block()
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    private fun reportError(error: Throwable, fallback: String = "Something went wrong. Try again.") {
        _state.update { it.copy(errorMessage = error.message?.takeIf { message -> message.isNotBlank() } ?: fallback) }
    }

    private fun reportMessage(message: String) {
        _state.update { it.copy(errorMessage = message) }
    }
}
