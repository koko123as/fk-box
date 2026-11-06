package io.nekohasekai.sfa.compose.pages

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
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
import io.nekohasekai.sfa.compose.ui.EmptyState
import io.nekohasekai.sfa.compose.ui.GlyphButton
import io.nekohasekai.sfa.compose.ui.GlyphIcon
import io.nekohasekai.sfa.compose.ui.IconAction
import io.nekohasekai.sfa.compose.ui.ItemPosition
import io.nekohasekai.sfa.compose.ui.ListItemOpen
import io.nekohasekai.sfa.compose.ui.ListItemUnavailable
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
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// pages/tools.dart
// ---------------------------------------------------------------------------

private val endpointKinds = listOf("tailscale", "openvpn", "openconnect", "usbip")
private val reportKinds = listOf("crash", "oom", "power")

/** tools.dart's ToolsPage: the fourth top-level destination. */
@Composable
fun ToolsPage(box: BoxController) {
    BoxPage("Tools") {
        // Requests (HTTP capture) is a sing-box feature: shown greyed on Xray/mihomo.
        val core = box.selectedProfile?.get("resolvedCore") as? String ?: "sing-box"
        val entries = buildList {
            add("Requests")
            add("Connections")
            add("Logs")
            add("Terminal")
            add("Remote control")
        }
        Section(count = entries.size) { index, position ->
            when (entries[index]) {
                "Requests" -> if (core == "sing-box") {
                    ListItemOpen(tr("Requests"), position, leading = AppGlyphs.filter) { RequestsPage(box) }
                } else {
                    ListItemUnavailable(tr("Requests"), position, leading = AppGlyphs.filter, reason = tr("HTTP capture needs the sing-box core"))
                }
                "Connections" -> ListItemOpen(tr("Connections"), position, leading = AppGlyphs.connections) { ConnectionsPage(box) }
                "Logs" -> ListItemOpen(tr("Logs"), position, leading = AppGlyphs.logs) { LogsPage(box) }
                "Terminal" -> ListItemOpen(tr("Terminal"), position, leading = AppGlyphs.code) { TerminalPage(box) }
                else -> ListItemOpen(tr("Remote control"), position, leading = AppGlyphs.proxies) { RemotePage(box) }
            }
        }
        Section(title = tr("Settings"), count = 4) { index, position ->
            when (index) {
                0 -> ListItemOpen(tr("Appearance"), position, leading = AppGlyphs.paintbrush) { AppearancePage(box) }
                1 -> ListItemOpen(tr("General"), position, leading = AppGlyphs.settings) { GeneralSettingsPage(box) }
                2 -> ListItemOpen(tr("Access control"), position, leading = AppGlyphs.appsList) { AccessPage(box) }
                else -> ListItemOpen(tr("Backup and restore"), position, leading = AppGlyphs.cloudSync) { BackupPage(box) }
            }
        }
        Section(title = tr("Network diagnostics"), count = 2) { index, position ->
            if (index == 0) {
                ListItemOpen(tr("Network quality"), position, leading = AppGlyphs.speed) { DiagnosticsPage(box, quality = true) }
            } else {
                ListItemOpen(tr("STUN test"), position, leading = AppGlyphs.proxies) { DiagnosticsPage(box, quality = false) }
            }
        }
        Section(title = tr("Reports"), count = reportKinds.size) { index, position ->
            val kind = reportKinds[index]
            ListItemOpen(tr(reportTitle(kind)), position, leading = AppGlyphs.profiles) { ReportsPage(box, kind) }
        }
        if (box.running) {
            val kinds = endpointKinds.filter { rows(box.toolState[it]).isNotEmpty() }
            Section(title = tr("Endpoints"), count = kinds.size) { index, position ->
                val kind = kinds[index]
                ListItemOpen(endpointName(kind), position, leading = AppGlyphs.devices) { EndpointsPage(box, kind) }
            }
        }
        Section(title = tr("Other"), count = 1) { _, position ->
            ListItemOpen(tr("About"), position, leading = AppGlyphs.info) { AboutPage(box) }
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

@Composable
fun ConnectionsPage(box: BoxController) {
    val dialogs = LocalDialogs.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("Active") }
    var sort by remember { mutableStateOf("Speed") }
    DisposableEffect(Unit) {
        box.launchAct("connections.visible", mapOf("visible" to true))
        onDispose { box.launchAct("connections.visible", mapOf("visible" to false)) }
    }
    val key = mapOf("Created" to "createdAt", "Upload" to "uploadTotal", "Download" to "downloadTotal")[sort]
    fun rank(c: Json): Double = if (key == null) (c["upload"].asDouble() ?: 0.0) + (c["download"].asDouble() ?: 0.0) else c[key].asDouble() ?: 0.0
    val list = box.connections.filter { connection ->
        val closed = (connection["closedAt"].asDouble() ?: 0.0) > 0
        if (filter == "Active" && closed || filter == "Closed" && !closed) return@filter false
        val search = listOf("destination", "domain", "outbound", "network", "rule", "source", "process").joinToString(" ") { connection[it]?.toString() ?: "null" }
        matchesWords(search, query)
    }.sortedByDescending(::rank)
    CommonScaffold(
        title = tr("Connections"),
        searchState = SearchState { query = it },
        iconActions = listOf(
            IconAction(AppGlyphs.clearAll, tr("Close all connections"), if (box.running) ({
                scope.launch {
                    if (dialogs?.confirm("Close all active connections?", action = "Close") == true) box.act("connections.closeAll")
                }
            }) else null),
        ),
        menuItems = listOf("Active", "Closed", "All").map { item ->
            PopupMenuItem(tr(item), checked = item == filter, section = if (item == "Active") PopupMenuSection(tr("Connections")) else null, onPressed = { filter = item })
        } + listOf("Speed", "Created", "Upload", "Download").map { item ->
            PopupMenuItem(tr(item), AppGlyphs.sort, checked = item == sort, onPressed = { sort = item })
        },
    ) {
        NullStatusSwitcher(
            isEmpty = list.isEmpty(),
            isSearching = query.isNotBlank(),
            nullStatus = { NullStatus(label = tr("No connections"), illustration = NullStatusIllustration.Connections) },
        ) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = contentTopPadding(), bottom = 16.dp + bottomInset()),
                verticalArrangement = Arrangement.spacedBy(RECORD_LIST_GAP.dp),
            ) {
                items(list, key = { it["id"].toString() }) { connection -> ConnectionRecord(box, connection) }
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
                RecordTimestamp(recordTime(c["createdAt"]))
                RecordLabel(c["network"].toString().uppercase())
                if (closed) RecordLabel(tr("Closed"))
            }
        },
        body = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(c["displayDestination"]?.toString() ?: c["destination"]?.toString() ?: "—", style = styles.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val rule = c["rule"] as? String ?: ""
                    if (rule.isNotEmpty()) {
                        Text(rule, style = styles.secondary)
                        RecordArrow()
                    }
                    Text(c["outbound"]?.toString() ?: "—", style = AppTextStyle.Footnote.of(emphasized = true).copy(color = scheme.primary))
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
    val logs = (paused ?: box.logs).filter { item ->
        matchesLevel(item, box.defaultLogLevel) && words.all { "${item["level"]} ${item["message"]}".lowercase().contains(it) }
    }
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
        },
    ) {
        NullStatusSwitcher(
            isEmpty = logs.isEmpty(),
            isSearching = query.isNotBlank() || level !in listOf("Default", "All"),
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
                        header = {
                            RecordHeader {
                                if ((log["receivedAt"].asDouble() ?: 0.0) > 0) RecordTimestamp(recordTime(log["receivedAt"]))
                                RecordLabel(itemLevel, tone)
                            }
                        },
                        body = { SelectionContainer { Text(log["message"].toString(), style = styles.primary) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun trLevel(level: String): String = tr(level)

