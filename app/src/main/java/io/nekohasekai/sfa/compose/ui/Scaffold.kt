package io.nekohasekai.sfa.compose.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.compose.theme.AppShape
import io.nekohasekai.sfa.compose.theme.AppTextStyle
import io.nekohasekai.sfa.compose.theme.BoxTheme
import io.nekohasekai.sfa.compose.theme.SurfaceLevel

/*
 * The parts of flutter/lib/ui/scaffold.dart (CommonScaffold), sheet_header.dart
 * (FloatingHeader), button.dart (TonalButton sizes) and metrics.dart the shell
 * needs: a 72 dp bar floating over the body behind a fading scrim, a page
 * title, and 44 dp tonal action buttons 16 dp from the edge, 8 dp apart.
 */

const val PAGE_TOOLBAR_HEIGHT = 72f
private const val HEADER_OVERHANG = 16f
private const val BAR_BUTTON = 44f
private const val BAR_BUTTON_GAP = 8f
private const val BAR_ACTION_INSET = 16f
private const val MAX_GROUPED_ACTIONS = 2

class IconAction(
    val glyph: Glyph,
    val tooltip: String,
    val onClick: (() -> Unit)?,
    val selected: Boolean = false,
    val loading: Boolean = false,
)

/** What floats over the page's foot (the dock), for content to scroll clear of. */
val LocalBottomInset = staticCompositionLocalOf { 0.dp }

/** True when the page is not a top-level destination (pushed over the home page). */
val LocalPushedPage = staticCompositionLocalOf { false }

/** context.dart's contentTopPadding for a page: status bar + toolbar + 12. */
@Composable
fun contentTopPadding(): Dp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + PAGE_TOOLBAR_HEIGHT.dp + 12.dp

/** context.dart's bottomInset: the dock (or nothing) plus the system bar under it. */
@Composable
fun bottomInset(): Dp = LocalBottomInset.current + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

@Composable
fun CommonScaffold(
    title: String,
    modifier: Modifier = Modifier,
    backgroundColor: Color = SurfaceLevel.Page.colorOf(BoxTheme.scheme),
    actions: List<IconAction> = emptyList(),
    loading: Boolean = false,
    body: @Composable () -> Unit,
) {
    val navigator = LocalBoxNavigator.current
    val pushed = LocalPushedPage.current
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Box(modifier.fillMaxSize().background(backgroundColor)) {
        body()
        // FloatingHeader: the page color at 90% fading out toward the bar's foot.
        val total = top.value + PAGE_TOOLBAR_HEIGHT + HEADER_OVERHANG
        val start = top.value / (top.value + PAGE_TOOLBAR_HEIGHT) * (total - HEADER_OVERHANG) / total
        val stops = buildList {
            add(0f to backgroundColor.copy(alpha = .9f))
            for (i in 0..8) {
                val t = i / 8f
                val eased = if (t < .5f) 2 * t * t else 1 - (-2 * t + 2) * (-2 * t + 2) / 2 // Curves.easeInOut approximation
                add(start + (1 - start) * t to backgroundColor.copy(alpha = .9f * (1 - eased)))
            }
        }.toTypedArray()
        Box(Modifier.fillMaxWidth().height(top + PAGE_TOOLBAR_HEIGHT.dp + HEADER_OVERHANG.dp).background(Brush.verticalGradient(*stops)))
        Column(Modifier.fillMaxWidth()) {
            Spacer(Modifier.height(top))
            Row(Modifier.fillMaxWidth().height(PAGE_TOOLBAR_HEIGHT.dp), verticalAlignment = Alignment.CenterVertically) {
                if (pushed && navigator != null) {
                    Spacer(Modifier.width(BAR_ACTION_INSET.dp))
                    TonalBarButton(IconAction(AppGlyphs.back, tr("Back"), { navigator.pop() }))
                    Spacer(Modifier.width(BAR_ACTION_INSET.dp))
                } else {
                    Spacer(Modifier.width(16.dp))
                }
                Text(
                    title,
                    Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = (if (pushed) AppTextStyle.Headline.of() else AppTextStyle.PageTitle.of()).copy(color = BoxTheme.scheme.onSurface),
                )
                BarActions(actions)
                Spacer(Modifier.width(BAR_ACTION_INSET.dp))
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun BarActions(actions: List<IconAction>) {
    if (actions.isEmpty()) return
    val grouped = if (actions.size > 1) actions.take(MAX_GROUPED_ACTIONS) else emptyList()
    Row(horizontalArrangement = Arrangement.spacedBy(BAR_BUTTON_GAP.dp), verticalAlignment = Alignment.CenterVertically) {
        if (grouped.isNotEmpty()) {
            // TonalButtonGroup: buttons sharing one filled pill.
            Row(Modifier.clip(AppShape.full).background(SurfaceLevel.Card.colorOf(BoxTheme.scheme))) {
                for (action in grouped) TonalBarButton(action, filled = false)
            }
        }
        for (action in actions.drop(grouped.size)) TonalBarButton(action)
    }
}

/** button.dart's TonalButton at the bar size: 44 dp, a 24 dp glyph on the raised surface. */
@Composable
fun TonalBarButton(action: IconAction, filled: Boolean = true) {
    val scheme = BoxTheme.scheme
    val background = when {
        action.selected -> scheme.primary
        filled -> SurfaceLevel.Card.colorOf(scheme)
        else -> Color.Transparent
    }
    val foreground = if (action.selected) scheme.onPrimary else scheme.onSurface
    Box(
        Modifier
            .size(BAR_BUTTON.dp)
            .elasticPress(action.onClick != null && !action.loading)
            .clip(AppShape.full)
            .background(background)
            .clickable(enabled = action.onClick != null && !action.loading) { action.onClick?.invoke() }
            .semanticsLabel(action.tooltip),
        contentAlignment = Alignment.Center,
    ) {
        if (action.loading) {
            androidx.compose.material3.CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = foreground)
        } else {
            GlyphIcon(action.glyph, size = 24.dp, color = if (action.onClick == null) foreground.copy(alpha = .38f) else foreground)
        }
    }
}

/** widgets.dart's BoxPage: a scaffold whose body is a padded list. */
@Composable
fun BoxPage(
    title: String,
    actions: List<IconAction> = emptyList(),
    loading: Boolean = false,
    content: @Composable () -> Unit,
) {
    CommonScaffold(title = tr(title), actions = actions, loading = loading) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(PaddingValues(start = 16.dp, end = 16.dp, top = contentTopPadding(), bottom = 24.dp + bottomInset())),
        ) { content() }
    }
}

// ---------------------------------------------------------------------------
// A page stack for pages opened over the home page (showExtend / showFormSheet)
// ---------------------------------------------------------------------------

class BoxNavigator {
    val pages = mutableStateListOf<@Composable () -> Unit>()
    fun push(page: @Composable () -> Unit) {
        pages.add(page)
    }
    fun pop() {
        if (pages.isNotEmpty()) pages.removeAt(pages.lastIndex)
    }
}

val LocalBoxNavigator = staticCompositionLocalOf<BoxNavigator?> { null }

/**
 * Hosts [home] and the pushed pages over it with the shared-axis X transition
 * (commonSharedXPageTransitions, 300 ms) and the system back gesture.
 */
@Composable
fun NavigatorHost(navigator: BoxNavigator, home: @Composable () -> Unit) {
    CompositionLocalProvider(LocalBoxNavigator provides navigator) {
        BackHandler(enabled = navigator.pages.isNotEmpty()) { navigator.pop() }
        val depth = navigator.pages.size
        AnimatedContent(
            targetState = depth,
            transitionSpec = {
                val forward = targetState > initialState
                val distance = 30
                (
                    slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { (if (forward) distance else -distance) * it / 100 } +
                        fadeIn(tween(210, 90))
                    ) togetherWith (
                    slideOutHorizontally(tween(300, easing = FastOutSlowInEasing)) { (if (forward) -distance else distance) * it / 100 } +
                        fadeOut(tween(90))
                    )
            },
            label = "navigator",
        ) { level ->
            if (level == 0) {
                home()
            } else {
                val page = navigator.pages.getOrNull(level - 1)
                if (page != null) {
                    CompositionLocalProvider(LocalPushedPage provides true, LocalBottomInset provides 0.dp) {
                        Box(Modifier.fillMaxSize().background(SurfaceLevel.Page.colorOf(BoxTheme.scheme))) { page() }
                    }
                }
            }
        }
    }
}

@Composable
fun rememberBoxNavigator(): BoxNavigator = remember { BoxNavigator() }

@Suppress("unused")
private fun Modifier.maxContentWidth(width: Dp) = this.widthIn(max = width)
