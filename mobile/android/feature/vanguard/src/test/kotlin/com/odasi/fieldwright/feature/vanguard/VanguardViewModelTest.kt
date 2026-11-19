package com.odasi.fieldwright.feature.vanguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VanguardViewModelTest {
    @Test
    fun `shortestFix unblocks Tier 3 claim cleanly`() {
        val vm = VanguardViewModel()
        vm.attemptClaimTask("ROOF_SAFETY", isFloorTask = false, "4-Slope Hail Inspection")
        assertNotNull(vm.state.value.activeBlock)
        assertEquals("T3-01", vm.state.value.activeBlock?.moduleId)

        vm.completeModule("T3-01")
        assertNull(vm.state.value.activeBlock)
        assertTrue(vm.state.value.heldCredentials.contains("ROOF_SAFETY"))

        vm.attemptClaimTask("ROOF_SAFETY", isFloorTask = false, "4-Slope Hail Inspection")
        assertNull(vm.state.value.activeBlock)
    }
}
