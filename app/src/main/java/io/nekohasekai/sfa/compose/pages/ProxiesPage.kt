package io.nekohasekai.sfa.compose.pages

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.compose.BoxController
import io.nekohasekai.sfa.compose.Json
import io.nekohasekai.sfa.compose.asLong
import io.nekohasekai.sfa.compose.json
import io.nekohasekai.sfa.compose.rows
import io.nekohasekai.sfa.compose.theme.AppCorner
import io.nekohasekai.sfa.compose.theme.AppShape
import io.nekohasekai.sfa.compose.theme.AppTextStyle
import io.nekohasekai.sfa.compose.theme.BoxTheme
import io.nekohasekai.sfa.compose.theme.SurfaceLevel
import io.nekohasekai.sfa.compose.ui.AppGlyphs
import io.nekohasekai.sfa.compose.ui.BoxPage
import io.nekohasekai.sfa.compose.ui.BoxShadowSpec
import io.nekohasekai.sfa.compose.ui.CommonCard
import io.nekohasekai.sfa.compose.ui.CommonCircleLoading
import io.nekohasekai.sfa.compose.ui.CommonExpandIcon
import io.nekohasekai.sfa.compose.ui.CommonPopupBox
import io.nekohasekai.sfa.compose.ui.CommonScaffold
import io.nekohasekai.sfa.compose.ui.ContextMenuRegion
import io.nekohasekai.sfa.compose.ui.DetailRows
import io.nekohasekai.sfa.compose.ui.EmojiText
import io.nekohasekai.sfa.compose.ui.GlyphIcon
import io.nekohasekai.sfa.compose.ui.IconAction
import io.nekohasekai.sfa.compose.ui.ListStyles
import io.nekohasekai.sfa.compose.ui.LocalBoxNavigator
import io.nekohasekai.sfa.compose.ui.NullStatus
import io.nekohasekai.sfa.compose.ui.NullStatusIllustration
import io.nekohasekai.sfa.compose.ui.PopupMenuItem
import io.nekohasekai.sfa.compose.ui.PopupMenuSection
import io.nekohasekai.sfa.compose.ui.SearchState
import io.nekohasekai.sfa.compose.ui.SelectionBadge
import io.nekohasekai.sfa.compose.ui.TonalButtonGroup
import io.nekohasekai.sfa.compose.ui.TonalButtonSize
import io.nekohasekai.sfa.compose.ui.TonalIconButton
import io.nekohasekai.sfa.compose.ui.boxShadows
import io.nekohasekai.sfa.compose.ui.bottomInset
import io.nekohasekai.sfa.compose.ui.contentTopPadding
import io.nekohasekai.sfa.compose.ui.elasticPress
import io.nekohasekai.sfa.compose.ui.raisedSurfaceLevel
import io.nekohasekai.sfa.compose.ui.tr
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min


// ---------------------------------------------------------------------------
// proxy_card.dart: metrics and the node card (FlClash v0.9.2 proxies/common.dart and card.dart)
// ---------------------------------------------------------------------------

class ProxyCardMetrics(preferences: Json, private val fontScale: Float) {
    val compact = preferences["proxyCardSize"] == "Compact" || preferences["proxyCardSize"] == null && preferences["proxyLayout"] == "Compact"
    val loose = preferences["proxyCardSize"] == "Loose"
    private fun textHeight(style: AppTextStyle) = style.leading * fontScale
    val nameHeight get() = textHeight(AppTextStyle.Subheadline) * if (compact) 1 else 2
    val detailHeight get() = textHeight(AppTextStyle.Footnote)
    val delayHeight get() = textHeight(AppTextStyle.Caption2)
    val extent get() = 28 + nameHeight + detailHeight + if (loose) delayHeight + 6 else 0f
    val rowExtent get() = extent + 8
}

@Composable
fun proxyCardMetrics(preferences: Json): ProxyCardMetrics = ProxyCardMetrics(preferences, LocalDensity.current.fontScale)

fun proxyColumns(width: Float, preferences: Json): Int {
    val standard = ceil(width / 250).toInt().coerceIn(2, 8)
    val density = preferences["proxyDensity"] ?: "Standard"
    return (standard + if (density == "Tight") 1 else if (density == "Loose") -1 else 0).coerceIn(1, 8)
}

@Composable
fun ProxyNodeCard(box: BoxController, group: Json, node: Json, testing: Boolean, onSelect: () -> Unit, onTest: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = BoxTheme.scheme
    val navigator = LocalBoxNavigator.current
    val metrics = proxyCardMetrics(box.preferences)
    val selected = group["selected"] == node["tag"]
    val delay = node["delay"].asLong()?.toInt() ?: 0
    val tested = (node["testedAt"].asLong() ?: 0) > 0
    val delayText = if (delay > 0) "$delay ms" else if (tested) tr("Unreachable") else "—"
    val delayColor = when {
        !tested -> scheme.onSurfaceVariant
        delay <= 0 -> scheme.error
        delay < 300 -> scheme.success
        else -> scheme.warning
    }
    val selectable = group["selectable"] == true
    val running = box.running
    val menu = buildList {
        if (selectable) add(PopupMenuItem(tr("Select"), AppGlyphs.checkCircle, checked = selected, onPressed = if (running) onSelect else null))
        add(PopupMenuItem(tr("Test latency"), AppGlyphs.bolt, onPressed = if (running && !testing) onTest else null))
        add(
            PopupMenuItem(tr("Details"), AppGlyphs.info, onPressed = {
                navigator?.openForm {
                    BoxPage("Details") {
                        DetailRows(listOf("Name" to node["tag"], "Protocol" to node["type"], "Latency" to delayText, "Proxy groups" to group["tag"]))
                    }
                }
            }),
        )
    }
    val density = LocalDensity.current
    ContextMenuRegion(menu, modifier) {
        CommonCard(
            Modifier.fillMaxSize(),
            radius = AppCorner.LG,
            isSelected = selected,
            onPressed = if (selectable && running) onSelect else null,
        ) {
            Column(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.Center) {
                Row(verticalAlignment = Alignment.Top) {
                    Box(Modifier.weight(1f).height(with(density) { metrics.nameHeight.dp })) {
                        EmojiText(node["tag"].toString(), AppTextStyle.Subheadline.of().copy(color = scheme.onSurface), maxLines = if (metrics.compact) 1 else 2)
                    }
                    if (selected) Box(Modifier.padding(start = 4.dp)) { SelectionBadge() }
                }
                Spacer(Modifier.height(8.dp))
                val typeStyle = AppTextStyle.Footnote.of().copy(color = scheme.onSurface.copy(alpha = 0.8f))
                if (metrics.loose) {
                    Text(node["type"].toString(), Modifier.height(metrics.detailHeight.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = typeStyle)
                    Spacer(Modifier.height(6.dp))
                }
                Row(Modifier.height((if (metrics.loose) metrics.delayHeight else metrics.detailHeight).dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!metrics.loose) {
                        Text(node["type"].toString(), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = typeStyle)
                        Spacer(Modifier.width(4.dp))
                    }
                    Box(
                        Modifier
                            .then(if (metrics.loose) Modifier.weight(1f) else Modifier)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                enabled = running && !testing,
                                onClick = onTest,
                            ),
                        contentAlignment = if (metrics.loose) Alignment.CenterStart else Alignment.CenterEnd,
                    ) {
                        val state = if (testing) 0 else if (!tested) 1 else 2
                        io.nekohasekai.sfa.compose.ui.FadeBox(state, if (metrics.loose) Alignment.CenterStart else Alignment.CenterEnd) { shown ->
                            when (shown) {
                                0 -> CommonCircleLoading(size = metrics.delayHeight.dp)
                                1 -> GlyphIcon(AppGlyphs.bolt, size = metrics.delayHeight.dp, color = scheme.onSurfaceVariant)
                                else -> Text(delayText, maxLines = 1, overflow = TextOverflow.Ellipsis, style = AppTextStyle.Caption2.of(true).copy(color = delayColor))
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// pages/proxies.dart
// ---------------------------------------------------------------------------

@Composable
fun ProxiesPage(box: BoxController) {
    var query by remember { mutableStateOf("") }
    val testing = remember { mutableStateMapOf<String, Boolean>() }
    val grids = remember { HashMap<String, LazyGridState>() }
    val selectedOffsets = remember { HashMap<String, Int>() }
    var currentGroup by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val groups = box.groups
    val prefs = box.preferences
    val list = prefs["proxyLayout"] == "List"
    if (groups.none { it["tag"] == currentGroup }) currentGroup = groups.firstOrNull()?.get("tag") as? String
    val liveTags = groups.map { it["tag"].toString() }.toSet()
    grids.keys.retainAll(liveTags)
    selectedOffsets.keys.retainAll(liveTags)

    suspend fun test(tag: String) {
        if (testing[tag] == true || !box.running) return
        testing[tag] = true
        box.act("groups.test", mapOf("tag" to tag))
        testing.remove(tag)
    }
    val launchTest: (String) -> Unit = { tag -> scope.launch { test(tag) } }
    val select: (Json, Json) -> Unit = { group, node ->
        scope.launch {
            if (box.running && group["selectable"] == true && node["tag"] != group["selected"]) {
                if (box.act("groups.select", mapOf("group" to group["tag"], "tag" to node["tag"]))) {
                    if (box.preferences["closeConnectionsOnSelect"] == true) box.act("connections.closeAll")
                }
            }
        }
    }
    fun nodes(group: Json): List<Json> {
        val words = query.lowercase().trim().split(Regex("\\s+"))
        val hide = prefs["hideTimeoutProxies"] == true
        val result = rows(group["items"]).filter { node ->
            val tag = node["tag"].toString().lowercase()
            words.all { tag.contains(it) } && !(hide && (node["testedAt"].asLong() ?: 0) > 0 && (node["delay"].asLong() ?: 0) <= 0)
        }
        return when (prefs["proxySort"] ?: "Default") {
            "Name" -> result.sortedBy { it["tag"].toString() }
            "Latency" -> result.sortedBy { node -> (node["delay"].asLong() ?: 0).let { if (it > 0) it else Int.MAX_VALUE.toLong() } }
            else -> result
        }
    }
    fun options(label: String, glyph: io.nekohasekai.sfa.compose.ui.Glyph, key: String, values: List<String>, fallback: String, translate: (String) -> String) =
        PopupMenuItem(translate(label), glyph, subItems = values.map { value ->
            PopupMenuItem(translate(value), checked = (prefs[key] ?: fallback) == value, onPressed = { scope.launch { box.setPreference(key, value) } })
        })
    val t: (String) -> String = trFunction()
    val card: @Composable (Json, Json, Modifier) -> Unit = { group, node, modifier ->
        ProxyNodeCard(
            box, group, node,
            testing = testing[group["tag"]] == true || testing[node["tag"]] == true,
            onSelect = { select(group, node) },
            onTest = { launchTest(node["tag"] as String) },
            modifier = modifier,
        )
    }
    CommonScaffold(
        title = tr("Proxies"),
        searchState = remember { SearchState { query = it } },
        primaryAction = if (list) null else IconAction(
            AppGlyphs.bolt, tr("Test latency"),
            if (box.running && currentGroup != null) ({ launchTest(currentGroup!!) }) else null,
            loading = testing[currentGroup] == true,
        ),
        iconActions = if (list) emptyList() else listOf(
            IconAction(AppGlyphs.locate, tr("Scroll to selected"), if (groups.isEmpty()) null else ({
                val grid = grids[currentGroup]
                val offset = selectedOffsets[currentGroup]
                if (grid != null && offset != null) scope.launch { grid.animateScrollToItem(offset) }
            })),
        ),
        menuItems = listOfNotNull(
            options("Style", AppGlyphs.appsList, "proxyLayout", listOf("Tabs", "List"), "Tabs", t),
            options("Sort", AppGlyphs.sort, "proxySort", listOf("Default", "Latency", "Name"), "Default", t),
            options("Layout", AppGlyphs.dashboard, "proxyDensity", listOf("Tight", "Standard", "Loose"), "Standard", t),
            options("Size", AppGlyphs.textSize, "proxyCardSize", listOf("Compact", "Standard", "Loose"), "Standard", t),
            if (list) options("Icon style", AppGlyphs.iconTile, "proxyIconStyle", listOf("Filled", "Plain", "Hidden"), "Filled", t) else null,
            PopupMenuItem(
                t("Hide timeout proxies"), AppGlyphs.eyeOff, checked = prefs["hideTimeoutProxies"] == true, section = PopupMenuSection(),
                onPressed = { scope.launch { box.setPreference("hideTimeoutProxies", box.preferences["hideTimeoutProxies"] != true) } },
            ),
            PopupMenuItem(t("Test all"), AppGlyphs.bolt, onPressed = {
                scope.launch {
                    if (box.running) for (group in groups) test(group["tag"] as String)
                }
            }),
            PopupMenuItem(
                t("Close connections after switching"), AppGlyphs.connections, checked = prefs["closeConnectionsOnSelect"] == true,
                onPressed = { scope.launch { box.setPreference("closeConnectionsOnSelect", box.preferences["closeConnectionsOnSelect"] != true) } },
            ),
        ),
    ) {
        when {
            groups.isEmpty() -> NullStatus(
                label = tr(if (box.running) "No proxy groups in this configuration" else "Start the service to view proxy groups"),
                illustration = NullStatusIllustration.Proxies,
            )
            list -> {
                val profileKey = if (box.remote) "remote:${box.dashboard["remote"]}" else "local:${box.selectedProfileId}"
                androidx.compose.runtime.key(profileKey) {
                    ProxyListView(box, profileKey, groups.map { it to nodes(it) }, testing.filterValues { it }.keys, card, launchTest)
                }
            }
            else -> {
                val groupsKey = groups.joinToString("") { "${it["tag"].toString().length}:${it["tag"]}" }
                androidx.compose.runtime.key(groupsKey) {
                    val initial = groups.indexOfFirst { it["tag"] == currentGroup }.coerceIn(0, groups.size - 1)
                    val pager = rememberPagerState(initial) { groups.size }
                    LaunchedEffect(pager) {
                        snapshotFlow { pager.currentPage }.collect { page -> currentGroup = groups.getOrNull(page)?.get("tag") as? String }
                    }
                    Column(Modifier.fillMaxSize().padding(top = contentTopPadding())) {
                        Box(Modifier.padding(bottom = 16.dp)) {
                            GroupTabs(groups, pager.currentPage) { index -> scope.launch { pager.animateScrollToPage(index) } }
                        }
                        HorizontalPager(pager, Modifier.weight(1f), key = { groups[it]["tag"].toString() }) { page ->
                            val group = groups[page]
                            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                                if (group["selectable"] != true) {
                                    Text(tr("Automatic selection"), Modifier.padding(top = 8.dp), style = ListStyles.caption)
                                }
                                Box(Modifier.weight(1f)) {
                                    ProxyGrid(box, group, nodes(group), grids, selectedOffsets, card)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun trFunction(): (String) -> String {
    val chinese = io.nekohasekai.sfa.compose.ui.isChinese()
    return { value -> if (chinese) io.nekohasekai.sfa.compose.BoxStrings.zh[value] ?: value else value }
}

@Composable
private fun ProxyGrid(
    box: BoxController,
    group: Json,
    nodes: List<Json>,
    grids: HashMap<String, LazyGridState>,
    selectedOffsets: HashMap<String, Int>,
    card: @Composable (Json, Json, Modifier) -> Unit,
) {
    val tag = group["tag"].toString()
    if (nodes.isEmpty()) {
        selectedOffsets.remove(tag)
        NullStatus(label = tr("No results"), illustration = NullStatusIllustration.Proxies)
        return
    }
    val metrics = proxyCardMetrics(box.preferences)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = proxyColumns(maxWidth.value - 32, box.preferences)
        val selected = nodes.indexOfFirst { it["tag"] == group["selected"] }
        if (selected < 0) selectedOffsets.remove(tag) else selectedOffsets[tag] = (selected / columns) * columns
        val state = grids.getOrPut(tag) { LazyGridState() }
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = state,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp + bottomInset()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(nodes, key = { _, node -> node["tag"].toString() }) { _, node ->
                card(group, node, Modifier.fillMaxWidth().height(metrics.extent.dp))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// group_tabs.dart: the group chips over the tab view
// ---------------------------------------------------------------------------

private const val STRIP_INSET = 16f
private const val TAB_SPACING = 8f
private const val TAB_PADDING = 16f
private const val FADE_EXTENT = 24f

@Composable
fun GroupTabs(groups: List<Json>, selected: Int, onSelected: (Int) -> Unit) {
    val state = rememberLazyListState()
    val density = LocalDensity.current
    LaunchedEffect(selected) {
        // _reveal: centers the selected chip.
        val info = state.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == selected }
        if (item != null) {
            val center = item.offset + item.size / 2
            val viewport = (info.viewportEndOffset - info.viewportStartOffset) / 2
            state.animateScrollBy((center - viewport).toFloat(), tween(300, easing = EaseOutCubic))
        } else {
            state.animateScrollToItem(selected)
        }
    }
    val hasMore = state.canScrollForward || state.canScrollBackward
    val fade = if (state.canScrollForward) 1f else 0f
    Row(Modifier.fillMaxWidth().height(TonalButtonSize.Section.button.dp), verticalAlignment = Alignment.CenterVertically) {
        LazyRow(
            state = state,
            modifier = Modifier
                .weight(1f)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithEdgeFade(fade, with(density) { FADE_EXTENT.dp.toPx() }),
            contentPadding = PaddingValues(start = STRIP_INSET.dp, end = if (hasMore) 0.dp else STRIP_INSET.dp),
            horizontalArrangement = Arrangement.spacedBy(TAB_SPACING.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            itemsIndexed(groups, key = { _, group -> group["tag"].toString() }) { index, group ->
                GroupTab(group["tag"].toString(), index == selected) { onSelected(index) }
            }
        }
        if (hasMore) {
            Box(Modifier.padding(start = 8.dp, end = 16.dp)) {
                CommonPopupBox(
                    groups.mapIndexed { index, group -> PopupMenuItem(group["tag"].toString(), checked = index == selected, onPressed = { onSelected(index) }) },
                    maxVisibleItems = 7,
                ) { open ->
                    TonalIconButton(IconAction(AppGlyphs.chevronDown, tr("Proxy groups"), open), size = TonalButtonSize.Section)
                }
            }
        }
    }
}

private fun Modifier.drawWithEdgeFade(fade: Float, extent: Float): Modifier = drawWithContent {
    drawContent()
    if (fade > 0f && size.width > 0f) {
        val start = max(0f, 1 - extent / size.width)
        drawRect(
            Brush.horizontalGradient(start to Color.Black, 1f to Color.Black.copy(alpha = 1 - fade)),
            blendMode = BlendMode.DstIn,
        )
    }
}

@Composable
private fun GroupTab(name: String, selected: Boolean, onPressed: () -> Unit) {
    val scheme = BoxTheme.scheme
    CommonCard(
        Modifier.height(TonalButtonSize.Section.button.dp).widthIn(min = 64.dp),
        isSelected = selected,
        fillsSelection = true,
        shape = AppShape.full,
        padding = PaddingValues(horizontal = TAB_PADDING.dp),
        onPressed = onPressed,
        contentAlignment = Alignment.Center,
    ) {
        val color = io.nekohasekai.sfa.compose.ui.animatedColor(if (selected) scheme.onPrimary else scheme.onSurfaceVariant, 200)
        EmojiText(name, AppTextStyle.Subheadline.of(true).copy(color = color), maxLines = 1)
    }
}

// ---------------------------------------------------------------------------
// proxy_list.dart: groups as folding sections with pinned headers
// ---------------------------------------------------------------------------

private const val HEADER_INSET = 10f
private const val HEADER_BUTTON_INSET = 4f
private const val GROUP_INSET = 16f
private const val LAST_ROW_GROWTH = 2f

@Composable
private fun headerHeight(): Float {
    val scale = LocalDensity.current.fontScale
    return 20 + AppTextStyle.Callout.leading * scale + 2 + AppTextStyle.Caption1.leading * scale + 2
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProxyListView(
    box: BoxController,
    profileKey: String,
    groups: List<Pair<Json, List<Json>>>,
    testing: Set<String>,
    card: @Composable (Json, Json, Modifier) -> Unit,
    onTest: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var unfolded by remember { mutableStateOf(savedUnfolded(box.preferences, profileKey)) }
    var saving by remember { mutableStateOf(false) }
    val state = rememberLazyListState()
    val unfolds = remember { HashMap<String, Animatable<Float, androidx.compose.animation.core.AnimationVector1D>>() }
    val metrics = proxyCardMetrics(box.preferences)
    val header = headerHeight()
    val toggle: (String) -> Unit = toggle@{ name ->
        if (saving) return@toggle
        val old = unfolded
        unfolded = if (name in unfolded) unfolded - name else unfolded + name
        saving = true
        scope.launch {
            val saved = json(box.preferences["proxyUnfolded"]).toMutableMap()
            saved.remove(profileKey)
            saved[profileKey] = unfolded.toList()
            while (saved.size > 128) saved.remove(saved.keys.first())
            val success = box.setPreference("proxyUnfolded", saved)
            saving = false
            if (!success) unfolded = old
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().padding(top = contentTopPadding())) {
        val gap = io.nekohasekai.sfa.compose.ui.cardSpacingOf(io.nekohasekai.sfa.compose.ui.LocalViewMode.current).value
        val width = max(maxWidth.value - GROUP_INSET * 2, 0f)
        val columns = proxyColumns(max(width - HEADER_INSET * 2, 0f), box.preferences)
        val locate: (String) -> Unit = locate@{ name ->
            val entry = groups.firstOrNull { it.first["tag"].toString() == name } ?: return@locate
            val index = entry.second.indexOfFirst { it["tag"] == entry.first["selected"] }
            if (index < 0) return@locate
            val target = state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "header:$name" }?.index
                ?: groups.indexOfFirst { it.first["tag"].toString() == name }.let { groupIndex ->
                    groups.take(groupIndex).sumOf { (g, n) -> 1 + if (g["tag"].toString() in unfolded && n.isNotEmpty()) (n.size + columns - 1) / columns + 1 else 1 }
                }
            scope.launch { state.animateScrollToItem(target + 1 + index / columns) }
        }
        val firstKey = state.layoutInfo.visibleItemsInfo.firstOrNull()?.key as? String
        LazyColumn(
            state = state,
            contentPadding = PaddingValues(start = GROUP_INSET.dp, end = GROUP_INSET.dp, bottom = 16.dp + bottomInset()),
        ) {
            for ((group, nodes) in groups) {
                val name = group["tag"].toString()
                val expanded = name in unfolded
                val rowCount = (nodes.size + columns - 1) / columns
                val unfold = unfolds.getOrPut(name) { Animatable(if (expanded) 1f else 0f) }
                val floatingHere = expanded && (firstKey == "rows:$name" || firstKey?.startsWith("row:$name:") == true || (firstKey == "header:$name" && state.firstVisibleItemScrollOffset > 0))
                stickyHeader(key = "header:$name") {
                    LaunchedEffect(expanded) {
                        unfold.animateTo(if (expanded) 1f else 0f, tween(240, easing = if (expanded) EaseOutCubic else EaseInCubic))
                    }
                    val floating by animateFloatAsState(if (floatingHere) 1f else 0f, tween(150), label = "floating")
                    Box(Modifier.fillMaxWidth().height(header.dp).background(if (floating > 0f) BoxTheme.scheme.let { SurfaceLevel.Page.colorOf(it) } else Color.Transparent)) {
                        ProxyGroupHeader(
                            box, group,
                            expanded = expanded,
                            unfolded = unfold.value >= 1f,
                            floating = floating,
                            lift = floating,
                            testing = name in testing,
                            onToggle = { toggle(name) },
                            onLocate = { locate(name) },
                            onTest = { onTest(name) },
                        )
                    }
                }
                if (nodes.isNotEmpty() && (expanded || unfold.value > 0f)) {
                    val rowShape = AppShape.vertical(0f, AppCorner.XL)
                    if (unfold.value < 1f) {
                        item(key = "rows:$name") {
                            UnfoldingRows(rowCount, metrics.rowExtent, unfold.value, rowShape) { row -> ProxyRow(group, nodes, row, columns, metrics, card) }
                        }
                    } else {
                        for (row in 0 until rowCount) {
                            item(key = "row:$name:$row") {
                                val last = row == rowCount - 1
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height((metrics.rowExtent + if (last) LAST_ROW_GROWTH else 0f).dp)
                                        .background(raisedSurfaceLevel.colorOf(BoxTheme.scheme), if (last) rowShape else AppShape.none),
                                ) { ProxyRow(group, nodes, row, columns, metrics, card) }
                            }
                        }
                    }
                }
                item(key = "gap:$name") { Spacer(Modifier.height(gap.dp)) }
            }
        }
    }
}

private fun savedUnfolded(preferences: Json, profileKey: String): Set<String> =
    (json(preferences["proxyUnfolded"])[profileKey] as? List<*>)?.filterIsInstance<String>()?.toSet() ?: emptySet()

@Composable
private fun ProxyRow(group: Json, nodes: List<Json>, row: Int, columns: Int, metrics: ProxyCardMetrics, card: @Composable (Json, Json, Modifier) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = HEADER_INSET.dp, end = HEADER_INSET.dp, bottom = 8.dp)) {
        for (column in 0 until columns) {
            if (column > 0) Spacer(Modifier.width(8.dp))
            val index = row * columns + column
            Box(Modifier.weight(1f).height((metrics.rowExtent - 8).dp)) {
                if (index < nodes.size) card(group, nodes[index], Modifier.fillMaxSize())
            }
        }
    }
}

/** _unfoldingRows: the rows slide out from under the header and fade in from the middle outward. */
@Composable
private fun UnfoldingRows(rowCount: Int, extent: Float, unfold: Float, shape: androidx.compose.ui.graphics.Shape, row: @Composable (Int) -> Unit) {
    val screen = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp
    val visible = min(rowCount.toFloat(), screen / extent)
    val count = min(rowCount, visible.toInt() + 1)
    val total = rowCount * extent + LAST_ROW_GROWTH
    Box(
        Modifier
            .fillMaxWidth()
            .height((total * unfold).dp)
            .clip(AppShape.none)
            .background(raisedSurfaceLevel.colorOf(BoxTheme.scheme), shape),
    ) {
        Column {
            for (index in 0 until count) {
                val half = visible / 2
                val distance = kotlin.math.abs(half - index - 0.5f) / half
                val start = min(1f, distance) * 0.5f
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(extent.dp)
                        .graphicsLayer {
                            translationY = (-index - 0.5f) * size.height * (1 - unfold)
                            alpha = ((unfold - start) / 0.5f).coerceIn(0f, 1f)
                        },
                ) { row(index) }
            }
        }
    }
}

@Composable
fun ProxyGroupHeader(
    box: BoxController,
    group: Json,
    expanded: Boolean,
    unfolded: Boolean,
    floating: Float,
    lift: Float,
    testing: Boolean,
    onToggle: () -> Unit,
    onLocate: () -> Unit,
    onTest: () -> Unit,
) {
    val scheme = BoxTheme.scheme
    val level = if (floating > 0f) SurfaceLevel.floatingOf(scheme) else raisedSurfaceLevel
    val radius = AppCorner.XL
    val strength = if (scheme.dark) 3f else 1f
    val shape = AppShape.vertical(radius, if (unfolded) radius * floating else radius)
    Box(
        Modifier
            .fillMaxSize()
            .boxShadows(
                shape,
                if (lift == 0f) emptyList() else listOf(
                    BoxShadowSpec(scheme.shadow.copy(alpha = (.08f * strength * lift).coerceIn(0f, 1f)), 4f, 16f, flutterRadius = true),
                    BoxShadowSpec(scheme.shadow.copy(alpha = (.04f * strength * lift).coerceIn(0f, 1f)), 1f, 3f, flutterRadius = true),
                ),
            )
            .background(level.colorOf(scheme), shape)
            .padding(HEADER_BUTTON_INSET.dp),
    ) {
        androidx.compose.runtime.CompositionLocalProvider(io.nekohasekai.sfa.compose.theme.LocalSurfaceLevel provides SurfaceLevel.entries[max(0, level.ordinal - 1)]) {
            CommonCard(Modifier.fillMaxSize(), radius = radius - HEADER_BUTTON_INSET, onPressed = onToggle) {
                Row(
                    Modifier.fillMaxSize().padding((HEADER_INSET - HEADER_BUTTON_INSET).dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(Modifier.weight(1f).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                        GroupIcon(box, group["tag"].toString())
                        Column(Modifier.weight(1f, fill = false)) {
                            EmojiText(group["tag"].toString(), AppTextStyle.Callout.of(true).copy(color = scheme.onSurface), maxLines = 1)
                            Spacer(Modifier.height(2.dp))
                            EmojiText(group["selected"] as? String ?: "", AppTextStyle.Caption1.of(true).copy(color = scheme.onSurface.copy(alpha = 0.8f)), maxLines = 1)
                        }
                    }
                    if (expanded) {
                        TonalButtonGroup(TonalButtonSize.Compact) {
                            TonalIconButton(IconAction(AppGlyphs.locate, tr("Scroll to selected"), onLocate), TonalButtonSize.Compact, grouped = true) {
                                GlyphIcon(AppGlyphs.locate, size = 19.dp, color = androidx.compose.material3.LocalContentColor.current)
                            }
                            TonalIconButton(
                                IconAction(AppGlyphs.bolt, tr("Test latency"), if (testing || !box.running) null else onTest, loading = testing),
                                TonalButtonSize.Compact,
                                grouped = true,
                            )
                        }
                    } else {
                        Text(
                            group["type"] as? String ?: if (group["selectable"] == true) "Selector" else "URLTest",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = AppTextStyle.Caption1.of(true).copy(color = scheme.onSurface.copy(alpha = 0.8f)),
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.elasticPress()) {
                        TonalIconButton(IconAction(AppGlyphs.caretDown, tr(if (expanded) "Show less" else "Show more"), onToggle), TonalButtonSize.Compact) {
                            CommonExpandIcon(expanded, size = TonalButtonSize.Compact.icon.dp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupIcon(box: BoxController, name: String) {
    val scheme = BoxTheme.scheme
    when (box.preferences["proxyIconStyle"] ?: "Filled") {
        "Filled" -> Box(
            Modifier
                .padding(end = 12.dp)
                .fillMaxHeight()
                .aspectRatio(1f)
                .background(raisedSurfaceLevel.colorOf(scheme), AppShape.all(AppCorner.XL - HEADER_INSET))
                .padding(6.dp),
            contentAlignment = Alignment.Center,
        ) {
            BoxWithConstraints { GroupMonogram(name, maxHeight, scheme.onSurfaceVariant) }
        }
        "Plain" -> BoxWithConstraints(Modifier.padding(start = 2.dp, end = 10.dp).fillMaxHeight()) {
            GroupMonogram(name, maxHeight, scheme.primary)
        }
        else -> Spacer(Modifier.width(4.dp))
    }
}

/** group_monogram.dart: the first letter or digit of the group's name, or the proxies glyph. */
@Composable
fun GroupMonogram(name: String, size: Dp, color: Color) {
    val letter = Regex("[\\p{L}\\p{N}]").find(name)?.value?.uppercase()
    val mark = letter ?: name.firstOrNull()?.toString() ?: ""
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        if (mark.isEmpty()) {
            GlyphIcon(AppGlyphs.proxies, size = size * .7f, color = color)
        } else {
            val text = size.value * .62f
            Text(mark, maxLines = 1, style = AppTextStyle.custom(text, text, FontWeight.W600).copy(color = color, fontSize = androidx.compose.ui.unit.TextUnit(text / LocalDensity.current.fontScale, androidx.compose.ui.unit.TextUnitType.Sp)))
        }
    }
}

