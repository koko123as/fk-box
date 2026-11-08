package io.nekohasekai.sfa.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.nekohasekai.sfa.compose.common.IpQualityCache
import io.nekohasekai.sfa.flutter.BoxBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import android.util.Log

typealias Json = Map<String, Any?>

fun json(value: Any?): Json = (value as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value } ?: emptyMap()

fun rows(value: Any?): List<Json> = (value as? List<*>)?.map(::json) ?: emptyList()

fun Any?.asLong(): Long? = (this as? Number)?.toLong()

fun Any?.asDouble(): Double? = (this as? Number)?.toDouble()

enum class BoxData { Memory, Lan, Logs, Connections }

/**
 * Compose port of flutter/lib/box.dart's BoxController: the same state fields,
 * event handling and commands, fed by [BoxBridge]'s former channel messages.
 */
class BoxController(private val bridge: BoxBridge) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _messages = MutableSharedFlow<BoxMessage>(extraBufferCapacity = 16)
    val messages: SharedFlow<BoxMessage> = _messages
    private val _intents = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val intents: SharedFlow<String> = _intents
    private val _browserEvents = MutableSharedFlow<Json>(extraBufferCapacity = 16)
    val browserEvents: SharedFlow<Json> = _browserEvents
    private val dataOwners = HashMap<BoxData, MutableSet<Any>>()
    private var disposed = false

    var dashboard by mutableStateOf<Json>(emptyMap())
    var settings by mutableStateOf<Json>(emptyMap())
    var preferences by mutableStateOf<Json>(emptyMap())
    var rates by mutableStateOf<Json>(emptyMap())
    var toolState by mutableStateOf<Json>(emptyMap())
    var stun by mutableStateOf<Json>(emptyMap())
    var quality by mutableStateOf<Json>(emptyMap())
    var inbox by mutableStateOf<Json>(emptyMap())
    var ping by mutableStateOf<Json>(emptyMap())
    var terminal by mutableStateOf<Json>(emptyMap())
    var memory by mutableStateOf<Json>(emptyMap())
    var lan by mutableStateOf<Json>(emptyMap())
    var transfers by mutableStateOf<List<Json>>(emptyList())
    var usbDevices by mutableStateOf<List<Json>>(emptyList())
    var outbounds by mutableStateOf<List<Json>>(emptyList())
    var profiles by mutableStateOf<List<Json>>(emptyList())
    var groups by mutableStateOf<List<Json>>(emptyList())
    var connections by mutableStateOf<List<Json>>(emptyList())
    var logs by mutableStateOf<List<Json>>(emptyList())
    var defaultLogLevel by mutableStateOf<String?>(null)
    var uploadHistory by mutableStateOf<List<Double>>(emptyList())
        private set
    var downloadHistory by mutableStateOf<List<Double>>(emptyList())
        private set
    var version by mutableStateOf("")
    var coreVersion by mutableStateOf("")
    var xrayVersion by mutableStateOf("")
    var mihomoVersion by mutableStateOf("")
    var systemAccentColor by mutableStateOf(0xff007aff.toInt())
    var sampleRevision by mutableIntStateOf(0)
    var profilesRevision by mutableIntStateOf(0)
    var ready by mutableStateOf(false)
    var lanChanging by mutableStateOf(false)
    var serviceSettingsChanging by mutableStateOf(false)
    var startupError by mutableStateOf<String?>(null)
    var applyRequired by mutableStateOf<String?>(null)
    val pendingIntents = ArrayDeque<String>()
    private val profileStats = HashMap<Long, Deferred<Json>>()

    val ipQualities = IpQualityCache(
        read = { ip -> call("dashboard.qualityCache.get", mapOf("ip" to ip)) },
        write = { ip, record -> call("dashboard.qualityCache.put", mapOf("ip" to ip, "value" to record)) },
        onCacheError = { Log.w("BoxController", "IP quality cache: ${describe(it)}") },
    )

    fun setDataVisible(data: BoxData, owner: Any, visible: Boolean) {
        if (disposed) return
        val owners = dataOwners.getOrPut(data) { mutableSetOf() }
        val wasVisible = owners.isNotEmpty()
        if (visible) owners.add(owner) else owners.remove(owner)
        if (ready && wasVisible != owners.isNotEmpty()) syncData(data)
    }

    private fun syncData(data: BoxData) {
        launchAct(
            when (data) {
                BoxData.Memory -> "dashboard.memory.visible"
                BoxData.Lan -> "dashboard.lan.visible"
                BoxData.Logs -> "logs.visible"
                BoxData.Connections -> "connections.visible"
            },
            mapOf("visible" to (dataOwners[data]?.isNotEmpty() == true)),
        )
    }

    fun profileStats(id: Long): Deferred<Json> = profileStats.getOrPut(id) {
        scope.async { json(call("profiles.stats", mapOf("id" to id))) }
    }

    val remote: Boolean get() = dashboard["remote"] != null
    val running: Boolean get() = if (remote) dashboard["remoteConnected"] == true else dashboard["status"] == "Started"
    val transitioning: Boolean get() = dashboard["status"] in listOf("Starting", "Stopping")
    val selectedProfileId: Long get() = dashboard["selectedProfileId"].asLong() ?: -1L
    val selectedProfile: Json? get() = profiles.firstOrNull { it["id"].asLong() == selectedProfileId }

    val lanAddresses: List<String> get() = (lan["addresses"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
    val canSetLanProxy: Boolean
        get() = !remote && !transitioning && !lanChanging && lan["canToggle"] == true && lan["profileId"].asLong() == selectedProfileId
    val lanProxyEndpoints: List<String>
        get() = if (remote || !running || lan["running"] != true) {
            emptyList()
        } else {
            rows(lan["activeBindings"]).flatMap { binding -> (binding["endpoints"] as? List<*>)?.filterIsInstance<String>() ?: emptyList() }.distinct()
        }

    val lanCopyText: String?
        get() {
            val addresses = lanAddresses
            val endpoints = lanProxyEndpoints
            for (address in addresses) {
                for (endpoint in endpoints) {
                    if (endpoint.startsWith("$address:") || endpoint.startsWith("[$address]:")) return endpoint
                }
            }
            return endpoints.firstOrNull() ?: addresses.firstOrNull()
        }

    fun refreshLan() {
        scope.launch {
            try {
                val value = call("dashboard.lan.refresh")
                if (!disposed && value is Map<*, *>) lan = json(value)
            } catch (error: Exception) {
                report(error)
            }
        }
    }

    suspend fun setLanProxy(enabled: Boolean, port: Int? = null, profileId: Long? = null, revision: String? = null): Boolean {
        if (!canSetLanProxy) return false
        val target = profileId ?: selectedProfileId
        if (target != selectedProfileId) {
            report(IllegalStateException("Selected profile changed; reload it before saving"))
            return false
        }
        lanChanging = true
        try {
            val args = buildMap<String, Any?> {
                put("profileId", target)
                put("revision", revision ?: lan["revision"])
                put("enabled", enabled)
                if (port != null) put("port", port)
            }
            val value = json(call("dashboard.lan.set", args))
            if (!disposed && !remote && selectedProfileId == target) lan = value
            return true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            report(error)
            if (!disposed) refreshLan()
            return false
        } finally {
            lanChanging = false
        }
    }

    fun initialize() {
        startupError = null
        bridge.listen { event -> onEvent(event) }
        scope.launch {
            try {
                val result = json(call("ready"))
                version = result["version"] as? String ?: ""
                coreVersion = result["coreVersion"] as? String ?: ""
                xrayVersion = result["xrayVersion"] as? String ?: ""
                mihomoVersion = result["mihomoVersion"] as? String ?: ""
                systemAccentColor = result["systemAccentColor"].asLong()?.toInt() ?: 0xff007aff.toInt()
                dashboard = json(result["dashboard"])
                profiles = rows(result["profiles"])
                settings = json(result["settings"])
                preferences = json(result["preferences"])
                if (result["lan"] is Map<*, *>) lan = json(result["lan"])
                toolState = json(result["tools"])
                val diagnostics = json(result["diagnostics"])
                stun = json(diagnostics["stun"])
                quality = json(diagnostics["quality"])
                (result["intent"] as? String)?.let { pendingIntents.addLast(it) }
                ready = true
                for (data in dataOwners.keys) syncData(data)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                startupError = describe(error)
            }
        }
    }

    fun retryInitialize() = initialize()

    /** The OpenConnect sign-in browser, formerly the io.koko123as.flbox/openconnect platform view. */
    internal fun browserView(context: android.content.Context, args: Map<String, Any?>): io.nekohasekai.sfa.flutter.PlatformView =
        io.nekohasekai.sfa.flutter.BoxBrowserFactory(bridge).create(context, 0, args)

    /** The Ghostty surface, formerly the io.koko123as.flbox/ghostty platform view. */
    internal fun terminalView(context: android.content.Context, args: Map<String, Any?>, channel: io.nekohasekai.sfa.flutter.TerminalChannel): io.nekohasekai.sfa.flutter.TerminalSurface =
        io.nekohasekai.sfa.flutter.BoxTerminalFactory().create(context, 0, args, channel)

    suspend fun call(method: String, args: Map<String, Any?> = emptyMap()): Any? = bridge.call(method, args)

    fun clearApplyRequired() {
        applyRequired = null
    }

    suspend fun act(method: String, args: Map<String, Any?> = emptyMap()): Boolean = try {
        call(method, args)
        true
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        report(error)
        false
    }

    fun launchAct(method: String, args: Map<String, Any?> = emptyMap(), then: (Boolean) -> Unit = {}) {
        scope.launch { then(act(method, args)) }
    }

    fun launch(block: suspend CoroutineScope.() -> Unit) = scope.launch(block = block)

    suspend fun setPreferences(values: Map<String, Any?>): Boolean = try {
        preferences = json(call("preferences.set", mapOf("value" to values)))
        true
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        report(error)
        false
    }

    suspend fun setPreference(key: String, value: Any?): Boolean = setPreferences(mapOf(key to value))

    /** box.dart's setSetting: an auto-restart setting restarts a running local service itself. */
    suspend fun setSetting(key: String, value: Any?, restart: Boolean = true, autoRestart: Boolean = false) {
        if (serviceSettingsChanging || autoRestart && transitioning && !remote) return
        val restartService = autoRestart && running && !remote
        if (autoRestart) serviceSettingsChanging = true
        try {
            val saved = json(call("settings.set", buildMap { put("key", key); put("value", value); if (restartService) put("restart", true) }))
            if (!disposed) {
                settings = saved
                if (restartService) {
                    applyRequired = null
                } else if (restart && !autoRestart && running && !remote) {
                    applyRequired = "Restart"
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            report(error)
        } finally {
            if (autoRestart) serviceSettingsChanging = false
        }
    }

    suspend fun setSettings(values: Map<String, Any?>, restart: Boolean = true): Boolean = try {
        settings = json(call("settings.patch", mapOf("value" to values)))
        if (restart && running && !remote) applyRequired = "Restart"
        true
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        report(error)
        false
    }

    val appIcons = HashMap<String, Deferred<ByteArray?>>()

    fun appIcon(packageName: String): Deferred<ByteArray?> = appIcons.getOrPut(packageName) {
        scope.async {
            try {
                call("apps.icon", mapOf("package" to packageName)) as? ByteArray
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
        }
    }

    fun report(error: Throwable) {
        if (!disposed) _messages.tryEmit(BoxMessage(describe(error), error = true))
    }

    fun message(text: String) {
        if (!disposed) _messages.tryEmit(BoxMessage(text))
    }

    private fun onEvent(event: Map<String, Any?>) {
        if (disposed) return
        val value = event["value"]
        when (event["type"]) {
            "tools" -> toolState = json(value)
            "outbounds" -> outbounds = rows(value)
            "stun" -> stun = json(value)
            "quality" -> quality = json(value)
            "inbox" -> inbox = json(value)
            "ping" -> ping = json(value)
            "transfers" -> transfers = rows(value)
            "usbDevices" -> usbDevices = rows(value)
            "terminal" -> terminal = json(value)
            "memory" -> memory = json(value)
            "lan" -> lan = json(value)
            "browserComplete" -> _browserEvents.tryEmit(json(value))
            "dashboard" -> dashboard = json(value)
            "profiles" -> {
                profilesRevision++
                profileStats.clear()
                profiles = rows(value)
            }
            "groups" -> groups = rows(value)
            "connections" -> connections = rows(value)
            "logs" -> {
                val logState = json(value)
                val entries = rows(logState["entries"])
                logs = if (logState["reset"] == false) {
                    val firstId = logState["firstId"].asLong()
                    val retained = if (firstId == null) emptyList() else logs.filter { (it["id"].asLong() ?: 0) >= firstId }
                    val lastId = logs.lastOrNull()?.get("id").asLong() ?: -1L
                    retained + entries.filter { (it["id"].asLong() ?: 0) > lastId }
                } else {
                    entries
                }
                defaultLogLevel = logState["defaultLogLevel"] as? String
            }
            "settings" -> settings = json(value)
            "preferences" -> preferences = json(value)
            "systemAccent" -> value.asLong()?.let { systemAccentColor = it.toInt() }
            "rates" -> {
                sampleRevision++
                rates = json(value)
                uploadHistory = (uploadHistory + (rates["upload"].asDouble() ?: 0.0)).takeLast(30)
                downloadHistory = (downloadHistory + (rates["download"].asDouble() ?: 0.0)).takeLast(30)
            }
            "error" -> _messages.tryEmit(BoxMessage(value.toString(), error = true))
            "intentAvailable" -> scope.launch {
                try {
                    val uri = call("intent.take")
                    if (uri is String) {
                        pendingIntents.addLast(uri)
                        _intents.tryEmit(uri)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    report(error)
                }
            }
        }
    }

    fun dispose() {
        disposed = true
        bridge.cancel()
        scope.cancel()
    }

    companion object {
        fun describe(error: Throwable): String = cleanErrorText(error.message ?: error.toString())

        /** Drops gRPC/Go plumbing ("rpc error: code = Unknown desc = ", "start or reload service: ") so the reason reads first. */
        fun cleanErrorText(text: String): String {
            var value = text.trim()
            value = value.replace(Regex("""^[a-z0-9 .]+ recv: """), "")
            value = value.replace(Regex("""rpc error: code = \w+ desc = """), "")
            value = value.removePrefix("start or reload service: ")
            return value.replaceFirstChar { it.uppercase() }
        }
    }
}

/** box.dart's bytes(): 1024-based units, no decimals for bytes. */
fun bytes(value: Number): String {
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var size = value.toDouble()
    var unit = 0
    while (kotlin.math.abs(size) >= 1024 && unit < units.size - 1) {
        size /= 1024
        unit++
    }
    return if (unit == 0) "${size.toLong()} ${units[unit]}" else "${String.format(java.util.Locale.ROOT, "%.1f", size)} ${units[unit]}"
}

/** A line for the message banner; [error] picks the error look. */
data class BoxMessage(val text: String, val error: Boolean = false)
