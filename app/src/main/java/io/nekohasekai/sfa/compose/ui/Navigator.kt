package io.nekohasekai.sfa.compose.ui

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import kotlin.coroutines.cancellation.CancellationException
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.compose.theme.AppCorner
import io.nekohasekai.sfa.compose.theme.AppShape
import io.nekohasekai.sfa.compose.theme.BoxTheme
import io.nekohasekai.sfa.compose.theme.LocalSurfaceLevel
import io.nekohasekai.sfa.compose.theme.SurfaceLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/*
 * The routes of flutter/lib/ui: pages pushed over the home page (showExtend,
 * commonSharedXPageTransitions), forms (showFormSheet) as a snap sheet in the
 * mobile layout (snap_sheet.dart, detents 9/16 and 0.9 of the height under
 * the status bar, a 28 dp superellipse top and the modal scrim) or a form
 * card centered over the navigator elsewhere, and pages pushed from inside a
 * sheet, which stay in that sheet as its nested navigator does.
 */

/** sheet.dart's SheetType, as the scaffold reads it. */
enum class SheetType { Page, BottomSheet, FormSheet }

/** One route: a page, or a sheet with the pages pushed inside it. */
@Stable
class NavLevel internal constructor(
    val content: @Composable () -> Unit,
    val sheet: SheetType?,
    internal val onClosed: ((Any?) -> Unit)?,
    internal val transition: PageTransition = PageTransition.Push,
    /** A zoom's source tile, in root coordinates, and its corner radius in px. */
    internal val origin: Rect? = null,
    internal val originRadius: Float = 0f,
) {
    internal val children = mutableStateListOf<NavLevel>()
    internal val presentation = Animatable(0f)
    internal var closing by mutableStateOf(false)
    internal var result: Any? = null

    /** The drag back following the finger, while there is one (drag_back.dart). */
    internal var drag by mutableStateOf<DragBackState?>(null)

    /** The route's width in px, which a drag back is measured against. */
    internal var width = 1f

    /** How far the route has come in: its presentation, or where a drag back holds it. */
    internal val progress: Float
        get() {
            val dragged = drag ?: return presentation.value
            return (1 - dragged.offset.value / width.coerceAtLeast(1f)).coerceIn(0f, 1f)
        }

    /** How far a drag back has stretched the page past rest toward the leading edge, as a share of the width. */
    internal val overdrag: Float
        get() {
            val dragged = drag ?: return 0f
            return (-dragged.offset.value / width.coerceAtLeast(1f)).coerceAtLeast(0f)
        }

    /** BoxEditGuard's question before the route may close. */
    var guard: (suspend () -> Boolean)? = null
}

class BoxNavigator internal constructor(
    private val scope: CoroutineScope,
    /** The root navigator of a destination's own navigator (home.dart's _NavigationPage). */
    val parent: BoxNavigator? = null,
) {
    val entries = mutableStateListOf<NavLevel>()

    private var ownMobile by mutableStateOf(true)

    /** Where this navigator's host sits in the window, for a zoom's source tile. */
    internal var hostOrigin = Offset.Zero

    /** True in the mobile layout, where a form is a bottom sheet. */
    var mobile: Boolean
        get() = parent?.mobile ?: ownMobile
        internal set(value) {
            ownMobile = value
        }

    /** The phase-1 name: the routes over the home page. */
    val pages: List<NavLevel> get() = entries

    private val topSheet: NavLevel? get() = entries.lastOrNull { !it.closing }?.takeIf { it.sheet != null }

    /** showExtend / openPage: a page, inside the sheet on top when there is one. */
    fun push(page: @Composable () -> Unit) = pushPage(page, null)

    fun pushPage(page: @Composable () -> Unit, onClosed: ((Any?) -> Unit)?) = pushRoute(NavLevel(page, null, onClosed))

    /** OpenContainer with transition: zoom: the page grows out of [source] and the page under it recedes. */
    fun pushZoom(source: ZoomSource?, onClosed: ((Any?) -> Unit)? = null, page: @Composable () -> Unit) =
        pushRoute(NavLevel(page, null, onClosed, PageTransition.Zoom, source?.bounds, source?.radius ?: 0f))

    private fun pushRoute(level: NavLevel) {
        // A sheet over the whole window keeps its pushes, as its nested navigator does.
        val root = parent
        if (root != null && root.topSheet != null) return root.pushRoute(level)
        val sheet = topSheet
        if (sheet != null) sheet.children.add(level) else entries.add(level)
        scope.launch { level.presentation.settleTo(level, 1f) }
    }

    /** showFormSheet / openForm. */
    fun openForm(onClosed: ((Any?) -> Unit)? = null, page: @Composable () -> Unit) {
        // Sheets and form cards belong to the root navigator, over the sidebar too.
        if (parent != null) return parent.openForm(onClosed, page)
        val level = NavLevel(page, if (mobile) SheetType.BottomSheet else SheetType.FormSheet, onClosed)
        entries.add(level)
        scope.launch { level.presentation.animateTo(1f, sheetSpecOf(opens = true)) }
    }

    private fun current(): Pair<NavLevel, NavLevel?>? {
        val top = entries.lastOrNull { !it.closing } ?: return null
        val child = top.children.lastOrNull { !it.closing }
        return top to child
    }

    /** context.safeNestedPop: closes the innermost route without asking its guard. */
    fun pop(result: Any? = null) {
        val (top, child) = current() ?: return
        close(child ?: top, if (child != null) top else null, result)
    }

    /** Navigator.maybePop: asks the innermost route's guard first. */
    fun maybePop() {
        val (top, child) = current() ?: return
        val level = child ?: top
        scope.launch {
            val guard = level.guard
            if (guard == null || guard()) close(level, if (child != null) top else null, null)
        }
    }

    /** Closes the whole sheet [level] belongs to, asking the guard of its visible route. */
    internal fun dismissSheet(level: NavLevel) {
        val visible = level.children.lastOrNull { !it.closing } ?: level
        scope.launch {
            val guard = visible.guard
            if (guard == null || guard()) close(level, null, null)
        }
    }

    private fun close(level: NavLevel, parent: NavLevel?, result: Any?) {
        if (level.closing) return
        level.closing = true
        level.result = result
        scope.launch {
            if (level.sheet != null) {
                level.presentation.animateTo(0f, sheetSpecOf(opens = false))
            } else {
                level.drag = null
                level.presentation.settleTo(level, 0f)
            }
            finishClose(level, parent)
        }
    }

    /** Takes a closed route out of its navigator and reports its result. */
    internal fun finishClose(level: NavLevel, parent: NavLevel? = ownerOf(level)) {
        if (parent != null) parent.children.remove(level) else entries.remove(level)
        level.onClosed?.invoke(level.result)
    }

    private fun ownerOf(level: NavLevel): NavLevel? = entries.firstOrNull { level in it.children }

    /** _canDragBack: the route is on top, settled, and nothing is over it in this navigator or its root. */
    internal fun canDragBack(level: NavLevel): Boolean {
        if (level.closing || level.drag != null || level.sheet != null || level.presentation.isRunning) return false
        // A route open on the root navigator (a form or sheet) is over a destination's own navigator.
        if (parent?.canPop == true) return false
        val (top, child) = current() ?: return false
        return (child ?: top) === level
    }

    /** The page the system back gesture moves, when one would pop without asking. */
    internal fun predictiveBackTarget(): NavLevel? {
        val (top, child) = current() ?: return null
        val level = child ?: top
        return level.takeIf { it.sheet == null && it.guard == null && canDragBack(it) }
    }

    val canPop: Boolean get() = entries.any { !it.closing }

    companion object {
        // snap_sheet.dart's presentation: a critically damped spring of stiffness 1000/3.
        val sheetSpring = spring<Float>(dampingRatio = 1f, stiffness = 1000f / 3f, visibilityThreshold = 0.001f)

        /** The sheet's presentation: its spring, or the minimal style's short fade. */
        internal fun sheetSpecOf(opens: Boolean): androidx.compose.animation.core.AnimationSpec<Float> {
            val tokens = io.nekohasekai.sfa.compose.theme.AppearanceTokens.active
            return when {
                tokens.expressiveMotion -> sheetSpring
                opens -> tokens.fade<Float>()
                else -> tokens.fadeOut<Float>()
            }
        }
    }
}

/** CardRouteMixin.settleTo: a push or pop springs from where the route is, launched at its multiple of the distance a second. */
private suspend fun Animatable<Float, AnimationVector1D>.settleTo(level: NavLevel, target: Float) {
    val opens = target == 1f
    // The minimal style pushes and pops with a short fade from where the route is.
    val tokens = io.nekohasekai.sfa.compose.theme.AppearanceTokens.active
    if (!tokens.expressiveMotion) {
        animateTo(target, if (opens) tokens.fade<Float>() else tokens.fadeOut<Float>())
        return
    }
    val (spec, speed) = when {
        level.transition == PageTransition.Zoom && opens -> ZOOM_OPEN_SPRING to ZOOM_OPEN_SPEED
        level.transition == PageTransition.Zoom -> ZOOM_CLOSE_SPRING to 0f
        else -> PUSH_SPRING to PUSH_SPEED
    }
    animateTo(target, spec, (target - value) * speed)
}

val LocalBoxNavigator = staticCompositionLocalOf<BoxNavigator?> { null }

/** The route the content is in, for its guard and its sheet. */
val LocalNavLevel = compositionLocalOf<NavLevel?> { null }

/** SheetProvider.of(context)?.type for the scaffold: null on a plain page. */
val LocalSheetType = compositionLocalOf<SheetType?> { null }

/** True for the first route of a sheet: its bar leads with close, not back. */
val LocalSheetRoot = compositionLocalOf { false }

/** The sheet the content is in, for a drag on its bar and its dismissal. */
internal val LocalSheetController = compositionLocalOf<SheetController?> { null }

@Composable
fun rememberBoxNavigator(): BoxNavigator {
    val scope = rememberCoroutineScope()
    return remember { BoxNavigator(scope) }
}

/** widgets.dart's BoxEditGuard: system back, the sheet's close and its dismissal ask first. */
@Composable
fun BoxEditGuard(dirty: Boolean, busy: Boolean = false, content: @Composable () -> Unit) {
    val level = LocalNavLevel.current
    val dialogs = LocalDialogs.current
    val state = remember { object { var confirming = false } }
    val dirtyNow = rememberUpdated(dirty)
    val busyNow = rememberUpdated(busy)
    DisposableEffect(level) {
        val guard: suspend () -> Boolean = guard@{
            if (busyNow.value || state.confirming) return@guard false
            if (!dirtyNow.value) return@guard true
            state.confirming = true
            try {
                val discard = dialogs?.confirm("Discard changes?", action = "Discard") ?: true
                discard && !busyNow.value
            } finally {
                state.confirming = false
            }
        }
        level?.guard = guard
        onDispose { if (level?.guard === guard) level.guard = null }
    }
    content()
}

@Composable
internal fun <T> rememberUpdated(value: T): androidx.compose.runtime.MutableState<T> {
    val state = remember { mutableStateOf(value) }
    state.value = value
    return state
}

/**
 * The system back gesture: on a page that would pop without asking, the page
 * follows the gesture's progress (Android 14+) as a drag back does and lets
 * go on commit or cancel; otherwise back is Navigator.maybePop.
 */
@Composable
private fun NavigatorBackHandler(navigator: BoxNavigator, enabled: Boolean) {
    val scope = rememberCoroutineScope()
    PredictiveBackHandler(enabled = enabled) { progress ->
        val target = navigator.predictiveBackTarget()
        if (target == null) {
            progress.collect { }
            navigator.maybePop()
            return@PredictiveBackHandler
        }
        var followed = false
        try {
            progress.collect { event ->
                if (!followed) target.startDragBack()
                followed = true
                target.dragBackTo(scope, event.progress * PREDICTIVE_BACK_REACH * target.width)
            }
            // Without the gesture's progress (before Android 14) back pops as a tap on back does.
            // The release runs in the host's scope: the handler is disabled as the page starts to close.
            if (followed) scope.launch { navigator.endDragBack(target, 0f, commit = true) } else navigator.maybePop()
        } catch (cancelled: CancellationException) {
            scope.launch { navigator.endDragBack(target, 0f, commit = false) }
            throw cancelled
        }
    }
}

/**
 * Hosts [home] and the routes over it. A page comes in with iOS 27's push
 * (page_transition.dart): from the trailing edge on a 300 ms critically
 * damped spring while the page under it slides 30% back and dims, and pops
 * by dragging anywhere on it (drag_back.dart); a sheet rises over the modal
 * scrim.
 */
@Composable
fun NavigatorHost(navigator: BoxNavigator, home: @Composable () -> Unit) {
    val dialogs = rememberDialogHost()
    val popups = remember { PopupHost() }
    CompositionLocalProvider(LocalBoxNavigator provides navigator, LocalDialogs provides dialogs, LocalPopups provides popups) {
        BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { navigator.hostOrigin = it.positionInRoot() }) {
            val viewMode = viewModeOf(maxWidth)
            navigator.mobile = viewMode == ViewMode.Mobile
            NavigatorBackHandler(navigator, enabled = navigator.canPop)
            CompositionLocalProvider(LocalViewMode provides viewMode) {
            val entries = navigator.entries.toList()
            // What a popup's or an alert's glass bends: everything under them.
            val glassOn = LocalLiquidGlass.current.shaded
            val appBackdrop = rememberGlassBackdrop()
            val motion = rememberCardMotion(navigator)
            Box(Modifier.fillMaxSize().glassBackdropSource(appBackdrop, glassOn)) {
            // A page over the home page covers it; sheets leave it in view.
            Box(Modifier.fillMaxSize().covered(motion, entries.firstOrNull()?.takeIf { it.sheet == null })) { home() }
            entries.forEachIndexed { index, level ->
                val above = entries.getOrNull(index + 1)?.takeIf { it.sheet == null }
                key(level) {
                    CompositionLocalProvider(LocalNavLevel provides level) {
                        if (level.sheet == null) {
                            PageRoute(navigator, motion, level, above)
                        } else {
                            SheetRoute(navigator, level)
                        }
                    }
                }
            }
            }
            CompositionLocalProvider(LocalGlassBackdrop provides if (glassOn) appBackdrop else null) {
                PopupLayer(popups)
                DialogLayer(dialogs)
            }
            }
        }
    }
}

/**
 * home.dart's _NavigationPage outside the mobile layout: a destination's own
 * navigator, so pages pushed from it stay in the content pane beside the
 * sidebar, with the same push and drag back. Sheets, dialogs and popups stay
 * with the root navigator.
 */
@Composable
fun NestedNavigatorHost(navigator: BoxNavigator, active: Boolean, home: @Composable () -> Unit) {
    val parentBusy = navigator.parent?.canPop == true
    NavigatorBackHandler(navigator, enabled = active && navigator.canPop && !parentBusy)
    CompositionLocalProvider(LocalBoxNavigator provides navigator) {
        val entries = navigator.entries.toList()
        val motion = rememberCardMotion(navigator)
        Box(Modifier.fillMaxSize().clipToBounds().onGloballyPositioned { navigator.hostOrigin = it.positionInRoot() }) {
            Box(Modifier.fillMaxSize().covered(motion, entries.firstOrNull())) { home() }
            entries.forEachIndexed { index, level ->
                key(level) {
                    CompositionLocalProvider(LocalNavLevel provides level) {
                        PageRoute(navigator, motion, level, entries.getOrNull(index + 1))
                    }
                }
            }
        }
    }
}

@Composable
private fun <T> key(value: T, content: @Composable () -> Unit) = androidx.compose.runtime.key(value) { content() }

/** What every card transition of one host reads: its navigator, the display corners, the page colour and the light theme's shadow. */
private class CardMotion(
    val navigator: BoxNavigator,
    val scope: CoroutineScope,
    val displayRadius: Float,
    val surface: Color,
    val shadow: Boolean,
    val rtl: Boolean,
    val minimal: Boolean,
)

@Composable
private fun rememberCardMotion(navigator: BoxNavigator): CardMotion {
    val scheme = BoxTheme.scheme
    val scope = rememberCoroutineScope()
    val radius = displayCornerRadius()
    val surface = SurfaceLevel.Page.colorOf(scheme)
    val light = !scheme.dark
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val tokens = io.nekohasekai.sfa.compose.theme.LocalAppearance.current
    val minimal = !tokens.expressiveMotion
    val shadow = light && tokens.shadows
    return remember(navigator, scope, radius, surface, shadow, rtl, minimal) { CardMotion(navigator, scope, radius, surface, shadow, rtl, minimal) }
}

/** The page under [above] (the home page, or a route's root) as the route over it covers it. */
private fun Modifier.covered(motion: CardMotion, above: NavLevel?): Modifier = if (above == null) {
    this
} else {
    cardTransition(null, above, { motion.navigator.hostOrigin }, motion.displayRadius, motion.surface, motion.shadow, motion.rtl, motion.minimal)
}

private fun Modifier.route(motion: CardMotion, level: NavLevel, above: NavLevel?): Modifier = this
    .onSizeChanged { level.width = it.width.toFloat() }
    .cardTransition(level, above, { motion.navigator.hostOrigin }, motion.displayRadius, motion.surface, motion.shadow, motion.rtl, motion.minimal)
    .dragBack(motion.navigator, level, motion.scope, motion.rtl)

@Composable
private fun PageRoute(navigator: BoxNavigator, motion: CardMotion, level: NavLevel, above: NavLevel?) {
    CompositionLocalProvider(LocalPushedPage provides true, LocalBottomInset provides 0.dp, LocalSheetType provides SheetType.Page) {
        Box(
            Modifier
                .fillMaxSize()
                .route(motion, level, above)
                .absorbPointer(),
        ) { level.content() }
    }
}

/** The pages of one sheet, the first one its root. */
@Composable
private fun SheetPages(level: NavLevel) {
    val navigator = LocalBoxNavigator.current ?: return
    val motion = rememberCardMotion(navigator)
    val children = level.children.toList()
    Box(Modifier.fillMaxSize().covered(motion, children.firstOrNull())) {
        CompositionLocalProvider(LocalSheetRoot provides true) { level.content() }
    }
    children.forEachIndexed { index, child ->
        key(child) {
            CompositionLocalProvider(LocalNavLevel provides child, LocalSheetRoot provides false) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .route(motion, child, children.getOrNull(index + 1))
                        .absorbPointer(),
                ) { child.content() }
            }
        }
    }
}

private fun Modifier.absorbPointer(): Modifier = clickable(
    interactionSource = MutableInteractionSource(),
    indication = null,
    onClick = {},
)

// ---------------------------------------------------------------------------
// Snap sheet (snap_sheet.dart)
// ---------------------------------------------------------------------------

private val SNAP_SHEET_DETENTS = floatArrayOf(9f / 16f, 0.9f)

/** UIKit's deceleration rate .99, as the time a throw projects over. */
private const val PROJECTION_TIME = 0.99f / (1 - 0.99f) / 1000f
private const val RUBBER_BAND_EXTENT = 100f
private const val RUBBER_BAND_COEFFICIENT = 0.55f

private fun rubberBand(overshoot: Float, extent: Float): Float {
    if (overshoot <= 0f || extent <= 0f) return 0f
    return (1 - 1 / (overshoot * RUBBER_BAND_COEFFICIENT / extent + 1)) * extent
}

@Stable
internal class SheetController(
    private val navigator: BoxNavigator,
    private val level: NavLevel,
    private val scope: CoroutineScope,
) {
    /** The sheet's height, in px, while it rests or follows a drag. */
    val height = Animatable(0f)
    var stops by mutableStateOf(floatArrayOf(0f, 0f))
    var available = 0f
    var fitHeight by mutableFloatStateOf(Float.POSITIVE_INFINITY)
    private var held = 0f
    private var holding = false
    var stop = 0
        private set

    fun layout(available: Float, density: Float) {
        this.available = available
        val shortest = SNAP_SHEET_DETENTS.first() * available
        val next = FloatArray(SNAP_SHEET_DETENTS.size) { index ->
            val detent = SNAP_SHEET_DETENTS[index] * available
            // A fit sheet follows its content up to each detent but never rests below the shortest.
            fitHeight.coerceIn(shortest, detent)
        }
        if (!next.contentEquals(stops)) {
            stops = next
            if (!holding) scope.launch { height.snapTo(next[stop.coerceIn(0, next.lastIndex)]) }
        }
    }

    val atTop: Boolean get() = height.value >= stops.last() - 0.5f

    fun dragBy(delta: Float) {
        holding = true
        held += delta
        val tallest = stops.last()
        val extent = (available - tallest).coerceIn(0f, RUBBER_BAND_EXTENT)
        val shown = if (held > tallest) tallest + rubberBand(held - tallest, extent) else held
        scope.launch { height.snapTo(max(0f, shown)) }
    }

    fun startDrag() {
        if (!holding) held = height.value
        holding = true
    }

    /** UIKit's sheet: the throw projects, and the nearest detent (or closing) wins. */
    fun dragEnd(velocity: Float) {
        holding = false
        val projected = height.value + velocity * PROJECTION_TIME * 1000f
        val candidates = listOf(0f) + stops.toList()
        val target = candidates.minByOrNull { abs(it - projected) } ?: stops.first()
        if (target == 0f) {
            scope.launch { height.animateTo(stops[stop], spring(1f, 1000f / 3f), velocity) }
            navigator.dismissSheet(level)
            return
        }
        stop = stops.indexOfFirst { it == target }.coerceAtLeast(0)
        scope.launch {
            height.animateTo(target, spring(if (abs(velocity) > 0f) 0.8f else 1f, 1000f / 3f), velocity)
        }
    }

    val nestedScroll = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            // Growing to the tallest detent comes before the content scrolls.
            if (available.y < 0f && !atTop) {
                startDrag()
                dragBy(-available.y)
                return Offset(0f, available.y)
            }
            if (available.y > 0f && holding && height.value > stops.first() - 0.5f && !atTopRest()) {
                dragBy(-available.y)
                return Offset(0f, available.y)
            }
            return Offset.Zero
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            // A pull past the content's top moves the sheet down.
            if (available.y > 0f && source == NestedScrollSource.UserInput) {
                startDrag()
                dragBy(-available.y)
                return Offset(0f, available.y)
            }
            return Offset.Zero
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            if (holding) {
                dragEnd(-available.y)
                return available
            }
            return Velocity.Zero
        }
    }

    private fun atTopRest(): Boolean = !holding && atTop
}

@Composable
private fun SheetRoute(navigator: BoxNavigator, level: NavLevel) {
    val scheme = BoxTheme.scheme
    val shown = level.presentation.value
    // ModalBarrier: the scrim fades with the presentation and a tap dismisses.
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = shown.coerceIn(0f, 1f) }
            .background(scheme.scrim.copy(alpha = 0.32f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                if (!level.closing) navigator.dismissSheet(level)
            },
    )
    if (level.sheet == SheetType.FormSheet) {
        FormSheetCard(level)
    } else {
        SnapSheet(navigator, level)
    }
}

@Composable
private fun SnapSheet(navigator: BoxNavigator, level: NavLevel) {
    val scope = rememberCoroutineScope()
    val controller = remember(level) { SheetController(navigator, level, scope) }
    val density = LocalDensity.current
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val ime = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    BoxWithConstraints(Modifier.fillMaxSize().padding(top = top)) {
        val available = with(density) { (maxHeight - ime).toPx() }
        controller.layout(available, density.density)
        // The minimal style fades the sheet in place instead of raising it.
        val minimal = !io.nekohasekai.sfa.compose.theme.LocalAppearance.current.expressiveMotion
        val presented = level.presentation.value
        val shown = if (minimal) 1f else presented
        val height = controller.height.value
        val sheetPx = max(0f, height)
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = ime)
                .fillMaxWidth()
                .height(with(density) { max(sheetPx, controller.stops.first()).toDp() })
                .offset { IntOffset(0, ((1 - shown) * max(sheetPx, controller.stops.first()) + max(0f, controller.stops.first() - sheetPx)).toInt()) }
                .then(if (minimal) Modifier.graphicsLayer { alpha = presented.coerceIn(0f, 1f) } else Modifier)
                .clip(AppShape.top(AppCorner.XXL))
                .background(SurfaceLevel.Page.colorOf(BoxTheme.scheme))
                .nestedScroll(controller.nestedScroll)
                .draggable(
                    rememberDraggableState { delta -> controller.dragBy(-delta) },
                    Orientation.Vertical,
                    onDragStarted = { controller.startDrag() },
                    onDragStopped = { velocity -> controller.dragEnd(-velocity) },
                ),
        ) {
            CompositionLocalProvider(
                LocalSheetType provides SheetType.BottomSheet,
                LocalSheetController provides controller,
                LocalSurfaceLevel provides SurfaceLevel.Page,
                LocalBottomInset provides 0.dp,
            ) { SheetPages(level) }
        }
    }
}

/** sheet.dart's FormSheetRoute: a card centered over the navigator outside the mobile layout. */
@Composable
private fun FormSheetCard(level: NavLevel) {
    val shown = level.presentation.value
    val minimal = !io.nekohasekai.sfa.compose.theme.LocalAppearance.current.expressiveMotion
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .widthIn(max = 580.dp)
                .fillMaxWidth(0.9f)
                .heightIn(max = 720.dp)
                .fillMaxSize(0.85f)
                .graphicsLayer {
                    alpha = shown.coerceIn(0f, 1f)
                    val scale = if (minimal) 1f else 0.94f + 0.06f * shown
                    scaleX = scale
                    scaleY = scale
                }
                .clip(AppShape.all(AppCorner.XXL))
                .background(SurfaceLevel.Page.colorOf(BoxTheme.scheme)),
        ) {
            CompositionLocalProvider(LocalSheetType provides SheetType.FormSheet, LocalBottomInset provides 0.dp) { SheetPages(level) }
        }
    }
}

