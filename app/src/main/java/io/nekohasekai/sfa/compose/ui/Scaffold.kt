package io.nekohasekai.sfa.compose.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.compose.theme.AppShape
import io.nekohasekai.sfa.compose.theme.AppTextStyle
import io.nekohasekai.sfa.compose.theme.BoxTheme
import io.nekohasekai.sfa.compose.theme.SurfaceLevel
import kotlinx.coroutines.launch
import kotlin.math.max

/*
 * flutter/lib/ui/scaffold.dart's CommonScaffold: a page's 72 dp bar floats
 * over the body behind a fading header (sheet_header.dart's FloatingHeader);
 * a sheet's bar is a drag handle, a 48 dp toolbar and a 10 dp trail (68 dp).
 * At most three buttons stand in the bar (the search lead, the primary and
 * icon actions, the confirmation); past that they fold into the overflow
 * menu with the menu items. The first two share a TonalButtonGroup. Search
 * is a button that takes the bar over, or on a pushed phone page and in a
 * sheet a field docked over the foot.
 */

const val PAGE_TOOLBAR_HEIGHT = 72f
const val SHEET_TOOLBAR_HEIGHT = 48f
const val SHEET_APP_BAR_HEIGHT = 68f
private const val DRAG_HANDLE_HEIGHT = 10f
private const val HEADER_OVERHANG = 16f
private const val BAR_BUTTON_GAP = 8f
private const val MAX_BAR_BUTTONS = 3
private const val MAX_GROUPED_ACTIONS = 2
const val DOCKED_SEARCH_HEIGHT = 48f
const val DOCKED_SEARCH_MARGIN = 16f
const val DOCKED_SEARCH_INSET = DOCKED_SEARCH_MARGIN + DOCKED_SEARCH_HEIGHT
private const val SEARCH_FIELD_GAP = 12f

/** IconButtonData. */
class IconAction(
    val glyph: Glyph,
    val tooltip: String,
    val onClick: (() -> Unit)?,
    val selected: Boolean = false,
    val loading: Boolean = false,
)

/** AppBarSearchState: what the field reports as it changes. */
class SearchState(val onSearch: (String) -> Unit)

/** What floats over the page's foot (the dock), for content to scroll clear of. */
val LocalBottomInset = staticCompositionLocalOf { 0.dp }

/** True when the page is not a top-level destination (pushed over the home page). */
val LocalPushedPage = staticCompositionLocalOf { false }

/** FloatingBarScope / TopInsetScope as a scaffold hands them to its body. */
private val LocalBarInset = staticCompositionLocalOf<Dp?> { null }

/** context.appBarInset: where the bar ends. */
@Composable
fun appBarInset(): Dp = LocalBarInset.current ?: if (LocalSheetType.current.isCompactSheet) {
    SHEET_APP_BAR_HEIGHT.dp
} else {
    WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + PAGE_TOOLBAR_HEIGHT.dp
}

private val SheetType?.isCompactSheet: Boolean get() = this == SheetType.BottomSheet || this == SheetType.FormSheet

/** context.contentTopPadding: a compact sheet's header already trails its own gap. */
@Composable
fun contentTopPadding(): Dp = if (LocalSheetType.current.isCompactSheet) SHEET_APP_BAR_HEIGHT.dp else appBarInset() + 12.dp

/** context.sectionsTopPadding: for a page that opens on a section. */
@Composable
fun sectionsTopPadding(): Dp = max(0f, (contentTopPadding() - 24.dp).value).dp

/** context.bottomInset: the dock (or a docked search) plus the system bar under it. */
@Composable
fun bottomInset(): Dp = LocalBottomInset.current + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

private fun foldBarActions(
    hasLead: Boolean,
    primary: IconAction?,
    icons: List<IconAction>,
    confirmation: IconAction?,
    widgetCount: Int,
    menuItems: List<PopupMenuItem>,
): Pair<List<IconAction>, List<PopupMenuItem>> {
    val foldable = listOfNotNull(primary) + icons
    val fixed = (if (hasLead) 1 else 0) + widgetCount + (if (confirmation == null) 0 else 1)
    if (fixed + foldable.size + (if (menuItems.isEmpty()) 0 else 1) <= MAX_BAR_BUTTONS) {
        return (foldable + listOfNotNull(confirmation)) to menuItems
    }
    val slots = MAX_BAR_BUTTONS - 1 - fixed
    val kept = foldable.take(max(0, slots))
    return (kept + listOfNotNull(confirmation)) to (
        foldable.filter { it !in kept }.map {
            PopupMenuItem(label = it.tooltip, glyph = it.glyph, checked = it.selected, onPressed = if (it.loading) null else it.onClick)
        } + menuItems
        )
}

@Composable
fun CommonScaffold(
    title: String,
    modifier: Modifier = Modifier,
    backgroundColor: Color = SurfaceLevel.Page.colorOf(BoxTheme.scheme),
    actions: List<@Composable () -> Unit> = emptyList(),
    primaryAction: IconAction? = null,
    iconActions: List<IconAction> = emptyList(),
    menuItems: List<PopupMenuItem> = emptyList(),
    confirmationAction: IconAction? = null,
    searchState: SearchState? = null,
    searchInBar: Boolean = false,
    canSort: Boolean = false,
    sorting: Boolean = false,
    onSortingChange: ((Boolean) -> Unit)? = null,
    loading: Boolean = false,
    body: @Composable () -> Unit,
) {
    val navigator = LocalBoxNavigator.current
    val sheet = LocalSheetType.current
    val compact = sheet.isCompactSheet
    val pushed = LocalPushedPage.current && !compact
    val sheetRoot = LocalSheetRoot.current
    val mobile = navigator?.mobile ?: true
    val docked = searchState != null && (compact || (!searchInBar && pushed && mobile))
    val barSearch = searchState != null && !docked
    var query by remember { mutableStateOf<String?>(null) }
    var dockedText by remember { mutableStateOf("") }
    val searchMotion = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val exitSearch = {
        if (query != null) {
            query = null
            searchState?.onSearch("")
            focus.clearFocus()
            scope.launch { searchMotion.animateTo(0f, spring(dampingRatio = 0.75f, stiffness = 600f)) }
        }
    }
    BackHandler(enabled = query != null || sorting) {
        exitSearch()
        if (sorting) onSortingChange?.invoke(false)
    }
    val top = if (compact) 0.dp else WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val toolbar = if (compact) SHEET_TOOLBAR_HEIGHT else PAGE_TOOLBAR_HEIGHT
    val headerHeight = if (compact) SHEET_APP_BAR_HEIGHT.dp else top + PAGE_TOOLBAR_HEIGHT.dp
    val inset = LocalBottomInset.current
    Box(modifier.fillMaxSize().background(backgroundColor)) {
        CompositionLocalProvider(
            LocalBarInset provides headerHeight,
            LocalSorting provides sorting,
            LocalBottomInset provides if (docked && !sorting) inset + DOCKED_SEARCH_INSET.dp else inset,
        ) { body() }
        // FloatingHeader: the page color at 90% fading out toward the bar's foot.
        val total = headerHeight.value + HEADER_OVERHANG
        val fadeStart = if (compact) 0.5f else top.value / (top.value + PAGE_TOOLBAR_HEIGHT)
        val start = (total - HEADER_OVERHANG) * fadeStart / total
        val stops = buildList {
            add(0f to backgroundColor.copy(alpha = .9f))
            for (i in 0..8) {
                val t = i / 8f
                val eased = if (t < .5f) 4 * t * t * t else 1 - (-2 * t + 2).let { it * it * it } / 2
                add(start + (1 - start) * t to backgroundColor.copy(alpha = .9f * (1 - eased)))
            }
        }.toTypedArray()
        val sheetController = LocalSheetController.current
        Box(
            Modifier
                .fillMaxWidth()
                .height(headerHeight + HEADER_OVERHANG.dp)
                .background(Brush.verticalGradient(*stops)),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .then(
                    if (sheet == SheetType.BottomSheet && sheetController != null) {
                        Modifier.draggable(
                            rememberDraggableState { delta -> sheetController.dragBy(-delta) },
                            Orientation.Vertical,
                            onDragStarted = { sheetController.startDrag() },
                            onDragStopped = { velocity -> sheetController.dragEnd(-velocity) },
                        )
                    } else {
                        Modifier
                    },
                ),
        ) {
            if (compact) {
                if (sheet == SheetType.BottomSheet) SheetDragHandle() else Spacer(Modifier.height(DRAG_HANDLE_HEIGHT.dp))
            } else {
                Spacer(Modifier.height(top))
            }
            Box(Modifier.fillMaxWidth().height(toolbar.dp)) {
                val motion = searchMotion.value
                Row(
                    Modifier.fillMaxSize().graphicsLayer { alpha = if (barSearch) (1 - motion * 2).coerceIn(0f, 1f) else 1f },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val leading: IconAction? = when {
                        compact && navigator != null -> if (sheetRoot) {
                            IconAction(AppGlyphs.close, tr("Close"), { LocalNavLevelHolder.dismiss(navigator) })
                        } else {
                            IconAction(AppGlyphs.back, tr("Back"), { navigator.maybePop() })
                        }
                        pushed && navigator != null -> IconAction(AppGlyphs.back, tr("Back"), { navigator.maybePop() })
                        else -> null
                    }
                    if (leading != null) {
                        Spacer(Modifier.width(16.dp))
                        TonalIconButton(leading)
                        Spacer(Modifier.width(16.dp))
                    } else {
                        Spacer(Modifier.width(16.dp))
                    }
                    Text(
                        title,
                        Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = (if (pushed || compact) AppTextStyle.Headline.of() else AppTextStyle.PageTitle.of()).copy(color = BoxTheme.scheme.onSurface),
                    )
                    if (sorting) {
                        Row(horizontalArrangement = Arrangement.spacedBy(BAR_BUTTON_GAP.dp)) {
                            if (canSort) TonalIconButton(IconAction(AppGlyphs.check, tr("Done"), { onSortingChange?.invoke(false) }, selected = true))
                        }
                    } else {
                        val lead = if (barSearch) {
                            IconAction(AppGlyphs.search, tr("Search"), {
                                query = ""
                                scope.launch { searchMotion.animateTo(1f, spring(dampingRatio = 0.75f, stiffness = 390f)) }
                            })
                        } else {
                            null
                        }
                        val icons = (if (canSort) listOf(IconAction(AppGlyphs.sort, tr("Reorder"), { onSortingChange?.invoke(true) })) else emptyList()) + iconActions
                        val (shown, overflow) = foldBarActions(lead != null, primaryAction, icons, confirmationAction, actions.size, menuItems)
                        BarActions(listOfNotNull(lead) + shown, actions, overflow)
                    }
                    Spacer(Modifier.width(16.dp))
                }
                if (barSearch && (query != null || motion > 0f)) {
                    BarSearchField(
                        motion = motion,
                        onChanged = {
                            query = it
                            searchState?.onSearch(it)
                        },
                        onCancel = exitSearch,
                    )
                }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (docked && !sorting) {
            DockedSearch(
                text = dockedText,
                onChanged = {
                    dockedText = it
                    searchState?.onSearch(it)
                },
                onCancel = {
                    dockedText = ""
                    searchState?.onSearch("")
                    focus.clearFocus()
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/** Closing a sheet from its bar goes through its guard, as SheetProvider.onClose does. */
private object LocalNavLevelHolder {
    fun dismiss(navigator: BoxNavigator) = navigator.maybePop()
}

@Composable
private fun SheetDragHandle() {
    Box(Modifier.fillMaxWidth().height(DRAG_HANDLE_HEIGHT.dp), contentAlignment = Alignment.BottomCenter) {
        Box(Modifier.size(28.dp, 4.dp).background(BoxTheme.scheme.onSurfaceVariant, AppShape.all(2f)))
    }
}

@Composable
private fun BarActions(shown: List<IconAction>, widgets: List<@Composable () -> Unit>, overflow: List<PopupMenuItem>) {
    if (shown.isEmpty() && widgets.isEmpty() && overflow.isEmpty()) return
    val grouped = if (shown.size > 1) shown.take(MAX_GROUPED_ACTIONS) else emptyList()
    Row(horizontalArrangement = Arrangement.spacedBy(BAR_BUTTON_GAP.dp), verticalAlignment = Alignment.CenterVertically) {
        if (grouped.isNotEmpty()) {
            TonalButtonGroup {
                for (action in grouped) TonalIconButton(action, grouped = true)
            }
        }
        for (action in shown.drop(grouped.size)) TonalIconButton(action)
        for (widget in widgets) Box(Modifier.elasticPress()) { widget() }
        if (overflow.isNotEmpty()) {
            CommonPopupBox(overflow) { open -> TonalIconButton(IconAction(AppGlyphs.more, tr("More"), open)) }
        }
    }
}

/** _BarSearch: the field grows out of the search button across the bar, with a cancel at its end. */
@Composable
private fun BarSearchField(motion: Float, onChanged: (String) -> Unit, onCancel: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentAlignment = Alignment.CenterEnd) {
        val button = TonalButtonSize.Bar.button.dp
        val full = maxWidth - button - SEARCH_FIELD_GAP.dp
        val width = button + (full - button) * motion.coerceIn(0f, 1.05f)
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .offset { IntOffset(((full - width).toPx()).toInt(), 0) }
                .width(width)
                .elasticPress(),
        ) {
            SearchField(
                text = text,
                onChanged = {
                    text = it
                    onChanged(it)
                },
                focusRequester = focus,
                fill = raisedSurfaceLevel.colorOf(BoxTheme.scheme),
                height = button,
                modifier = Modifier.graphicsLayer { alpha = ((motion - 0.2f) / 0.6f).coerceIn(0f, 1f) },
            )
        }
        Box(Modifier.graphicsLayer { alpha = ((motion - 0.5f) / 0.4f).coerceIn(0f, 1f); scaleX = motion.coerceIn(0f, 1f); scaleY = motion.coerceIn(0f, 1f) }) {
            TonalIconButton(IconAction(AppGlyphs.close, tr("Cancel"), onCancel))
        }
    }
}

/** _buildDockedSearch: a floating pill over the foot; its cancel grows out of the field's end in use. */
@Composable
private fun DockedSearch(text: String, onChanged: (String) -> Unit, onCancel: () -> Unit, modifier: Modifier) {
    val scheme = BoxTheme.scheme
    var focused by remember { mutableStateOf(false) }
    val active = focused || text.isNotEmpty()
    val t by animateFloatAsState(if (active) 1f else 0f, spring(dampingRatio = 0.75f, stiffness = 390f), label = "docked")
    val height = DOCKED_SEARCH_HEIGHT.dp
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Box(
        modifier
            .fillMaxWidth()
            .padding(start = DOCKED_SEARCH_MARGIN.dp, end = DOCKED_SEARCH_MARGIN.dp, bottom = DOCKED_SEARCH_MARGIN.dp + bottom)
            .height(height),
    ) {
        val grown = max(t, 0f)
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .size(height)
                .graphicsLayer {
                    translationX = height.toPx() / 2 * (1 - t)
                    scaleX = grown
                    scaleY = grown
                    alpha = ((t - 0.5f) / 0.4f).coerceIn(0f, 1f)
                },
        ) {
            if (active) TonalIconButton(IconAction(AppGlyphs.close, tr("Cancel"), onCancel), size = TonalButtonSize.Docked)
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .width(maxWidth - (height + SEARCH_FIELD_GAP.dp) * grown)
                    .height(height)
                    .elasticPress()
                    .boxShadows(AppShape.full, dockShadows(scheme))
                    .background(SurfaceLevel.floatingOf(scheme).colorOf(scheme), AppShape.full),
            ) {
                SearchField(text = text, onChanged = onChanged, height = height, onFocusChanged = { focused = it })
            }
        }
    }
}

/** widgets.dart's BoxPage: a scaffold whose body is a padded list. */
@Composable
fun BoxPage(
    title: String,
    actions: List<IconAction> = emptyList(),
    menuItems: List<PopupMenuItem> = emptyList(),
    primaryAction: IconAction? = null,
    confirmationAction: IconAction? = null,
    loading: Boolean = false,
    content: @Composable () -> Unit,
) {
    CommonScaffold(
        title = tr(title),
        iconActions = actions,
        menuItems = menuItems,
        primaryAction = primaryAction,
        confirmationAction = confirmationAction,
        loading = loading,
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(PaddingValues(start = 16.dp, end = 16.dp, top = contentTopPadding(), bottom = 24.dp + bottomInset())),
        ) { content() }
    }
}

/** button.dart's TonalButton at the bar size, as the phase-1 pages call it. */
@Composable
fun TonalBarButton(action: IconAction, filled: Boolean = true) = TonalIconButton(action, grouped = !filled)
