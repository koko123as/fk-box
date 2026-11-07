package io.nekohasekai.sfa.compose.ui

import android.graphics.BlurMaskFilter
import android.graphics.RectF
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import kotlin.math.ceil
import kotlin.math.max

/*
 * Bitmaps that stand in for drawing HWUI would otherwise redo on the GPU every
 * frame. Raw anti-aliased glyph paths with saveLayer/Clear cutouts and blurred
 * shadow masks each make Skia rasterize a coverage mask or allocate an
 * offscreen target per frame; on Mali those textures pile up in the driver's
 * per-process pool. An alpha-only (ALPHA_8) bitmap is drawn with the paint's
 * color, so one mask serves every tint and is uploaded to the GPU once.
 */

/** A size-bounded LRU of ALPHA_8 masks, accounted in bytes. */
internal class MaskCache(private val maxBytes: Int) {
    private val entries = LinkedHashMap<Any, ImageBitmap>(64, 0.75f, true)
    private var bytes = 0

    @Synchronized
    fun get(key: Any, create: () -> ImageBitmap): ImageBitmap {
        entries[key]?.let { return it }
        val image = create()
        val size = image.width * image.height
        if (size > maxBytes) return image
        entries[key] = image
        bytes += size
        val iterator = entries.entries.iterator()
        while (bytes > maxBytes && iterator.hasNext()) {
            val eldest = iterator.next()
            if (eldest.value === image) break
            bytes -= eldest.value.width * eldest.value.height
            iterator.remove()
        }
        return image
    }

    /** Drops the references only: a display list that still draws a mask keeps it alive, so nothing is recycled here. */
    @Synchronized
    fun clear() {
        entries.clear()
        bytes = 0
    }
}

internal val GlyphMasks = MaskCache(4 * 1024 * 1024)
internal val ShadowMasks = MaskCache(8 * 1024 * 1024)

/** Clears every raster cache; the Activity calls this when its UI is hidden or memory is short. */
fun clearRasterCaches() {
    GlyphMasks.clear()
    ShadowMasks.clear()
}

private fun bitmapPaint(color: androidx.compose.ui.graphics.Color) = android.graphics.Paint().apply {
    this.color = color.toArgb()
    isFilterBitmap = true
    isAntiAlias = true
}

/** Draws an alpha mask in [color] at its own pixel size, its top-left at the scope's origin. */
internal fun DrawScope.drawMask(mask: ImageBitmap, color: androidx.compose.ui.graphics.Color) {
    drawIntoCanvas { it.nativeCanvas.drawBitmap(mask.asAndroidBitmap(), 0f, 0f, bitmapPaint(color)) }
}

/** A blurred shadow mask, rendered once; [bounds] is where it lands in the scope that asked for it. */
internal class ShadowMask(val image: ImageBitmap, val bounds: RectF)

/**
 * The Gaussian falloff of a blurred [path] (pixels) as an alpha mask. A wide
 * blur has no detail finer than its radius, so it is rendered at a reduced
 * scale (never below a quarter, keeping at least 8 px of blur in the mask)
 * and stretched back with bilinear filtering when drawn.
 */
internal fun shadowMask(key: Any, radius: Float, path: () -> Path): ShadowMask {
    val source = path()
    val bounds = source.getBounds()
    // BlurMaskFilter's sigma is radius * 0.57735 + 0.5; three sigma reaches the end of the falloff.
    val margin = ceil(radius * 1.8f + 2f)
    val scale = if (radius <= 8f) 1f else (8f / radius).coerceIn(.25f, 1f)
    val left = bounds.left - margin
    val top = bounds.top - margin
    val width = bounds.width + 2 * margin
    val height = bounds.height + 2 * margin
    val pixelsWide = max(1, ceil(width * scale).toInt())
    val pixelsHigh = max(1, ceil(height * scale).toInt())
    val image = ShadowMasks.get(ShadowKey(key, radius)) {
        ImageBitmap(pixelsWide, pixelsHigh, ImageBitmapConfig.Alpha8).also { image ->
            val canvas = Canvas(image).nativeCanvas
            canvas.scale(scale, scale)
            canvas.translate(-left, -top)
            canvas.drawPath(
                source.asAndroidPath(),
                android.graphics.Paint().apply {
                    isAntiAlias = true
                    color = android.graphics.Color.WHITE
                    if (radius > 0f) maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL)
                },
            )
        }
    }
    return ShadowMask(image, RectF(left, top, left + image.width / scale, top + image.height / scale))
}

private data class ShadowKey(val key: Any, val radius: Float)

/** Draws [mask] in [color], moved by ([dx], [dy]). */
internal fun DrawScope.drawShadowMask(mask: ShadowMask, color: androidx.compose.ui.graphics.Color, dx: Float = 0f, dy: Float = 0f) {
    val bounds = mask.bounds
    val target = RectF(bounds.left + dx, bounds.top + dy, bounds.right + dx, bounds.bottom + dy)
    drawIntoCanvas { it.nativeCanvas.drawBitmap(mask.image.asAndroidBitmap(), null, target, bitmapPaint(color)) }
}
