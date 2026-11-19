package com.odasi.fieldwright.feature.payouts

import com.odasi.fieldwright.model.Balances
import com.odasi.fieldwright.model.EarningsSummary
import com.odasi.fieldwright.model.LedgerEntry
import com.odasi.fieldwright.model.LedgerKind
import com.odasi.fieldwright.model.LedgerStatus
import com.odasi.fieldwright.model.PayoutMode
import com.odasi.fieldwright.model.PayoutQuote
import com.odasi.fieldwright.model.PayoutRequest
import com.odasi.fieldwright.model.PayoutStatus

/**
 * Deterministic sample content for `@Preview` only. Figures are plausible Fieldwright numbers
 * (integer cents, documented fee parameters); they are never read at runtime.
 */
internal object PayoutsSamples {

    /** 2026-11-16T00:00:00Z. */
    const val NOW: Long = 1_763_251_200_000L

    fun balances(
        availableCents: Int = 18_642,
        pendingCents: Int = 9_500,
        paidLifetimeCents: Int = 124_000,
    ): Balances = Balances(
        availableCents = availableCents,
        pendingCents = pendingCents,
        paidLifetimeCents = paidLifetimeCents,
        instantFeePaidLifetimeCents = 3_150,
        currentStreakDays = 12,
        weeklyGoalCents = 30_000,
    )

    fun summary(): EarningsSummary = EarningsSummary(
        todayCents = 9_500,
        weekCents = 18_642,
        monthCents = 74_300,
        onlineMinutesToday = 212,
        jobsCompletedWeek = 5,
        milesDrivenWeek = 61.4,
        bonusesWeekCents = 1_500,
        tipsWeekCents = 800,
        platformFeeWeekCents = 3_291,
        mileageDeductionCents = 4_298,
    )

    fun ledger(): List<LedgerEntry> = listOf(
        LedgerEntry(
            id = "led-today-1",
            jobId = "job-FW-1048",
            packetId = "FW-1048",
            kind = LedgerKind.EARNING,
            amountCents = 9_500,
            status = LedgerStatus.AVAILABLE,
            atEpochMs = NOW - 3_600_000,
            description = "Annual RPZ test · feeder 14",
            idempotencyKey = "idem-today-1",
            demo = true,
        ),
        LedgerEntry(
            id = "led-today-2",
            jobId = "job-FW-1048",
            packetId = "FW-1048",
            kind = LedgerKind.TIP,
            amountCents = 500,
            status = LedgerStatus.PENDING,
            atEpochMs = NOW - 3_500_000,
            description = "Requester tip · feeder 14",
            idempotencyKey = "idem-today-2",
            demo = true,
        ),
        LedgerEntry(
            id = "led-yesterday-1",
            jobId = "job-FW-0994",
            packetId = "FW-0994",
            kind = LedgerKind.EARNING,
            amountCents = 42_000,
            status = LedgerStatus.PAID,
            atEpochMs = NOW - 86_400_000 - 7_200_000,
            description = "Roof hail assessment · Panel T-14",
            idempotencyKey = "idem-yesterday-1",
            demo = true,
        ),
        LedgerEntry(
            id = "led-yesterday-2",
            jobId = "job-FW-0994",
            packetId = "FW-0994",
            kind = LedgerKind.BONUS,
            amountCents = 1_500,
            status = LedgerStatus.PAID,
            atEpochMs = NOW - 86_400_000 - 7_100_000,
            description = "Busy bonus · Creekside",
            idempotencyKey = "idem-yesterday-2",
            demo = true,
        ),
        LedgerEntry(
            id = "led-yesterday-3",
            jobId = null,
            packetId = null,
            kind = LedgerKind.INSTANT_FEE,
            amountCents = -315,
            status = LedgerStatus.PAID,
            atEpochMs = NOW - 86_400_000 - 7_000_000,
            description = "Instant payout fee · ••4242",
            idempotencyKey = "idem-yesterday-3",
            demo = true,
        ),
    )

    fun payouts(): List<PayoutRequest> = listOf(
        PayoutRequest(
            id = "payout-paid",
            amountCents = 42_000,
            feeCents = 315,
            netCents = 41_685,
            mode = PayoutMode.INSTANT,
            status = PayoutStatus.PAID,
            requestedAtEpochMs = NOW - 86_400_000 - 7_000_000,
            completedAtEpochMs = NOW - 86_400_000 - 6_900_000,
            destinationLast4 = "4242",
            idempotencyKey = "idem-payout-paid",
        ),
        PayoutRequest(
            id = "payout-failed",
            amountCents = 18_642,
            feeCents = 0,
            netCents = 18_642,
            mode = PayoutMode.STANDARD,
            status = PayoutStatus.FAILED,
            requestedAtEpochMs = NOW - 10_800_000,
            completedAtEpochMs = null,
            destinationLast4 = "4242",
            idempotencyKey = "idem-payout-failed",
            failureReason = "The bank returned the transfer: account closed. Update the destination and retry.",
        ),
    )

    fun instantQuote(amountCents: Int = 18_642): PayoutQuote {
        val fee = ((amountCents.toLong() * 150 + 5_000L) / 10_000L).toInt().coerceIn(50, 500)
        return PayoutQuote(
            amountCents = amountCents,
            feeCents = fee,
            netCents = amountCents - fee,
            mode = PayoutMode.INSTANT,
            etaLabel = "Minutes to your debit card",
            feeDisclosure = "1.50% instant fee, minimum \$0.50, maximum \$5.00, charged only when you send.",
        )
    }

    fun standardQuote(amountCents: Int = 18_642): PayoutQuote = PayoutQuote(
        amountCents = amountCents,
        feeCents = 0,
        netCents = amountCents,
        mode = PayoutMode.STANDARD,
        etaLabel = "1–2 business days",
        feeDisclosure = "Standard ACH is free. No fee is charged to the worker.",
    )

    fun state(
        balances: Balances? = balances(),
        summary: EarningsSummary? = summary(),
        ledger: List<LedgerEntry> = ledger(),
        payouts: List<PayoutRequest> = payouts(),
        offline: Boolean = false,
        queued: Int = 0,
        error: String? = null,
    ): PayoutsUiState = PayoutsUiState(
        loading = false,
        error = error,
        operatorLabel = "Elena Voss · Lead inspector",
        demo = true,
        balances = balances,
        summary = summary,
        ledgerDays = groupLedgerByDay(ledger, NOW),
        ledgerEntryCount = ledger.size,
        payouts = payouts.map { it.toRow() },
        destinationLast4 = "4242",
        offline = offline,
        queued = queued,
        platformFeePercentLabel = "15.00%",
        instantFeePercentLabel = "1.50%",
        instantFeeMinCents = 50,
        instantFeeMaxCents = 500,
        mileageRateCentsPerMile = 70,
    )
}
