package io.nekohasekai.sfa.compose.pages

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import io.nekohasekai.sfa.compose.theme.BoxTheme
import io.nekohasekai.sfa.compose.theme.SurfaceLevel
import io.nekohasekai.sfa.compose.ui.AnimatedGlyph
import io.nekohasekai.sfa.compose.ui.AppGlyphs
import io.nekohasekai.sfa.compose.ui.BoxPage
import io.nekohasekai.sfa.compose.ui.CommonScaffold
import io.nekohasekai.sfa.compose.ui.DockDestination
import io.nekohasekai.sfa.compose.ui.EmptyState
import io.nekohasekai.sfa.compose.ui.GlyphIcon
import io.nekohasekai.sfa.compose.ui.LocalBottomInset
import io.nekohasekai.sfa.compose.ui.LocalBoxNavigator
import io.nekohasekai.sfa.compose.ui.NavigationDock
import io.nekohasekai.sfa.compose.ui.bottomInset
import io.nekohasekai.sfa.compose.ui.contentTopPadding
import io.nekohasekai.sfa.compose.ui.tr
import kotlinx.coroutines.launch

/** components/page_switcher.dart's PageActivityScope. */
val LocalPageActive = staticCompositionLocalOf { true }

private val EaseInOut = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
private val EaseOut = CubicBezierEasing(0f, 0f, 0.58f, 1f)

/**
 * main.dart's HomePage: Dashboard, Proxies, Profiles and Tools, switched by
 * the floating dock (or a Material bar / rail), with the page switcher's
 * slide (300 ms) or fade (220 ms) transition.
 */
@Composable
fun HomePage(box: BoxController) {
    val items = listOf("Dashboard" to AppGlyphs.dashboard, "Proxies" to AppGlyphs.proxies, "Profiles" to AppGlyphs.profiles, "Tools" to AppGlyphs.tools)
    var index by rememberSaveable { mutableIntStateOf(0) }
    var previous by remember { mutableStateOf<Int?>(null) }
    val motion = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    val fade = box.preferences["tabAnimation"] == "fade"
    val floating = box.preferences["floatingNavigation"] != false
    val navigator = LocalBoxNavigator.current
    val select: (Int) -> Unit = { next ->
        if (next != index) {
            previous = index
            index = next
            scope.launch {
                motion.snapTo(0f)
                motion.animateTo(1f, tween(if (fade) 220 else 300, easing = if (fade) EaseInOut else EaseOut))
                previous = null
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
                    navigator?.openForm { ProfileForm(box, data) }
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
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val mobile = maxWidth < 600.dp
        Row(Modifier.fillMaxSize()) {
            if (!mobile) {
                NavigationRail(containerColor = SurfaceLevel.Page.colorOf(BoxTheme.scheme)) {
                    items.forEachIndexed { i, (label, glyph) ->
                        NavigationRailItem(
                            selected = i == index,
                            onClick = { select(i) },
                            icon = { GlyphIcon(glyph) },
                            label = { Text(tr(label)) },
                            colors = NavigationRailItemDefaults.colors(indicatorColor = SurfaceLevel.Block.colorOf(BoxTheme.scheme)),
                        )
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    val inset = if (mobile && floating) NavigationDock.inset() else 0.dp
                    CompositionLocalProvider(LocalBottomInset provides inset) {
                        val progress = motion.value
                        val direction = if (previous == null || index >= (previous ?: 0)) 1f else -1f
                        for (i in items.indices) {
                            val active = i == index
                            val shown = active || i == previous
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
                                    when (i) {
                                        0 -> DashboardPage(box)
                                        1 -> ProxiesPage(box)
                                        2 -> ProfilesPage(box)
                                        else -> ToolsPage(box)
                                    }
                                }
                            }
                        }
                    }
                    if (mobile && floating) {
                        NavigationDock(
                            destinations = items.map { DockDestination(it.second, tr(it.first)) },
                            selectedIndex = index,
                            onSelected = select,
                            modifier = Modifier.align(Alignment.BottomCenter).zIndex(2f),
                        )
                    }
                }
                if (mobile && !floating) {
                    val scheme = BoxTheme.scheme
                    val floatingLevel = SurfaceLevel.floatingOf(scheme)
                    NavigationBar(containerColor = floatingLevel.colorOf(scheme)) {
                        items.forEachIndexed { i, (label, glyph) ->
                            NavigationBarItem(
                                selected = i == index,
                                onClick = { select(i) },
                                icon = { AnimatedGlyph(glyph, filled = i == index) },
                                label = { Text(tr(label)) },
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
    }
}

private fun Modifier.blockInput(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }
}
