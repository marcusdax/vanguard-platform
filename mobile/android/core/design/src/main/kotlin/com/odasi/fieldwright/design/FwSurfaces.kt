package com.odasi.fieldwright.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview

/**
 * The Fieldwright card: sheet surface, 1 dp Line border, 10 dp radius, flat (no shadow).
 * Packet cards put [FwSectionRule] at the top; that is the signature motif (§3.4).
 */
@Composable
fun FwCard(
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = Fw.shape.card
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Fw.colors.sheet)
            .border(Fw.dimens.hairline, Fw.colors.line, shape)
            .then(
                if (onClick != null) {
                    Modifier.clickable(role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(Fw.dimens.cardPadding),
        content = content,
    )
}

/**
 * The signature olive rule: 64 × 2 dp. With a label it becomes a section head (micro
 * overline); without one it is a pure rule and is hidden from accessibility.
 */
@Composable
fun FwSectionRule(label: String? = null, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (label == null) Modifier.clearAndSetSemantics { } else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = Fw.dimens.ruleWidth, height = Fw.dimens.ruleHeight)
                .background(Fw.colors.olive),
        )
        if (label != null) {
            Spacer(Modifier.width(Fw.space.xs))
            Text(
                text = label.uppercase(),
                style = Fw.type.micro,
                color = Fw.colors.inkSoft,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Inline notice: the app-level snackbar belongs to AppRoot, so screens report transient
 * state (offline, refresh failed, offer rejected) with a notice they own and can dismiss.
 * Tone carries meaning; the icon and title repeat it so colour is never the only signal.
 */
@Composable
fun FwNotice(
    title: String,
    body: String,
    tone: PillTone = PillTone.NEUTRAL,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val accent = tone.foreground()
    val shape = Fw.shape.card
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(accent.copy(alpha = Fw.alpha.tint))
            .border(Fw.dimens.hairline, accent.copy(alpha = Fw.alpha.border), shape)
            .padding(Fw.space.md),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = tone.icon(),
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(Fw.dimens.icon),
        )
        Spacer(Modifier.width(Fw.space.md))
        Column(Modifier.weight(1f)) {
            Text(text = title, style = Fw.type.bodyStrong, color = Fw.colors.ink)
            if (body.isNotEmpty()) {
                Spacer(Modifier.height(Fw.space.xxs))
                Text(text = body, style = Fw.type.meta, color = Fw.colors.inkSoft)
            }
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(Fw.space.xs))
                FwGhostButton(text = actionLabel, onClick = onAction)
            }
        }
    }
}

/**
 * Consent scope callout: an oxide rule on the left, an overline, then the scope text
 * exactly as the requester wrote it. The oxide accent marks evidence/location, never an
 * action — the rule is a quote mark, not a button.
 */
@Composable
fun FwScopeCallout(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
    ) {
        Box(
            Modifier
                .width(Fw.dimens.ruleHeight)
                .fillMaxHeight()
                .background(Fw.colors.oxide),
        )
        Spacer(Modifier.width(Fw.space.sm))
        Column(Modifier.weight(1f)) {
            Text(
                text = title.uppercase(),
                style = Fw.type.micro,
                color = Fw.colors.inkSoft,
            )
            Spacer(Modifier.height(Fw.space.xxs))
            Text(text = body, style = Fw.type.body, color = Fw.colors.ink)
        }
    }
}

/**
 * Empty / off-the-clock state. Centred, quiet, one optional action — it explains what the
 * screen would show rather than blaming the operator for the market being empty.
 */
@Composable
fun FwEmptyState(
    title: String,
    body: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(Fw.space.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        FwLogoMark(size = Fw.dimens.iconLarge, tint = Fw.colors.inkSoft)
        Spacer(Modifier.height(Fw.space.md))
        Text(
            text = title,
            style = Fw.type.title,
            color = Fw.colors.ink,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Fw.space.xs))
        Text(
            text = body,
            style = Fw.type.body,
            color = Fw.colors.inkSoft,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(Fw.space.md))
            FwSecondaryButton(text = actionLabel, onClick = onAction)
        }
    }
}

@Preview(name = "Surfaces", showBackground = true, widthDp = 360)
@Composable
private fun FwSurfacesPreview() {
    FwPreviewSurface {
        FwCard {
            FwSectionRule(label = "Vanguard Field packet")
            Spacer(Modifier.height(Fw.space.xs))
            Text(text = "FW-0994 · Panel T-14 is set", style = Fw.type.title, color = Fw.colors.ink)
            Spacer(Modifier.height(Fw.space.xs))
            Text(
                text = "Meridian Mutual · Sealed · $420",
                style = Fw.type.meta,
                color = Fw.colors.inkSoft,
            )
            Spacer(Modifier.height(Fw.space.md))
            Row(horizontalArrangement = Arrangement.spacedBy(Fw.space.xs)) {
                FwPill(text = "Sealed", tone = PillTone.OLIVE)
                FwPill(text = "In review", tone = PillTone.AMBER)
                FwPill(text = "Flagged", tone = PillTone.FLAG)
            }
        }
        Spacer(Modifier.height(Fw.space.md))
        FwNotice(
            title = "Offline",
            body = "3 changes are queued. They sync when you are back in coverage.",
            tone = PillTone.AMBER,
            actionLabel = "Retry now",
            onAction = {},
        )
        Spacer(Modifier.height(Fw.space.md))
        FwScopeCallout(
            title = "Consent scope",
            body = "The assembly inside the service vault. Not the building interior.",
        )
        Spacer(Modifier.height(Fw.space.md))
        FwEmptyState(
            title = "No jobs in your ring",
            body = "Dispatch is checking Creekside every few seconds. Pull down to refresh.",
            actionLabel = "Refresh",
            onAction = {},
        )
    }
}
