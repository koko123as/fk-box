package io.nekohasekai.sfa.compose.pages

import android.graphics.BlurMaskFilter
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import io.nekohasekai.sfa.compose.theme.AppShape
import io.nekohasekai.sfa.compose.theme.BoxTheme
import io.nekohasekai.sfa.compose.theme.SurfaceLevel
import io.nekohasekai.sfa.compose.ui.AppGlyphs
import io.nekohasekai.sfa.compose.ui.GlyphIcon
import io.nekohasekai.sfa.compose.ui.MaterialShapes
import io.nekohasekai.sfa.compose.ui.elasticPress
import io.nekohasekai.sfa.compose.ui.morphPathOf
import io.nekohasekai.sfa.compose.ui.semanticsLabel
import io.nekohasekai.sfa.compose.ui.tr
import kotlin.math.PI

val EaseOutBack = CubicBezierEasing(0.175f, 0.885f, 0.32f, 1.275f)
val EaseInCubic = CubicBezierEasing(0.55f, 0.055f, 0.675f, 0.19f)
val EaseOutCubic = CubicBezierEasing(0.215f, 0.61f, 0.355f, 1f)

private val startMorph by lazy { Morph(MaterialShapes.Circle, MaterialShapes.Cookie9Sided) }

/**
 * components/start_toggle.dart: a circle with a play glyph while stopped
 * that grows the cookie-9 of a running tunnel over 450 ms.
 */
@Composable
fun StartToggle(size: Dp, enabled: Boolean, running: Boolean, suspended: Boolean, onTap: () -> Unit) {
    val scheme = BoxTheme.scheme
    val progress = remember { Animatable(if (running) 1f else 0f) }
    LaunchedEffect(running) {
        if (running) progress.animateTo(1f, tween(450, easing = EaseOutBack)) else progress.animateTo(0f, tween(450, easing = EaseInCubic))
    }
    val glyphProgress by animateFloatAsState(if (running) 1f else 0f, tween(450, easing = EaseOutBack), label = "glyph")
    val fill = if (enabled) scheme.primary else SurfaceLevel.Inset.colorOf(scheme)
    val ink = if (enabled) scheme.onPrimary else scheme.onSurfaceVariant
    val shadow = if (enabled) scheme.primary.copy(alpha = 0.3f) else Color.Transparent
    val tooltip = tr(if (suspended) "Remote" else if (running) "Stop" else "Start")
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(size)
            .elasticPress(enabled)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onTap)
            .semanticsLabel(tooltip),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size)) {
            val p = progress.value
            val path = morphPathOf(startMorph, p)
            val turn = Math.toDegrees(p * 2 * PI / 9).toFloat()
            if (shadow.alpha > 0f && Build.VERSION.SDK_INT >= 28) {
                withTransform({
                    translate(0f, 6.dp.toPx())
                    rotate(turn)
                    scale(this@Canvas.size.width, this@Canvas.size.height, pivot = Offset.Zero)
                }) {
                    drawIntoCanvas { canvas ->
                        val paint = Paint().apply { color = shadow }
                        // MaskFilter.blur(sigma 7 dp) in the unit square.
                        val radius = (7f - .5f) / .57735f / this@Canvas.size.width * density
                        paint.asFrameworkPaint().maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL)
                        canvas.drawPath(path, paint)
                    }
                }
            }
            withTransform({
                rotate(turn)
                scale(this@Canvas.size.width, this@Canvas.size.height, pivot = Offset.Zero)
            }) {
                drawPath(path, fill)
            }
        }
        if (suspended) {
            GlyphIcon(AppGlyphs.wifiOff, size = size * 0.34f, color = ink, fill = 1f)
        } else {
            GlyphIcon(AppGlyphs.playPause(glyphProgress.toDouble()), size = size * 0.34f, color = ink, fill = 1f)
        }
    }
}

/** components/connected_buttons.dart: a row of connected buttons 3 dp apart. */
@Composable
fun ConnectedGroup(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically, content = content)
}

/**
 * M3 Expressive connected buttons: the ends round off the group, the inner
 * corners stay tight, and the selected one turns into a full pill.
 */
@Composable
fun ConnectedButton(
    selected: Boolean,
    first: Boolean,
    last: Boolean,
    outer: Float,
    inner: Float,
    background: Color,
    label: String,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (Color) -> Unit,
) {
    val scheme = BoxTheme.scheme
    val amount by animateFloatAsState(if (selected) 1f else 0f, tween(240, easing = EaseOutCubic), label = "connected")
    val startRest = if (first) outer else inner
    val endRest = if (last) outer else inner
    val start = startRest + (outer - startRest) * amount
    val end = endRest + (outer - endRest) * amount
    val shape = AppShape.horizontal(start, end)
    val foreground by animateColorAsState(if (selected) scheme.onPrimary else scheme.onSurfaceVariant, tween(240), label = "foreground")
    Box(
        modifier
            .elasticPress()
            .clip(shape)
            .background(lerp(background, scheme.primary, amount))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onTap)
            .semanticsLabel(label),
        contentAlignment = Alignment.Center,
    ) { content(if (selected) scheme.onPrimary else foreground) }
}
