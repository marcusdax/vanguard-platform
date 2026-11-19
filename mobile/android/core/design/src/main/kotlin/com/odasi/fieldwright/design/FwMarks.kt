package com.odasi.fieldwright.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlin.math.cos
import kotlin.math.sin

/**
 * The supplied Fieldwright mark: a circle + crosshair. Used for the app identity and,
 * via [FwSealMark], for anything that claims to be sealed or verified.
 *
 * The mark is decorative next to a printed product name, so it clears its own semantics
 * rather than shouting a brand string at a screen reader.
 */
@Composable
fun FwLogoMark(size: Dp = 32.dp, tint: Color = Fw.colors.olive) {
    Canvas(
        modifier = Modifier
            .size(size)
            .clearAndSetSemantics { },
    ) {
        val radius = this.size.minDimension / 2f
        val center = this.center
        val stroke = Fw.dimens.stroke.toPx()
        drawCircle(
            color = tint,
            radius = radius - stroke / 2f,
            style = Stroke(width = stroke),
        )
        val tickInner = radius * 0.62f
        val tickOuter = radius - stroke
        listOf(0f, 90f, 180f, 270f).forEach { degrees ->
            val radians = Math.toRadians(degrees.toDouble())
            val dx = cos(radians).toFloat()
            val dy = sin(radians).toFloat()
            drawLine(
                color = tint,
                start = Offset(center.x + dx * tickInner, center.y + dy * tickInner),
                end = Offset(center.x + dx * tickOuter, center.y + dy * tickOuter),
                strokeWidth = stroke,
            )
        }
        drawCircle(color = tint, radius = radius * 0.16f)
    }
}

/**
 * The seal mark. Sealed is a filled olive disc with a crosshair cut in sheet; unsealed is
 * a dashed outline. State is exposed to accessibility, never carried by colour alone.
 */
@Composable
fun FwSealMark(
    sealed: Boolean,
    size: Dp = 40.dp,
    tint: Color = Fw.colors.olive,
) {
    Canvas(
        modifier = Modifier
            .size(size)
            .semantics { contentDescription = if (sealed) FwA11y.sealed else FwA11y.notSealed },
    ) {
        val radius = this.size.minDimension / 2f
        val center = this.center
        val stroke = Fw.dimens.stroke.toPx()
        if (sealed) {
            drawCircle(color = tint, radius = radius)
            drawCircle(
                color = Fw.colors.sheet,
                radius = radius * 0.78f,
                style = Stroke(width = stroke * 0.75f),
            )
            val tickInner = radius * 0.40f
            val tickOuter = radius * 0.58f
            listOf(0f, 90f, 180f, 270f).forEach { degrees ->
                val radians = Math.toRadians(degrees.toDouble())
                val dx = cos(radians).toFloat()
                val dy = sin(radians).toFloat()
                drawLine(
                    color = Fw.colors.sheet,
                    start = Offset(center.x + dx * tickInner, center.y + dy * tickInner),
                    end = Offset(center.x + dx * tickOuter, center.y + dy * tickOuter),
                    strokeWidth = stroke,
                )
            }
            drawCircle(color = Fw.colors.sheet, radius = radius * 0.12f)
        } else {
            val dashed = PathEffect.dashPathEffect(floatArrayOf(radius * 0.28f, radius * 0.18f))
            drawCircle(
                color = tint.copy(alpha = Fw.alpha.disabled + 0.22f),
                radius = radius - stroke / 2f,
                style = Stroke(width = stroke, pathEffect = dashed),
            )
            val tickInner = radius * 0.46f
            val tickOuter = radius * 0.70f
            listOf(0f, 90f, 180f, 270f).forEach { degrees ->
                val radians = Math.toRadians(degrees.toDouble())
                val dx = cos(radians).toFloat()
                val dy = sin(radians).toFloat()
                drawLine(
                    color = tint.copy(alpha = Fw.alpha.muted),
                    start = Offset(center.x + dx * tickInner, center.y + dy * tickInner),
                    end = Offset(center.x + dx * tickOuter, center.y + dy * tickOuter),
                    strokeWidth = stroke * 0.75f,
                )
            }
            drawCircle(
                color = tint.copy(alpha = Fw.alpha.muted),
                radius = radius * 0.10f,
            )
        }
    }
}

@Preview(name = "Marks", showBackground = true, widthDp = 360)
@Composable
private fun FwMarksPreview() {
    FwPreviewSurface {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Fw.space.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FwLogoMark(size = 32.dp)
            FwLogoMark(size = 28.dp, tint = Fw.colors.oxide)
            FwSealMark(sealed = true, size = 40.dp)
            FwSealMark(sealed = false, size = 40.dp)
        }
    }
}
