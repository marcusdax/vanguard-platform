package com.odasi.fieldwright.data.repo

import com.odasi.fieldwright.data.Fixtures
import com.odasi.fieldwright.data.demo.DemoCatalog
import com.odasi.fieldwright.model.FeeEngine
import com.odasi.fieldwright.model.FeeSchedule
import com.odasi.fieldwright.model.LedgerEngine
import com.odasi.fieldwright.model.PayoutMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Money decisions the data layer leans on: demo ledger balances through the real engine,
 * the payout fee quote/disclosure, and payout idempotency keys (the "same request once"
 * property that a retried tap depends on).
 */
class LedgerAndPayoutTest {

    private val now = Fixtures.NOW
    private val operator = DemoCatalog.operator()

    @Test
    fun `demo ledger balances match the sample book`() {
        val balances = LedgerEngine.balances(DemoCatalog.ledger(now), operator)
        assertEquals(42_000, balances.availableCents, "FW-0994 earning + bonus are available")
        assertEquals(1_200, balances.pendingCents, "the requester tip is pending review")
        assertEquals(38_000, balances.paidLifetimeCents, "the paid payout's gross earnings")
        assertEquals(0, balances.instantFeePaidLifetimeCents)
        assertEquals(6, balances.currentStreakDays)
        assertEquals(120_000, balances.weeklyGoalCents)
    }

    @Test
    fun `earnings summary passes telemetry through and keeps the fee an estimate`() {
        val summary = LedgerEngine.summary(
            entries = DemoCatalog.ledger(now),
            nowEpochMs = now,
            onlineMinutesToday = 187,
            milesDrivenWeek = 4.2,
            schedule = FeeSchedule(),
        )
        assertEquals(187, summary.onlineMinutesToday)
        assertEquals(4.2, summary.milesDrivenWeek)
        assertTrue(summary.monthCents >= summary.weekCents)
        assertTrue(summary.weekCents >= summary.todayCents)
        assertEquals(FeeEngine.mileageDeductionCents(4.2, FeeSchedule()), summary.mileageDeductionCents)
        assertTrue(summary.platformFeeWeekCents >= 0, "the platform fee is requester-paid, shown as a disclosure")
    }

    @Test
    fun `instant payout quote discloses a capped fee and standard ACH is free`() {
        val schedule = FeeSchedule()
        val instant = FeeEngine.quote(10_000, PayoutMode.INSTANT, schedule)
        assertEquals(150, instant.feeCents)
        assertEquals(9_850, instant.netCents)
        assertTrue(instant.feeDisclosure.contains("1.5%"))
        assertTrue(instant.etaLabel.contains("30 minutes"))

        val standard = FeeEngine.quote(10_000, PayoutMode.STANDARD, schedule)
        assertEquals(0, standard.feeCents)
        assertEquals(10_000, standard.netCents)

        assertEquals(50, FeeEngine.instantPayoutFeeCents(1_000, schedule), "fee floors at \$0.50")
        assertEquals(500, FeeEngine.instantPayoutFeeCents(100_000, schedule), "fee caps at \$5.00")
    }

    @Test
    fun `payout idempotency keys are stable within the request window and unique across requests`() {
        val first = PayoutKeys.forRequest("op-test", 5_000, PayoutMode.INSTANT, now)
        val retriedTap = PayoutKeys.forRequest("op-test", 5_000, PayoutMode.INSTANT, now + 30_000)
        assertEquals(first, retriedTap)

        assertNotEquals(first, PayoutKeys.forRequest("op-test", 5_000, PayoutMode.INSTANT, now + 61_000))
        assertNotEquals(first, PayoutKeys.forRequest("op-test", 6_000, PayoutMode.INSTANT, now))
        assertNotEquals(first, PayoutKeys.forRequest("op-test", 5_000, PayoutMode.STANDARD, now))
        assertNotEquals(first, PayoutKeys.forRequest("op-other", 5_000, PayoutMode.INSTANT, now))
    }

    @Test
    fun `payout short id is deterministic and display-safe`() {
        val key = PayoutKeys.forRequest("op-test", 3_800, PayoutMode.STANDARD, now)
        val short = PayoutKeys.shortId(key, Fixtures.hasher)
        assertEquals(short, PayoutKeys.shortId(key, Fixtures.hasher))
        assertEquals(10, short.length)
        assertTrue(short.all { it.isDigit() || it in 'A'..'F' })
    }
}
