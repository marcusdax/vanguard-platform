package com.odasi.fieldwright.feature.payouts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.odasi.fieldwright.data.FieldwrightRepositories
import com.odasi.fieldwright.data.SyncState
import com.odasi.fieldwright.model.Balances
import com.odasi.fieldwright.model.Clock
import com.odasi.fieldwright.model.EarningsSummary
import com.odasi.fieldwright.model.FeeSchedule
import com.odasi.fieldwright.model.LedgerEntry
import com.odasi.fieldwright.model.Money
import com.odasi.fieldwright.model.Operator
import com.odasi.fieldwright.model.PayoutMode
import com.odasi.fieldwright.model.PayoutRequest
import com.odasi.fieldwright.model.SyncPhase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * State holder for Payouts.
 *
 * Money is integer cents end to end. The fee the sheet shows is never computed here: the
 * repository quotes it from `FeeEngine` so the number on the sheet is the number that will be
 * charged. This class composes display state (day groups, status labels, the ring's fraction)
 * and enforces only two guards that are UI policy, not fee policy:
 *  - you cannot request a payout with a zero available balance, and
 *  - a request is only sent for the amount that was quoted.
 */
class PayoutsViewModel(
    private val repos: FieldwrightRepositories,
    private val clock: Clock = Clock { System.currentTimeMillis() },
) : ViewModel() {

    /** The documented sprint-1 fee parameters; actual charging lives in the repository. */
    private val schedule = FeeSchedule()

    private val balances = MutableStateFlow<Balances?>(null)
    private val summary = MutableStateFlow<EarningsSummary?>(null)
    private val ledger = MutableStateFlow<List<LedgerEntry>>(emptyList())
    private val payouts = MutableStateFlow<List<PayoutRequest>>(emptyList())
    private val operator = MutableStateFlow<Operator?>(null)
    private val sync = MutableStateFlow<SyncState?>(null)
    private var receivedBalances = false

    private val _ui = MutableStateFlow(
        PayoutsUiState(
            platformFeePercentLabel = schedule.platformFeePercentLabel,
            instantFeePercentLabel = basisPointPercentLabel(schedule.instantPayoutFeeBp),
            instantFeeMinCents = schedule.instantPayoutMinFeeCents,
            instantFeeMaxCents = schedule.instantPayoutMaxFeeCents,
            mileageRateCentsPerMile = schedule.mileageRateCentsPerMile,
        ),
    )
    val ui: StateFlow<PayoutsUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            repos.earnings.observeBalances()
                .catch { failure -> reportFailure("Could not read your balance", failure) }
                .collect { value ->
                    balances.value = value
                    receivedBalances = true
                    publishDerived()
                }
        }
        viewModelScope.launch {
            repos.earnings.observeSummary()
                .catch { summary.value = null }
                .collect { value ->
                    summary.value = value
                    publishDerived()
                }
        }
        viewModelScope.launch {
            repos.earnings.observeLedger()
                .catch { ledger.value = emptyList() }
                .collect { value ->
                    ledger.value = value
                    publishDerived()
                }
        }
        viewModelScope.launch {
            repos.earnings.observePayouts()
                .catch { payouts.value = emptyList() }
                .collect { value ->
                    payouts.value = value
                    publishDerived()
                }
        }
        viewModelScope.launch {
            repos.operator.observeOperator()
                .catch { operator.value = null }
                .collect { value ->
                    operator.value = value
                    publishDerived()
                }
        }
        viewModelScope.launch {
            repos.sync.observeSyncState()
                .catch { sync.value = null }
                .collect { value ->
                    sync.value = value
                    publishDerived()
                }
        }
    }

    /**
     * Open the payout sheet for [mode]. With nothing available the sheet still opens so the
     * reason is stated where the action would be, rather than a disabled button with no
     * explanation.
     */
    fun openPayoutSheet(mode: PayoutMode) {
        val available = balances.value?.availableCents ?: 0
        _ui.update {
            it.copy(
                sheetOpen = true,
                quoteMode = mode,
                requesting = false,
                payoutError = null,
                message = null,
            )
        }
        if (available <= 0) {
            _ui.update {
                it.copy(
                    quote = null,
                    payoutError = "Nothing available yet. A sealed packet becomes payable after the requester settles it.",
                )
            }
            return
        }
        refreshQuote(mode, available)
    }

    /** Switch Instant ↔ Standard ACH inside the sheet; the fee is re-quoted, never guessed. */
    fun setQuoteMode(mode: PayoutMode) {
        if (_ui.value.quoteMode == mode) return
        val available = balances.value?.availableCents ?: 0
        _ui.update { it.copy(quoteMode = mode) }
        if (available <= 0) return
        refreshQuote(mode, available)
    }

    fun dismissPayoutSheet() {
        if (_ui.value.requesting) return
        _ui.update { it.copy(sheetOpen = false, quote = null, payoutError = null) }
    }

    /** Ask the repository for a payout of the full available balance at the quoted mode. */
    fun requestPayout() {
        if (_ui.value.requesting) return
        val balance = balances.value
        if (balance == null || balance.availableCents <= 0) {
            _ui.update {
                it.copy(
                    payoutError = "No available balance to pay out. Sealed evidence settles first — nothing has been sent.",
                )
            }
            return
        }
        val amount = balance.availableCents
        val quote = _ui.value.quote
        if (quote == null || quote.amountCents != amount) {
            _ui.update {
                it.copy(payoutError = "The fee quote is out of date. Close the sheet and try again — nothing has been sent.")
            }
            return
        }
        val mode = _ui.value.quoteMode
        val offlineAtRequest = _ui.value.offline
        _ui.update { it.copy(requesting = true, payoutError = null) }
        viewModelScope.launch {
            repos.earnings.requestPayout(amount, mode, clock.nowEpochMs()).fold(
                onSuccess = { request ->
                    _ui.update {
                        it.copy(
                            requesting = false,
                            sheetOpen = false,
                            quote = null,
                            message = if (offlineAtRequest) {
                                "Payout requested and queued — ${Money.format(request.netCents)} will send when the connection returns."
                            } else {
                                "Payout requested — ${Money.format(request.netCents)} on the way to ••${request.destinationLast4}."
                            },
                        )
                    }
                },
                onFailure = { failure ->
                    _ui.update {
                        it.copy(
                            requesting = false,
                            payoutError = "Payout not sent: ${failure.message ?: "unknown failure"}. No balance was moved.",
                        )
                    }
                },
            )
        }
    }

    fun consumeMessage() {
        _ui.update { it.copy(message = null) }
    }

    fun dismissError() {
        _ui.update { it.copy(error = null) }
    }

    fun clearPayoutError() {
        _ui.update { it.copy(payoutError = null) }
    }

    fun retry() {
        _ui.update { it.copy(error = null) }
        publishDerived()
    }

    private fun refreshQuote(mode: PayoutMode, amountCents: Int) {
        val quote = runCatching { repos.earnings.quote(amountCents, mode) }.getOrNull()
        _ui.update {
            it.copy(
                quote = quote,
                payoutError = if (quote == null) {
                    "The fee quote is unavailable right now. Nothing has been sent."
                } else {
                    null
                },
            )
        }
    }

    private fun publishDerived() {
        val currentSync = sync.value
        val offline = currentSync != null &&
            (currentSync.phase == SyncPhase.WAITING_FOR_NETWORK || currentSync.phase == SyncPhase.RETRYING)
        _ui.update { current ->
            current.copy(
                loading = !receivedBalances && current.error == null,
                balances = balances.value,
                summary = summary.value,
                operatorLabel = operator.value?.let { "${it.displayName} · ${it.tierName}" }.orEmpty(),
                destinationLast4 = operator.value?.payoutAccountLast4.orEmpty(),
                ledgerDays = groupLedgerByDay(ledger.value, clock.nowEpochMs()),
                ledgerEntryCount = ledger.value.size,
                payouts = payouts.value.sortedByDescending { it.requestedAtEpochMs }.map { it.toRow() },
                demo = ledger.value.any { it.demo },
                offline = offline,
                queued = currentSync?.queued ?: 0,
                syncLabel = PayoutsTime.syncLabel(currentSync),
            )
        }
    }

    private fun reportFailure(message: String, failure: Throwable) {
        receivedBalances = true
        _ui.update {
            it.copy(
                loading = false,
                error = "$message: ${failure.message ?: "unknown failure"}.",
            )
        }
    }
}
