package com.odasi.fieldwright.data.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Outbox retry maths: exponential growth, a jitter band, a hard cap and a dead-letter
 * threshold. Pure arithmetic, fully deterministic through [FixedJitter].
 */
class RetryPolicyTest {

    @Test
    fun `delay doubles with each attempt then caps at fifteen minutes`() {
        val noJitter = FixedJitter(0.5) // factor = 1.0, so the raw schedule is visible
        assertEquals(2_000L, RetryPolicy.delayMillis(1, noJitter))
        assertEquals(4_000L, RetryPolicy.delayMillis(2, noJitter))
        assertEquals(8_000L, RetryPolicy.delayMillis(3, noJitter))
        assertEquals(16_000L, RetryPolicy.delayMillis(4, noJitter))
        assertEquals(256_000L, RetryPolicy.delayMillis(8, noJitter))
        assertEquals(RetryPolicy.MAX_DELAY_MS, RetryPolicy.delayMillis(12, noJitter))
        assertEquals(RetryPolicy.MAX_DELAY_MS, RetryPolicy.delayMillis(40, noJitter))
    }

    @Test
    fun `jitter stays inside the disclosed plus or minus twenty percent band`() {
        val low = FixedJitter(0.0) // 80% of the capped delay
        val high = FixedJitter(0.999_999) // ~120% of the capped delay
        assertEquals(1_600L, RetryPolicy.delayMillis(1, low))
        assertTrue(RetryPolicy.delayMillis(1, high) in 2_399L..2_401L)
        assertTrue(RetryPolicy.delayMillis(1, FixedJitter(0.5)) == 2_000L)
        // Jitter is bounded at the cap, never beyond it.
        assertTrue(RetryPolicy.delayMillis(30, high) <= RetryPolicy.MAX_DELAY_MS)
    }

    @Test
    fun `the eighth failure dead-letters the event`() {
        assertFalse(RetryPolicy.isDeadLetter(7))
        assertTrue(RetryPolicy.isDeadLetter(8))
        assertTrue(RetryPolicy.isDeadLetter(9))
        assertEquals(8, RetryPolicy.MAX_ATTEMPTS)
    }

    @Test
    fun `fixed jitter cycles and never throws on repeated reads`() {
        val jitter = FixedJitter(0.1, 0.2)
        assertEquals(0.1, jitter.nextFraction())
        assertEquals(0.2, jitter.nextFraction())
        assertEquals(0.1, jitter.nextFraction())
    }
}
