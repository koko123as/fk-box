package io.nekohasekai.sfa.compose.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/*
 * A right-aligned sample window, Gaussian
 * smoothed, drawn as monotone cubic segments with a 14% fill, its height
 * scaled to the larger of the peak and [minScale].
 */

private const val SIGMA = 1.5
private val RADIUS = ceil(SIGMA * 2.5).toInt()
private val KERNEL = DoubleArray(RADIUS + 1) { d -> exp(-(d * d) / (2 * SIGMA * SIGMA)) }
private const val STROKE_WIDTH = 2f
private const val FILL_OPACITY = 0.14f

private fun series(window: List<Double>, length: Int): DoubleArray {
    val values = DoubleArray(length)
    val count = min(window.size, length)
    for (i in 0 until count) values[length - count + i] = window[window.size - count + i]
    return values
}

private fun smooth(values: DoubleArray): DoubleArray = DoubleArray(values.size) { i ->
    var sum = 0.0
    var weight = 0.0
    for (j in max(i - RADIUS, 0)..min(i + RADIUS, values.size - 1)) {
        val w = KERNEL[abs(j - i)]
        sum += w * values[j]
        weight += w
    }
    sum / weight
}

private fun slopes(values: DoubleArray): DoubleArray {
    val last = values.size - 1
    return DoubleArray(values.size) { i ->
        if (i == 0 || i == last) 0.0 else ((values[i + 1] - values[i - 1]) / 2).coerceIn(-3 * values[i], 3 * values[i])
    }
}

private class ChartCache {
    var source: List<Double>? = null
    var capacity = -1
    var heights = DoubleArray(0)
}

/**
 * [values] is read in the draw phase, so a new sample redraws the chart without
 * recomposing its caller; each window is smoothed once (cached by identity).
 */
@Composable
fun LineChart(values: () -> List<Double>, capacity: Int, minScale: Double, color: Color, baselineRatio: Float, modifier: Modifier = Modifier) {
    val cache = remember { ChartCache() }
    Canvas(modifier) {
        val window = values()
        if (cache.source !== window || cache.capacity != capacity) {
            cache.heights = smooth(series(window, capacity + RADIUS + 1))
            cache.source = window
            cache.capacity = capacity
        }
        val heights = cache.heights
        val step = size.width / (capacity - 1)
        val baseline = size.height * baselineRatio
        val slope = slopes(heights)
        val firstX = size.width - (heights.size - 1) * step
        var x = firstX
        var y = -heights[0].toFloat()
        val curve = Path().apply { moveTo(x, y) }
        for (i in 1 until heights.size) {
            val nextX = x + step
            val nextY = -heights[i].toFloat()
            curve.cubicTo(x + step / 3, y - slope[i - 1].toFloat() / 3, nextX - step / 3, nextY + slope[i].toFloat() / 3, nextX, nextY)
            x = nextX
            y = nextY
        }
        val top = max(minScale.toFloat(), -curve.getBounds().top)
        val yScale = (baseline - STROKE_WIDTH) / top
        val line = Path().apply {
            addPath(curve)
            transform(Matrix().apply { translate(0f, baseline); scale(1f, yScale) })
        }
        val area = Path().apply {
            addPath(line)
            lineTo(x, size.height)
            lineTo(firstX, size.height)
            close()
        }
        clipRect(0f, 0f, size.width, size.height) {
            drawPath(area, color.copy(alpha = color.alpha * FILL_OPACITY))
            drawPath(line, color, style = Stroke(STROKE_WIDTH, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
