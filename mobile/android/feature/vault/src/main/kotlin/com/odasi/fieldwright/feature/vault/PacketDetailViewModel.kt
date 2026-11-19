package com.odasi.fieldwright.feature.vault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.odasi.fieldwright.data.FieldwrightRepositories
import com.odasi.fieldwright.model.ChainVerification
import com.odasi.fieldwright.model.Clock
import com.odasi.fieldwright.model.EvidencePacket
import com.odasi.fieldwright.model.LedgerEntry
import com.odasi.fieldwright.model.SealEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * State holder for one packet: the sealed artefact, its append-only record chain, its derived
 * seal evaluation, and the two writes a worker is allowed to make from here — export and a
 * correction record. Nothing here edits or deletes evidence; a correction appends.
 */
class PacketDetailViewModel(
    private val repos: FieldwrightRepositories,
    private val packetId: String,
    private val clock: Clock = Clock { System.currentTimeMillis() },
) : ViewModel() {

    private val _ui = MutableStateFlow(PacketDetailUiState())
    val ui: StateFlow<PacketDetailUiState> = _ui.asStateFlow()

    private var packet: EvidencePacket? = null
    private var ledger: List<LedgerEntry> = emptyList()
    private var chainValid: Boolean? = null
    private var started = false

    init {
        viewModelScope.launch {
            repos.vault.observePacket(packetId)
                .catch { failure ->
                    _ui.update {
                        it.copy(
                            loading = false,
                            error = "Could not read this packet: ${failure.message ?: "unknown failure"}.",
                        )
                    }
                }
                .collect { loaded ->
                    if (loaded == null) {
                        _ui.update { it.copy(loading = false, packet = null) }
                        return@collect
                    }
                    packet = loaded
                    _ui.update {
                        it.copy(
                            loading = false,
                            packet = loaded,
                            payoutCents = packetPayoutCents(loaded.id, ledger),
                            payoutLabel = packetPayoutLabel(loaded.id, ledger),
                            packetLedger = packetLedgerLines(loaded.id, ledger),
                        )
                    }
                    // Verify once on open; the banner can be re-run on demand.
                    if (!started) {
                        started = true
                        verifyChain()
                    } else {
                        evaluateSeal()
                    }
                }
        }
        viewModelScope.launch {
            repos.earnings.observeLedger()
                .catch { ledger = emptyList() }
                .collect { entries ->
                    ledger = entries
                    val current = packet ?: return@collect
                    _ui.update {
                        it.copy(
                            payoutCents = packetPayoutCents(current.id, entries),
                            payoutLabel = packetPayoutLabel(current.id, entries),
                            packetLedger = packetLedgerLines(current.id, entries),
                        )
                    }
                }
        }
    }

    /**
     * Re-walk the packet's chain from genesis. The seal evaluation depends on this result, so
     * it is re-run whenever the chain verdict changes — a sealed packet whose chain no longer
     * verifies must not keep showing a clean seal.
     */
    fun verifyChain() {
        if (_ui.value.verifying) return
        _ui.update { it.copy(verifying = true, chain = it.chain.copy(state = ChainHealthState.VERIFYING)) }
        viewModelScope.launch {
            val checkedAt = clock.nowEpochMs()
            val result: Result<ChainVerification> = repos.vault.verifyChain(packetId)
            result.fold(
                onSuccess = { verification ->
                    chainValid = verification.valid
                    _ui.update {
                        it.copy(
                            verifying = false,
                            chain = ChainHealth.of(verification, checkedAt, wholeVault = false),
                        )
                    }
                },
                onFailure = { failure ->
                    chainValid = false
                    _ui.update {
                        it.copy(
                            verifying = false,
                            chain = ChainHealth.failed(
                                detail = "The chain could not be verified: ${failure.message ?: "unknown failure"}.",
                                checkedAtEpochMs = checkedAt,
                                wholeVault = false,
                            ),
                        )
                    }
                },
            )
            evaluateSeal()
        }
    }

    /** Primary action: export the packet, then the route writes it to cache and shares it. */
    fun exportPacket() {
        if (_ui.value.exporting) return
        _ui.update { it.copy(exporting = true, error = null, message = null) }
        viewModelScope.launch {
            val current = packet
            repos.vault.exportPacket(packetId).fold(
                onSuccess = { export ->
                    _ui.update {
                        it.copy(
                            exporting = false,
                            share = PacketShare(
                                packetId = packetId,
                                fileName = export.fileName,
                                canonicalJson = export.canonicalJson,
                                custodyText = export.custodyText,
                                chainHead = export.chainHead,
                                recordCount = export.recordCount,
                                demo = current?.demo == true,
                            ),
                            message = if (current?.demo == true) {
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
                            exporting = false,
                            error = "Could not export packet: ${failure.message ?: "unknown failure"}.",
                        )
                    }
                },
            )
        }
    }

    fun openCorrection() {
        _ui.update { it.copy(correctionOpen = true, correctionError = null) }
    }

    fun dismissCorrection() {
        if (_ui.value.appendingCorrection) return
        _ui.update { it.copy(correctionOpen = false, correctionNote = "", correctionError = null) }
    }

    fun updateCorrectionNote(note: String) {
        _ui.update { it.copy(correctionNote = note, correctionError = null) }
    }

    /**
     * Corrections are records: the packet's history keeps both the original and this note.
     * The screen states that in the dialog before the button, so nobody expects an edit.
     */
    fun appendCorrection() {
        if (_ui.value.appendingCorrection) return
        val note = _ui.value.correctionNote.trim()
        if (note.isBlank()) {
            _ui.update { it.copy(correctionError = "A correction needs a note describing what changed.") }
            return
        }
        _ui.update { it.copy(appendingCorrection = true, correctionError = null) }
        viewModelScope.launch {
            repos.vault.appendCorrection(packetId, note, clock.nowEpochMs()).fold(
                onSuccess = { updated ->
                    _ui.update {
                        it.copy(
                            appendingCorrection = false,
                            correctionOpen = false,
                            correctionNote = "",
                            packet = updated,
                            message = "Correction appended as record ${updated.recordCount}. Nothing was edited or deleted.",
                        )
                    }
                    evaluateSeal()
                },
                onFailure = { failure ->
                    _ui.update {
                        it.copy(
                            appendingCorrection = false,
                            correctionError = "Correction not appended: ${failure.message ?: "unknown failure"}.",
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

    private fun evaluateSeal() {
        val current = packet ?: return
        val verified = chainValid ?: return
        val included = current.merkleLeaf != null && current.merkleProof.isNotEmpty()
        // The engine decides; if it is unavailable the screen simply omits the reasons rather
        // than inventing them.
        val evaluation = runCatching {
            SealEngine.evaluate(current, verified, included, clock.nowEpochMs())
        }.getOrNull()
        _ui.update { it.copy(sealEvaluation = evaluation) }
    }
}
