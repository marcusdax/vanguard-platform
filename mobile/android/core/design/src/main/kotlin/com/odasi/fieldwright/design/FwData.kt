package com.odasi.fieldwright.design

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.odasi.fieldwright.model.Money

/** How many fingerprint characters are shown before the copy affordance. */
private const val SHORT_HASH_CHARS = 8

/**
 * A status pill. Tone means exactly one thing: olive positive, amber pending, flag
 * blocking, neutral metadata, oxide evidence/location. The pill is not interactive, so it
 * is deliberately smaller than a 48 dp target — the row it sits in owns the target.
 */
@Composable
fun FwPill(text: String, tone: PillTone, modifier: Modifier = Modifier) {
    val accent = tone.foreground()
    Row(
        modifier = modifier
            .clip(Fw.shape.pill)
            .background(accent.copy(alpha = Fw.alpha.tint))
            .border(Fw.dimens.hairline, accent.copy(alpha = Fw.alpha.border), Fw.shape.pill)
            .padding(horizontal = Fw.space.xs, vertical = Fw.space.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text.uppercase(),
            style = Fw.type.micro,
            color = tone.emphasis(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Money, always tabular and always formatted by the domain's canonical [Money.format].
 * `signed` adds a leading "+" for positive ledger amounts; negatives use a true minus.
 */
@Composable
fun FwMoneyText(
    cents: Int,
    style: TextStyle = Fw.type.numeric,
    signed: Boolean = false,
    showCents: Boolean? = null,
    color: Color = Color.Unspecified,
    modifier: Modifier = Modifier,
) {
    Text(
        text = Money.format(
            cents = cents,
            showCents = showCents ?: (cents % 100 != 0),
            signed = signed,
        ),
        style = style,
        color = color.takeOrElse { Fw.colors.ink },
        modifier = modifier,
    )
}

/** Label / value row. Label is quiet meta on the left, value is the reading on the right. */
@Composable
fun FwKeyValue(
    label: String,
    value: String,
    valueStyle: TextStyle = Fw.type.bodyStrong,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Fw.space.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = Fw.type.meta,
            color = Fw.colors.inkSoft,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(Fw.space.sm))
        Text(
            text = value,
            style = valueStyle,
            color = Fw.colors.ink,
            textAlign = TextAlign.End,
        )
    }
}

/**
 * A labelled figure for a strip or summary: overline, value, optional caption. The value is
 * a serif heading because it is a claim, not a table cell.
 */
@Composable
fun FwStat(
    label: String,
    value: String,
    caption: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = label.uppercase(),
            style = Fw.type.micro,
            color = Fw.colors.inkSoft,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(Fw.space.xxs))
        Text(
            text = value,
            style = Fw.type.heading,
            color = Fw.colors.ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (caption != null) {
            Spacer(Modifier.height(Fw.space.xxs))
            Text(
                text = caption,
                style = Fw.type.meta,
                color = Fw.colors.inkSoft,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Chain fingerprint: the first eight characters of a hash plus a copy target. It shows the
 * short form on purpose — a full hash belongs in the packet export, not on a card.
 */
@Composable
fun FwChainHash(
    hash: String,
    label: String? = null,
    copyContentDescription: String = FwA11y.copyHash,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (label != null) {
            Text(
                text = label.uppercase(),
                style = Fw.type.micro,
                color = Fw.colors.inkSoft,
            )
            Spacer(Modifier.width(Fw.space.xs))
        }
        Text(
            text = hash.take(SHORT_HASH_CHARS),
            style = Fw.type.hash,
            color = Fw.colors.ink,
        )
        Spacer(Modifier.width(Fw.space.xxs))
        Box(
            modifier = Modifier
                .size(Fw.dimens.touchTarget)
                .clip(Fw.shape.pill)
                .clickable { clipboard.setText(AnnotatedString(hash)) }
                .semantics {
                    contentDescription = copyContentDescription
                    role = Role.Button
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.ContentCopy,
                contentDescription = null,
                tint = Fw.colors.inkSoft,
                modifier = Modifier.size(Fw.dimens.iconSmall),
            )
        }
    }
}

/**
 * Workflow progress across named steps. `current` is the 0-based index of the step the
 * operator is on. Tier and rank are never progress bars (§3.4) — this is only for a
 * job's linear workflow.
 */
@Composable
fun FwProgressSteps(
    labels: List<String>,
    current: Int,
    modifier: Modifier = Modifier,
) {
    if (labels.isEmpty()) return
    val safeCurrent = current.coerceIn(0, labels.lastIndex)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics { stateDescription = labels[safeCurrent] },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            labels.forEachIndexed { index, _ ->
                if (index > 0) {
                    Box(
                        Modifier
                            .weight(1f)
                            .height(Fw.dimens.progressConnector)
                            .background(
                                if (index <= safeCurrent) Fw.colors.olive else Fw.colors.line,
                            ),
                    )
                }
                StepDot(index = index, current = safeCurrent)
            }
        }
        Spacer(Modifier.height(Fw.space.xs))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Fw.space.xxs),
        ) {
            labels.forEachIndexed { index, label ->
                Text(
                    text = label.uppercase(),
                    style = Fw.type.micro,
                    color = if (index == safeCurrent) Fw.colors.oliveDeep else Fw.colors.inkSoft,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun StepDot(index: Int, current: Int) {
    val dot = Fw.dimens.progressStepDot
    when {
        index < current -> Box(
            modifier = Modifier
                .size(dot)
                .clip(CircleShape)
                .background(Fw.colors.olive),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = null,
                tint = Fw.colors.sheet,
                modifier = Modifier.size(dot / 2f),
            )
        }

        index == current -> Box(
            modifier = Modifier
                .size(dot)
                .clip(CircleShape)
                .background(Fw.colors.olive),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(dot / 3f)
                    .clip(CircleShape)
                    .background(Fw.colors.sheet),
            )
        }

        else -> Box(
            Modifier
                .size(dot)
                .clip(CircleShape)
                .background(Fw.colors.sheet)
                .border(Fw.dimens.hairline, Fw.colors.line, CircleShape),
        )
    }
}

/**
 * The 45-second offer clock. The ring drains from olive to amber to flag so urgency is
 * readable at a glance; the number inside is exact and tabular. Animation is dropped when
 * the device asks for reduce-motion.
 */
@Composable
fun FwCountdownRing(
    remainingMillis: Long,
    totalMillis: Long,
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    val fraction = if (totalMillis <= 0L) {
        0f
    } else {
        (remainingMillis.toFloat() / totalMillis.toFloat()).coerceIn(0f, 1f)
    }
    val animationsEnabled = fwAnimationsEnabled()
    val animatedFraction by animateFloatAsState(
        targetValue = fraction,
        animationSpec = tween(durationMillis = Fw.motion.fastMs, easing = LinearEasing),
        label = "offer-countdown",
    )
    val shownFraction = if (animationsEnabled) animatedFraction else fraction
    val arcColor = when {
        fraction > 0.5f -> Fw.colors.olive
        fraction > 0.2f -> Fw.colors.amber
        else -> Fw.colors.flag
    }
    val seconds = ((remainingMillis + 999L) / 1000L).coerceAtLeast(0L)
    Box(
        modifier = modifier
            .size(Fw.dimens.countdownSize)
            .semantics {
                if (label != null) contentDescription = label
                stateDescription = "${seconds}s"
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = Fw.dimens.countdownStroke.toPx()
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
            if (shownFraction > 0f) {
                drawArc(
                    color = arcColor,
                    startAngle = -90f,
                    sweepAngle = 360f * shownFraction,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
        Text(
            text = "${seconds}s",
            style = Fw.type.numeric,
            color = Fw.colors.ink,
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}

@Preview(name = "Data display", showBackground = true, widthDp = 360)
@Composable
private fun FwDataPreview() {
    FwPreviewSurface {
        Row(horizontalArrangement = Arrangement.spacedBy(Fw.space.xs)) {
            FwPill(text = "Surge 1.4×", tone = PillTone.AMBER)
            FwPill(text = "Auto-accept", tone = PillTone.OLIVE)
            FwPill(text = "Override", tone = PillTone.OXIDE)
            FwPill(text = "Draft", tone = PillTone.NEUTRAL)
        }
        Spacer(Modifier.height(Fw.space.md))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                FwMoneyText(cents = 42_000, style = Fw.type.numericLarge)
                FwMoneyText(cents = 3_150, style = Fw.type.numeric, signed = true)
            }
            FwCountdownRing(remainingMillis = 32_000L, totalMillis = 45_000L)
            FwCountdownRing(remainingMillis = 7_000L, totalMillis = 45_000L)
        }
        Spacer(Modifier.height(Fw.space.md))
        FwKeyValue(label = "Bounty", value = "$420.00")
        FwKeyValue(label = "Requester-paid platform fee", value = "$63.00 · 15%")
        FwKeyValue(label = "Site", value = "1841 Creekside Dr")
        Spacer(Modifier.height(Fw.space.md))
        FwStat(label = "Demand", value = "High", caption = "12 open jobs")
        Spacer(Modifier.height(Fw.space.md))
        FwChainHash(hash = "9f2c41d8e77ab3c0d9e1f4a5b6c7d8e9", label = "Chain head")
        Spacer(Modifier.height(Fw.space.md))
        FwProgressSteps(labels = listOf("Accepted", "En route", "On site", "Capture"), current = 1)
    }
}
