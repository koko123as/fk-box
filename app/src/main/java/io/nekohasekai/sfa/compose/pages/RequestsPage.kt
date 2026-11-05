package io.nekohasekai.sfa.compose.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.item
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.compose.BoxController
import io.nekohasekai.sfa.compose.Json
import io.nekohasekai.sfa.compose.asDouble
import io.nekohasekai.sfa.compose.asLong
import io.nekohasekai.sfa.compose.bytes
import io.nekohasekai.sfa.compose.json
import io.nekohasekai.sfa.compose.rows
import io.nekohasekai.sfa.compose.theme.AppTextStyle
import io.nekohasekai.sfa.compose.theme.BoxTheme
import io.nekohasekai.sfa.compose.ui.ActionSection
import io.nekohasekai.sfa.compose.ui.AppGlyphs
import io.nekohasekai.sfa.compose.ui.BoxEditGuard
import io.nekohasekai.sfa.compose.ui.BoxPage
import io.nekohasekai.sfa.compose.ui.CapturedBody
import io.nekohasekai.sfa.compose.ui.CenteredLoading
import io.nekohasekai.sfa.compose.ui.CommonScaffold
import io.nekohasekai.sfa.compose.ui.EmptyState
import io.nekohasekai.sfa.compose.ui.ErrorState
import io.nekohasekai.sfa.compose.ui.FormPage
import io.nekohasekai.sfa.compose.ui.GlyphIcon
import io.nekohasekai.sfa.compose.ui.IconAction
import io.nekohasekai.sfa.compose.ui.ItemPosition
import io.nekohasekai.sfa.compose.ui.ListFooter
import io.nekohasekai.sfa.compose.ui.ListItem
import io.nekohasekai.sfa.compose.ui.ListItemCheckbox
import io.nekohasekai.sfa.compose.ui.ListItemOptions
import io.nekohasekai.sfa.compose.ui.ListItemToggle
import io.nekohasekai.sfa.compose.ui.ListStyles
import io.nekohasekai.sfa.compose.ui.LocalBoxNavigator
import io.nekohasekai.sfa.compose.ui.LocalDialogs
import io.nekohasekai.sfa.compose.ui.PopupMenuItem
import io.nekohasekai.sfa.compose.ui.PopupMenuSection
import io.nekohasekai.sfa.compose.ui.RECORD_LIST_GAP
import io.nekohasekai.sfa.compose.ui.RecordDetailRow
import io.nekohasekai.sfa.compose.ui.RecordHeader
import io.nekohasekai.sfa.compose.ui.RecordLabel
import io.nekohasekai.sfa.compose.ui.RecordListItem
import io.nekohasekai.sfa.compose.ui.RecordTimestamp
import io.nekohasekai.sfa.compose.ui.RecordTone
import io.nekohasekai.sfa.compose.ui.SearchState
import io.nekohasekai.sfa.compose.ui.Section
import io.nekohasekai.sfa.compose.ui.TextPillButton
import io.nekohasekai.sfa.compose.ui.bottomInset
import io.nekohasekai.sfa.compose.ui.capturedCookies
import io.nekohasekai.sfa.compose.ui.contentTopPadding
import io.nekohasekai.sfa.compose.ui.recordTextStyles
import io.nekohasekai.sfa.compose.ui.recordTime
import io.nekohasekai.sfa.compose.ui.rememberCapturePoll
import io.nekohasekai.sfa.compose.ui.rememberPageActive
import io.nekohasekai.sfa.compose.ui.setClipboard
import io.nekohasekai.sfa.compose.ui.tr
import kotlinx.coroutines.launch

private const val LOCAL_ONLY = "HTTP debugging is only available for the local service"

@Composable
private fun LocalOnlyPage(title: String) {
    BoxPage(title) { EmptyState(LOCAL_ONLY) }
}

// ---------------------------------------------------------------------------
// pages/requests.dart
// ---------------------------------------------------------------------------

@Composable
fun RequestsPage(box: BoxController) {
    if (box.remote) return LocalOnlyPage("Requests")
    val navigator = LocalBoxNavigator.current
    val dialogs = LocalDialogs.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var limit by remember { mutableIntStateOf(50) }
    var revision by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    val poll = rememberCapturePoll(box, "capture.snapshot", mapOf("query" to query, "limit" to limit), revision)
    fun act(method: String, arguments: Map<String, Any?> = emptyMap()) {
        if (busy) return
        busy = true
        scope.launch {
            box.act(method, arguments)
            busy = false
            revision++
        }
    }
    val snapshot = poll.value
    val state = json(snapshot?.get("state"))
    val page = json(snapshot?.get("page"))
    val active = state["active"] == true
    val entries = rows(page["items"])
    val styles = recordTextStyles()
    val scheme = BoxTheme.scheme
    CommonScaffold(
        title = tr("Requests"),
        loading = busy,
        searchState = SearchState {
            query = it
            limit = 50
        },
        iconActions = listOf(IconAction(AppGlyphs.refresh, tr("Refresh"), poll.refresh)),
        menuItems = listOf(
            PopupMenuItem(tr("Capture settings"), AppGlyphs.sliders, onPressed = if (busy || active) null else ({
                busy = true
                scope.launch {
                    try {
                        val source = json(box.call("capture.settings"))
                        navigator?.pushPage({ RequestSettingsPage(box, json(source["config"]), source) }) {
                            busy = false
                            revision++
                        }
                    } catch (error: Throwable) {
                        box.report(error)
                        busy = false
                        revision++
                    }
                }
            })),
            PopupMenuItem(tr("MITM certificate"), AppGlyphs.lock, onPressed = { navigator?.push { MitmCertificatePage(box) } }),
            PopupMenuItem(tr("Capture logs"), AppGlyphs.logs, onPressed = { navigator?.push { CaptureLogsPage(box) } }),
            PopupMenuItem(tr("Export HAR"), AppGlyphs.arrowDown, onPressed = if (busy || entries.isEmpty()) null else ({ act("capture.export", mapOf("format" to "har", "query" to query)) })),
            PopupMenuItem(tr("Clear requests"), AppGlyphs.delete, onPressed = if (busy) null else ({
                scope.launch { if (dialogs?.confirm("Clear captured requests?") == true) act("capture.clear") }
            })),
        ),
    ) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = contentTopPadding(), bottom = 24.dp + bottomInset())) {
            item(key = "header") {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    val rewrite = state["rewriteActive"] == true
                    Column {
                        ListItemToggle(
                            tr("Capture requests"),
                            active,
                            if (rewrite) ItemPosition.Start else ItemPosition.StartAndEnd,
                            subtitle = tr(if (active) "Capturing new connections" else "Capture stopped"),
                            onChanged = if (busy || snapshot == null || !box.running || box.transitioning) null else ({ act(if (active) "capture.stop" else "capture.start") }),
                        )
                        if (rewrite) {
                            ListItem(
                                title = { Text(tr("Profile HTTP rewriting is active")) },
                                subtitle = { Text("${state["rewriteRules"] ?: 0} · ${tr("Rules remain active when request recording is stopped.")}") },
                                position = ItemPosition.End,
                            )
                        }
                    }
                    if (!box.running) Text(tr("Start the local service before capturing requests"), Modifier.padding(12.dp))
                    if (snapshot != null) {
                        Text("${page["total"] ?: 0} ${tr("Requests")} · ${bytes(page["storedBytes"] as? Number ?: 0)} / 32 MiB", Modifier.padding(12.dp), style = ListStyles.subtitle)
                    }
                    val pageError = page["error"] as? String ?: ""
                    if (pageError.isNotEmpty()) Text(pageError, style = ListStyles.subtitle.copy(color = scheme.error))
                    poll.error?.let { ErrorState(describe(it), poll.refresh) }
                    if (snapshot == null && poll.error == null) CenteredLoading()
                    if (snapshot != null && entries.isEmpty()) {
                        EmptyState(
                            if (query.isEmpty()) "No captured requests" else "No results",
                            message = if (query.isEmpty()) "HTTP and HTTPS capture follow the local service. HTTPS requires a trusted MITM CA. Start capture, then reconnect the target app." else null,
                        )
                    }
                }
            }
            items(entries, key = { it["id"].toString() }) { item ->
                Column(Modifier.padding(bottom = RECORD_LIST_GAP.dp)) {
                    RecordListItem(
                        onTap = { navigator?.push { RequestDetailPage(box, item["id"].toString()) } },
                        header = {
                            RecordHeader {
                                RecordTimestamp(recordTime(item["startedAt"]))
                                RecordLabel(item["method"]?.toString() ?: "")
                                val status = item["status"].asLong() ?: 0
                                RecordLabel(
                                    when {
                                        item["domainOnly"] == true -> tr("Domain only")
                                        status > 0 -> status.toString()
                                        else -> tr(if (item["complete"] == true) "Error" else "Active")
                                    },
                                )
                            }
                        },
                        body = {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(item["url"]?.toString() ?: "", maxLines = 2, overflow = TextOverflow.Ellipsis, style = styles.title)
                                Text("${item["protocol"] ?: ""} · ${item["durationMs"] ?: 0} ms · ${bytes(item["bytes"] as? Number ?: 0)}", style = styles.secondary)
                                val packages = item["packages"] as? List<*> ?: emptyList<Any>()
                                if (packages.isNotEmpty()) Text(packages.joinToString(", "), maxLines = 1, overflow = TextOverflow.Ellipsis, style = styles.secondary)
                                val error = item["error"] as? String ?: ""
                                if (error.isNotEmpty()) Text(error, maxLines = 2, overflow = TextOverflow.Ellipsis, style = styles.secondary.copy(color = scheme.error))
                            }
                        },
                    )
                }
            }
            item(key = "more") {
                if ((page["total"].asLong() ?: 0) > entries.size) {
                    TextPillButton(tr("Show more"), { limit = (limit + 50).coerceIn(1, 300) }, Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }
}

internal fun describe(error: Throwable): String = BoxController.describe(error)

@Composable
fun RequestDetailPage(box: BoxController, id: String) {
    if (box.remote) return LocalOnlyPage("Requests")
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var part by remember { mutableStateOf("Response") }
    var busy by remember { mutableStateOf(false) }
    val copiedText = tr("Copied")
    fun copy(text: String) {
        setClipboard(context, text)
        box.message(copiedText)
    }
    val poll = rememberCapturePoll(box, "capture.detail", mapOf("id" to id))
    val detail = poll.value
    val metadata = json(detail?.get("metadata"))
    val request = json(detail?.get("request"))
    val socket = json(metadata["socket"])
    val tls = json(metadata["tls"])
    val response = part == "Response"
    val message = json(detail?.get(if (response) "response" else "request"))
    val preview = json(detail?.get(if (response) "responsePreview" else "requestPreview"))
    val original = detail?.get("originalUrl") != null
    val notNegotiated = tr("Not negotiated")
    val values = buildList<Pair<String, Any?>> {
        add("URL" to request["url"])
        add("Method" to request["method"])
        add("Created" to localDate(detail?.get("startedAt")))
        if (original) add("Original URL" to detail?.get("originalUrl"))
        add("Duration" to "${detail?.get("durationMs") ?: 0} ms")
        add("Status" to json(detail?.get("response"))["status"])
        add("Protocol" to request["protocol"])
        add((if (original) "Original outbound" else "Outbound") to metadata["outbound"])
        add((if (original) "Original rule matched" else "Rule matched") to metadata["rule"])
        add("App" to ((metadata["packages"] as? List<*>) ?: emptyList<Any>()).joinToString(", "))
        add("UID" to metadata["uid"])
        if (metadata["captureSource"] != null) add("Capture source" to metadata["captureSource"])
        if (metadata["pid"] != null) add("PID" to metadata["pid"])
        if ((metadata["tlsAdapter"] as? String ?: "").isNotEmpty()) add("TLS implementation" to metadata["tlsAdapter"])
        if (tls.isNotEmpty()) {
            add("TLS server name" to tls["serverName"])
            add("Client TLS" to tls["clientVersion"])
            add("Upstream TLS" to tls["upstreamVersion"])
        }
        if ((tls["upstreamServerName"] as? String ?: "").isNotEmpty()) add("Upstream TLS server name" to tls["upstreamServerName"])
        if (tls.isNotEmpty()) {
            add("ALPN" to (if (tls["alpn"]?.toString()?.isNotEmpty() == true) tls["alpn"] else notNegotiated))
            add("Upstream certificate SHA-256" to tls["upstreamCertificate"])
        }
        if (socket.isNotEmpty()) {
            add("Socket FD" to socket["fd"])
            add("Socket inode" to socket["inode"])
        }
        add("Source" to metadata["source"])
        add("Destination" to metadata["destination"])
        if (metadata["originalDestination"] != null) add("Original destination" to metadata["originalDestination"])
        if ((detail?.get("error") as? String ?: "").isNotEmpty()) add("Error" to detail?.get("error"))
        if ((detail?.get("note") as? String ?: "").isNotEmpty()) add("Note" to detail?.get("note"))
    }
    val stackedKeys = setOf("URL", "Original URL", "App", "Error", "Note", "Upstream certificate SHA-256")
    val curlBlocked = detail == null || busy || request["bodyComplete"] != true || request["bodyTruncated"] == true ||
        request["headersTruncated"] == true || rows(request["trailers"]).isNotEmpty() || request["trailersTruncated"] == true || request["method"] == "TLS"
    BoxPage(
        "Request details",
        loading = busy,
        actions = listOf(
            IconAction(AppGlyphs.copy, tr("Copy URL"), if (detail == null) null else ({ copy(request["url"]?.toString() ?: "") })),
            IconAction(AppGlyphs.arrowDown, tr("Export cURL"), if (curlBlocked) null else ({
                busy = true
                scope.launch {
                    box.act("capture.export", mapOf("format" to "curl", "id" to id))
                    busy = false
                }
            })),
        ),
        menuItems = listOf("Request", "Response", "Cookies").map { item ->
            PopupMenuItem(tr(item), section = if (item == "Request") PopupMenuSection(tr("Message type")) else null, checked = item == part, onPressed = { part = item })
        },
    ) {
        poll.error?.let { ErrorState(describe(it), poll.refresh) }
        if (detail == null && poll.error == null) CenteredLoading()
        if (detail != null) {
            Column {
                values.forEachIndexed { index, (key, value) ->
                    RecordDetailRow(tr(key), value?.toString() ?: "—", ItemPosition.of(index, values.size), stacked = key in stackedKeys)
                }
            }
            if (part == "Cookies") {
                CookiesSection(request, false, ::copy)
                CookiesSection(json(detail["response"]), true, ::copy)
            } else {
                HeadersSection(message, response, ::copy)
                androidx.compose.runtime.key("body-$part") { CapturedBody(message, preview) }
                val trailers = rows(message["trailers"])
                if (trailers.isNotEmpty()) {
                    Section(
                        title = tr("Trailers"),
                        footer = if (message["trailersTruncated"] == true) tr("Headers truncated") else null,
                        rows = trailers.map<Json, @Composable (ItemPosition) -> Unit> { trailer ->
                            { position: ItemPosition -> RecordDetailRow(trailer["name"]?.toString() ?: "", trailer["value"]?.toString() ?: "", position, stacked = true) }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun HeadersSection(message: Json, response: Boolean, copy: (String) -> Unit) {
    val headers = rows(message["headers"])
    ActionSection(
        tr(if (response) "Response headers" else "Request headers"),
        listOf(IconAction(AppGlyphs.copy, tr("Copy headers"), if (headers.isEmpty()) null else ({ copy(headers.joinToString("\n") { "${it["name"]}: ${it["value"]}" }) }))),
        footer = if (message["headersTruncated"] == true) tr("Headers truncated") else null,
        rows = if (headers.isEmpty()) {
            listOf<@Composable (ItemPosition) -> Unit> { position -> ListItem(title = { Text(tr("No headers captured")) }, position = position) }
        } else {
            headers.map<Json, @Composable (ItemPosition) -> Unit> { header ->
                { position: ItemPosition -> RecordDetailRow(header["name"]?.toString() ?: "", header["value"]?.toString() ?: "", position, stacked = true) }
            }
        },
    )
}

@Composable
private fun CookiesSection(message: Json, response: Boolean, copy: (String) -> Unit) {
    val cookies = capturedCookies(rows(message["headers"]), response)
    val emptyValue = tr("Empty value")
    val caption = ListStyles.caption
    ActionSection(
        tr(if (response) "Response cookies" else "Request cookies"),
        listOf(IconAction(AppGlyphs.copy, tr("Copy cookies"), if (cookies.isEmpty()) null else ({ copy(cookies.joinToString("\n") { it.raw }) }))),
        footer = if (message["headersTruncated"] == true) tr("Headers truncated") else null,
        rows = if (cookies.isEmpty()) {
            listOf<@Composable (ItemPosition) -> Unit> { position -> ListItem(title = { Text(tr("No cookies captured")) }, position = position) }
        } else {
            cookies.map<io.nekohasekai.sfa.compose.ui.CapturedCookie, @Composable (ItemPosition) -> Unit> { cookie ->
                { position: ItemPosition ->
                    RecordDetailRow(cookie.name, cookie.value, position, stacked = true, copyText = cookie.raw) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(cookie.value.ifEmpty { emptyValue })
                            if (cookie.attributes.isNotEmpty()) Text(cookie.attributes.joinToString("\n"), style = caption)
                        }
                    }
                }
            }
        },
    )
}

// ---------------------------------------------------------------------------
// pages/request_settings.dart
// ---------------------------------------------------------------------------

private fun lines(value: String): List<String> = value.trim().split(Regex("[\\s,;]+")).filter { it.isNotEmpty() }.distinct()

private fun stringList(value: Any?): List<String> = (value as? List<*>)?.map { it.toString() } ?: emptyList()

@Composable
private fun BareField(value: String, onChanged: (String) -> Unit, hint: String?, multiline: Boolean, position: ItemPosition) {
    val scheme = BoxTheme.scheme
    ListItem(
        title = {
            androidx.compose.foundation.layout.Box {
                if (value.isEmpty() && hint != null) Text(hint, style = AppTextStyle.Body.of().copy(color = scheme.onSurfaceVariant))
                BasicTextField(
                    value = value,
                    onValueChange = { onChanged(it.take(65536)) },
                    minLines = if (multiline) 2 else 1,
                    maxLines = if (multiline) 4 else 2,
                    textStyle = AppTextStyle.Body.of().copy(color = scheme.onSurface),
                    cursorBrush = SolidColor(scheme.primary),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = if (multiline) ImeAction.Default else ImeAction.Done,
                        autoCorrectEnabled = false,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        position = position,
    )
}

@Composable
fun RequestSettingsPage(box: BoxController, initial: Json, source: Json = emptyMap()) {
    if (box.remote) return LocalOnlyPage("Capture settings")
    val navigator = LocalBoxNavigator.current
    val scope = rememberCoroutineScope()
    var decrypt by remember { mutableStateOf(stringList(initial["decryptHosts"]).joinToString("\n")) }
    var decryptBypass by remember { mutableStateOf(stringList(initial["decryptBypassHosts"]).joinToString("\n")) }
    var ports by remember { mutableStateOf(((initial["ports"] as? List<*>) ?: listOf(80, 443, 8080, 8443)).joinToString(", ")) }
    var https by remember { mutableStateOf(initial["https"] == true) }
    var bodyKiB by remember { mutableIntStateOf(Math.round((initial["bodyLimit"].asDouble() ?: 65536.0) / 1024).toInt()) }
    var packages by remember { mutableStateOf(stringList(initial["packages"])) }
    var extract by remember { mutableStateOf(initial["extractHosts"] == true) }
    var blockQUIC by remember { mutableStateOf(initial["blockQUIC"] == true) }
    var busy by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val portsError = tr("Ports must be between 1 and 65535")
    fun save() {
        if (busy || box.remote) return
        busy = true
        error = null
        scope.launch {
            try {
                val parsed = lines(ports).map { it.toIntOrNull() ?: -1 }
                if (parsed.isEmpty() || parsed.any { it < 1 || it > 65535 }) throw NumberFormatException()
                box.call(
                    "capture.configure",
                    mapOf(
                        "revision" to source["revision"],
                        "config" to mapOf(
                            "version" to 2, "https" to https, "decryptHosts" to lines(decrypt), "packages" to packages, "ports" to parsed,
                            "bodyLimit" to bodyKiB * 1024, "decryptBypassHosts" to lines(decryptBypass), "extractHosts" to extract, "blockQUIC" to blockQUIC,
                        ),
                    ),
                )
                dirty = false
                busy = false
                navigator?.pop()
            } catch (failure: NumberFormatException) {
                error = portsError
                busy = false
            } catch (failure: Throwable) {
                error = describe(failure)
                busy = false
            }
        }
    }
    fun addDomains(target: Int) {
        navigator?.pushPage({ CaptureDomainsPage(box, select = true) }) { result ->
            val selected = (result as? List<*>)?.map { it.toString() } ?: return@pushPage
            if (selected.isEmpty()) return@pushPage
            if (target == 0) decrypt = (lines(decrypt) + selected).distinct().joinToString("\n") else decryptBypass = (lines(decryptBypass) + selected).distinct().joinToString("\n")
            dirty = true
        }
    }
    @Composable
    fun Field(value: String, onChanged: (String) -> Unit, label: String, footer: String? = null, hint: String? = null, multiline: Boolean = true) {
        Section(title = tr(label), count = 1, footer = footer?.let { tr(it) }) { _, position ->
            BareField(value, {
                onChanged(it)
                dirty = true
            }, hint, multiline, position)
        }
    }
    BoxEditGuard(dirty = dirty, busy = busy) {
        FormPage("Capture settings", onSave = ::save, error = error, saving = busy) {
            ListFooter(tr("Saved on this device. Capture stops when the local service restarts."))
            Section(title = "HTTPS / MITM", count = 2) { index, position ->
                if (index == 0) {
                    ListItemToggle(tr("Decrypt HTTPS"), https, position) {
                        https = it
                        dirty = true
                    }
                } else {
                    ListItem(title = { Text(tr("MITM certificate")) }, position = position, onClick = { navigator?.push { MitmCertificatePage(box) } })
                }
            }
            if (https) {
                Field(decrypt, { decrypt = it }, "HTTPS domains", hint = "*.example.com", footer = "Leave empty to decrypt all HTTPS domains. Exact names and *.example.com are accepted; exclusions take priority.")
                ListItem(title = { Text(tr("Add captured HTTPS domains")) }, onClick = { addDomains(0) })
                Field(decryptBypass, { decryptBypass = it }, "HTTPS excluded domains", hint = "pinned.example.com", footer = "Pass these TLS connections through unchanged. Visible hostnames can still be extracted.")
                ListItem(title = { Text(tr("Add captured HTTPS exclusions")) }, onClick = { addDomains(1) })
                ListFooter(tr("Install and trust the MITM CA, start capture, then reconnect the target app. Only traffic routed through the local service is captured. Server certificates are always verified."))
                Section(count = 1) { _, position ->
                    ListItemToggle(
                        tr("Request TCP fallback for QUIC"),
                        blockQUIC,
                        position,
                        subtitle = tr("Reject only observed QUIC connections whose names match the HTTPS filters. Apps may retry over TCP. Unknown names and other UDP stay unchanged."),
                    ) {
                        blockQUIC = it
                        dirty = true
                    }
                }
            }
            Section(count = 1) { _, position ->
                ListItemToggle(
                    tr("Extract TLS/QUIC domains"),
                    extract,
                    position,
                    subtitle = tr("Collect visible TLS/QUIC hostnames through the local service. These records contain names only."),
                ) {
                    extract = it
                    dirty = true
                }
            }
            Section(title = tr("Filters and storage"), count = 2) { index, position ->
                if (index == 0) {
                    ListItem(
                        title = { Text(tr("Capture applications")) },
                        subtitle = { Text(if (packages.isEmpty()) tr("All applications") else packages.joinToString(", ")) },
                        leading = { GlyphIcon(AppGlyphs.appsList) },
                        position = position,
                        onClick = {
                            navigator?.pushPage({ CaptureAppsPage(box, packages) }) { result ->
                                val selected = (result as? List<*>)?.map { it.toString() } ?: return@pushPage
                                packages = selected
                                dirty = true
                            }
                        },
                    )
                } else {
                    ListItemOptions(tr("Body limit per message"), listOf(64, 128, 256, 1024), bodyKiB, position, { "$it KiB" }) {
                        bodyKiB = it
                        dirty = true
                    }
                }
            }
            Field(ports, { ports = it }, "Capture ports", hint = "80, 443, 8080, 8443", multiline = false, footer = "Keeps up to 300 requests and 32 MiB. Large bodies keep a prefix. Port filters use the connection destination.")
        }
    }
}

@Composable
fun CaptureAppsPage(box: BoxController, initial: List<String>) {
    val navigator = LocalBoxNavigator.current
    val selected = remember { mutableStateListOf<String>().apply { addAll(initial) } }
    var query by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<Json>?>(null) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) {
        error = null
        try {
            apps = rows(box.call("apps.list", mapOf("system" to true, "refresh" to true)))
        } catch (failure: Throwable) {
            error = failure
        }
    }
    val all = (apps ?: emptyList()).toMutableList()
    val installed = all.map { it["package"].toString() }.toSet()
    for (pkg in selected.filter { it !in installed }) all.add(mapOf("name" to pkg, "package" to pkg))
    val visible = all.filter { "${it["name"]} ${it["package"]}".lowercase().contains(query) }
    CommonScaffold(
        title = tr("Capture applications"),
        searchState = SearchState { query = it.lowercase() },
        confirmationAction = IconAction(AppGlyphs.check, tr("Done"), { navigator?.pop(selected.toList().sorted()) }),
        iconActions = listOf(IconAction(AppGlyphs.clearAll, tr("All applications"), { selected.clear() })),
    ) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = contentTopPadding(), bottom = 24.dp + bottomInset())) {
            item(key = "header") {
                Column {
                    Text(
                        tr("An empty selection captures all applications routed through the local service. Unknown owners do not match a selected application. Applications sharing a UID share the same filter."),
                        Modifier.padding(12.dp),
                    )
                    error?.let { ErrorState(describe(it)) { attempt++ } }
                    if (apps == null && error == null) CenteredLoading()
                }
            }
            items(visible, key = { it["package"].toString() }) { app ->
                val pkg = app["package"].toString()
                ListItemCheckbox(app["name"]?.toString() ?: pkg, pkg in selected, null, subtitle = pkg) {
                    if (pkg in selected) selected.remove(pkg) else selected.add(pkg)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// pages/capture_domains.dart
// ---------------------------------------------------------------------------

@Composable
fun CaptureDomainsPage(box: BoxController, query: String = "", select: Boolean = false) {
    if (box.remote) return LocalOnlyPage("Extract domains")
    val context = LocalContext.current
    val navigator = LocalBoxNavigator.current
    val scope = rememberCoroutineScope()
    val selected = remember { mutableStateListOf<String>() }
    var busy by remember { mutableStateOf(false) }
    var domains by remember { mutableStateOf<List<String>?>(null) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    val copiedText = tr("Copied")
    fun copy(text: String) {
        setClipboard(context, text)
        box.message(copiedText)
    }
    LaunchedEffect(attempt) {
        error = null
        domains = null
        try {
            domains = (box.call("capture.domains", mapOf("query" to query)) as? List<*>)?.map { it.toString() } ?: emptyList()
        } catch (failure: Throwable) {
            error = failure
        }
    }
    val list = domains ?: emptyList()
    CommonScaffold(
        title = tr("Extract domains"),
        loading = busy,
        confirmationAction = if (select) IconAction(AppGlyphs.check, tr("Add domains"), if (selected.isEmpty()) null else ({ navigator?.pop(selected.toList().sorted()) })) else null,
        iconActions = buildList {
            add(IconAction(AppGlyphs.copy, tr("Copy domains"), if (list.isEmpty()) null else ({ copy(list.joinToString("\n")) })))
            if (!select) {
                add(
                    IconAction(AppGlyphs.arrowDown, tr("Export domains"), if (list.isEmpty() || busy) null else ({
                        busy = true
                        scope.launch {
                            box.act("capture.export", mapOf("format" to "domains", "query" to query))
                            busy = false
                        }
                    })),
                )
            }
            add(IconAction(AppGlyphs.refresh, tr("Refresh"), { attempt++ }))
        },
    ) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = contentTopPadding(), bottom = 24.dp + bottomInset())) {
            item(key = "header") {
                Column {
                    ListFooter(tr("Domains are extracted from retained requests and visible TLS/QUIC names, using the current search filter. Encrypted hostnames cannot be recovered."))
                    error?.let { ErrorState(describe(it)) { attempt++ } }
                    if (domains == null && error == null) CenteredLoading()
                    if (domains != null && list.isEmpty()) EmptyState("No captured domains")
                }
            }
            items(list, key = { it }) { host ->
                if (select) {
                    ListItemCheckbox(host, host in selected, null) { if (host in selected) selected.remove(host) else selected.add(host) }
                } else {
                    ListItem(title = { Text(host) }, onClick = { copy(host) })
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// pages/capture_logs.dart
// ---------------------------------------------------------------------------

private fun captureLine(log: Json): String {
    val ms = log["receivedAt"].asLong() ?: 0L
    val time = java.time.Instant.ofEpochMilli(ms).toString()
    return "$time [${log["level"]}] ${log["message"]}"
}

@Composable
fun CaptureLogsPage(box: BoxController) {
    if (box.remote) return LocalOnlyPage("Capture logs")
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var statistics by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    val copiedText = tr("Copied")
    fun copy(text: String) {
        setClipboard(context, text)
        box.message(copiedText)
    }
    val poll = rememberCapturePoll(box, "capture.logs", emptyMap(), revision)
    val snapshot = poll.value
    val state = json(snapshot?.get("state"))
    val active = state["active"] == true
    val words = query.lowercase().trim().split(Regex("\\s+"))
    val logs = rows(snapshot?.get("logs")).filter { log -> words.all { "${log["level"]} ${log["message"]}".lowercase().contains(it) } }
        .sortedWith(compareBy<Json>({ it["receivedAt"].asDouble() ?: 0.0 }, { it["id"].asDouble() ?: 0.0 }))
    val text = logs.joinToString("\n", transform = ::captureLine)
    val styles = recordTextStyles()
    CommonScaffold(
        title = tr("Capture logs"),
        loading = busy,
        searchState = SearchState { query = it },
        iconActions = listOf(IconAction(AppGlyphs.refresh, tr("Refresh"), poll.refresh)),
        menuItems = listOf(
            PopupMenuItem(tr("Copy logs"), AppGlyphs.copy, onPressed = if (logs.isEmpty()) null else ({ copy(text) })),
            PopupMenuItem(tr("Export"), AppGlyphs.export, onPressed = if (logs.isEmpty()) null else ({ box.launchAct("document.save", mapOf("name" to "B-box-capture.log", "content" to text)) })),
            PopupMenuItem(tr("Clear"), AppGlyphs.delete, onPressed = if (busy) null else ({
                busy = true
                scope.launch {
                    box.act("capture.logs.clear")
                    busy = false
                    revision++
                }
            })),
        ),
    ) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = contentTopPadding(), bottom = 24.dp + bottomInset())) {
            item(key = "header") {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (snapshot != null) {
                        val statusRows = buildList<@Composable (ItemPosition) -> Unit> {
                            add { p -> ListItem(title = { Text(tr("Status")) }, subtitle = { Text(tr(if (active) "Capturing new connections" else "Capture stopped")) }, position = p) }
                            if (state["rewriteActive"] == true) {
                                add { p -> ListItem(title = { Text(tr("Profile HTTP rewriting is active")) }, subtitle = { Text(tr("Rules remain active when request recording is stopped.")) }, position = p) }
                            }
                            if (active) {
                                add { p -> ListItem(title = { Text(state["backend"]?.toString() ?: "HTTP") }, subtitle = { Text(tr("Reconnect target applications to capture new connections.")) }, position = p) }
                            }
                            if ((state["skipped"].asDouble() ?: 0.0) > 0) add { p -> RecordDetailRow(tr("Connections skipped or interrupted"), state["skipped"].toString(), p, stacked = true) }
                            if (state["backend"] != null) {
                                add { p ->
                                    ListItem(
                                        title = { Text(tr("Capture statistics")) },
                                        subtitle = { Text("TLS ${state["tlsOpened"] ?: 0} · ${tr("Plaintext")} ${state["plaintextConnections"] ?: 0} · ${tr("Parsed")} ${state["parsedRequests"] ?: 0}") },
                                        position = p,
                                        onClick = { statistics = !statistics },
                                    )
                                }
                            }
                            if (statistics) {
                                val stats = listOf(
                                    "TLS handshakes attempted" to "${state["tlsOpened"] ?: 0}",
                                    "TLS handshakes failed" to "${state["tlsFailed"] ?: 0}",
                                    "Connections with plaintext" to "${state["plaintextConnections"] ?: 0} · ${bytes(state["plaintextBytes"] as? Number ?: 0)}",
                                    "Parsed requests" to "${state["parsedRequests"] ?: 0}",
                                    "Requests excluded by filters" to "${state["filteredRequests"] ?: 0}",
                                    "Requests saved" to "${state["recordedRequests"] ?: 0}",
                                    "QUIC connections rejected" to "${state["blockedQUIC"] ?: 0}",
                                )
                                for ((title, value) in stats) add { p -> RecordDetailRow(tr(title), value, p, stacked = true) }
                            }
                            val latest = state["error"] as? String ?: ""
                            if (latest.isNotEmpty()) add { p -> RecordDetailRow(tr("Latest error"), latest, p, stacked = true) }
                        }
                        Column { statusRows.forEachIndexed { index, row -> row(ItemPosition.of(index, statusRows.size)) } }
                    }
                    ListFooter(tr("Recent logs are kept until the app process exits. Export logs to keep them."))
                    poll.error?.let { ErrorState(describe(it), poll.refresh) }
                    if (snapshot == null && poll.error == null) CenteredLoading()
                    if (snapshot != null && logs.isEmpty()) EmptyState(if (query.isBlank()) "No Capture logs" else "No results")
                }
            }
            items(logs.asReversed(), key = { it["id"].toString() }) { log ->
                val level = log["level"]?.toString() ?: "info"
                Column(Modifier.padding(bottom = RECORD_LIST_GAP.dp)) {
                    RecordListItem(
                        onTap = { copy(captureLine(log)) },
                        header = {
                            RecordHeader {
                                RecordTimestamp(recordTime(log["receivedAt"]))
                                RecordLabel(level, if (level == "error") RecordTone.Error else if (level == "warn") RecordTone.Warning else RecordTone.Neutral)
                            }
                        },
                        body = { androidx.compose.foundation.text.selection.SelectionContainer { Text(log["message"]?.toString() ?: "", style = styles.primary) } },
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// pages/mitm_certificate.dart
// ---------------------------------------------------------------------------

@Composable
fun MitmCertificatePage(box: BoxController) {
    if (box.remote) return LocalOnlyPage("MITM certificate")
    val dialogs = LocalDialogs.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<Json?>(null) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var busy by remember { mutableStateOf(false) }
    val active = rememberPageActive()
    val messages = mapOf(
        "capture.ca.install" to tr("Certificate settings opened. Refresh trust after installation."),
        "capture.ca.module" to tr("CA module exported. Install it with your root manager, reboot, then refresh trust."),
        "capture.ca.export" to tr("Public CA certificate exported"),
    )
    fun refresh() {
        if (busy || box.remote) return
        busy = true
        error = null
        scope.launch {
            try {
                val value = json(box.call("capture.ca.snapshot"))
                if (!box.remote) state = value
            } catch (failure: Throwable) {
                error = failure
            } finally {
                busy = false
            }
        }
    }
    // Refresh when the page opens and whenever the app returns to the foreground.
    LaunchedEffect(active) { if (active) refresh() }
    fun run(method: String) {
        val current = state ?: return
        if (busy || box.remote) return
        scope.launch {
            if (method == "capture.ca.generate" && current["exists"] == true) {
                if (dialogs?.confirm("Regenerate the MITM CA? Replace the old certificate in Android and target applications afterwards.") != true) return@launch
            }
            if (box.remote || busy) return@launch
            busy = true
            error = null
            try {
                val result = box.call(method, mapOf("revision" to current["revision"]))
                if (box.remote) return@launch
                if (method == "capture.ca.generate") {
                    state = json(result)
                } else if (result == true) {
                    box.message(messages[method] ?: "")
                }
            } catch (failure: Throwable) {
                error = failure
            } finally {
                busy = false
            }
        }
    }
    val current = state
    val exists = current?.get("exists") == true
    val usable = exists && current?.get("valid") == true && current["keyAvailable"] == true
    val trust = when {
        current?.get("systemTrusted") == true -> "Present in system CA store"
        current?.get("userTrusted") == true -> "Present in user CA store"
        else -> "CA not installed"
    }
    BoxPage("MITM certificate", loading = busy, actions = listOf(IconAction(AppGlyphs.refresh, tr("Refresh trust"), if (busy) null else ({ refresh() })))) {
        ListFooter(tr("HTTPS decryption requires the target app to trust this CA. Apps with certificate pinning or their own trust store may reject it; add those domains to HTTPS exclusions."))
        error?.let { ErrorState(describe(it)) { refresh() } }
        if (current != null) {
            val locked = current["captureActive"] == true || current["rewriteActive"] == true
            val certificateRows = buildList<@Composable (ItemPosition) -> Unit> {
                add { p ->
                    ListItem(
                        title = { Text(tr(if (exists) trust else "No MITM certificate")) },
                        subtitle = { Text(tr("Trust status describes this app process. It does not prove that every target app trusts the CA.")) },
                        position = p,
                    )
                }
                if (exists) {
                    add { p -> RecordDetailRow(tr("Subject"), current["subject"]?.toString() ?: "—", p, stacked = true) }
                    add { p -> RecordDetailRow("SHA-256", current["fingerprint"]?.toString() ?: "—", p, stacked = true) }
                    add { p -> RecordDetailRow(tr("Expires"), localDate(current["expiresAt"]), p) }
                    if (!usable) add { p -> ListItem(title = { Text(tr("Certificate expired or signing key unavailable. Regenerate the CA.")) }, position = p) }
                }
                add { p ->
                    ListItem(
                        title = { Text(tr(if (exists) "Regenerate CA" else "Generate CA")) },
                        subtitle = if (locked) ({ Text(tr("Stop capture and the local service before changing the MITM certificate")) }) else null,
                        position = p,
                        onClick = if (busy || locked) null else ({ run("capture.ca.generate") }),
                    )
                }
            }
            Section(title = tr("Certificate"), rows = certificateRows)
        }
        if (exists) {
            Section(
                title = tr("Install and export"),
                footer = tr("On Android 11 or later, export the certificate first, then choose CA certificate in Android security settings. For system trust, install the exported module with Magisk, KernelSU or APatch and reboot."),
                count = 3,
            ) { index, position ->
                val (label, method) = listOf(
                    "Export public CA certificate" to "capture.ca.export",
                    "Open certificate settings" to "capture.ca.install",
                    "Export system CA module" to "capture.ca.module",
                )[index]
                ListItem(title = { Text(tr(label)) }, position = position, onClick = if (busy || !usable) null else ({ run(method) }))
            }
        }
        ListFooter(tr("The private signing key stays on this device. Exports contain only the public CA. Regeneration requires replacing installed certificates; remove system modules in the root manager and reboot."))
    }
}
