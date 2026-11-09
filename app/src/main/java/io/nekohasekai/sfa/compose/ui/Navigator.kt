package io.nekohasekai.sfa.compose.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.compose.theme.AppShape
import io.nekohasekai.sfa.compose.theme.BoxTheme
import io.nekohasekai.sfa.compose.theme.LocalSurfaceLevel
import io.nekohasekai.sfa.compose.theme.SurfaceLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/*
 * The app's routes: pages pushed over the home page, and forms. A route shows
 * and goes away at once, without a transition; system back closes the top
 * one. On a phone a form is a full-screen page with a close button; on wider
 * windows it is a card centered over the content. Pages pushed from inside a
 * form stay in that form.
 */

/** How a route is shown, as the scaffold reads it. */
enum class SheetType { Page, BottomSheet, FormSheet }

/** One route: a page, or a form with the pages pushed inside it. */
@Stable
class NavLevel internal constructor(
    val content: @Composable () -> Unit,
    val sheet: SheetType?,
    internal val onClosed: ((Any?) -> Unit)?,
) {
    internal val children = mutableStateListOf<NavLevel>()
    internal var closing by mutableStateOf(false)
    internal var result: Any? = null

    /** BoxEditGuard's question before the route may close. */
    var guard: (suspend () -> Boolean)? = null
}

class BoxNavigator internal constructor(
    private val scope: CoroutineScope,
    /** The root navigator of a destination's own navigator. */
    val parent: BoxNavigator? = null,
) {
    val entries = mutableStateListOf<NavLevel>()

    private var ownMobile by mutableStateOf(true)

    /** True in the phone layout, where a form fills the screen. */
    var mobile: Boolean
        get() = parent?.mobile ?: ownMobile
        internal set(value) {
            ownMobile = value
        }

    private val topSheet: NavLevel? get() = entries.lastOrNull { !it.closing }?.takeIf { it.sheet != null }

    /** Opens a page, inside the form on top when there is one. */
    fun push(page: @Composable () -> Unit) = pushPage(page, null)

    fun pushPage(page: @Composable () -> Unit, onClosed: ((Any?) -> Unit)?) = pushRoute(NavLevel(page, null, onClosed))

    private fun pushRoute(level: NavLevel) {
        val root = parent
        if (root != null && root.topSheet != null) return root.pushRoute(level)
        val sheet = topSheet
        if (sheet != null) sheet.children.add(level) else entries.add(level)
    }

    /** Opens a form; [onClosed] receives what it was closed with. */
    fun openForm(onClosed: ((Any?) -> Unit)? = null, page: @Composable () -> Unit) {
        // Forms belong to the root navigator, over the navigation rail too.
        if (parent != null) return parent.openForm(onClosed, page)
        entries.add(NavLevel(page, if (mobile) SheetType.BottomSheet else SheetType.FormSheet, onClosed))
    }

    private fun current(): Pair<NavLevel, NavLevel?>? {
        val top = entries.lastOrNull { !it.closing } ?: return null
        val child = top.children.lastOrNull { !it.closing }
        return top to child
    }

    /** Closes the innermost route without asking its guard. */
    fun pop(result: Any? = null) {
        val (top, child) = current() ?: return
        close(child ?: top, if (child != null) top else null, result)
    }

    /** Asks the innermost route's guard, then closes it. */
    fun maybePop() {
        val (top, child) = current() ?: return
        val level = child ?: top
        scope.launch {
            val guard = level.guard
            if (guard == null || guard()) close(level, if (child != null) top else null, null)
        }
    }

    /** Closes the whole form [level] belongs to, asking the guard of its visible route. */
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
        if (parent != null) parent.children.remove(level) else entries.remove(level)
        level.onClosed?.invoke(level.result)
    }

    val canPop: Boolean get() = entries.any { !it.closing }
}

val LocalBoxNavigator = staticCompositionLocalOf<BoxNavigator?> { null }

/** The route the content is in, for its guard. */
val LocalNavLevel = compositionLocalOf<NavLevel?> { null }

/** How the content's route is shown: null on the home page. */
val LocalSheetType = compositionLocalOf<SheetType?> { null }

/** True for the first route of a form: its bar leads with close, not back. */
val LocalSheetRoot = compositionLocalOf { false }

@Composable
fun rememberBoxNavigator(): BoxNavigator {
    val scope = rememberCoroutineScope()
    return remember { BoxNavigator(scope) }
}

/** System back, the form's close and its dismissal ask first while [dirty]. */
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

/** Hosts [home], the routes over it, and the dialogs. */
@Composable
fun NavigatorHost(navigator: BoxNavigator, home: @Composable () -> Unit) {
    val dialogs = rememberDialogHost()
    CompositionLocalProvider(LocalBoxNavigator provides navigator, LocalDialogs provides dialogs) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val viewMode = viewModeOf(maxWidth)
            navigator.mobile = viewMode == ViewMode.Mobile
            BackHandler(enabled = navigator.canPop) { navigator.maybePop() }
            CompositionLocalProvider(LocalViewMode provides viewMode) {
                // Every route stays composed, each one opaque over the one under it.
                Box(Modifier.fillMaxSize()) {
                    home()
                    for (level in navigator.entries.toList()) {
                        androidx.compose.runtime.key(level) {
                            CompositionLocalProvider(LocalNavLevel provides level) {
                                when (level.sheet) {
                                    null -> PageRoute(level)
                                    SheetType.FormSheet -> FormCard(navigator, level)
                                    else -> FormPage(level)
                                }
                            }
                        }
                    }
                }
                DialogLayer(dialogs)
            }
        }
    }
}

/**
 * A destination's own navigator outside the phone layout, so pages pushed
 * from it stay in the content pane beside the navigation rail.
 */
@Composable
fun NestedNavigatorHost(navigator: BoxNavigator, active: Boolean, home: @Composable () -> Unit) {
    val parentBusy = navigator.parent?.canPop == true
    BackHandler(enabled = active && navigator.canPop && !parentBusy) { navigator.maybePop() }
    CompositionLocalProvider(LocalBoxNavigator provides navigator) {
        Box(Modifier.fillMaxSize().clipToBounds()) {
            home()
            for (level in navigator.entries.toList()) {
                androidx.compose.runtime.key(level) {
                    CompositionLocalProvider(LocalNavLevel provides level) { PageRoute(level) }
                }
            }
        }
    }
}

@Composable
private fun PageRoute(level: NavLevel) {
    CompositionLocalProvider(LocalPushedPage provides true, LocalSheetType provides SheetType.Page) {
        Box(Modifier.fillMaxSize().background(BoxTheme.scheme.surface).absorbPointer()) { level.content() }
    }
}

/** The pages of one form: its root, and the pages pushed inside it over it. */
@Composable
private fun FormPages(level: NavLevel) {
    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalSheetRoot provides true) { level.content() }
        for (child in level.children.toList()) {
            androidx.compose.runtime.key(child) {
                CompositionLocalProvider(LocalNavLevel provides child, LocalSheetRoot provides false) {
                    Box(Modifier.fillMaxSize().background(BoxTheme.scheme.surface).absorbPointer()) { child.content() }
                }
            }
        }
    }
}

@Composable
private fun FormPage(level: NavLevel) {
    CompositionLocalProvider(LocalSheetType provides SheetType.BottomSheet, LocalSurfaceLevel provides SurfaceLevel.Page) {
        Box(Modifier.fillMaxSize().background(BoxTheme.scheme.surface).absorbPointer()) { FormPages(level) }
    }
}

/** A form as a card centered over the navigator, over a scrim that dismisses it. */
@Composable
private fun FormCard(navigator: BoxNavigator, level: NavLevel) {
    Box(
        Modifier
            .fillMaxSize()
            .background(BoxTheme.scheme.scrim.copy(alpha = 0.32f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { navigator.dismissSheet(level) }
            .imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            Modifier
                .widthIn(max = 580.dp)
                .fillMaxWidth(0.9f)
                .heightIn(max = 720.dp)
                .fillMaxSize(0.85f)
                .absorbPointer(),
            shape = AppShape.card,
            color = BoxTheme.scheme.surface,
        ) {
            CompositionLocalProvider(LocalSheetType provides SheetType.FormSheet, LocalSurfaceLevel provides SurfaceLevel.Page) { FormPages(level) }
        }
    }
}

private fun Modifier.absorbPointer(): Modifier = clickable(
    interactionSource = MutableInteractionSource(),
    indication = null,
    onClick = {},
)
