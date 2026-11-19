package com.odasi.fieldwright.feature.ground

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.odasi.fieldwright.data.FieldwrightRepositories
import com.odasi.fieldwright.model.DispatchEngine
import com.odasi.fieldwright.model.GeoPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Ground: *should I go online, and what should I take next?*
 *
 * It reads repositories only (never Room, never the network directly), keeps one immutable
 * [GroundUiState], and writes through the repository contracts for availability, radius,
 * auto-accept, offer accept/decline and dispatch refresh. Offer expiry is evaluated against
 * the injected clock: the UI calls [onTick] on a timer, which keeps the ViewModel testable
 * under `kotlinx-coroutines-test` without an infinite coroutine.
 *
 * @param now the wall clock, injected so countdown/expiry tests are deterministic.
 * @param fixProvider optional real device fix; when absent the map uses an estimated
 * centroid of the returned jobs, flagged as a simulated fix.
 */
class GroundViewModel(
    private val repos: FieldwrightRepositories,
    private val now: () -> Long = System::currentTimeMillis,
    private val fixProvider: (() -> GeoPoint?)? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(GroundUiState(nowEpochMs = now()))
    val state: StateFlow<GroundUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repos.operator.observeOperator()
                .catch { error -> reportError(error) }
                .collect { operator -> update { it.copy(operator = operator) } }
        }
        viewModelScope.launch {
            repos.operator.observeAvailability()
                .catch { error -> reportError(error) }
                .collect { availability ->
                    update { it.copy(availability = availability, loading = false) }
                }
        }
        viewModelScope.launch {
            repos.dispatch.observeOffers()
                .catch { error -> reportError(error) }
                .collect { offers ->
                    val at = now()
                    update {
                        it.copy(
                            offers = offers.map { offer ->
                                GroundOffer(offer = offer, remainingMillis = offer.remainingMillis(at))
                            },
                        )
                    }
                }
        }
        viewModelScope.launch {
            repos.dispatch.observeMarket()
                .catch { error -> reportError(error) }
                .collect { market ->
                    update { it.copy(market = market, hotZones = market.hotZones) }
                }
        }
        viewModelScope.launch {
            repos.jobs.observeActiveJobs()
                .catch { error -> reportError(error) }
                .collect { jobs -> update { it.copy(activeJobs = jobs) } }
        }
        viewModelScope.launch {
            repos.sync.observeSyncState()
                .catch { error -> reportError(error) }
                .collect { sync -> update { it.copy(sync = sync) } }
        }
        viewModelScope.launch {
            repos.earnings.observeSummary()
                .catch { error -> reportError(error) }
                .collect { summary -> update { it.copy(todayEarningsCents = summary.todayCents) } }
        }
    }

    /** Drives the 45 s countdown; the route calls this while the screen is resumed. */
    fun onTick() {
        val at = now()
        var anyExpired = false
        _state.update { current ->
            val refreshed = current.offers.map { offer ->
                val remaining = offer.offer.remainingMillis(at)
                if (remaining <= 0L) anyExpired = true
                offer.copy(remainingMillis = remaining)
            }
            current.copy(offers = refreshed, nowEpochMs = at)
        }
        if (anyExpired) {
            viewModelScope.launch {
                repos.dispatch.expireOffers(at)
            }
        }
    }

    fun toggleOnline() {
        val target = !_state.value.availability.isOnline
        val at = now()
        viewModelScope.launch {
            repos.operator.setOnline(target, at).fold(
                onSuccess = { availability ->
                    update {
                        it.copy(
                            availability = availability,
                            errorMessage = null,
                            actionMessage = if (target) {
                                "You are on the clock. Dispatch is ranking nearby work."
                            } else {
                                "You are off the clock. Dispatch has stopped sending offers."
                            },
                        )
                    }
                },
                onFailure = { error -> reportError(error, "Could not change availability.") },
            )
        }
    }

    fun setRadius(radiusMeters: Int) {
        val clamped = radiusMeters.coerceIn(0, DispatchEngine.MAX_OFFER_RADIUS_METERS)
        viewModelScope.launch {
            repos.operator.updateRadius(clamped).fold(
                onSuccess = { availability -> update { it.copy(availability = availability) } },
                onFailure = { error -> reportError(error, "Could not change your radius.") },
            )
        }
    }

    fun setAutoAccept(enabled: Boolean) {
        val policy = _state.value.availability.autoAccept.copy(enabled = enabled)
        viewModelScope.launch {
            repos.operator.updateAutoAccept(policy).fold(
                onSuccess = { availability ->
                    update {
                        it.copy(
                            availability = availability,
                            actionMessage = if (enabled) {
                                "Auto-accept is on. Every automatic accept is recorded with a reason."
                            } else {
                                "Auto-accept is off. You will see every offer."
                            },
                        )
                    }
                },
                onFailure = { error -> reportError(error, "Could not save the auto-accept policy.") },
            )
        }
    }

    fun refresh() {
        if (_state.value.refreshing) return
        val at = now()
        val fix = _state.value.operatorFix ?: _state.value.mapJobs.centroidFix(at) ?: DEMO_FALLBACK_FIX
        _state.update { it.copy(refreshing = true, errorMessage = null) }
        viewModelScope.launch {
            repos.dispatch.refresh(fix, at).fold(
                onSuccess = { count ->
                    update {
                        it.copy(
                            refreshing = false,
                            actionMessage = when {
                                !it.isOnline -> "You are off the clock. Go online and dispatch will rank nearby work."
                                count > 0 -> "$count nearby jobs are ranked for you."
                                else -> "No new work in your ring right now."
                            },
                        )
                    }
                },
                onFailure = { error ->
                    update {
                        it.copy(
                            refreshing = false,
                            errorMessage = error.message
                                ?: "Dispatch did not answer. Check your connection and retry.",
                        )
                    }
                },
            )
        }
    }

    /**
     * Accept an offer. The repository owns the write and the idempotency; the screen only
     * reports the outcome. A failure is inline (the offer expired, was taken, or the server
     * refused) and never silently marks a job accepted.
     */
    fun acceptOffer(offerId: String) {
        if (_state.value.acceptingOfferId != null) return
        val at = now()
        _state.update { it.copy(acceptingOfferId = offerId, errorMessage = null, actionMessage = null) }
        viewModelScope.launch {
            repos.dispatch.acceptOffer(offerId, at).fold(
                onSuccess = { job ->
                    update {
                        it.copy(
                            acceptingOfferId = null,
                            selectedPinJobId = null,
                            actionMessage = "Offer accepted · ${job.title}",
                        )
                    }
                },
                onFailure = { error ->
                    update {
                        it.copy(
                            acceptingOfferId = null,
                            errorMessage = error.message
                                ?: "That offer could not be accepted. It may have expired or been taken.",
                        )
                    }
                },
            )
        }
    }

    fun declineOffer(offerId: String) {
        val at = now()
        viewModelScope.launch {
            repos.dispatch.declineOffer(offerId, DECLINE_REASON, at).fold(
                onSuccess = {
                    update {
                        it.copy(
                            selectedPinJobId = null,
                            offers = it.offers.filterNot { offer -> offer.offer.id == offerId },
                            actionMessage = "Offer passed. Dispatch keeps your reasons on file.",
                        )
                    }
                },
                onFailure = { error -> reportError(error, "Could not pass on that offer.") },
            )
        }
    }

    fun selectPin(jobId: String?) {
        _state.update { it.copy(selectedPinJobId = jobId) }
    }

    fun retrySync() {
        viewModelScope.launch {
            runCatching { repos.sync.flushNow() }
                .onSuccess { report ->
                    update { it.copy(actionMessage = report.detail) }
                }
                .onFailure { error -> reportError(error, "Sync retry failed.") }
        }
    }

    fun dismissMessage() {
        _state.update { it.copy(actionMessage = null) }
    }

    fun dismissError() {
        _state.update { it.copy(errorMessage = null) }
    }

    private fun reportError(error: Throwable, fallback: String = "Something went wrong. Try again.") {
        _state.update { it.copy(errorMessage = error.message?.takeIf { message -> message.isNotBlank() } ?: fallback) }
    }

    /**
     * Applies a state change and then re-derives the map fix and demo flag, so derived data
     * can never go stale relative to the repositories.
     */
    private fun update(block: (GroundUiState) -> GroundUiState) {
        _state.update { current ->
            val next = block(current)
            next.copy(
                operatorFix = fixProvider?.invoke()
                    ?: next.operatorFix
                    ?: next.mapJobs.centroidFix(next.nowEpochMs),
                demo = next.offers.any { it.job.demo } || next.activeJobs.any { it.demo },
            )
        }
    }

    private companion object {
        /** A human reason is mandatory for a pass (§5.2). */
        const val DECLINE_REASON = "Passed from Ground"

        /** Creekside · MUD 14 stand-in until the app layer injects a real fix. */
        val DEMO_FALLBACK_FIX = GeoPoint(
            lat = 30.2691,
            lng = -97.7421,
            recordedAtEpochMs = 0L,
            accuracyMeters = ESTIMATED_FIX_ACCURACY_METERS,
            source = GeoPoint.FixSource.SIMULATED,
        )
    }
}
