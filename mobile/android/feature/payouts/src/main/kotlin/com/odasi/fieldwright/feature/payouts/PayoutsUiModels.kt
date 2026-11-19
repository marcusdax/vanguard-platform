package com.odasi.fieldwright.feature.payouts

import com.odasi.fieldwright.model.Balances
import com.odasi.fieldwright.model.EarningsSummary
import com.odasi.fieldwright.model.LedgerEntry
import com.odasi.fieldwright.model.LedgerStatus
import com.odasi.fieldwright.model.PayoutMode
import com.odasi.fieldwright.model.PayoutQuote
import com.odasi.fieldwright.model.PayoutRequest
import com.odasi.fieldwright.model.PayoutStatus
import java.time.Instant
import java.time.ZoneId

/**
 * One ledger line. `amountCents` stays signed exactly as the ledger stores it: earnings
 * positive, fees and reversals negative. The screen renders it with the canonical money
 * component so the sign and the grouping are never re-invented here.
 */
data class LedgerRow(
    val id: String,
    val kindLabel: String,
    val description: String,
    val amountCents: Int,
    val status: LedgerStatus,
    val statusLabel: String,
    val tone: MoneyTone,
    val atEpochMs: Long,
)

data class LedgerDay(
    val dayLabel: String,
    val sortEpochMs: Long,
    val totalCents: Int,
    val rows: List<LedgerRow>,
)

data class PayoutRow(
    val id: String,
    val amountCents: Int,
    val feeCents: Int,
    val netCents: Int,
    val mode: PayoutMode,
    val modeLabel: String,
    val status: PayoutStatus,
    val statusLabel: String,
    val tone: MoneyTone,
    val requestedAtEpochMs: Long,
    val completedAtEpochMs: Long?,
    val destinationLast4: String,
    val failureReason: String?,
)

/** Semantic tone only; the screen maps it onto the design system's pill tones. */
enum class MoneyTone { OLIVE, AMBER, FLAG, NEUTRAL, OXIDE }

data class PayoutsUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val message: String? = null,
    val operatorLabel: String = "",
    val demo: Boolean = false,
    val balances: Balances? = null,
    val summary: EarningsSummary? = null,
    val ledgerDays: List<LedgerDay> = emptyList(),
    val ledgerEntryCount: Int = 0,
    val payouts: List<PayoutRow> = emptyList(),
    val destinationLast4: String = "",
    val quote: PayoutQuote? = null,
    val quoteMode: PayoutMode = PayoutMode.INSTANT,
    val sheetOpen: Boolean = false,
    val requesting: Boolean = false,
    val payoutError: String? = null,
    val offline: Boolean = false,
    val queued: Int = 0,
    val syncLabel: String? = null,
    // Disclosure constants, read from core:model's FeeSchedule defaults so the copy cannot
    // drift from the engine's parameters.
    val platformFeePercentLabel: String = "",
    val instantFeePercentLabel: String = "",
    val instantFeeMinCents: Int = 0,
    val instantFeeMaxCents: Int = 0,
    val mileageRateCentsPerMile: Int = 0,
) {
    val hasLedger: Boolean get() = ledgerEntryCount > 0
    val hasPayouts: Boolean get() = payouts.isNotEmpty()
    val hasBalance: Boolean get() = (balances?.availableCents ?: 0) > 0
    val weeklyGoalProgress: Float
        get() = balances?.let { balance ->
            if (balance.weeklyGoalCents <= 0) 0f
            else ((summary?.weekCents ?: 0).toFloat() / balance.weeklyGoalCents).coerceIn(0f, 1f)
        } ?: 0f
}

internal fun ledgerStatusLabel(status: LedgerStatus): String = when (status) {
    LedgerStatus.PENDING -> "Pending"
    LedgerStatus.AVAILABLE -> "Available"
    LedgerStatus.PAID -> "Paid"
    LedgerStatus.REVERSED -> "Reversed"
    LedgerStatus.FAILED -> "Failed"
}

internal fun ledgerStatusTone(status: LedgerStatus): MoneyTone = when (status) {
    LedgerStatus.PENDING -> MoneyTone.AMBER
    LedgerStatus.AVAILABLE -> MoneyTone.OLIVE
    LedgerStatus.PAID -> MoneyTone.OLIVE
    LedgerStatus.REVERSED -> MoneyTone.FLAG
    LedgerStatus.FAILED -> MoneyTone.FLAG
}

internal fun payoutStatusLabel(status: PayoutStatus): String = when (status) {
    PayoutStatus.REQUESTED -> "Requested"
    PayoutStatus.PROCESSING -> "Processing"
    PayoutStatus.PAID -> "Paid"
    PayoutStatus.FAILED -> "Failed"
    PayoutStatus.CANCELLED -> "Cancelled"
}

internal fun payoutStatusTone(status: PayoutStatus): MoneyTone = when (status) {
    PayoutStatus.REQUESTED, PayoutStatus.PROCESSING -> MoneyTone.AMBER
    PayoutStatus.PAID -> MoneyTone.OLIVE
    PayoutStatus.FAILED -> MoneyTone.FLAG
    PayoutStatus.CANCELLED -> MoneyTone.NEUTRAL
}

/** Ledger lines grouped into the days they happened, newest first. */
internal fun groupLedgerByDay(
    entries: List<LedgerEntry>,
    nowEpochMs: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): List<LedgerDay> =
    entries
        .sortedByDescending { it.atEpochMs }
        .groupBy { entry -> Instant.ofEpochMilli(entry.atEpochMs).atZone(zone).toLocalDate() }
        .entries
        .sortedByDescending { (day, _) -> day }
        .map { (day, dayEntries) ->
            val sorted = dayEntries.sortedByDescending { it.atEpochMs }
            LedgerDay(
                dayLabel = PayoutsTime.dayLabel(sorted.first().atEpochMs, nowEpochMs, zone),
                sortEpochMs = day.atStartOfDay(zone).toInstant().toEpochMilli(),
                totalCents = sorted.sumOf { it.amountCents },
                rows = sorted.map { entry ->
                    LedgerRow(
                        id = entry.id,
                        kindLabel = entry.kind.label,
                        description = entry.description,
                        amountCents = entry.amountCents,
                        status = entry.status,
                        statusLabel = ledgerStatusLabel(entry.status),
                        tone = ledgerStatusTone(entry.status),
                        atEpochMs = entry.atEpochMs,
                    )
                },
            )
        }

internal fun PayoutRequest.toRow(): PayoutRow =
    PayoutRow(
        id = id,
        amountCents = amountCents,
        feeCents = feeCents,
        netCents = netCents,
        mode = mode,
        modeLabel = mode.label,
        status = status,
        statusLabel = payoutStatusLabel(status),
        tone = payoutStatusTone(status),
        requestedAtEpochMs = requestedAtEpochMs,
        completedAtEpochMs = completedAtEpochMs,
        destinationLast4 = destinationLast4,
        failureReason = failureReason,
    )

/** "1.50%" from basis points, matching FeeSchedule's own label rule. */
internal fun basisPointPercentLabel(bp: Int): String =
    "${bp / 100}.${(bp % 100).toString().padStart(2, '0')}%"
