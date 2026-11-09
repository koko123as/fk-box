package io.nekohasekai.sfa.compose.ui

import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt

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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.nekohasekai.sfa.compose.theme.BoxTheme

/*
 * A reorderable column: a long press on a row, or a drag on its SortHandle,
 * picks the row up; it follows the finger and swaps places with a neighbour
 * once it passes half of it.
 */

/** True for the row being dragged. */
val LocalSortLift = staticCompositionLocalOf { false }

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
        offset = 0f
        dragging = -1
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
                        .offset { IntOffset(0, if (lifted) offset.roundToInt() else 0) }
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
                    CompositionLocalProvider(LocalSortLift provides lifted, LocalSortDrag provides rowDrag) {
                        itemContent(index, item)
                    }
                }
            }
        }
    }
}

/** A 48 dp drag target; its icon takes the primary colour while the row is dragged. */
@Composable
fun SortHandle() {
    val scheme = BoxTheme.scheme
    val lifted = LocalSortLift.current
    Box(Modifier.size(48.dp).then(LocalSortDrag.current), contentAlignment = Alignment.Center) {
        AppIcon(AppIcons.dragHandle, color = if (lifted) scheme.primary else scheme.onSurfaceVariant)
    }
}
