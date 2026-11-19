package com.odasi.fieldwright.design

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * A tone is a *meaning*, not a colour: pills, notices and status marks all take one so a
 * screen can say "this is pending" without picking a hex value.
 */
enum class PillTone {
    /** Positive, active, sealed. */
    OLIVE,

    /** Pending, in review, waiting. */
    AMBER,

    /** Flagged, blocking, failed. */
    FLAG,

    /** Neutral metadata. */
    NEUTRAL,

    /** Evidence and location marks — the second accent, never a second action accent. */
    OXIDE,
}

/** Foreground/border colour for a tone, from tokens only. */
internal fun PillTone.foreground(): Color = when (this) {
    PillTone.OLIVE -> Fw.colors.olive
    PillTone.AMBER -> Fw.colors.amber
    PillTone.FLAG -> Fw.colors.flag
    PillTone.NEUTRAL -> Fw.colors.inkSoft
    PillTone.OXIDE -> Fw.colors.oxide
}

/** High-emphasis text colour on a tone's own tint. */
internal fun PillTone.emphasis(): Color = when (this) {
    PillTone.OLIVE -> Fw.colors.oliveDeep
    PillTone.AMBER -> Fw.colors.amber
    PillTone.FLAG -> Fw.colors.flag
    PillTone.NEUTRAL -> Fw.colors.ink
    PillTone.OXIDE -> Fw.colors.oxide
}

/** Leading glyph for a tone, used by [FwNotice]. */
internal fun PillTone.icon(): ImageVector = when (this) {
    PillTone.OLIVE -> Icons.Outlined.CheckCircle
    PillTone.AMBER -> Icons.Outlined.Schedule
    PillTone.FLAG -> Icons.Outlined.WarningAmber
    PillTone.NEUTRAL -> Icons.Outlined.Info
    PillTone.OXIDE -> Icons.Outlined.ErrorOutline
}

/** Accessibility strings that are not product copy; overridable at the call site. */
internal object FwA11y {
    const val sealed = "Sealed"
    const val notSealed = "Not sealed"
    const val copyHash = "Copy chain hash"
    const val openBrief = "Open operating brief"
    const val sampleBook = "Sample book"
    const val fieldwright = "Vanguard Field"
}
