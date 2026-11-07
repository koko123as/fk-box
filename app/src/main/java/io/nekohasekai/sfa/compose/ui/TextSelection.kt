package io.nekohasekai.sfa.compose.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.MutatorMutex
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuItem
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuSession
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuProvider
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.compose.theme.AppCorner
import io.nekohasekai.sfa.compose.theme.AppShape
import io.nekohasekai.sfa.compose.theme.AppTextStyle
import io.nekohasekai.sfa.compose.theme.BoxTheme
import io.nekohasekai.sfa.compose.theme.LocalSurfaceLevel
import io.nekohasekai.sfa.compose.theme.SurfaceLevel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/*
 * prism_ui 63450c0 text_selection_menu.dart + edit_menu.dart: the selection
 * menu of every text widget in place of the platform floating toolbar. By
 * touch it is iOS 27's edit menu (UIEditMenuInteraction): a capsule of labels
 * beside the selection that unfolds into a card when they do not all fit.
 * Compose's text widgets still decide when it shows; [EditMenuProvider] only
 * mirrors that session into this overlay. The platform magnifier is kept.
 */

// Measured on UIEditMenuInteraction of iOS 27 (iPhone 17 Pro).
private const val BAR_HEIGHT = 44f
private const val BAR_INSET = 4f
private const val CELL_PADDING = 16f
private const val CELL_PRESS_INSET = 2f
private const val CELL_PRESS_HEIGHT = 32f
private const val SEPARATOR_HEIGHT = 18f
private val PRESSED_DARK = Color(0x40CCCCCC)
private val PRESSED_LIGHT = Color(0x66BCBCBC)
private const val CHEVRON_SIZE = 36f
private const val MARGIN = 16f
private const val LINE_GAP = 15f
private const val CLOSED_SCALE = 0.2f
private const val CARD_WIDTH = 250f
private const val CARD_ROW_HEIGHT = 42f
private const val CARD_INSET = 8f
private const val CARD_ROW_PADDING = 12f
private const val CARD_CORNER = AppCorner.MD + CARD_INSET

/** edit_menu.dart _openCurve (stiffness 522.9, damping 38.87) over 405 ms; closes in 150 ms ease-in. */
private val openSpring = spring<Float>(dampingRatio = 0.85f, stiffness = 522.9f)
private const val CLOSE_MS = 150

/** edit_menu.dart _unfoldCurve (stiffness 300, damping 30). */
private val unfoldSpring = spring<Float>(dampingRatio = 0.866f, stiffness = 300f)

/** One open selection menu: Compose's data and the session that closes it. */
class EditMenuSession internal constructor(
    val data: TextContextMenuDataProvider,
    val session: TextContextMenuSession,
)

/** The toolbar provider Compose's SelectionContainer and text fields open their menu through. */
class EditMenuProvider : TextContextMenuProvider {
    private val mutex = MutatorMutex()
    var active: EditMenuSession? by mutableStateOf(null)
        private set

    override suspend fun showTextContextMenu(dataProvider: TextContextMenuDataProvider) {
        val closed = Channel<Unit>(Channel.CONFLATED)
        val session = object : TextContextMenuSession {
            override fun close() {
                closed.trySend(Unit)
            }
        }
        val entry = EditMenuSession(dataProvider, session)
        mutex.mutate {
            try {
                active = entry
                closed.receive()
            } finally {
                if (active === entry) active = null
            }
        }
    }

    fun close() {
        active?.session?.close()
    }
}

private class EditMenuItem(val key: Any, val glyph: Glyph?, val label: String, val onPressed: () -> Unit)

private class EditMenuState(val selection: Rect, val items: List<EditMenuItem>)

/**
 * text_selection_menu.dart's item mapping: cut, copy, paste and select all
 * with prism's labels and glyphs. Other items Compose offers (such as
 * autofill) keep their own label so nothing the platform toolbar had is lost.
 */
@Composable
private fun menuItems(session: EditMenuSession): List<EditMenuItem> {
    val cut = tr("Cut")
    val copy = tr("Copy")
    val paste = tr("Paste")
    val selectAll = tr("Select all")
    return session.data.data().components.filterIsInstance<TextContextMenuItem>().map { item ->
        val run = { item.onClick(session.session) }
        when (item.key) {
            TextContextMenuKeys.CutKey -> EditMenuItem(item.key, AppGlyphs.cut, cut, run)
            TextContextMenuKeys.CopyKey -> EditMenuItem(item.key, AppGlyphs.copy, copy, run)
            TextContextMenuKeys.PasteKey -> EditMenuItem(item.key, AppGlyphs.paste, paste, run)
            TextContextMenuKeys.SelectAllKey -> EditMenuItem(item.key, AppGlyphs.selectAll, selectAll, run)
            else -> EditMenuItem(item.key, null, item.label, run)
        }
    }
}

/**
 * Hosts the edit menu for every selectable text and text field in [content]
 * (main.dart wraps the app once). Any pointer down outside the menu closes
 * it, as edit_menu.dart's translucent Listener does.
 */
@Composable
fun EditMenuHost(content: @Composable () -> Unit) {
    val provider = remember { EditMenuProvider() }
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null, neverEqualPolicy()) }
    val menuBounds = remember { arrayOf(Rect.Zero) }
    CompositionLocalProvider(LocalTextContextMenuToolbarProvider provides provider) {
        Box(
            Modifier
                .onGloballyPositioned { coordinates = it }
                .pointerInput(provider) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.type == PointerEventType.Press && provider.active != null) {
                                val position = event.changes.firstOrNull()?.position ?: continue
                                if (!menuBounds[0].contains(position)) provider.close()
                            }
                        }
                    }
                },
            propagateMinConstraints = true,
        ) {
            content()
            EditMenuOverlay(provider, { coordinates }, menuBounds)
        }
    }
}

/** selectable_text.dart: a region whose text the user can select, with the edit menu above. */
@Composable
fun SelectionArea(content: @Composable () -> Unit) {
    SelectionContainer(content = content)
}

@Composable
private fun EditMenuOverlay(provider: EditMenuProvider, coordinates: () -> LayoutCoordinates?, menuBounds: Array<Rect>) {
    val active = provider.active
    val coords = coordinates()
    val last = remember { arrayOfNulls<EditMenuState>(1) }
    val current = if (active != null && coords != null && coords.isAttached) {
        val items = menuItems(active)
        if (items.isEmpty()) null else EditMenuState(active.data.contentBounds(coords), items)
    } else {
        null
    }
    if (current != null) last[0] = current
    val open = remember { Animatable(0f) }
    val unfold = remember { Animatable(0f) }
    val showing = current != null
    LaunchedEffect(showing) {
        if (showing) {
            open.animateTo(1f, openSpring)
        } else {
            open.animateTo(0f, tween(CLOSE_MS, easing = EaseIn))
            unfold.snapTo(0f)
        }
    }
    val keys = current?.items?.map { it.key }
    // _foldOnNewItems: a new set of items starts folded again.
    LaunchedEffect(keys) { if (keys != null) unfold.snapTo(0f) }
    val state = current ?: last[0]
    if (state == null || coords == null || (!showing && open.value <= 0f)) {
        menuBounds[0] = Rect.Zero
        return
    }
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val scheme = BoxTheme.scheme
    val measurer = rememberTextMeasurer()
    val style = AppTextStyle.Subheadline.of()
    val insets = WindowInsets.safeDrawing
    with(density) {
        val size = coords.size
        val areaWidth = size.width.toDp().value
        val areaHeight = size.height.toDp().value
        val top = insets.getTop(this).toDp().value
        val bottom = insets.getBottom(this).toDp().value
        val area = Rect(MARGIN, top, max(areaWidth - MARGIN, MARGIN), max(areaHeight - bottom, top))
        val selection = Rect(
            state.selection.left.toDp().value,
            state.selection.top.toDp().value,
            state.selection.right.toDp().value,
            state.selection.bottom.toDp().value,
        )
        val widths = state.items.map { item ->
            ceil(measurer.measure(item.label, style, maxLines = 1).size.width.toDp().value) + 2 * CELL_PADDING
        }
        val count = fittingCells(widths, area.width)
        val fits = count == widths.size
        val barWidth = if (fits) {
            widths.sum() + 2 * BAR_INSET
        } else {
            min(area.width, widths.take(count).sum() + 2 * BAR_INSET + CHEVRON_SIZE)
        }
        val topBelow = selection.bottom + LINE_GAP
        val topAbove = selection.top - LINE_GAP - BAR_HEIGHT
        val wantsBelow = selection.center.y < area.center.y
        val below = if (wantsBelow) {
            topBelow + BAR_HEIGHT <= area.bottom || topAbove < area.top
        } else {
            topAbove < area.top && topBelow + BAR_HEIGHT <= area.bottom
        }
        val barTop = clampTo(if (below) topBelow else topAbove, area.top, area.bottom - BAR_HEIGHT)
        val barLeft = clampTo(selection.center.x - barWidth / 2, area.left, area.right - barWidth)
        val cardHeight = min(state.items.size * CARD_ROW_HEIGHT + 2 * CARD_INSET, area.height)
        val cardWidth = min(CARD_WIDTH, area.width)
        val cardLeft = clampTo(selection.center.x - cardWidth / 2, area.left, area.right - cardWidth)
        val cardTop = clampTo(barTop, area.top, area.bottom - cardHeight)
        val unfolded = unfold.value
        val width = lerp(barWidth, cardWidth, unfolded)
        val height = lerp(BAR_HEIGHT, cardHeight, unfolded)
        val left = lerp(barLeft, cardLeft, unfolded)
        val menuTop = lerp(barTop, cardTop, unfolded)
        menuBounds[0] = Rect(left.dp.toPx(), menuTop.dp.toPx(), (left + width).dp.toPx(), (menuTop + height).dp.toPx())
        val shape = AppShape.all(lerp(BAR_HEIGHT / 2, CARD_CORNER, unfolded))
        val pressed = if (scheme.dark) PRESSED_DARK else PRESSED_LIGHT
        Box(Modifier.wrapContentSize(Alignment.TopStart, unbounded = true)) {
            Box(
                Modifier
                    .offset { IntOffset(left.dp.roundToPx(), menuTop.dp.roundToPx()) }
                    .requiredSize(width.dp, height.dp)
                    .graphicsLayer {
                        val scale = CLOSED_SCALE + (1 - CLOSED_SCALE) * open.value
                        scaleX = scale
                        scaleY = scale
                        alpha = open.value.coerceIn(0f, 1f)
                        transformOrigin = TransformOrigin(0.5f, if (below) 0f else 1f)
                    }
                    .boxShadows(shape, menuShadows(scheme))
                    .clip(shape)
                    .background(SurfaceLevel.floatingOf(scheme).colorOf(scheme)),
            ) {
                CompositionLocalProvider(LocalSurfaceLevel provides SurfaceLevel.floatingOf(scheme)) {
                    if (unfolded < 1f) {
                        Box(
                            Modifier
                                .wrapContentSize(Alignment.TopStart, unbounded = true)
                                .requiredSize(barWidth.dp, BAR_HEIGHT.dp)
                                .graphicsLayer { alpha = (1 - unfolded).coerceIn(0f, 1f) },
                        ) {
                            EditMenuBar(state.items, widths, count, pressed) { scope.launch { unfold.animateTo(1f, unfoldSpring) } }
                        }
                    }
                    if (unfolded > 0f) {
                        Box(
                            Modifier
                                .wrapContentSize(Alignment.TopStart, unbounded = true)
                                .requiredSize(cardWidth.dp, cardHeight.dp)
                                .graphicsLayer { alpha = unfolded.coerceIn(0f, 1f) },
                        ) {
                            EditMenuCard(state.items, pressed)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditMenuBar(items: List<EditMenuItem>, widths: List<Float>, count: Int, pressed: Color, onMore: () -> Unit) {
    val scheme = BoxTheme.scheme
    val style = AppTextStyle.Subheadline.of().copy(color = scheme.onSurface)
    Row(Modifier.fillMaxHeight().padding(horizontal = BAR_INSET.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until count) {
            if (i > 0) {
                Box(Modifier.width(0.dp).wrapContentSize(unbounded = true)) {
                    Box(Modifier.size(1.dp, SEPARATOR_HEIGHT.dp).background(scheme.outlineVariant))
                }
            }
            val item = items[i]
            // The highlight is smaller than the cell, the target is not.
            val interaction = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .width(widths[i].dp)
                    .fillMaxHeight()
                    .clickable(interactionSource = interaction, indication = null, onClick = item.onPressed),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .padding(horizontal = CELL_PRESS_INSET.dp)
                        .fillMaxWidth()
                        .height(CELL_PRESS_HEIGHT.dp)
                        .then(Modifier.pressRegionOverlay(interaction, AppShape.full, pressed)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(item.label, maxLines = 1, style = style)
                }
            }
        }
        if (count < items.size) {
            Spacer(Modifier.weight(1f))
            val interaction = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .size(CHEVRON_SIZE.dp)
                    .clip(AppShape.full)
                    .background(scheme.onSurface.copy(alpha = 0.05f))
                    .clickable(interactionSource = interaction, indication = PressRegionIndication(AppShape.full, pressed = pressed), onClick = onMore)
                    .semanticsLabel(tr("More")),
                contentAlignment = Alignment.Center,
            ) {
                GlyphIcon(AppGlyphs.chevronForward, size = 18.dp, color = scheme.onSurface)
            }
        }
    }
}

@Composable
private fun EditMenuCard(items: List<EditMenuItem>, pressed: Color) {
    val scheme = BoxTheme.scheme
    val style = AppTextStyle.Body.of().copy(color = scheme.onSurface)
    Column(Modifier.padding(CARD_INSET.dp)) {
        for (item in items) {
            val interaction = remember { MutableInteractionSource() }
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(CARD_ROW_HEIGHT.dp)
                    .clip(AppShape.md)
                    .clickable(interactionSource = interaction, indication = PressRegionIndication(AppShape.md, pressed = pressed), onClick = item.onPressed)
                    .padding(horizontal = CARD_ROW_PADDING.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (item.glyph != null) {
                    GlyphIcon(item.glyph, size = 20.dp, color = scheme.onSurface)
                    Spacer(Modifier.width(12.dp))
                }
                Text(item.label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = style)
            }
        }
    }
}

/** The pressed fill of a cell drawn inside its own inset highlight. */
private fun Modifier.pressRegionOverlay(interaction: MutableInteractionSource, shape: androidx.compose.ui.graphics.Shape, pressed: Color): Modifier =
    this.indication(interaction, PressRegionIndication(shape, pressed = pressed))

private fun fittingCells(widths: List<Float>, maxWidth: Float): Int {
    val total = widths.sum() + 2 * BAR_INSET
    if (total <= maxWidth) return widths.size
    var used = 2 * BAR_INSET + CHEVRON_SIZE
    var count = 0
    for (width in widths) {
        if (used + width > maxWidth) break
        used += width
        count++
    }
    return max(count, 1)
}

private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

private fun clampTo(value: Float, low: Float, high: Float): Float = max(low, min(value, max(low, high)))
