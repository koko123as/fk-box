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
            "provider.list" -> JSONArray()
            "provider.get" -> null
            // The Android service owns the listeners (FlClash's CoreLib does the same).
            "listener.start", "listener.stop" -> null
            "network.changed", "dns.clearCache", "traffic.reset", "connection.reset" -> null
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

    private fun validateConfig(path: String): String? = runCatching {
        ProfileContent.prepare(TypedProfile.CORE_MIHOMO, read(path))
    }.exceptionOrNull()?.let { it.message ?: it.toString() }

    /** The JSON of mihomo's RawConfig: rules under "rule", as FlClash's core marshals it. */
    private fun rawConfig(text: String): JSONObject {
        val config = FkYaml.toJson(FkYaml.mapping(text)) as JSONObject
        if (config.has("rules")) config.put("rule", config.remove("rules"))
        return config
    }

    private fun readConfig(path: String): JSONObject = rawConfig(read(path))

    private fun profileBase(path: String): JSONObject {
        val bytes = File(path).readBytes()
        val config = rawConfig(String(bytes))
        val rules = config.optJSONArray("rule") ?: JSONArray()
        val matchRules = JSONArray()
        for (index in 0 until rules.length()) {
            val rule = rules.optString(index)
            if (rule.substringBefore(',').trim().equals("MATCH", ignoreCase = true)) matchRules.put(rule)
        }
        val base = JSONObject()
            .put("digest", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
            .put("match-rules", matchRules)
            .put("proxies", config.optJSONArray("proxies")?.length() ?: 0)
            .put("proxy-groups", config.optJSONArray("proxy-groups")?.length() ?: 0)
            .put("rules", rules.length())
        for (key in listOf("proxies", "proxy-groups", "rule", "sub-rules")) config.remove(key)
        return base.put("config", config)
    }

    private fun profileOutbounds(path: String): JSONObject {
        val config = rawConfig(read(path))
        fun outbounds(key: String) = JSONArray().also { list ->
            val items = config.optJSONArray(key) ?: JSONArray()
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                list.put(JSONObject().put("name", item.optString("name")).put("type", item.optString("type")))
            }
        }
        return JSONObject().put("proxies", outbounds("proxies")).put("proxy-groups", outbounds("proxy-groups"))
    }

    private fun merged(base: JSONObject): String =
        FkYaml.dump(FkYaml.mergeProfileBase(read(base.getString("path")), base.optString("overlay")))

    /** The merged config goes to the spill directory; the Dart side reads and deletes it. */
    private fun buildProfile(base: JSONObject): String {
        val home = homeDir ?: throw CoreMethodError("not_initialized", "Core is not initialized")
        val directory = File(home, "responses").apply { mkdirs() }
        val file = File.createTempFile("profile-", ".yaml", directory)
        file.writeText(merged(base))
        return file.path
    }

    private suspend fun setupConfig(args: JSONObject): Any? = setupLock.withLock {
        val home = homeDir ?: throw CoreMethodError("not_initialized", "Core is not initialized")
        args.optJSONObject("options")?.optString("test-url")?.takeIf { it.isNotEmpty() }?.let { testUrl = it }
        selectedMap = args.optJSONObject("selected-map")?.let { map ->
            map.keys().asSequence().associateWith { map.getString(it) }
        } ?: emptyMap()
        val configFile = File(home, "config.yaml")
        val content = args.optJSONObject("base")?.let(::merged)?.also { text ->
            val temporary = File(home, ".config-${System.nanoTime()}.yaml")
            temporary.writeText(text)
            if (!temporary.renameTo(configFile)) {
                temporary.delete()
                throw CoreMethodError("config_error", "Unable to write config.yaml")
            }
        } ?: configFile.takeIf { it.exists() }?.readText()
            ?: throw CoreMethodError("config_rejected", "No config to set up")
        currentConfig = runCatching { rawConfig(content) }.getOrNull()
        val prepared = try {
            withContext(Dispatchers.IO) { ProfileContent.prepare(TypedProfile.CORE_MIHOMO, content) }
        } catch (error: Exception) {
            throw CoreMethodError("config_rejected", error.message ?: error.toString())
        }
        withContext(Dispatchers.IO) { saveRunningProfile(prepared.content) }
        if (running) {
            withContext(Dispatchers.Main) { FkHost.activity?.applyProfile() }
            scope.launch { applySelections() }
        }
        null
    }

    /** The one Room profile FlClash's UI runs; created on first use and selected. */
    private suspend fun saveRunningProfile(content: String) {
        Settings.dataStore.initialize()
        val id = preferences.getLong("profileId", 0L)
        val existing = if (id != 0L) ProfileManager.get(id) else null
        val profile = existing ?: Profile(name = "FlClash", userOrder = ProfileManager.nextOrder())
        val file = if (existing != null && existing.typed.path.isNotEmpty()) {
            File(existing.typed.path)
        } else {
            File(GlobalState.application.filesDir, "configs/flclash.yaml")
        }
        file.parentFile?.mkdirs()
        file.writeText(content)
        profile.typed.core = TypedProfile.CORE_MIHOMO
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
            JSONObject().put("up", status.uplinkTotal).put("down", status.downlinkTotal)
        } else {
            JSONObject().put("up", status.uplink).put("down", status.downlink)
        }
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
