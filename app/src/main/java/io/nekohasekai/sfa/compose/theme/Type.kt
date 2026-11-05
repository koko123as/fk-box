package io.nekohasekai.sfa.compose.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import io.nekohasekai.sfa.R

/**
 * flutter/lib/ui/typography.dart: Apple's Dynamic Type scale at its default
 * size, a dp per point, plus display and pageTitle.
 */
enum class AppTextStyle(
    val size: Float,
    val leading: Float,
    val weight: FontWeight = FontWeight.W400,
    val emphasizedWeight: FontWeight = FontWeight.W600,
) {
    Display(40f, 44f, emphasizedWeight = FontWeight.W700),
    LargeTitle(34f, 41f, emphasizedWeight = FontWeight.W700),
    Title1(28f, 34f, emphasizedWeight = FontWeight.W700),
    PageTitle(24f, 30f, weight = FontWeight.W600, emphasizedWeight = FontWeight.W700),
    Title2(22f, 28f, emphasizedWeight = FontWeight.W700),
    Title3(20f, 25f),
    Headline(17f, 22f, weight = FontWeight.W600),
    Body(17f, 22f),
    Callout(16f, 21f),
    Subheadline(15f, 20f),
    Footnote(13f, 18f),
    Caption1(12f, 16f),
    Caption2(11f, 13f),
    ;

    @Composable
    @ReadOnlyComposable
    fun of(emphasized: Boolean = false): TextStyle = style(LocalAppFontFamily.current, emphasized)

    fun style(family: FontFamily?, emphasized: Boolean = false): TextStyle = TextStyle(
        fontFamily = family,
        fontSize = size.sp,
        lineHeight = leading.sp,
        fontWeight = if (emphasized) emphasizedWeight else weight,
        letterSpacing = 0.sp,
        // Flutter's TextStyle.height spreads the leading evenly and keeps no font padding.
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
        platformStyle = PlatformTextStyle(includeFontPadding = false),
    )

    companion object {
        /** For text sized to the shape it marks, such as a letter in an icon. */
        @Composable
        @ReadOnlyComposable
        fun custom(size: Float, leading: Float, weight: FontWeight = FontWeight.W400): TextStyle =
            TextStyle(
                fontFamily = LocalAppFontFamily.current,
                fontSize = size.sp,
                lineHeight = leading.sp,
                fontWeight = weight,
                letterSpacing = 0.sp,
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
                platformStyle = PlatformTextStyle(includeFontPadding = false),
            )
    }
}

/** text_style.dart's toTabular. */
val TextStyle.tabular: TextStyle get() = copy(fontFeatureSettings = "tnum")

/** text_style.dart's toJetBrainsMono. */
val TextStyle.jetBrainsMono: TextStyle get() = copy(fontFamily = JetBrainsMono)

/** The bundled flutter/assets/fonts/JetBrainsMono-Regular.ttf. */
val JetBrainsMono = FontFamily(Font(R.font.jetbrains_mono_regular))

/** The appearance preference's font family, or the platform default. */
val LocalAppFontFamily = staticCompositionLocalOf<FontFamily?> { null }

fun appFontFamily(name: Any?): FontFamily? = when (name) {
    "JetBrainsMono" -> JetBrainsMono
    else -> null
}
