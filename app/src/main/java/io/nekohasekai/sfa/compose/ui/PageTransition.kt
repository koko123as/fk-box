package io.nekohasekai.sfa.compose.ui

import android.os.Build
import android.view.RoundedCorner
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * prism_ui 63450c0 lib/page_transition.dart (CardRouteMixin, _CardTransition)
 * and lib/open_container.dart (the zoom route), as used by FlClash v0.9.3
 * be84736, GPL-3.0: iOS 27's navigation push and zoom, in Compose.
 */

/** open_container.dart's OpenContainerTransition. */
enum class PageTransition { Push, Zoom }

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

/** The zoom opens on a critically damped 0.34 s spring set off at 0.016 of its stiffness, and closes from rest with a 0.08 bounce. */
internal val ZOOM_OPEN_SPRING = durationSpring(340)
internal val ZOOM_OPEN_SPEED = 0.016f * springStiffnessOf(340)
internal val ZOOM_CLOSE_SPRING = durationSpring(340, 0.08f)
private const val ZOOM_FADE_END = 0.65f

private const val COVERED_SLIDE = 0.3f
private const val COVERED_DIM = 0.1f

/** UIKit's zoom shrinks the page it covers by 34.667 dp of width, to no less than 80%, and dims it 15%. */
private const val RECEDED_SHRINK_WIDTH = 34.667f
private const val RECEDED_MIN_SCALE = 0.8f
private const val RECEDED_DIM = 0.15f

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

/** Where a zoom opens from: the bounds of the tile it grows out of. */
@Stable
class ZoomSource {
    internal var bounds by mutableStateOf<Rect?>(null)
    internal var radius = 0f

    val modifier: Modifier = Modifier.onGloballyPositioned { bounds = it.boundsInRoot() }
}

@Composable
fun rememberZoomSource(cornerRadius: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp(0f)): ZoomSource {
    val density = LocalDensity.current
    return remember { ZoomSource() }.also { it.radius = with(density) { cornerRadius.toPx() } }
}

/**
 * _RenderCardTransition for the route [level] (null for the home page under
 * the routes) with [above] over it: a push slides in from the trailing edge
 * with the covered page 30% behind and dimmed, a zoom grows out of its source
 * while the covered page recedes. A moving card takes the display's corners
 * and, in a light theme, a soft shadow at its leading edge.
 */
private fun mix(start: Float, stop: Float, fraction: Float): Float = start + (stop - start) * fraction

private class CardFrame(
    val dx: Float,
    val dy: Float,
    val scale: Float,
    val alpha: Float,
    val shape: androidx.compose.ui.graphics.Shape,
    val covered: Float,
    val dim: Float,
)

private fun cardFrame(level: NavLevel?, above: NavLevel?, size: Size, density: Float, hostOrigin: Offset, displayRadius: Float, rtl: Boolean): CardFrame {
    val toward = if (rtl) -1f else 1f
    val width = size.width
    val covered = above?.let { it.progress + it.overdrag } ?: 0f
    val recedes = above?.transition == PageTransition.Zoom
    val progress = level?.progress ?: 1f
    var dx = 0f
    var dy = 0f
    var scale = 1f
    var alpha = 1f
    var shape: androidx.compose.ui.graphics.Shape = RectangleShape
    val origin = level?.origin?.takeIf { level.transition == PageTransition.Zoom }
    if (level != null && origin != null && width > 0f) {
        // _openingFrame: the card grows from the tile's rect, its height and corners scaled with it.
        val closed = origin.translate(-hostOrigin)
        val from = (closed.width / width).coerceAtLeast(0.01f)
        scale = mix(from, 1f, progress)
        val center = Offset(mix(closed.center.x, width / 2, progress), mix(closed.center.y, size.height / 2, progress))
        dx = center.x - width / 2
        dy = center.y - size.height / 2
        val visibleHeight = mix(closed.height / from, size.height, progress)
        val radius = mix(level.originRadius / from, 0f, progress)
        val top = (size.height - visibleHeight) / 2
        if (progress < 1f) {
            shape = GenericShape { box, _ ->
                addRoundRect(RoundRect(Rect(0f, top, box.width, top + visibleHeight), CornerRadius(radius)))
            }
        }
        // UIKit fades the page in over the first 65% of the way.
        alpha = (progress / ZOOM_FADE_END).coerceIn(0f, 1f)
    } else {
        if (level != null) dx = (1 - progress - level.overdrag) * width * toward
        if (displayRadius > 0f && (progress < 1f || covered > 0f) && !(recedes && progress >= 1f)) {
            shape = RoundedCornerShape(displayRadius)
        }
    }
    if (covered > 0f) {
        if (recedes) {
            val shrink = min(1 - RECEDED_MIN_SCALE, RECEDED_SHRINK_WIDTH * density / width.coerceAtLeast(1f))
            scale *= 1 - shrink * min(covered, 1f)
        } else {
            dx -= COVERED_SLIDE * width * covered * toward
        }
    }
    return CardFrame(dx, dy, scale, alpha, shape, covered, if (recedes) RECEDED_DIM else COVERED_DIM)
}

/**
 * _RenderCardTransition for the route [level] (null for the home page under
 * the routes) with [above] over it: a push slides in from the trailing edge
 * with the covered page 30% behind and dimmed 10%, a zoom grows out of its
 * source while the covered page recedes and dims 15%. A moving card takes the
 * display's corners and, in a light theme, a soft shadow at its leading edge.
 */
internal fun Modifier.cardTransition(
    level: NavLevel?,
    above: NavLevel?,
    hostOrigin: () -> Offset,
    displayRadius: Float,
    surface: Color,
    shadow: Boolean,
    rtl: Boolean = false,
    minimal: Boolean = false,
): Modifier = if (minimal) fadeTransition(level, surface) else this
    .graphicsLayer {
        val frame = cardFrame(level, above, size, density, hostOrigin(), displayRadius, rtl)
        // On whole pixels the moving page is drawn as sharp as at rest.
        translationX = frame.dx.roundToInt().toFloat()
        translationY = frame.dy
        scaleX = frame.scale
        scaleY = frame.scale
        transformOrigin = TransformOrigin.Center
        alpha = frame.alpha
    }
    .drawWithContent {
        val progress = level?.progress ?: 1f
        if (shadow && !rtl && level != null && level.transition == PageTransition.Push && progress < 1f) {
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
        val frame = cardFrame(level, above, size, density, hostOrigin(), displayRadius, rtl)
        shape = frame.shape
        clip = frame.shape !== RectangleShape
    }
    .drawWithContent {
        if (level != null) drawRect(surface)
        drawContent()
        val covered = above?.let { it.progress + it.overdrag } ?: 0f
        if (covered > 0f) {
            val dim = if (above?.transition == PageTransition.Zoom) RECEDED_DIM else COVERED_DIM
            drawRect(Color.Black.copy(alpha = dim * min(covered, 1f)))
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
