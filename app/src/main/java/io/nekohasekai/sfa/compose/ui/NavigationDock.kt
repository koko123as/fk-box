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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalView
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
 * after iOS's tab bar. Same metrics, colors, springs, press swell, drag with
 * rubber-banding, fling, jelly and lift timing. Pointer hover (highlight and
 * parallax), touch glow and the liquid glass lens are not translated yet.
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
    val swell = remember { Animatable(0f) }
    val stretch = remember { Animatable(0f) }
    val pxDensity = LocalDensity.current.density
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
                .graphicsLayer {
                    // _PressTransform: the bar swells under a press and stretches past either end.
                    val longest = max(size.width, size.height) / pxDensity
                    val grow = if (longest <= 0f) 1f else 1 + swell.value * min(PRESS_GROWTH_DOCK * 2, MAX_PRESS_GROWTH_DOCK / longest)
                    scaleX = grow * (1 + kotlin.math.abs(stretch.value) / max(1f, size.width) * PULL_STRETCH)
                    scaleY = grow
                    translationX = stretch.value
                }
                .boxShadows(AppShape.full, dockShadows(scheme))
                .clip(AppShape.full)
                .background(floating.colorOf(scheme))
                .padding(BAR_PADDING.dp),
        ) {
            FloatingBar(destinations, selectedIndex, onSelected, floating, swell, stretch)
        }
    }
}

private const val LIFT_SNAP = 0.975f
private const val LIFT_RELEASE = 0.04f
private const val JELLY_SPEED = 3f
private const val JELLY_STRETCH = 0.2f
private const val JELLY_BRAKE = 25f
private const val JELLY_SQUASH = 0.15f
private const val JELLY_ASPECT = 1.4f
private const val OVERDRAG = 0.35f
private const val PULL_LIMIT = 7f / 32
private const val PULL_STRETCH = 0.5f
private const val FLING_PROJECTION = 0.1f
private const val PRESS_GROWTH_DOCK = 1f / 8
private const val MAX_PRESS_GROWTH_DOCK = 16f

private val trackSpring = spring<Float>(dampingRatio = 1f, stiffness = 2741.6f)
private val pressSpringDock = spring<Float>(dampingRatio = 0.593f, stiffness = 311f)
private val jellySpring = spring<Float>(dampingRatio = 0.4f, stiffness = 100f)

private fun rubberBand(overshoot: Float, limit: Float): Float {
    val pull = 1 - 1 / (kotlin.math.abs(overshoot) * 0.55f / limit + 1)
    return limit * pull * kotlin.math.sign(overshoot)
}

private fun saturate(value: Float): Float = 1 - 2 / (kotlin.math.exp(2 * value) + 1)

@Composable
private fun FloatingBar(
    destinations: List<DockDestination>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    floating: SurfaceLevel,
    swell: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>,
    stretch: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>,
) {
    val scheme = BoxTheme.scheme
    val count = destinations.size
    val lastIndex = max(0, count - 1)
    val selected = selectedIndex.coerceIn(0, lastIndex)
    val lens = remember { Animatable(selected.toFloat()) }
    val lift = remember { Animatable(0f) }
    val jelly = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    var pressedIndex by remember { mutableIntStateOf(-1) }
    var dropping by remember { mutableStateOf(false) }
    val currentSelected by rememberUpdatedState(selected)
    val currentOnSelected by rememberUpdatedState(onSelected)
    LaunchedEffect(selected) {
        if (pressedIndex < 0 && lens.targetValue != selected.toFloat()) lens.animateTo(selected.toFloat(), travelSpring)
    }
    // _wobble: the lens stretches with its speed and squashes as it brakes.
    LaunchedEffect(Unit) {
        var lastVelocity = 0f
        var lastTime = 0L
        while (true) {
            withFrameNanos { time ->
                val velocity = lens.velocity
                val dt = if (lastTime == 0L) 0f else (time - lastTime) / 1e9f
                val acceleration = if (dt > 0f) (velocity - lastVelocity) / dt else 0f
                lastVelocity = velocity
                lastTime = time
                val braking = max(0f, -acceleration * kotlin.math.sign(velocity))
                val target = JELLY_STRETCH * saturate(kotlin.math.abs(velocity) / JELLY_SPEED) - JELLY_SQUASH * saturate(braking / JELLY_BRAKE)
                if (kotlin.math.abs(target - jelly.targetValue) > 1e-3f) scope.launch { jelly.animateTo(target, jellySpring) }
                // _dropLift: UIKit keeps the lens lifted until it has risen and arrived.
                if (dropping && pressedIndex < 0 && lift.value >= LIFT_SNAP && kotlin.math.abs(lens.targetValue - lens.value) <= LIFT_RELEASE) {
                    dropping = false
                    scope.launch { lift.animateTo(0f, liftSpring) }
                }
            }
        }
    }
    val density = LocalDensity.current
    val labelStyle = AppTextStyle.custom(LABEL_SIZE, LABEL_LEADING, FontWeight.W500)
    BoxWithConstraints(
        Modifier.fillMaxSize(),
    ) {
        val span = constraints.maxWidth / density.density
        val heightDp = constraints.maxHeight / density.density
        val layout = dockLayout(span, count)
        fun lensRect(): RoundRect {
            val growth = LENS_GROWTH * 2 * lift.value
            val width = (layout.lens + growth) * (1 + jelly.value)
            val h = (heightDp + growth) * (1 - jelly.value * JELLY_ASPECT)
            val left = (lens.value + 0.5f) * layout.pitch - width / 2 + layout.inset
            val top = (heightDp - h) / 2
            val px = density.density
            return RoundRect(left * px, top * px, (left + width) * px, (top + h) * px, CornerRadius(h * px / 2))
        }
        val lensColor = scheme.onSurface.copy(alpha = 0.08f * lift.value.coerceIn(0f, 1f)).compositeOver(floating.next.colorOf(scheme))
        // The lens: a pill one surface level up, swelling as it lifts.
        Box(
            Modifier.fillMaxSize().drawWithContent {
                drawPath(Path().apply { addRoundRect(lensRect()) }, lensColor)
            },
        )
        // Destinations, then their copy in the primary color cut to the lens, magnified as it lifts.
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
        // it (rubber-banding past the ends), a flick carries it one further,
        // and letting go selects where it rests.
        Box(
            Modifier.fillMaxSize().pointerInput(count, layout.pitch, layout.inset) {
                fun positionAt(x: Float): Float {
                    val position = (x / density.density - layout.inset) / layout.pitch - 0.5f
                    return when {
                        position < 0 -> rubberBand(position, OVERDRAG)
                        position > lastIndex -> lastIndex + rubberBand(position - lastIndex, OVERDRAG)
                        else -> position
                    }
                }
                fun indexAt(position: Float): Int = position.roundToInt().coerceIn(0, lastIndex)
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (count == 0) return@awaitEachGesture
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    scope.launch { swell.animateTo(1f, pressSpringDock) }
                    val start = indexAt(positionAt(down.position.x))
                    pressedIndex = start
                    dropping = false
                    scope.launch { lift.animateTo(1f, liftSpring) }
                    scope.launch { lens.animateTo(start.toFloat(), travelSpring) }
                    var dragging = false
                    var committed = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            committed = true
                            break
                        }
                        tracker.addPosition(change.uptimeMillis, change.position)
                        if (change.positionChange() == Offset.Zero) continue
                        val x = change.position.x
                        if (!dragging) {
                            if (kotlin.math.abs(x - down.position.x) < viewConfiguration.touchSlop) continue
                            dragging = true
                        }
                        change.consume()
                        val overshoot = x - x.coerceIn(0f, size.width.toFloat())
                        val pull = rubberBand(overshoot, min(size.width, size.height) * PULL_LIMIT)
                        scope.launch { stretch.animateTo(pull, trackSpring) }
                        val position = positionAt(x)
                        scope.launch { lens.animateTo(position, followSpring) }
                        val index = indexAt(position)
                        if (index != pressedIndex) {
                            view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                            pressedIndex = index
                        }
                    }
                    if (committed && dragging) {
                        // _fling: a flick carries on at most one past the destination under the finger.
                        val velocity = tracker.calculateVelocity().x / density.density
                        val lead = velocity / layout.pitch * FLING_PROJECTION
                        val pressed = pressedIndex
                        val index = (lens.targetValue + lead).roundToInt().coerceIn(pressed - 1, pressed + 1).coerceIn(0, lastIndex)
                        if (index != pressed) {
                            view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                            pressedIndex = index
                        }
                    }
                    val index = pressedIndex
                    pressedIndex = -1
                    dropping = true
                    scope.launch { lens.animateTo((if (committed) index else currentSelected).toFloat(), travelSpring) }
                    scope.launch { stretch.animateTo(0f, pressSpringDock) }
                    scope.launch { swell.animateTo(0f, pressSpringDock) }
                    if (committed && index != currentSelected) currentOnSelected(index)
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
