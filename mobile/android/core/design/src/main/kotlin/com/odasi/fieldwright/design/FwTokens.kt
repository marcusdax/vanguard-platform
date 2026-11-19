package com.odasi.fieldwright.design

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Fieldwright design tokens — the single source of truth for colour, type, spacing,
 * shape, size and motion (see docs/CONTRACTS.md §3).
 *
 * The palette is light-only this sprint: a dark scheme is a documented roadmap item,
 * not a hidden toggle. That is why [Fw] exposes plain values instead of a
 * `CompositionLocal` — there is exactly one theme, and a feature may read a token
 * from any context (composable or not) without guessing.
 *
 * Feature code must never write a raw hex colour or a literal dp. Everything comes
 * from [Fw].
 */

/**
 * Bundled typefaces. Fraunces is the serif voice (display and titles); Outfit is the
 * UI/data voice. The TTFs are checked into `core/design/src/main/res/font`.
 */
object FwFonts {
    val fraunces: FontFamily = FontFamily(
        Font(resId = R.font.fraunces_500, weight = FontWeight.Medium),
        Font(resId = R.font.fraunces_600, weight = FontWeight.SemiBold),
    )

    val outfit: FontFamily = FontFamily(
        Font(resId = R.font.outfit_400, weight = FontWeight.Normal),
        Font(resId = R.font.outfit_500, weight = FontWeight.Medium),
        Font(resId = R.font.outfit_600, weight = FontWeight.SemiBold),
    )
}

/**
 * The Fieldwright palette. Warm parchment, olive action, oxide evidence marks.
 * Contrast is deliberate: [ink] is AAA on [desk]/[sheet], [inkSoft] is AA on [desk].
 */
@Immutable
data class FwColors(
    /** App background. Warm parchment reduces glare outdoors. */
    val desk: Color = Color(0xFFEFE6D4),
    /** Cards, sheets, dock surface. */
    val sheet: Color = Color(0xFFFFFDF7),
    /** Primary text. */
    val ink: Color = Color(0xFF1F1C16),
    /** Secondary text and metadata. */
    val inkSoft: Color = Color(0xFF6E6556),
    /** Primary action, active tab, seal, positive. One action accent per surface. */
    val olive: Color = Color(0xFF4E5D2C),
    /** Pressed state and high-emphasis text on an olive tint. */
    val oliveDeep: Color = Color(0xFF38451F),
    /** Accent rules and evidence/location marks. Never body text on desk. */
    val oxide: Color = Color(0xFF94451F),
    /** 1 dp borders and dividers. */
    val line: Color = Color(0xFFD8CBB4),
    /** Pending / in review. */
    val amber: Color = Color(0xFF9A6414),
    /** Flagged / blocking. Contrast-safe on sheet. */
    val flag: Color = Color(0xFF8C2F1F),
    /** Map canvas base. */
    val mapPaper: Color = Color(0xFFE9DFC9),
    /** Map roads. */
    val mapRoad: Color = Color(0xFFD3C3A6),
    /** Map parcels. */
    val mapParcel: Color = Color(0xFFF4EDDD),
    /** Selected rows and chip backgrounds. */
    val oliveTint: Color = Color(0xFFE7E8D6),
    /** Elevation shadow only (8% ink). Never a paint colour. */
    val sheetShadow: Color = Color(0x1414110B),
)

/**
 * The type scale. Display and title styles are Fraunces; UI, body and data are Outfit.
 * Money and hashes are tabular (`tnum`) because numerals are evidence (§3.4).
 */
@Immutable
data class FwType(
    /** 40/44 Fraunces 600 — screen titles. */
    val displayXl: TextStyle = TextStyle(
        fontFamily = FwFonts.fraunces,
        fontWeight = FontWeight.SemiBold,
        fontSize = 40.sp,
        lineHeight = 44.sp,
    ),
    /** 32/38 Fraunces 600 — hero numbers and long packet titles. */
    val displayL: TextStyle = TextStyle(
        fontFamily = FwFonts.fraunces,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 38.sp,
    ),
    /** 24/30 Fraunces 600 — card titles. */
    val title: TextStyle = TextStyle(
        fontFamily = FwFonts.fraunces,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
    ),
    /** 18/24 Fraunces 500 — section heads. */
    val heading: TextStyle = TextStyle(
        fontFamily = FwFonts.fraunces,
        fontWeight = FontWeight.Medium,
        fontSize = 18.sp,
        lineHeight = 24.sp,
    ),
    /** 15/22 Outfit 400 — paragraphs and notes. */
    val body: TextStyle = TextStyle(
        fontFamily = FwFonts.outfit,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    /** 15/22 Outfit 500 — card values. */
    val bodyStrong: TextStyle = TextStyle(
        fontFamily = FwFonts.outfit,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    /** 13/18 Outfit 500 — buttons, chips, tabs. */
    val label: TextStyle = TextStyle(
        fontFamily = FwFonts.outfit,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    /** 12/16 Outfit 400 — timestamps, addresses, hints. */
    val meta: TextStyle = TextStyle(
        fontFamily = FwFonts.outfit,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    /** 11/14 Outfit 500, 0.08em tracking, uppercase — overline labels. */
    val micro: TextStyle = TextStyle(
        fontFamily = FwFonts.outfit,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.08.em,
    ),
    /** 15/20 Outfit 500, tabular — money, distances, counts. */
    val numeric: TextStyle = TextStyle(
        fontFamily = FwFonts.outfit,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        fontFeatureSettings = "tnum",
    ),
    /** 28/34 Outfit 600, tabular — balance figures. */
    val numericLarge: TextStyle = TextStyle(
        fontFamily = FwFonts.outfit,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        fontFeatureSettings = "tnum",
    ),
    /** 13/18 Outfit 500, tabular — chain fingerprints and short hashes. */
    val hash: TextStyle = TextStyle(
        fontFamily = FwFonts.outfit,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.02.em,
        fontFeatureSettings = "tnum",
    ),
)

/**
 * Spacing scale 4/8/12/16/24/32/48 plus the screen gutter. Named steps are canonical;
 * [s4]…[s48] are explicit aliases for code that wants the raw step number.
 */
@Immutable
data class FwSpace(
    val xxs: Dp = 4.dp,
    val xs: Dp = 8.dp,
    val sm: Dp = 12.dp,
    val md: Dp = 16.dp,
    val lg: Dp = 24.dp,
    val xl: Dp = 32.dp,
    val xxl: Dp = 48.dp,
    /** Screen gutter: 16 dp, or 20 dp at ≥ 600 dp width (the screen picks gutterWide). */
    val gutter: Dp = 16.dp,
    val gutterWide: Dp = 20.dp,
    val s4: Dp = 4.dp,
    val s8: Dp = 8.dp,
    val s12: Dp = 12.dp,
    val s16: Dp = 16.dp,
    val s24: Dp = 24.dp,
    val s32: Dp = 32.dp,
    val s48: Dp = 48.dp,
)

/** Radii. Cards are flat with a 1 dp border; only the dock and sheets cast shadow. */
@Immutable
data class FwShapes(
    val button: RoundedCornerShape = RoundedCornerShape(6.dp),
    val card: RoundedCornerShape = RoundedCornerShape(10.dp),
    val sheet: RoundedCornerShape = RoundedCornerShape(16.dp),
    val pill: RoundedCornerShape = RoundedCornerShape(percent = 50),
)

/** Fixed sizes that carry meaning (rules, targets, marks). Everything else is spacing. */
@Immutable
data class FwDimens(
    /** 1 dp hairline for borders and dividers. */
    val hairline: Dp = 1.dp,
    /** The signature olive rule: 64 × 2 dp at the top of a packet card. */
    val ruleWidth: Dp = 64.dp,
    val ruleHeight: Dp = 2.dp,
    /** Active dock underline: 24 dp oxide, at the top edge. */
    val dockUnderlineWidth: Dp = 24.dp,
    val dockUnderlineHeight: Dp = 2.dp,
    /** Minimum touch target. Nothing interactive is smaller. */
    val touchTarget: Dp = 48.dp,
    val dockItemHeight: Dp = 56.dp,
    val topBarHeight: Dp = 64.dp,
    val cardPadding: Dp = 16.dp,
    val cardGap: Dp = 12.dp,
    val icon: Dp = 22.dp,
    val iconSmall: Dp = 18.dp,
    val iconLarge: Dp = 28.dp,
    val dot: Dp = 8.dp,
    val dotSmall: Dp = 6.dp,
    val statusDot: Dp = 10.dp,
    val seal: Dp = 40.dp,
    val mapHeight: Dp = 280.dp,
    val mapPinRadius: Dp = 9.dp,
    val mapPinTarget: Dp = 48.dp,
    val operatorDot: Dp = 9.dp,
    val countdownSize: Dp = 44.dp,
    val countdownStroke: Dp = 3.dp,
    val progressStepDot: Dp = 18.dp,
    val progressConnector: Dp = 2.dp,
    val thumbnail: Dp = 64.dp,
    /** Generic 2 dp stroke for indicators, glyphs and rings. */
    val stroke: Dp = 2.dp,
    val dockElevation: Dp = 8.dp,
)

/** Opacity levels that are part of the design language, not one-off tweaks. */
@Immutable
data class FwAlpha(
    /** De-emphasise a surface (muted map while off the clock). */
    val muted: Float = 0.55f,
    /** Disabled controls. */
    val disabled: Float = 0.38f,
    /** Fill behind a tinted notice or chip. */
    val tint: Float = 0.10f,
    /** Border of a tinted notice or chip. */
    val border: Float = 0.32f,
)

/**
 * Motion 120–180 ms with FastOutSlowIn. Non-essential animation is removed when the
 * device animator duration scale is 0 (see [fwAnimationsEnabled]).
 */
@Immutable
data class FwMotion(
    val fastMs: Int = 120,
    val standardMs: Int = 180,
    val easing: Easing = FastOutSlowInEasing,
)

/** The single entry point for design tokens: `Fw.colors.olive`, `Fw.space.md`, … */
object Fw {
    val colors: FwColors = FwColors()
    val type: FwType = FwType()
    val space: FwSpace = FwSpace()
    val shape: FwShapes = FwShapes()
    val dimens: FwDimens = FwDimens()
    val alpha: FwAlpha = FwAlpha()
    val motion: FwMotion = FwMotion()
    val fonts: FwFonts = FwFonts
}
