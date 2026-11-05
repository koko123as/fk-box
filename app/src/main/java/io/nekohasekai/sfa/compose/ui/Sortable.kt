package io.nekohasekai.sfa.compose.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.nekohasekai.sfa.compose.theme.AppCorner
import io.nekohasekai.sfa.compose.theme.AppShape
import io.nekohasekai.sfa.compose.theme.BoxTheme
import kotlinx.coroutines.launch

/*
 * ReorderableListView with ReorderableDelayedDragStartListener (a long press
 * lifts the row) and a SortHandle (a drag on the handle lifts it at once),
 * decorated as effect.dart's commonProxyDecorator: the lifted row scales to
 * 1.02 on easeInOut and its own corners round to the full card radius.
 */

private val EaseInOut = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

/** ProxyDecoratorProvider: how far the dragged row has lifted, 0–1. */
val LocalSortLift = staticCompositionLocalOf { 0f }

/** The row's own drag start, for the SortHandle inside it. */
private val LocalSortDrag = staticCompositionLocalOf<Modifier> { Modifier }

@Composable
fun <T> ReorderableColumn(
    items: List<T>,
    key: (T) -> Any,
    onMove: (from: Int, to: Int) -> Unit,
    contentPadding: PaddingValues,
    enabled: Boolean = true,
    itemContent: @Composable (index: Int, item: T) -> Unit,
) {
    val heights = remember { mutableStateMapOf<Any, Int>() }
    var dragging by remember { mutableIntStateOf(-1) }
    var offset by remember { mutableFloatStateOf(0f) }
    val lift = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val currentItems = rememberUpdated(items)
    fun start(itemKey: Any) {
        if (!enabled) return
        val index = currentItems.value.indexOfFirst { key(it) == itemKey }
        if (index < 0) return
        dragging = index
        offset = 0f
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        scope.launch { lift.animateTo(1f, tween(200, easing = EaseInOut)) }
    }
    fun drag(delta: Float) {
        val items = currentItems.value
        if (dragging !in items.indices) return
        offset += delta
        val current = dragging
        val height = heights[key(items[current])] ?: return
        if (offset > 0 && current < items.lastIndex) {
            val next = heights[key(items[current + 1])] ?: height
            if (offset > next / 2f) {
                onMove(current, current + 1)
                dragging = current + 1
                offset -= next
            }
        } else if (offset < 0 && current > 0) {
            val previous = heights[key(items[current - 1])] ?: height
            if (-offset > previous / 2f) {
                onMove(current, current - 1)
                dragging = current - 1
                offset += previous
            }
        }
    }
    fun end() {
        scope.launch {
            lift.animateTo(0f, tween(200, easing = EaseInOut))
        }
        val settle = Animatable(offset)
        scope.launch {
            settle.animateTo(0f, tween(200)) { offset = value }
            dragging = -1
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding)) {
        items.forEachIndexed { index, item ->
            val itemKey = key(item)
            androidx.compose.runtime.key(itemKey) {
                val lifted = index == dragging
                val rowDrag = Modifier.pointerInput(itemKey, enabled) {
                    if (!enabled) return@pointerInput
                    detectDragGestures(
                        onDragStart = { start(itemKey) },
                        onDrag = { change, amount ->
                            change.consume()
                            drag(amount.y)
                        },
                        onDragEnd = { end() },
                        onDragCancel = { end() },
                    )
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .onSizeChanged { heights[key(item)] = it.height }
                        .zIndex(if (lifted) 1f else 0f)
                        .graphicsLayer {
                            if (lifted) {
                                translationY = offset
                                val scale = 1f + 0.02f * lift.value
                                scaleX = scale
                                scaleY = scale
                            }
                        }
                        .then(if (lifted) Modifier.clip(AppShape.all(AppCorner.XL * lift.value)) else Modifier)
                        .pointerInput(itemKey, enabled) {
                            if (!enabled) return@pointerInput
                            detectDragGesturesAfterLongPress(
                                onDragStart = { start(itemKey) },
                                onDrag = { change, amount ->
                                    change.consume()
                                    drag(amount.y)
                                },
                                onDragEnd = { end() },
                                onDragCancel = { end() },
                            )
                        },
                ) {
                    CompositionLocalProvider(LocalSortLift provides if (lifted) lift.value else 0f, LocalSortDrag provides rowDrag) {
                        itemContent(index, item)
                    }
                }
            }
        }
    }
}

/** sortable.dart's SortHandle: a 48 dp target whose circle tints while the row is lifted. */
@Composable
fun SortHandle() {
    val scheme = BoxTheme.scheme
    val lifted = LocalSortLift.current > 0f
    Box(Modifier.size(48.dp).then(LocalSortDrag.current), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(40.dp)
                .background(scheme.onSurface.copy(alpha = if (lifted) 0.16f else 0f), androidx.compose.foundation.shape.CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            GlyphIcon(AppGlyphs.dragHandle, color = if (lifted) scheme.primary else scheme.onSurfaceVariant)
        }
    }
}
