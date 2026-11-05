package io.nekohasekai.sfa.compose.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.compose.theme.AppShape
import io.nekohasekai.sfa.compose.theme.AppTextStyle
import io.nekohasekai.sfa.compose.theme.BoxTheme
import io.nekohasekai.sfa.compose.theme.SurfaceLevel
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * flutter/lib/ui/navigation_dock.dart (FlClash UI kit, GPL-3.0): the floating
 * pill of destinations whose selection is a lens that springs between them,
 * after iOS's tab bar. Same metrics, colors and springs; the drag jelly,
 * hover parallax and the liquid glass lens are left for a later phase.
 */

private const val BAR_HEIGHT = 62f
private const val BAR_PADDING = 4f
private const val EDGE_MARGIN = 21f
private const val SHADOW_ROOM = 8f
private const val MAX_ITEM_EXTENT = 86f
private const val MIN_LENS_WIDTH = 84f
private const val FULL_WIDTH_COUNT = 5
private const val ICON_SIZE = 28f
private const val LABEL_GAP = 2f
private const val LABEL_INSET = 2f
private const val LABEL_SIZE = 10f
private const val LABEL_LEADING = 14.5f
private const val LENS_GROWTH = 8f
private const val LENS_MAGNIFY = 0.16f

// SpringDescription.withDurationAndBounce: stiffness (2π/d)², damping ratio 1 - bounce.
private val travelSpring = spring<Float>(dampingRatio = 0.85f, stiffness = 246.7f)
private val liftSpring = spring<Float>(dampingRatio = 1f, stiffness = 631.7f)
private val followSpring = spring<Float>(dampingRatio = 0.9f, stiffness = 1024f)

class DockDestination(val glyph: Glyph, val label: String)

object NavigationDock {
    fun height(fontScale: Float): Dp = (BAR_HEIGHT + LABEL_SIZE * fontScale - LABEL_SIZE).dp

    @Composable
    fun bottomMargin(): Dp = max(EDGE_MARGIN, WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding().value).dp

    /** The room content scrolling under the dock leaves at its foot. */
    @Composable
    fun inset(): Dp = height(LocalDensity.current.fontScale) + bottomMargin()
}

private class DockLayout(val pitch: Float, val lens: Float, val inset: Float)

private fun dockLayout(span: Float, count: Int): DockLayout {
    if (count <= 1) return DockLayout(span - BAR_PADDING * 2, span, BAR_PADDING)
    val pitch = (span - BAR_PADDING * 2) / count
    if (pitch + BAR_PADDING * 2 >= MIN_LENS_WIDTH) return DockLayout(pitch, pitch + BAR_PADDING * 2, BAR_PADDING)
    val lens = min(MIN_LENS_WIDTH, span)
    val closed = (span - lens) / (count - 1)
    return DockLayout(closed, lens, (lens - closed) / 2)
}

@Composable
fun NavigationDock(destinations: List<DockDestination>, selectedIndex: Int, onSelected: (Int) -> Unit, modifier: Modifier = Modifier) {
    val scheme = BoxTheme.scheme
    val fontScale = LocalDensity.current.fontScale
    val height = NavigationDock.height(fontScale)
    val bottom = NavigationDock.bottomMargin()
    val maxWidth = if (destinations.size >= FULL_WIDTH_COUNT) Dp.Infinity else (destinations.size * MAX_ITEM_EXTENT + BAR_PADDING * 4).dp
    val floating = SurfaceLevel.floatingOf(scheme)
    Box(
        modifier.fillMaxWidth().height(SHADOW_ROOM.dp + height + bottom)
            .padding(start = EDGE_MARGIN.dp, top = SHADOW_ROOM.dp, end = EDGE_MARGIN.dp, bottom = bottom),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .widthIn(max = maxWidth)
                .fillMaxWidth()
                .height(height)
                .boxShadows(AppShape.full, dockShadows(scheme))
                .clip(AppShape.full)
                .background(floating.colorOf(scheme))
                .padding(BAR_PADDING.dp),
        ) {
            FloatingBar(destinations, selectedIndex, onSelected, floating)
        }
    }
}

@Composable
private fun FloatingBar(destinations: List<DockDestination>, selectedIndex: Int, onSelected: (Int) -> Unit, floating: SurfaceLevel) {
    val scheme = BoxTheme.scheme
    val count = destinations.size
    val selected = selectedIndex.coerceIn(0, max(0, count - 1))
    val lens = remember { Animatable(selected.toFloat()) }
    val lift = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var pressed by remember { mutableIntStateOf(-1) }
    val currentSelected by rememberUpdatedState(selected)
    val currentOnSelected by rememberUpdatedState(onSelected)
    LaunchedEffect(selected) {
        if (pressed < 0) lens.animateTo(selected.toFloat(), travelSpring)
    }
    val density = LocalDensity.current
    val labelStyle = AppTextStyle.custom(LABEL_SIZE, LABEL_LEADING, FontWeight.W500)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val span = constraints.maxWidth / density.density
        val heightDp = constraints.maxHeight / density.density
        val layout = dockLayout(span, count)
        fun lensRect(): RoundRect {
            val growth = LENS_GROWTH * 2 * lift.value
            val width = layout.lens + growth
            val h = heightDp + growth
            val left = (lens.value + 0.5f) * layout.pitch - width / 2 + layout.inset
            val top = (heightDp - h) / 2
            val px = density.density
            return RoundRect(left * px, top * px, (left + width) * px, (top + h) * px, CornerRadius(h * px / 2))
        }
        val lensColor = scheme.onSurface.copy(alpha = 0.08f * lift.value.coerceIn(0f, 1f)).compositeOver(floating.next.colorOf(scheme))
        // The lens: a pill one surface level up, swelling as it lifts.
        Box(
            Modifier.fillMaxSize().drawWithContent {
                val rect = lensRect()
                drawPath(Path().apply { addRoundRect(rect) }, lensColor)
            },
        )
        // Destinations, then their copy tinted in the primary color cut to the lens.
        DockItems(destinations, layout, scheme.onSurfaceVariant, labelStyle, Modifier)
        DockItems(
            destinations,
            layout,
            scheme.primary,
            labelStyle,
            Modifier.drawWithContent {
                val rect = lensRect()
                val magnify = 1 + LENS_MAGNIFY * lift.value.coerceIn(0f, 1f)
                clipPath(Path().apply { addRoundRect(rect) }) {
                    scale(magnify, magnify, pivot = Offset((rect.left + rect.right) / 2, (rect.top + rect.bottom) / 2)) {
                        this@drawWithContent.drawContent()
                    }
                }
            },
        )
        // Raw pointers: a press lifts the lens under the finger, a drag slides
        // it, and letting go selects where it rests.
        Box(
            Modifier.fillMaxSize().pointerInput(count, layout.pitch) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    fun indexAt(x: Float): Float = ((x / density.density - layout.inset) / layout.pitch - 0.5f).coerceIn(0f, (count - 1).toFloat())
                    val start = indexAt(down.position.x).roundToInt()
                    pressed = start
                    scope.launch { lift.animateTo(1f, liftSpring) }
                    scope.launch { lens.animateTo(start.toFloat(), followSpring) }
                    var dragged = false
                    var position = down.position.x
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        if (change.positionChange() != Offset.Zero) {
                            position = change.position.x
                            if (kotlin.math.abs(position - down.position.x) > viewConfiguration.touchSlop) dragged = true
                            if (dragged) {
                                val target = indexAt(position)
                                scope.launch { lens.animateTo(target, followSpring) }
                            }
                            change.consume()
                        }
                    }
                    val index = if (dragged) indexAt(position).roundToInt() else start
                    pressed = -1
                    scope.launch { lens.animateTo(index.toFloat(), travelSpring) }
                    scope.launch { lift.animateTo(0f, liftSpring) }
                    if (index != currentSelected) currentOnSelected(index)
                }
            },
        )
    }
}

@Composable
private fun DockItems(destinations: List<DockDestination>, layout: DockLayout, color: Color, labelStyle: androidx.compose.ui.text.TextStyle, modifier: Modifier) {
    Row(modifier.fillMaxSize().padding(horizontal = layout.inset.dp)) {
        for (destination in destinations) {
            Column(
                Modifier.weight(1f).fillMaxHeight().padding(horizontal = LABEL_INSET.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            ) {
                GlyphIcon(destination.glyph, size = ICON_SIZE.dp, color = color, fill = 1f)
                Spacer(Modifier.height(LABEL_GAP.dp))
                Text(destination.label, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, style = labelStyle.copy(color = color))
            }
        }
    }
}
