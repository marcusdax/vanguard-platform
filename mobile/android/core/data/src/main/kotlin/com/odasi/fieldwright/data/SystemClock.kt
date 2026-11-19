package com.odasi.fieldwright.data

import com.odasi.fieldwright.model.Clock

/**
 * Device wall clock. Every decision in the data layer takes an injected `atEpochMs`
 * argument, so this object is used only to fill *now* at the boundary; tests swap in
 * [com.odasi.fieldwright.model.FixedClock].
 */
object SystemClock : Clock {
    override fun nowEpochMs(): Long = java.lang.System.currentTimeMillis()
}
