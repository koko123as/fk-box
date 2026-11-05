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

/** flutter/lib/ui/surface.dart: the page, card, block and inset levels. */
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
        Page -> if (scheme.dark) scheme.surface else scheme.surfaceContainer
        Card -> if (scheme.dark) scheme.surfaceContainer else scheme.surfaceBright
        Block -> if (scheme.dark) scheme.surfaceContainerHigh else scheme.surfaceContainer
        Inset -> scheme.surfaceContainerHighest
    }

    companion object {
        fun floatingOf(scheme: BoxScheme): SurfaceLevel = if (scheme.dark) Block else Card
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
 * The app theme from main.dart: seeded scheme, superellipse shapes, the
 * Dynamic Type scale and the clamped text scale; also Material 3's own scheme
 * for the stock components (snackbar, navigation rail and bar).
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
                background = SurfaceLevel.Page.colorOf(scheme), onBackground = scheme.onSurface,
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
                background = SurfaceLevel.Page.colorOf(scheme), onBackground = scheme.onSurface,
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
    val typography = remember(fontFamily) {
        Typography(
            displayLarge = AppTextStyle.LargeTitle.style(fontFamily),
            displayMedium = AppTextStyle.LargeTitle.style(fontFamily),
            displaySmall = AppTextStyle.LargeTitle.style(fontFamily),
            headlineLarge = AppTextStyle.LargeTitle.style(fontFamily),
            headlineMedium = AppTextStyle.Title1.style(fontFamily),
            headlineSmall = AppTextStyle.Title2.style(fontFamily),
            titleLarge = AppTextStyle.Title3.style(fontFamily),
            titleMedium = AppTextStyle.Headline.style(fontFamily),
            titleSmall = AppTextStyle.Subheadline.style(fontFamily, emphasized = true),
            bodyLarge = AppTextStyle.Body.style(fontFamily),
            bodyMedium = AppTextStyle.Subheadline.style(fontFamily),
            bodySmall = AppTextStyle.Footnote.style(fontFamily),
            labelLarge = AppTextStyle.Subheadline.style(fontFamily, emphasized = true),
            labelMedium = AppTextStyle.Caption1.style(fontFamily, emphasized = true),
            labelSmall = AppTextStyle.Caption2.style(fontFamily, emphasized = true),
        )
    }
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
