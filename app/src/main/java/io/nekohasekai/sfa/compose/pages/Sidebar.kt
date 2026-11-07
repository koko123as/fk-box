package io.nekohasekai.sfa.compose.pages

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.nekohasekai.sfa.compose.theme.AppShape
import io.nekohasekai.sfa.compose.theme.forClip
import io.nekohasekai.sfa.compose.theme.AppTextStyle
import io.nekohasekai.sfa.compose.theme.BoxTheme
import io.nekohasekai.sfa.compose.ui.AnimatedGlyph
import io.nekohasekai.sfa.compose.ui.Glyph
import io.nekohasekai.sfa.compose.ui.GlyphBox
import io.nekohasekai.sfa.compose.ui.GlyphIcon
import io.nekohasekai.sfa.compose.ui.GlyphPolyline
import io.nekohasekai.sfa.compose.ui.GlyphRole
import io.nekohasekai.sfa.compose.ui.GlyphVertex
import io.nekohasekai.sfa.compose.ui.semanticsLabel
import io.nekohasekai.sfa.compose.ui.tr
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/*
 * FlClash be84736's manager/app_manager.dart (_Sidebar, AppSidebarContainer,
 * sideWidthProvider), common/layout.dart (getViewMode, canExpandSidebar) and
 * prism_ui 63450c0's sidebar.dart (NavigationSidebar), GPL-3.0. Phones keep
 * the dock or bottom bar; wider windows get the sidebar, which widens in place
 * with labels on a desktop-wide window and opens over the content otherwise.
 */

/** constant.dart's maxMobileWidth and maxLaptopWidth. */
const val MAX_MOBILE_WIDTH = 600f
const val MAX_LAPTOP_WIDTH = 840f

enum class ViewMode { Mobile, Laptop, Desktop }

fun viewModeOf(width: Float): ViewMode = when {
    width <= MAX_MOBILE_WIDTH -> ViewMode.Mobile
    width <= MAX_LAPTOP_WIDTH -> ViewMode.Laptop
    else -> ViewMode.Desktop
}

/** An expanded sidebar in the laptop range would leave the content narrower than the mobile layout, so labels wait for desktop. */
fun canExpandSidebar(mode: ViewMode): Boolean = mode == ViewMode.Desktop

/** viewSizeProvider and sideWidthProvider: the window and the room the sidebar takes from it, in dp. */
val LocalViewWidth = compositionLocalOf { 0f }
val LocalViewHeight = compositionLocalOf { 0f }
val LocalSideWidth = compositionLocalOf { 0f }

private const val COMPACT_WIDTH = 48f
private const val EXPANDED_WIDTH = 220f
private const val ITEM_SIZE = 40f
private const val ITEM_GAP = 4f
private const val TOGGLE_GAP = 8f
private const val VERTICAL_INSET = 4f
private const val MAX_SIDE_INSET = 12f
private const val ICON_SIZE = 24f
private const val LABEL_GAP = 12f
private const val INDICATOR_WIDTH = 3f
private const val INDICATOR_HEIGHT = 16f
private const val EXPAND_MS = 250
private const val INDICATOR_MS = 320
private const val OVERLAY_FADE_MS = 50

/** Easing.standard. */
private val StandardEasing = CubicBezierEasing(.2f, 0f, 0f, 1f)
private val EaseOutCubicCurve = CubicBezierEasing(.215f, .61f, .355f, 1f)
private val EaseInOutCubicCurve = CubicBezierEasing(.645f, .045f, .355f, 1f)

/** Glyphs.sidebar(progress): the panel's divider slides out as the sidebar widens. */
fun sidebarGlyph(progress: Float): Glyph {
    val divider = 8.5 + (11.5 - 8.5) * progress
    return Glyph(
        buildList {
            add(GlyphBox(3.0, 4.5, 21.0, 19.5, 3.5))
            add(GlyphPolyline(listOf(GlyphVertex(divider, 4.5), GlyphVertex(divider, 19.5)), role = GlyphRole.Detail))
            for (y in listOf(8.5, 11.0, 13.5)) {
                add(GlyphPolyline(listOf(GlyphVertex(5.5, y), GlyphVertex(divider - 2.5, y)), role = GlyphRole.Detail))
            }
        },
        matchTextDirection = true,
        key = "sidebar" to progress.toDouble(),
        cacheable = progress == 0f || progress == 1f,
    )
}

class SidebarDestination(val glyph: Glyph, val label: String)

/** colorScheme.chromeSurface. */
@Composable
fun chromeSurface(): Color = BoxTheme.scheme.let { if (it.dark) it.surfaceContainerLow else it.surfaceContainerHighest }

/**
 * NavigationSidebar. Where it cannot widen in place, [onToggle] is null and
 * the toggle opens the expanded pane over the content through [overlay].
 */
@Composable
fun NavigationSidebar(
    destinations: List<SidebarDestination>,
    selectedIndex: Int,
    expanded: Boolean,
    onSelected: (Int) -> Unit,
    onToggle: (() -> Unit)?,
    overlay: SidebarOverlayState,
    modifier: Modifier = Modifier,
) {
    val progress by animateFloatAsState(if (expanded) 1f else 0f, tween(EXPAND_MS, easing = StandardEasing), label = "sidebar")
    // A window that can widen the sidebar again drops the pane opened over the content.
    LaunchedEffect(onToggle != null) { if (onToggle != null) overlay.snapClosed() }
    SidebarPane(
        progress = progress,
        expanded = expanded,
        destinations = destinations,
        selectedIndex = selectedIndex,
        onSelected = onSelected,
        onToggle = { if (onToggle != null) onToggle() else overlay.toggle() },
        modifier = modifier.windowInsetsPadding(WindowInsets.safeDrawing),
    )
}

/** The pane opened over the content on a laptop-wide window: OverlayPortal's _SidebarOverlay. */
class SidebarOverlayState(private val scope: kotlinx.coroutines.CoroutineScope) {
    val progress = Animatable(0f)
    val showing: Boolean get() = progress.value > 0f || progress.isRunning && progress.targetValue > 0f

    fun toggle() {
        if (progress.targetValue > 0f) close() else scope.launch { progress.animateTo(1f, tween(EXPAND_MS, easing = StandardEasing)) }
    }

    fun close() {
        scope.launch { progress.animateTo(0f, tween(EXPAND_MS, easing = StandardEasing)) }
    }

    suspend fun snapClosed() = progress.snapTo(0f)
}

@Composable
fun rememberSidebarOverlay(): SidebarOverlayState {
    val scope = rememberCoroutineScope()
    return remember { SidebarOverlayState(scope) }
}

/** The opened pane and the scrim that dismisses it, laid over the content. */
@Composable
fun SidebarOverlay(
    state: SidebarOverlayState,
    destinations: List<SidebarDestination>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
) {
    if (!state.showing) return
    val scheme = BoxTheme.scheme
    Box(Modifier.fillMaxSize().zIndex(10f)) {
        Box(
            Modifier
                .fillMaxSize()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { state.close() },
        )
        SidebarPane(
            progress = state.progress.value,
            expanded = true,
            destinations = destinations,
            selectedIndex = selectedIndex,
            onSelected = {
                onSelected(it)
                state.close()
            },
            onToggle = { state.close() },
            modifier = Modifier
                .fillMaxHeight()
                .shadow(8.dp, androidx.compose.ui.graphics.RectangleShape, clip = false, ambientColor = scheme.shadow, spotColor = scheme.shadow)
                .background(chromeSurface())
                .sidebarDivider()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        )
    }
}

/** The end hairline Surface draws with BorderDirectional(end: Divider.createBorderSide). */
@Composable
fun Modifier.sidebarDivider(): Modifier {
    val color = BoxTheme.scheme.onSurface.copy(alpha = .14f)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    return this.drawBehind {
        val x = if (rtl) 0f else size.width - .5f
        drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
    }
}

@Composable
private fun SidebarPane(
    progress: Float,
    expanded: Boolean,
    destinations: List<SidebarDestination>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = BoxTheme.scheme
    val fontSize = AppTextStyle.Subheadline.size
    val fontScale = LocalDensity.current.fontScale
    val labelOpacity = ((progress - .4f) / .6f).coerceIn(0f, 1f)
    val compactWidth = COMPACT_WIDTH
    val sideInset = min(MAX_SIDE_INSET, (compactWidth - ITEM_SIZE) / 2)
    val itemWidth = compactWidth - sideInset * 2
    val iconInset = (itemWidth - ICON_SIZE) / 2
    val itemHeight = ITEM_SIZE + fontSize * fontScale - fontSize
    val rowWidth = EXPANDED_WIDTH - sideInset * 2
    val labelStyle = AppTextStyle.Subheadline.of().copy(color = scheme.onSurface)
    Column(modifier.width((compactWidth + (EXPANDED_WIDTH - compactWidth) * progress).dp)) {
        Spacer(Modifier.height(VERTICAL_INSET.dp))
        SidebarButton(
            height = itemHeight,
            sideInset = sideInset,
            selected = null,
            label = tr(if (expanded) "Collapse" else "Expand"),
            onTap = onToggle,
            modifier = Modifier.wrapContentWidth(Alignment.Start),
        ) {
            Box(Modifier.width(itemWidth.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
                GlyphIcon(sidebarGlyph(progress), size = ICON_SIZE.dp, color = scheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(TOGGLE_GAP.dp))
        Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            Column(Modifier.fillMaxWidth()) {
                destinations.forEachIndexed { index, destination ->
                    val selected = index == selectedIndex
                    SidebarButton(
                        height = itemHeight,
                        sideInset = sideInset,
                        selected = selected,
                        label = destination.label,
                        onTap = { onSelected(index) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        // The row lays out at the expanded width whatever the pane's width, so the label clips rather than reflows.
                        Box(Modifier.fillMaxSize().clipToBounds()) {
                            Row(
                                Modifier.unboundedWidth(rowWidth).fillMaxHeight(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Spacer(Modifier.width(iconInset.dp))
                                AnimatedGlyph(destination.glyph, filled = selected, size = ICON_SIZE.dp, color = scheme.onSurfaceVariant)
                                Spacer(Modifier.width(LABEL_GAP.dp))
                                Text(
                                    destination.label,
                                    Modifier.weight(1f).graphicsLayer { alpha = labelOpacity },
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                    style = labelStyle,
                                )
                                Spacer(Modifier.width(LABEL_GAP.dp))
                            }
                        }
                    }
                }
            }
            if (selectedIndex in destinations.indices) {
                SelectionIndicator(selectedIndex, sideInset, itemHeight + ITEM_GAP, scheme.primary)
            }
        }
        Spacer(Modifier.height(VERTICAL_INSET.dp))
    }
}

/** Lays the child out at [width] dp from the start edge, whatever room it was given. */
private fun Modifier.unboundedWidth(width: Float): Modifier = this.layout { measurable, constraints ->
    val px = width.dp.roundToPx()
    val placeable = measurable.measure(Constraints(minWidth = px, maxWidth = px, minHeight = constraints.minHeight, maxHeight = constraints.maxHeight))
    val shown = if (constraints.hasBoundedWidth) min(px, constraints.maxWidth) else px
    layout(shown, placeable.height) { placeable.placeRelative(0, 0) }
}

/** _SidebarButton: a 10 dp rounded row, filled while selected and shaded while pressed (PressRegion's overlay). */
@Composable
private fun SidebarButton(
    height: Float,
    sideInset: Float,
    selected: Boolean?,
    label: String,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val scheme = BoxTheme.scheme
    val shape = AppShape.all(10f)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val overlay by animateFloatAsState(if (pressed) .06f else 0f, tween(OVERLAY_FADE_MS, easing = LinearEasing), label = "sidebarPress")
    Box(modifier.padding(horizontal = sideInset.dp, vertical = (ITEM_GAP / 2).dp)) {
        Box(
            Modifier
                .height(height.dp)
                .background(if (selected == true) scheme.onSurface.copy(alpha = .08f) else Color.Transparent, shape)
                // The press overlay is drawn in the squircle itself, as the clip is a native
                // rounded rectangle just inside it (Shape.forClip).
                .drawWithCache {
                    val outline = shape.createOutline(size, layoutDirection, this)
                    onDrawBehind { if (overlay > 0f) drawOutline(outline, scheme.onSurface.copy(alpha = overlay)) }
                }
                .clip(shape.forClip())
                .clickable(interactionSource = interaction, indication = null, onClick = onTap)
                .semanticsLabel(label),
        ) { content() }
    }
}

/** _SelectionIndicator: stretches toward the new row before its trailing edge catches up. */
@Composable
private fun SelectionIndicator(index: Int, start: Float, stride: Float, color: Color) {
    val motion = remember { Animatable(1f) }
    val from = remember { intArrayOf(index, index) }
    LaunchedEffect(index) {
        if (from[1] != index) {
            from[0] = from[1]
            from[1] = index
            motion.snapTo(0f)
            motion.animateTo(1f, tween(INDICATOR_MS, easing = LinearEasing))
        }
    }
    fun topOf(i: Int): Float = i * stride + (stride - INDICATOR_HEIGHT) / 2
    val t = motion.value
    val a = topOf(from[0])
    val b = topOf(index)
    val lead = EaseOutCubicCurve.transform(min(1f, t / .6f))
    val trail = EaseInOutCubicCurve.transform(max(0f, (t - .35f) / .65f))
    val (top, bottom) = if (b >= a) (a + (b - a) * trail) to (a + (b - a) * lead) else (a + (b - a) * lead) to (a + (b - a) * trail)
    Box(
        Modifier
            .offset(x = start.dp, y = top.dp)
            .size(INDICATOR_WIDTH.dp, (bottom - top + INDICATOR_HEIGHT).dp)
            .background(color, AppShape.full),
    )
}
