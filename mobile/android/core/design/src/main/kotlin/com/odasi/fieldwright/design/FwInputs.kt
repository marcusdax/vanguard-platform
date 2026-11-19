package com.odasi.fieldwright.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview

/**
 * A Fieldwright form field: sheet fill, 1 dp Line border that turns olive on focus.
 * The label is always visible (never a placeholder-only field) and doubles as the
 * accessibility name.
 */
@Composable
fun FwTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    val placeholderContent: (@Composable () -> Unit)? = if (placeholder.isNotEmpty()) {
        { Text(text = placeholder, style = Fw.type.body, color = Fw.colors.inkSoft) }
    } else {
        null
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Fw.dimens.touchTarget),
        label = { Text(text = label, style = Fw.type.meta) },
        placeholder = placeholderContent,
        singleLine = singleLine,
        textStyle = Fw.type.body,
        shape = Fw.shape.button,
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType,
            imeAction = if (singleLine) ImeAction.Done else ImeAction.Default,
        ),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Fw.colors.olive,
            unfocusedBorderColor = Fw.colors.line,
            focusedLabelColor = Fw.colors.olive,
            unfocusedLabelColor = Fw.colors.inkSoft,
            cursorColor = Fw.colors.olive,
            focusedTextColor = Fw.colors.ink,
            unfocusedTextColor = Fw.colors.ink,
            focusedContainerColor = Fw.colors.sheet,
            unfocusedContainerColor = Fw.colors.sheet,
            focusedPlaceholderColor = Fw.colors.inkSoft,
            unfocusedPlaceholderColor = Fw.colors.inkSoft,
        ),
    )
}

/**
 * A setting row. The whole row is the switch — the label is a 48 dp target, not a caption
 * you have to hit precisely.
 */
@Composable
fun FwSwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Fw.dimens.touchTarget)
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(vertical = Fw.space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = Fw.type.bodyStrong,
                color = Fw.colors.ink,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(Fw.space.xxs))
                Text(
                    text = subtitle,
                    style = Fw.type.meta,
                    color = Fw.colors.inkSoft,
                )
            }
        }
        Spacer(Modifier.width(Fw.space.md))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.clearAndSetSemantics { },
            colors = fwSwitchColors(),
        )
    }
}

/**
 * The availability control: a status dot, the caller's status line (which names the area
 * and radius) and the online switch. Turning it off stops dispatch; the copy is supplied by
 * the screen so the component carries no product strings.
 */
@Composable
fun FwAvailabilityToggle(
    isOnline: Boolean,
    statusLine: String,
    onToggle: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val shape = Fw.shape.card
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (isOnline) Fw.colors.oliveTint else Fw.colors.sheet)
            .border(
                width = Fw.dimens.hairline,
                color = if (isOnline) Fw.colors.olive else Fw.colors.line,
                shape = shape,
            )
            .padding(Fw.space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(Fw.dimens.statusDot)
                .clip(CircleShape)
                .background(if (isOnline) Fw.colors.olive else Fw.colors.line),
        )
        Spacer(Modifier.width(Fw.space.md))
        Text(
            text = statusLine,
            style = Fw.type.bodyStrong,
            color = if (isOnline) Fw.colors.oliveDeep else Fw.colors.ink,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(Fw.space.md))
        Switch(
            checked = isOnline,
            onCheckedChange = { onToggle() },
            enabled = enabled,
            colors = fwSwitchColors(),
        )
    }
}

/** The four checklist states a step can be in. */
enum class ChecklistRowState(val label: String) {
    /** Not yet recorded. */
    OPEN("Open"),

    /** Recorded and passing its gate. */
    PASS("Pass"),

    /** Recorded and failing its gate. */
    FAIL("Fail"),

    /** Not applicable to this site, with a reason on file. */
    NA("N/A"),
}

/**
 * One checklist step. State is carried by a glyph and a `stateDescription`, never by
 * colour alone; `onClick` is what makes the row interactive (and is then a 48 dp target).
 */
@Composable
fun FwChecklistRow(
    label: String,
    hint: String,
    state: ChecklistRowState,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Fw.dimens.touchTarget)
            .then(
                if (onClick != null) {
                    Modifier.clickable(role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(vertical = Fw.space.xs)
            .semantics { stateDescription = state.label },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChecklistGlyph(state = state)
        Spacer(Modifier.width(Fw.space.md))
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = Fw.type.bodyStrong,
                color = if (state == ChecklistRowState.FAIL) Fw.colors.flag else Fw.colors.ink,
            )
            if (hint.isNotEmpty()) {
                Spacer(Modifier.height(Fw.space.xxs))
                Text(
                    text = hint,
                    style = Fw.type.meta,
                    color = Fw.colors.inkSoft,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (onClick != null) {
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = Fw.colors.inkSoft,
                modifier = Modifier.size(Fw.dimens.icon),
            )
        }
    }
}

@Composable
private fun ChecklistGlyph(state: ChecklistRowState) {
    val size = Fw.dimens.iconLarge
    val base = Modifier.size(size).clip(CircleShape)
    when (state) {
        ChecklistRowState.PASS -> Box(
            modifier = base.background(Fw.colors.olive),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = null,
                tint = Fw.colors.sheet,
                modifier = Modifier.size(Fw.dimens.iconSmall),
            )
        }

        ChecklistRowState.FAIL -> Box(
            modifier = base.background(Fw.colors.flag),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = null,
                tint = Fw.colors.sheet,
                modifier = Modifier.size(Fw.dimens.iconSmall),
            )
        }

        ChecklistRowState.OPEN -> Box(
            modifier = base.border(Fw.dimens.hairline, Fw.colors.line, CircleShape),
        )

        ChecklistRowState.NA -> Box(
            modifier = base.border(Fw.dimens.hairline, Fw.colors.line, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(width = Fw.space.xs, height = Fw.dimens.hairline)
                    .background(Fw.colors.inkSoft),
            )
        }
    }
}

@Composable
private fun fwSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = Fw.colors.sheet,
    checkedTrackColor = Fw.colors.olive,
    checkedBorderColor = Fw.colors.olive,
    uncheckedThumbColor = Fw.colors.sheet,
    uncheckedTrackColor = Fw.colors.desk,
    uncheckedBorderColor = Fw.colors.line,
)

@Preview(name = "Inputs", showBackground = true, widthDp = 360)
@Composable
private fun FwInputsPreview() {
    FwPreviewSurface {
        FwTextField(
            value = "Tam Nguyen",
            onValueChange = {},
            label = "Signed by",
            placeholder = "Full name",
        )
        Spacer(Modifier.height(Fw.space.md))
        FwSwitchRow(
            title = "Auto-accept under $30",
            subtitle = "Max 2 stacked · written policy, every accept has a reason",
            checked = true,
            onCheckedChange = {},
        )
        Spacer(Modifier.height(Fw.space.md))
        FwAvailabilityToggle(
            isOnline = true,
            statusLine = "Online · 15 km · Creekside",
            onToggle = {},
        )
        Spacer(Modifier.height(Fw.space.sm))
        FwAvailabilityToggle(
            isOnline = false,
            statusLine = "Off the clock · 15 km · Creekside",
            onToggle = {},
        )
        Spacer(Modifier.height(Fw.space.md))
        FwChecklistRow(
            label = "Vault polygon",
            hint = "Photo · required",
            state = ChecklistRowState.PASS,
            onClick = {},
        )
        FwChecklistRow(
            label = "Gauge face",
            hint = "Measurement · required · not yet recorded",
            state = ChecklistRowState.OPEN,
            onClick = {},
        )
        FwChecklistRow(
            label = "Test cocks",
            hint = "Photo · failed framing gate",
            state = ChecklistRowState.FAIL,
            onClick = {},
        )
        FwChecklistRow(
            label = "Building interior",
            hint = "Not in consent scope",
            state = ChecklistRowState.NA,
            onClick = null,
        )
    }
}
