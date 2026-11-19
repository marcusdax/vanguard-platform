package com.odasi.fieldwright.feature.vault

import androidx.compose.ui.unit.dp
import com.odasi.fieldwright.design.Fw
import com.odasi.fieldwright.design.PillTone
import com.odasi.fieldwright.model.BadgeState
import com.odasi.fieldwright.model.MediaSource
import com.odasi.fieldwright.model.RedactionState
import com.odasi.fieldwright.model.SealState
import com.odasi.fieldwright.model.StepResult

/**
 * The one place the Vault names design-system tokens and semantic tones.
 *
 * Everything here is a mapping from a `core:model` state to the Fieldwright visual system.
 * Keeping it in one file means a token rename in `core:design` is a one-file change, and it
 * makes the colour rule auditable: olive = good, amber = pending, flag = blocking.
 */
internal object VaultStyle {

    /** The seal in the packet hero is deliberately larger than the dock-level mark. */
    val heroSealSize = 64.dp

    /** A numbered record dot in the append-only timeline; the icon token is the right base size. */
    val timelineDotSize = Fw.dimens.icon

    /** Width of the timeline's vertical rule; a hairline, like every other divider. */
    val timelineRuleWidth = Fw.dimens.hairline

    /** Preview/production gutters come from the design system, never from raw dp. */
    val gutter = Fw.space.gutter

    fun sealTone(state: SealState): PillTone = when (state) {
        SealState.EARNED -> PillTone.OLIVE
        SealState.PENDING -> PillTone.AMBER
        SealState.FLAGGED -> PillTone.FLAG
        SealState.WITHDRAWN -> PillTone.NEUTRAL
    }

    fun badgeTone(state: BadgeState): PillTone = when (state) {
        BadgeState.VERIFIED -> PillTone.OLIVE
        BadgeState.IN_REVIEW -> PillTone.AMBER
        BadgeState.FLAGGED -> PillTone.FLAG
        BadgeState.MISSING -> PillTone.OXIDE
        BadgeState.WAIVED -> PillTone.NEUTRAL
    }

    fun redactionTone(state: RedactionState): PillTone = when (state) {
        RedactionState.APPLIED -> PillTone.OLIVE
        RedactionState.REQUIRED -> PillTone.AMBER
        RedactionState.NONE, RedactionState.NOT_NEEDED -> PillTone.NEUTRAL
    }

    fun redactionLabel(state: RedactionState): String = when (state) {
        RedactionState.APPLIED -> "Redaction applied"
        RedactionState.REQUIRED -> "Redaction required"
        RedactionState.NONE -> "No redaction"
        RedactionState.NOT_NEEDED -> "Redaction not needed"
    }

    fun mediaSourceLabel(source: MediaSource): String = when (source) {
        MediaSource.CAMERA -> "Camera"
        MediaSource.SIMULATED -> "Simulated frame"
        MediaSource.IMPORTED -> "Imported"
    }

    fun mediaSourceTone(source: MediaSource): PillTone = when (source) {
        MediaSource.CAMERA -> PillTone.NEUTRAL
        MediaSource.SIMULATED -> PillTone.AMBER
        MediaSource.IMPORTED -> PillTone.NEUTRAL
    }

    fun stepResultTone(result: StepResult): PillTone = when (result) {
        StepResult.PASS -> PillTone.OLIVE
        StepResult.FAIL -> PillTone.FLAG
        StepResult.PENDING -> PillTone.AMBER
        StepResult.NOT_APPLICABLE -> PillTone.NEUTRAL
    }

    /** Same labels the capture flow uses; duplicated naming is worse than shared vocabulary. */
    fun stepResultLabel(result: StepResult): String = when (result) {
        StepResult.PASS -> "Passed"
        StepResult.FAIL -> "Failed"
        StepResult.PENDING -> "Open"
        StepResult.NOT_APPLICABLE -> "N/A"
    }

    fun gateTone(passed: Boolean, blocking: Boolean): PillTone = when {
        passed -> PillTone.OLIVE
        blocking -> PillTone.FLAG
        else -> PillTone.AMBER
    }

    fun gateLabel(passed: Boolean, blocking: Boolean): String = when {
        passed -> "Passed"
        blocking -> "Blocked"
        else -> "Advisory"
    }

    /** Ledger statuses use the same semantic tones as everything else on the screen. */
    fun ledgerTone(status: com.odasi.fieldwright.model.LedgerStatus): PillTone = when (status) {
        com.odasi.fieldwright.model.LedgerStatus.PAID -> PillTone.OLIVE
        com.odasi.fieldwright.model.LedgerStatus.AVAILABLE -> PillTone.OLIVE
        com.odasi.fieldwright.model.LedgerStatus.PENDING -> PillTone.AMBER
        com.odasi.fieldwright.model.LedgerStatus.REVERSED -> PillTone.FLAG
        com.odasi.fieldwright.model.LedgerStatus.FAILED -> PillTone.FLAG
    }
}
