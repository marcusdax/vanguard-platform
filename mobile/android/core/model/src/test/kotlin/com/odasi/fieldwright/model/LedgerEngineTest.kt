package com.odasi.fieldwright.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ledger behaviour: balances come from entries (never from jobs), windows are UTC and
 * deterministic, demo/failed/reversed rows never become spendable money.
 */
class LedgerEngineTest {

    private val operator = Fixtures.operator(streakDays = 12, weeklyGoalCents = 60_000)
    private val schedule = FeeSchedule()

    private fun entry(
        id: String,
        kind: LedgerKind,
        amountCents: Int,
        status: LedgerStatus,
        atEpochMs: Long = NOW,
        jobId: String? = null,
        demo: Boolean = false,
    ): LedgerEntry = LedgerEntry(
        id = id,
        jobId = jobId,
        packetId = null,
        kind = kind,
        amountCents = amountCents,
        status = status,
        atEpochMs = atEpochMs,
        description = "$kind $id",
        idempotencyKey = "fw1:test:$id",
        demo = demo,
    )

    @Test
    fun `balances split available, pending and paid gross from entry status`() {
        val entries = listOf(
            entry("a1", LedgerKind.EARNING, 10_000, LedgerStatus.AVAILABLE, jobId = "j1"),
            entry("a2", LedgerKind.BONUS, 500, LedgerStatus.AVAILABLE),
            entry("p1", LedgerKind.EARNING, 2_000, LedgerStatus.PENDING, jobId = "j2"),
            entry("d1", LedgerKind.EARNING, 3_000, LedgerStatus.PAID, jobId = "j3"),
            entry("f1", LedgerKind.INSTANT_FEE, -150, LedgerStatus.PAID),
            entry("r1", LedgerKind.EARNING, 99_999, LedgerStatus.REVERSED),
            entry("x1", LedgerKind.EARNING, 99_999, LedgerStatus.FAILED),
        )

        val balances = LedgerEngine.balances(entries, operator)

        assertEquals(10_500, balances.availableCents)
        assertEquals(2_000, balances.pendingCents)
        assertEquals(3_000, balances.paidLifetimeCents)
        assertEquals(150, balances.instantFeePaidLifetimeCents)
        assertEquals(12, balances.currentStreakDays)
        assertEquals(60_000, balances.weeklyGoalCents)
    }

    @Test
    fun `an empty ledger is an honest zero, not an error`() {
        val balances = LedgerEngine.balances(emptyList(), operator)

        assertEquals(0, balances.availableCents)
        assertEquals(0, balances.pendingCents)
        assertEquals(0, balances.paidLifetimeCents)
        assertEquals(0, balances.instantFeePaidLifetimeCents)
    }

    @Test
    fun `negative adjustments reduce the available balance instead of being dropped`() {
        val entries = listOf(
            entry("a1", LedgerKind.EARNING, 10_000, LedgerStatus.AVAILABLE),
            entry("adj", LedgerKind.ADJUSTMENT, -1_200, LedgerStatus.AVAILABLE),
        )

        assertEquals(8_800, LedgerEngine.balances(entries, operator).availableCents)
    }

    @Test
    fun `instant payout fees are reported separately, never buried in paid gross`() {
        val entries = listOf(
            entry("e1", LedgerKind.EARNING, 50_000, LedgerStatus.PAID),
            entry("f1", LedgerKind.INSTANT_FEE, -500, LedgerStatus.PAID),
            entry("f2", LedgerKind.INSTANT_FEE, -500, LedgerStatus.PAID),
        )

        val balances = LedgerEngine.balances(entries, operator)

        assertEquals(50_000, balances.paidLifetimeCents)
        assertEquals(1_000, balances.instantFeePaidLifetimeCents)
    }

    @Test
    fun `summary windows use UTC day, ISO week and calendar month boundaries`() {
        // NOW = 2026-01-15T12:00:00Z (Thursday); week starts Monday 2026-01-12; month starts 2026-01-01.
        assertEquals(JAN_15_START, LedgerEngine.utcDayStart(NOW))
        assertEquals(JAN_12_START, LedgerEngine.utcWeekStart(NOW))
        assertEquals(JAN_01_START, LedgerEngine.utcMonthStart(NOW))
    }

    @Test
    fun `summary counts earnings inside each window and excludes the rest`() {
        val entries = listOf(
            entry("today", LedgerKind.EARNING, 1_000, LedgerStatus.AVAILABLE, atEpochMs = NOW, jobId = "j1"),
            entry("yesterday", LedgerKind.EARNING, 2_000, LedgerStatus.PAID, atEpochMs = JAN_15_START - 1, jobId = "j2"),
            entry("monday", LedgerKind.BONUS, 300, LedgerStatus.AVAILABLE, atEpochMs = JAN_12_START),
            entry("month", LedgerKind.TIP, 400, LedgerStatus.AVAILABLE, atEpochMs = JAN_01_START),
            entry("december", LedgerKind.EARNING, 999, LedgerStatus.AVAILABLE, atEpochMs = JAN_01_START - 1),
            entry("instant-fee", LedgerKind.INSTANT_FEE, -150, LedgerStatus.PAID, atEpochMs = NOW),
            entry("reversed", LedgerKind.EARNING, 777, LedgerStatus.REVERSED, atEpochMs = NOW),
        )

        val summary = LedgerEngine.summary(entries, NOW, onlineMinutesToday = 184, milesDrivenWeek = 10.0, schedule = schedule)

        assertEquals(1_000, summary.todayCents)
        assertEquals(3_300, summary.weekCents)
        assertEquals(3_700, summary.monthCents)
        assertEquals(300, summary.bonusesWeekCents)
        assertEquals(0, summary.tipsWeekCents) // the tip is in the month but before Monday
        assertEquals(2, summary.jobsCompletedWeek)
        assertEquals(184, summary.onlineMinutesToday)
        assertEquals(10.0, summary.milesDrivenWeek, 1e-9)
    }

    @Test
    fun `summary discloses the requester platform fee and labels mileage an estimate`() {
        val entries = listOf(
            entry("e1", LedgerKind.EARNING, 10_000, LedgerStatus.AVAILABLE, atEpochMs = NOW, jobId = "j1"),
            entry("e2", LedgerKind.EARNING, 10_000, LedgerStatus.AVAILABLE, atEpochMs = NOW, jobId = "j2"),
        )

        val summary = LedgerEngine.summary(entries, NOW, onlineMinutesToday = 60, milesDrivenWeek = 12.0, schedule = schedule)

        // 15% of the week's $200 of EARNING entries, disclosed as paid by the requester.
        assertEquals(3_000, summary.platformFeeWeekCents)
        // 12.0 miles * 70 cents, an estimate.
        assertEquals(840, summary.mileageDeductionCents)
    }

    @Test
    fun `summary windows are monotone and cross-month bounds safely`() {
        val entries = listOf(
            entry("old", LedgerKind.EARNING, 500, LedgerStatus.PAID, atEpochMs = JAN_01_START - 86_400_000L),
        )

        val summary = LedgerEngine.summary(entries, NOW, onlineMinutesToday = 0, milesDrivenWeek = 0.0, schedule = schedule)

        assertEquals(0, summary.todayCents)
        assertEquals(0, summary.weekCents)
        assertEquals(0, summary.monthCents)
    }

    @Test
    fun `negative online minutes and miles are clamped rather than counted`() {
        val summary = LedgerEngine.summary(
            entries = emptyList(),
            nowEpochMs = NOW,
            onlineMinutesToday = -30,
            milesDrivenWeek = -5.0,
            schedule = schedule,
        )

        assertEquals(0, summary.onlineMinutesToday)
        assertEquals(0.0, summary.milesDrivenWeek, 1e-9)
        assertEquals(0, summary.mileageDeductionCents)
    }

    @Test
    fun `demo entries can be counted when handed in but keep their demo flag visible`() {
        val entries = listOf(
            entry("demo", LedgerKind.EARNING, 4_000, LedgerStatus.AVAILABLE, atEpochMs = NOW, jobId = "demo", demo = true),
        )

        val balances = LedgerEngine.balances(entries, operator)

        assertEquals(4_000, balances.availableCents)
        assertTrue(entries.single().demo)
    }

    @Test
    fun `monthly boundary rolls over a leap February correctly`() {
        // 2024-02-29T12:00:00Z, a leap day.
        val leapNow = 1_709_208_000_000L
        val monthStart = LedgerEngine.utcMonthStart(leapNow)
        val dayStart = LedgerEngine.utcDayStart(leapNow)

        assertTrue(dayStart > monthStart)
        assertEquals(0.0, ((monthStart - dayStart) % 86_400_000L).toDouble(), 1e-9)
        // February has 29 days in 2024, so the day and month boundaries are 28 days apart.
        assertEquals(28L * 86_400_000L, dayStart - monthStart)
    }
}
