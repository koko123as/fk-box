package io.nekohasekai.sfa.compose.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.compose.theme.AppShape
import io.nekohasekai.sfa.compose.theme.BoxTheme
import io.nekohasekai.sfa.compose.theme.SurfaceLevel
import kotlin.math.max

/*
 * A page: a Material 3 Scaffold with a TopAppBar over the body. At most three
 * buttons stand in the bar (the search button, the primary and icon actions,
 * the confirmation); past that they fold into the overflow menu with the menu
 * items. Search takes the bar's title over, or on a pushed phone page and in
 * a form it is a field at the foot of the page.
 */

private const val MAX_BAR_BUTTONS = 3
const val DOCKED_SEARCH_HEIGHT = 48f
const val DOCKED_SEARCH_MARGIN = 16f
const val DOCKED_SEARCH_INSET = DOCKED_SEARCH_MARGIN + DOCKED_SEARCH_HEIGHT

/** An icon button of a bar or a list header. */
class IconAction(
    val icon: ImageVector,
    val tooltip: String,
    val onClick: (() -> Unit)?,
    val selected: Boolean = false,
    val loading: Boolean = false,
)

/**
 * A chip in the strip under the bar. A removable one ([onDeleted]) shows a
 * close mark; a toggle ([onPressed]) is a filter chip, checked when
 * [selected]. [startsSection] widens the gap before it.
 */
class ScaffoldKeyword(
    val key: Any,
    val label: String,
    val onDeleted: (() -> Unit)? = null,
    val onPressed: (() -> Unit)? = null,
    val selected: Boolean = false,
    val startsSection: Boolean = false,
)

/** What the search field reports as it changes. */
class SearchState(val onSearch: (String) -> Unit)

/** What stands over the page's foot (a docked search), for content to scroll clear of. */
val LocalBottomInset = staticCompositionLocalOf { 0.dp }

/** True when the system navigation bar is already kept clear by an enclosing navigation bar. */
val LocalNavigationBarHandled = staticCompositionLocalOf { false }

/** True when the page is not a top-level destination (pushed over the home page). */
val LocalPushedPage = staticCompositionLocalOf { false }

private val SheetType?.isForm: Boolean get() = this == SheetType.BottomSheet || this == SheetType.FormSheet

/** The bar is laid out above the body, so the body starts under it. */
@Composable
fun appBarInset(): Dp = 0.dp

/** The gap between the bar and a page's first item. */
@Composable
fun contentTopPadding(): Dp = 8.dp

/** For a page that opens on a section, whose header brings its own gap. */
@Composable
fun sectionsTopPadding(): Dp = 0.dp

/** A docked search plus the system navigation bar, unless a navigation bar keeps that clear. */
@Composable
fun bottomInset(): Dp = LocalBottomInset.current +
    if (LocalNavigationBarHandled.current) 0.dp else WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

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
            PopupMenuItem(label = it.tooltip, icon = it.icon, checked = it.selected, onPressed = if (it.loading) null else it.onClick)
        } + menuItems
        )
}

@OptIn(ExperimentalMaterial3Api::class)
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
    titleMenuItems: List<PopupMenuItem> = emptyList(),
    keywords: List<ScaffoldKeyword> = emptyList(),
    body: @Composable () -> Unit,
) {
    val navigator = LocalBoxNavigator.current
    val sheet = LocalSheetType.current
    val form = sheet.isForm
    val pushed = LocalPushedPage.current && !form
    val sheetRoot = LocalSheetRoot.current
    val mobile = navigator?.mobile ?: true
    val docked = searchState != null && (form || (!searchInBar && pushed && mobile))
    val barSearch = searchState != null && !docked
    var query by remember { mutableStateOf<String?>(null) }
    var dockedText by remember { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val exitSearch = {
        if (query != null) {
            query = null
            searchState?.onSearch("")
            focus.clearFocus()
        }
    }
    BackHandler(enabled = query != null || sorting) {
        exitSearch()
        if (sorting) onSortingChange?.invoke(false)
    }
    val inset = LocalBottomInset.current
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = backgroundColor,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        val searching = query
                        when {
                            barSearch && searching != null -> BarSearchField(onChanged = {
                                query = it
                                searchState?.onSearch(it)
                            })
                            titleMenuItems.isEmpty() -> Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            else -> CommonPopupBox(titleMenuItems) { open -> TitleMenuButton(title, onPressed = open) }
                        }
                    },
                    navigationIcon = {
                        val leading: IconAction? = when {
                            barSearch && query != null -> IconAction(AppIcons.back, tr("Cancel"), exitSearch)
                            form && navigator != null -> if (sheetRoot) {
                                IconAction(AppIcons.close, tr("Close"), { navigator.maybePop() })
                            } else {
                                IconAction(AppIcons.back, tr("Back"), { navigator.maybePop() })
                            }
                            pushed && navigator != null -> IconAction(AppIcons.back, tr("Back"), { navigator.maybePop() })
                            else -> null
                        }
                        if (leading != null) AppIconButton(leading.icon, leading.tooltip, leading.onClick)
                    },
                    actions = {
                        when {
                            sorting -> if (canSort) AppIconButton(AppIcons.check, tr("Done"), { onSortingChange?.invoke(false) })
                            barSearch && query != null -> Unit
                            else -> {
                                val lead = if (barSearch) IconAction(AppIcons.search, tr("Search"), { query = "" }) else null
                                val icons = (if (canSort) listOf(IconAction(AppIcons.sort, tr("Reorder"), { onSortingChange?.invoke(true) })) else emptyList()) + iconActions
                                val (shown, overflow) = foldBarActions(lead != null, primaryAction, icons, confirmationAction, actions.size, menuItems)
                                BarActions(listOfNotNull(lead) + shown, actions, overflow)
                            }
                        }
                    },
                    windowInsets = if (sheet == SheetType.FormSheet) WindowInsets(0) else TopAppBarDefaults.windowInsets,
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = backgroundColor),
                )
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (keywords.isNotEmpty()) KeywordStrip(keywords)
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            CompositionLocalProvider(
                LocalSorting provides sorting,
                LocalBottomInset provides if (docked && !sorting) inset + DOCKED_SEARCH_INSET.dp else inset,
            ) { body() }
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
}

/** The title and a drop-down arrow that opens the title menu. */
@Composable
private fun TitleMenuButton(title: String, onPressed: () -> Unit) {
    Row(
        Modifier.clip(AppShape.card).clickable(role = Role.Button, onClick = onPressed).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
        AppIcon(AppIcons.caretDown, size = 24.dp, color = BoxTheme.scheme.onSurfaceVariant)
    }
}

/** A horizontal row of chips under the bar, 16 dp in from each side. */
@Composable
private fun KeywordStrip(keywords: List<ScaffoldKeyword>) {
    LazyRow(Modifier.fillMaxWidth().padding(bottom = 8.dp), contentPadding = PaddingValues(horizontal = 16.dp)) {
        itemsIndexed(keywords, key = { _, keyword -> keyword.key.toString() }) { index, keyword ->
            Row {
                if (index > 0) Spacer(Modifier.width(if (keyword.startsSection) 16.dp else 8.dp))
                KeywordChip(keyword)
            }
        }
    }
}

/** A removable keyword is an input chip; a toggle is a filter chip. */
@Composable
fun KeywordChip(keyword: ScaffoldKeyword) {
    val label: @Composable () -> Unit = { Text(keyword.label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    val deleted = keyword.onDeleted
    if (deleted != null) {
        InputChip(
            selected = false,
            onClick = deleted,
            label = label,
            trailingIcon = { AppIcon(AppIcons.close, size = InputChipDefaults.IconSize) },
        )
    } else {
        FilterChip(selected = keyword.selected, onClick = { keyword.onPressed?.invoke() }, enabled = keyword.onPressed != null, label = label)
    }
}

@Composable
private fun BarActions(shown: List<IconAction>, widgets: List<@Composable () -> Unit>, overflow: List<PopupMenuItem>) {
    for (action in shown) {
        if (action.loading) {
            Box(Modifier.padding(horizontal = 12.dp)) { CommonCircleLoading(size = 24.dp) }
        } else {
            AppIconButton(action.icon, action.tooltip, action.onClick, color = if (action.selected) BoxTheme.scheme.primary else androidx.compose.material3.LocalContentColor.current)
        }
    }
    for (widget in widgets) widget()
    if (overflow.isNotEmpty()) CommonPopupBox(overflow) { open -> AppIconButton(AppIcons.more, tr("More"), open) }
}

/** The search field that takes over the bar's title. */
@Composable
private fun BarSearchField(onChanged: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    SearchField(
        text = text,
        onChanged = {
            text = it
            onChanged(it)
        },
        focusRequester = focus,
    )
}

/** The search field at the page's foot, with a cancel button while in use. */
@Composable
private fun DockedSearch(text: String, onChanged: (String) -> Unit, onCancel: () -> Unit, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    val active = focused || text.isNotEmpty()
    val bottom = if (LocalNavigationBarHandled.current) 0.dp else WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Surface(modifier.fillMaxWidth(), color = BoxTheme.scheme.surfaceContainer) {
        Row(
            Modifier.fillMaxWidth().padding(start = DOCKED_SEARCH_MARGIN.dp, end = 4.dp, top = 8.dp, bottom = 8.dp + bottom).height(DOCKED_SEARCH_HEIGHT.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                SearchField(text = text, onChanged = onChanged, fill = BoxTheme.scheme.surfaceContainerHighest, onFocusChanged = { focused = it })
            }
            if (active) AppIconButton(AppIcons.close, tr("Cancel"), onCancel) else Spacer(Modifier.width(12.dp))
        }
    }
}

/** A scaffold whose body is a padded, scrolling column. */
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
