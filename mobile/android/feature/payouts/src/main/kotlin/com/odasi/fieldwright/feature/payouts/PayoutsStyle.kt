package com.odasi.fieldwright.feature.payouts

import androidx.compose.ui.unit.dp
import com.odasi.fieldwright.design.Fw
import com.odasi.fieldwright.design.PillTone
import com.odasi.fieldwright.model.LedgerStatus
import com.odasi.fieldwright.model.PayoutStatus

/**
 * One place where the Payouts feature names design tokens and semantic tones. Olive means
 * good/available, amber means pending, flag means blocked or failed — the same mapping the
 * ledger states use everywhere else in the app.
 */
internal object PayoutsStyle {

    val gutter = Fw.space.gutter

    /** Weekly-goal ring geometry. Ring stroke is a composition of the 4 dp spacing base. */
    val goalRingSize = 96.dp
    val goalRingStroke = Fw.space.xs

    fun tone(tone: MoneyTone): PillTone = when (tone) {
        MoneyTone.OLIVE -> PillTone.OLIVE
        MoneyTone.AMBER -> PillTone.AMBER
        MoneyTone.FLAG -> PillTone.FLAG
        MoneyTone.OXIDE -> PillTone.OXIDE
        MoneyTone.NEUTRAL -> PillTone.NEUTRAL
    }

    fun ledgerTone(status: LedgerStatus): PillTone = tone(ledgerStatusTone(status))

    fun payoutTone(status: PayoutStatus): PillTone = tone(payoutStatusTone(status))
}
