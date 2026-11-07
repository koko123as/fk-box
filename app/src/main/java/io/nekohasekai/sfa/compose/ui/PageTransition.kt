package io.nekohasekai.sfa.compose.ui

import android.os.Build
import android.view.RoundedCorner
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * prism_ui 63450c0 lib/page_transition.dart (CardRouteMixin, _CardTransition),
 * as used by FlClash v0.9.3 be84736, GPL-3.0: iOS 27's navigation push, in Compose.
 */

/** SpringDescription.withDurationAndBounce: stiffness (2π/d)², damping ratio 1 − bounce, mass 1. */
internal fun springStiffnessOf(durationMs: Int): Float {
    val omega = 2 * PI.toFloat() / (durationMs / 1000f)
    return omega * omega
}

internal fun durationSpring(durationMs: Int, bounce: Float = 0f): SpringSpec<Float> =
    spring(dampingRatio = 1f - bounce, stiffness = springStiffnessOf(durationMs), visibilityThreshold = 0.001f)

/** The step response of [durationSpring] from rest, for motion read off a clock. */
internal fun springStepAt(seconds: Float, durationMs: Int, bounce: Float): Float {
    if (seconds <= 0f) return 0f
    val omega = 2 * PI / (durationMs / 1000.0)
    val zeta = 1.0 - bounce
    val t = seconds.toDouble()
    return if (zeta >= 1.0) {
        (1 - (1 + omega * t) * kotlin.math.exp(-omega * t)).toFloat()
    } else {
        val damped = omega * kotlin.math.sqrt(1 - zeta * zeta)
        (1 - kotlin.math.exp(-zeta * omega * t) * (kotlin.math.cos(damped * t) + zeta * omega / damped * kotlin.math.sin(damped * t))).toFloat()
    }
}

/** iOS 27 pushes and pops on a critically damped 0.3 s spring set off at nine times its distance a second. */
internal val PUSH_SPRING = durationSpring(300)
internal const val PUSH_SPEED = 9f

private const val COVERED_SLIDE = 0.3f
private const val COVERED_DIM = 0.1f

/** A 4% black Gaussian of sigma 12 past the card's leading edge, as gradient stops. */
private const val CARD_SHADOW_SIGMA = 12f
private val CARD_SHADOW_STOPS = floatArrayOf(0f, 1f / 6, 1f / 3, 1f / 2, 2f / 3, 1f)
private val CARD_SHADOW_SHARES = floatArrayOf(0.5f, 0.3085f, 0.1587f, 0.0668f, 0.0228f, 0f)

/** displayRadiusOf: the display's corner where Android reports it (API 31+), else square. */
@Composable
internal fun displayCornerRadius(): Float {
    val view = LocalView.current
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return 0f
    val insets = view.rootWindowInsets ?: return 0f
    return insets.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius?.toFloat() ?: 0f
}

private class CardFrame(
    val dx: Float,
    val shape: androidx.compose.ui.graphics.Shape,
)

private fun cardFrame(level: NavLevel?, above: NavLevel?, width: Float, displayRadius: Float, rtl: Boolean): CardFrame {
    val toward = if (rtl) -1f else 1f
    val covered = above?.let { it.progress + it.overdrag } ?: 0f
    val progress = level?.progress ?: 1f
    var dx = 0f
    var shape: androidx.compose.ui.graphics.Shape = RectangleShape
    if (level != null) dx = (1 - progress - level.overdrag) * width * toward
    if (displayRadius > 0f && (progress < 1f || covered > 0f)) shape = RoundedCornerShape(displayRadius)
    if (covered > 0f) dx -= COVERED_SLIDE * width * covered * toward
    return CardFrame(dx, shape)
}

/**
 * _RenderCardTransition for the route [level] (null for the home page under
 * the routes) with [above] over it: a push slides in from the trailing edge
 * with the covered page 30% behind and dimmed 10%. A moving card takes the
 * display's corners and, in a light theme, a soft shadow at its leading edge.
 */
internal fun Modifier.cardTransition(
    level: NavLevel?,
    above: NavLevel?,
    displayRadius: Float,
    surface: Color,
    shadow: Boolean,
    rtl: Boolean = false,
    minimal: Boolean = false,
): Modifier = if (minimal) fadeTransition(level, surface) else this
    .graphicsLayer {
        val frame = cardFrame(level, above, size.width, displayRadius, rtl)
        // On whole pixels the moving page is drawn as sharp as at rest.
        translationX = frame.dx.roundToInt().toFloat()
    }
    .drawWithContent {
        val progress = level?.progress ?: 1f
        if (shadow && !rtl && level != null && progress < 1f) {
            val extent = 3 * CARD_SHADOW_SIGMA * density
            val inset = displayRadius
            if (size.height > 2 * inset) {
                drawRect(
                    Brush.horizontalGradient(
                        *Array(CARD_SHADOW_STOPS.size) { index ->
                            (1 - CARD_SHADOW_STOPS[index]) to Color.Black.copy(alpha = 0.04f * CARD_SHADOW_SHARES[index])
                        }.reversedArray(),
                        startX = -extent,
                        endX = 0f,
                    ),
                    topLeft = Offset(-extent, inset),
                    size = Size(extent, size.height - 2 * inset),
                )
            }
        }
        drawContent()
    }
    .graphicsLayer {
        val frame = cardFrame(level, above, size.width, displayRadius, rtl)
        shape = frame.shape
        clip = frame.shape !== RectangleShape
    }
    .drawWithContent {
        if (level != null) drawRect(surface)
        drawContent()
        val covered = above?.let { it.progress + it.overdrag } ?: 0f
        if (covered > 0f) {
            drawRect(Color.Black.copy(alpha = COVERED_DIM * min(covered, 1f)))
        }
    }

/**
 * The minimal style's page transition: the route fades in over the page it
 * covers and out again, on the page colour so the two do not show through
 * each other at rest. Nothing slides, scales, dims or casts a shadow; the
 * covered page stays where it is.
 */
private fun Modifier.fadeTransition(level: NavLevel?, surface: Color): Modifier = if (level == null) {
    this
} else {
    this
        .graphicsLayer { alpha = level.progress.coerceIn(0f, 1f) }
        .drawWithContent {
            drawRect(surface)
            drawContent()
        }
}
