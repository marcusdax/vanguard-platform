package com.odasi.fieldwright.feature.jobs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.odasi.fieldwright.data.FieldwrightRepositories
import com.odasi.fieldwright.model.GeoPoint
import com.odasi.fieldwright.model.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Jobs list: one list, three lenses (Offers · Active · Done).
 *
 * Offers keep the same 45 s discipline as Ground; the countdown is recomputed from
 * `expiresAtEpochMs` against the injected clock and expired offers are handed back to
 * dispatch. Packet ids and short hashes come from the vault repository — the job record
 * carries no hash, so nothing is invented for the Done card.
 */
class JobsViewModel(
    private val repos: FieldwrightRepositories,
    private val now: () -> Long = System::currentTimeMillis,
    private val fixProvider: (() -> GeoPoint?)? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(JobsUiState(nowEpochMs = now()))
    val state: StateFlow<JobsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repos.dispatch.observeOffers()
                .catch { error -> reportError(error) }
                .collect { offers ->
                    val at = now()
                    _state.update { it.copy(offers = offers.map { offer -> JobsOffer(offer, offer.remainingMillis(at)) }) }
                }
        }
        viewModelScope.launch {
            repos.jobs.observeActiveJobs()
                .catch { error -> reportError(error) }
                .collect { jobs -> _state.update { it.copy(activeJobs = jobs, loading = false) } }
        }
        viewModelScope.launch {
            repos.jobs.observeCompletedJobs()
                .catch { error -> reportError(error) }
                .collect { jobs -> _state.update { it.copy(completedJobs = jobs) } }
        }
        viewModelScope.launch {
            repos.vault.observePackets()
                .catch { error -> reportError(error) }
                .collect { packets ->
                    _state.update {
                        it.copy(
                            packetIds = packets.associate { packet -> packet.jobId to packet.id },
                            packetHashes = packets.mapNotNull { packet ->
                                packet.shortHash.takeIf { hash -> hash.isNotBlank() }?.let { hash ->
                                    packet.jobId to hash
                                }
                            }.toMap(),
                        )
                    }
                }
        }
    }

    fun setFilter(filter: JobsFilter) {
        _state.update { it.copy(filter = filter) }
    }

    /** Drives the countdown; the route calls this while the list is composed. */
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
            viewModelScope.launch { repos.dispatch.expireOffers(at) }
        }
    }

    fun acceptOffer(offerId: String) {
        if (_state.value.acceptingOfferId != null) return
        val at = now()
        _state.update { it.copy(acceptingOfferId = offerId, errorMessage = null, actionMessage = null) }
        viewModelScope.launch {
            repos.dispatch.acceptOffer(offerId, at).fold(
                onSuccess = { job ->
                    _state.update {
                        it.copy(
                            acceptingOfferId = null,
                            actionMessage = "Offer accepted · ${job.title}",
                        )
                    }
                },
                onFailure = { error ->
                    _state.update {
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
                    _state.update {
                        it.copy(
                            offers = it.offers.filterNot { offer -> offer.offer.id == offerId },
                            actionMessage = "Offer passed. Dispatch keeps your reason on file.",
                        )
                    }
                },
                onFailure = { error -> reportError(error, "Could not pass on that offer.") },
            )
        }
    }

    fun refresh() {
        if (_state.value.refreshing) return
        val at = now()
        val fix = fixProvider?.invoke()
            ?: (_state.value.offers.map { it.job } + _state.value.activeJobs).centroidFix(at)
            ?: DEMO_FALLBACK_FIX
        _state.update { it.copy(refreshing = true, errorMessage = null) }
        viewModelScope.launch {
            repos.dispatch.refresh(fix, at).fold(
                onSuccess = { count ->
                    _state.update {
                        it.copy(
                            refreshing = false,
                            actionMessage = when {
                                count > 0 -> "$count nearby jobs are ranked for you."
                                else -> "No new work in your ring right now. Go online to ask dispatch for a fresh ranking."
                            },
                        )
                    }
                },
                onFailure = { error ->
                    _state.update {
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

    fun dismissError() {
        _state.update { it.copy(errorMessage = null) }
    }

    fun dismissMessage() {
        _state.update { it.copy(actionMessage = null) }
    }

    private fun reportError(error: Throwable, fallback: String = "Something went wrong. Try again.") {
        _state.update { it.copy(errorMessage = error.message?.takeIf { message -> message.isNotBlank() } ?: fallback) }
    }

    private companion object {
        const val DECLINE_REASON = "Passed from Jobs"

        val DEMO_FALLBACK_FIX = GeoPoint(
            lat = 30.2691,
            lng = -97.7421,
            recordedAtEpochMs = 0L,
            accuracyMeters = 25f,
            source = GeoPoint.FixSource.SIMULATED,
        )
    }
}

/** Estimated map/dispatch centre when the app has not supplied a device fix. */
internal fun List<Job>.centroidFix(nowEpochMs: Long): GeoPoint? {
    if (isEmpty()) return null
    return GeoPoint(
        lat = sumOf { it.site.point.lat } / size.toDouble(),
        lng = sumOf { it.site.point.lng } / size.toDouble(),
        recordedAtEpochMs = nowEpochMs,
        accuracyMeters = 20f,
        source = GeoPoint.FixSource.SIMULATED,
    )
}
