package com.odasi.fieldwright.feature.vault

import com.odasi.fieldwright.model.BadgeState
import com.odasi.fieldwright.model.ChainVerification
import com.odasi.fieldwright.model.EvidencePacket
import com.odasi.fieldwright.model.EvidenceTier
import com.odasi.fieldwright.model.JobCatalog
import com.odasi.fieldwright.model.LedgerEntry
import com.odasi.fieldwright.model.LedgerKind
import com.odasi.fieldwright.model.LedgerStatus
import com.odasi.fieldwright.model.SealEvaluation
import com.odasi.fieldwright.model.StepResult

/**
 * UI-facing shapes for the Vault. They are deliberately derived, read-only projections of
 * [EvidencePacket]: every decision (seal state, tier, gate outcome, badge state) is carried in
 * from `core:model` and is never recomputed here. The card only composes copy and joins the
 * ledger for the payout it shows.
 */

/** The three honest slices of the sample book. */
enum class VaultFilter(val label: String) {
    ALL("All"),
    SEALED("Sealed"),
    ATTENTION("Needs attention"),
    ;

    fun matches(card: PacketCard): Boolean = when (this) {
        ALL -> true
        SEALED -> card.isSealed
        ATTENTION -> card.needsAttention
    }
}

/** Verification is a named state, never a spinner without a result. */
enum class ChainHealthState { UNVERIFIED, VERIFYING, VERIFIED, FAILED }

data class ChainHealth(
    val state: ChainHealthState = ChainHealthState.UNVERIFIED,
    val entries: Int = 0,
    val headHash: String = "",
    val brokenAtSeq: Int? = null,
    val detail: String = "",
    val checkedAtEpochMs: Long? = null,
    /** True when the row came from the whole-vault check rather than one packet. */
    val wholeVault: Boolean = true,
) {
    val verified: Boolean get() = state == ChainHealthState.VERIFIED

    companion object {
        fun of(verification: ChainVerification, checkedAtEpochMs: Long, wholeVault: Boolean): ChainHealth =
            ChainHealth(
                state = if (verification.valid) ChainHealthState.VERIFIED else ChainHealthState.FAILED,
                entries = verification.entries,
                headHash = verification.headHash,
                brokenAtSeq = verification.brokenAtSeq,
                detail = verification.detail,
                checkedAtEpochMs = checkedAtEpochMs,
                wholeVault = wholeVault,
            )

        fun failed(detail: String, checkedAtEpochMs: Long, wholeVault: Boolean): ChainHealth =
            ChainHealth(
                state = ChainHealthState.FAILED,
                detail = detail,
                checkedAtEpochMs = checkedAtEpochMs,
                wholeVault = wholeVault,
            )
    }
}

data class ChecklistLine(val label: String, val result: StepResult)

/**
 * What the ledger says a packet has earned. "Paid" is only claimed when the earning entries
 * are PAID; AVAILABLE means the money is earned and can be paid out, not that it has left.
 */
enum class PayoutLabel { PAID, EARNED, PENDING }

internal fun payoutLabelText(label: PayoutLabel): String = when (label) {
    PayoutLabel.PAID -> "Paid"
    PayoutLabel.EARNED -> "Earned"
    PayoutLabel.PENDING -> "Pending"
}

/**
 * One packet as the list card shows it. The card copy (overline, serif title, requester ·
 * status · payout, consent line, checklist lines, fingerprint) is the Fieldwright idiom from
 * the design contract; this type is the whole vocabulary the card needs.
 */
data class PacketCard(
    val packetId: String,
    val title: String,
    val requesterLabel: String,
    val sealLabel: String,
    val sealState: com.odasi.fieldwright.model.SealState,
    val isSealed: Boolean,
    val payoutCents: Int?,
    val payoutLabel: PayoutLabel?,
    val consentLine: String,
    val checklistLines: List<ChecklistLine>,
    val recordCount: Int,
    val lastRecordAtEpochMs: Long?,
    val shortHash: String,
    val demo: Boolean,
    val needsAttention: Boolean,
    val jobId: String,
    val sortEpochMs: Long,
)

/** One download request, ready for the route to write to the cache and hand to the share sheet. */
data class PacketShare(
    val packetId: String,
    val fileName: String,
    val canonicalJson: String,
    val custodyText: String,
    val chainHead: String,
    val recordCount: Int,
    val demo: Boolean,
)

data class VaultUiState(
    val loading: Boolean = true,
    val filter: VaultFilter = VaultFilter.ALL,
    val cards: List<PacketCard> = emptyList(),
    val totalCards: Int = 0,
    val demo: Boolean = false,
    val chain: ChainHealth = ChainHealth(),
    val verifying: Boolean = false,
    val exportingPacketId: String? = null,
    val share: PacketShare? = null,
    val error: String? = null,
    val message: String? = null,
) {
    val isEmpty: Boolean get() = !loading && totalCards == 0
    val isFilteredEmpty: Boolean get() = !loading && totalCards > 0 && cards.isEmpty()
}

data class PacketDetailUiState(
    val loading: Boolean = true,
    val packet: EvidencePacket? = null,
    val sealEvaluation: SealEvaluation? = null,
    val chain: ChainHealth = ChainHealth(),
    val verifying: Boolean = false,
    val payoutCents: Int? = null,
    val payoutLabel: PayoutLabel? = null,
    val packetLedger: List<LedgerEntry> = emptyList(),
    val exporting: Boolean = false,
    val share: PacketShare? = null,
    val correctionOpen: Boolean = false,
    val correctionNote: String = "",
    val appendingCorrection: Boolean = false,
    val correctionError: String? = null,
    val error: String? = null,
    val message: String? = null,
) {
    val demo: Boolean get() = packet?.demo == true
}

// ---------------------------------------------------------------------------------------------
// Projections. These read model state and join the ledger; they never re-decide a domain rule.
// ---------------------------------------------------------------------------------------------

/**
 * The ledger is the only truthful source for what a packet paid, because money and evidence
 * move independently. Only positive earning, bonus, and tip lines count toward the packet's
 * payout; instant fees and reversals are shown as their own signed lines on the detail screen
 * rather than silently reducing the card figure. No ledger entry means no invented number.
 */
private val PACKET_EARNING_KINDS = setOf(LedgerKind.EARNING, LedgerKind.BONUS, LedgerKind.TIP)

internal fun packetEarningEntries(packetId: String, ledger: List<LedgerEntry>): List<LedgerEntry> =
    ledger.filter { it.packetId == packetId && it.kind in PACKET_EARNING_KINDS && it.amountCents > 0 }

/** A packet's ledger-derived payout, already resolved once per emission. */
data class PacketPayout(val cents: Int, val label: PayoutLabel)

/**
 * Resolve every packet's payout in one pass over the ledger, grouped by packet id. The Vault
 * list is a scroll surface; filtering the whole ledger per card inside composition would put
 * an O(cards × ledger) scan in the frame budget for no reason.
 */
internal fun payoutIndex(ledger: List<LedgerEntry>): Map<String, PacketPayout> {
    val grouped = ledger
        .filter { it.packetId != null && it.kind in PACKET_EARNING_KINDS && it.amountCents > 0 }
        .groupBy { it.packetId ?: "" }
    val result = LinkedHashMap<String, PacketPayout>(grouped.size)
    for ((packetId, entries) in grouped) {
        if (packetId.isEmpty()) continue
        val cents = entries.sumOf { it.amountCents }
        if (cents <= 0) continue
        val statuses = entries.map { it.status }.toSet()
        val label = when {
            statuses.all { it == LedgerStatus.PAID } -> PayoutLabel.PAID
            statuses.all { it == LedgerStatus.PAID || it == LedgerStatus.AVAILABLE } -> PayoutLabel.EARNED
            else -> PayoutLabel.PENDING
        }
        result[packetId] = PacketPayout(cents, label)
    }
    return result
}

internal fun packetPayoutCents(packetId: String, ledger: List<LedgerEntry>): Int? =
    packetEarningEntries(packetId, ledger).sumOf { it.amountCents }.takeIf { it > 0 }

internal fun packetPayoutLabel(packetId: String, ledger: List<LedgerEntry>): PayoutLabel? {
    val entries = packetEarningEntries(packetId, ledger)
    if (entries.isEmpty()) return null
    val statuses = entries.map { it.status }.toSet()
    return when {
        statuses.all { it == LedgerStatus.PAID } -> PayoutLabel.PAID
        statuses.all { it == LedgerStatus.PAID || it == LedgerStatus.AVAILABLE } -> PayoutLabel.EARNED
        else -> PayoutLabel.PENDING
    }
}

/** Everything the ledger records against this packet, signed, including fees and reversals. */
internal fun packetLedgerLines(packetId: String, ledger: List<LedgerEntry>): List<LedgerEntry> =
    ledger.filter { it.packetId == packetId }.sortedByDescending { it.atEpochMs }

internal fun vaultLedgerStatusLabel(status: LedgerStatus): String = when (status) {
    LedgerStatus.PENDING -> "Pending"
    LedgerStatus.AVAILABLE -> "Available"
    LedgerStatus.PAID -> "Paid"
    LedgerStatus.REVERSED -> "Reversed"
    LedgerStatus.FAILED -> "Failed"
}

/** "Consent: Chris Adeyemi. Roof surfaces, slopes and penetrations." — or an honest absence. */
internal fun EvidencePacket.consentLine(): String =
    consent?.let { consent ->
        val scope = consent.scopeText.trim().trimEnd('.')
        "Consent: ${consent.name}. $scope."
    } ?: "Consent: none on file — this packet cannot be sealed."

/** Checklist lines preserve the job family's own wording, keyed by the stored results. */
internal fun EvidencePacket.checklistLines(): List<ChecklistLine> {
    val steps = JobCatalog.checklistFor(jobType).associateBy { it.key }
    return checklist.map { result ->
        ChecklistLine(
            label = steps[result.key]?.label ?: result.key.replaceFirstChar { it.uppercase() },
            result = result.result,
        )
    }
}

/**
 * "Needs attention" is composed from the engine's own outputs: an unearned seal, a required
 * badge that is not verified, a flagged badge, or a blocking gate that failed. It is a filter,
 * not a diagnosis.
 */
internal fun EvidencePacket.needsAttention(): Boolean {
    if (!isSealed) return true
    if (badges.any { it.state == BadgeState.FLAGGED }) return true
    if (badges.any { it.required && it.state != BadgeState.VERIFIED && it.state != BadgeState.WAIVED }) return true
    return blockedGates.isNotEmpty()
}

internal fun EvidencePacket.toCard(payout: PacketPayout?): PacketCard {
    val sortedRecords = records.sortedBy { it.seq }
    return PacketCard(
        packetId = id,
        title = title,
        requesterLabel = requesterLabel,
        sealLabel = seal.label,
        sealState = seal,
        isSealed = isSealed,
        payoutCents = payout?.cents,
        payoutLabel = payout?.label,
        consentLine = consentLine(),
        checklistLines = checklistLines(),
        recordCount = recordCount,
        lastRecordAtEpochMs = sortedRecords.lastOrNull()?.atEpochMs,
        shortHash = shortHash,
        demo = demo,
        needsAttention = needsAttention(),
        jobId = jobId,
        sortEpochMs = sealedAtEpochMs ?: createdAtEpochMs,
    )
}

/** Convenience for previews/tests; production call sites pass a precomputed [payoutIndex]. */
internal fun EvidencePacket.toCard(ledger: List<LedgerEntry>): PacketCard =
    toCard(payoutIndex(ledger)[id])

/** Tier copy is the engine's named state and meaning; never a percentage. */
internal fun EvidenceTier.meaningLine(): String = "$label — $meaning"
