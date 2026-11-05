package io.nekohasekai.sfa.compose.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.item
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.nekohasekai.sfa.compose.BoxController
import io.nekohasekai.sfa.compose.Json
import io.nekohasekai.sfa.compose.asDouble
import io.nekohasekai.sfa.compose.bytes
import io.nekohasekai.sfa.compose.json
import io.nekohasekai.sfa.compose.rows
import io.nekohasekai.sfa.compose.theme.BoxTheme
import io.nekohasekai.sfa.compose.theme.JetBrainsMono
import io.nekohasekai.sfa.compose.ui.AppGlyphs
import io.nekohasekai.sfa.compose.ui.BoxMenu
import io.nekohasekai.sfa.compose.ui.BoxPage
import io.nekohasekai.sfa.compose.ui.BoxProgress
import io.nekohasekai.sfa.compose.ui.CenteredLoading
import io.nekohasekai.sfa.compose.ui.CommonCard
import io.nekohasekai.sfa.compose.ui.CommonScaffold
import io.nekohasekai.sfa.compose.ui.DetailRows
import io.nekohasekai.sfa.compose.ui.EmptyState
import io.nekohasekai.sfa.compose.ui.ErrorState
import io.nekohasekai.sfa.compose.ui.FilledPillButton
import io.nekohasekai.sfa.compose.ui.GlyphIcon
import io.nekohasekai.sfa.compose.ui.IconAction
import io.nekohasekai.sfa.compose.ui.ItemPosition
import io.nekohasekai.sfa.compose.ui.LabeledField
import io.nekohasekai.sfa.compose.ui.ListItem
import io.nekohasekai.sfa.compose.ui.ListItemOpen
import io.nekohasekai.sfa.compose.ui.ListItemOptions
import io.nekohasekai.sfa.compose.ui.ListItemToggle
import io.nekohasekai.sfa.compose.ui.LocalBoxNavigator
import io.nekohasekai.sfa.compose.ui.LocalDialogs
import io.nekohasekai.sfa.compose.ui.PopupMenuItem
import io.nekohasekai.sfa.compose.ui.Section
import io.nekohasekai.sfa.compose.ui.TextPillButton
import io.nekohasekai.sfa.compose.ui.TrailingGlyph
import io.nekohasekai.sfa.compose.ui.bottomInset
import io.nekohasekai.sfa.compose.ui.contentTopPadding
import io.nekohasekai.sfa.compose.ui.tr
import kotlinx.coroutines.launch
import java.util.Locale

// ---------------------------------------------------------------------------
// pages/diagnostics.dart
// ---------------------------------------------------------------------------

@Composable
fun DiagnosticsPage(box: BoxController, quality: Boolean) {
    val dialogs = LocalDialogs.current
    val scope = rememberCoroutineScope()
    val initialState = if (quality) box.quality else box.stun
    var address by remember { mutableStateOf(initialState[if (quality) "configURL" else "server"] as? String ?: "") }
    var outbound by remember { mutableStateOf("") }
    var serial by remember { mutableStateOf(false) }
    var http3 by remember { mutableStateOf(false) }
    var seconds by remember { mutableIntStateOf(30) }
    var asking by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { box.launchAct(if (quality) "quality.stop" else "stun.stop") } }
    val state = if (quality) box.quality else box.stun
    val running = state["isRunning"] == true
    LaunchedEffect(state["showMeteredWarning"]) {
        if (quality && state["showMeteredWarning"] == true && !asking) {
            asking = true
            val result = dialogs?.confirm("Network quality", message = "This test transfers data on a metered network. Continue?", action = "Start") == true
            box.act(if (result) "quality.confirm" else "quality.dismiss")
            asking = false
        }
    }
    val outbounds = (listOf("") + box.outbounds.map { it["tag"].toString() }).distinct()
    val phase = state["phase"].asDouble() ?: -1.0
    val default = tr("Default")
    BoxPage(if (quality) "Network quality" else "STUN test") {
        val rows = buildList<@Composable (ItemPosition) -> Unit> {
            add { p ->
                ListItem(
                    title = { LabeledField(address, { address = it }, tr(if (quality) "URL" else "Address"), readOnly = running) },
                    position = p,
                )
            }
            if (box.running) {
                add { p ->
                    ListItemOptions(tr("Outbound"), outbounds, if (outbound in outbounds) outbound else "", p, { if (it.isEmpty()) default else it }) {
                        if (!running) outbound = it
                    }
                }
            }
            if (quality) {
                add { p -> ListItemToggle(tr("Serial test"), serial, p, onChanged = if (running) null else ({ serial = it })) }
                add { p -> ListItemToggle("HTTP/3", http3, p, onChanged = if (running) null else ({ http3 = it })) }
                add { p -> ListItemOptions(tr("Duration"), listOf(30, 60), seconds, p, { "$it s" }) { if (!running) seconds = it } }
            }
        }
        Section(rows = rows)
        Box(Modifier.fillMaxWidth().padding(vertical = 20.dp)) {
            FilledPillButton(
                tr(if (running) "Stop" else "Start"),
                {
                    if (running) {
                        box.launchAct(if (quality) "quality.stop" else "stun.stop")
                    } else {
                        scope.launch {
                            box.act(
                                if (quality) "quality.start" else "stun.start",
                                mapOf(
                                    (if (quality) "url" else "server") to address.trim(),
                                    "outbound" to outbound, "serial" to serial, "http3" to http3, "seconds" to seconds,
                                ),
                            )
                        }
                    }
                },
                Modifier.fillMaxWidth(),
            )
        }
        if (running) BoxProgress()
        if (phase >= 0) {
            Spacer(Modifier.height(16.dp))
            DetailRows(
                if (quality) {
                    listOf(
                        "Latency" to "${state["idleLatencyMs"] ?: 0} ms",
                        "Download" to String.format(Locale.ROOT, "%.2f Mbps", (state["downloadCapacity"].asDouble() ?: 0.0) / 1000000),
                        "Upload" to String.format(Locale.ROOT, "%.2f Mbps", (state["uploadCapacity"].asDouble() ?: 0.0) / 1000000),
                        "Download RPM" to state["downloadRPM"],
                        "Upload RPM" to state["uploadRPM"],
                    )
                } else {
                    buildList {
                        add("Address" to state["externalAddr"])
                        add("Latency" to "${state["latencyMs"] ?: 0} ms")
                        if (state["natTypeSupported"] == true) {
                            add("NAT mapping" to state["mapping"])
                            add("NAT filtering" to state["filtering"])
                        }
                    }
                },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// pages/reports.dart
// ---------------------------------------------------------------------------

fun reportTitle(kind: String): String = mapOf("crash" to "Crash reports", "oom" to "Memory reports", "power" to "Power reports")[kind] ?: kind

@Composable
fun ReportsPage(box: BoxController, kind: String) {
    val navigator = LocalBoxNavigator.current
    val dialogs = LocalDialogs.current
    val scope = rememberCoroutineScope()
    var reports by remember { mutableStateOf<List<Json>?>(null) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) {
        error = null
        reports = null
        try {
            reports = rows(box.call("reports.list", mapOf("kind" to kind)))
        } catch (failure: Throwable) {
            error = failure
        }
    }
    CommonScaffold(
        title = tr(reportTitle(kind)),
        iconActions = listOf(
            IconAction(AppGlyphs.refresh, tr("Refresh"), { attempt++ }),
            IconAction(AppGlyphs.delete, tr("Clear"), {
                scope.launch {
                    if (dialogs?.confirm("Delete all reports?") == true) {
                        box.act("reports.clear", mapOf("kind" to kind))
                        attempt++
                    }
                }
            }),
        ),
    ) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = contentTopPadding(), bottom = 24.dp + bottomInset())) {
            item(key = "state") {
                error?.let { ErrorState(describe(it)) { attempt++ } }
                if (reports == null && error == null) CenteredLoading()
                if (reports?.isEmpty() == true) EmptyState("No reports")
            }
            items(reports ?: emptyList(), key = { it["id"].toString() }) { report ->
                ListItem(
                    title = { Text(localDate(report["date"])) },
                    subtitle = { Text(report["id"].toString()) },
                    leading = { GlyphIcon(if (report["read"] == true) AppGlyphs.profiles else AppGlyphs.info) },
                    onClick = { navigator?.pushPage({ ReportDetail(box, kind, report["id"].toString()) }) { attempt++ } },
                )
            }
        }
    }
}

@Composable
private fun ReportDetail(box: BoxController, kind: String, id: String) {
    val navigator = LocalBoxNavigator.current
    val dialogs = LocalDialogs.current
    val scope = rememberCoroutineScope()
    var files by remember { mutableStateOf<List<Json>?>(null) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var config by remember { mutableStateOf(false) }
    var logs by remember { mutableStateOf(true) }
    var encrypt by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    val args = mapOf("kind" to kind, "id" to id)
    LaunchedEffect(Unit) {
        try {
            files = rows(box.call("reports.files", args))
        } catch (failure: Throwable) {
            error = failure
        }
    }
    BoxPage(
        reportTitle(kind),
        loading = busy,
        actions = listOf(
            IconAction(AppGlyphs.delete, tr("Delete"), {
                scope.launch {
                    if (dialogs?.confirm("Delete", message = id) == true && box.act("reports.delete", args)) navigator?.pop()
                }
            }),
        ),
    ) {
        val loaded = files ?: emptyList()
        if (error != null) {
            ErrorState(describe(error!!))
        } else {
            Spacer(Modifier.height(24.dp))
            Column {
                loaded.forEachIndexed { index, file ->
                    val fileArgs = args + ("name" to file["name"])
                    ListItem(
                        title = { Text(file["label"].toString()) },
                        subtitle = { Text(bytes(file["size"] as? Number ?: 0)) },
                        position = ItemPosition.of(index, loaded.size),
                        onClick = {
                            if (file["kind"] == "PROFILE") {
                                box.launchAct("reports.save", fileArgs)
                            } else {
                                navigator?.push { ReportText(box, fileArgs, file["label"].toString()) }
                            }
                        },
                        trailing = { TrailingGlyph(AppGlyphs.arrowDown, tr("Export")) { box.launchAct("reports.save", fileArgs) } },
                    )
                }
            }
        }
        Section(title = tr("Export"), count = 4) { index, position ->
            when (index) {
                0 -> ListItemToggle(tr("Include configuration"), config, position) { config = it }
                1 -> ListItemToggle(tr("Include logs"), logs, position) { logs = it }
                2 -> ListItemToggle(tr("Encrypt for sing-box maintainers"), encrypt, position) { encrypt = it }
                else -> ListItem(
                    title = { Text(tr("Export archive")) },
                    position = position,
                    onClick = if (busy) null else ({
                        busy = true
                        scope.launch {
                            box.act("reports.export", args + mapOf("config" to config, "logs" to logs, "encrypt" to encrypt))
                            busy = false
                        }
                    }),
                )
            }
        }
    }
}

@Composable
private fun ReportText(box: BoxController, args: Map<String, Any?>, title: String) {
    var content by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    LaunchedEffect(Unit) {
        try {
            content = box.call("reports.read", args).toString()
        } catch (failure: Throwable) {
            error = failure
        }
    }
    BoxPage(title) {
        when {
            error != null -> ErrorState(describe(error!!))
            content != null -> SelectionContainer { Text(content!!, style = TextStyle(fontFamily = JetBrainsMono, fontSize = 12.sp, color = BoxTheme.scheme.onSurface)) }
            else -> CenteredLoading()
        }
    }
}

// ---------------------------------------------------------------------------
// pages/settings.dart: RemotePage and RemoteForm
// ---------------------------------------------------------------------------

@Composable
fun RemotePage(box: BoxController) {
    val navigator = LocalBoxNavigator.current
    val dialogs = LocalDialogs.current
    val scope = rememberCoroutineScope()
    var servers by remember { mutableStateOf<List<Json>?>(null) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) {
        error = null
        try {
            servers = rows(box.call("remote.list"))
        } catch (failure: Throwable) {
            error = failure
        }
    }
    fun edit(server: Json = emptyMap()) {
        navigator?.openForm(onClosed = { attempt++ }) { RemoteForm(box, server) }
    }
    CommonScaffold(
        title = tr("Remote control"),
        primaryAction = IconAction(AppGlyphs.add, tr("Add server"), { edit() }),
    ) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = contentTopPadding(), bottom = 24.dp + bottomInset())) {
            item(key = "state") {
                if (box.remote) {
                    ListItem(
                        title = { Text(box.dashboard["remote"].toString()) },
                        trailing = { TextPillButton(tr("Disconnect"), { box.launchAct("remote.disconnect") }) },
                    )
                }
                error?.let { ErrorState(describe(it)) }
                if (servers?.isEmpty() == true) EmptyState("No remote servers")
            }
            items(servers ?: emptyList(), key = { it["id"].toString() }) { server ->
                Box(Modifier.padding(bottom = 12.dp)) {
                    CommonCard(onPressed = { box.launchAct("remote.connect", mapOf("id" to server["id"])) }) {
                        ListItem(
                            title = { Text(server["name"].toString()) },
                            subtitle = { Text(server["url"].toString()) },
                            trailing = {
                                BoxMenu(
                                    listOf(
                                        PopupMenuItem(tr("Edit"), AppGlyphs.edit, onPressed = { edit(server) }),
                                        PopupMenuItem(tr("Delete"), AppGlyphs.delete, danger = true, onPressed = {
                                            scope.launch {
                                                if (dialogs?.confirm("Delete", message = server["name"].toString()) == true) {
                                                    box.act("remote.delete", mapOf("id" to server["id"]))
                                                    attempt++
                                                }
                                            }
                                        }),
                                    ),
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RemoteForm(box: BoxController, initial: Json) {
    val navigator = LocalBoxNavigator.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initial["name"] as? String ?: "") }
    var url by remember { mutableStateOf(initial["url"] as? String ?: "") }
    var secret by remember { mutableStateOf(initial["secret"] as? String ?: "") }
    var saving by remember { mutableStateOf(false) }
    BoxPage(
        "Remote control",
        loading = saving,
        confirmationAction = IconAction(AppGlyphs.check, tr("Save"), if (saving) null else ({
            saving = true
            scope.launch {
                val saved = box.act("remote.save", initial + mapOf("name" to name, "url" to url, "secret" to secret))
                saving = false
                if (saved) navigator?.pop()
            }
        })),
    ) {
        LabeledField(name, { name = it }, tr("Name"))
        Spacer(Modifier.height(16.dp))
        LabeledField(url, { url = it }, tr("Address"), keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri)
        Spacer(Modifier.height(16.dp))
        LabeledField(secret, { secret = it }, tr("Secret"), secret = true)
    }
}

// ---------------------------------------------------------------------------
// pages/endpoints.dart
// ---------------------------------------------------------------------------

fun endpointName(kind: String): String = mapOf("tailscale" to "Tailscale", "openconnect" to "OpenConnect", "openvpn" to "OpenVPN", "usbip" to "USB/IP")[kind] ?: kind

fun peerName(peer: Json): String {
    val dns = peer["dnsName"] as? String ?: ""
    return if (dns.isNotEmpty()) dns.removeSuffix(".") else (peer["hostName"] ?: peer["id"] ?: "").toString()
}

fun endpointPeers(endpoint: Json): List<Json> = rows(endpoint["userGroups"]).flatMap { rows(it["peers"]) }

private fun endpointTag(endpoint: Json): String = (endpoint["endpointTag"] ?: endpoint["serverTag"]).toString()

private fun stringsOf(value: Any?): List<String> = (value as? List<*>)?.map { it.toString() } ?: emptyList()

@Composable
fun EndpointsPage(box: BoxController, kind: String) {
    val endpoints = rows(box.toolState[kind])
    BoxPage(endpointName(kind)) {
        if (endpoints.isEmpty()) EmptyState("No endpoints")
        Column {
            endpoints.forEachIndexed { index, endpoint ->
                val tag = endpointTag(endpoint)
                ListItemOpen(tag, ItemPosition.of(index, endpoints.size), leading = AppGlyphs.devices, subtitle = (endpoint["stateText"] ?: "").toString()) {
                    EndpointPage(box, kind, tag)
                }
            }
        }
    }
}

@Composable
private fun EndpointPage(box: BoxController, kind: String, tag: String) {
    val navigator = LocalBoxNavigator.current
    val dialogs = LocalDialogs.current
    val scope = rememberCoroutineScope()
    val scheme = BoxTheme.scheme
    val endpoint = rows(box.toolState[kind]).firstOrNull { endpointTag(it) == tag }
    if (endpoint == null) {
        BoxPage(tag) { EmptyState("Endpoint disconnected") }
        return
    }
    val challenge = json(endpoint[if (kind == "openvpn") "challenge" else "authChallenge"])
    BoxPage(tag) {
        when (kind) {
            "tailscale" -> {
                DetailRows(
                    listOf(
                        "Status" to endpoint["stateText"],
                        "Network" to endpoint["networkName"],
                        "Address" to stringsOf(json(endpoint["selfPeer"])["tailscaleIPs"]).joinToString("\n"),
                    ),
                )
                if ((endpoint["authURL"] as? String ?: "").isNotEmpty()) {
                    ListItem(
                        title = { Text(tr("Sign in")) },
                        trailing = { GlyphIcon(AppGlyphs.openExternal) },
                        onClick = { box.launchAct("url.open", mapOf("url" to endpoint["authURL"])) },
                    )
                }
                if (endpoint["canShareFiles"] == true) {
                    Section(title = "Taildrop", count = 2) { index, position ->
                        if (index == 0) {
                            ListItemOpen(tr("Received files"), position, subtitle = "${endpoint["waitingFileCount"] ?: 0}") { InboxPage(box, tag) }
                        } else {
                            ListItemOpen(tr("Transfers"), position) { TransfersPage(box) }
                        }
                    }
                }
                val peers = endpointPeers(endpoint)
                val none = tr("None")
                Section(title = tr("Exit node"), count = 1) { _, position ->
                    ListItemOptions(
                        tr("Exit node"),
                        listOf("") + peers.filter { it["exitNodeOption"] == true }.map { it["stableID"].toString() },
                        json(endpoint["exitNode"])["stableID"] as? String ?: "",
                        position,
                        { id -> if (id.isEmpty()) none else peerName(peers.firstOrNull { it["stableID"] == id } ?: mapOf("id" to id)) },
                    ) { id -> box.launchAct("tailscale.exit", mapOf("endpoint" to tag, "peer" to id)) }
                }
                for (group in rows(endpoint["userGroups"])) {
                    val groupPeers = rows(group["peers"])
                    Section(title = (group["displayName"] ?: group["loginName"]).toString(), count = groupPeers.size) { index, position ->
                        val peer = groupPeers[index]
                        ListItem(
                            title = { Text(peerName(peer)) },
                            subtitle = { Text(stringsOf(peer["tailscaleIPs"]).joinToString(" · ")) },
                            leading = { GlyphIcon(AppGlyphs.devices, color = if (peer["online"] == true) scheme.primary else scheme.outline) },
                            trailing = { GlyphIcon(AppGlyphs.chevronForward) },
                            position = position,
                            onClick = { navigator?.push { PeerPage(box, tag, peer["id"].toString()) } },
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
                ListItem(
                    title = { Text(tr("Sign out")) },
                    position = ItemPosition.StartAndEnd,
                    onClick = {
                        scope.launch {
                            if (dialogs?.confirm("Sign out", action = "Sign out") == true) box.act("tailscale.logout", mapOf("endpoint" to tag))
                        }
                    },
                )
            }
            "usbip" -> {
                val devices = rows(endpoint["devices"])
                Section(title = tr("Shared devices"), count = devices.size) { index, position ->
                    val device = devices[index]
                    ListItemOpen((device["product"] ?: device["busId"]).toString(), position, subtitle = (device["state"] ?: "").toString()) {
                        BoxPage("USB/IP") { DetailRows(device.entries.map { it.key to it.value }) }
                    }
                }
                if (!box.remote) {
                    Spacer(Modifier.height(24.dp))
                    ListItem(
                        title = { Text(tr("Attach USB device")) },
                        trailing = { GlyphIcon(AppGlyphs.add) },
                        onClick = { navigator?.openForm { UsbPicker(box, tag) } },
                    )
                    for (device in box.usbDevices.filter { it["server"] == tag }) {
                        ListItem(
                            title = { Text(device["label"].toString()) },
                            subtitle = { Text("${device["state"]}\n${device["error"] ?: ""}") },
                            trailing = { TrailingGlyph(AppGlyphs.close, tr("Detach")) { box.launchAct("usb.detach", mapOf("id" to device["id"])) } },
                        )
                    }
                }
            }
            else -> {
                DetailRows(
                    buildList {
                        add("Status" to endpoint["stateText"])
                        if ((endpoint["error"] ?: "") != "") add("Error" to endpoint["error"])
                        addAll(json(endpoint["tunnelInfo"]).entries.map { it.key to it.value })
                    },
                )
                if (challenge.isNotEmpty()) {
                    Section(title = tr("Authentication"), count = 1) { _, position ->
                        ListItem(
                            title = { Text(tr("Authenticate")) },
                            subtitle = { Text((challenge["message"] ?: challenge["banner"] ?: "").toString()) },
                            position = position,
                            onClick = { navigator?.push { AuthenticationPage(box, kind, tag, challenge) } },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PeerPage(box: BoxController, endpointTag: String, id: String) {
    val navigator = LocalBoxNavigator.current
    val scope = rememberCoroutineScope()
    var ping by remember { mutableStateOf(false) }
    val pinging by androidx.compose.runtime.rememberUpdatedState(ping)
    DisposableEffect(Unit) { onDispose { if (pinging) box.launchAct("tailscale.stopPing") } }
    val endpoint = rows(box.toolState["tailscale"]).firstOrNull { it["endpointTag"] == endpointTag }
    val peer = endpoint?.let { e -> endpointPeers(e).firstOrNull { it["id"] == id } }
    if (peer == null) {
        BoxPage("Tailscale") { EmptyState("Peer disconnected") }
        return
    }
    val args = mapOf("endpoint" to endpointTag, "peer" to id)
    BoxPage(peerName(peer)) {
        DetailRows(
            listOf(
                "Address" to stringsOf(peer["tailscaleIPs"]).joinToString("\n"),
                "OS" to peer["os"],
                "Status" to tr(if (peer["online"] == true) "Online" else "Offline"),
                "Download" to bytes(peer["rxBytes"] as? Number ?: 0),
                "Upload" to bytes(peer["txBytes"] as? Number ?: 0),
            ),
        )
        val actions = buildList<@Composable (ItemPosition) -> Unit> {
            add { p ->
                ListItem(title = { Text("Ping") }, position = p, onClick = {
                    ping = true
                    box.launchAct("tailscale.ping", args)
                })
            }
            if (stringsOf(peer["sshHostKeys"]).isNotEmpty()) {
                add { p -> ListItemOpen("SSH", p) { TerminalPage(box, endpointTag, id, peerName(peer)) } }
            }
            if (peer["canReceiveFiles"] == true) {
                add { p ->
                    ListItem(title = { Text(tr("Send file")) }, position = p, onClick = {
                        scope.launch {
                            try {
                                val transfer = box.call("taildrop.send", args)
                                if (transfer != null) navigator?.push { TransfersPage(box) }
                            } catch (error: Throwable) {
                                box.report(error)
                            }
                        }
                    })
                }
            }
        }
        Section(rows = actions)
        if (ping) {
            Spacer(Modifier.height(16.dp))
            if (box.ping["isRunning"] == true) BoxProgress()
            DetailRows(
                buildList {
                    add("Latency" to "${box.ping["latencyMs"] ?: 0} ms")
                    add("Path" to if (box.ping["isDirect"] == true) tr("Direct") else box.ping["derpRegionCode"])
                    if ((box.ping["error"] ?: "") != "") add("Error" to box.ping["error"])
                },
            )
        }
    }
}

@Composable
private fun UsbPicker(box: BoxController, server: String) {
    val navigator = LocalBoxNavigator.current
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<Json>?>(null) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var loading by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) {
        loading = true
        try {
            devices = rows(box.call("usb.list"))
            error = null
        } catch (failure: Throwable) {
            error = failure
        } finally {
            loading = false
        }
    }
    BoxPage("Attach USB device", loading = loading) {
        error?.let { ErrorState(describe(it)) { attempt++ } }
        if (devices?.isEmpty() == true) EmptyState("No USB devices")
        for (device in devices ?: emptyList()) {
            ListItem(title = { Text(device["name"].toString()) }, onClick = {
                scope.launch {
                    if (box.act("usb.attach", mapOf("server" to server, "device" to device["id"]))) navigator?.pop()
                }
            })
        }
    }
}

// ---------------------------------------------------------------------------
// pages/taildrop.dart
// ---------------------------------------------------------------------------

@Composable
fun InboxPage(box: BoxController, endpoint: String) {
    val dialogs = LocalDialogs.current
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) {
        box.launchAct("taildrop.subscribe", mapOf("endpoint" to endpoint))
        onDispose { box.launchAct("taildrop.unsubscribe") }
    }
    val state = box.inbox
    BoxPage("Received files") {
        if (rows(state["files"]).isEmpty() && rows(state["receiving"]).isEmpty()) EmptyState("No received files")
        for (file in rows(state["receiving"])) {
            ListItem(
                title = { Text(file["name"].toString()) },
                subtitle = { Text("${bytes(file["receivedBytes"] as? Number ?: 0)} / ${bytes(file["size"] as? Number ?: 0)}") },
                trailing = {
                    TrailingGlyph(AppGlyphs.close, tr("Cancel")) {
                        box.launchAct("taildrop.cancelReceive", mapOf("endpoint" to endpoint, "name" to file["name"], "sender" to file["senderID"]))
                    }
                },
            )
        }
        for (file in rows(state["files"])) {
            ListItem(
                title = { Text(file["name"].toString()) },
                subtitle = { Text("${bytes(file["size"] as? Number ?: 0)} · ${file["senderName"]}") },
                onClick = { box.launchAct("taildrop.save", mapOf("endpoint" to endpoint, "name" to file["name"])) },
                trailing = {
                    TrailingGlyph(AppGlyphs.delete, tr("Delete")) {
                        scope.launch {
                            if (dialogs?.confirm("Delete", message = file["name"].toString()) == true) {
                                box.act("taildrop.delete", mapOf("endpoint" to endpoint, "name" to file["name"]))
                            }
                        }
                    }
                },
            )
        }
    }
}

@Composable
fun TransfersPage(box: BoxController) {
    BoxPage("Transfers") {
        if (box.transfers.isEmpty()) EmptyState("No transfers")
        for (transfer in box.transfers) {
            val files = rows(transfer["files"])
            val rowsList = buildList<@Composable (ItemPosition) -> Unit> {
                for (file in files) {
                    add { p ->
                        val size = file["size"].asDouble() ?: 0.0
                        val sent = file["sentBytes"].asDouble() ?: 0.0
                        ListItem(
                            title = { Text(file["name"].toString()) },
                            subtitle = {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("${bytes(sent)} / ${bytes(size)}")
                                    BoxProgress(if (file["completed"] == true) 1f else if (size > 0) (sent / size).coerceIn(0.0, 1.0).toFloat() else null)
                                }
                            },
                            position = p,
                        )
                    }
                }
                if (transfer["error"] != null) {
                    add { p -> ListItem(title = { Text(tr("Error")) }, subtitle = { SelectionContainer { Text(transfer["error"].toString()) } }, position = p) }
                }
                add { p ->
                    val finished = transfer["finished"] == true
                    ListItem(
                        title = { Text(tr(if (finished) "Done" else "Cancel")) },
                        position = p,
                        onClick = { box.launchAct(if (finished) "taildrop.dismissSend" else "taildrop.cancelSend", mapOf("id" to transfer["id"])) },
                    )
                }
            }
            Section(title = transfer["peerName"].toString(), rows = rowsList)
        }
    }
}

/** Android ACTION_SEND content stays owned by the native host until sent or discarded. */
@Composable
fun SharePage(box: BoxController, token: String) {
    val navigator = LocalBoxNavigator.current
    val scope = rememberCoroutineScope()
    var sending by remember { mutableStateOf(false) }
    val sent = remember { booleanArrayOf(false) }
    DisposableEffect(Unit) { onDispose { if (!sent[0]) box.launchAct("share.discard", mapOf("token" to token)) } }
    val endpoints = rows(box.toolState["tailscale"]).filter { it["canShareFiles"] == true }
    BoxPage("Send with Taildrop", loading = sending) {
        if (!box.running) {
            EmptyState("Service not started", action = { FilledPillButton(tr("Start"), { box.launchAct("service.start") }) })
        }
        if (box.running && endpoints.isEmpty()) EmptyState("No recipients")
        for (endpoint in endpoints) {
            val self = json(endpoint["selfPeer"])["stableID"]
            val peers = endpointPeers(endpoint).filter { it["canReceiveFiles"] == true && it["stableID"] != self }
            Section(title = endpoint["endpointTag"].toString(), count = peers.size) { index, position ->
                val peer = peers[index]
                ListItem(
                    title = { Text(peerName(peer)) },
                    subtitle = { Text(tr(if (peer["online"] == true) "Online" else "Offline")) },
                    position = position,
                    onClick = if (sending || peer["online"] != true) null else ({
                        sending = true
                        scope.launch {
                            val ok = box.act("share.send", mapOf("token" to token, "endpoint" to endpoint["endpointTag"], "peer" to peer["id"]))
                            sending = false
                            sent[0] = ok
                            if (ok) {
                                navigator?.pop()
                                navigator?.push { TransfersPage(box) }
                            }
                        }
                    }),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// pages/authentication.dart
// ---------------------------------------------------------------------------

@Composable
private fun AuthenticationPage(box: BoxController, kind: String, endpoint: String, challenge: Json) {
    val navigator = LocalBoxNavigator.current
    val scope = rememberCoroutineScope()
    val id = challenge["id"].toString()
    val args = mapOf("endpoint" to endpoint, "challenge" to id)
    val fields = rows(json(challenge["form"])["fields"])
    val texts = remember {
        mutableStateMapOf<String, String>().apply {
            if (kind == "openconnect") {
                for (field in fields) put(field["submissionKey"].toString(), field["value"] as? String ?: "")
            } else {
                for (key in listOf("username", "password", "secret")) put(key, if (key == "username") challenge[key] as? String ?: "" else "")
            }
        }
    }
    var busy by remember { mutableStateOf(false) }
    val complete = remember { booleanArrayOf(false) }
    DisposableEffect(Unit) { onDispose { if (!complete[0]) box.launchAct("$kind.cancel", args) } }
    fun submit() {
        busy = true
        scope.launch {
            val values = texts.toMap()
            val ok = box.act("$kind.submit", if (kind == "openconnect") args + ("values" to values) else args + values)
            busy = false
            if (ok) {
                complete[0] = true
                navigator?.pop()
            }
        }
    }
    @Composable
    fun Field(key: String, label: String, secret: Boolean = false) {
        LabeledField(texts[key] ?: "", { texts[key] = it }, tr(label), Modifier.padding(16.dp), secret = secret)
    }
    val browser = json(challenge["browser"])
    BoxPage("Authentication", loading = busy) {
        for (key in listOf("banner", "message", "error", "previousError")) {
            if ((challenge[key] ?: "") != "") SelectionContainer { Text(challenge[key].toString(), Modifier.padding(16.dp)) }
        }
        if (kind == "openconnect") {
            if (browser.isNotEmpty()) {
                ListItem(title = { Text(tr("Open sign-in page")) }, onClick = {
                    navigator?.pushPage({ AuthBrowser(box, endpoint, id) }) { result ->
                        if (result == true) {
                            complete[0] = true
                            navigator?.pop()
                        }
                    }
                })
            }
            for (field in fields) {
                val key = field["submissionKey"].toString()
                val label = (field["label"] ?: field["name"]).toString()
                if (field["kind"] == "select") {
                    val options = rows(field["options"])
                    ListItemOptions(label, options.map { it["value"].toString() }, texts[key] ?: "", null, { value ->
                        (options.firstOrNull { it["value"] == value } ?: mapOf("label" to value))["label"].toString()
                    }) { texts[key] = it }
                } else {
                    LabeledField(texts[key] ?: "", { texts[key] = it }, label, Modifier.padding(16.dp), secret = field["kind"] == "password")
                }
            }
        } else if (challenge["kind"] == "open-url") {
            ListItem(
                title = { Text(tr("Open sign-in page")) },
                subtitle = { SelectionContainer { Text((challenge["url"] ?: "").toString()) } },
                onClick = { box.launchAct("url.open", mapOf("url" to challenge["url"])) },
            )
        } else {
            if (challenge["kind"] == "credentials") {
                Field("username", "Username")
                Field("password", "Password", secret = true)
            }
            if (challenge["kind"] == "secret" || (challenge["secretMessage"] ?: "") != "") {
                Field("secret", (challenge["secretMessage"] ?: "Verification code").toString(), secret = challenge["echo"] != true)
            }
        }
        val canSubmit = if (kind == "openconnect") json(challenge["form"]).isNotEmpty() else challenge["kind"] != "open-url"
        if (canSubmit) {
            Box(Modifier.fillMaxWidth().padding(16.dp)) {
                FilledPillButton(tr("Submit"), if (busy) null else ({ submit() }), Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun AuthBrowser(box: BoxController, endpoint: String, challenge: String) {
    val navigator = LocalBoxNavigator.current
    LaunchedEffect(Unit) {
        box.browserEvents.collect { event ->
            if (event["id"] == challenge) navigator?.pop(event["success"] == true)
        }
    }
    val context = LocalContext.current
    val view = remember { box.browserView(context, mapOf("endpoint" to endpoint, "challenge" to challenge)) }
    DisposableEffect(view) { onDispose { view.dispose() } }
    CommonScaffold(title = tr("Authentication")) {
        AndroidView(
            factory = { view.getView() },
            modifier = Modifier.fillMaxSize().padding(top = contentTopPadding(), bottom = bottomInset()),
        )
    }
}
