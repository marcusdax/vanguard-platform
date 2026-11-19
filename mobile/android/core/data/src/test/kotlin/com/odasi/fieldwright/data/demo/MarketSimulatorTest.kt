package com.odasi.fieldwright.data.demo

import com.odasi.fieldwright.data.Fixtures
import com.odasi.fieldwright.model.GeoPoint
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The market heartbeat is deterministic and explainable: same tick sequence, same market.
 * Dispatch and the UI depend on that — an operator's offer list must not teleport between
 * recompositions.
 */
class MarketSimulatorTest {

    private val now = Fixtures.NOW
    private val base = GeoPoint(30.2672, -97.7431, now, 8f, GeoPoint.FixSource.SIMULATED)

    @Test
    fun `the same previous market and tick always produce the same snapshot`() {
        val first = MarketSimulator.tick(previous = null, nowEpochMs = now, tickIndex = 3, basePoint = base)
        val second = MarketSimulator.tick(previous = null, nowEpochMs = now, tickIndex = 3, basePoint = base)
        assertEquals(first, second)

        val next = MarketSimulator.tick(previous = first, nowEpochMs = now + 8_000, tickIndex = 4, basePoint = base)
        val nextAgain = MarketSimulator.tick(previous = first, nowEpochMs = now + 8_000, tickIndex = 4, basePoint = base)
        assertEquals(next, nextAgain)
    }

    @Test
    fun `demand, supply, hot zones, daylight and surge stay inside disclosed bounds`() {
        var previous = MarketSimulator.tick(previous = null, nowEpochMs = now, tickIndex = 0, basePoint = base)
        for (tick in 1L..40L) {
            previous = MarketSimulator.tick(previous, nowEpochMs = now + tick * 8_000, tickIndex = tick, basePoint = base)
            assertTrue(previous.demandIndex in 0.20..0.95, "demand ${previous.demandIndex} out of bounds at tick $tick")
            assertTrue(previous.supplyIndex in 0.15..0.90, "supply ${previous.supplyIndex} out of bounds at tick $tick")
            assertTrue(previous.hotZones.size in 2..3)
            previous.hotZones.forEach { zone ->
                assertTrue(zone.surgeMultiplier in 1.0..1.6, "zone surge ${zone.surgeMultiplier} outside the disclosure band")
                assertTrue(zone.radiusMeters > 0)
                assertTrue(zone.reason.isNotBlank())
            }
            assertTrue(previous.surgeMultiplier in 1.0..1.6)
            assertTrue(previous.daylightLabel.matches(Regex("\\d+ hr \\d+ min")), previous.daylightLabel)
            assertTrue(previous.weatherLabel.isNotBlank())
        }
    }

    @Test
    fun `demand and supply drift as a bounded walk rather than jumping`() {
        var previous = MarketSimulator.tick(previous = null, nowEpochMs = now, tickIndex = 0, basePoint = base)
        for (tick in 1L..10L) {
            val next = MarketSimulator.tick(previous, nowEpochMs = now + tick * 8_000, tickIndex = tick, basePoint = base)
            assertTrue(abs(next.demandIndex - previous.demandIndex) <= 0.05 + 1e-9)
            assertTrue(abs(next.supplyIndex - previous.supplyIndex) <= 0.04 + 1e-9)
            previous = next
        }
    }

    @Test
    fun `tick cadence is the documented eight seconds`() {
        assertEquals(8_000L, MarketSimulator.TICK_INTERVAL_MS)
    }
}
