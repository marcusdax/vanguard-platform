package com.odasi.fieldwright.model

/**
 * Money is integer cents everywhere, in integer-safe arithmetic. The platform never
 * floats a payout, never holds a rounding remainder, and always discloses the fee
 * next to the money it changes.
 */
data class FeeSchedule(
    /** Requester-paid platform fee in basis points. Disclosed on every money surface. */
    val platformFeeBp: Int = 1_500,
    /** Worker-paid instant payout fee, capped, charged only when a payout is requested. */
    val instantPayoutFeeBp: Int = 150,
    val instantPayoutMinFeeCents: Int = 50,
    val instantPayoutMaxFeeCents: Int = 500,
    /** IRS-style mileage rate, a parameter because it changes every January. */
    val mileageRateCentsPerMile: Int = 70,
) {
    val platformFeePercentLabel: String get() = "${platformFeeBp / 100}.${(platformFeeBp % 100).toString().padStart(2, '0')}%"
}

data class LedgerEntry(
    val id: String,
    val jobId: String?,
    val packetId: String?,
    val kind: LedgerKind,
    /** Signed cents: earnings positive, fees and reversals negative. */
    val amountCents: Int,
    val status: LedgerStatus,
    val atEpochMs: Long,
    val description: String,
    val idempotencyKey: String,
    val demo: Boolean,
)

data class PayoutRequest(
    val id: String,
    val amountCents: Int,
    val feeCents: Int,
    val netCents: Int,
    val mode: PayoutMode,
    val status: PayoutStatus,
    val requestedAtEpochMs: Long,
    val completedAtEpochMs: Long?,
    val destinationLast4: String,
    val idempotencyKey: String,
    val failureReason: String? = null,
)

data class PayoutQuote(
    val amountCents: Int,
    val feeCents: Int,
    val netCents: Int,
    val mode: PayoutMode,
    val etaLabel: String,
    val feeDisclosure: String,
)

data class Balances(
    val availableCents: Int,
    val pendingCents: Int,
    val paidLifetimeCents: Int,
    val instantFeePaidLifetimeCents: Int,
    val currentStreakDays: Int,
    val weeklyGoalCents: Int,
)

data class EarningsSummary(
    val todayCents: Int,
    val weekCents: Int,
    val monthCents: Int,
    val onlineMinutesToday: Int,
    val jobsCompletedWeek: Int,
    val milesDrivenWeek: Double,
    val bonusesWeekCents: Int,
    val tipsWeekCents: Int,
    val platformFeeWeekCents: Int,
    val mileageDeductionCents: Int,
)

/** Canonical money formatting. UI uses this so no screen invents its own format. */
object Money {
    fun format(cents: Int, showCents: Boolean = (cents % 100) != 0, signed: Boolean = false): String {
        val negative = cents < 0
        val abs = kotlin.math.abs(cents)
        val whole = abs / 100
        val frac = abs % 100
        val grouped = whole.toString().reversed().chunked(3).joinToString(",").reversed()
        val body = if (showCents) "$grouped.${frac.toString().padStart(2, '0')}" else grouped
        return when {
            negative -> "\u2212$$body"
            signed -> "+$$body"
            else -> "$$body"
        }
    }

    fun formatDollars(dollars: Double): String = format(kotlin.math.round(dollars * 100).toInt())

    fun perHour(cents: Int, minutes: Int): String =
        if (minutes <= 0) "—" else "${format(kotlin.math.round(cents.toDouble() * 60.0 / minutes).toInt())}/hr"

    fun miles(meters: Double): String = String.format(java.util.Locale.US, "%.1f mi", meters / 1609.344)
}

/** Rounding rule used by every fee: half-up, to the nearest cent, applied once. */
internal fun applyBasisPoints(amountCents: Int, bp: Int): Int =
    ((amountCents.toLong() * bp + 5_000L) / 10_000L).toInt()
