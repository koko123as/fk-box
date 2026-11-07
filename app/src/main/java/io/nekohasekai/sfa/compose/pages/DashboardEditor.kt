package io.nekohasekai.sfa.compose.pages

import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.nekohasekai.sfa.compose.theme.AppShape
import io.nekohasekai.sfa.compose.theme.AppTextStyle
import io.nekohasekai.sfa.compose.theme.BoxTheme
import io.nekohasekai.sfa.compose.theme.SurfaceLevel
import io.nekohasekai.sfa.compose.theme.tabular
import io.nekohasekai.sfa.compose.ui.AppGlyphs
import io.nekohasekai.sfa.compose.ui.BoxShadowSpec
import io.nekohasekai.sfa.compose.ui.Glyph
import io.nekohasekai.sfa.compose.ui.GlyphIcon
import io.nekohasekai.sfa.compose.ui.boxShadows
import io.nekohasekai.sfa.compose.ui.tr
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * components/dashboard_editor.dart: the in-place card editor adapted from
 * FlClash v0.9.2 dashboard/edit.dart (GPL-3.0). Cards sway while editing, a
 * long press lifts one to reorder, the minus badge removes it, and the
 * Available cards list adds it back. Every change is saved at once.
 */

private const val ROW_HEIGHT = 64
private const val ROW_GAP = 8
private const val ADDABLE_TOP = 14
private const val SWAY_LOOP_MS = 3600
private val swaysPerLoop = intArrayOf(6, 7, 5)
private const val SWAY_PHASE_STEP = 1.7f
private const val SWAY_TURN_DEGREES = .7f
private const val SWAY_BOB = 1.25f
private const val ROOM_MS = 260
private const val APPEAR_MS = 200
private const val APPEAR_SCALE = .92f
private const val LIFT_GROWTH = .03f
private const val AUTO_SCROLL_EDGE = 72
private const val AUTO_SCROLL_STEP = 14
private val EaseOut = CubicBezierEasing(0f, 0f, .58f, 1f)

private val dashboardDescriptions = mapOf(
    "Memory" to "App and core memory",
    "Intranet IP" to "Device network addresses",
    "Network speed" to "Upload and download traffic",
    "Current profile" to "Selected sing-box configuration",
    "Proxy groups" to "Groups and selected nodes",
    "Outbound mode" to "Available routing modes",
    "Connection stats" to "Live connections and recent requests",
)

/** DashboardEditorState: the edited order, with cards still animating in or out. */
class DashboardEditorState(shown: List<String>) {
    val cards = mutableStateListOf<String>().apply { addAll(shown) }
    val leaving = mutableStateListOf<String>()
    val entering = mutableStateListOf<String>()
    var saving by mutableStateOf(false)
    var dirty by mutableStateOf(false)
    internal var onChanged: suspend (List<String>) -> Boolean = { true }
    internal var onBusyChanged: (Boolean) -> Unit = {}
    internal var onDone: () -> Unit = {}

    val shown: List<String> get() = cards.filter { it !in leaving }

    suspend fun save(): Boolean {
        if (saving) return false
        saving = true
        onBusyChanged(true)
        val success = onChanged(shown)
        saving = false
        if (success) dirty = false
        onBusyChanged(false)
        return success
    }

    suspend fun finish() {
        if (saving) return
        if (dirty && !save()) return
        onDone()
    }

    fun sync(available: List<String>, shownNow: List<String>) {
        cards.removeAll { it !in available }
        leaving.removeAll { it !in available }
        entering.removeAll { it !in available }
        if (!saving && !dirty && shown != shownNow) {
            cards.clear(); cards.addAll(shownNow); leaving.clear(); entering.clear()
        }
    }

    internal fun settle(card: String) {
        entering.remove(card)
        if (leaving.remove(card)) cards.remove(card)
    }
}

@Composable
fun DashboardEditor(
    state: DashboardEditorState,
    shown: List<String>,
    available: List<String>,
    cardContent: (String) -> (@Composable () -> Unit)?,
    gap: Dp,
    onChanged: suspend (List<String>) -> Boolean,
    onBusyChanged: (Boolean) -> Unit,
    onDone: () -> Unit,
    scrollState: androidx.compose.foundation.ScrollState? = null,
    viewport: () -> androidx.compose.ui.geometry.Rect? = { null },
) {
    state.onChanged = onChanged
    state.onBusyChanged = onBusyChanged
    state.onDone = onDone
    state.sync(available, shown)
    val scope = rememberCoroutineScope()
    BackHandler { scope.launch { state.finish() } }
    val context = LocalContext.current
    val reduceMotion = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val sway = if (reduceMotion) null else rememberInfiniteTransition(label = "sway").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(SWAY_LOOP_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "sway",
    )

    fun save() { scope.launch { state.save() } }
    fun remove(card: String) {
        if (state.saving || card == "Status") return
        state.leaving.add(card); state.dirty = true; save()
    }
    fun add(card: String) {
        if (state.saving) return
        if (!state.leaving.remove(card)) { state.entering.add(card); state.cards.add(card) }
        state.dirty = true; save()
    }
    fun move(from: Int, to: Int) {
        if (state.saving || from == to) return
        state.cards.add(to, state.cards.removeAt(from))
        state.dirty = true
    }

    val heights = remember { mutableStateMapOf<String, Int>() }
    var dragging by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    val lift = remember { Animatable(0f) }
    val haptics = LocalHapticFeedback.current
    val gapPx = with(LocalDensity.current) { gap.toPx() }
    val cards by rememberUpdatedState(state.cards.toList())
    val cardCoordinates = remember { HashMap<String, androidx.compose.ui.layout.LayoutCoordinates>() }
    var pointerY by remember { mutableStateOf<Float?>(null) }
    val edgePx = with(LocalDensity.current) { AUTO_SCROLL_EDGE.dp.toPx() }
    val stepPx = with(LocalDensity.current) { AUTO_SCROLL_STEP.dp.toPx() }

    fun dragBy(delta: Float) {
        val card = dragging ?: return
        val current = cards.indexOf(card)
        if (current < 0) return
        offset += delta
        if (offset > 0 && current < cards.lastIndex) {
            val next = (heights[cards[current + 1]] ?: return) + gapPx
            if (offset > next / 2f) { move(current, current + 1); offset -= next }
        } else if (offset < 0 && current > 0) {
            val previous = (heights[cards[current - 1]] ?: return) + gapPx
            if (-offset > previous / 2f) { move(current, current - 1); offset += previous }
        }
    }
    fun dragEnd() {
        scope.launch { lift.animateTo(0f, tween(200)) }
        val settle = Animatable(offset)
        scope.launch {
            settle.animateTo(0f, tween(200)) { offset = value }
            dragging = null
        }
        if (state.dirty) save()
    }

    // A card held near the top or bottom of the page scrolls it, faster the
    // closer it is, as Flutter's reorderable list does while dragging.
    androidx.compose.runtime.LaunchedEffect(dragging, scrollState) {
        val scroll = scrollState ?: return@LaunchedEffect
        if (dragging == null) return@LaunchedEffect
        while (dragging != null) {
            androidx.compose.runtime.withFrameNanos { }
            val y = pointerY ?: continue
            val bounds = viewport() ?: continue
            val speed = when {
                y < bounds.top + edgePx -> -stepPx * ((bounds.top + edgePx - y) / edgePx).coerceIn(0f, 1f)
                y > bounds.bottom - edgePx -> stepPx * ((y - (bounds.bottom - edgePx)) / edgePx).coerceIn(0f, 1f)
                else -> 0f
            }
            if (speed == 0f) continue
            val moved = scroll.scrollBy(speed)
            if (moved != 0f) dragBy(moved)
        }
    }

    val addable = available.filter { it !in state.shown }
    Column {
        for (card in state.cards.toList()) {
            androidx.compose.runtime.key(card) {
                val visibility = remember { MutableTransitionState(card !in state.entering).apply { targetState = true } }
                val leaving = card in state.leaving
                visibility.targetState = !leaving
                androidx.compose.runtime.LaunchedEffect(visibility.isIdle, visibility.currentState) {
                    if (visibility.isIdle && (card in state.entering || (card in state.leaving && !visibility.currentState))) {
                        state.settle(card)
                    }
                }
                val lifted = dragging == card
                AnimatedVisibility(
                    visibleState = visibility,
                    modifier = Modifier.zIndex(if (lifted) 1f else 0f),
                    enter = expandVertically(tween(ROOM_MS, easing = EaseOutCubic), expandFrom = Alignment.Top) +
                        fadeIn(tween(APPEAR_MS, ROOM_MS, EaseOutCubic)) +
                        scaleIn(tween(APPEAR_MS, ROOM_MS, EaseOutCubic), initialScale = APPEAR_SCALE),
                    exit = fadeOut(tween(APPEAR_MS, easing = EaseOutCubic)) +
                        scaleOut(tween(APPEAR_MS, easing = EaseOutCubic), targetScale = APPEAR_SCALE) +
                        shrinkVertically(tween(ROOM_MS, APPEAR_MS, EaseInCubic), shrinkTowards = Alignment.Top),
                ) {
                    Box(Modifier.padding(bottom = gap)) {
                        EditableCard(
                            name = card,
                            seed = dashboardCards.indexOf(card),
                            sway = sway?.value,
                            lift = if (lifted) lift.value else 0f,
                            offset = if (lifted) offset else 0f,
                            onRemove = if (state.saving || card == "Status") null else ({ remove(card) }),
                            modifier = Modifier
                                .onSizeChanged { heights[card] = it.height }
                                .then(Modifier.onGloballyPositioned { cardCoordinates[card] = it })
                                .pointerInput(card) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            if (state.saving) return@detectDragGesturesAfterLongPress
                                            dragging = card
                                            offset = 0f
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            scope.launch { lift.animateTo(1f, tween(200)) }
                                        },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            pointerY = cardCoordinates[card]?.takeIf { it.isAttached }?.localToRoot(change.position)?.y
                                            dragBy(amount.y)
                                        },
                                        onDragEnd = { pointerY = null; dragEnd() },
                                        onDragCancel = { pointerY = null; dragEnd() },
                                    )
                                },
                        ) { cardContent(card)?.invoke() }
                    }
                }
            }
        }
        if (addable.isNotEmpty()) {
            Column(Modifier.padding(top = ADDABLE_TOP.dp)) {
                Text(
                    tr("Available cards"),
                    modifier = Modifier.padding(start = 8.dp, top = 4.dp, end = 8.dp, bottom = 10.dp),
                    style = AppTextStyle.Footnote.of(true).copy(color = BoxTheme.scheme.onSurfaceVariant),
                )
                for (card in addable) {
                    Box(Modifier.padding(bottom = ROW_GAP.dp)) {
                        AddRow(
                            name = card,
                            glyph = dashboardGlyph(card),
                            description = dashboardDescriptions[card]?.let { tr(it) },
                            onAdd = if (state.saving) null else ({ add(card) }),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EditableCard(
    name: String,
    seed: Int,
    sway: Float?,
    lift: Float,
    offset: Float,
    onRemove: (() -> Unit)?,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    val scheme = BoxTheme.scheme
    val density = LocalDensity.current
    val t = EaseOut.transform(lift.coerceIn(0f, 1f))
    val shape = AppShape.all(34f)
    val shadows = liftShadows(scheme.dark).map { it.copy(color = it.color.copy(alpha = it.color.alpha * t)) }
    Box(
        Modifier.graphicsLayer {
            translationY = offset
            if (sway != null && lift == 0f) {
                val swings = swaysPerLoop[Math.floorMod(seed, swaysPerLoop.size)]
                val turn = sway * 2f * PI.toFloat() * swings + seed * SWAY_PHASE_STEP
                translationY += cos(turn) * with(density) { SWAY_BOB.dp.toPx() }
                rotationZ = sin(turn) * SWAY_TURN_DEGREES
            }
            val scale = 1f + LIFT_GROWTH * t
            scaleX = scale
            scaleY = scale
        },
    ) {
        Box(modifier.then(if (t > 0f) Modifier.boxShadows(shape, shadows) else Modifier)) {
            content()
            // AbsorbPointer: the card's own controls stay still while editing.
            Box(Modifier.matchParentSize().pointerInput(Unit) {
                awaitPointerEventScope { while (true) awaitPointerEvent() }
            })
        }
        if (onRemove != null) {
            RoundButton(
                glyph = AppGlyphs.remove,
                description = "${tr("Remove")} ${tr(name)}",
                background = scheme.errorContainer,
                foreground = scheme.onErrorContainer,
                onClick = onRemove,
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }
    }
}

@Composable
private fun AddRow(name: String, glyph: Glyph, description: String?, onAdd: (() -> Unit)?) {
    val scheme = BoxTheme.scheme
    val cardColor = SurfaceLevel.Card.colorOf(scheme)
    val label = tr(name)
    Row(
        Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT.dp)
            .background(cardColor.copy(alpha = .55f), AppShape.all(22f))
            .padding(start = 12.dp, end = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundButton(AppGlyphs.add, "${tr("Add")} $label", scheme.primary, scheme.onPrimary, onAdd)
        Box(Modifier.size(40.dp).background(cardColor, AppShape.all(14f)), contentAlignment = Alignment.Center) {
            GlyphIcon(glyph, size = 20.dp, color = scheme.onSurfaceVariant)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = AppTextStyle.Subheadline.of(true).copy(color = scheme.onSurface))
            if (description != null) {
                Text(description, maxLines = 1, overflow = TextOverflow.Ellipsis, style = AppTextStyle.Caption1.of().tabular.copy(color = scheme.onSurfaceVariant))
            }
        }
    }
}

@Composable
private fun RoundButton(
    glyph: Glyph,
    description: String,
    background: Color,
    foreground: Color,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(if (onClick == null) background.copy(alpha = background.alpha * .38f) else background)
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(glyph, size = 16.dp, color = foreground)
    }
}

/** DashboardShadows.e2: the lifted card's drop. */
private fun liftShadows(dark: Boolean): List<BoxShadowSpec> = if (dark) {
    listOf(BoxShadowSpec(Color(0x80000000), 2f, 6f), BoxShadowSpec(Color(0xB3000000), 32f, 64f, -12f))
} else {
    listOf(BoxShadowSpec(Color(0x0F000000), 1f, 3f), BoxShadowSpec(Color(0x14000000), 10f, 24f), BoxShadowSpec(Color(0x38000000), 32f, 64f, -12f))
}

private fun dashboardGlyph(card: String): Glyph = when (card) {
    "Status" -> AppGlyphs.status
    "Network speed" -> AppGlyphs.speed
    "Outbound mode" -> AppGlyphs.split
    "Current profile" -> AppGlyphs.profiles
    "Proxy groups" -> AppGlyphs.layers
    "Memory" -> AppGlyphs.cpu
    "Intranet IP" -> AppGlyphs.devices
    "Service status" -> AppGlyphs.verified
    "Connection stats" -> AppGlyphs.connections
    else -> dashboardSystemProxyGlyph
}
