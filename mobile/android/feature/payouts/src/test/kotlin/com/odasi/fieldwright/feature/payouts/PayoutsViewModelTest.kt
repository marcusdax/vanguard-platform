package com.odasi.fieldwright.feature.payouts

import com.odasi.fieldwright.data.SyncState
import com.odasi.fieldwright.model.FixedClock
import com.odasi.fieldwright.model.LedgerKind
import com.odasi.fieldwright.model.LedgerStatus
import com.odasi.fieldwright.model.PayoutMode
import com.odasi.fieldwright.model.PayoutStatus
import com.odasi.fieldwright.model.SyncPhase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Payouts owns the fee-disclosure flow and the UI guards around it. The fee arithmetic itself
 * is `FeeEngine`'s (domain-core) — these tests assert that the ViewModel relays the engine's
 * quote unchanged and never sends a request that was not quoted.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PayoutsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val clock = FixedClock(TEST_NOW_MS)

    private fun viewModel(container: FakeFieldwrightForPayouts): PayoutsViewModel =
        PayoutsViewModel(container, clock)

    @Test
    fun `instant quote uses the engine fee and net`() = runTest {
        val container = FakeFieldwrightForPayouts(balances = balancesFixture(availableCents = 20_000))
        val viewModel = viewModel(container)
        advanceUntilIdle()

        viewModel.openPayoutSheet(PayoutMode.INSTANT)

        val state = viewModel.ui.value
        assertTrue(state.sheetOpen)
        val quote = state.quote
        assertNotNull(quote)
        assertEquals(20_000, quote!!.amountCents)
        assertEquals(300, quote.feeCents)
        assertEquals(19_700, quote.netCents)
        assertEquals(PayoutMode.INSTANT, quote.mode)
    }

    @Test
    fun `instant fee is capped at five dollars`() = runTest {
        val container = FakeFieldwrightForPayouts(balances = balancesFixture(availableCents = 100_000))
        val viewModel = viewModel(container)
        advanceUntilIdle()

        viewModel.openPayoutSheet(PayoutMode.INSTANT)

        val quote = viewModel.ui.value.quote!!
        assertEquals(500, quote.feeCents)
        assertEquals(99_500, quote.netCents)
    }

    @Test
    fun `instant fee is floored at fifty cents`() = runTest {
        val container = FakeFieldwrightForPayouts(balances = balancesFixture(availableCents = 1_000))
        val viewModel = viewModel(container)
        advanceUntilIdle()

        viewModel.openPayoutSheet(PayoutMode.INSTANT)

        val quote = viewModel.ui.value.quote!!
        assertEquals(50, quote.feeCents)
        assertEquals(950, quote.netCents)
    }

    @Test
    fun `standard ACH is free and receives the full amount`() = runTest {
        val container = FakeFieldwrightForPayouts(balances = balancesFixture(availableCents = 20_000))
        val viewModel = viewModel(container)
        advanceUntilIdle()

        viewModel.openPayoutSheet(PayoutMode.STANDARD)

        val quote = viewModel.ui.value.quote!!
        assertEquals(PayoutMode.STANDARD, quote.mode)
        assertEquals(0, quote.feeCents)
        assertEquals(20_000, quote.netCents)
    }

    @Test
    fun `switching mode re-quotes instead of reusing the old fee`() = runTest {
        val container = FakeFieldwrightForPayouts(balances = balancesFixture(availableCents = 20_000))
        val viewModel = viewModel(container)
        advanceUntilIdle()

        viewModel.openPayoutSheet(PayoutMode.INSTANT)
        assertEquals(300, viewModel.ui.value.quote!!.feeCents)

        viewModel.setQuoteMode(PayoutMode.STANDARD)

        val quote = viewModel.ui.value.quote!!
        assertEquals(PayoutMode.STANDARD, quote.mode)
        assertEquals(0, quote.feeCents)
        assertEquals(20_000, quote.netCents)
    }

    @Test
    fun `insufficient balance opens the sheet with a reason and sends nothing`() = runTest {
        val container = FakeFieldwrightForPayouts(balances = balancesFixture(availableCents = 0))
        val viewModel = viewModel(container)
        advanceUntilIdle()

        viewModel.openPayoutSheet(PayoutMode.INSTANT)
        assertEquals(0, container.earningsRepo.requestCalls.size)
        assertNull(viewModel.ui.value.quote)
        assertNotNull(viewModel.ui.value.payoutError)

        viewModel.requestPayout()
        advanceUntilIdle()

        assertTrue(container.earningsRepo.requestCalls.isEmpty())
        assertTrue(viewModel.ui.value.payoutError!!.contains("No available balance"))
    }

    @Test
    fun `a failing payout is reported with its reason and keeps the sheet open`() = runTest {
        val container = FakeFieldwrightForPayouts(balances = balancesFixture(availableCents = 20_000))
        container.earningsRepo.requestResult = { _, _ ->
            Result.failure(IllegalStateException("PSP declined: account closed"))
        }
        val viewModel = viewModel(container)
        advanceUntilIdle()

        viewModel.openPayoutSheet(PayoutMode.INSTANT)
        viewModel.requestPayout()
        advanceUntilIdle()

        val state = viewModel.ui.value
        assertFalse(state.requesting)
        assertTrue(state.sheetOpen)
        assertTrue(state.payoutError!!.contains("PSP declined"))
        assertTrue(state.payoutError!!.contains("No balance was moved"))
        assertNull(state.message)
        assertEquals(1, container.earningsRepo.requestCalls.size)
        assertEquals(20_000, container.earningsRepo.requestCalls.single().amountCents)
    }

    @Test
    fun `an offline request is reported as queued, not as delivered`() = runTest {
        val container = FakeFieldwrightForPayouts(
            balances = balancesFixture(availableCents = 20_000),
            syncState = SyncState(SyncPhase.WAITING_FOR_NETWORK, queued = 2, inFlight = 0, deadLetters = 0, lastSyncedAtEpochMs = null, lastError = "offline"),
        )
        val viewModel = viewModel(container)
        advanceUntilIdle()

        assertTrue(viewModel.ui.value.offline)
        assertEquals(2, viewModel.ui.value.queued)

        viewModel.openPayoutSheet(PayoutMode.INSTANT)
        viewModel.requestPayout()
        advanceUntilIdle()

        val state = viewModel.ui.value
        assertFalse(state.sheetOpen)
        assertNotNull(state.message)
        assertTrue(state.message!!.contains("queued"))
    }

    @Test
    fun `successful online payout names the net amount and destination`() = runTest {
        val container = FakeFieldwrightForPayouts(balances = balancesFixture(availableCents = 20_000))
        val viewModel = viewModel(container)
        advanceUntilIdle()

        viewModel.openPayoutSheet(PayoutMode.INSTANT)
        viewModel.requestPayout()
        advanceUntilIdle()

        val state = viewModel.ui.value
        assertFalse(state.sheetOpen)
        assertNotNull(state.message)
        assertTrue(state.message!!.contains("••4242"))
        assertNull(state.payoutError)
        assertEquals(TEST_NOW_MS, container.earningsRepo.requestCalls.single().atEpochMs)
    }

    @Test
    fun `payout history carries failure reasons and status tones`() = runTest {
        val container = FakeFieldwrightForPayouts(
            payouts = listOf(
                payoutRequest("payout-failed", PayoutStatus.FAILED, failureReason = "Bank returned: account closed."),
                payoutRequest("payout-paid", PayoutStatus.PAID),
            ),
        )
        val viewModel = viewModel(container)
        advanceUntilIdle()

        val rows = viewModel.ui.value.payouts
        assertEquals(2, rows.size)
        val failed = rows.first { it.id == "payout-failed" }
        assertEquals("Failed", failed.statusLabel)
        assertEquals(MoneyTone.FLAG, failed.tone)
        assertEquals("Bank returned: account closed.", failed.failureReason)
        val paid = rows.first { it.id == "payout-paid" }
        assertEquals(MoneyTone.OLIVE, paid.tone)
        assertNull(paid.failureReason)
        assertEquals(42_000 - 315, paid.netCents)
    }

    @Test
    fun `ledger is grouped by day with signed amounts preserved`() = runTest {
        val zone = java.time.ZoneId.systemDefault()
        val today = java.time.Instant.ofEpochMilli(TEST_NOW_MS).atZone(zone).toLocalDate()
        fun localTime(daysAgo: Long, hour: Int): Long =
            today.minusDays(daysAgo).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

        val container = FakeFieldwrightForPayouts(
            ledger = listOf(
                ledgerEntry("today-earn", LedgerKind.EARNING, 9_500, LedgerStatus.AVAILABLE, localTime(0, 9)),
                ledgerEntry("today-tip", LedgerKind.TIP, 500, LedgerStatus.PENDING, localTime(0, 10)),
                ledgerEntry("yesterday-earn", LedgerKind.EARNING, 42_000, LedgerStatus.PAID, localTime(1, 9)),
                ledgerEntry("yesterday-fee", LedgerKind.INSTANT_FEE, -315, LedgerStatus.PAID, localTime(1, 10)),
            ),
        )
        val viewModel = viewModel(container)
        advanceUntilIdle()

        val days = viewModel.ui.value.ledgerDays
        assertEquals(2, days.size)
        assertEquals("Today", days[0].dayLabel)
        assertEquals(10_000, days[0].totalCents)
        assertEquals("Yesterday", days[1].dayLabel)
        assertEquals(41_685, days[1].totalCents)
        assertEquals(4, viewModel.ui.value.ledgerEntryCount)
        assertEquals(-315, days[1].rows.first { it.id == "yesterday-fee" }.amountCents)
        assertEquals("Available", days[0].rows.first { it.id == "today-earn" }.statusLabel)
    }

    @Test
    fun `destination and operator label come from the operator repository`() = runTest {
        val container = FakeFieldwrightForPayouts()
        val viewModel = viewModel(container)
        advanceUntilIdle()

        assertEquals("4242", viewModel.ui.value.destinationLast4)
        assertTrue(viewModel.ui.value.operatorLabel.contains("Elena Voss"))
    }

    @Test
    fun `weekly goal progress is capped and derived from week earnings`() = runTest {
        val container = FakeFieldwrightForPayouts(
            balances = balancesFixture(availableCents = 20_000, weeklyGoalCents = 30_000),
            summary = summaryFixture(weekCents = 18_642),
        )
        val viewModel = viewModel(container)
        advanceUntilIdle()

        val progress = viewModel.ui.value.weeklyGoalProgress
        assertTrue(progress > 0f && progress < 1f)
        assertEquals(18_642f / 30_000f, progress, 0.0001f)
    }

    @Test
    fun `a zero weekly goal does not divide by zero`() = runTest {
        val container = FakeFieldwrightForPayouts(
            balances = balancesFixture(availableCents = 20_000, weeklyGoalCents = 0),
        )
        val viewModel = viewModel(container)
        advanceUntilIdle()

        assertEquals(0f, viewModel.ui.value.weeklyGoalProgress, 0.0f)
    }

    @Test
    fun `loading clears once balances arrive`() = runTest {
        val container = FakeFieldwrightForPayouts()
        val viewModel = viewModel(container)
        advanceUntilIdle()

        assertFalse(viewModel.ui.value.loading)
        assertNotNull(viewModel.ui.value.balances)
    }
}
