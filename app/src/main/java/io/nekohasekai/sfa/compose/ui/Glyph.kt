package io.nekohasekai.sfa.compose.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.compose.theme.superellipsePath
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * flutter/lib/ui/icons/glyph.dart, glyph_painter.dart, glyph_icon.dart and
 * animated_glyph.dart (FlClash UI kit, GPL-3.0): stroked glyphs on a 24 grid
 * whose bodies fill and whose details turn into cutouts as [fill] rises.
 */

enum class GlyphRole { Body, Detail, Line }

/**
 * [key] names a computed glyph's shape for the raster cache (a fixed glyph is
 * its own key); [cacheable] is false for in-between frames of a computed
 * glyph, which are drawn live instead of filling the cache.
 */
@Immutable
class Glyph(
    val shapes: List<GlyphShape>,
    val matchTextDirection: Boolean = false,
    key: Any? = null,
    val cacheable: Boolean = true,
) {
    val cacheKey: Any = key ?: this


    companion object {
        const val SIZE = 24f
        const val STROKE_WIDTH = 1.8f
    }
}

data class GlyphPoint(val x: Double, val y: Double)

sealed class GlyphShape(val role: GlyphRole, val solid: Boolean) {
    private var cached: Path? = null
    val path: Path get() = cached ?: build().also { cached = it }
    protected abstract fun build(): Path
}

class GlyphBox(
    private val left: Double,
    private val top: Double,
    private val right: Double,
    private val bottom: Double,
    private val radius: Double,
    role: GlyphRole = GlyphRole.Body,
) : GlyphShape(role, false) {
    override fun build(): Path {
        val r = radius.toFloat()
        return superellipsePath(Rect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat()), r, r, r, r)
    }
}

class GlyphCircle(
    private val x: Double,
    private val y: Double,
    private val radius: Double,
    role: GlyphRole = GlyphRole.Body,
    solid: Boolean = false,
) : GlyphShape(role, solid) {
    override fun build(): Path = Path().apply {
        addOval(Rect(Offset(x.toFloat(), y.toFloat()), radius.toFloat()))
    }
}

class GlyphOval(
    private val left: Double,
    private val top: Double,
    private val right: Double,
    private val bottom: Double,
    role: GlyphRole = GlyphRole.Body,
) : GlyphShape(role, false) {
    override fun build(): Path = Path().apply {
        addOval(Rect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat()))
    }
}

class GlyphArc(
    private val x: Double,
    private val y: Double,
    private val radius: Double,
    private val start: Double,
    private val sweep: Double,
    role: GlyphRole = GlyphRole.Body,
) : GlyphShape(role, false) {
    override fun build(): Path = Path().apply {
        addArc(
            Rect(Offset(x.toFloat(), y.toFloat()), radius.toFloat()),
            Math.toDegrees(start).toFloat(),
            Math.toDegrees(sweep).toFloat(),
        )
    }
}

/** Joins two circles of [radius] with a line whose round caps end on their strokes. */
class GlyphLink(private val from: GlyphPoint, private val to: GlyphPoint, private val radius: Double) :
    GlyphShape(GlyphRole.Line, false) {
    override fun build(): Path {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val distance = hypot(dx, dy)
        val inset = (radius + Glyph.STROKE_WIDTH) / distance
        return Path().apply {
            moveTo((from.x + dx * inset).toFloat(), (from.y + dy * inset).toFloat())
            lineTo((to.x - dx * inset).toFloat(), (to.y - dy * inset).toFloat())
        }
    }
}

/** SVG path data on the glyph grid, tracing the center of the stroke. */
class GlyphPath(private val data: String, role: GlyphRole = GlyphRole.Body, solid: Boolean = false) : GlyphShape(role, solid) {
    override fun build(): Path = PathParser().parsePathString(data).toPath()
}

class GlyphVertex(val x: Double, val y: Double, val radius: Double = 0.0)

class GlyphPolyline(
    private val vertices: List<GlyphVertex>,
    private val closed: Boolean = false,
    role: GlyphRole = GlyphRole.Body,
) : GlyphShape(role, false) {
    private fun corner(index: Int): Pair<Offset, Offset> {
        val count = vertices.size
        val vertex = vertices[index]
        val previous = vertices[(index - 1 + count) % count]
        val next = vertices[(index + 1) % count]
        val px = previous.x - vertex.x
        val py = previous.y - vertex.y
        val nx = next.x - vertex.x
        val ny = next.y - vertex.y
        val pd = hypot(px, py)
        val nd = hypot(nx, ny)
        val enterScale = min(vertex.radius, pd / 2) / pd
        val exitScale = min(vertex.radius, nd / 2) / nd
        return Offset((vertex.x + px * enterScale).toFloat(), (vertex.y + py * enterScale).toFloat()) to
            Offset((vertex.x + nx * exitScale).toFloat(), (vertex.y + ny * exitScale).toFloat())
    }

    override fun build(): Path {
        val path = Path()
        val count = vertices.size
        val first = if (closed) corner(0).second else Offset(vertices.first().x.toFloat(), vertices.first().y.toFloat())
        path.moveTo(first.x, first.y)
        val last = if (closed) count else count - 1
        for (index in 1..last) {
            val vertex = vertices[index % count]
            if (!closed && index == count - 1) {
                path.lineTo(vertex.x.toFloat(), vertex.y.toFloat())
                break
            }
            val (enter, exit) = corner(index % count)
            path.lineTo(enter.x, enter.y)
            path.quadraticTo(vertex.x.toFloat(), vertex.y.toFloat(), exit.x, exit.y)
        }
        if (closed) path.close()
        return path
    }
}

private const val FILL_START = Glyph.STROKE_WIDTH / 2
private const val DETAIL_SWAP = 0.5f
private const val FILLED_LINE_WIDTH = 2.2f

private fun stroke(color: Color, width: Float) = Paint().apply {
    this.color = color
    style = PaintingStyle.Stroke
    strokeWidth = width
    strokeCap = StrokeCap.Round
    strokeJoin = StrokeJoin.Round
    isAntiAlias = true
}

private fun area(color: Color) = Paint().apply {
    this.color = color
    isAntiAlias = true
}

/** glyph_painter.dart's GlyphPainter, drawn live. */
fun DrawScope.drawGlyph(glyph: Glyph, fill: Float, color: Color, mirror: Boolean = false) {
    if (!io.nekohasekai.sfa.compose.DebugSwitches.icons) return
    drawIntoCanvas { canvas -> paintGlyph(canvas, size.width, size.height, glyph, fill, color, mirror) }
}

private fun paintGlyph(canvas: Canvas, width: Float, height: Float, glyph: Glyph, fill: Float, color: Color, mirror: Boolean) {
    val opaque = color.copy(alpha = 1f)
    val strokePaint = stroke(opaque, Glyph.STROKE_WIDTH)
    val clamped = fill.coerceIn(0f, 1f)
    val lineWidth = Glyph.STROKE_WIDTH + (FILLED_LINE_WIDTH - Glyph.STROKE_WIDTH) * clamped
    val linePaint = stroke(opaque, lineWidth)
    val areaPaint = area(opaque)
    val cut = fill > DETAIL_SWAP
    val detailScale = if (cut) (fill - DETAIL_SWAP) / (1 - DETAIL_SWAP) else 1 - fill / DETAIL_SWAP
    val scale = min(width, height) / Glyph.SIZE
    run {
        canvas.save()
        if (mirror) {
            canvas.translate(width, 0f)
            canvas.scale(-1f, 1f)
        }
        canvas.scale(scale, scale)
        val grid = Rect(0f, 0f, Glyph.SIZE, Glyph.SIZE)
        if (color.alpha < 1f || (cut && detailScale > 0f)) {
            canvas.saveLayer(grid, Paint().apply { alpha = color.alpha })
        } else {
            canvas.save()
        }
        for (shape in glyph.shapes) {
            if (shape.role == GlyphRole.Body) paintFill(canvas, shape.path, opaque, fill)
        }
        if (detailScale > 0f) {
            for (shape in glyph.shapes) {
                if (shape.role == GlyphRole.Detail) paintDetail(canvas, shape, opaque, detailScale, cut)
            }
        }
        for (shape in glyph.shapes) {
            if (shape.role == GlyphRole.Line && shape.solid) {
                drawScaled(canvas, shape.path, areaPaint, lineWidth / Glyph.STROKE_WIDTH)
            } else if (shape.role != GlyphRole.Detail) {
                val paint = when {
                    shape.solid -> areaPaint
                    shape.role == GlyphRole.Line -> linePaint
                    else -> strokePaint
                }
                canvas.drawPath(shape.path, paint)
            }
        }
        canvas.restore()
        canvas.restore()
    }
}

private fun paintFill(canvas: Canvas, body: Path, color: Color, fill: Float) {
    if (fill <= 0f) return
    if (fill >= 1f) {
        canvas.drawPath(body, area(color))
        return
    }
    val bounds = body.getBounds()
    val depth = min(bounds.width, bounds.height) / 2
    val reach = FILL_START + (depth - FILL_START) * fill
    canvas.save()
    canvas.clipPath(body)
    canvas.drawPath(body, stroke(color, reach * 2))
    canvas.restore()
}

private fun paintDetail(canvas: Canvas, detail: GlyphShape, color: Color, scale: Float, cut: Boolean) {
    val blend = if (cut) BlendMode.Clear else BlendMode.SrcOver
    if (!detail.solid) {
        canvas.drawPath(detail.path, stroke(color, Glyph.STROKE_WIDTH * scale).apply { blendMode = blend })
        return
    }
    drawScaled(canvas, detail.path, area(color).apply { blendMode = blend }, scale)
}

private fun drawScaled(canvas: Canvas, path: Path, paint: Paint, scale: Float) {
    val center = path.getBounds().center
    canvas.save()
    canvas.translate(center.x, center.y)
    canvas.scale(scale, scale)
    canvas.translate(-center.x, -center.y)
    canvas.drawPath(path, paint)
    canvas.restore()
}

/** glyph_icon.dart's GlyphIcon. */
@Composable
fun GlyphIcon(
    glyph: Glyph,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    color: Color = LocalContentColor.current,
    fill: Float = 0f,
) {
    GlyphIcon(glyph, modifier, size, color, fill, live = false)
}

@Composable
private fun GlyphIcon(glyph: Glyph, modifier: Modifier, size: Dp, color: Color, fill: Float, live: Boolean) {
    val mirror = glyph.matchTextDirection && LocalLayoutDirection.current == LayoutDirection.Rtl
    Canvas(modifier.size(size)) {
        if (live) drawGlyph(glyph, fill, color, mirror) else drawGlyphCached(glyph, fill, color, mirror)
    }
}

/**
 * Draws [glyph] from a mask rasterized once per glyph, pixel size, fill and
 * mirroring, the way VectorPainter caches a vector; the mask is alpha-only and
 * takes [color] (alpha included) when drawn. A fill between 0 and 1 is a
 * fill animation in flight and is drawn live, as are in-between frames of a
 * computed glyph and sizes too large to be worth caching.
 */
fun DrawScope.drawGlyphCached(glyph: Glyph, fill: Float, color: Color, mirror: Boolean = false, box: Size = size) {
    if (!io.nekohasekai.sfa.compose.DebugSwitches.icons) return
    val width = box.width.roundToInt()
    val height = box.height.roundToInt()
    val settled = fill == 0f || fill == 1f
    if (!settled || !glyph.cacheable || width <= 0 || height <= 0 || width * height > MAX_CACHED_GLYPH_PIXELS) {
        drawIntoCanvas { canvas -> paintGlyph(canvas, box.width, box.height, glyph, fill, color, mirror) }
        return
    }
    val mask = GlyphMasks.get(GlyphKey(glyph.cacheKey, width, height, fill == 1f, mirror)) {
        ImageBitmap(width, height, ImageBitmapConfig.Alpha8).also { image ->
            paintGlyph(Canvas(image), width.toFloat(), height.toFloat(), glyph, fill, Color.White, mirror)
        }
    }
    drawMask(mask, color)
}

private data class GlyphKey(val glyph: Any, val width: Int, val height: Int, val filled: Boolean, val mirror: Boolean)

private const val MAX_CACHED_GLYPH_PIXELS = 256 * 256

private val EaseInOutCubic = CubicBezierEasing(0.645f, 0.045f, 0.355f, 1f)

/** animated_glyph.dart: fills over 350 ms and pops by a tenth when it fills. */
@Composable
fun AnimatedGlyph(glyph: Glyph, filled: Boolean, modifier: Modifier = Modifier, size: Dp = 24.dp, color: Color = LocalContentColor.current) {
    val fill = remember { Animatable(if (filled) 1f else 0f) }
    val pop = remember { Animatable(0f) }
    LaunchedEffect(filled) {
        if (filled) {
            coroutineScope {
                launch { fill.animateTo(1f, tween(350, easing = EaseInOutCubic)) }
                launch {
                    pop.snapTo(0f)
                    pop.animateTo(1f, tween(350, easing = LinearEasing))
                    pop.snapTo(0f)
                }
            }
        } else {
            fill.animateTo(0f, tween(350, easing = EaseInOutCubic))
        }
    }
    val popScale = if (pop.value > 0f && pop.value < 1f) sin(PI * pop.value).toFloat() * 0.1f else 0f
    GlyphIcon(
        glyph,
        modifier.graphicsLayer {
            scaleX = 1 + popScale
            scaleY = 1 + popScale
        },
        size = size,
        color = color,
        fill = fill.value,
        // The pop scales the layer: a live path stays sharp where a scaled mask would soften.
        live = popScale != 0f,
    )
}

/** app_glyphs.dart's sidebar(progress). */
internal fun computedSidebar(progress: Double): Glyph {
    val divider = 8.5 + (11.5 - 8.5) * progress
    val shapes = mutableListOf<GlyphShape>(
        GlyphBox(3.0, 4.5, 21.0, 19.5, 3.5),
        GlyphPolyline(listOf(GlyphVertex(divider, 4.5), GlyphVertex(divider, 19.5)), role = GlyphRole.Detail),
    )
    for (y in listOf(8.5, 11.0, 13.5)) {
        shapes += GlyphPolyline(listOf(GlyphVertex(5.5, y), GlyphVertex(divider - 2.5, y)), role = GlyphRole.Detail)
    }
    return Glyph(shapes, matchTextDirection = true, key = "sidebar" to progress, cacheable = progress == 0.0 || progress == 1.0)
}

private val playCorners = listOf(GlyphPoint(8.3, 5.6), GlyphPoint(18.8, 12.0), GlyphPoint(8.3, 18.4))
private val playHalves = listOf(
    listOf(GlyphPoint(8.3, 5.6), GlyphPoint(18.8, 12.0), GlyphPoint(18.8, 12.0), GlyphPoint(8.3, 12.0)) to
        listOf(GlyphPoint(5.9, 6.9), GlyphPoint(18.1, 6.9), GlyphPoint(18.1, 9.1), GlyphPoint(5.9, 9.1)),
    listOf(GlyphPoint(8.3, 12.0), GlyphPoint(18.8, 12.0), GlyphPoint(18.8, 12.0), GlyphPoint(8.3, 18.4)) to
        listOf(GlyphPoint(5.9, 14.9), GlyphPoint(18.1, 14.9), GlyphPoint(18.1, 17.1), GlyphPoint(5.9, 17.1)),
)

private fun turnedVertex(corner: GlyphPoint, angle: Double): GlyphVertex {
    val dx = corner.x - 12
    val dy = corner.y - 12
    return GlyphVertex(12 + dx * cos(angle) - dy * sin(angle), 12 + dx * sin(angle) + dy * cos(angle), 1.2)
}

/** app_glyphs.dart's playPause: play at 0, pause at 1; past either end it keeps turning. */
internal fun computedPlayPause(progress: Double): Glyph {
    val fold = progress.coerceIn(0.0, 1.0)
    val turn = PI / 2 * progress
    val key = "playPause" to progress
    val cacheable = progress == 0.0 || progress == 1.0
    if (fold == 0.0) {
        return Glyph(listOf(GlyphPolyline(playCorners.map { turnedVertex(it, turn) }, closed = true)), key = key, cacheable = cacheable)
    }
    return Glyph(
        key = key,
        cacheable = cacheable,
        shapes = playHalves.map { (half, bar) ->
            GlyphPolyline(
                half.indices.map { i ->
                    turnedVertex(GlyphPoint(half[i].x + (bar[i].x - half[i].x) * fold, half[i].y + (bar[i].y - half[i].y) * fold), turn)
                },
                closed = true,
            )
        },
    )
}
