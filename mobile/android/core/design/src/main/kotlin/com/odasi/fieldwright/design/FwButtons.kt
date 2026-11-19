package com.odasi.fieldwright.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview

/**
 * Primary action. Olive fill, sheet ink, 48 dp minimum, one per surface.
 *
 * @param loading keeps the button's geometry stable while a repository call is in flight;
 * it is not a second, ghosted button.
 */
@Composable
fun FwPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = Fw.dimens.touchTarget),
        enabled = enabled && !loading,
        shape = Fw.shape.button,
        colors = ButtonDefaults.buttonColors(
            containerColor = Fw.colors.olive,
            contentColor = Fw.colors.sheet,
            disabledContainerColor = Fw.colors.olive.copy(alpha = Fw.alpha.disabled),
            disabledContentColor = Fw.colors.sheet.copy(alpha = Fw.alpha.disabled),
        ),
        contentPadding = PaddingValues(horizontal = Fw.space.lg, vertical = Fw.space.sm),
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(Fw.dimens.iconSmall),
                color = Fw.colors.sheet,
                strokeWidth = Fw.dimens.stroke,
            )
            Spacer(Modifier.width(Fw.space.xs))
        }
        Text(
            text = text,
            style = Fw.type.label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Secondary action: sheet fill, 1 dp olive border, olive ink. */
@Composable
fun FwSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = Fw.dimens.touchTarget),
        enabled = enabled,
        shape = Fw.shape.button,
        border = BorderStroke(Fw.dimens.hairline, Fw.colors.olive),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = Fw.colors.olive,
            disabledContentColor = Fw.colors.inkSoft.copy(alpha = Fw.alpha.disabled),
        ),
        contentPadding = PaddingValues(horizontal = Fw.space.lg, vertical = Fw.space.sm),
    ) {
        Text(
            text = text,
            style = Fw.type.label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Tertiary action: no fill, no border, olive ink. Still a full 48 dp target. */
@Composable
fun FwGhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = Fw.dimens.touchTarget),
        enabled = enabled,
        colors = ButtonDefaults.textButtonColors(
            contentColor = Fw.colors.olive,
            disabledContentColor = Fw.colors.inkSoft.copy(alpha = Fw.alpha.disabled),
        ),
        contentPadding = PaddingValues(horizontal = Fw.space.md, vertical = Fw.space.sm),
    ) {
        Text(
            text = text,
            style = Fw.type.label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Segmented control for a small set of mutually exclusive filters (Offers · Active · Done).
 * Each segment is a full 48 dp target and announces itself as a selected tab.
 */
@Composable
fun FwSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(Fw.shape.button)
            .background(Fw.colors.sheet)
            .border(Fw.dimens.hairline, Fw.colors.line, Fw.shape.button)
            .padding(Fw.space.xxs),
        horizontalArrangement = Arrangement.spacedBy(Fw.space.xxs),
    ) {
        options.forEachIndexed { index, option ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = Fw.dimens.touchTarget)
                    .clip(Fw.shape.button)
                    .background(if (selected) Fw.colors.olive else Color.Transparent)
                    .selectable(
                        selected = selected,
                        role = Role.Tab,
                        onClick = { onSelect(index) },
                    )
                    .padding(horizontal = Fw.space.xs),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = option,
                    style = Fw.type.label,
                    color = if (selected) Fw.colors.sheet else Fw.colors.inkSoft,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Preview(name = "Buttons", showBackground = true, widthDp = 360)
@Composable
private fun FwButtonsPreview() {
    FwPreviewSurface {
        FwPrimaryButton(text = "Accept offer", onClick = {})
        Spacer(Modifier.size(Fw.space.sm))
        FwPrimaryButton(text = "Accepting…", onClick = {}, loading = true)
        Spacer(Modifier.size(Fw.space.sm))
        FwSecondaryButton(text = "Open job", onClick = {})
        Spacer(Modifier.size(Fw.space.sm))
        FwGhostButton(text = "Pass", onClick = {})
        Spacer(Modifier.size(Fw.space.sm))
        FwSegmentedControl(
            options = listOf("Offers", "Active", "Done"),
            selectedIndex = 0,
            onSelect = {},
        )
    }
}
