package io.nekohasekai.sfa.compose.pages

import io.nekohasekai.sfa.compose.theme.itemMotion
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import io.nekohasekai.sfa.compose.ui.SelectionArea
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.compose.BoxController
import io.nekohasekai.sfa.compose.BoxData
import io.nekohasekai.sfa.compose.Json
import io.nekohasekai.sfa.compose.asDouble
import io.nekohasekai.sfa.compose.bytes
import io.nekohasekai.sfa.compose.json
import io.nekohasekai.sfa.compose.rows
import io.nekohasekai.sfa.compose.theme.AppTextStyle
import io.nekohasekai.sfa.compose.theme.BoxTheme
import io.nekohasekai.sfa.compose.theme.tabular
import io.nekohasekai.sfa.compose.ui.AppGlyphs
import io.nekohasekai.sfa.compose.ui.BoxPage
import io.nekohasekai.sfa.compose.ui.CommonScaffold
import io.nekohasekai.sfa.compose.ui.GlyphButton
import io.nekohasekai.sfa.compose.ui.GlyphIcon
import io.nekohasekai.sfa.compose.ui.IconAction
import io.nekohasekai.sfa.compose.ui.ItemPosition
import io.nekohasekai.sfa.compose.ui.ListItemOpen
import io.nekohasekai.sfa.compose.ui.LocalBoxNavigator
import io.nekohasekai.sfa.compose.ui.LocalDialogs
import io.nekohasekai.sfa.compose.ui.NullStatus
import io.nekohasekai.sfa.compose.ui.NullStatusIllustration
import io.nekohasekai.sfa.compose.ui.NullStatusSwitcher
import io.nekohasekai.sfa.compose.ui.PopupMenuItem
import io.nekohasekai.sfa.compose.ui.PopupMenuSection
import io.nekohasekai.sfa.compose.ui.RECORD_LIST_GAP
import io.nekohasekai.sfa.compose.ui.RecordArrow
import io.nekohasekai.sfa.compose.ui.RecordDetailRow
import io.nekohasekai.sfa.compose.ui.RecordHeader
import io.nekohasekai.sfa.compose.ui.RecordLabel
import io.nekohasekai.sfa.compose.ui.RecordListItem
import io.nekohasekai.sfa.compose.ui.RecordTimestamp
import io.nekohasekai.sfa.compose.ui.RecordTone
import io.nekohasekai.sfa.compose.ui.SearchState
import io.nekohasekai.sfa.compose.ui.Section
import io.nekohasekai.sfa.compose.ui.bottomInset
import io.nekohasekai.sfa.compose.ui.contentTopPadding
import io.nekohasekai.sfa.compose.ui.recordTextStyles
import io.nekohasekai.sfa.compose.ui.recordTime
import io.nekohasekai.sfa.compose.ui.setClipboard
import io.nekohasekai.sfa.compose.ui.tr
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.SideEffect
import io.nekohasekai.sfa.compose.theme.AppShape
import io.nekohasekai.sfa.compose.ui.LocalViewMode
import io.nekohasekai.sfa.compose.ui.ViewMode
import io.nekohasekai.sfa.compose.ui.RecordFacet
import io.nekohasekai.sfa.compose.ui.RecordFacetKind
import io.nekohasekai.sfa.compose.ui.RecordFilter
import io.nekohasekai.sfa.compose.ui.RecordFilterScope
import io.nekohasekai.sfa.compose.ui.RecordGrouper
import io.nekohasekai.sfa.compose.ui.RecordGrouping
import io.nekohasekai.sfa.compose.ui.TonalChip
import io.nekohasekai.sfa.compose.ui.raisedSurfaceLevel
import io.nekohasekai.sfa.compose.ui.recordFilterAction
import io.nekohasekai.sfa.compose.ui.recordGroups
import io.nekohasekai.sfa.compose.ui.rememberRecordFilterController
import io.nekohasekai.sfa.compose.ui.rememberRecordGroupFolds
import io.nekohasekai.sfa.compose.ui.savedRecordGrouping
import io.nekohasekai.sfa.compose.ui.recordGroupingsWith
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// pages/tools.dart
// ---------------------------------------------------------------------------

private val endpointKinds = listOf("tailscale", "openvpn", "openconnect", "usbip")
private val reportKinds = listOf("crash", "oom", "power")

/**
 * tools.dart's ToolsView (v0.9.3): the "more" navigation items first
 * (Requests, Resources, Logs; whatever the sidebar shows is left out), then
 * Settings and Other. fk-box's own tools (endpoints such as Tailscale with
 * Taildrop, OpenVPN, OpenConnect and USB/IP, the terminal and remote control)
 * join the top section; diagnostics and reports sit under Other, where
 * FlClash keeps its developer page.
 */
@Composable
fun ToolsPage(box: BoxController) {
    BoxPage("Tools") {
        val sidebar = io.nekohasekai.sfa.compose.ui.LocalViewMode.current != io.nekohasekai.sfa.compose.ui.ViewMode.Mobile
        val endpoints = if (box.running) endpointKinds.filter { rows(box.toolState[it]).isNotEmpty() } else emptyList()
        val entries = buildList {
            if (!sidebar) add("Requests")
            add("Resources")
            if (!sidebar) add("Logs")
            endpoints.forEach { add("endpoint:$it") }
            add("Terminal")
            add("Remote control")
        }
        Section(count = entries.size) { index, position ->
            val entry = entries[index]
            when {
                entry == "Requests" -> ListItemOpen(tr("Requests"), position, leading = AppGlyphs.requests) { RequestsPage(box) }
                entry == "Resources" -> ListItemOpen(tr("Resources"), position, leading = AppGlyphs.resources) { ResourcesPage(box) }
                entry == "Logs" -> ListItemOpen(tr("Logs"), position, leading = AppGlyphs.logs) { LogsPage(box) }
                entry.startsWith("endpoint:") -> {
                    val kind = entry.removePrefix("endpoint:")
                    ListItemOpen(endpointName(kind), position, leading = AppGlyphs.devices) { EndpointsPage(box, kind) }
                }
                entry == "Terminal" -> ListItemOpen(tr("Terminal"), position, leading = AppGlyphs.code) { TerminalPage(box) }
                else -> ListItemOpen(tr("Remote control"), position, leading = AppGlyphs.proxies) { RemotePage(box) }
            }
        }
        // tools.dart _getSettingList: theme, access, backup, general (fk-box follows the system language).
        Section(title = tr("Settings"), count = 4) { index, position ->
            when (index) {
                0 -> ListItemOpen(tr("Appearance"), position, leading = AppGlyphs.paintbrush) { AppearancePage(box) }
                1 -> ListItemOpen(tr("Access control"), position, leading = AppGlyphs.appsList) { AccessPage(box) }
                2 -> ListItemOpen(tr("Backup and restore"), position, leading = AppGlyphs.cloudSync) { BackupPage(box) }
                else -> ListItemOpen(tr("General"), position, leading = AppGlyphs.settings) { GeneralSettingsPage(box) }
            }
        }
        Section(title = tr("Other"), count = 3 + reportKinds.size) { index, position ->
            when (index) {
                0 -> ListItemOpen(tr("Network quality"), position, leading = AppGlyphs.speed) { DiagnosticsPage(box, quality = true) }
                1 -> ListItemOpen(tr("STUN test"), position, leading = AppGlyphs.proxies) { DiagnosticsPage(box, quality = false) }
                in 2 until 2 + reportKinds.size -> {
                    val kind = reportKinds[index - 2]
                    ListItemOpen(tr(reportTitle(kind)), position, leading = AppGlyphs.profiles) { ReportsPage(box, kind) }
                }
                else -> ListItemOpen(tr("About"), position, leading = AppGlyphs.info) { AboutPage(box) }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// pages/connections.dart
// ---------------------------------------------------------------------------

private fun matchesWords(text: String, query: String): Boolean {
    val search = text.lowercase()
    return query.lowercase().split(' ').all { search.contains(it) }
}

/** The app a connection came from: its first package, else the process path's last segment. */
private fun connectionApp(c: Json): String {
    val process = c["process"] as? Map<*, *> ?: return ""
    (process["packages"] as? List<*>)?.firstOrNull { it is String && it.isNotEmpty() }?.let { return it as String }
    return (process["path"] as? String)?.substringAfterLast('/').orEmpty()
}

/** The outbounds a connection went through, the matched outbound first. */
private fun connectionChain(c: Json): List<String> {
    val chain = (c["chain"] as? List<*>).orEmpty().mapNotNull { (it as? String)?.takeIf(String::isNotEmpty) }
    return chain.ifEmpty { listOfNotNull((c["outbound"] as? String)?.takeIf(String::isNotEmpty)) }
}

/** common.dart's trackerInfoFacets for fk-box's connection records (no MITM flag on a connection). */
val connectionFacets: List<RecordFacet<Json>> = listOf(
    RecordFacet<Json>(RecordFacetKind.Network, { listOf(it["network"]?.toString()?.lowercase().orEmpty()) }, offered = true, order = listOf("tcp", "udp"), labelOf = { it.uppercase() }),
    RecordFacet<Json>(RecordFacetKind.Rule, { listOf((it["rule"] as? String).orEmpty()) }, offered = true),
    RecordFacet<Json>(RecordFacetKind.App, { listOf(connectionApp(it)) }),
    RecordFacet<Json>(RecordFacetKind.Proxy, { connectionChain(it) }),
)

/** common.dart's trackerInfoGroupers. */
val connectionGroupers: List<RecordGrouper<Json>> = listOf(
    RecordGrouper<Json>(RecordGrouping.App) { connectionApp(it) },
    RecordGrouper<Json>(RecordGrouping.Host) { (it["displayDestination"] ?: it["destination"])?.toString().orEmpty() },
    RecordGrouper<Json>(RecordGrouping.Rule) { (it["rule"] as? String).orEmpty() },
    RecordGrouper<Json>(RecordGrouping.Exit) { connectionChain(it).lastOrNull().orEmpty() },
)

/**
 * requests.dart's _RecentFeed ([recent]: closed connections, the kept history,
 * or every connection when "All" is chosen) and _LiveFeed (running
 * connections with close-all), each with its own record filter and grouping.
 */
@Composable
internal fun ConnectionsFeed(box: BoxController, recent: Boolean, title: String, titleMenuItems: List<PopupMenuItem>) {
    val dialogs = LocalDialogs.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(if (recent) "Closed" else "Active") }
    var sort by remember { mutableStateOf(if (recent) "Created" else "Speed") }
    val pageId = if (recent) "recentRequests" else "connections"
    val filters = rememberRecordFilterController(
        connectionFacets,
        connectionGroupers,
        grouping = savedRecordGrouping(box.preferences, pageId),
        onGroupingChanged = { grouping -> scope.launch { box.setPreference("recordGroupings", recordGroupingsWith(box.preferences, pageId, grouping)) } },
    )
    val pinned = LocalViewMode.current != ViewMode.Mobile
    // Shared with the dashboard's connection stats, so either one leaving keeps the other's feed.
    val connectionsOwner = remember { Any() }
    DisposableEffect(Unit) {
        box.setDataVisible(BoxData.Connections, connectionsOwner, true)
        onDispose { box.setDataVisible(BoxData.Connections, connectionsOwner, false) }
    }
    val key = mapOf("Created" to "createdAt", "Upload" to "uploadTotal", "Download" to "downloadTotal")[sort]
    fun rank(c: Json): Double = if (key == null) (c["upload"].asDouble() ?: 0.0) + (c["download"].asDouble() ?: 0.0) else c[key].asDouble() ?: 0.0
    val searched = box.connections.filter { connection ->
        val closed = (connection["closedAt"].asDouble() ?: 0.0) > 0
        if (filter == "Active" && closed || filter == "Closed" && !closed) return@filter false
        val search = listOf("destination", "domain", "outbound", "network", "rule", "source", "process").joinToString(" ") { connection[it]?.toString() ?: "null" }
        matchesWords(search, query)
    }
    SideEffect { filters.offer(searched) }
    val list = filters.apply(searched).sortedByDescending(::rank)
    val groups = filters.groupsOf(list)
    val folds = rememberRecordGroupFolds(filters.grouping)
    CommonScaffold(
        title = title,
        titleMenuItems = titleMenuItems,
        searchState = SearchState { query = it },
        keywords = filters.keywords(pinned),
        iconActions = if (recent) emptyList() else listOf(
            IconAction(AppGlyphs.clearAll, tr("Close all connections"), if (box.running) ({
                scope.launch {
                    if (dialogs?.confirm("Close all active connections?", action = "Close") == true) box.act("connections.closeAll")
                }
            }) else null),
        ),
        // The running feed is the active list; the recent feed keeps its closed/all choice.
        menuItems = (if (recent) listOf("Closed", "All") else emptyList<String>()).map { item ->
            PopupMenuItem(tr(item), checked = item == filter, section = if (item == "Closed") PopupMenuSection(tr("Connections")) else null, onPressed = { filter = item })
        } + listOf("Speed", "Created", "Upload", "Download").map { item ->
            PopupMenuItem(tr(item), AppGlyphs.sort, checked = item == sort, onPressed = { sort = item })
        } + filters.menuItems(pinned),
    ) {
        RecordFilterScope(filters::add) {
            NullStatusSwitcher(
                isEmpty = list.isEmpty(),
                isSearching = query.isNotBlank() || filters.isFiltering,
                nullStatus = { NullStatus(label = tr("No connections"), illustration = NullStatusIllustration.Connections) },
            ) {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = contentTopPadding(), bottom = 16.dp + bottomInset()),
                    verticalArrangement = Arrangement.spacedBy(RECORD_LIST_GAP.dp),
                ) {
                    if (groups != null) {
                        recordGroups(groups, folds, keyOf = { it["id"].toString() }) { connection -> ConnectionRecord(box, connection) }
                    } else {
                        items(list, key = { it["id"].toString() }) { connection ->
                            Box(itemMotion()) { ConnectionRecord(box, connection) }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConnectionRecord(box: BoxController, c: Json) {
    val navigator = LocalBoxNavigator.current
    val styles = recordTextStyles()
    val scheme = BoxTheme.scheme
    val closed = (c["closedAt"].asDouble() ?: 0.0) > 0
    RecordListItem(
        onTap = { navigator?.push { ConnectionDetail(box, c) } },
        header = {
            RecordHeader(
                trailing = if (!closed && box.running) {
                    {
                        GlyphButton(AppGlyphs.block, tr("Close this connection"), { box.launchAct("connections.close", mapOf("id" to c["id"])) }, size = 28.dp, iconSize = 16.dp, color = scheme.onSurfaceVariant)
                    }
                } else {
                    null
                },
            ) {
                val network = c["network"]?.toString()?.lowercase().orEmpty()
                val app = connectionApp(c)
                RecordTimestamp(recordTime(c["createdAt"]))
                RecordLabel(network.uppercase(), onPressed = recordFilterAction(RecordFilter(RecordFacetKind.Network, network)))
                if (app.isNotEmpty()) RecordLabel(app, onPressed = recordFilterAction(RecordFilter(RecordFacetKind.App, app)))
                if (closed) RecordLabel(tr("Closed"))
            }
        },
        body = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(c["displayDestination"]?.toString() ?: c["destination"]?.toString() ?: "—", style = styles.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                // tracker_info_item.dart: the rule, then each outbound of the chain; a tap filters on it.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    val rule = c["rule"] as? String ?: ""
                    val chain = connectionChain(c)
                    if (rule.isNotEmpty()) {
                        val onRule = recordFilterAction(RecordFilter(RecordFacetKind.Rule, rule))
                        Text(rule, Modifier.then(if (onRule != null) Modifier.clip(AppShape.sm).clickable(onClick = onRule) else Modifier), style = styles.secondary)
                        RecordArrow()
                    }
                    if (chain.isEmpty()) Text("—", style = AppTextStyle.Footnote.of(emphasized = true).copy(color = scheme.primary))
                    chain.forEachIndexed { index, outbound ->
                        if (index > 0) RecordArrow()
                        val last = index == chain.lastIndex
                        TonalChip(
                            outbound,
                            color = if (last) scheme.primary.copy(alpha = 0.12f) else raisedSurfaceLevel.colorOf(scheme),
                            foregroundColor = if (last) scheme.primary else scheme.onSurfaceVariant,
                            onPressed = recordFilterAction(RecordFilter(RecordFacetKind.Proxy, outbound)),
                        )
                    }
                }
                Text(c["source"]?.toString() ?: "—", style = styles.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (upload in listOf(true, false)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            GlyphIcon(if (upload) AppGlyphs.arrowUp else AppGlyphs.arrowDown, size = 12.dp, color = scheme.onSurfaceVariant)
                            val total = c[if (upload) "uploadTotal" else "downloadTotal"] as? Number ?: 0
                            val rate = c[if (upload) "upload" else "download"] as? Number ?: 0
                            Text("${bytes(total)} · ${bytes(rate)}/s", style = styles.secondary.tabular)
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun ConnectionDetail(box: BoxController, initial: Json) {
    val context = LocalContext.current
    var cached by remember { mutableStateOf(initial) }
    val live = box.connections.firstOrNull { it["id"] == initial["id"] }
    LaunchedEffect(live) { if (live != null) cached = live }
    val present = live != null
    val c = live ?: cached
    val closedAt = c["closedAt"].asDouble() ?: 0.0
    val values = buildList<Pair<String, Any?>> {
        add("Destination" to c["displayDestination"])
        add("Source" to c["source"])
        add("Network" to c["network"])
        add("Protocol" to c["protocol"])
        add("Inbound" to c["inbound"])
        add("Outbound" to c["outbound"])
        add("Rule matched" to c["rule"])
        add("Chain" to ((c["chain"] as? List<*>) ?: emptyList<Any>()).joinToString(" → "))
        add("Created" to localDate(c["createdAt"]))
        if (closedAt > 0) add("Closed" to localDate(c["closedAt"]))
        add("Upload" to bytes(c["uploadTotal"] as? Number ?: 0))
        add("Download" to bytes(c["downloadTotal"] as? Number ?: 0))
        if (c["process"] != null) add("Process" to json(c["process"]).entries.joinToString("\n") { "${it.key}: ${it.value}" })
    }
    val copied = tr("Copied")
    BoxPage(
        "Details",
        actions = listOf(
            IconAction(AppGlyphs.copy, tr("Copy"), {
                setClipboard(context, values.joinToString("\n") { "${it.first}: ${it.second}" })
                box.message(copied)
            }),
            IconAction(AppGlyphs.close, tr("Close this connection"), if (box.running && present && closedAt == 0.0) ({ box.launchAct("connections.close", mapOf("id" to c["id"])) }) else null),
        ),
    ) {
        Spacer(Modifier.height(0.dp))
        Column {
            values.forEachIndexed { index, (title, value) ->
                RecordDetailRow(tr(title), value?.toString() ?: "—", ItemPosition.of(index, values.size))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// pages/logs.dart
// ---------------------------------------------------------------------------

private val logPriorities = mapOf("panic" to 0, "fatal" to 1, "error" to 2, "warn" to 3, "warning" to 3, "info" to 4, "debug" to 5, "trace" to 6)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LogsPage(box: BoxController) {
    BoxDataSubscription(box, BoxData.Logs)
    var query by remember { mutableStateOf("") }
    var level by remember { mutableStateOf("Default") }
    var paused by remember { mutableStateOf<List<Json>?>(null) }
    val scope = rememberCoroutineScope()
    val filters = rememberRecordFilterController(logFacets)
    val pinned = LocalViewMode.current != ViewMode.Mobile
    fun matchesLevel(item: Json, defaultLevel: String?): Boolean {
        val itemLevel = item["level"].toString().lowercase()
        if (level == "Default") {
            // Before the core reports its level, keep received records available.
            val threshold = logPriorities[defaultLevel?.lowercase()] ?: return true
            val priority = logPriorities[itemLevel]
            return priority != null && priority <= threshold
        }
        return level == "All" || itemLevel == level.lowercase()
    }
    val words = query.lowercase().trim().split(Regex("\\s+"))
    val searched = (paused ?: box.logs).filter { item ->
        matchesLevel(item, box.defaultLogLevel) && words.all { "${item["level"]} ${item["message"]}".lowercase().contains(it) }
    }
    SideEffect { filters.offer(searched) }
    val logs = filters.apply(searched)
    // reverse: true, as the Flutter list: the newest record sits at the bottom and the view follows it.
    val reversed = logs.asReversed()
    val state = rememberLazyListState()
    LaunchedEffect(state) {
        snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }.collect { (index, offset) ->
            if (index == 0 && offset == 0) {
                paused = null
            } else if (paused == null && state.isScrollInProgress) {
                paused = box.logs.toList()
            }
        }
    }
    CommonScaffold(
        title = tr("Logs"),
        searchState = SearchState { query = it },
        keywords = filters.keywords(pinned),
        iconActions = listOf(
            IconAction(AppGlyphs.export, tr("Export"), {
                box.launchAct(
                    "document.save",
                    mapOf("name" to "B-box.log", "content" to logs.joinToString("\n") { "${recordTime(it["receivedAt"])} [${it["level"]}] ${it["message"]}" }),
                )
            }),
        ),
        menuItems = buildList {
            add(
                PopupMenuItem(
                    tr(if (paused == null) "Pause" else "Resume"),
                    if (paused == null) AppGlyphs.playPause(1.0) else AppGlyphs.playPause(0.0),
                    onPressed = {
                        if (paused == null) {
                            paused = box.logs.toList()
                        } else {
                            paused = null
                            scope.launch { state.scrollToItem(0) }
                        }
                    },
                ),
            )
            add(PopupMenuItem(tr("Clear"), AppGlyphs.delete, onPressed = { box.launchAct("logs.clear") { if (it) paused = null } }))
            for (item in listOf("Default", "All", "Trace", "Debug", "Info", "Warn", "Error", "Fatal", "Panic")) {
                val label = if (item == "Default") {
                    tr("Follow configuration") + (box.defaultLogLevel?.let { " (${trLevel(it)})" } ?: "")
                } else {
                    tr(item)
                }
                add(PopupMenuItem(label, checked = level == item, section = if (item == "Default") PopupMenuSection(tr("Log level")) else null, onPressed = { level = item }))
            }
            addAll(filters.menuItems(pinned))
        },
    ) {
        RecordFilterScope(filters::add) {
            NullStatusSwitcher(
                isEmpty = logs.isEmpty(),
                isSearching = query.isNotBlank() || level !in listOf("Default", "All") || filters.isFiltering,
                nullStatus = { NullStatus(label = tr("No logs"), illustration = NullStatusIllustration.Logs) },
            ) {
                val styles = recordTextStyles()
                LazyColumn(
                    Modifier.fillMaxSize(),
                    state = state,
                    reverseLayout = true,
                    contentPadding = PaddingValues(top = contentTopPadding(), bottom = 16.dp + bottomInset()),
                    verticalArrangement = Arrangement.spacedBy(RECORD_LIST_GAP.dp, Alignment.Bottom),
                ) {
                    itemsIndexed(reversed, key = { _, log -> log["id"].toString() }) { _, log ->
                        val itemLevel = log["level"].toString().lowercase()
                        val tone = when (itemLevel) {
                            "error", "fatal", "panic" -> RecordTone.Error
                            "warn", "warning" -> RecordTone.Warning
                            else -> RecordTone.Neutral
                        }
                        RecordListItem(
                            onTap = {},
                            modifier = itemMotion(),
                            header = {
                                RecordHeader {
                                    if ((log["receivedAt"].asDouble() ?: 0.0) > 0) RecordTimestamp(recordTime(log["receivedAt"]))
                                    RecordLabel(itemLevel, tone, onPressed = recordFilterAction(RecordFilter(RecordFacetKind.Level, itemLevel)))
                                }
                            },
                            body = { SelectionArea { Text(log["message"].toString(), style = styles.primary) } },
                        )
                    }
                }
            }
        }
    }
}

/** common.dart's logFacets: the level, in the core's order. */
private val logFacets: List<RecordFacet<Json>> = listOf(
    RecordFacet<Json>(
        RecordFacetKind.Level,
        { listOf(it["level"].toString().lowercase()) },
        offered = true,
        order = listOf("trace", "debug", "info", "warn", "warning", "error", "fatal", "panic"),
    ),
)

@Composable
private fun trLevel(level: String): String = tr(level)

