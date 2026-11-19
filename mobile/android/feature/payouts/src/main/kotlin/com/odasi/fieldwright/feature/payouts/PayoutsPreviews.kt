package com.odasi.fieldwright.feature.payouts

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.odasi.fieldwright.design.FieldwrightTheme
import com.odasi.fieldwright.design.Fw
import com.odasi.fieldwright.model.Balances
import com.odasi.fieldwright.model.PayoutMode

@Composable
private fun PayoutsPreviewSurface(content: @Composable () -> Unit) {
    FieldwrightTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = Fw.colors.desk) {
            content()
        }
    }
}

@Composable
private fun PreviewScreen(state: PayoutsUiState) {
    PayoutsScreen(
        state = state,
        nowEpochMs = PayoutsSamples.NOW,
        onOpenSheet = {},
        onQuoteMode = {},
        onDismissSheet = {},
        onRequestPayout = {},
        onDismissError = {},
        onDismissMessage = {},
        onClearPayoutError = {},
        onRetry = {},
        onOpenBrief = {},
    )
}

@Preview(name = "Payouts · balances, ledger, history", showBackground = false)
@Composable
private fun PayoutsContentPreview() {
    PayoutsPreviewSurface { PreviewScreen(PayoutsSamples.state()) }
}

@Preview(name = "Payouts · offline queued", showBackground = false)
@Composable
private fun PayoutsOfflinePreview() {
    PayoutsPreviewSurface {
        PreviewScreen(PayoutsSamples.state(offline = true, queued = 3))
    }
}

@Preview(name = "Payouts · insufficient balance", showBackground = false)
@Composable
private fun PayoutsInsufficientPreview() {
    PayoutsPreviewSurface {
        PreviewScreen(
            PayoutsSamples.state(
                balances = PayoutsSamples.balances(
                    availableCents = 0,
                    pendingCents = 9_500,
                    paidLifetimeCents = 124_000,
                ),
                ledger = PayoutsSamples.ledger().filter { it.status == com.odasi.fieldwright.model.LedgerStatus.PENDING },
            ),
        )
    }
}

@Preview(name = "Payouts · payout failed", showBackground = false)
@Composable
private fun PayoutsFailedPreview() {
    PayoutsPreviewSurface {
        PreviewScreen(
            PayoutsSamples.state().copy(
                error = "Your bank rejected the last transfer: account closed. Update the destination and retry.",
            ),
        )
    }
}

@Preview(name = "Payouts · loading", showBackground = false)
@Composable
private fun PayoutsLoadingPreview() {
    PayoutsPreviewSurface {
        PreviewScreen(PayoutsUiState(loading = true))
    }
}

@Preview(name = "Payouts · no balance / no ledger", showBackground = false)
@Composable
private fun PayoutsEmptyPreview() {
    PayoutsPreviewSurface {
        PreviewScreen(
            PayoutsSamples.state(
                balances = Balances(0, 0, 0, 0, 0, 0),
                ledger = emptyList(),
                payouts = emptyList(),
            ),
        )
    }
}

@Preview(name = "Payouts · instant quote sheet", showBackground = false)
@Composable
private fun PayoutsInstantSheetPreview() {
    PayoutsPreviewSurface {
        PreviewScreen(
            PayoutsSamples.state().copy(
                sheetOpen = true,
                quoteMode = PayoutMode.INSTANT,
                quote = PayoutsSamples.instantQuote(),
            ),
        )
    }
}

@Preview(name = "Payouts · standard ACH sheet", showBackground = false)
@Composable
private fun PayoutsStandardSheetPreview() {
    PayoutsPreviewSurface {
        PreviewScreen(
            PayoutsSamples.state().copy(
                sheetOpen = true,
                quoteMode = PayoutMode.STANDARD,
                quote = PayoutsSamples.standardQuote(),
            ),
        )
    }
}

@Preview(name = "Payouts · nothing available sheet", showBackground = false)
@Composable
private fun PayoutsNoBalanceSheetPreview() {
    PayoutsPreviewSurface {
        PreviewScreen(
            PayoutsSamples.state(
                balances = PayoutsSamples.balances(availableCents = 0),
                ledger = PayoutsSamples.ledger().filter { it.amountCents > 0 },
            ).copy(
                sheetOpen = true,
                quoteMode = PayoutMode.INSTANT,
                quote = null,
                payoutError = "Nothing available yet. A sealed packet becomes payable after the requester settles it.",
            ),
        )
    }
}

@Preview(name = "Payouts · payout not sent error", showBackground = false)
@Composable
private fun PayoutsSheetErrorPreview() {
    PayoutsPreviewSurface {
        PreviewScreen(
            PayoutsSamples.state().copy(
                sheetOpen = true,
                quoteMode = PayoutMode.INSTANT,
                quote = PayoutsSamples.instantQuote(),
                payoutError = "Payout not sent: the payment provider declined the destination. No balance was moved.",
            ),
        )
    }
}
