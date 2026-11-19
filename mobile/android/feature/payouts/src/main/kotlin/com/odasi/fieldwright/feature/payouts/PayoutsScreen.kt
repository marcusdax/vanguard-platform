package com.odasi.fieldwright.feature.payouts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.odasi.fieldwright.data.FieldwrightRepositories
import com.odasi.fieldwright.design.Fw
import com.odasi.fieldwright.design.FwCard
import com.odasi.fieldwright.design.FwEmptyState
import com.odasi.fieldwright.design.FwGhostButton
import com.odasi.fieldwright.design.FwMoneyText
import com.odasi.fieldwright.design.FwNotice
import com.odasi.fieldwright.design.FwPill
import com.odasi.fieldwright.design.FwPrimaryButton
import com.odasi.fieldwright.design.FwSecondaryButton
import com.odasi.fieldwright.design.FwSectionRule
import com.odasi.fieldwright.design.FwSegmentedControl
import com.odasi.fieldwright.design.PillTone
import com.odasi.fieldwright.model.Balances
import com.odasi.fieldwright.model.EarningsSummary
import com.odasi.fieldwright.model.Money
import com.odasi.fieldwright.model.PayoutMode
import com.odasi.fieldwright.model.PayoutQuote
import kotlin.math.roundToInt

/**
 * Payouts route. Chrome belongs to the app shell; this route owns the ViewModel and the
 * bottom sheet. Every money figure on this screen comes from the repository (which quotes and
 * derives through `core:model`), and every fee is disclosed next to the money it changes.
 */
@Composable
fun PayoutsRoute(
    repos: FieldwrightRepositories,
    onOpenBrief: () -> Unit,
) {
    val viewModel: PayoutsViewModel = viewModel { PayoutsViewModel(repos) }
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val nowEpochMs = remember { System.currentTimeMillis() }

    PayoutsScreen(
        state = state,
        nowEpochMs = nowEpochMs,
        onOpenSheet = viewModel::openPayoutSheet,
        onQuoteMode = viewModel::setQuoteMode,
        onDismissSheet = viewModel::dismissPayoutSheet,
        onRequestPayout = viewModel::requestPayout,
        onDismissError = viewModel::dismissError,
        onDismissMessage = viewModel::consumeMessage,
        onClearPayoutError = viewModel::clearPayoutError,
        onRetry = viewModel::retry,
        onOpenBrief = onOpenBrief,
    )
}

@Composable
internal fun PayoutsScreen(
    state: PayoutsUiState,
    nowEpochMs: Long,
    onOpenSheet: (PayoutMode) -> Unit,
    onQuoteMode: (PayoutMode) -> Unit,
    onDismissSheet: () -> Unit,
    onRequestPayout: () -> Unit,
    onDismissError: () -> Unit,
    onDismissMessage: () -> Unit,
    onClearPayoutError: () -> Unit,
    onRetry: () -> Unit,
    onOpenBrief: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(
                start = PayoutsStyle.gutter,
                end = PayoutsStyle.gutter,
                top = Fw.space.md,
            ),
            verticalArrangement = Arrangement.spacedBy(Fw.space.xxs),
        ) {
            Text("Payouts", style = Fw.type.displayXl, color = Fw.colors.ink)
            Text(
                "Every cent in the ledger. Every fee quoted before you send.",
                style = Fw.type.body,
                color = Fw.colors.inkSoft,
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = PayoutsStyle.gutter,
                    end = PayoutsStyle.gutter,
                    top = Fw.space.sm,
                    bottom = Fw.space.xxl,
                ),
            verticalArrangement = Arrangement.spacedBy(Fw.dimens.cardGap),
        ) {
            if (state.offline) {
                FwNotice(
                    title = "Offline · ${state.queued} queued",
                    body = "Payout requests are recorded and sent when the connection returns. No balance moves until then.",
                    tone = PillTone.AMBER,
                )
            }
            state.error?.let { error ->
                FwNotice(
                    title = "Payouts unavailable",
                    body = error,
                    tone = PillTone.FLAG,
                    actionLabel = "Try again",
                    onAction = onRetry,
                )
            }
            state.message?.let { message ->
                FwNotice(
                    title = "Payout requested",
                    body = message,
                    tone = PillTone.NEUTRAL,
                    actionLabel = "Dismiss",
                    onAction = onDismissMessage,
                )
            }

            when {
                state.loading -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Fw.space.xl),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(color = Fw.colors.olive, strokeWidth = Fw.dimens.stroke)
                    Spacer(Modifier.width(Fw.space.sm))
                    Text("Reading your ledger…", style = Fw.type.body, color = Fw.colors.inkSoft)
                }

                else -> {
                    val balances = state.balances
                    if (balances == null) {
                        FwEmptyState(
                            title = "No balance available",
                            body = "Once a sealed packet is approved, its earning appears here and can be paid out.",
                            actionLabel = "Read how payouts work",
                            onAction = onOpenBrief,
                        )
                    } else {
                        BalancesCard(
                            state = state,
                            balances = balances,
                            nowEpochMs = nowEpochMs,
                            onInstant = { onOpenSheet(PayoutMode.INSTANT) },
                            onStandard = { onOpenSheet(PayoutMode.STANDARD) },
                        )
                        state.summary?.let { summary ->
                            EarningsCard(state = state, summary = summary)
                        }
                        LedgerCard(state = state, nowEpochMs = nowEpochMs)
                        PayoutHistoryCard(state = state, nowEpochMs = nowEpochMs)
                        HowYouGetPaidCard(state = state)
                    }
                }
            }
        }
    }

    if (state.sheetOpen) {
        PayoutSheet(
            state = state,
            onQuoteMode = onQuoteMode,
            onDismiss = onDismissSheet,
            onConfirm = onRequestPayout,
            onClearError = onClearPayoutError,
        )
    }
}

@Composable
private fun BalancesCard(
    state: PayoutsUiState,
    balances: Balances,
    nowEpochMs: Long,
    onInstant: () -> Unit,
    onStandard: () -> Unit,
) {
    val summary = state.summary
    FwCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "AVAILABLE TO PAY OUT",
                style = Fw.type.micro,
                color = Fw.colors.inkSoft,
                modifier = Modifier.weight(1f),
            )
            if (state.demo) FwPill(text = "Sample", tone = PillTone.AMBER)
        }
        FwMoneyText(
            cents = balances.availableCents,
            style = Fw.type.numericLarge,
            color = Fw.colors.ink,
        )
        Spacer(Modifier.height(Fw.space.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(Fw.space.lg)) {
            MoneyFigure(label = "Pending", cents = balances.pendingCents)
            MoneyFigure(label = "Paid lifetime", cents = balances.paidLifetimeCents)
        }
        Spacer(Modifier.height(Fw.space.md))
        Row(horizontalArrangement = Arrangement.spacedBy(Fw.space.xs)) {
            FwPrimaryButton(
                text = "Instant payout",
                onClick = onInstant,
                modifier = Modifier.weight(1f),
            )
            FwSecondaryButton(
                text = "Standard ACH",
                onClick = onStandard,
                modifier = Modifier.weight(1f),
            )
        }
        if (!state.hasBalance) {
            Spacer(Modifier.height(Fw.space.xs))
            Text(
                "Nothing available yet. A sealed packet becomes payable after the requester settles it.",
                style = Fw.type.meta,
                color = Fw.colors.inkSoft,
            )
        }
        Spacer(Modifier.height(Fw.space.xs))
        Text(
            text = buildString {
                if (state.destinationLast4.isNotBlank()) append("Destination ••${state.destinationLast4} · ")
                append("instant fee ${state.instantFeePercentLabel} ")
                append("(min ${Money.format(state.instantFeeMinCents)}, max ${Money.format(state.instantFeeMaxCents)})")
            },
            style = Fw.type.meta,
            color = Fw.colors.inkSoft,
        )
        Spacer(Modifier.height(Fw.space.md))
        FwSectionRule()
        Spacer(Modifier.height(Fw.space.sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("STREAK", style = Fw.type.micro, color = Fw.colors.inkSoft)
                Text(
                    text = if (balances.currentStreakDays == 1) "1 day" else "${balances.currentStreakDays} days",
                    style = Fw.type.heading,
                    color = Fw.colors.ink,
                )
                Spacer(Modifier.height(Fw.space.xs))
                Text("WEEKLY GOAL", style = Fw.type.micro, color = Fw.colors.inkSoft)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FwMoneyText(
                        cents = summary?.weekCents ?: 0,
                        style = Fw.type.numeric,
                        color = Fw.colors.ink,
                    )
                    Text(
                        text = " of ${Money.format(balances.weeklyGoalCents)}",
                        style = Fw.type.meta,
                        color = Fw.colors.inkSoft,
                    )
                }
            }
            WeeklyGoalRing(progress = state.weeklyGoalProgress)
        }
    }
}

/** Ring of weekly goal progress. It is a goal, not a tier: the tier rule against percentages does not apply to a savings target. */
@Composable
private fun WeeklyGoalRing(progress: Float) {
    val percent = (progress * 100).roundToInt()
    Box(
        modifier = Modifier
            .size(PayoutsStyle.goalRingSize)
            .semantics {
                contentDescription = "Weekly goal: $percent percent"
                progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = PayoutsStyle.goalRingStroke.toPx()
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = Fw.colors.line,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            if (progress > 0f) {
                drawArc(
                    color = Fw.colors.olive,
                    startAngle = -90f,
                    sweepAngle = 360f * progress.coerceIn(0f, 1f),
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("GOAL", style = Fw.type.micro, color = Fw.colors.inkSoft)
            Text("$percent%", style = Fw.type.numeric, color = Fw.colors.ink)
        }
    }
}

@Composable
private fun EarningsCard(state: PayoutsUiState, summary: EarningsSummary) {
    FwCard {
        FwSectionRule(label = "Earnings")
        Spacer(Modifier.height(Fw.space.xs))
        Row(horizontalArrangement = Arrangement.spacedBy(Fw.space.md)) {
            MoneyFigure(label = "Today", cents = summary.todayCents, modifier = Modifier.weight(1f))
            MoneyFigure(label = "This week", cents = summary.weekCents, modifier = Modifier.weight(1f))
            MoneyFigure(label = "This month", cents = summary.monthCents, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(Fw.space.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(Fw.space.md)) {
            TextFigure(label = "Online today", value = PayoutsTime.durationLabel(summary.onlineMinutesToday), modifier = Modifier.weight(1f))
            TextFigure(label = "Jobs this week", value = summary.jobsCompletedWeek.toString(), modifier = Modifier.weight(1f))
            TextFigure(
                label = "Miles this week",
                value = Money.miles(summary.milesDrivenWeek * METERS_PER_MILE),
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(Fw.space.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(Fw.space.md)) {
            MoneyFigure(label = "Bonuses", cents = summary.bonusesWeekCents, modifier = Modifier.weight(1f))
            MoneyFigure(label = "Tips", cents = summary.tipsWeekCents, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(Fw.space.md))
        FwSectionRule()
        Spacer(Modifier.height(Fw.space.xs))
        Text(
            text = "Requester-paid platform fee ${state.platformFeePercentLabel}: " +
                "${Money.format(summary.platformFeeWeekCents)} this week, charged to the requester and never deducted from your earnings.",
            style = Fw.type.meta,
            color = Fw.colors.inkSoft,
        )
        Spacer(Modifier.height(Fw.space.xs))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Mileage deduction ${Money.format(summary.mileageDeductionCents)} at ${state.mileageRateCentsPerMile}¢/mi.",
                style = Fw.type.meta,
                color = Fw.colors.inkSoft,
                modifier = Modifier.weight(1f),
            )
            FwPill(text = "Estimate", tone = PillTone.AMBER)
        }
        Text(
            "The mileage figure is an estimate for your records, not tax advice.",
            style = Fw.type.meta,
            color = Fw.colors.inkSoft,
        )
    }
}

@Composable
private fun LedgerCard(state: PayoutsUiState, nowEpochMs: Long) {
    FwCard {
        FwSectionRule(label = "Ledger")
        Spacer(Modifier.height(Fw.space.xs))
        if (!state.hasLedger) {
            Text(
                "No ledger entries yet. Earnings appear here when a sealed packet is approved.",
                style = Fw.type.body,
                color = Fw.colors.inkSoft,
            )
            return@FwCard
        }
        state.ledgerDays.forEachIndexed { dayIndex, day ->
            if (dayIndex > 0) Spacer(Modifier.height(Fw.space.md))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = day.dayLabel,
                    style = Fw.type.micro,
                    color = Fw.colors.inkSoft,
                    modifier = Modifier.weight(1f),
                )
                FwMoneyText(
                    cents = day.totalCents,
                    style = Fw.type.numeric,
                    signed = true,
                    color = Fw.colors.inkSoft,
                )
            }
            day.rows.forEach { row ->
                Spacer(Modifier.height(Fw.space.xs))
                Row(verticalAlignment = Alignment.Top) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(row.kindLabel, style = Fw.type.bodyStrong, color = Fw.colors.ink)
                        Text(
                            text = row.description,
                            style = Fw.type.meta,
                            color = Fw.colors.inkSoft,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = PayoutsTime.clockLabel(row.atEpochMs, nowEpochMs),
                            style = Fw.type.meta,
                            color = Fw.colors.inkSoft,
                        )
                    }
                    Spacer(Modifier.width(Fw.space.xs))
                    Column(horizontalAlignment = Alignment.End) {
                        FwMoneyText(
                            cents = row.amountCents,
                            style = Fw.type.numeric,
                            signed = true,
                            color = if (row.amountCents < 0) Fw.colors.flag else Fw.colors.ink,
                        )
                        FwPill(text = row.statusLabel, tone = PayoutsStyle.tone(row.tone))
                    }
                }
            }
        }
    }
}

@Composable
private fun PayoutHistoryCard(state: PayoutsUiState, nowEpochMs: Long) {
    FwCard {
        FwSectionRule(label = "Payout history")
        Spacer(Modifier.height(Fw.space.xs))
        if (!state.hasPayouts) {
            Text(
                "No payouts yet. Your first payout will appear here with its fee and status.",
                style = Fw.type.body,
                color = Fw.colors.inkSoft,
            )
            return@FwCard
        }
        state.payouts.forEachIndexed { index, payout ->
            if (index > 0) Spacer(Modifier.height(Fw.space.sm))
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(payout.modeLabel, style = Fw.type.bodyStrong, color = Fw.colors.ink)
                    Text(
                        text = buildString {
                            if (payout.destinationLast4.isNotBlank()) append("••${payout.destinationLast4} · ")
                            append("requested ${PayoutsTime.clockLabel(payout.requestedAtEpochMs, nowEpochMs)}")
                        },
                        style = Fw.type.meta,
                        color = Fw.colors.inkSoft,
                    )
                    Text(
                        text = "Amount ${Money.format(payout.amountCents)} · fee ${Money.format(payout.feeCents)}",
                        style = Fw.type.meta,
                        color = Fw.colors.inkSoft,
                    )
                    payout.completedAtEpochMs?.let { completed ->
                        Text(
                            "Completed ${PayoutsTime.clockLabel(completed, nowEpochMs)}",
                            style = Fw.type.meta,
                            color = Fw.colors.inkSoft,
                        )
                    }
                    payout.failureReason?.let { reason ->
                        Text("Failed: $reason", style = Fw.type.meta, color = Fw.colors.flag)
                        Text(
                            "No money moved. The balance stayed available; retry when the reason is resolved.",
                            style = Fw.type.meta,
                            color = Fw.colors.inkSoft,
                        )
                    }
                }
                Spacer(Modifier.width(Fw.space.xs))
                Column(horizontalAlignment = Alignment.End) {
                    FwMoneyText(cents = payout.netCents, style = Fw.type.numeric, color = Fw.colors.ink)
                    FwPill(text = payout.statusLabel, tone = PayoutsStyle.tone(payout.tone))
                }
            }
        }
    }
}

@Composable
private fun HowYouGetPaidCard(state: PayoutsUiState) {
    FwCard {
        FwSectionRule(label = "How you get paid")
        Spacer(Modifier.height(Fw.space.xs))
        DisclosureLine(
            "Instant to debit: ${state.instantFeePercentLabel} fee, minimum ${Money.format(state.instantFeeMinCents)}, maximum ${Money.format(state.instantFeeMaxCents)}. The fee is quoted in the sheet before you send.",
        )
        DisclosureLine("Standard ACH: free. Usually arrives in 1–2 business days.")
        DisclosureLine(
            "Requester-paid platform fee: ${state.platformFeePercentLabel}. It is charged to the requester and never deducted from your payout.",
        )
        DisclosureLine("Pending earnings become available when the requester settles the packet.")
        DisclosureLine("A failed payout moves no money. The balance stays available and the failure reason appears in your history.")
        DisclosureLine("No tokens, no crypto. Payouts go to your bank account only.")
    }
}

@Composable
private fun DisclosureLine(text: String) {
    Row(modifier = Modifier.padding(vertical = Fw.space.xxs)) {
        Text("·", style = Fw.type.body, color = Fw.colors.inkSoft)
        Spacer(Modifier.width(Fw.space.xxs))
        Text(text, style = Fw.type.body, color = Fw.colors.ink)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PayoutSheet(
    state: PayoutsUiState,
    onQuoteMode: (PayoutMode) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onClearError: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Fw.colors.sheet,
        shape = Fw.shape.sheet,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PayoutsStyle.gutter)
                .padding(bottom = Fw.space.xxl),
            verticalArrangement = Arrangement.spacedBy(Fw.space.sm),
        ) {
            Text("Move your balance", style = Fw.type.heading, color = Fw.colors.ink)
            FwSegmentedControl(
                options = listOf(PayoutMode.INSTANT.label, PayoutMode.STANDARD.label),
                selectedIndex = if (state.quoteMode == PayoutMode.INSTANT) 0 else 1,
                onSelect = { index ->
                    onQuoteMode(if (index == 0) PayoutMode.INSTANT else PayoutMode.STANDARD)
                },
            )
            val quote = state.quote
            if (quote != null) {
                SheetQuote(quote = quote)
            } else if (state.payoutError == null) {
                Text(
                    "Preparing the fee quote…",
                    style = Fw.type.body,
                    color = Fw.colors.inkSoft,
                )
            }
            state.payoutError?.let { error ->
                FwNotice(
                    title = "Payout not sent",
                    body = error,
                    tone = PillTone.FLAG,
                    actionLabel = "Dismiss",
                    onAction = onClearError,
                )
            }
            FwPrimaryButton(
                text = when (state.quoteMode) {
                    PayoutMode.INSTANT -> "Send instant payout"
                    PayoutMode.STANDARD -> "Send standard ACH"
                },
                onClick = onConfirm,
                modifier = Modifier.fillMaxWidth(),
                enabled = quote != null && !state.requesting,
                loading = state.requesting,
            )
            FwGhostButton(
                text = "Cancel",
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.requesting,
            )
            if (state.destinationLast4.isNotBlank()) {
                Text(
                    "Destination ••${state.destinationLast4}",
                    style = Fw.type.meta,
                    color = Fw.colors.inkSoft,
                )
            }
        }
    }
}

@Composable
private fun SheetQuote(quote: PayoutQuote) {
    Column(verticalArrangement = Arrangement.spacedBy(Fw.space.xxs)) {
        Text("AMOUNT", style = Fw.type.micro, color = Fw.colors.inkSoft)
        FwMoneyText(cents = quote.amountCents, style = Fw.type.numericLarge, color = Fw.colors.ink)
        Spacer(Modifier.height(Fw.space.xs))
        Row(horizontalArrangement = Arrangement.spacedBy(Fw.space.lg)) {
            MoneyFigure(label = "Fee", cents = quote.feeCents)
            MoneyFigure(label = "You receive", cents = quote.netCents)
        }
        Text(
            "Arrives ${quote.etaLabel}",
            style = Fw.type.body,
            color = Fw.colors.ink,
        )
        Text(
            quote.feeDisclosure,
            style = Fw.type.meta,
            color = Fw.colors.inkSoft,
        )
    }
}

@Composable
private fun MoneyFigure(label: String, cents: Int, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(label.uppercase(), style = Fw.type.micro, color = Fw.colors.inkSoft)
        FwMoneyText(cents = cents, style = Fw.type.numeric, color = Fw.colors.ink)
    }
}

@Composable
private fun TextFigure(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(label.uppercase(), style = Fw.type.micro, color = Fw.colors.inkSoft)
        Text(value, style = Fw.type.numeric, color = Fw.colors.ink)
    }
}

/** 1,609.344 m per mile: the model's canonical distance unit is metres. */
private const val METERS_PER_MILE = 1_609.344
