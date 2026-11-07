package io.nekohasekai.sfa.compose.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

/*
 * prism_ui 63450c0 lib/drag_back.dart (DragBackRouteMixin), as used by
 * FlClash v0.9.3 be84736, GPL-3.0: iOS 27's interactive pop. The page trails
 * the finger on a stiff spring, pops when the distance dragged plus the
 * velocity over the 0.35 s left of the transition reaches 0.4665 of the
 * width, and lets go on a spring a flick past 1.5 widths a second speeds up
 * 4.25 times, ramping in from one. A drag toward the leading edge stretches.
 */

private val FOLLOW_SPRING = durationSpring(80, 0.15f)
private val RELEASE_SPRING = durationSpring(300, 0.15f)
private const val POP_REACH = 0.4665f
private const val POP_LEAD = 0.35f
private const val FLICK_SPEED = 1.5f
private const val FLICK_RAMP_START = 1f
private const val FLICK_BOOST = 4.25f
private const val TOUCH_SLOP_DP = 30f
private const val RUBBER_STIFFNESS = 0.55f
private const val RUBBER_REACH = 0.5f

/** The system back gesture's progress, drawn as this share of the drag back. */
internal const val PREDICTIVE_BACK_REACH = 0.4f

/** A drag back in progress on one route: the page's offset as it follows the finger. */
internal class DragBackState(
    /** The minimal style: the page stays put under the finger and fades back on release. */
    val still: Boolean = false,
) {
    val offset = Animatable(0f)
    var translation = 0f
}

/** dragBackTargetOf: up to the width toward the end; rubber-banded toward the start. */
private fun targetOf(translation: Float, width: Float): Float {
    val reach = RUBBER_REACH * width
    return if (translation >= 0f) {
        minOf(translation, width)
    } else {
        -(1 - 1 / (RUBBER_STIFFNESS * -translation / reach + 1)) * reach
    }
}

private fun releaseSpeedOf(speed: Float): Float {
    val boost = ((speed - FLICK_RAMP_START) / (FLICK_SPEED - FLICK_RAMP_START)).coerceIn(0f, 1f)
    return speed * (1 + (FLICK_BOOST - 1) * boost)
}

internal fun NavLevel.startDragBack() {
    val state = DragBackState(still = !io.nekohasekai.sfa.compose.theme.AppearanceTokens.active.expressiveMotion)
    drag = state
}

internal fun NavLevel.dragBackBy(scope: CoroutineScope, delta: Float) {
    val state = drag ?: return
    state.translation += delta
    if (state.still) return
    val target = targetOf(state.translation, width)
    scope.launch { state.offset.animateTo(target, FOLLOW_SPRING) }
}

/** The system back gesture's progress drives the same follow. */
internal fun NavLevel.dragBackTo(scope: CoroutineScope, translation: Float) {
    val state = drag ?: return
    dragBackBy(scope, translation - state.translation)
}

/**
 * Lets go of the drag: pops when it reached far enough (or [commit] says so),
 * otherwise springs back. A route with an edit guard springs back first and
 * then asks its guard, as Navigator.maybePop would.
 */
internal suspend fun BoxNavigator.endDragBack(level: NavLevel, velocity: Float, commit: Boolean? = null) {
    val state = level.drag ?: return
    val width = level.width.coerceAtLeast(1f)
    val pops = commit ?: (state.translation + POP_LEAD * velocity >= POP_REACH * width)
    val speed = releaseSpeedOf(velocity / width) * width
    if (state.still) {
        // The gesture only decides; the pop itself is the minimal style's short fade.
        level.drag = null
        if (pops && level.guard == null && !level.closing) {
            level.closing = true
            level.presentation.animateTo(0f, io.nekohasekai.sfa.compose.theme.AppearanceTokens.active.fadeOut<Float>())
            finishClose(level)
        } else if (pops) {
            maybePop()
        }
        return
    }
    if (pops && level.guard == null && !level.closing) {
        level.closing = true
        coroutineScope {
            var job: Job? = null
            job = launch {
                // The card ends once it reaches the edge rather than bouncing off it.
                state.offset.animateTo(width, RELEASE_SPRING, speed) { if (value >= width) job?.cancel() }
            }
        }
        level.presentation.snapTo(0f)
        level.drag = null
        finishClose(level)
        return
    }
    state.offset.animateTo(0f, RELEASE_SPRING, speed)
    level.drag = null
    if (pops) maybePop()
}

/** Drag anywhere on the route toward its reading end to pop. A horizontal scroller under the finger takes the drag first. */
internal fun Modifier.dragBack(navigator: BoxNavigator, level: NavLevel, scope: CoroutineScope, rtl: Boolean): Modifier = pointerInput(navigator, level, rtl) {
    val slop = TOUCH_SLOP_DP * density
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
        if (!navigator.canDragBack(level)) return@awaitEachGesture
        val toward = if (rtl) -1f else 1f
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        var moved = androidx.compose.ui.geometry.Offset.Zero
        // Wait for the slop toward the end, giving up to a child or a vertical drag.
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
            if (!change.pressed || change.isConsumed) return@awaitEachGesture
            tracker.addPosition(change.uptimeMillis, change.position)
            moved += change.positionChange()
            val along = moved.x * toward
            if (abs(moved.y) > slop && abs(moved.y) > abs(along)) return@awaitEachGesture
            if (along < -slop) return@awaitEachGesture
            if (along > slop) {
                change.consume()
                break
            }
        }
        if (!navigator.canDragBack(level)) return@awaitEachGesture
        level.startDragBack()
        var velocity = 0f
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            val change = event.changes.firstOrNull { it.id == down.id }
            if (change == null || !change.pressed) {
                if (change != null) tracker.addPosition(change.uptimeMillis, change.position)
                velocity = tracker.calculateVelocity().x * toward
                break
            }
            tracker.addPosition(change.uptimeMillis, change.position)
            level.dragBackBy(scope, change.positionChange().x * toward)
            change.consume()
        }
        scope.launch { navigator.endDragBack(level, velocity) }
    }
}
