package io.nekohasekai.sfa.compose.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** flutter/lib/ui/shape.dart: the corner scale of the FlClash UI kit. */
object AppCorner {
    const val SM = 8f
    const val MD = 16f
    const val LG = 20f
    const val XL = 24f
    const val XXL = 28f
    const val ALERT = 34f
    const val FULL = 1000f
}

/**
 * Flutter's RoundedSuperellipseBorder: continuous (iOS-style) corners. Each
 * corner follows the smoothed-corner construction (60% smoothing, the iOS
 * value) and falls back to a circular arc when the side is too short.
 */
@Immutable
class SuperellipseShape(
    private val topStart: Dp,
    private val topEnd: Dp = topStart,
    private val bottomEnd: Dp = topStart,
    private val bottomStart: Dp = topStart,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val ltr = layoutDirection == LayoutDirection.Ltr
        with(density) {
            val tl = (if (ltr) topStart else topEnd).toPx()
            val tr = (if (ltr) topEnd else topStart).toPx()
            val br = (if (ltr) bottomEnd else bottomStart).toPx()
            val bl = (if (ltr) bottomStart else bottomEnd).toPx()
            return Outline.Generic(superellipsePath(Rect(0f, 0f, size.width, size.height), tl, tr, br, bl))
        }
    }

    /**
     * A rounded rectangle with the same corner radii. Used only for MediaTek
     * shadow outlines, where Skia's rrect blur avoids per-path mask textures.
     */
    fun toRoundedCornerShape(): RoundedCornerShape = RoundedCornerShape(topStart, topEnd, bottomEnd, bottomStart)

    override fun equals(other: Any?): Boolean = other is SuperellipseShape && other.topStart == topStart &&
        other.topEnd == topEnd && other.bottomEnd == bottomEnd && other.bottomStart == bottomStart

    override fun hashCode(): Int = ((topStart.hashCode() * 31 + topEnd.hashCode()) * 31 + bottomEnd.hashCode()) * 31 + bottomStart.hashCode()
}

/**
 * The app's shapes. Every radius passes through the appearance tokens, so the
 * minimal style's smaller, squarer corners apply everywhere; a read during
 * composition recomposes when the style changes.
 */
object AppShape {
    private fun c(corner: Float): Dp = AppearanceTokens.active.corner(corner).dp

    val none = SuperellipseShape(0.dp)
    val sm: Shape get() = SuperellipseShape(c(AppCorner.SM))
    val md: Shape get() = SuperellipseShape(c(AppCorner.MD))
    val lg: Shape get() = SuperellipseShape(c(AppCorner.LG))
    val xl: Shape get() = SuperellipseShape(c(AppCorner.XL))
    val xxl: Shape get() = SuperellipseShape(c(AppCorner.XXL))

    /** A pill: the superellipse of a 1000 dp radius collapses to round ends. */
    val full = RoundedCornerShape(50)
    fun all(corner: Float): Shape = SuperellipseShape(c(corner))
    fun top(corner: Float): Shape = SuperellipseShape(c(corner), c(corner), 0.dp, 0.dp)
    fun vertical(top: Float = 0f, bottom: Float = 0f): Shape = SuperellipseShape(c(top), c(top), c(bottom), c(bottom))
    fun horizontal(start: Float, end: Float): Shape = SuperellipseShape(c(start), c(end), c(end), c(start))
}

private const val SMOOTHING = 0.6f

private class CornerParams(val a: Float, val b: Float, val c: Float, val d: Float, val p: Float, val arc: Float, val radius: Float)

private fun cornerParams(radius: Float, budget: Float): CornerParams {
    if (radius <= 0f) return CornerParams(0f, 0f, 0f, 0f, 0f, 0f, 0f)
    val r = min(radius, budget)
    var smoothing = SMOOTHING
    var p = (1 + smoothing) * r
    if (p > budget) {
        smoothing = max(budget / r - 1, 0f)
        p = min(p, budget)
    }
    val arcMeasure = 90f * (1 - smoothing)
    val arcSection = (sin(Math.toRadians(arcMeasure / 2.0)) * r * sqrt(2.0)).toFloat()
    val angleAlpha = (90f - arcMeasure) / 2f
    val p3ToP4 = (r * tan(Math.toRadians(angleAlpha / 2.0))).toFloat()
    val angleBeta = 45f * smoothing
    val c = (p3ToP4 * cos(Math.toRadians(angleBeta.toDouble()))).toFloat()
    val d = (c * tan(Math.toRadians(angleBeta.toDouble()))).toFloat()
    val b = (p - arcSection - c - d) / 3f
    val a = 2f * b
    return CornerParams(a, b, c, d, p, arcMeasure, r)
}

/** Builds a rectangle with continuous corners. Radii in pixels. */
fun superellipsePath(rect: Rect, topLeft: Float, topRight: Float, bottomRight: Float, bottomLeft: Float, path: Path = Path()): Path {
    path.reset()
    val budget = min(rect.width, rect.height) / 2f
    val tl = cornerParams(topLeft, budget)
    val tr = cornerParams(topRight, budget)
    val br = cornerParams(bottomRight, budget)
    val bl = cornerParams(bottomLeft, budget)
    val l = rect.left
    val t = rect.top
    val r = rect.right
    val b = rect.bottom

    path.moveTo(l + tl.p, t)
    // Top edge to the top-right corner.
    path.lineTo(r - tr.p, t)
    if (tr.radius > 0f) {
        path.cubicTo(r - tr.p + tr.a, t, r - tr.p + tr.a + tr.b, t, r - tr.p + tr.a + tr.b + tr.c, t + tr.d)
        path.arcTo(Rect(r - 2 * tr.radius, t, r, t + 2 * tr.radius), -90f + (90f - tr.arc) / 2f, tr.arc, false)
        path.cubicTo(r, t + tr.p - tr.a - tr.b, r, t + tr.p - tr.a, r, t + tr.p)
    }
    path.lineTo(r, b - br.p)
    if (br.radius > 0f) {
        path.cubicTo(r, b - br.p + br.a, r, b - br.p + br.a + br.b, r - br.d, b - br.p + br.a + br.b + br.c)
        path.arcTo(Rect(r - 2 * br.radius, b - 2 * br.radius, r, b), (90f - br.arc) / 2f, br.arc, false)
        path.cubicTo(r - br.p + br.a + br.b, b, r - br.p + br.a, b, r - br.p, b)
    }
    path.lineTo(l + bl.p, b)
    if (bl.radius > 0f) {
        path.cubicTo(l + bl.p - bl.a, b, l + bl.p - bl.a - bl.b, b, l + bl.p - bl.a - bl.b - bl.c, b - bl.d)
        path.arcTo(Rect(l, b - 2 * bl.radius, l + 2 * bl.radius, b), 90f + (90f - bl.arc) / 2f, bl.arc, false)
        path.cubicTo(l, b - bl.p + bl.a + bl.b, l, b - bl.p + bl.a, l, b - bl.p)
    }
    path.lineTo(l, t + tl.p)
    if (tl.radius > 0f) {
        path.cubicTo(l, t + tl.p - tl.a, l, t + tl.p - tl.a - tl.b, l + tl.d, t + tl.p - tl.a - tl.b - tl.c)
        path.arcTo(Rect(l, t, l + 2 * tl.radius, t + 2 * tl.radius), 180f + (90f - tl.arc) / 2f, tl.arc, false)
        path.cubicTo(l + tl.p - tl.a - tl.b, t, l + tl.p - tl.a, t, l + tl.p, t)
    }
    path.close()
    return path
}
