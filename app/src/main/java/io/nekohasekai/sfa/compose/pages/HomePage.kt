package io.nekohasekai.sfa.compose.pages

import io.nekohasekai.sfa.compose.ui.glassBackdropSource
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.nekohasekai.sfa.compose.BoxController
import androidx.compose.foundation.background
import io.nekohasekai.sfa.compose.theme.BoxTheme
import io.nekohasekai.sfa.compose.theme.SurfaceLevel
import io.nekohasekai.sfa.compose.ui.AnimatedGlyph
import io.nekohasekai.sfa.compose.ui.AppGlyphs
import io.nekohasekai.sfa.compose.ui.DockDestination
import io.nekohasekai.sfa.compose.ui.LocalBottomInset
import io.nekohasekai.sfa.compose.ui.LocalBoxNavigator
import io.nekohasekai.sfa.compose.ui.NavigationDock
import io.nekohasekai.sfa.compose.ui.BoxNavigator
import io.nekohasekai.sfa.compose.ui.LocalViewMode
import io.nekohasekai.sfa.compose.ui.NestedNavigatorHost
import io.nekohasekai.sfa.compose.ui.SidebarDestination
import io.nekohasekai.sfa.compose.ui.SidebarLayout
import io.nekohasekai.sfa.compose.ui.ViewMode
import io.nekohasekai.sfa.compose.ui.canExpandSidebar
import io.nekohasekai.sfa.compose.ui.tr
import kotlinx.coroutines.launch

/** components/page_switcher.dart's PageActivityScope. */
val LocalPageActive = staticCompositionLocalOf { true }

private val EaseInOut = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
private val EaseOut = CubicBezierEasing(0f, 0f, 0.58f, 1f)

/** navigation.dart's NavigationItem: a destination and where it shows. */
private class HomeDestination(val label: String, val glyph: io.nekohasekai.sfa.compose.ui.Glyph, val core: Boolean)

/** Shown on the phone dock and bar; the sidebar adds Requests and Logs (NavigationItemMode.desktop). */
private val mobileDestinations = listOf(
    HomeDestination("Dashboard", AppGlyphs.dashboard, true),
    HomeDestination("Proxies", AppGlyphs.proxies, true),
    HomeDestination("Profiles", AppGlyphs.profiles, true),
    HomeDestination("Tools", AppGlyphs.tools, true),
)

/**
 * The destinations of [mode]: FlClash v0.9.3 lib/views/navigation.dart. Requests
 * is the merged requests page (recent, running, captured) on every core; its
 * Captured feed is greyed on Xray/mihomo.
 */
@Composable
private fun destinationsOf(@Suppress("UNUSED_PARAMETER") box: BoxController, mode: ViewMode): List<HomeDestination> {
    if (mode == ViewMode.Mobile) return mobileDestinations
    return buildList {
        addAll(mobileDestinations.take(3))
        add(HomeDestination("Requests", AppGlyphs.requests, false))
        add(HomeDestination("Logs", AppGlyphs.logs, false))
        add(mobileDestinations.last())
    }
}

/**
 * main.dart's HomePage: Dashboard, Proxies, Profiles and Tools, switched by
 * the floating dock or a Material bar on phones, and by the FlClash sidebar
 * (with Requests and Logs) at laptop and desktop widths, where each
 * destination keeps its own navigator; the page switcher's slide (300 ms) or
 * fade (220 ms) transition.
 */
@Composable
fun HomePage(box: BoxController) {
    val viewMode = LocalViewMode.current
    val mobile = viewMode == ViewMode.Mobile
    val items = destinationsOf(box, viewMode)
    var current by rememberSaveable { mutableStateOf(items.first().label) }
    var previousLabel by remember { mutableStateOf<String?>(null) }
    // A destination the layout no longer shows (Requests, Logs on a phone) falls back to Tools.
    val index = items.indexOfFirst { it.label == current }.takeIf { it >= 0 } ?: items.lastIndex
    val previous = previousLabel?.let { label -> items.indexOfFirst { it.label == label }.takeIf { it >= 0 } }
    val motion = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    val appearance = io.nekohasekai.sfa.compose.theme.LocalAppearance.current
    // The minimal style always cross-fades destinations, briefly.
    val fade = !appearance.expressiveMotion || box.preferences["tabAnimation"] == "fade"
    val floating = box.preferences["floatingNavigation"] != false
    val navigator = LocalBoxNavigator.current
    val nested = remember(navigator) { mutableMapOf<String, BoxNavigator>() }
    val importedMessage = tr("Imported %d profiles")
    val select: (Int) -> Unit = { next ->
        if (next != index) {
            previousLabel = items[index].label
            current = items[next].label
            scope.launch {
                motion.snapTo(0f)
                motion.animateTo(1f, tween(if (!appearance.expressiveMotion) appearance.fadeMillis else if (fade) 220 else 300, easing = if (fade) EaseInOut else EaseOut))
                previousLabel = null
            }
        }
    }
    // Drains imports and shares (main.dart _drainIntents).
    LaunchedEffect(box.ready) {
        if (!box.ready) return@LaunchedEffect
        suspend fun drain() {
            while (box.pendingIntents.isNotEmpty()) {
                val uri = box.pendingIntents.removeFirst()
                try {
                    if (uri.startsWith("bbox-share:")) {
                        navigator?.push { SharePage(box, uri.removePrefix("bbox-share:")) }
                        continue
                    }
                    val data = io.nekohasekai.sfa.compose.json(box.call("profiles.decodeIntent", mapOf("uri" to uri)))
                    openImported(box, navigator, data, importedMessage)
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (error: Exception) {
                    box.report(error)
                }
            }
        }
        drain()
        box.intents.collect { drain() }
    }
    val pages: @Composable () -> Unit = {
        val progress = motion.value
        val direction = if (previous == null || index >= previous) 1f else -1f
        items.forEachIndexed { i, item ->
            val active = i == index
            val shown = active || i == previous
            // Requests and Logs subscribe to live feeds, so they are composed only while shown.
            if (item.core || shown) androidx.compose.runtime.key(item.label) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .zIndex(if (active) 1f else 0f)
                        .graphicsLayer {
                            alpha = when {
                                !shown -> 0f
                                fade && previous != null -> if (active) progress else 1 - progress
                                else -> 1f
                            }
                            translationX = if (fade || previous == null || !shown) {
                                0f
                            } else if (active) {
                                size.width * direction * (1 - progress)
                            } else {
                                -size.width * direction * progress
                            }
                        }
                        .then(if (active) Modifier else Modifier.blockInput()),
                ) {
                    CompositionLocalProvider(LocalPageActive provides active) {
                        val stack = navigator?.let { root -> nested.getOrPut(item.label) { BoxNavigator(scope, root) } }
                        if (stack != null && (!mobile || stack.canPop)) {
                            NestedNavigatorHost(stack, active) { DestinationPage(box, item.label) }
                        } else {
                            DestinationPage(box, item.label)
                        }
                    }
                }
            }
        }
    }
    if (!mobile) {
        val canExpand = canExpandSidebar(viewMode)
        val expanded = canExpand && box.preferences["sidebarExpanded"] != false
        SidebarLayout(
            destinations = items.map { SidebarDestination(it.glyph, tr(it.label)) },
            selectedIndex = index,
            expanded = expanded,
            onSelected = select,
            onToggle = if (canExpand) ({ scope.launch { box.setPreference("sidebarExpanded", !expanded) } }) else null,
        ) {
            CompositionLocalProvider(LocalBottomInset provides 0.dp) { Box(Modifier.fillMaxSize()) { pages() } }
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val inset = if (floating) NavigationDock.inset() else 0.dp
            val pageBackdrop = io.nekohasekai.sfa.compose.ui.rememberGlassBackdrop()
            val glassOn = io.nekohasekai.sfa.compose.ui.LocalLiquidGlass.current.shaded && floating
            Box(Modifier.fillMaxSize().glassBackdropSource(pageBackdrop, glassOn).then(if (glassOn) Modifier.background(SurfaceLevel.Page.colorOf(BoxTheme.scheme)) else Modifier)) {
                CompositionLocalProvider(LocalBottomInset provides inset) { pages() }
            }
            if (floating) {
                NavigationDock(
                    backdrop = if (glassOn) pageBackdrop else null,
                    destinations = items.map { DockDestination(it.glyph, tr(it.label)) },
                    selectedIndex = index,
                    onSelected = select,
                    modifier = Modifier.align(Alignment.BottomCenter).zIndex(2f),
                )
            }
        }
        if (!floating) {
            val scheme = BoxTheme.scheme
            val floatingLevel = SurfaceLevel.floatingOf(scheme)
            NavigationBar(containerColor = floatingLevel.colorOf(scheme)) {
                items.forEachIndexed { i, item ->
                    NavigationBarItem(
                        selected = i == index,
                        onClick = { select(i) },
                        icon = { AnimatedGlyph(item.glyph, filled = i == index) },
                        label = { Text(tr(item.label)) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = scheme.primary,
                            unselectedIconColor = scheme.onSurfaceVariant,
                            indicatorColor = floatingLevel.next.colorOf(scheme),
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun DestinationPage(box: BoxController, label: String) {
    when (label) {
        "Dashboard" -> DashboardPage(box)
        "Proxies" -> ProxiesPage(box)
        "Profiles" -> ProfilesPage(box)
        "Requests" -> RequestsPage(box)
        "Logs" -> LogsPage(box)
        else -> ToolsPage(box)
    }
}

private fun Modifier.blockInput(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }
}
