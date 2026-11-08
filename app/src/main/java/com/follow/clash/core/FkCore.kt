// FK-box's answer to FlClash's Core protocol (lib/core/method.dart, interface.dart).
//
// FlClash on Android forwards each CoreMethodCall as JSON over
// com.follow.clash/core to its in-process Go core (core/protocol.go). FK-box has
// no FlClash core: libbox runs sing-box, Xray or mihomo inside BoxService. This
// dispatcher answers the same wire methods with the same JSON shapes from the
// FK-box backend, so lib/core/interface.dart and every provider above it stay
// FlClash's code unchanged. A method FK-box cannot serve answers
// {"error":{"code":"unsupported"}}, which the Dart side reports like any Core error.
//
// Profiles: FlClash's Dart side keeps its profiles as YAML files under the home
// directory and hands the Core a path plus an overlay (config.setup). FK-box
// merges them as FlClash's core/profile_base.go does (FkYaml) and runs the
// result as the "FlClash" mihomo profile in Room, so BoxService starts it with
// the VPN permission, notification and per-app proxy FK-box already has.
package com.follow.clash.core

import android.os.Debug
import com.follow.clash.FkHost
import com.follow.clash.common.GlobalState
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.backend.model.Connection
import io.nekohasekai.sfa.backend.model.Group
import io.nekohasekai.sfa.bg.GeoResources
import io.nekohasekai.sfa.bridge.BoxBridge
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.ProfileContent
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.database.TypedProfile
import io.nekohasekai.sfa.utils.CommandTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.Instant

class CoreMethodError(val code: String, override val message: String) : Exception(message)

object FkCore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val setupLock = Mutex()
    private val preferences by lazy { GlobalState.application.getSharedPreferences("flclash_host", 0) }

    @Volatile
    private var eventListener: ((ByteArray) -> Unit)? = null

    @Volatile
    var homeDir: String? = null
        private set

    /** The selections FlClash's config.setup asks for, applied once the groups exist. */
    @Volatile
    private var selectedMap: Map<String, String> = emptyMap()

    @Volatile
    private var testUrl = "https://www.gstatic.com/generate_204"

    @Volatile
    private var currentConfig: JSONObject? = null

    private val logs = FkLogs(::sendEvents)

    // FlClash's route.watch/route.check (core/route.go): the epoch changes with
    // each run of the core, the version with anything that can move a
    // connection to another exit (config, selection, mode, providers, network).
    @Volatile
    private var coreEpoch = 0

    @Volatile
    private var routeVersion = 0

    @Volatile
    private var trafficBase = 0L to 0L

    init {
        scope.launch {
            var last = FkHost.status.value
            FkHost.status.collect { status ->
                if (status != last && (status == Status.Started || status == Status.Stopped)) {
                    coreEpoch++
                    trafficBase = 0L to 0L
                    routeChanged()
                }
                last = status
            }
        }
    }

    private fun routeChanged() {
        routeVersion++
        event("routeChanged", routeSnapshot())
    }

    private fun routeSnapshot(): JSONObject = JSONObject().put("core-epoch", coreEpoch).put("route-version", routeVersion)

    /** Every route the caller still holds is reported held while the route version it saw is current. */
    private fun routeCheck(args: JSONObject): JSONObject {
        val routes = args.optJSONArray("routes") ?: JSONArray()
        val held = JSONArray()
        for (index in 0 until routes.length()) held.put(true)
        return routeSnapshot().put("held", held)
    }

    fun setEventListener(listener: ((ByteArray) -> Unit)?) {
        eventListener = listener
    }

    /** Sends Core events (lib/core/event.dart CoreEvent list) to Dart. */
    fun sendEvents(events: JSONArray) {
        val listener = eventListener ?: return
        val call = JSONObject()
            .put("method", "message")
            .put("arguments", events)
        listener(call.toString().toByteArray(Charsets.UTF_8))
    }

    private fun event(type: String, data: Any?) = sendEvents(JSONArray().put(JSONObject().put("type", type).put("data", data ?: JSONObject.NULL)))

    fun invokeMethod(data: String, reply: (ByteArray) -> Unit) {
        scope.launch {
            val response = JSONObject()
            var id: Any? = null
            try {
                val call = JSONObject(data)
                id = call.opt("id")
                val method = call.getString("method")
                val arguments = call.opt("arguments").takeUnless { it == JSONObject.NULL }
                response.put("result", dispatch(method, arguments) ?: JSONObject.NULL)
            } catch (error: CoreMethodError) {
                response.put("error", errorOf(error.code, error.message))
            } catch (error: Exception) {
                GlobalState.log("Core method failed: $error")
                response.put("error", errorOf("core_error", error.message ?: error.toString()))
            }
            if (id != null) response.put("id", id)
            reply(response.toString().toByteArray(Charsets.UTF_8))
        }
    }

    private fun errorOf(code: String, message: String) =
        JSONObject().put("code", code).put("message", message).put("details", JSONObject.NULL)

    private suspend fun dispatch(method: String, arguments: Any?): Any? {
        val args = arguments as? JSONObject ?: JSONObject()
        return when (method) {
            "core.init" -> {
                homeDir = args.optString("home-dir").takeIf { it.isNotEmpty() }
                scope.launch { FkGeo.syncHome(homeDir) }
                null
            }
            "core.initialized" -> homeDir != null
            "core.gc" -> {
                withContext(Dispatchers.IO) { runCatching { Libbox.collectUIRuntimeMemory() } }
                System.gc()
                null
            }
            "core.memory" -> memoryStats()
            "core.crash" -> null

            "config.validate" -> validateConfig(args.getString("path"))
            "config.read" -> readConfig(args.getString("path"))
            "config.decrypt" -> throw CoreMethodError("unsupported", "Encrypted profiles are not supported by FK-box")
            "config.setup" -> setupConfig(args)
            "config.update" -> updateConfig(args)
            "profile.base" -> profileBase(args.getString("path"))
            "profile.outbounds" -> profileOutbounds(args.getString("path"))
            "profile.build" -> buildProfile(args)
            "profile.clearEffect" -> null
            "proxy.convert" -> convertProxies(args.getString("path"))

            "proxy.list" -> proxies()
            "proxy.select" -> selectProxy(args.getString("group-name"), args.getString("proxy-name"))
            "proxy.testDelay" -> testDelay(args.getString("proxy-name"), args.optString("test-url"), args.optInt("timeout", 5000))

            "traffic.current" -> traffic(total = false)
            "traffic.total" -> traffic(total = true)
            "connection.list" -> connections()
            "connection.count" -> activeConnections().size
            "connection.close" -> {
                withContext(Dispatchers.IO) { CommandTarget.standaloneClient().closeConnection(args.getString("id")) }
                null
            }
            "connection.closeAll" -> {
                withContext(Dispatchers.IO) { CommandTarget.standaloneClient().closeConnections() }
                null
            }
            "log.start" -> {
                logs.start()
                null
            }
            "log.stop" -> {
                logs.stop()
                null
            }
            "provider.list" -> withContext(Dispatchers.IO) { JSONArray(Libbox.mihomoExternalProviders()) }
            "provider.get" -> withContext(Dispatchers.IO) {
                Libbox.mihomoExternalProvider(args.getString("name")).takeIf { it.isNotEmpty() }?.let(::JSONObject)
            }
            "provider.update" -> {
                val name = args.getString("name")
                withContext(Dispatchers.IO) {
                    try {
                        Libbox.updateMihomoProvider(name)
                    } catch (error: Exception) {
                        throw CoreMethodError("provider_update_failed", error.message ?: error.toString())
                    }
                }
                routeChanged()
                null
            }
            "geo.update" -> FkGeo.update(args.getString("type"), currentConfig?.optJSONObject("geox-url"), homeDir, ::sendEvents)
            "traffic.reset" -> {
                resetTraffic()
                null
            }
            "connection.reset" -> {
                if (running) withContext(Dispatchers.IO) { runCatching { CommandTarget.standaloneClient().closeConnections() } }
                null
            }
            "route.watch" -> routeSnapshot()
            "route.check" -> routeCheck(args)
            "probe.outboundIp" -> FkProbe.outboundIp(args).put("core-epoch", coreEpoch).put("route-version", routeVersion)
            "probe.services" -> JSONArray()
            // The Android service owns the listeners (FlClash's CoreLib does the same).
            "listener.start", "listener.stop" -> null
            "network.changed" -> {
                routeChanged()
                null
            }
            "dns.clearCache" -> null
            else -> throw CoreMethodError("unsupported", "FK-box does not provide $method yet")
        }
    }

    private suspend fun bridge(): BoxBridge = FkHost.activity?.boxBridge
        ?: throw CoreMethodError("core_unavailable", "The FK-box activity is not running")

    private val running: Boolean
        get() = FkHost.status.value == Status.Started

    // ---- memory -------------------------------------------------------------

    private suspend fun memoryStats(): JSONObject {
        val goMemory = runCatching { bridge().lastStatus?.memory }.getOrNull() ?: 0L
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        return JSONObject()
            .put("rss", info.totalPss.toLong() * 1024)
            .put("heap-inuse", goMemory)
    }

    // ---- profiles -----------------------------------------------------------

    private fun read(path: String): String = File(path).readText()

    // FlClash's profiles are files under its home directory. A file holding
    // sing-box or Xray JSON is kept as it is and run by that core; mihomo
    // YAML goes through FlClash's overlay merge (FkYaml) as in its own core.

    private fun coreOf(content: String): String = Libbox.detectCoreConfig(content)

    private fun isJsonCore(core: String) = core == TypedProfile.CORE_SING_BOX || core == TypedProfile.CORE_XRAY

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * Null when the file is a profile FK-box can run. Share links answer an issue so FlClash's
     * validateProfile converts them through proxy.convert, except links only Xray can carry
     * (xhttp), which are rewritten into an Xray profile here. A JSON array of configs that
     * libbox merges into one profile is rewritten into it.
     */
    private fun validateConfig(path: String): String? = runCatching {
        val file = File(path)
        var content = file.readText()
        when (coreOf(content)) {
            Libbox.CoreLinks -> {
                val converted = Libbox.convertCoreShareLinksEnabled(TypedProfile.CORE_AUTO, "xray,mihomo", content)
                if (converted.core != TypedProfile.CORE_XRAY) {
                    throw IllegalArgumentException("Share links are converted to mihomo proxies")
                }
                content = converted.content
                file.writeText(content)
            }
            Libbox.CoreArray -> {
                val split = Libbox.splitCoreConfigArray(content)
                if (split.len() != 1) {
                    throw IllegalArgumentException(
                        "This file holds ${split.len()} separate configurations; import each one as its own profile",
                    )
                }
                content = split.content(0)
                file.writeText(content)
            }
        }
        val core = Libbox.resolveProfileCore(TypedProfile.CORE_AUTO, content)
        // Xray's and mihomo's checks open geoip.dat/geosite.dat for geo rules.
        if (core != TypedProfile.CORE_SING_BOX) GeoResources.ensureBundledBlocking()
        ProfileContent.prepare(core, content)
        Unit
    }.exceptionOrNull()?.let { it.message ?: it.toString() }

    /** FlClash's proxy.convert: share links or a Base64 node list as mihomo proxies. */
    private fun convertProxies(path: String): JSONArray {
        val converted = Libbox.convertCoreShareLinksFor(TypedProfile.CORE_MIHOMO, read(path))
        val config = FkYaml.toJson(FkYaml.mapping(converted.content)) as JSONObject
        return config.optJSONArray("proxies") ?: JSONArray()
    }

    /** The JSON of mihomo's RawConfig: rules under "rule", as FlClash's core marshals it. */
    private fun rawConfig(text: String): JSONObject {
        val config = FkYaml.toJson(FkYaml.mapping(text)) as JSONObject
        if (config.has("rules")) config.put("rule", config.remove("rules"))
        return config
    }

    private fun readConfig(path: String): JSONObject {
        val text = read(path)
        return if (isJsonCore(coreOf(text))) jsonConfig(text) else rawConfig(text)
    }

    private fun jsonConfig(text: String): JSONObject = JSONObject(text).also { config ->
        // Rules where FlClash's views look for them ("rule"); the profile itself is untouched.
        val rules = config.optJSONObject("route")?.optJSONArray("rules")
            ?: config.optJSONObject("routing")?.optJSONArray("rules")
        if (rules != null) config.put("rule", rules)
    }

    /** Groups and nodes of a sing-box or Xray profile, as FlClash lists a Clash config's. */
    private class JsonOutbounds(val groups: List<Triple<String, String, List<String>>>, val nodes: List<Pair<String, String>>)

    private fun jsonOutbounds(core: String, text: String): JsonOutbounds {
        val config = JSONObject(text)
        val outbounds = config.optJSONArray("outbounds") ?: JSONArray()
        val groups = mutableListOf<Triple<String, String, List<String>>>()
        val nodes = mutableListOf<Pair<String, String>>()
        if (core == TypedProfile.CORE_SING_BOX) {
            for (index in 0 until outbounds.length()) {
                val item = outbounds.optJSONObject(index) ?: continue
                val tag = item.optString("tag")
                val type = item.optString("type")
                when (type) {
                    "selector", "urltest" -> {
                        val members = item.optJSONArray("outbounds") ?: JSONArray()
                        groups += Triple(tag, if (type == "selector") "Selector" else "URLTest", (0 until members.length()).map { members.optString(it) })
                    }
                    else -> nodes += tag to type
                }
            }
        } else {
            for (index in 0 until outbounds.length()) {
                val item = outbounds.optJSONObject(index) ?: continue
                nodes += item.optString("tag") to item.optString("protocol")
            }
            val balancers = config.optJSONObject("routing")?.optJSONArray("balancers") ?: JSONArray()
            for (index in 0 until balancers.length()) {
                val balancer = balancers.optJSONObject(index) ?: continue
                val selectors = balancer.optJSONArray("selector") ?: JSONArray()
                val prefixes = (0 until selectors.length()).map { selectors.optString(it) }
                val members = nodes.map { it.first }.filter { tag -> prefixes.any { tag.startsWith(it) } }
                groups += Triple(balancer.optString("tag"), "LoadBalance", members)
            }
        }
        return JsonOutbounds(groups, nodes)
    }

    private fun profileBase(path: String): JSONObject {
        val bytes = File(path).readBytes()
        val text = String(bytes)
        val core = coreOf(text)
        if (isJsonCore(core)) {
            val outbounds = jsonOutbounds(core, text)
            val config = jsonConfig(text)
            return JSONObject()
                .put("digest", sha256(bytes))
                .put("match-rules", JSONArray())
                .put("proxies", outbounds.nodes.size)
                .put("proxy-groups", outbounds.groups.size)
                .put("rules", config.optJSONArray("rule")?.length() ?: 0)
                .put("config", JSONObject())
        }
        val config = rawConfig(text)
        val rules = config.optJSONArray("rule") ?: JSONArray()
        val matchRules = JSONArray()
        for (index in 0 until rules.length()) {
            val rule = rules.optString(index)
            if (rule.substringBefore(',').trim().equals("MATCH", ignoreCase = true)) matchRules.put(rule)
        }
        val base = JSONObject()
            .put("digest", sha256(bytes))
            .put("match-rules", matchRules)
            .put("proxies", config.optJSONArray("proxies")?.length() ?: 0)
            .put("proxy-groups", config.optJSONArray("proxy-groups")?.length() ?: 0)
            .put("rules", rules.length())
        for (key in listOf("proxies", "proxy-groups", "rule", "sub-rules")) config.remove(key)
        return base.put("config", config)
    }

    private fun profileOutbounds(path: String): JSONObject {
        val text = read(path)
        val core = coreOf(text)
        if (isJsonCore(core)) {
            val outbounds = jsonOutbounds(core, text)
            return JSONObject()
                .put("proxies", JSONArray(outbounds.nodes.map { JSONObject().put("name", it.first).put("type", it.second) }))
                .put("proxy-groups", JSONArray(outbounds.groups.map { JSONObject().put("name", it.first).put("type", it.second) }))
        }
        val config = rawConfig(text)
        fun outbounds(key: String) = JSONArray().also { list ->
            val items = config.optJSONArray(key) ?: JSONArray()
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                list.put(JSONObject().put("name", item.optString("name")).put("type", item.optString("type")))
            }
        }
        return JSONObject().put("proxies", outbounds("proxies")).put("proxy-groups", outbounds("proxy-groups"))
    }

    /** The profile to run and its core: JSON profiles as they are, Clash YAML merged with FlClash's overlay. */
    private fun merged(base: JSONObject): Pair<String, String> {
        val text = read(base.getString("path"))
        val core = coreOf(text)
        if (isJsonCore(core)) return core to text
        return TypedProfile.CORE_MIHOMO to FkYaml.dump(FkYaml.mergeProfileBase(text, base.optString("overlay")))
    }

    /** The merged config goes to the spill directory; the Dart side reads and deletes it. */
    private fun buildProfile(base: JSONObject): String {
        val home = homeDir ?: throw CoreMethodError("not_initialized", "Core is not initialized")
        val directory = File(home, "responses").apply { mkdirs() }
        val (core, content) = merged(base)
        val file = File.createTempFile("profile-", if (isJsonCore(core)) ".json" else ".yaml", directory)
        file.writeText(content)
        return file.path
    }

    @Volatile
    private var currentCore = TypedProfile.CORE_MIHOMO

    @Volatile
    private var currentContent: String? = null

    private suspend fun setupConfig(args: JSONObject): Any? = setupLock.withLock {
        val home = homeDir ?: throw CoreMethodError("not_initialized", "Core is not initialized")
        args.optJSONObject("options")?.optString("test-url")?.takeIf { it.isNotEmpty() }?.let { testUrl = it }
        selectedMap = args.optJSONObject("selected-map")?.let { map ->
            map.keys().asSequence().associateWith { map.getString(it) }
        } ?: emptyMap()
        val configFile = File(home, "config.yaml")
        val content = args.optJSONObject("base")?.let(::merged)?.second?.also { text ->
            val temporary = File(home, ".config-${System.nanoTime()}.yaml")
            temporary.writeText(text)
            if (!temporary.renameTo(configFile)) {
                temporary.delete()
                throw CoreMethodError("config_error", "Unable to write config.yaml")
            }
        } ?: configFile.takeIf { it.exists() }?.readText()
            ?: throw CoreMethodError("config_rejected", "No config to set up")
        val core = coreOf(content).takeIf { isJsonCore(it) } ?: TypedProfile.CORE_MIHOMO
        currentCore = core
        currentContent = content
        currentConfig = runCatching { if (isJsonCore(core)) jsonConfig(content) else rawConfig(content) }.getOrNull()
        val prepared = try {
            withContext(Dispatchers.IO) {
                if (core != TypedProfile.CORE_SING_BOX) GeoResources.ensureBundledBlocking()
                ProfileContent.prepare(core, content)
            }
        } catch (error: Exception) {
            throw CoreMethodError("config_rejected", error.message ?: error.toString())
        }
        withContext(Dispatchers.IO) { saveRunningProfile(prepared.core, prepared.content) }
        routeChanged()
        if (running) {
            withContext(Dispatchers.Main) { FkHost.activity?.applyProfile() }
            scope.launch { applySelections() }
        }
        null
    }

    /** The one Room profile FlClash's UI runs; created on first use and selected. */
    private suspend fun saveRunningProfile(core: String, content: String) {
        Settings.dataStore.initialize()
        val id = preferences.getLong("profileId", 0L)
        val existing = if (id != 0L) ProfileManager.get(id) else null
        val profile = existing ?: Profile(name = "FlClash", userOrder = ProfileManager.nextOrder())
        val file = if (existing != null && existing.typed.path.isNotEmpty()) {
            File(existing.typed.path)
        } else {
            File(GlobalState.application.filesDir, "configs/flclash.conf")
        }
        file.parentFile?.mkdirs()
        file.writeText(content)
        profile.typed.core = core
        profile.typed.path = file.path
        profile.typed.type = TypedProfile.Type.Local
        if (existing == null) {
            val created = ProfileManager.create(profile, andSelect = true)
            preferences.edit().putLong("profileId", created.id).apply()
        } else {
            ProfileManager.update(profile)
            Settings.selectedProfile = profile.id
        }
        Settings.dataStore.flush()
    }

    private suspend fun updateConfig(args: JSONObject): Any? {
        args.optJSONObject("options")?.optString("test-url")?.takeIf { it.isNotEmpty() }?.let { testUrl = it }
        val mode = args.optString("mode").takeIf { it.isNotEmpty() } ?: return null
        currentConfig?.put("mode", mode)
        if (!running) return null
        val modes = runCatching { bridge().call { dashboardState.value.clashModes } }.getOrDefault(emptyList())
        val target = modes.firstOrNull { it.equals(mode, ignoreCase = true) } ?: return null
        withContext(Dispatchers.IO) { CommandTarget.standaloneClient().setClashMode(target) }
        routeChanged()
        return null
    }

    // ---- proxies ------------------------------------------------------------

    private suspend fun groups(): List<Group> =
        if (running) runCatching { bridge().call { groupsState.value.groups } }.getOrDefault(emptyList()) else emptyList()

    private fun groupType(group: Group): String = when (group.type.lowercase()) {
        "selector", "select" -> "Selector"
        "urltest", "url-test" -> "URLTest"
        "fallback" -> "Fallback"
        "loadbalance", "load-balance" -> "LoadBalance"
        "relay" -> "Relay"
        else -> if (group.selectable) "Selector" else "URLTest"
    }

    /** mihomo's /proxies shape (ProxiesData): every group and node by name, groups in order. */
    private suspend fun proxies(): JSONObject {
        val groups = groups()
        val proxies = JSONObject()
        val all = JSONArray()
        if (groups.isEmpty()) return staticProxies()
        for (group in groups) {
            for (item in group.items) {
                if (!proxies.has(item.tag)) {
                    proxies.put(item.tag, JSONObject().put("name", item.tag).put("type", item.displayType))
                }
            }
        }
        for (group in groups) {
            all.put(group.tag)
            proxies.put(
                group.tag,
                JSONObject()
                    .put("name", group.tag)
                    .put("type", groupType(group))
                    .put("now", group.selected)
                    .put("all", JSONArray(group.items.map { it.tag })),
            )
        }
        return JSONObject().put("proxies", proxies).put("all", all)
    }

    /** While the service is stopped, the groups the set-up config declares, as FlClash shows them before a start. */
    private fun staticProxies(): JSONObject {
        val content = currentContent
        if (isJsonCore(currentCore) && content != null) {
            val outbounds = runCatching { jsonOutbounds(currentCore, content) }.getOrNull()
                ?: return JSONObject().put("proxies", JSONObject()).put("all", JSONArray())
            val proxies = JSONObject()
            val all = JSONArray()
            for ((name, type) in outbounds.nodes) proxies.put(name, JSONObject().put("name", name).put("type", type))
            for ((name, type, members) in outbounds.groups) {
                all.put(name)
                proxies.put(
                    name,
                    JSONObject().put("name", name).put("type", type)
                        .put("now", selectedMap[name] ?: members.firstOrNull() ?: "").put("all", JSONArray(members)),
                )
            }
            return JSONObject().put("proxies", proxies).put("all", all)
        }
        val config = currentConfig ?: return JSONObject().put("proxies", JSONObject()).put("all", JSONArray())
        val proxies = JSONObject()
        val all = JSONArray()
        for (name in listOf("DIRECT", "REJECT")) proxies.put(name, JSONObject().put("name", name).put("type", name.lowercase().replaceFirstChar { it.uppercase() }))
        val nodes = config.optJSONArray("proxies") ?: JSONArray()
        for (index in 0 until nodes.length()) {
            val node = nodes.optJSONObject(index) ?: continue
            proxies.put(node.optString("name"), JSONObject().put("name", node.optString("name")).put("type", node.optString("type")))
        }
        val groups = config.optJSONArray("proxy-groups") ?: JSONArray()
        for (index in 0 until groups.length()) {
            val group = groups.optJSONObject(index) ?: continue
            val name = group.optString("name")
            val members = group.optJSONArray("proxies") ?: JSONArray()
            val type = when (group.optString("type")) {
                "select" -> "Selector"
                "url-test" -> "URLTest"
                "fallback" -> "Fallback"
                "load-balance" -> "LoadBalance"
                "relay" -> "Relay"
                else -> "Selector"
            }
            val first = if (members.length() > 0) members.optString(0) else ""
            all.put(name)
            proxies.put(
                name,
                JSONObject().put("name", name).put("type", type).put("now", selectedMap[name] ?: first)
                    .put("all", members).put("hidden", group.optBoolean("hidden", false)),
            )
        }
        return JSONObject().put("proxies", proxies).put("all", all)
    }

    private suspend fun selectProxy(groupName: String, proxyName: String): JSONObject {
        selectedMap = selectedMap + (groupName to proxyName)
        if (!running) return JSONObject().put("changed", true).put("message", "")
        val group = groups().firstOrNull { it.tag == groupName }
            ?: return JSONObject().put("changed", false).put("message", "Group $groupName no longer exists")
        if (!group.selectable) return JSONObject().put("changed", false).put("message", "$groupName is selected automatically")
        if (group.selected == proxyName) return JSONObject().put("changed", false).put("message", "")
        withContext(Dispatchers.IO) { CommandTarget.standaloneClient().selectOutbound(groupName, proxyName) }
        routeChanged()
        return JSONObject().put("changed", true).put("message", "")
    }

    private suspend fun applySelections() {
        val groups = withTimeoutOrNull(20_000) {
            var current = groups()
            while (current.isEmpty()) {
                delay(500)
                current = groups()
            }
            current
        } ?: return
        for ((groupName, proxyName) in selectedMap) {
            val group = groups.firstOrNull { it.tag == groupName } ?: continue
            if (!group.selectable || group.selected == proxyName || group.items.none { it.tag == proxyName }) continue
            runCatching { CommandTarget.standaloneClient().selectOutbound(groupName, proxyName) }
        }
    }

    /** Tests the group that holds [proxyName] (libbox tests a group at a time) and reads the node's delay. */
    private suspend fun testDelay(proxyName: String, url: String, timeout: Int): JSONObject {
        val testedUrl = url.ifEmpty { testUrl }
        val result = JSONObject().put("name", proxyName).put("url", testedUrl)
        if (!running) return result.put("value", -1)
        val before = groups()
        val owner = before.firstOrNull { it.tag == proxyName } ?: before.firstOrNull { group -> group.items.any { it.tag == proxyName } }
            ?: return result.put("value", -1)
        val previous = owner.items.firstOrNull { it.tag == proxyName }?.urlTestTime ?: 0L
        withContext(Dispatchers.IO) { CommandTarget.standaloneClient().urlTest(owner.tag) }
        val delay = withTimeoutOrNull(timeout.toLong() + 3_000) {
            while (true) {
                val item = groups().firstOrNull { it.tag == owner.tag }?.items?.firstOrNull { it.tag == proxyName }
                if (item != null && item.urlTestTime != previous) return@withTimeoutOrNull item.urlTestDelay
                delay(300)
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }
        val value = if (delay == null || delay <= 0) -1 else delay
        result.put("value", value)
        event("delay", JSONObject(result.toString()))
        return result
    }

    // ---- traffic and connections -------------------------------------------

    private suspend fun traffic(total: Boolean): JSONObject {
        val status = if (running) runCatching { bridge().lastStatus }.getOrNull() else null
        return if (status == null) {
            JSONObject().put("up", 0).put("down", 0)
        } else if (total) {
            val (up, down) = trafficBase
            JSONObject().put("up", (status.uplinkTotal - up).coerceAtLeast(0)).put("down", (status.downlinkTotal - down).coerceAtLeast(0))
        } else {
            JSONObject().put("up", status.uplink).put("down", status.downlink)
        }
    }

    private suspend fun resetTraffic() {
        val status = if (running) runCatching { bridge().lastStatus }.getOrNull() else null
        trafficBase = if (status == null) 0L to 0L else status.uplinkTotal to status.downlinkTotal
    }

    private suspend fun activeConnections(): List<Connection> {
        if (!running) return emptyList()
        val bridge = bridge()
        return bridge.call {
            setConnectionsVisible(true)
            connectionsState.value.allConnections.filter { it.closedAt == null }
        }
    }

    private fun hostPort(value: String): Pair<String, String> {
        val index = value.lastIndexOf(':')
        if (index <= 0) return value to ""
        return value.substring(0, index).removePrefix("[").removeSuffix("]") to value.substring(index + 1)
    }

    private suspend fun connections(): JSONObject {
        val list = JSONArray()
        for (connection in activeConnections()) {
            val (sourceIP, sourcePort) = hostPort(connection.source)
            val (destinationIP, destinationPort) = hostPort(connection.destination)
            val process = connection.processInfo
            val metadata = JSONObject()
                .put("network", connection.network)
                .put("sourceIP", sourceIP)
                .put("sourcePort", sourcePort)
                .put("destinationIP", destinationIP)
                .put("destinationPort", destinationPort)
                .put("host", connection.domain)
                .put("uid", process?.userId ?: 0)
                .put("process", process?.packageNames?.firstOrNull() ?: "")
                .put("processPath", process?.processPath ?: "")
            list.put(
                JSONObject()
                    .put("id", connection.id)
                    .put("upload", connection.uploadTotal)
                    .put("download", connection.downloadTotal)
                    .put("uploadSpeed", connection.upload)
                    .put("downloadSpeed", connection.download)
                    .put("start", Instant.ofEpochMilli(connection.createdAt).toString())
                    .put("metadata", metadata)
                    .put("chains", JSONArray(connection.chain))
                    .put("rule", connection.rule.substringBefore(" => ").ifEmpty { connection.rule })
                    .put("rulePayload", ""),
            )
        }
        return JSONObject().put("connections", list)
    }
}
