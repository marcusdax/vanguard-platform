package com.odasi.fieldwright.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VanguardEngineTest {

    @Test
    fun `floor tasks are never gated regardless of held credentials`() {
        val decision = VanguardEngine.checkCredentials(
            isFloorAction = true,
            requiredCredentials = setOf("ROOF_SAFETY"),
            heldCredentials = emptySet(),
        )
        assertTrue(decision is VanguardCredentialDecision.Allowed)
        assertEquals("I-1", (decision as VanguardCredentialDecision.Allowed).invariant)
    }

    @Test
    fun `gated tasks return explainable shortestFix with zero fee`() {
        val decision = VanguardEngine.checkCredentials(
            isFloorAction = false,
            requiredCredentials = setOf("DART_FFI_INCLINOMETER_T3"),
            heldCredentials = setOf("IDENTITY_VERIFIED"),
            actionLabel = "4-Slope Roof Inspection",
        )
        assertTrue(decision is VanguardCredentialDecision.Blocked)
        val blocked = decision as VanguardCredentialDecision.Blocked
        assertEquals("T3-02", blocked.shortestFix.moduleId)
        assertEquals(0, blocked.shortestFix.costCents)
    }

    @Test
    fun `4-task neighborhood loop at H2 2026 IRS 76 cents per mile nets $54_97 per hour`() {
        val eco = VanguardEngine.computeClusterEconomics(
            bountiesCents = listOf(1800, 4200, 2000, 2000),
            taskMinutes = 90,
            miles = 5.0,
            irsCentsPerMile = VanguardEngine.IRS_H2_2026_MILEAGE_CENTS,
        )
        assertEquals(10_000, eco.grossCents)
        assertEquals(380, eco.vehicleCostCents)
        assertEquals(9_620, eco.netCents)
        assertEquals(5_497, eco.netHourlyCents)
    }

    @Test
    fun `half life royalties sum exactly to query price`() {
        val hl = VanguardEngine.computeEconomicHalfLife(120)
        assertEquals(800, hl.queryPriceCents)
        assertEquals(hl.queryPriceCents, hl.workerRoyaltyCents + hl.homeownerRoyaltyCents + hl.treasuryBurnCents)
        assertTrue(hl.refreshMultiplier > 1.0)
    }
}
