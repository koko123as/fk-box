package io.nekohasekai.sfa.compose.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.nekohasekai.sfa.R

/**
 * The text styles the pages name, mapped onto the stock Material 3 type scale
 * (size, line height and weight of the M3 role each one stands for).
 */
enum class AppTextStyle(
    val size: Float,
    val leading: Float,
    val weight: FontWeight = FontWeight.W400,
    val emphasizedWeight: FontWeight = FontWeight.W500,
) {
    /** displaySmall */
    Display(36f, 44f),
    /** headlineLarge */
    LargeTitle(32f, 40f),
    /** headlineMedium */
    Title1(28f, 36f),
    /** titleLarge */
    PageTitle(22f, 28f),
    /** headlineSmall */
    Title2(24f, 32f),
    /** titleLarge */
    Title3(22f, 28f),
    /** titleMedium */
    Headline(16f, 24f, weight = FontWeight.W500, emphasizedWeight = FontWeight.W600),
    /** bodyLarge */
    Body(16f, 24f),
    /** bodyLarge */
    Callout(16f, 24f),
    /** bodyMedium */
    Subheadline(14f, 20f),
    /** bodySmall */
    Footnote(12f, 16f),
    /** labelMedium */
    Caption1(12f, 16f),
    /** labelSmall */
    Caption2(11f, 16f),
    ;

    @Composable
    @ReadOnlyComposable
    fun of(emphasized: Boolean = false): TextStyle = style(LocalAppFontFamily.current, emphasized)

    fun style(family: FontFamily?, emphasized: Boolean = false): TextStyle = TextStyle(
        fontFamily = family,
        fontSize = size.sp,
        lineHeight = leading.sp,
        fontWeight = if (emphasized) emphasizedWeight else weight,
    )
}

/** Tabular figures, for numbers that update in place. */
val TextStyle.tabular: TextStyle get() = copy(fontFeatureSettings = "tnum")

/** The monospaced variant of a style. */
val TextStyle.jetBrainsMono: TextStyle get() = copy(fontFamily = JetBrainsMono)

/** The bundled JetBrains Mono, for code and terminal text. */
val JetBrainsMono = FontFamily(Font(R.font.jetbrains_mono_regular))

/** The appearance preference's font family, or the platform default. */
val LocalAppFontFamily = staticCompositionLocalOf<FontFamily?> { null }

fun appFontFamily(name: Any?): FontFamily? = when (name) {
    "JetBrainsMono" -> JetBrainsMono
    else -> null
}
