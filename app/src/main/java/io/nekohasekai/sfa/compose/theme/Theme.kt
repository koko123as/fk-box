package io.nekohasekai.sfa.compose.theme

import android.app.Activity
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat

/** The page, card, block and inset surface levels, on Material 3 surface containers. */
enum class SurfaceLevel {
    Page,
    Card,
    Block,
    Inset,
    ;

    val next: SurfaceLevel get() = when (this) {
        Page -> Card
        Card -> Block
        Block, Inset -> Inset
    }

    fun colorOf(scheme: BoxScheme): Color = when (this) {
        Page -> scheme.surface
        Card -> scheme.surfaceContainer
        Block -> scheme.surfaceContainerHigh
        Inset -> scheme.surfaceContainerHighest
    }

    companion object {
        fun floatingOf(@Suppress("UNUSED_PARAMETER") scheme: BoxScheme): SurfaceLevel = Block
    }
}

val LocalBoxScheme = staticCompositionLocalOf { seededColorScheme(0xff007aff.toInt(), false) }

/** The surface the content sits on; a raised child takes [SurfaceLevel.next]. */
val LocalSurfaceLevel = staticCompositionLocalOf { SurfaceLevel.Page }

object BoxTheme {
    val scheme: BoxScheme
        @Composable @ReadOnlyComposable get() = LocalBoxScheme.current
}

/**
 * The app theme: the seeded scheme as Material 3's colour scheme, the stock
 * Material 3 type scale in the chosen font, the clamped text scale and the
 * system ripple.
 */
@Composable
fun Theme(
    scheme: BoxScheme,
    fontFamily: FontFamily?,
    textScale: Float,
    content: @Composable () -> Unit,
) {
    val materialScheme = remember(scheme) {
        if (scheme.dark) {
            darkColorScheme(
                primary = scheme.primary, onPrimary = scheme.onPrimary,
                primaryContainer = scheme.primaryContainer, onPrimaryContainer = scheme.onPrimaryContainer,
                inversePrimary = scheme.inversePrimary,
                secondary = scheme.secondary, onSecondary = scheme.onSecondary,
                secondaryContainer = scheme.secondaryContainer, onSecondaryContainer = scheme.onSecondaryContainer,
                tertiary = scheme.tertiary, onTertiary = scheme.onTertiary,
                tertiaryContainer = scheme.tertiaryContainer, onTertiaryContainer = scheme.onTertiaryContainer,
                background = scheme.surface, onBackground = scheme.onSurface,
                surface = scheme.surface, onSurface = scheme.onSurface,
                surfaceVariant = scheme.surfaceContainerHighest, onSurfaceVariant = scheme.onSurfaceVariant,
                surfaceTint = scheme.primary,
                inverseSurface = scheme.inverseSurface, inverseOnSurface = scheme.onInverseSurface,
                error = scheme.error, onError = scheme.onError,
                errorContainer = scheme.errorContainer, onErrorContainer = scheme.onErrorContainer,
                outline = scheme.outline, outlineVariant = scheme.outlineVariant, scrim = scheme.scrim,
                surfaceBright = scheme.surfaceBright, surfaceContainer = scheme.surfaceContainer,
                surfaceContainerHigh = scheme.surfaceContainerHigh, surfaceContainerHighest = scheme.surfaceContainerHighest,
                surfaceContainerLow = scheme.surfaceContainerLow, surfaceContainerLowest = scheme.surfaceContainerLowest,
                surfaceDim = scheme.surfaceDim,
            )
        } else {
            lightColorScheme(
                primary = scheme.primary, onPrimary = scheme.onPrimary,
                primaryContainer = scheme.primaryContainer, onPrimaryContainer = scheme.onPrimaryContainer,
                inversePrimary = scheme.inversePrimary,
                secondary = scheme.secondary, onSecondary = scheme.onSecondary,
                secondaryContainer = scheme.secondaryContainer, onSecondaryContainer = scheme.onSecondaryContainer,
                tertiary = scheme.tertiary, onTertiary = scheme.onTertiary,
                tertiaryContainer = scheme.tertiaryContainer, onTertiaryContainer = scheme.onTertiaryContainer,
                background = scheme.surface, onBackground = scheme.onSurface,
                surface = scheme.surface, onSurface = scheme.onSurface,
                surfaceVariant = scheme.surfaceContainerHighest, onSurfaceVariant = scheme.onSurfaceVariant,
                surfaceTint = scheme.primary,
                inverseSurface = scheme.inverseSurface, inverseOnSurface = scheme.onInverseSurface,
                error = scheme.error, onError = scheme.onError,
                errorContainer = scheme.errorContainer, onErrorContainer = scheme.onErrorContainer,
                outline = scheme.outline, outlineVariant = scheme.outlineVariant, scrim = scheme.scrim,
                surfaceBright = scheme.surfaceBright, surfaceContainer = scheme.surfaceContainer,
                surfaceContainerHigh = scheme.surfaceContainerHigh, surfaceContainerHighest = scheme.surfaceContainerHighest,
                surfaceContainerLow = scheme.surfaceContainerLow, surfaceContainerLowest = scheme.surfaceContainerLowest,
                surfaceDim = scheme.surfaceDim,
            )
        }
    }
    val typography = remember(fontFamily) { Typography().withFontFamily(fontFamily) }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !scheme.dark
                isAppearanceLightNavigationBars = !scheme.dark
            }
        }
    }
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalBoxScheme provides scheme,
        LocalAppFontFamily provides fontFamily,
        LocalDensity provides Density(density.density, textScale),
    ) {
        MaterialTheme(colorScheme = materialScheme, typography = typography) {
            CompositionLocalProvider(LocalContentColor provides scheme.onSurface, content = content)
        }
    }
}

private fun Typography.withFontFamily(family: FontFamily?): Typography {
    if (family == null) return this
    return Typography(
        displayLarge = displayLarge.copy(fontFamily = family),
        displayMedium = displayMedium.copy(fontFamily = family),
        displaySmall = displaySmall.copy(fontFamily = family),
        headlineLarge = headlineLarge.copy(fontFamily = family),
        headlineMedium = headlineMedium.copy(fontFamily = family),
        headlineSmall = headlineSmall.copy(fontFamily = family),
        titleLarge = titleLarge.copy(fontFamily = family),
        titleMedium = titleMedium.copy(fontFamily = family),
        titleSmall = titleSmall.copy(fontFamily = family),
        bodyLarge = bodyLarge.copy(fontFamily = family),
        bodyMedium = bodyMedium.copy(fontFamily = family),
        bodySmall = bodySmall.copy(fontFamily = family),
        labelLarge = labelLarge.copy(fontFamily = family),
        labelMedium = labelMedium.copy(fontFamily = family),
        labelSmall = labelSmall.copy(fontFamily = family),
    )
}
