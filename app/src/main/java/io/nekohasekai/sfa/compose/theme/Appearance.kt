package io.nekohasekai.sfa.compose.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import kotlin.math.max
import kotlin.math.min

/** The "Style" appearance preference: the FlClash look, or a flat, quiet one. */
enum class AppearanceStyle(val key: String) {
    Standard("standard"),
    Minimal("minimal"),
    ;

    companion object {
        fun of(value: Any?): AppearanceStyle = entries.firstOrNull { it.key == value } ?: Standard
    }
}

/**
 * The app-wide appearance tokens. Theme, glass, shadows, motion and press
 * feedback read these instead of checking the style themselves; colours,
 * layouts and functional indicators are the same in every style.
 */
@Immutable
data class AppearanceTokens(
    val style: AppearanceStyle,
    /** Liquid glass panes (and their AGSL shaders) may be used at all. */
    val glass: Boolean,
    /** Drop shadows and elevation; without them a raised surface takes a hairline outline. */
    val shadows: Boolean,
    /** Decorative graphics such as the status card's background shape. */
    val decorations: Boolean,
    /** Ink that spreads in and out over time; without it the press is a static dim. */
    val inkSpread: Boolean,
    /** The InkSparkle shader from the press point. */
    val sparkle: Boolean,
    /** ElasticPress: a press swells the control. */
    val elasticPress: Boolean,
    /** Springs, slides, scales and blurs; without them transitions are short fades. */
    val expressiveMotion: Boolean,
    /** The length of a minimal fade. */
    val fadeMillis: Int,
    /** Corner radii are multiplied by this (small radii keep a readable floor). */
    val cornerScale: Float,
) {
    val minimal: Boolean get() = style == AppearanceStyle.Minimal

    /** A corner radius in dp for this style. */
    fun corner(radius: Float): Float {
        if (cornerScale >= 1f || radius <= 0f || radius >= AppCorner.FULL) return radius
        return max(radius * cornerScale, min(radius, MIN_CORNER))
    }

    /** The short, plain fade minimal transitions use. */
    fun <T> fade(): FiniteAnimationSpec<T> = tween(fadeMillis, easing = FastOutSlowInEasing)

    /** The fade for something leaving. */
    fun <T> fadeOut(): FiniteAnimationSpec<T> = tween(FADE_OUT_MILLIS, easing = LinearOutSlowInEasing)

    companion object {
        private const val MIN_CORNER = 4f
        private const val FADE_OUT_MILLIS = 120

        val Standard = AppearanceTokens(
            style = AppearanceStyle.Standard,
            glass = true,
            shadows = true,
            decorations = true,
            inkSpread = true,
            sparkle = true,
            elasticPress = true,
            expressiveMotion = true,
            fadeMillis = 160,
            cornerScale = 1f,
        )

        val Minimal = AppearanceTokens(
            style = AppearanceStyle.Minimal,
            glass = false,
            shadows = false,
            decorations = false,
            inkSpread = false,
            sparkle = false,
            elasticPress = false,
            expressiveMotion = false,
            fadeMillis = 160,
            cornerScale = 0.5f,
        )

        fun of(style: AppearanceStyle): AppearanceTokens = if (style == AppearanceStyle.Minimal) Minimal else Standard

        private var activeTokens by mutableStateOf(Standard)

        /**
         * The tokens in effect, for code that runs outside composition locals
         * (shape factories, modifiers built at the top level). A read during
         * composition still subscribes to a style change.
         */
        val active: AppearanceTokens get() = activeTokens

        internal fun publish(tokens: AppearanceTokens) {
            if (activeTokens != tokens) activeTokens = tokens
        }
    }
}

val LocalAppearance = staticCompositionLocalOf { AppearanceTokens.Standard }

/** Provides [tokens] to the content and to the shape factories. */
@Composable
fun ProvideAppearance(tokens: AppearanceTokens, content: @Composable () -> Unit) {
    SideEffect { AppearanceTokens.publish(tokens) }
    CompositionLocalProvider(LocalAppearance provides tokens, content = content)
}

/** The minimal style's stand-in for a shadow: a hairline in the outline-variant colour. */
fun Modifier.hairlineOutline(shape: Shape): Modifier = composed {
    border(Dp.Hairline, LocalBoxScheme.current.outlineVariant, shape)
}

/** Draws [shape]'s hairline only when the style leaves shadows out. */
fun Modifier.hairlineWithoutShadows(shape: Shape): Modifier = composed {
    if (LocalAppearance.current.shadows) this else border(Dp.Hairline, LocalBoxScheme.current.outlineVariant, shape)
}

/**
 * animateItem for a list row: the standard style's keyed fade and slide, or
 * in the minimal style a short fade in and out with survivors left in place.
 */
@Composable
fun LazyItemScope.itemMotion(): Modifier {
    val tokens = LocalAppearance.current
    return if (tokens.expressiveMotion) {
        Modifier.animateItem()
    } else {
        Modifier.animateItem(fadeInSpec = tokens.fade(), placementSpec = null, fadeOutSpec = tokens.fadeOut())
    }
}
