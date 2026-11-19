package com.odasi.fieldwright.feature.vault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.odasi.fieldwright.data.FieldwrightRepositories
import com.odasi.fieldwright.model.ChainVerification
import com.odasi.fieldwright.model.Clock
import com.odasi.fieldwright.model.EvidencePacket
import com.odasi.fieldwright.model.LedgerEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * State holder for the Vault list.
 *
 * The ViewModel owns *presentation* state only: filtering, the chain-health banner, the
 * download request that the route hands to the share sheet. Every evidence decision (seal,
 * tier, gate, badge) arrives from `core:model` through the repository; the ledger is joined
 * here solely to show what a packet actually paid, because money and evidence move
 * independently.
 *
 * Chain verification is on demand (and once when the screen opens): a chain result is a claim
 * with a timestamp, so the banner always shows when it was checked.
 */
class VaultViewModel(
    private val repos: FieldwrightRepositories,
    private val clock: Clock = Clock { System.currentTimeMillis() },
) : ViewModel() {

    private val packets = MutableStateFlow<List<EvidencePacket>>(emptyList())
    private val ledger = MutableStateFlow<List<LedgerEntry>>(emptyList())
    private val filter = MutableStateFlow(VaultFilter.ALL)
    private val chain = MutableStateFlow(ChainHealth())
    private var receivedPackets = false

    private val _ui = MutableStateFlow(VaultUiState())
    val ui: StateFlow<VaultUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            repos.vault.observePackets()
                .catch { failure -> reportFailure("Could not read the vault", failure) }
                .collect { list ->
                    packets.value = list
                    receivedPackets = true
                    rebuild()
                }
        }
        viewModelScope.launch {
            // A ledger read failure must not hide packets: money is shown only when known.
            repos.earnings.observeLedger()
                .catch { ledger.value = emptyList() }
                .collect { list ->
                    ledger.value = list
                    rebuild()
                }
        }
        // One check when the vault opens, then on demand. The banner always carries its time.
        verifyChain()
    }

    fun setFilter(next: VaultFilter) {
        if (filter.value == next) return
        filter.value = next
        rebuild()
    }

    /**
     * Verify the vault's chains. `packetId == null` asks the repository to walk the whole
     * sample book; the banner reports the head hash and the first broken sequence, if any.
     */
    fun verifyChain() {
        if (chain.value.state == ChainHealthState.VERIFYING) return
        chain.value = chain.value.copy(state = ChainHealthState.VERIFYING, detail = "")
        rebuild()
        viewModelScope.launch {
            val checkedAt = clock.nowEpochMs()
            applyVerification(repos.vault.verifyChain(null), checkedAt)
        }
    }

    private fun applyVerification(result: Result<ChainVerification>, checkedAt: Long) {
        chain.value = result.fold(
            onSuccess = { verification -> ChainHealth.of(verification, checkedAt, wholeVault = true) },
            onFailure = { failure ->
                ChainHealth.failed(
                    detail = "The chain could not be verified: ${failure.message ?: "unknown failure"}.",
                    checkedAtEpochMs = checkedAt,
                    wholeVault = true,
                )
            },
        )
        rebuild()
    }

    /** Primary action on a packet card: export, then the route opens the share sheet. */
    fun downloadPacket(packetId: String) {
        if (_ui.value.exportingPacketId != null) return
        _ui.update { it.copy(exportingPacketId = packetId, error = null, message = null) }
        viewModelScope.launch {
            val packet = packets.value.firstOrNull { it.id == packetId }
            val result = repos.vault.exportPacket(packetId)
            result.fold(
                onSuccess = { export ->
                    _ui.update {
                        it.copy(
                            exportingPacketId = null,
                            share = PacketShare(
                                packetId = packetId,
                                fileName = export.fileName,
                                canonicalJson = export.canonicalJson,
                                custodyText = export.custodyText,
                                chainHead = export.chainHead,
                                recordCount = export.recordCount,
                                demo = packet?.demo == true,
                            ),
                            message = if (packet?.demo == true) {
                                "Sample packet saved to the cache — demonstration data, not the log."
                            } else {
                                "Packet ${export.fileName} saved to the cache."
                            },
                        )
                    }
                },
                onFailure = { failure ->
                    _ui.update {
                        it.copy(
                            exportingPacketId = null,
                            error = "Could not export packet: ${failure.message ?: "unknown failure"}.",
                        )
                    }
                },
            )
        }
    }

    fun consumeShare() {
        _ui.update { it.copy(share = null) }
    }

    fun consumeMessage() {
        _ui.update { it.copy(message = null) }
    }

    fun dismissError() {
        _ui.update { it.copy(error = null) }
    }

    /** Retry the whole-vault chain check after a failure. */
    fun retry() {
        _ui.update { it.copy(error = null) }
        chain.value = ChainHealth()
        verifyChain()
    }

    private fun rebuild() {
        // One pass over the ledger, then O(1) payout lookup per card. The list is a scroll
        // surface; per-card ledger scans would land in the frame budget.
        val payoutsByPacket = payoutIndex(ledger.value)
        val cards = packets.value
            .map { it.toCard(payoutsByPacket[it.id]) }
            .sortedWith(compareByDescending<PacketCard> { it.sortEpochMs }.thenBy { it.packetId })
        val visible = cards.filter { filter.value.matches(it) }
        _ui.update { current ->
            current.copy(
                loading = !receivedPackets && current.error == null,
                filter = filter.value,
                cards = visible,
                totalCards = cards.size,
                demo = packets.value.any { it.demo },
                chain = chain.value,
                verifying = chain.value.state == ChainHealthState.VERIFYING,
            )
        }
    }

    private fun reportFailure(message: String, failure: Throwable) {
        receivedPackets = true
        _ui.update {
            it.copy(
                loading = false,
                error = "$message: ${failure.message ?: "unknown failure"}.",
            )
        }
    }
}
