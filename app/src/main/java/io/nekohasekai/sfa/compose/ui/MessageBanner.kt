package io.nekohasekai.sfa.compose.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.nekohasekai.sfa.compose.theme.hairlineOutline
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.compose.theme.AppShape
import io.nekohasekai.sfa.compose.theme.AppTextStyle
import io.nekohasekai.sfa.compose.theme.BoxScheme
import io.nekohasekai.sfa.compose.theme.BoxTheme
import io.nekohasekai.sfa.compose.theme.LocalSurfaceLevel
import io.nekohasekai.sfa.compose.theme.SurfaceLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * prism_ui 63450c0 banner.dart BannerHost: iOS 27's notification banner as the
 * app's message surface (it replaces the bottom snackbar). Banners drop in
 * from above the screen one at a time on phones, or stack up to three on
 * wider layouts (FlClash application.dart stackedBanners: !isMobileView).
 * Each lasts 3/5/6 s by level; once every slot is taken the oldest leaves
 * after 3150 ms for the next. A repeat of a shown banner restarts it instead
 * of queueing. Swipe up (or the close button) dismisses.
 */

private const val ACTION_MIN_MS = 6000L
private const val QUEUED_HOLD_MS = 3150L
private const val MAX_BUFFERED = 8
private const val STACKED_MAX_VISIBLE = 3
private const val BANNER_GAP = 8f
private const val BANNER_INSET = 8f
private const val BANNER_MAX_WIDTH = 556f
private const val BANNER_MIN_HEIGHT = 52f
private const val EXIT_MS = 170
private const val DISMISS_VELOCITY = 700f
private const val DISMISS_FRACTION = 0.5f
private const val PULL_EXTENT = 40f
private const val PULL_COEFFICIENT = 0.55f

/** banner.dart _dropSpring (stiffness 256, damping 27.2). */
private const val DROP_STIFFNESS = 256f
private const val DROP_DAMPING_RATIO = 0.85f

enum class BannerLevel(val durationMs: Long) {
    Info(3000),
    Success(3000),
    Warning(5000),
    Error(6000),
    ;

    /** The banner's fill, or null for the plain floating surface. */
    fun fillOf(scheme: BoxScheme): Color? = when (this) {
        Info -> null
        Success -> scheme.successContainer
        Warning -> scheme.warningContainer
        Error -> scheme.errorContainer
    }

    fun contentOf(scheme: BoxScheme): Color = when (this) {
        Info -> scheme.onSurface
        Success -> scheme.onSuccessContainer
        Warning -> scheme.onWarningContainer
        Error -> scheme.onErrorContainer
    }
}

internal class BannerMessage(val id: Int, val text: String, val level: BannerLevel, val onTap: (() -> Unit)?) {
    val durationMs: Long get() = if (onTap == null || level.durationMs >= ACTION_MIN_MS) level.durationMs else ACTION_MIN_MS

    fun repeats(other: BannerMessage): Boolean = text == other.text && level == other.level
}

internal class BannerEntry(val message: BannerMessage, val visible: Boolean) {
    val id: Int get() = message.id
}

/** banner.dart BannerHostState: the queue, its timers and the shown entries. */
@Stable
class BannerHostState internal constructor(private val scope: CoroutineScope) {
    private val entries = mutableStateListOf<BannerEntry>()
    private val buffer = ArrayDeque<BannerMessage>()
    private val hideJobs = HashMap<Int, Job>()
    private val holdJobs = HashMap<Int, Job>()
    private val held = HashSet<Int>()
    private var nextId = 0
    private var draggingId: Int? = null
    internal var stacked = false

    private val visibleEntries: List<BannerEntry> get() = entries.filter { it.visible }

    /** A banner with [onTap] runs it when tapped, as an iOS notification opens what it tells of. */
    fun show(text: String, level: BannerLevel = BannerLevel.Info, onTap: (() -> Unit)? = null) {
        if (text.isEmpty()) return
        val message = BannerMessage(nextId++, text, level, onTap)
        if (merge(message)) return
        buffer.addLast(message)
        trimBuffer()
        processQueue()
    }

    private fun merge(message: BannerMessage): Boolean {
        val index = entries.indexOfFirst { it.visible && it.message.repeats(message) }
        if (index != -1) {
            val merged = BannerMessage(entries[index].id, message.text, message.level, message.onTap)
            entries[index] = BannerEntry(merged, true)
            startTimers(merged)
            return true
        }
        return buffer.any { it.repeats(message) }
    }

    private fun trimBuffer() {
        while (buffer.size > MAX_BUFFERED) {
            buffer.remove(buffer.firstOrNull { it.level != BannerLevel.Error } ?: buffer.first())
        }
    }

    private fun processQueue() {
        if (draggingId != null) return
        val maxVisible = if (stacked) STACKED_MAX_VISIBLE else 1
        while (buffer.isNotEmpty()) {
            val visible = visibleEntries
            if (visible.size >= maxVisible) {
                val oldest = visible.last()
                if (oldest.id in held && oldest.message.onTap == null) {
                    hide(oldest.id)
                    continue
                }
                return
            }
            // A lone banner's successor waits until it has risen off the screen.
            if (!stacked && entries.isNotEmpty()) return
            val next = buffer.firstOrNull { it.level == BannerLevel.Error } ?: buffer.first()
            buffer.remove(next)
            entries.add(0, BannerEntry(next, true))
            startTimers(next)
        }
    }

    private fun startTimers(message: BannerMessage) {
        val id = message.id
        cancelTimersOf(id)
        if (draggingId != null) return
        holdJobs[id] = scope.launch {
            delay(QUEUED_HOLD_MS)
            holdJobs.remove(id)
            held.add(id)
            processQueue()
        }
        hideJobs[id] = scope.launch {
            delay(message.durationMs)
            hideJobs.remove(id)
            hide(id)
        }
    }

    private fun cancelTimersOf(id: Int) {
        hideJobs.remove(id)?.cancel()
        holdJobs.remove(id)?.cancel()
        held.remove(id)
    }

    private fun cancelTimers() {
        (hideJobs.values + holdJobs.values).forEach { it.cancel() }
        hideJobs.clear()
        holdJobs.clear()
        held.clear()
    }

    private fun hide(id: Int) {
        val index = entries.indexOfFirst { it.id == id }
        if (index == -1 || !entries[index].visible) return
        cancelTimersOf(id)
        entries[index] = BannerEntry(entries[index].message, false)
        processQueue()
    }

    internal fun dismiss(id: Int) {
        if (draggingId == id) {
            draggingId = null
            resumeTimers()
        }
        buffer.removeAll { it.id == id }
        hide(id)
    }

    internal fun setDragging(id: Int, dragging: Boolean) {
        if (dragging) {
            if (draggingId == id) return
            draggingId = id
            cancelTimers()
            return
        }
        if (draggingId != id) return
        draggingId = null
        resumeTimers()
        processQueue()
    }

    private fun resumeTimers() {
        visibleEntries.forEach { startTimers(it.message) }
    }

    internal fun remove(id: Int) {
        if (entries.none { it.id == id }) return
        entries.removeAll { it.id == id }
        processQueue()
    }

    internal val shown: List<BannerEntry> get() = entries
}

@Composable
fun rememberBannerHostState(): BannerHostState {
    val scope = rememberCoroutineScope()
    return remember(scope) { BannerHostState(scope) }
}

/**
 * The banner layer drawn over the app. [topInset] is what the app keeps clear
 * below the status bar (banner.dart topInset).
 */
@Composable
fun BannerHost(state: BannerHostState, stacked: Boolean, topInset: Dp = 0.dp) {
    state.stacked = stacked
    val density = LocalDensity.current
    val statusTop = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
    val top = max(statusTop.value + topInset.value, BANNER_INSET).dp
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = (maxWidth.value - BANNER_INSET * 2).coerceIn(0f, BANNER_MAX_WIDTH).dp
        val entries = state.shown
        if (stacked) {
            Column(Modifier.align(Alignment.TopCenter).padding(top = top).width(width)) {
                for (entry in entries) key(entry.id) { BannerItem(state, entry, stacked = true, top = top) }
            }
        } else {
            for (entry in entries) {
                key(entry.id) {
                    Box(Modifier.align(Alignment.TopCenter).width(width)) { BannerItem(state, entry, stacked = false, top = top) }
                }
            }
        }
    }
}

/** banner.dart _Banner: drops straight in from above and rises back out, or opens and closes a slot when stacked. */
@Composable
private fun BannerItem(state: BannerHostState, entry: BannerEntry, stacked: Boolean, top: Dp) {
    val visible = entry.visible
    val id = entry.id
    val tokens = io.nekohasekai.sfa.compose.theme.LocalAppearance.current
    // The minimal style shows a banner in place with a short fade instead of dropping it in.
    val still = !tokens.expressiveMotion
    val drop = remember { Animatable(0f) }
    val fade = remember { Animatable(if (still) 0f else 1f) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var pull by remember { mutableFloatStateOf(0f) }
    var dragging by remember { androidx.compose.runtime.mutableStateOf(false) }
    var height by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    var settle by remember { androidx.compose.runtime.mutableStateOf<Job?>(null) }
    val density = LocalDensity.current
    val topPx = with(density) { top.toPx() }
    val currentVisible by rememberUpdatedState(visible)
    LaunchedEffect(visible) {
        if (still) {
            if (visible) {
                drop.snapTo(1f)
                fade.animateTo(1f, tokens.fade<Float>())
            } else {
                settle?.cancel()
                if (fade.value > 0f) fade.animateTo(0f, tokens.fadeOut<Float>())
                state.remove(id)
            }
            return@LaunchedEffect
        }
        if (visible) {
            drop.animateTo(1f, spring(DROP_DAMPING_RATIO, DROP_STIFFNESS))
            drop.snapTo(1f)
        } else {
            settle?.cancel()
            if (drop.value <= 0f) {
                state.remove(id)
                return@LaunchedEffect
            }
            if (stacked) launch { fade.animateTo(0f, tween(EXIT_MS, easing = EaseIn)) }
            drop.animateTo(0f, tween(EXIT_MS, easing = EaseIn))
            state.remove(id)
        }
    }
    val message = entry.message
    val onTap = message.onTap
    val dragState = rememberDraggableState { delta ->
        if (dragging) {
            pull += delta
            dragOffset = if (pull <= 0f) pull else PULL_EXTENT * density.density * (1 - 1 / (PULL_COEFFICIENT * pull / (PULL_EXTENT * density.density) + 1))
        }
    }
    val gestures = Modifier.draggable(
        dragState,
        Orientation.Vertical,
        enabled = visible,
        onDragStarted = {
            if (currentVisible) {
                settle?.cancel()
                dragging = true
                pull = dragOffset
                state.setDragging(id, true)
            }
        },
        onDragStopped = { velocity ->
            if (dragging) {
                dragging = false
                val offset = dragOffset
                val bannerHeight = if (height > 0f) height else BANNER_MIN_HEIGHT * density.density
                if (velocity <= -DISMISS_VELOCITY * density.density || offset <= -(topPx + bannerHeight) * DISMISS_FRACTION) {
                    state.dismiss(id)
                } else {
                    state.setDragging(id, false)
                    settle = scope.launch {
                        animate(offset, 0f, velocity, if (still) tokens.fade<Float>() else spring(DROP_DAMPING_RATIO, DROP_STIFFNESS)) { value, _ -> dragOffset = value }
                        dragOffset = 0f
                    }
                }
            }
        },
    )
    val surface = @Composable {
        BannerSurface(
            message,
            Modifier
                .then(if (visible) gestures else Modifier)
                .then(
                    if (onTap != null && visible) {
                        Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            state.dismiss(id)
                            onTap()
                        }
                    } else {
                        Modifier
                    },
                ),
            onDismiss = { state.dismiss(id) },
        )
    }
    if (stacked) {
        Box(
            Modifier
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    val h = (placeable.height * max(0f, drop.value)).roundToInt()
                    layout(placeable.width, h) {
                        placeable.place(0, if (visible) h - placeable.height else 0)
                    }
                }
                .offset { IntOffset(0, dragOffset.roundToInt()) }
                .graphicsLayer { alpha = fade.value.coerceIn(0f, 1f) }
                .padding(bottom = BANNER_GAP.dp),
        ) { surface() }
    } else {
        Box(
            Modifier
                .onSizeChanged { height = it.height.toFloat() }
                // Lerps the top from a banner's height above the screen to its rest.
                .offset { IntOffset(0, (topPx * drop.value + dragOffset).roundToInt()) }
                .graphicsLayer {
                    translationY = (drop.value - 1) * size.height
                    alpha = fade.value.coerceIn(0f, 1f)
                },
        ) { surface() }
    }
}

/**
 * banner.dart _BannerSurface/_BannerContent on the floating surface or the
 * level's fill, keeping the app's glyph badge, up to four lines of text and
 * the close button.
 */
@Composable
private fun BannerSurface(message: BannerMessage, modifier: Modifier, onDismiss: () -> Unit) {
    val scheme = BoxTheme.scheme
    val level = message.level
    val floating = SurfaceLevel.floatingOf(scheme)
    val fill = level.fillOf(scheme) ?: floating.colorOf(scheme)
    val content = level.contentOf(scheme)
    val error = level == BannerLevel.Error
    val accent = if (error) scheme.error else scheme.primary
    val badge = if (level == BannerLevel.Info) scheme.primaryContainer else content.copy(alpha = 0.12f)
    val shape = AppShape.xl
    val shadows = io.nekohasekai.sfa.compose.theme.LocalAppearance.current.shadows
    androidx.compose.runtime.CompositionLocalProvider(LocalSurfaceLevel provides floating) {
        Row(
            modifier
                .fillMaxWidth()
                .heightIn(min = BANNER_MIN_HEIGHT.dp)
                .then(
                    if (shadows) {
                        Modifier.shadow(3.dp, shape, ambientColor = scheme.shadow.copy(alpha = 0.6f), spotColor = scheme.shadow.copy(alpha = 0.6f))
                    } else {
                        Modifier.hairlineOutline(shape)
                    },
                )
                .clip(shape)
                .background(fill)
                .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(badge), contentAlignment = Alignment.Center) {
                GlyphIcon(if (error) AppGlyphs.error else AppGlyphs.info, size = 20.dp, color = if (level == BannerLevel.Info) accent else content)
            }
            Text(
                message.text,
                Modifier.weight(1f),
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                style = AppTextStyle.Subheadline.of(true).copy(color = content),
            )
            Box(
                Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onDismiss).semanticsLabel(tr("Close")),
                contentAlignment = Alignment.Center,
            ) {
                GlyphIcon(AppGlyphs.close, size = 18.dp, color = if (level == BannerLevel.Info) scheme.onSurfaceVariant else content.copy(alpha = 0.7f))
            }
        }
    }
}
