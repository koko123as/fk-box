package io.nekohasekai.sfa.compose.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.compose.theme.AppCorner
import io.nekohasekai.sfa.compose.theme.AppShape
import io.nekohasekai.sfa.compose.theme.AppTextStyle
import io.nekohasekai.sfa.compose.theme.BoxTheme
import io.nekohasekai.sfa.compose.theme.LocalSurfaceLevel
import io.nekohasekai.sfa.compose.theme.SurfaceLevel
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/*
 * popup.dart's CommonPopupBox / CommonPopupMenu and context_menu.dart's
 * ContextMenuRegion: a card of rows 16 dp round inside an 8 dp inset (24 dp
 * outer corners), 160–280 dp wide, on the floating surface with the menu
 * shadow, placed over the anchor's end (or below a long-pressed point clear
 * of its row) 16 dp from the screen edges. It opens on the measured iOS 27
 * spring (stiffness 400, ratio 0.754) out of its anchor and fades back into
 * it; a sub menu stacks its own card at its item and the cards under it
 * recede by 5% and dim by 20% a level.
 */

enum class PopupPlacement { OverAnchorEnd, OverAnchorStart, BelowPoint }

class PopupMenuSection(val title: String? = null)

class PopupMenuItem(
    val label: String,
    val glyph: Glyph? = null,
    val subtitle: String? = null,
    val onPressed: (() -> Unit)? = null,
    val danger: Boolean = false,
    val checked: Boolean? = null,
    val section: PopupMenuSection? = null,
    val subItems: List<PopupMenuItem> = emptyList(),
)

private const val SCREEN_MARGIN = 16f
private const val ANCHOR_OVERLAP = 8f
private const val AVOID_GAP = 8f
private const val CARD_INSET = 8f
private const val ITEM_RADIUS = AppCorner.MD
private const val CARD_RADIUS = ITEM_RADIUS + CARD_INSET
private const val ITEM_ICON_SIZE = 20f
private const val SUBMENU_ARROW_SIZE = 16f
private const val MENU_MIN_WIDTH = 160f
private const val MENU_MAX_WIDTH = 280f
private const val DEFAULT_MAX_VISIBLE_ITEMS = 7
private const val LEVEL_SCALE_STEP = 0.05f
private const val LEVEL_FADE_STEP = 0.2f
private const val DISMISS_SCALE = 0.8f

private val openSpring = spring<Float>(dampingRatio = 0.754f, stiffness = 400f)
private val unfoldSpring = spring<Float>(dampingRatio = 0.85f, stiffness = 224f)

@Stable
internal class PopupRequest(
    val anchor: Rect,
    val placement: PopupPlacement,
    val avoid: Rect?,
    val items: List<PopupMenuItem>,
    val maxVisibleItems: Int?,
) {
    val presentation = Animatable(0f)
    var closing by mutableStateOf(false)
}

@Stable
class PopupHost {
    internal val requests = mutableStateListOf<PopupRequest>()

    internal fun show(anchor: Rect, placement: PopupPlacement, items: List<PopupMenuItem>, avoid: Rect? = null, maxVisibleItems: Int? = null) {
        if (items.isEmpty()) return
        requests.add(PopupRequest(anchor, placement, avoid, items, maxVisibleItems))
    }
}

val LocalPopups = staticCompositionLocalOf<PopupHost?> { null }


/** popup.dart's CommonPopupBox: [target] receives the opener. */
@Composable
fun CommonPopupBox(
    items: List<PopupMenuItem>,
    placement: PopupPlacement = PopupPlacement.OverAnchorEnd,
    maxVisibleItems: Int? = null,
    modifier: Modifier = Modifier,
    target: @Composable (open: () -> Unit) -> Unit,
) {
    val host = LocalPopups.current
    var bounds by remember { mutableStateOf(Rect.Zero) }
    Box(modifier.onGloballyPositioned { bounds = it.boundsInRoot() }) {
        target { host?.show(bounds, placement, items, maxVisibleItems = maxVisibleItems) }
    }
}

/**
 * context_menu.dart's ContextMenuRegion: a long press (or a secondary click)
 * opens [items] below the point, clear of the row it came from.
 */
@Composable
fun ContextMenuRegion(items: List<PopupMenuItem>?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val host = LocalPopups.current
    val haptics = LocalHapticFeedback.current
    var bounds by remember { mutableStateOf(Rect.Zero) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var lastDown by remember { mutableStateOf(Offset.Zero) }
    val enabled = !items.isNullOrEmpty() && !LocalSorting.current
    val open: (() -> Unit)? = if (!enabled) {
        null
    } else {
        {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            val point = origin + lastDown
            host?.show(Rect(point, point), PopupPlacement.BelowPoint, items!!, avoid = bounds)
        }
    }
    Box(
        modifier
            .onGloballyPositioned {
                bounds = it.boundsInRoot()
                origin = it.positionInRoot()
            }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.firstOrNull()?.let { if (it.pressed && !it.previousPressed) lastDown = it.position }
                    }
                }
            },
    ) {
        CompositionLocalProvider(LocalContextMenu provides open) { content() }
    }
}

/** The opener of the enclosing ContextMenuRegion, which a CommonCard takes as its long press. */
val LocalContextMenu = staticCompositionLocalOf<(() -> Unit)?> { null }

/** SortModeScope / SortableItem.isSorting: a menu stays shut while the list sorts. */
val LocalSorting = staticCompositionLocalOf { false }

/** BoxMenu: the trailing more button of a row, opening [items]. */
@Composable
fun BoxMenu(items: List<PopupMenuItem>) {
    CommonPopupBox(items) { open -> GlyphButton(AppGlyphs.more, tr("More"), open) }
}

/** The layer every popup draws in, over the routes and under nothing. */
@Composable
internal fun PopupLayer(host: PopupHost) {
    val request = host.requests.lastOrNull() ?: return
    androidx.compose.runtime.key(request) { PopupOverlay(host, request) }
}

@Composable
private fun PopupOverlay(host: PopupHost, request: PopupRequest) {
    val scope = rememberCoroutineScope()
    val levels = remember { mutableStateListOf(MenuLevel(request.items, null, 0f)) }
    val close: (after: (() -> Unit)?) -> Unit = { after ->
        if (!request.closing) {
            request.closing = true
            scope.launch {
                request.presentation.animateTo(0f, tween(200))
                host.requests.remove(request)
                after?.invoke()
            }
        }
    }
    LaunchedEffect(request) { request.presentation.animateTo(1f, openSpring) }
    BackHandler {
        if (levels.size > 1) levels.removeAt(levels.lastIndex) else close(null)
    }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Box(
        Modifier
            .fillMaxSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { close(null) },
    ) {
        Layout(
            content = {
                MenuStack(
                    levels = levels,
                    maxVisibleItems = request.maxVisibleItems,
                    onPick = { action -> close(action) },
                    onPush = { item, itemTop -> levels.add(MenuLevel(item.subItems, item, itemTop)) },
                    onPop = { if (levels.size > 1) levels.removeAt(levels.lastIndex) },
                    presentation = request.presentation.value,
                    closing = request.closing,
                    placement = request.placement,
                )
            },
        ) { measurables, constraints ->
            val margin = SCREEN_MARGIN.dp.toPx()
            val insetTop = top.toPx() + margin
            val insetBottom = bottom.toPx() + margin
            val maxWidth = max(0, constraints.maxWidth - (2 * margin).toInt())
            val maxHeight = max(0, constraints.maxHeight - (insetTop + insetBottom).toInt())
            val placeable = measurables.first().measure(Constraints(0, maxWidth, 0, maxHeight))
            val anchor = request.anchor
            val overlap = ANCHOR_OVERLAP.dp.toPx()
            val maxX = constraints.maxWidth - margin - placeable.width
            val maxY = constraints.maxHeight - insetBottom - placeable.height
            val (x, y) = when (request.placement) {
                PopupPlacement.OverAnchorEnd -> anchor.right - placeable.width to anchor.top - overlap
                PopupPlacement.OverAnchorStart -> anchor.left to anchor.top - overlap
                PopupPlacement.BelowPoint -> anchor.left to yBelowPoint(request, placeable.height.toFloat(), insetTop, maxY, AVOID_GAP.dp.toPx())
            }
            val minY = if (request.placement == PopupPlacement.BelowPoint) insetTop else max(top.toPx(), min(insetTop, y))
            val px = x.coerceIn(margin, max(margin, maxX))
            val py = y.coerceIn(minY, max(minY, maxY))
            layout(constraints.maxWidth, constraints.maxHeight) {
                placeable.place(px.toInt(), py.toInt())
            }
        }
    }
}

private fun yBelowPoint(request: PopupRequest, height: Float, minY: Float, maxY: Float, gap: Float): Float {
    val avoid = request.avoid
    val anchor = request.anchor
    if (avoid != null) {
        val above = avoid.top - gap - height
        if (above >= minY) return above
        val below = avoid.bottom + gap
        if (below <= maxY) return below
    }
    if (anchor.bottom > maxY && anchor.top - height >= minY) return anchor.top - height
    return anchor.bottom
}

private class MenuLevel(val items: List<PopupMenuItem>, val owner: PopupMenuItem?, val top: Float) {
    val unfold = Animatable(if (owner == null) 1f else 0f)
}

@Composable
private fun MenuStack(
    levels: List<MenuLevel>,
    maxVisibleItems: Int?,
    onPick: (() -> Unit) -> Unit,
    onPush: (PopupMenuItem, Float) -> Unit,
    onPop: () -> Unit,
    presentation: Float,
    closing: Boolean,
    placement: PopupPlacement,
) {
    val scheme = BoxTheme.scheme
    val origin = when (placement) {
        PopupPlacement.OverAnchorEnd -> TransformOrigin(1f, 0f)
        PopupPlacement.OverAnchorStart, PopupPlacement.BelowPoint -> TransformOrigin(0f, 0f)
    }
    Box(
        Modifier.graphicsLayer {
            val shown = presentation
            val scale = if (closing) DISMISS_SCALE + (1 - DISMISS_SCALE) * shown else 0.3f + 0.7f * shown
            scaleX = scale
            scaleY = scale
            alpha = shown.coerceIn(0f, 1f)
            transformOrigin = origin
        },
        contentAlignment = Alignment.TopEnd,
    ) {
        val topIndex = levels.lastIndex
        val glassMenu = LocalLiquidGlass.current.shaded && LocalGlassBackdrop.current != null
        val menuGlass = remember(scheme) { LiquidGlassStyle.menu(scheme) }
        levels.forEachIndexed { index, level ->
            androidx.compose.runtime.key(level) {
                LaunchedEffect(level) { if (level.owner != null) level.unfold.animateTo(1f, unfoldSpring) }
                val depth = topIndex - index
                val receding = depth > 0
                val distance = if (receding) depth - 1 + (levels.getOrNull(index + 1)?.unfold?.value ?: 1f) else 0f
                val scale = max(0f, 1 - LEVEL_SCALE_STEP * distance)
                val shadowDepth = if (receding) max(1f / 6, 1 - distance / 3) else level.unfold.value.coerceIn(0f, 1f)
                Box(
                    Modifier
                        .padding(top = with(LocalDensity.current) { level.top.toDp() })
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            transformOrigin = TransformOrigin(1f, 0f)
                        }
                        .boxShadows(AppShape.all(CARD_RADIUS), menuShadows(scheme, shadowDepth))
                        .clip(AppShape.all(CARD_RADIUS))
                        .then(if (glassMenu) Modifier else Modifier.background(SurfaceLevel.floatingOf(scheme).colorOf(scheme)))
                        .then(if (receding) Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onPop() } else Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}),
                ) {
                    // On glass a menu stands on LiquidGlassStyle.menu over what it opened from.
                    if (glassMenu) LiquidGlassPane(menuGlass, Modifier.matchParentSize(), cornerRadius = CARD_RADIUS)
                    CompositionLocalProvider(LocalSurfaceLevel provides SurfaceLevel.floatingOf(scheme)) {
                        MenuCard(level, active = !receding, maxVisibleItems = maxVisibleItems, onPick = onPick, onPush = onPush, onPop = onPop)
                    }
                    if (receding) {
                        Box(Modifier.matchParentSize().background(scheme.surface.copy(alpha = min(0.4f, LEVEL_FADE_STEP * distance))))
                    }
                }
            }
        }
    }
}

/** liquid_glass.dart's menuShadows. */
internal fun menuShadows(scheme: io.nekohasekai.sfa.compose.theme.BoxScheme, depth: Float = 1f): List<BoxShadowSpec> {
    val strength = if (scheme.dark) 1.5f else 1f
    return listOf(BoxShadowSpec(scheme.shadow.copy(alpha = (0.118f * strength * depth).coerceIn(0f, 1f)), 8f, 29f, flutterRadius = true))
}

@Composable
private fun MenuCard(
    level: MenuLevel,
    active: Boolean,
    maxVisibleItems: Int?,
    onPick: (() -> Unit) -> Unit,
    onPush: (PopupMenuItem, Float) -> Unit,
    onPop: () -> Unit,
) {
    val density = LocalDensity.current
    val rowHeight = 12f + 12f + AppTextStyle.Subheadline.leading
    val visible = maxVisibleItems ?: DEFAULT_MAX_VISIBLE_ITEMS
    val maxHeight = if (level.items.size > visible) CARD_INSET * 2 + rowHeight * (visible + 0.5f) else Float.POSITIVE_INFINITY
    val width = level.unfold.value
    Column(
        Modifier
            .widthIn(min = MENU_MIN_WIDTH.dp, max = MENU_MAX_WIDTH.dp)
            .width(IntrinsicSize.Max)
            .then(if (maxHeight.isFinite() && level.owner == null) Modifier.heightIn(max = maxHeight.dp) else Modifier)
            .verticalScroll(rememberScrollState())
            .padding(CARD_INSET.dp),
    ) {
        val owner = level.owner
        if (owner != null) {
            MenuRow(owner, onTap = onPop, arrowTurns = 0.25f * width)
            Box(Modifier.graphicsLayer { alpha = width.coerceIn(0f, 1f) }) {
                Column {
                    HorizontalDivider(Modifier.padding(vertical = CARD_INSET.dp), thickness = 1.dp, color = BoxTheme.scheme.outlineVariant)
                    MenuItems(level, active, onPick, onPush, density.density)
                }
            }
        } else {
            MenuItems(level, active, onPick, onPush, density.density)
        }
    }
}

@Composable
private fun MenuItems(level: MenuLevel, active: Boolean, onPick: (() -> Unit) -> Unit, onPush: (PopupMenuItem, Float) -> Unit, densityScale: Float) {
    var cardTop by remember { mutableStateOf(0f) }
    Column(Modifier.onGloballyPositioned { cardTop = it.positionInRoot().y }) {
        level.items.forEachIndexed { index, item ->
            val section = item.section
            if (section != null) {
                if (index > 0) HorizontalDivider(Modifier.padding(vertical = CARD_INSET.dp), thickness = 1.dp, color = BoxTheme.scheme.outlineVariant)
                if (section.title != null) {
                    Text(
                        section.title,
                        Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 2.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = AppTextStyle.Footnote.of().copy(color = BoxTheme.scheme.onSurfaceVariant),
                    )
                }
            }
            var itemTop by remember { mutableStateOf(0f) }
            Box(Modifier.onGloballyPositioned { itemTop = it.positionInRoot().y }) {
                val onTap: (() -> Unit)? = when {
                    !active -> null
                    item.subItems.isNotEmpty() -> ({ onPush(item, level.top + (itemTop - cardTop)) })
                    item.onPressed != null -> ({ onPick(item.onPressed) })
                    else -> null
                }
                MenuRow(item, onTap = onTap, disabled = item.subItems.isEmpty() && item.onPressed == null)
            }
        }
    }
}

@Composable
private fun MenuRow(item: PopupMenuItem, onTap: (() -> Unit)?, arrowTurns: Float = 0f, disabled: Boolean = onTap == null) {
    val scheme = BoxTheme.scheme
    val color = if (item.danger) scheme.error else scheme.onSurface
    val foreground = if (disabled) color.copy(alpha = 0.3f) else color
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hasArrow = item.subItems.isNotEmpty() || item.checked == true
    Row(
        Modifier
            .fillMaxWidth()
            .clip(AppShape.all(ITEM_RADIUS))
            .background(if (pressed && onTap != null) scheme.onSurface.copy(alpha = 0.1f) else Color.Transparent)
            .clickable(interactionSource = interaction, indication = hoverPressIndication(AppShape.all(ITEM_RADIUS), if (item.danger) scheme.error.copy(alpha = 0.1f) else null), enabled = onTap != null) { onTap?.invoke() }
            .padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = if (hasArrow) 8.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (item.glyph != null) {
            GlyphIcon(item.glyph, size = ITEM_ICON_SIZE.dp, color = foreground)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(item.label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = AppTextStyle.Subheadline.of().copy(color = foreground))
            if (item.subtitle != null) {
                Text(
                    item.subtitle,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = AppTextStyle.Footnote.of().copy(color = if (disabled) scheme.onSurfaceVariant.copy(alpha = 0.3f) else scheme.onSurfaceVariant),
                )
            }
        }
        if (item.subItems.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            GlyphIcon(AppGlyphs.chevronForward, Modifier.graphicsLayer { rotationZ = arrowTurns * 360f }, size = SUBMENU_ARROW_SIZE.dp, color = foreground)
        } else if (item.checked == true) {
            Spacer(Modifier.width(8.dp))
            GlyphIcon(AppGlyphs.check, size = SUBMENU_ARROW_SIZE.dp, color = foreground)
        }
    }
}
