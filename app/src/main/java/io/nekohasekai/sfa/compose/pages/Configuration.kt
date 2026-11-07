@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.nekohasekai.sfa.compose.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import io.nekohasekai.sfa.compose.ui.SelectionArea
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.compose.BoxController
import io.nekohasekai.sfa.compose.Json
import io.nekohasekai.sfa.compose.json
import io.nekohasekai.sfa.compose.rows
import io.nekohasekai.sfa.compose.theme.AppCorner
import io.nekohasekai.sfa.compose.theme.AppShape
import io.nekohasekai.sfa.compose.theme.BoxTheme
import io.nekohasekai.sfa.compose.ui.AppGlyphs
import io.nekohasekai.sfa.compose.ui.BoxEditGuard
import io.nekohasekai.sfa.compose.ui.BoxMenu
import io.nekohasekai.sfa.compose.ui.BoxPage
import io.nekohasekai.sfa.compose.ui.CommonCard
import io.nekohasekai.sfa.compose.ui.CommonCircleLoading
import io.nekohasekai.sfa.compose.ui.CommonScaffold
import io.nekohasekai.sfa.compose.ui.ContextMenuRegion
import io.nekohasekai.sfa.compose.ui.ErrorState
import io.nekohasekai.sfa.compose.ui.GlyphIcon
import io.nekohasekai.sfa.compose.ui.IconAction
import io.nekohasekai.sfa.compose.ui.ItemPosition
import io.nekohasekai.sfa.compose.ui.ListFooter
import io.nekohasekai.sfa.compose.ui.ListItem
import io.nekohasekai.sfa.compose.ui.ListItemInput
import io.nekohasekai.sfa.compose.ui.ListItemOpen
import io.nekohasekai.sfa.compose.ui.ListItemOptions
import io.nekohasekai.sfa.compose.ui.ListItemToggle
import io.nekohasekai.sfa.compose.ui.LocalBoxNavigator
import io.nekohasekai.sfa.compose.ui.LocalDialogs
import io.nekohasekai.sfa.compose.ui.NullStatus
import io.nekohasekai.sfa.compose.ui.NullStatusIllustration
import io.nekohasekai.sfa.compose.ui.PopupMenuItem
import io.nekohasekai.sfa.compose.ui.ReorderableColumn
import io.nekohasekai.sfa.compose.ui.Section
import io.nekohasekai.sfa.compose.ui.TonalButton
import io.nekohasekai.sfa.compose.ui.TonalButtonSize
import io.nekohasekai.sfa.compose.ui.appBarInset
import io.nekohasekai.sfa.compose.ui.bottomInset
import io.nekohasekai.sfa.compose.ui.contentTopPadding
import io.nekohasekai.sfa.compose.ui.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json as KJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

// ---------------------------------------------------------------------------
// JSON as plain values, in order (dart:convert's jsonDecode / JsonEncoder.withIndent('  '))
// ---------------------------------------------------------------------------

private val configJson = KJson {
    prettyPrint = true
    prettyPrintIndent = "  "
}

fun decodeJson(text: String): Any? = fromElement(configJson.parseToJsonElement(text))

fun encodeJson(value: Any?): String = configJson.encodeToString(JsonElement.serializer(), toElement(value))

private fun fromElement(element: JsonElement): Any? = when (element) {
    is JsonNull -> null
    is JsonObject -> LinkedHashMap<String, Any?>().apply { for ((key, item) in element) put(key, fromElement(item)) }
    is JsonArray -> element.map(::fromElement)
    is JsonPrimitive -> when {
        element.isString -> element.content
        element.booleanOrNull != null -> element.booleanOrNull
        element.longOrNull != null -> element.longOrNull
        else -> element.doubleOrNull ?: element.content
    }
}

private fun toElement(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to toElement(it.value) })
    is List<*> -> JsonArray(value.map(::toElement))
    is Boolean -> JsonPrimitive(value)
    is Int -> JsonPrimitive(value)
    is Long -> JsonPrimitive(value)
    is Double -> if (value % 1.0 == 0.0 && kotlin.math.abs(value) < 1e15) JsonPrimitive(value.toLong()) else JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    else -> JsonPrimitive(value.toString())
}

// ---------------------------------------------------------------------------
// pages/configuration.dart
// ---------------------------------------------------------------------------

/** ProfileConfigStore: the profile's formatted JSON, its stats and revision, saved through profiles.write. */
@Stable
class ProfileConfigStore(private val box: BoxController, val profile: Json, private val scope: CoroutineScope) {
    var root by mutableStateOf<Map<String, Any?>>(emptyMap())
        private set
    var stats by mutableStateOf<Json>(emptyMap())
        private set
    var revision by mutableStateOf("")
        private set
    var error by mutableStateOf<String?>(null)
    var ready by mutableStateOf(false)
        private set
    var saving by mutableStateOf(false)
        private set
    var loading by mutableStateOf(false)
        private set
    private var disposed = false
    private var generation = 0

    @Suppress("UNCHECKED_CAST")
    private fun receive(data: Json) {
        root = (decodeJson(data["formatted"] as String) as? Map<String, Any?>) ?: emptyMap()
        stats = json(data["stats"])
        revision = data["revision"] as String
        ready = true
    }

    fun load() {
        if (disposed || saving) return
        val current = ++generation
        loading = true
        error = null
        scope.launch {
            try {
                val data = json(box.call("profiles.config", mapOf("id" to profile["id"])))
                if (!disposed && current == generation) receive(data)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                if (!disposed && current == generation) error = BoxController.describe(failure)
            } finally {
                if (!disposed && current == generation) loading = false
            }
        }
    }

    fun value(path: List<String>): Any? {
        var result: Any? = root
        for (key in path) {
            if (result !is Map<*, *>) return null
            result = result[key]
        }
        return result
    }

    fun entries(path: List<String>): List<Json> = rows(value(path))
    fun tags(path: List<String>): List<String> = entries(path).mapNotNull { it["tag"] as? String }

    /** Writes [value] at [path] (or removes it) over the loaded revision; [expectedRevision] guards a form opened earlier. */
    suspend fun set(path: List<String>, value: Any?, remove: Boolean = false, expectedRevision: String? = null, rethrowFailure: Boolean = false): Boolean {
        if (disposed || !ready || saving || loading) return false
        if (expectedRevision != null && expectedRevision != revision) {
            val failure = IllegalStateException("Configuration changed; reload it before saving")
            error = BoxController.describe(failure)
            if (rethrowFailure) throw failure
            box.report(failure)
            return false
        }
        @Suppress("UNCHECKED_CAST")
        val candidate = (decodeJson(encodeJson(root)) as? MutableMap<String, Any?>) ?: LinkedHashMap()
        var parent: MutableMap<String, Any?> = candidate
        for (key in path.dropLast(1)) {
            @Suppress("UNCHECKED_CAST")
            val next = (parent[key] as? Map<String, Any?>)?.let { LinkedHashMap(it) } ?: LinkedHashMap()
            parent[key] = next
            parent = next
        }
        if (remove) parent.remove(path.last()) else parent[path.last()] = value
        saving = true
        error = null
        return try {
            val data = json(box.call("profiles.write", mapOf("id" to profile["id"], "revision" to revision, "content" to encodeJson(candidate))))
            if (!disposed) receive(data)
            true
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            error = BoxController.describe(failure)
            if (rethrowFailure) throw failure
            box.report(failure)
            false
        } finally {
            saving = false
        }
    }

    fun launchSet(path: List<String>, value: Any?, remove: Boolean = false) {
        scope.launch { set(path, value, remove) }
    }

    fun dispose() {
        disposed = true
    }
}

@Composable
fun ProfileConfigurationPage(box: BoxController, profile: Json? = null, resources: Boolean = false) {
    val scope = rememberCoroutineScope()
    val navigator = LocalBoxNavigator.current
    val target = profile ?: box.selectedProfile
    val store = remember(target?.get("id")) {
        if (target != null && !box.remote) ProfileConfigStore(box, target, scope).also { it.load() } else null
    }
    DisposableEffect(store) { onDispose { store?.dispose() } }
    val title = tr(if (resources) "Resources" else "Advanced configuration")
    if (store == null) {
        CommonScaffold(title = title) {
            NullStatus(
                label = tr(if (box.remote) "Disconnect remote session to edit local profiles" else "No profile"),
                illustration = NullStatusIllustration.Profile,
                action = if (box.remote) null else ({
                    TonalButton(tr("Profiles"), { navigator?.openForm { ProfilesPage(box, picker = true) } }, glyph = AppGlyphs.profiles, size = TonalButtonSize.Bar)
                }),
            )
        }
        return
    }
    if (!store.ready) {
        CommonScaffold(title = title) {
            if (store.error == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CommonCircleLoading(size = 48.dp) }
            } else {
                ErrorState(store.error!!) { store.load() }
            }
        }
        return
    }
    if (resources) {
        ConfigListPage(store, "Rule sets", listOf("route", "rule_set"), mapOf("type" to "inline", "tag" to "new-rules", "rules" to emptyList<Any>()))
        return
    }
    BoxPage(
        "Advanced configuration",
        loading = store.saving,
        actions = listOf(IconAction(AppGlyphs.refresh, tr("Reload"), if (store.saving) null else ({ store.load() }))),
    ) {
        StoreError(store)
        Section(count = 6, footer = store.profile["name"].toString()) { index, position ->
            when (index) {
                0 -> ListItemOpen(tr("Network"), position, leading = AppGlyphs.key) { NetworkConfigPage(store) }
                1 -> ListItemOpen("DNS", position, leading = AppGlyphs.dns) { DnsConfigPage(store) }
                2 -> ListItemOpen(tr("Routing rules"), position, leading = AppGlyphs.rules) { RouteConfigPage(store) }
                3 -> ListItemOpen(tr("Rule sets"), position, leading = AppGlyphs.resources) {
                    ConfigListPage(store, "Rule sets", listOf("route", "rule_set"), mapOf("type" to "inline", "tag" to "new-rules", "rules" to emptyList<Any>()))
                }
                4 -> ListItemOpen(tr("Log settings"), position, leading = AppGlyphs.logs) { LogConfigPage(store) }
                else -> ListItemOpen(tr("HTTP rewriting"), position, leading = AppGlyphs.code) { HttpRewriteConfigPage(store) }
            }
        }
        Section(count = 1) { _, position ->
            ListItem(
                title = { Text(tr("Open configuration")) },
                leading = { GlyphIcon(AppGlyphs.code) },
                trailing = { GlyphIcon(AppGlyphs.chevronForward) },
                position = position,
                onClick = { navigator?.pushPage({ ProfileEditor(box, store.profile) }) { store.load() } },
            )
        }
    }
}

@Composable
private fun StoreError(store: ProfileConfigStore) {
    val error = store.error ?: return
    Box(Modifier.padding(bottom = 12.dp)) {
        SelectionArea { Text(error, style = androidx.compose.material3.LocalTextStyle.current.copy(color = BoxTheme.scheme.error)) }
    }
}

private val HTTP_REWRITE = listOf("experimental", "http_rewrite")

@Composable
fun HttpRewriteConfigPage(store: ProfileConfigStore) {
    val revision = store.revision
    val pattern = "^https?://example\\.com/(.*)"
    val sections = listOf(
        Triple("Header Rewrite", "header_rewrite", mapOf("phase" to "request", "operation" to "add", "header" to "X-B-box", "value" to "1")),
        Triple("URL Rewrite", "url_rewrite", mapOf("action" to "header", "replacement" to "https://example.com/\$1")),
        Triple("Map Remote", "map_remote", mapOf("replacement" to "https://example.net/\$1")),
        Triple("Body Rewrite", "body_rewrite", mapOf("phase" to "response", "regex" to "old", "replacement" to "new")),
        Triple("Map Local / API Mock", "map_local", mapOf("status" to 200, "data_type" to "text", "data" to "{}", "headers" to listOf(mapOf("name" to "Content-Type", "value" to "application/json")))),
        Triple("JavaScript", "scripts", mapOf("phase" to "response", "requires_body" to true, "source" to "\$done({body: \$response.body});")),
    )
    val inset = appBarInset()
    val contentGap = contentTopPadding() - inset
    CommonScaffold(title = tr("HTTP rewriting"), loading = store.saving) {
        LazyColumn(
            Modifier.fillMaxSize().padding(top = inset),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = if (contentGap > 0.dp) contentGap else 0.dp, bottom = 24.dp + bottomInset()),
        ) {
            item { ListFooter(tr("Rules belong to this JSON profile and run with the local service. Request recording is controlled separately.")) }
            item { StoreError(store) }
            item {
                Section(count = 3) { index, position ->
                    when (index) {
                        0 -> ListItemToggle(tr("Enable HTTP rewriting"), store.value(HTTP_REWRITE + "enabled") == true, position, onChanged = if (store.saving) null else ({ store.launchSet(HTTP_REWRITE + "enabled", it) }))
                        1 -> ListItemOpen("MITM", position, leading = AppGlyphs.lock, subtitle = tr("Choose HTTPS hosts in this profile")) {
                            ConfigValueEditor("MITM", store.value(HTTP_REWRITE + "mitm") ?: mapOf("enabled" to false, "hosts" to emptyList<Any>(), "excluded_hosts" to emptyList<Any>(), "block_quic" to false)) {
                                store.set(HTTP_REWRITE + "mitm", it, expectedRevision = revision, rethrowFailure = true)
                            }
                        }
                        else -> ListItemOpen(tr("MITM certificate"), position, leading = AppGlyphs.key) { MitmCertificatePage() }
                    }
                }
            }
            item {
                Section(count = sections.size, footer = tr("New example rules are disabled. Edit their pattern and content, then set disabled to false.")) { index, position ->
                    val (title, key, initial) = sections[index]
                    ListItemOpen(title, position, leading = AppGlyphs.code, subtitle = "${store.entries(HTTP_REWRITE + key).size}") {
                        ConfigListPage(store, title, HTTP_REWRITE + key, mapOf("pattern" to pattern, "disabled" to true) + initial)
                    }
                }
            }
            item {
                Section(count = 2) { index, position ->
                    if (index == 0) {
                        ListItemOpen(tr("Embedded rewrite resources"), position, leading = AppGlyphs.resources) {
                            ConfigValueEditor("Embedded rewrite resources", store.value(HTTP_REWRITE + "resources") ?: emptyMap<String, Any>()) {
                                store.set(HTTP_REWRITE + "resources", it, expectedRevision = revision, rethrowFailure = true)
                            }
                        }
                    } else {
                        ListItemOpen(tr("Edit HTTP rewrite options"), position, leading = AppGlyphs.code) {
                            ConfigValueEditor("HTTP rewriting", store.value(HTTP_REWRITE) ?: mapOf("enabled" to false)) {
                                store.set(HTTP_REWRITE, it, expectedRevision = revision, rethrowFailure = true)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** pages/mitm_certificate.dart belongs to the Tools phase; until then its row opens this notice. */
@Composable
private fun MitmCertificatePage() {
    BoxPage("MITM certificate") {
        NullStatus(label = tr("MITM certificate"), illustration = NullStatusIllustration.Permission)
    }
}

private fun capitalized(value: String) = value.replaceFirstChar { it.uppercase() }

@Composable
fun DnsConfigPage(store: ProfileConfigStore) {
    BoxPage("DNS", loading = store.saving) {
        val defaultLabel = tr("Default")
        val invalid = tr("Invalid value")
        Section(count = 7) { index, position ->
            when (index) {
                0 -> ListItemOpen(tr("DNS servers"), position, leading = AppGlyphs.dns, subtitle = "${store.entries(listOf("dns", "servers")).size}") {
                    ConfigListPage(store, "DNS servers", listOf("dns", "servers"), mapOf("type" to "local", "tag" to "new-dns"))
                }
                1 -> ListItemOpen(tr("DNS rules"), position, leading = AppGlyphs.rules) { ConfigListPage(store, "DNS rules", listOf("dns", "rules"), emptyMap()) }
                2 -> ListItemOptions(
                    tr("Default DNS server"), listOf("") + store.tags(listOf("dns", "servers")), store.value(listOf("dns", "final")) as? String ?: "", position,
                    textOf = { if (it.isEmpty()) defaultLabel else it },
                    onChanged = { store.launchSet(listOf("dns", "final"), it, remove = it.isEmpty()) },
                )
                3 -> ListItemToggle(tr("Reverse mapping"), store.value(listOf("dns", "reverse_mapping")) == true, position) { store.launchSet(listOf("dns", "reverse_mapping"), it) }
                4 -> ListItemToggle(tr("Disable DNS cache"), store.value(listOf("dns", "disable_cache")) == true, position) { store.launchSet(listOf("dns", "disable_cache"), it) }
                5 -> ListItemToggle(tr("Disable DNS cache expiration"), store.value(listOf("dns", "disable_expire")) == true, position) { store.launchSet(listOf("dns", "disable_expire"), it) }
                else -> ListItemInput(
                    tr("DNS cache capacity"), "${(store.value(listOf("dns", "cache_capacity")) as? Number)?.toLong() ?: 0}", position,
                    keyboardType = KeyboardType.Number,
                    validator = { value -> val number = value.toLongOrNull(); if (number != null && number in 0..0xffffffffL) null else invalid },
                ) { value -> if (value != null) store.launchSet(listOf("dns", "cache_capacity"), value.toLong()) }
            }
        }
    }
}

@Composable
fun RouteConfigPage(store: ProfileConfigStore) {
    val revision = store.revision
    BoxPage("Routing rules", loading = store.saving) {
        val defaultLabel = tr("Default")
        Section(count = 3) { index, position ->
            when (index) {
                0 -> ListItemOpen(tr("Routing rules"), position, leading = AppGlyphs.rules, subtitle = "${store.entries(listOf("route", "rules")).size}") {
                    ConfigListPage(store, "Routing rules", listOf("route", "rules"), emptyMap())
                }
                1 -> ListItemOptions(
                    tr("Default outbound"), listOf("") + store.tags(listOf("outbounds")) + store.tags(listOf("endpoints")), store.value(listOf("route", "final")) as? String ?: "", position,
                    textOf = { if (it.isEmpty()) defaultLabel else it },
                    onChanged = { store.launchSet(listOf("route", "final"), it, remove = it.isEmpty()) },
                )
                else -> ListItemToggle(tr("Find process"), store.value(listOf("route", "find_process")) == true, position) { store.launchSet(listOf("route", "find_process"), it) }
            }
        }
        Section(count = 1) { _, position ->
            ListItemOpen(tr("Edit route options"), position, leading = AppGlyphs.code) {
                ConfigValueEditor("Routing rules", store.value(listOf("route")) ?: emptyMap<String, Any>()) {
                    store.set(listOf("route"), it, expectedRevision = revision, rethrowFailure = true)
                }
            }
        }
    }
}

@Composable
fun NetworkConfigPage(store: ProfileConfigStore) {
    BoxPage("Network", loading = store.saving) {
        Section(count = 3) { index, position ->
            when (index) {
                0 -> ListItemOpen(tr("Inbounds"), position, leading = AppGlyphs.devices) {
                    ConfigListPage(store, "Inbounds", listOf("inbounds"), mapOf("type" to "socks", "tag" to "new-socks", "listen" to "127.0.0.1", "listen_port" to 2080))
                }
                1 -> ListItemOpen(tr("Outbounds"), position, leading = AppGlyphs.proxies) {
                    ConfigListPage(store, "Outbounds", listOf("outbounds"), mapOf("type" to "direct", "tag" to "new-direct"))
                }
                else -> ListItemOpen(tr("Endpoints"), position, leading = AppGlyphs.devices) { ConfigListPage(store, "Endpoints", listOf("endpoints"), emptyMap()) }
            }
        }
    }
}

@Composable
fun LogConfigPage(store: ProfileConfigStore) {
    val t = trFor()
    BoxPage("Log settings", loading = store.saving) {
        Section(count = 3) { index, position ->
            when (index) {
                0 -> ListItemToggle(tr("Enable logging"), store.value(listOf("log", "disabled")) != true, position) { store.launchSet(listOf("log", "disabled"), !it) }
                1 -> ListItemOptions(
                    tr("Log level"), listOf("trace", "debug", "info", "warn", "error", "fatal", "panic"), store.value(listOf("log", "level")) as? String ?: "warn", position,
                    textOf = { t(capitalized(it)) },
                    onChanged = { store.launchSet(listOf("log", "level"), it) },
                )
                else -> ListItemToggle(tr("Log timestamps"), store.value(listOf("log", "timestamp")) == true, position) { store.launchSet(listOf("log", "timestamp"), it) }
            }
        }
    }
}

@Composable
fun ConfigListPage(store: ProfileConfigStore, title: String, path: List<String>, initial: Json) {
    val navigator = LocalBoxNavigator.current
    val dialogs = LocalDialogs.current
    val scope = rememberCoroutineScope()
    val entries = store.entries(path)
    val t = trFor()
    fun edit(index: Int? = null) {
        val current = store.entries(path)
        val revision = store.revision
        val value = LinkedHashMap(if (index == null) initial else current[index])
        if (index == null && value["tag"] is String) {
            val base = value["tag"] as String
            val tags = store.tags(path).toSet()
            var suffix = 2
            while (value["tag"] in tags) value["tag"] = "$base-${suffix++}"
        }
        navigator?.openForm {
            ConfigValueEditor(if (index == null) "Add" else "Edit", value) { edited ->
                if (edited !is Map<*, *>) throw IllegalArgumentException("JSON object required")
                val next = current.toMutableList()
                if (index == null) next.add(json(edited)) else next[index] = json(edited)
                store.set(path, next, expectedRevision = revision, rethrowFailure = true)
            }
        }
    }
    CommonScaffold(
        title = tr(title),
        loading = store.saving,
        primaryAction = IconAction(AppGlyphs.add, tr("Add"), if (store.saving) null else ({ edit() })),
        iconActions = listOfNotNull(
            IconAction(AppGlyphs.refresh, tr("Reload"), if (store.saving) null else ({ store.load() })),
            if (entries.size > 1) IconAction(AppGlyphs.sort, tr("Reorder"), if (store.saving) null else ({ navigator?.openForm { ConfigOrderPage(store, title, path) } })) else null,
        ),
    ) {
        if (entries.isEmpty()) {
            NullStatus(
                label = tr("No results"),
                illustration = NullStatusIllustration.Profile,
                action = { TonalButton(tr("Add"), { edit() }, glyph = AppGlyphs.add, size = TonalButtonSize.Bar) },
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = contentTopPadding(), bottom = 16.dp + bottomInset()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(entries) { index, entry ->
                    val revision = store.revision
                    val label = (entry["tag"] ?: entry["action"] ?: entry["type"] ?: "${t(title)} ${index + 1}").toString()
                    val menu = listOf(
                        PopupMenuItem(t("Edit"), AppGlyphs.edit, onPressed = { edit(index) }),
                        PopupMenuItem(t("Delete"), AppGlyphs.delete, danger = true, onPressed = {
                            scope.launch {
                                if (dialogs?.confirm("Delete", message = label) == true) {
                                    store.set(path, entries.toMutableList().also { it.removeAt(index) }, expectedRevision = revision)
                                }
                            }
                        }),
                    )
                    ContextMenuRegion(menu) {
                        CommonCard(Modifier.fillMaxWidth(), radius = AppCorner.XL, onPressed = { edit(index) }) {
                            ListItem(
                                title = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                subtitle = { Text((entry["url"] ?: entry["path"] ?: entry["server"])?.toString() ?: encodeJson(entry).replace(Regex("\\s+"), " "), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                leading = { GlyphIcon(AppGlyphs.profiles) },
                                trailing = { BoxMenu(menu) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ConfigOrderPage(store: ProfileConfigStore, title: String, path: List<String>) {
    val navigator = LocalBoxNavigator.current
    val scope = rememberCoroutineScope()
    val entries = remember { mutableStateListOf<Json>().apply { addAll(store.entries(path)) } }
    val original = remember { encodeJson(store.entries(path)) }
    val revision = remember { store.revision }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var version by remember { mutableIntStateOf(0) }
    BoxEditGuard(dirty = version >= 0 && encodeJson(entries.toList()) != original, busy = saving) {
        CommonScaffold(
            title = tr("Reorder"),
            loading = saving,
            confirmationAction = IconAction(AppGlyphs.check, tr("Save"), if (saving) null else ({
                saving = true
                scope.launch {
                    try {
                        if (store.set(path, entries.toList(), expectedRevision = revision, rethrowFailure = true)) navigator?.pop()
                    } catch (failure: CancellationException) {
                        throw failure
                    } catch (failure: Exception) {
                        error = BoxController.describe(failure)
                    } finally {
                        saving = false
                    }
                }
            })),
        ) {
            Column(Modifier.fillMaxSize()) {
                if (error != null) {
                    Box(Modifier.padding(start = 16.dp, top = contentTopPadding(), end = 16.dp, bottom = 8.dp)) {
                        SelectionArea { Text(error!!, style = androidx.compose.material3.LocalTextStyle.current.copy(color = BoxTheme.scheme.error)) }
                    }
                }
                ReorderableColumn(
                    items = entries.toList(),
                    key = { System.identityHashCode(it) },
                    onMove = { from, to ->
                        if (!saving) {
                            entries.add(to, entries.removeAt(from))
                            version++
                        }
                    },
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = if (error == null) contentTopPadding() else 0.dp, bottom = 16.dp + bottomInset()),
                    enabled = !saving,
                ) { index, entry ->
                    ListItem(
                        title = { Text((entry["tag"] ?: entry["action"] ?: "${index + 1}").toString()) },
                        trailing = { GlyphIcon(AppGlyphs.sort) },
                    )
                }
            }
        }
    }
}

@Composable
fun ConfigValueEditor(title: String, initial: Any?, onSave: suspend (Any?) -> Boolean) {
    val navigator = LocalBoxNavigator.current
    val scope = rememberCoroutineScope()
    val scheme = BoxTheme.scheme
    val original = remember { encodeJson(initial) }
    var text by remember { mutableStateOf(TextFieldValue(original)) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    BoxEditGuard(dirty = text.text != original, busy = saving) {
        BoxPage(
            title,
            loading = saving,
            confirmationAction = IconAction(AppGlyphs.check, tr("Save"), if (saving) null else ({
                val value = try {
                    decodeJson(text.text)
                } catch (failure: Exception) {
                    error = BoxController.describe(failure)
                    null
                }
                if (error == null || value != null) {
                    saving = true
                    error = null
                    scope.launch {
                        try {
                            if (onSave(value)) navigator?.pop()
                        } catch (failure: CancellationException) {
                            throw failure
                        } catch (failure: Exception) {
                            error = BoxController.describe(failure)
                        } finally {
                            saving = false
                        }
                    }
                }
            })),
        ) {
            if (error != null) {
                Box(Modifier.padding(bottom = 12.dp)) {
                    SelectionArea { Text(error!!, style = androidx.compose.material3.LocalTextStyle.current.copy(color = scheme.error)) }
                }
            }
            // An outlined field 8–16 lines tall, labelled Configuration, in JetBrains Mono 13 at 1.6.
            androidx.compose.material3.OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                readOnly = saving,
                minLines = 8,
                maxLines = 16,
                label = { Text(tr("Configuration")) },
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = io.nekohasekai.sfa.compose.theme.JetBrainsMono, fontSize = androidx.compose.ui.unit.TextUnit(13f, androidx.compose.ui.unit.TextUnitType.Sp), lineHeight = androidx.compose.ui.unit.TextUnit(13f * 1.6f, androidx.compose.ui.unit.TextUnitType.Sp), color = scheme.onSurface),
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
            )
        }
    }
}

