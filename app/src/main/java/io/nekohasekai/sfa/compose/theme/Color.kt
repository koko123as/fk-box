@file:Suppress("RestrictedApi")

package io.nekohasekai.sfa.compose.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import com.google.android.material.color.utilities.DynamicColor
import com.google.android.material.color.utilities.DynamicScheme
import com.google.android.material.color.utilities.Hct
import com.google.android.material.color.utilities.MaterialDynamicColors
import com.google.android.material.color.utilities.SchemeContent
import com.google.android.material.color.utilities.SchemeMonochrome
import com.google.android.material.color.utilities.SchemeTonalSpot
import com.google.android.material.color.utilities.TonalPalette
import kotlin.math.min

/**
 * Seeded colour schemes (adapted from FlClash, GPL-3.0): the seeded scheme with its neutral, accent and surface adjustments, using the
 * same material color utilities through Material Components.
 */
enum class ColorStyle {
    Vivid,
    Tinted,
    Soft,
    Monochrome,
    ;

    companion object {
        fun of(name: Any?): ColorStyle = entries.firstOrNull { it.name.equals(name as? String, ignoreCase = true) } ?: Vivid
    }
}

@Immutable
data class BoxScheme(
    val dark: Boolean,
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondary: Color,
    val onSecondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val tertiary: Color,
    val onTertiary: Color,
    val tertiaryContainer: Color,
    val onTertiaryContainer: Color,
    val error: Color,
    val onError: Color,
    val errorContainer: Color,
    val onErrorContainer: Color,
    val outline: Color,
    val outlineVariant: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val inverseSurface: Color,
    val onInverseSurface: Color,
    val inversePrimary: Color,
    val scrim: Color,
    val surface: Color,
    val surfaceBright: Color,
    val surfaceDim: Color,
    val surfaceContainerLowest: Color,
    val surfaceContainerLow: Color,
    val surfaceContainer: Color,
    val surfaceContainerHigh: Color,
    val surfaceContainerHighest: Color,
) {
    private fun tone(palette: TonalPalette, light: Int, dark: Int) = Color(palette.tone(if (this.dark) dark else light))

    val success: Color get() = tone(successPalette, 40, 80)
    val successContainer: Color get() = tone(successPalette, 90, 30)
    val onSuccessContainer: Color get() = tone(successPalette, 30, 90)
    val warning: Color get() = tone(warningPalette, 40, 80)
    val warningContainer: Color get() = tone(warningPalette, 90, 30)
    val onWarningContainer: Color get() = tone(warningPalette, 30, 90)


    fun delayColor(delay: Int?): Color? = when {
        delay == null -> null
        delay < 0 -> error
        delay < 600 -> success
        else -> warning
    }

    fun toPureBlack(pureBlack: Boolean): BoxScheme {
        if (!pureBlack || !dark) return this
        val shift = surface.tone
        fun lower(color: Color) = color.shiftTone(-shift)
        return copy(
            surface = Color.Black,
            surfaceDim = Color.Black,
            surfaceContainerLowest = Color.Black,
            surfaceContainerLow = lower(surfaceContainerLow),
            surfaceContainer = lower(surfaceContainer),
            surfaceContainerHigh = lower(surfaceContainerHigh),
            surfaceContainerHighest = lower(surfaceContainerHighest),
            surfaceBright = lower(surfaceBright),
        )
    }

    companion object {
        // Material green 500 and orange 500.
        private val successPalette = TonalPalette.fromHct(Hct.fromInt(0xFF4CAF50.toInt()))
        private val warningPalette = TonalPalette.fromHct(Hct.fromInt(0xFFFF9800.toInt()))
    }
}

val Color.tone: Double get() = Hct.fromInt(toArgb()).tone

fun Color.shiftTone(delta: Double): Color {
    val hct = Hct.fromInt(toArgb())
    return Color(Hct.from(hct.hue, hct.chroma, (hct.tone + delta).coerceIn(0.0, 100.0)).toInt())
}

/** Dart's Color.alphaBlend(foreground, background). */
fun alphaBlend(foreground: Color, background: Color): Color = foreground.compositeOver(background)

private const val SURFACE_CHROMA = 3.0
private const val PAPER_CHROMA = 1.5
private const val TINT_DIVISOR = 6.0
private const val MAX_TINT_CHROMA = 10.0
private const val PRIMARY_CONTAINER_CHROMA = 48.0
private const val DARK_ON_SURFACE_TONE = 97
private const val DARK_CARD_TONE = 15.0
private const val DARK_BLOCK_TONE = 21.0
private const val DARK_INSET_TONE = 27.0
private const val MIN_ACCENT_TONE = 35.0
private const val MAX_ACCENT_TONE = 60.0
private const val DARK_ACCENT_LIFT = 3.0
private const val MIN_DARK_ACCENT_TONE = 50.0
private const val MAX_DARK_ACCENT_TONE = 75.0

private val dynamicColors = MaterialDynamicColors()

private fun dynamicScheme(source: Hct, dark: Boolean, style: ColorStyle): DynamicScheme = when (style) {
    ColorStyle.Vivid, ColorStyle.Tinted -> SchemeContent(source, dark, 0.0)
    ColorStyle.Soft -> SchemeTonalSpot(source, dark, 0.0)
    ColorStyle.Monochrome -> SchemeMonochrome(source, dark, 0.0)
}

private val schemeCache = HashMap<String, BoxScheme>()

fun seededColorScheme(seedColor: Int, dark: Boolean, style: ColorStyle = ColorStyle.Vivid): BoxScheme = synchronized(schemeCache) {
    schemeCache.getOrPut("$seedColor:$dark:$style") { buildScheme(seedColor, dark, style) }
}

private fun buildScheme(seedColor: Int, dark: Boolean, style: ColorStyle): BoxScheme {
    val source = Hct.fromInt(seedColor)
    val scheme = dynamicScheme(source, dark, style)
    val primaryPalette = scheme.primaryPalette
    val container = TonalPalette.fromHueAndChroma(primaryPalette.hue, min(primaryPalette.chroma, PRIMARY_CONTAINER_CHROMA))
    val tint = if (style == ColorStyle.Tinted) min(source.chroma / TINT_DIVISOR, MAX_TINT_CHROMA) else null
    fun neutral(role: DynamicColor, maxChroma: Double, darkTone: Double? = null): Color {
        val palette = role.palette.apply(scheme)
        val tone = (if (dark) darkTone else null) ?: role.getTone(scheme)
        return Color(Hct.from(palette.hue, tint ?: min(palette.chroma, maxChroma), tone).toInt())
    }
    fun role(color: DynamicColor) = Color(color.getArgb(scheme))
    val paperChroma = if (dark) SURFACE_CHROMA else PAPER_CHROMA
    val accent = when (style) {
        ColorStyle.Vivid, ColorStyle.Tinted -> accent(seedColor, source, dark)
        ColorStyle.Soft, ColorStyle.Monochrome -> null
    }
    val m = dynamicColors
    val primary = accent?.first ?: role(m.primary())
    return BoxScheme(
        dark = dark,
        primary = primary,
        onPrimary = accent?.second ?: role(m.onPrimary()),
        primaryContainer = Color(container.tone(if (dark) 30 else 90)),
        onPrimaryContainer = Color(container.tone(if (dark) 90 else 30)),
        secondary = role(m.secondary()),
        onSecondary = role(m.onSecondary()),
        secondaryContainer = role(m.secondaryContainer()),
        onSecondaryContainer = role(m.onSecondaryContainer()),
        tertiary = role(m.tertiary()),
        onTertiary = role(m.onTertiary()),
        tertiaryContainer = role(m.tertiaryContainer()),
        onTertiaryContainer = role(m.onTertiaryContainer()),
        error = role(m.error()),
        onError = role(m.onError()),
        errorContainer = role(m.errorContainer()),
        onErrorContainer = role(m.onErrorContainer()),
        outline = role(m.outline()),
        onSurface = if (dark) Color(m.onSurface().palette.apply(scheme).tone(DARK_ON_SURFACE_TONE)) else role(m.onSurface()),
        onSurfaceVariant = role(m.onSurfaceVariant()),
        inverseSurface = role(m.inverseSurface()),
        onInverseSurface = role(m.inverseOnSurface()),
        inversePrimary = role(m.inversePrimary()),
        scrim = role(m.scrim()),
        surface = neutral(m.surface(), paperChroma),
        surfaceBright = neutral(m.surfaceBright(), paperChroma),
        surfaceDim = neutral(m.surfaceDim(), SURFACE_CHROMA),
        surfaceContainerLowest = neutral(m.surfaceContainerLowest(), SURFACE_CHROMA),
        surfaceContainerLow = neutral(m.surfaceContainerLow(), SURFACE_CHROMA),
        surfaceContainer = neutral(m.surfaceContainer(), SURFACE_CHROMA, DARK_CARD_TONE),
        surfaceContainerHigh = neutral(m.surfaceContainerHigh(), SURFACE_CHROMA, DARK_BLOCK_TONE),
        surfaceContainerHighest = neutral(m.surfaceContainerHighest(), SURFACE_CHROMA, DARK_INSET_TONE),
        outlineVariant = neutral(m.outlineVariant(), 4.0),
    )
}

private fun accent(seedColor: Int, seed: Hct, dark: Boolean): Pair<Color, Color> {
    val tone = if (dark) {
        (seed.tone + DARK_ACCENT_LIFT).coerceIn(MIN_DARK_ACCENT_TONE, MAX_DARK_ACCENT_TONE)
    } else {
        seed.tone.coerceIn(MIN_ACCENT_TONE, MAX_ACCENT_TONE)
    }
    val primary = if (tone == seed.tone) Color(seedColor) else Color(Hct.from(seed.hue, seed.chroma, tone).toInt())
    val onPrimary = if (tone <= MAX_ACCENT_TONE) Color.White else Color(TonalPalette.fromHueAndChroma(seed.hue, seed.chroma).tone(10))
    return primary to onPrimary
}
