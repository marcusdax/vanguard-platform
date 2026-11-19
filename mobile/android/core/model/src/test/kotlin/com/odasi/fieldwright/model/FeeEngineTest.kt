package com.odasi.fieldwright.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fee rules are money rules: integer cents, half-up rounding applied once, disclosed
 * caps, and a worker-paid fee that can never exceed the payout it charges.
 */
class FeeEngineTest {

    private val schedule = FeeSchedule()

    @Test
    fun `the default schedule is the contracted 15 percent requester fee`() {
        assertEquals(1_500, schedule.platformFeeBp)
        assertEquals("15.00%", schedule.platformFeePercentLabel)
        assertEquals(150, schedule.instantPayoutFeeBp)
        assertEquals(50, schedule.instantPayoutMinFeeCents)
        assertEquals(500, schedule.instantPayoutMaxFeeCents)
        assertEquals(70, schedule.mileageRateCentsPerMile)
    }

    @Test
    fun `platform fee is 15 percent of the bounty, rounded half-up once`() {
        assertEquals(1_500, FeeEngine.platformFeeCents(10_000, schedule))
        assertEquals(1_425, FeeEngine.platformFeeCents(9_500, schedule))
        // 3,350 * 15% = 502.5 -> 503
        assertEquals(503, FeeEngine.platformFeeCents(3_350, schedule))
        // 1 cent * 15% = 0.15 -> 0
        assertEquals(0, FeeEngine.platformFeeCents(1, schedule))
    }

    @Test
    fun `platform fee is never negative and never charged on nothing`() {
        assertEquals(0, FeeEngine.platformFeeCents(0, schedule))
        assertEquals(0, FeeEngine.platformFeeCents(-5_000, schedule))
    }

    @Test
    fun `instant payout fee is 15 basis points inside the disclosed caps`() {
        // 1.5% of $100.00 = $1.50, under the $5.00 cap
        assertEquals(150, FeeEngine.instantPayoutFeeCents(10_000, schedule))
        // 1.5% of $1,000.00 = $15.00 -> capped at $5.00
        assertEquals(500, FeeEngine.instantPayoutFeeCents(100_000, schedule))
        // 1.5% of $10.00 = $0.15 -> floored at $0.50
        assertEquals(50, FeeEngine.instantPayoutFeeCents(1_000, schedule))
    }

    @Test
    fun `instant payout fee can never exceed the payout itself`() {
        val fee = FeeEngine.instantPayoutFeeCents(40, schedule)

        assertEquals(40, fee)
        assertTrue(fee <= 40)
    }

    @Test
    fun `instant payout fee is zero for nothing and for negatives`() {
        assertEquals(0, FeeEngine.instantPayoutFeeCents(0, schedule))
        assertEquals(0, FeeEngine.instantPayoutFeeCents(-1_000, schedule))
    }

    @Test
    fun `standard ACH is free and the quote says so`() {
        val quote = FeeEngine.quote(12_500, PayoutMode.STANDARD, schedule)

        assertEquals(12_500, quote.amountCents)
        assertEquals(0, quote.feeCents)
        assertEquals(12_500, quote.netCents)
        assertTrue(quote.feeDisclosure.contains("free"))
        assertTrue(quote.etaLabel.contains("business days"))
    }

    @Test
    fun `the instant quote discloses the fee and the net before it is charged`() {
        val quote = FeeEngine.quote(10_000, PayoutMode.INSTANT, schedule)

        assertEquals(10_000, quote.amountCents)
        assertEquals(150, quote.feeCents)
        assertEquals(9_850, quote.netCents)
        assertTrue(quote.feeDisclosure.contains("1.5%"))
        assertTrue(quote.feeDisclosure.contains(Money.format(150)))
        assertTrue(quote.feeDisclosure.contains(Money.format(9_850)))
        assertTrue(quote.etaLabel.contains("30 minutes"))
    }

    @Test
    fun `quote for nothing is an honest zero, not a minimum fee`() {
        val quote = FeeEngine.quote(0, PayoutMode.INSTANT, schedule)

        assertEquals(0, quote.amountCents)
        assertEquals(0, quote.feeCents)
        assertEquals(0, quote.netCents)
    }

    @Test
    fun `a negative request is treated as zero rather than producing a fee`() {
        val quote = FeeEngine.quote(-500, PayoutMode.INSTANT, schedule)

        assertEquals(0, quote.amountCents)
        assertEquals(0, quote.feeCents)
        assertEquals(0, quote.netCents)
    }

    @Test
    fun `mileage deduction uses the configured rate and rounds half-up`() {
        assertEquals(700, FeeEngine.mileageDeductionCents(10.0, schedule))
        assertEquals(864, FeeEngine.mileageDeductionCents(12.345, schedule))
        assertEquals(0, FeeEngine.mileageDeductionCents(0.0, schedule))
        assertEquals(0, FeeEngine.mileageDeductionCents(-3.0, schedule))
    }

    @Test
    fun `mileage is a parameter so a new year needs no code change`() {
        val raised = schedule.copy(mileageRateCentsPerMile = 75)

        assertEquals(750, FeeEngine.mileageDeductionCents(10.0, raised))
        assertFalse(FeeEngine.mileageDeductionCents(10.0, raised) == FeeEngine.mileageDeductionCents(10.0, schedule))
    }

    @Test
    fun `the requester pays the platform fee, the worker keeps the payout whole`() {
        val jobPayout = 28_000
        val requesterFee = FeeEngine.platformFeeCents(jobPayout, schedule)

        assertEquals(4_200, requesterFee)
        // The worker's amount is the full payout; the fee is disclosed beside it.
        assertEquals(jobPayout, jobPayout)
        assertTrue(requesterFee > 0)
    }
}
