package com.odasi.fieldwright.data.sync

import kotlin.random.Random

/**
 * Deterministic retry arithmetic for the outbox (CONTRACTS §8: exponential backoff plus
 * jitter, max 8 attempts, then dead letter).
 *
 * The schedule is deliberately *fast at first* (a dropped Wi-Fi handshake should not cost
 * a minute) and *flat at the tail* (a backend outage should not spin the radio): base 2 s,
 * doubling per attempt, capped at 15 minutes. Jitter is ±20% of the capped delay so a
 * fleet of devices that lost network together does not retry in lockstep.
 *
 * The schedule is pure arithmetic so it is unit-tested without WorkManager: the worker's
 * own backoff criteria are a second, independent safety net for process death.
 */
object RetryPolicy {

    /** Attempts after which an event stops retrying and becomes a visible dead letter. */
    const val MAX_ATTEMPTS: Int = 8

    const val BASE_DELAY_MS: Long = 2_000L
    const val MAX_DELAY_MS: Long = 15 * 60_000L
    const val JITTER_FRACTION: Double = 0.20

    /**
     * Delay before the next attempt.
     *
     * @param attempt number of failures so far (1 = first retry after the first failure).
     */
    fun delayMillis(attempt: Int, jitter: Jitter): Long {
        require(attempt >= 1) { "Retry attempt must be at least 1 (got $attempt)." }
        val exponent = (attempt - 1).coerceAtMost(20)
        val base = BASE_DELAY_MS shl exponent
        val capped = base.coerceAtMost(MAX_DELAY_MS)
        val factor = 1.0 - JITTER_FRACTION + 2.0 * JITTER_FRACTION * jitter.nextFraction()
        return (capped * factor).toLong().coerceIn(0L, MAX_DELAY_MS)
    }

    /** True when an event that has already failed [attempts] times must be dead-lettered. */
    fun isDeadLetter(attempts: Int): Boolean = attempts >= MAX_ATTEMPTS
}

/** Seam for deterministic jitter in tests. Fraction is expected in `[0, 1)`. */
fun interface Jitter {
    fun nextFraction(): Double
}

class RandomJitter(private val random: Random = Random.Default) : Jitter {
    override fun nextFraction(): Double = random.nextDouble()
}

/** Replays a fixed sequence of jitter fractions — tests stay fully deterministic. */
class FixedJitter(vararg fractions: Double) : Jitter {
    private val values: DoubleArray = if (fractions.isEmpty()) doubleArrayOf(0.5) else fractions.copyOf()
    private var index: Int = 0

    override fun nextFraction(): Double {
        val value = values[index % values.size]
        index++
        return value.coerceIn(0.0, 0.999_999)
    }
}
