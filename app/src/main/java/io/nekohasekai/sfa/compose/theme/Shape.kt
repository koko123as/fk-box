package io.nekohasekai.sfa.compose.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.addOutline
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
    const val NONE = 0f
    const val XS = 4f
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
        if (!io.nekohasekai.sfa.compose.DebugSwitches.superellipse) return Outline.Rectangle(Rect(0f, 0f, size.width, size.height))
        val ltr = layoutDirection == LayoutDirection.Ltr
        with(density) {
            val tl = (if (ltr) topStart else topEnd).toPx()
            val tr = (if (ltr) topEnd else topStart).toPx()
            val br = (if (ltr) bottomEnd else bottomStart).toPx()
            val bl = (if (ltr) bottomStart else bottomEnd).toPx()
            return Outline.Generic(superellipsePath(Rect(0f, 0f, size.width, size.height), tl, tr, br, bl))
        }
    }

    /** The native rounded-rectangle stand-in to clip content to; see [SuperellipseClipShape]. */
    val clip: Shape by lazy(LazyThreadSafetyMode.NONE) { SuperellipseClipShape(topStart, topEnd, bottomEnd, bottomStart) }

    override fun equals(other: Any?): Boolean = other is SuperellipseShape && other.topStart == topStart &&
        other.topEnd == topEnd && other.bottomEnd == bottomEnd && other.bottomStart == bottomStart

    override fun hashCode(): Int = ((topStart.hashCode() * 31 + topEnd.hashCode()) * 31 + bottomEnd.hashCode()) * 31 + bottomStart.hashCode()
}

/**
 * What to hand Modifier.clip in place of a [SuperellipseShape]. A generic path
 * outline makes HWUI rasterize a clip mask for the layer, which a Mali driver
 * keeps per process; a rounded rectangle with one radius clips natively. The
 * radius is raised just enough (by [clipRadius]) that the rounded rectangle
 * lies inside the squircle to within a quarter pixel, so clipped content never
 * shows outside the squircle the background and border are still drawn with.
 * Clip sites draw their background and border with the squircle itself.
 */
@Immutable
class SuperellipseClipShape(
    private val topStart: Dp,
    private val topEnd: Dp,
    private val bottomEnd: Dp,
    private val bottomStart: Dp,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        if (!io.nekohasekai.sfa.compose.DebugSwitches.superellipse) return Outline.Rectangle(Rect(0f, 0f, size.width, size.height))
        val ltr = layoutDirection == LayoutDirection.Ltr
        with(density) {
            val budget = min(size.width, size.height) / 2f
            fun radius(dp: Dp) = min(clipRadius(dp.toPx()), budget)
            val tl = radius(if (ltr) topStart else topEnd)
            val tr = radius(if (ltr) topEnd else topStart)
            val br = radius(if (ltr) bottomEnd else bottomStart)
            val bl = radius(if (ltr) bottomStart else bottomEnd)
            val rect = Rect(0f, 0f, size.width, size.height)
            if (tl <= 0f && tr <= 0f && br <= 0f && bl <= 0f) return Outline.Rectangle(rect)
            if (tl == tr && tr == br && br == bl) return Outline.Rounded(RoundRect(rect, CornerRadius(tl)))
            // One rounded side (a sheet's top, a segment's end): the square side is pushed
            // out past the bounds so the outline keeps a single radius and a native clip.
            val r = max(max(tl, tr), max(br, bl))
            val extended = when {
                tl == r && tr == r && br <= 0f && bl <= 0f -> rect.copy(bottom = rect.bottom + r)
                bl == r && br == r && tl <= 0f && tr <= 0f -> rect.copy(top = rect.top - r)
                tl == r && bl == r && tr <= 0f && br <= 0f -> rect.copy(right = rect.right + r)
                tr == r && br == r && tl <= 0f && bl <= 0f -> rect.copy(left = rect.left - r)
                else -> null
            }
            if (extended != null) return Outline.Rounded(RoundRect(extended, CornerRadius(r)))
            return Outline.Rounded(RoundRect(rect, CornerRadius(tl), CornerRadius(tr), CornerRadius(br), CornerRadius(bl)))
        }
    }

    override fun equals(other: Any?): Boolean = other is SuperellipseClipShape && other.topStart == topStart &&
        other.topEnd == topEnd && other.bottomEnd == bottomEnd && other.bottomStart == bottomStart

    override fun hashCode(): Int = (((topStart.hashCode() * 31 + topEnd.hashCode()) * 31 + bottomEnd.hashCode()) * 31 + bottomStart.hashCode()) * 31 + 1
}

/** The shape to clip content with: a squircle's native stand-in, any other shape as is. */
fun Shape.forClip(): Shape = if (this is SuperellipseShape) clip else this

// How far (in units of the radius) a 60%-smoothed squircle corner of radius 1
// lies inside a circular corner of radius k, for k = 1.00, 1.05 … 1.60. Measured
// by sampling the corner of superellipsePath against the circle.
private val CLIP_DEPTHS = floatArrayOf(
    .012487f, .008527f, .005770f, .003831f, .002470f, .001528f, .000894f, .000483f, .000232f, .000092f, .000026f, .000003f, 0f,
)

/** The smallest circular radius whose corner stays within a quarter pixel of a squircle corner of [radius] px. */
fun clipRadius(radius: Float): Float {
    if (radius <= 0f) return 0f
    val allowed = .25f / radius
    if (allowed >= CLIP_DEPTHS[0]) return radius
    for (i in 1 until CLIP_DEPTHS.size) {
        if (CLIP_DEPTHS[i] <= allowed) {
            val previous = CLIP_DEPTHS[i - 1]
            val fraction = (previous - allowed) / (previous - CLIP_DEPTHS[i])
            return radius * (1f + .05f * (i - 1 + fraction))
        }
    }
    return radius * 1.6f
}

/**
 * Covers what lies outside [shape] with [outside], the solid color around it,
 * in place of a squircle clip: one static path fill (bounds minus the
 * squircle) instead of a clip mask, for content that runs into the corners.
 */
fun Modifier.squircleCorners(shape: Shape, outside: Color): Modifier = drawWithCache {
    val corners = Path().apply {
        fillType = PathFillType.EvenOdd
        addRect(Rect(0f, 0f, size.width, size.height))
        addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache))
    }
    onDrawWithContent {
        drawContent()
        drawPath(corners, outside)
    }
}

object AppShape {
    val none = SuperellipseShape(0.dp)
    val xs = SuperellipseShape(AppCorner.XS.dp)
    val sm = SuperellipseShape(AppCorner.SM.dp)
    val md = SuperellipseShape(AppCorner.MD.dp)
    val lg = SuperellipseShape(AppCorner.LG.dp)
    val xl = SuperellipseShape(AppCorner.XL.dp)
    val xxl = SuperellipseShape(AppCorner.XXL.dp)

    /** A pill: the superellipse of a 1000 dp radius collapses to round ends. */
    val full = RoundedCornerShape(50)
    fun all(corner: Float) = SuperellipseShape(corner.dp)
    fun top(corner: Float) = SuperellipseShape(corner.dp, corner.dp, 0.dp, 0.dp)
    fun vertical(top: Float = 0f, bottom: Float = 0f) = SuperellipseShape(top.dp, top.dp, bottom.dp, bottom.dp)
    fun horizontal(start: Float, end: Float) = SuperellipseShape(start.dp, end.dp, end.dp, start.dp)
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
