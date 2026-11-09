package io.nekohasekai.sfa.compose.pages

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import io.nekohasekai.sfa.compose.BoxController
import io.nekohasekai.sfa.compose.theme.BoxTheme
import io.nekohasekai.sfa.compose.ui.AppIcons
import io.nekohasekai.sfa.compose.ui.BoxNavigator
import io.nekohasekai.sfa.compose.ui.LocalBoxNavigator
import io.nekohasekai.sfa.compose.ui.LocalNavigationBarHandled
import io.nekohasekai.sfa.compose.ui.LocalViewMode
import io.nekohasekai.sfa.compose.ui.NestedNavigatorHost
import io.nekohasekai.sfa.compose.ui.ViewMode
import io.nekohasekai.sfa.compose.ui.tr

/** Whether the page the content belongs to is the one shown. */
val LocalPageActive = staticCompositionLocalOf { true }

private class HeldValue {
    var set = false
    var value: Any? = null
}

/**
 * [read] while the enclosing page is shown; while it is hidden, the last value
 * read, without observing [read]'s state. Core pages stay composed behind the
 * shown one, so a per-second value read this way no longer recomposes a hidden page.
 */
@Suppress("UNCHECKED_CAST")
@Composable
fun <T> whileActive(read: () -> T): T {
    val held = remember { HeldValue() }
    val active = LocalPageActive.current
    if (!active && held.set) return held.value as T
    val value = if (active) read() else androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation(read)
    held.value = value
    held.set = true
    return value
}

/**
 * [whileActive] for the draw phase: a reader to call from a draw or layer lambda,
 * so a live value invalidates only drawing, and nothing while the page is hidden.
 */
@Suppress("UNCHECKED_CAST")
@Composable
fun <T> activeReader(read: () -> T): () -> T {
    val held = remember { HeldValue() }
    val active = LocalPageActive.current
    val latest = androidx.compose.runtime.rememberUpdatedState(read)
    return remember(active) {
        if (active) {
            { latest.value().also { held.value = it; held.set = true } }
        } else {
            { if (held.set) held.value as T else androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation { latest.value() } }
        }
    }
}

/** A destination: its label, its icon and whether it stays composed while hidden. */
private class HomeDestination(val label: String, val icon: ImageVector, val core: Boolean)

/** The phone's bottom destinations; the navigation rail adds Requests and Logs. */
private val mobileDestinations = listOf(
    HomeDestination("Dashboard", AppIcons.dashboard, true),
    HomeDestination("Proxies", AppIcons.proxies, true),
    HomeDestination("Profiles", AppIcons.profiles, true),
    HomeDestination("Tools", AppIcons.tools, true),
)

private fun destinationsOf(mode: ViewMode): List<HomeDestination> {
    if (mode == ViewMode.Mobile) return mobileDestinations
    return buildList {
        addAll(mobileDestinations.take(3))
        add(HomeDestination("Requests", AppIcons.requests, false))
        add(HomeDestination("Logs", AppIcons.logs, false))
        add(mobileDestinations.last())
    }
}

/**
 * The home page: Dashboard, Proxies, Profiles and Tools on a Material 3
 * navigation bar on phones, and a navigation rail (with Requests and Logs)
 * on wider windows, where each destination keeps its own navigator. A
 * destination change is instant.
 */
@Composable
fun HomePage(box: BoxController) {
    val viewMode = LocalViewMode.current
    val mobile = viewMode == ViewMode.Mobile
    val items = destinationsOf(viewMode)
    var current by rememberSaveable { mutableStateOf(items.first().label) }
    // A destination the layout no longer shows (Requests, Logs on a phone) falls back to Tools.
    val index = items.indexOfFirst { it.label == current }.takeIf { it >= 0 } ?: items.lastIndex
    val scope = rememberCoroutineScope()
    val navigator = LocalBoxNavigator.current
    val nested = remember(navigator) { mutableMapOf<String, BoxNavigator>() }
    val importedMessage = tr("Imported %d profiles")
    val select: (Int) -> Unit = { next -> current = items[next].label }
    // Drains imports and shares.
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
                    val data = io.nekohasekai.sfa.compose.json(box.call { decodeProfileIntent(uri) })
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
        Box(Modifier.fillMaxSize()) {
            items.forEachIndexed { i, item ->
                val active = i == index
                // Requests and Logs subscribe to live feeds, so they are composed only while shown.
                if (item.core || active) androidx.compose.runtime.key(item.label) {
                    Box(Modifier.fillMaxSize().zIndex(if (active) 1f else 0f).then(if (active) Modifier else Modifier.blockInput())) {
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
    }
    if (!mobile) {
        Row(Modifier.fillMaxSize()) {
            NavigationRail(containerColor = BoxTheme.scheme.surface) {
                items.forEachIndexed { i, item ->
                    NavigationRailItem(
                        selected = i == index,
                        onClick = { select(i) },
                        icon = { Icon(item.icon, contentDescription = null) },
                        label = { Text(tr(item.label)) },
                    )
                }
            }
            VerticalDivider(Modifier.fillMaxHeight())
            Box(Modifier.weight(1f).fillMaxHeight()) { pages() }
        }
        return
    }
    Scaffold(
        bottomBar = {
            NavigationBar {
                items.forEachIndexed { i, item ->
                    NavigationBarItem(
                        selected = i == index,
                        onClick = { select(i) },
                        icon = { Icon(item.icon, contentDescription = null) },
                        label = { Text(tr(item.label)) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding())) {
            CompositionLocalProvider(LocalNavigationBarHandled provides true) { pages() }
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
