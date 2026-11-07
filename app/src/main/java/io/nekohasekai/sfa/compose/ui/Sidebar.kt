package io.nekohasekai.sfa.compose.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.nekohasekai.sfa.compose.theme.AppTextStyle
import io.nekohasekai.sfa.compose.theme.BoxScheme
import io.nekohasekai.sfa.compose.theme.BoxTheme
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/*
 * prism_ui 63450c0 lib/sidebar.dart's NavigationSidebar (GPL-3.0) and FlClash
 * v0.9.3 lib/manager/app_manager.dart's AppSidebarContainer: a 48 dp rail of
 * glyphs that widens to 220 dp with labels, on the chrome surface with an end
 * hairline. Where it cannot widen in place (laptop widths) the toggle opens
 * the expanded pane over the content instead.
 */

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
private const val EXPAND_DURATION = 250
private const val INDICATOR_DURATION = 320
private val ItemShape = RoundedCornerShape(10.dp)

/** Material's Easing.standard. */
private val StandardEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
private val EaseOutCubic = CubicBezierEasing(0.215f, 0.61f, 0.355f, 1f)
private val EaseInOutCubic = CubicBezierEasing(0.645f, 0.045f, 0.355f, 1f)

class SidebarDestination(val glyph: Glyph, val label: String)

/** surface.dart's chromeSurface. */
val BoxScheme.chromeSurface: Color get() = if (dark) surfaceContainerLow else surfaceContainerHighest

/** glyphs.dart's Glyphs.sidebar: the divider slides out as the pane widens. */
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
    )
}

/**
 * The sidebar beside [content]. [onToggle] widens the pane in place; when it
 * is null the toggle opens the expanded pane over the content.
 */
@Composable
fun SidebarLayout(
    destinations: List<SidebarDestination>,
    selectedIndex: Int,
    expanded: Boolean,
    onSelected: (Int) -> Unit,
    onToggle: (() -> Unit)?,
    content: @Composable () -> Unit,
) {
    val scheme = BoxTheme.scheme
    val overlay = remember { Animatable(0f) }
    var overlayShown by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val closeOverlay: () -> Unit = {
        scope.launch {
            overlay.animateTo(0f, tween(EXPAND_DURATION, easing = StandardEasing))
            if (overlay.value == 0f) overlayShown = false
        }
    }
    LaunchedEffect(onToggle != null) {
        if (onToggle != null && overlayShown) {
            overlay.snapTo(0f)
            overlayShown = false
        }
    }
    val toggle: () -> Unit = {
        when {
            onToggle != null -> onToggle()
            overlayShown && overlay.targetValue == 1f -> closeOverlay()
            else -> {
                overlayShown = true
                scope.launch { overlay.animateTo(1f, tween(EXPAND_DURATION, easing = StandardEasing)) }
            }
        }
    }
    val progress by animateFloatAsState(if (expanded) 1f else 0f, tween(EXPAND_DURATION, easing = StandardEasing), label = "sidebar")
    Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxHeight().background(scheme.chromeSurface).endHairline(scheme)) {
                SidebarPane(progress, expanded, destinations, selectedIndex, onSelected, toggle)
            }
            Box(Modifier.weight(1f).fillMaxHeight().clipToBounds()) { content() }
        }
        if (overlayShown) {
            BackHandler { closeOverlay() }
            Box(
                Modifier
                    .fillMaxSize()
                    .zIndex(3f)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { closeOverlay() },
            )
            Box(
                Modifier
                    .zIndex(4f)
                    .fillMaxHeight()
                    .shadow(8.dp, RectangleShape, clip = false, ambientColor = scheme.shadow, spotColor = scheme.shadow)
                    .background(scheme.chromeSurface)
                    .endHairline(scheme)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            ) {
                SidebarPane(
                    progress = overlay.value,
                    expanded = true,
                    destinations = destinations,
                    selectedIndex = selectedIndex,
                    onSelected = { index ->
                        onSelected(index)
                        closeOverlay()
                    },
                    onToggle = closeOverlay,
                )
            }
        }
    }
}

/** Divider.createBorderSide on the end edge: a hairline at 14% of onSurface. */
private fun Modifier.endHairline(scheme: BoxScheme): Modifier = drawBehind {
    val x = if (layoutDirection == LayoutDirection.Ltr) size.width - 0.5f else 0.5f
    drawLine(scheme.onSurface.copy(alpha = 0.14f), Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
}

@Composable
private fun SidebarPane(
    progress: Float,
    expanded: Boolean,
    destinations: List<SidebarDestination>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    onToggle: () -> Unit,
) {
    val scheme = BoxTheme.scheme
    val fontScale = LocalDensity.current.fontScale
    val fontSize = AppTextStyle.Subheadline.size
    val labelOpacity = ((progress - 0.4f) / 0.6f).coerceIn(0f, 1f)
    val sideInset = min(MAX_SIDE_INSET, (COMPACT_WIDTH - ITEM_SIZE) / 2)
    val itemWidth = COMPACT_WIDTH - sideInset * 2
    val iconInset = (itemWidth - ICON_SIZE) / 2
    val itemHeight = ITEM_SIZE + fontSize * fontScale - fontSize
    val rowWidth = EXPANDED_WIDTH - sideInset * 2
    val labelStyle = AppTextStyle.Subheadline.of().copy(color = scheme.onSurface)
    Column(
        Modifier
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start))
            .width((COMPACT_WIDTH + (EXPANDED_WIDTH - COMPACT_WIDTH) * progress).dp)
            .fillMaxHeight(),
    ) {
        Spacer(Modifier.height(VERTICAL_INSET.dp))
        SidebarButton(
            height = itemHeight,
            sideInset = sideInset,
            selected = null,
            description = tr(if (expanded) "Show less" else "Show more"),
            onTap = onToggle,
        ) {
            Box(Modifier.width(itemWidth.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
                GlyphIcon(sidebarGlyph(progress), color = scheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(TOGGLE_GAP.dp))
        Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            Column(Modifier.fillMaxWidth()) {
                destinations.forEachIndexed { index, destination ->
                    androidx.compose.runtime.key(destination.label) {
                        SidebarButton(
                            height = itemHeight,
                            sideInset = sideInset,
                            selected = index == selectedIndex,
                            description = destination.label,
                            onTap = { onSelected(index) },
                        ) {
                            DestinationRow(destination, index == selectedIndex, rowWidth, iconInset, labelStyle, labelOpacity)
                        }
                    }
                }
            }
            if (selectedIndex in destinations.indices) {
                SelectionIndicator(scheme.primary, selectedIndex, sideInset, itemHeight + ITEM_GAP)
            }
        }
        Spacer(Modifier.height(VERTICAL_INSET.dp))
    }
}

@Composable
private fun SidebarButton(
    height: Float,
    sideInset: Float,
    selected: Boolean?,
    description: String,
    onTap: () -> Unit,
    content: @Composable () -> Unit,
) {
    val scheme = BoxTheme.scheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    // PressRegion: selection fill, then the pressed (6%) or hovered (4%) overlay.
    val overlay = when {
        pressed -> scheme.onSurface.copy(alpha = 0.06f)
        hovered -> scheme.onSurface.copy(alpha = 0.04f)
        else -> Color.Transparent
    }
    Box(
        Modifier
            .padding(horizontal = sideInset.dp, vertical = (ITEM_GAP / 2).dp)
            .height(height.dp)
            .clip(ItemShape)
            .then(if (selected == true) Modifier.background(scheme.onSurface.copy(alpha = 0.08f)) else Modifier)
            .background(overlay)
            .semantics {
                contentDescription = description
                if (selected != null) this.selected = selected
            }
            .clickable(interactionSource = interaction, indication = null, role = Role.Tab, onClick = onTap),
    ) { content() }
}

/** The row lays out at the expanded width whatever the pane's width, so the label clips rather than reflows. */
@Composable
private fun DestinationRow(
    destination: SidebarDestination,
    selected: Boolean,
    width: Float,
    iconInset: Float,
    style: androidx.compose.ui.text.TextStyle,
    labelOpacity: Float,
) {
    Box(Modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.CenterStart) {
        Row(Modifier.wrapContentWidth(Alignment.Start, unbounded = true).width(width.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(iconInset.dp))
            AnimatedGlyph(destination.glyph, filled = selected, color = BoxTheme.scheme.onSurfaceVariant)
            Spacer(Modifier.width(LABEL_GAP.dp))
            Text(
                destination.label,
                Modifier.weight(1f).graphicsLayer { alpha = labelOpacity },
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                style = style,
            )
            Spacer(Modifier.width(LABEL_GAP.dp))
        }
    }
}

/** The indicator stretches toward the new row before its trailing edge catches up. */
@Composable
private fun SelectionIndicator(color: Color, index: Int, start: Float, stride: Float) {
    val motion = remember { Animatable(1f) }
    var from by remember { mutableIntStateOf(index) }
    var to by remember { mutableIntStateOf(index) }
    LaunchedEffect(index) {
        if (index != to) {
            from = to
            to = index
            motion.snapTo(0f)
            motion.animateTo(1f, tween(INDICATOR_DURATION, easing = androidx.compose.animation.core.LinearEasing))
        }
    }
    fun topOf(i: Int) = i * stride + (stride - INDICATOR_HEIGHT) / 2
    val t = motion.value
    val a = topOf(from)
    val b = topOf(to)
    val lead = EaseOutCubic.transform(min(1f, t / 0.6f))
    val trail = EaseInOutCubic.transform(max(0f, (t - 0.35f) / 0.65f))
    val (top, bottom) = if (b >= a) {
        (a + (b - a) * trail) to (a + (b - a) * lead)
    } else {
        (a + (b - a) * lead) to (a + (b - a) * trail)
    }
    Box(
        Modifier
            .offset(x = start.dp, y = top.dp)
            .size(INDICATOR_WIDTH.dp, (bottom - top + INDICATOR_HEIGHT).dp)
            .clip(RoundedCornerShape(50))
            .background(color),
    )
}
