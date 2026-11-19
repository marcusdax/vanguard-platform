package com.odasi.fieldwright.design

import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

/**
 * The Fieldwright theme. Material 3 is the base; the Fieldwright visual system is applied
 * on top through [Fw] tokens and the M3 scheme mapping below.
 *
 * Light-only this sprint (CONTRACTS §3.1): a dark scheme is a documented roadmap item,
 * not a hidden toggle. Every screen and every `@Preview` is wrapped in this theme.
 */
@Composable
fun FieldwrightTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = FwMaterial.colorScheme,
        typography = FwMaterial.typography,
        shapes = FwMaterial.shapes,
        content = content,
    )
}

/**
 * Material 3 mapping. M3 components that a feature drops in (Snackbar, Switch,
 * PullToRefresh indicator, dialogs) inherit the Fieldwright palette from here, so a
 * feature never has to re-colour an M3 component itself.
 */
internal object FwMaterial {

    private val c = Fw.colors

    val colorScheme = lightColorScheme(
        primary = c.olive,
        onPrimary = c.sheet,
        primaryContainer = c.oliveTint,
        onPrimaryContainer = c.oliveDeep,
        secondary = c.oxide,
        onSecondary = c.sheet,
        secondaryContainer = c.oliveTint,
        onSecondaryContainer = c.oliveDeep,
        tertiary = c.amber,
        onTertiary = c.sheet,
        tertiaryContainer = c.oliveTint,
        onTertiaryContainer = c.amber,
        background = c.desk,
        onBackground = c.ink,
        surface = c.sheet,
        onSurface = c.ink,
        surfaceVariant = c.oliveTint,
        onSurfaceVariant = c.inkSoft,
        surfaceTint = c.olive,
        surfaceContainerLowest = c.sheet,
        surfaceContainerLow = c.sheet,
        surfaceContainer = c.desk,
        surfaceContainerHigh = c.desk,
        surfaceContainerHighest = c.desk,
        outline = c.line,
        outlineVariant = c.line,
        error = c.flag,
        onError = c.sheet,
        errorContainer = c.flag.copy(alpha = 0.12f),
        onErrorContainer = c.flag,
        inverseSurface = c.ink,
        inverseOnSurface = c.sheet,
        inversePrimary = c.oliveTint,
        scrim = c.ink,
    )

    val typography = Typography(
        displayLarge = Fw.type.displayXl,
        displayMedium = Fw.type.displayL,
        displaySmall = Fw.type.title,
        headlineLarge = Fw.type.displayL,
        headlineMedium = Fw.type.title,
        headlineSmall = Fw.type.heading,
        titleLarge = Fw.type.title,
        titleMedium = Fw.type.heading,
        titleSmall = Fw.type.bodyStrong,
        bodyLarge = Fw.type.body,
        bodyMedium = Fw.type.body,
        bodySmall = Fw.type.meta,
        labelLarge = Fw.type.label,
        labelMedium = Fw.type.label,
        labelSmall = Fw.type.micro,
    )

    val shapes = Shapes(
        extraSmall = Fw.shape.button,
        small = Fw.shape.button,
        medium = Fw.shape.card,
        large = Fw.shape.sheet,
        extraLarge = Fw.shape.sheet,
    )
}

/**
 * Whether non-essential animation should play. False when the device animator duration
 * scale is 0 (Reduce motion / battery saver), per CONTRACTS §3.3. Essential state changes
 * still happen — only the tween is dropped.
 */
@Composable
fun fwAnimationsEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) != 0f
        }.getOrDefault(true)
    }
}

/**
 * Preview-only surface: the real page (parchment background, theme, screen gutter) so a
 * `@Preview` shows the product rather than a white rectangle.
 */
@Composable
internal fun FwPreviewSurface(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    FieldwrightTheme {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .background(Fw.colors.desk)
                .padding(Fw.space.md),
            content = content,
        )
    }
}
