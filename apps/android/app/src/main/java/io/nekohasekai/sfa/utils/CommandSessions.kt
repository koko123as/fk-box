package io.nekohasekai.sfa.utils

import io.nekohasekai.sfa.bg.BoxService
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.CoreStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Which kernel streams the UI reads (bridge/BoxStreams.kt). The rules are
 * mobile-ffi `sessions.rs` (`sessions.plan`); this object reports what it
 * sees (app and screen state, the service, the loaded configuration, which
 * pages are shown) and each stream follows its [Target]. Remote sessions
 * (libbox's remote command clients) do not exist on the single-core path.
 */
object CommandSessions {
    enum class Stream(val key: String) {
        ClashMode("clashMode"),
        Status("status"),
        Groups("groups"),
        Connections("connections"),
        Log("log"),
        RemoteMonitor("remoteMonitor"),
        ToolStatus("toolStatus"),
    }

    data class Target(val connect: Boolean = false, val remoteServerId: Long? = null, val localConfig: Long? = null)

    private data class Inputs(
        val foreground: Boolean,
        val screenOn: Boolean,
        val serviceStarted: Boolean,
        val remoteServerId: Long?,
        val remoteConnected: Boolean,
        val localConfig: Long?,
        val trafficVisible: Boolean,
        val connectionsVisible: Int,
        val logsVisible: Boolean,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val serviceStarted = MutableStateFlow(false)
    private val trafficVisible = MutableStateFlow(false)
    private val connectionsVisible = MutableStateFlow(0)
    private val logsVisible = MutableStateFlow(false)

    // A new token for every configuration the service loads (start or reload).
    private var configGeneration = 0L
    private val localConfig: StateFlow<Long?> = BoxService.dashboardConfig
        .map { config -> config?.let { ++configGeneration } }
        .stateIn(scope, SharingStarted.Eagerly, null)

    private val plan: StateFlow<Map<Stream, Target>> =
        combine(
            combine(AppLifecycleObserver.isForeground, AppLifecycleObserver.isScreenOn, serviceStarted) { a, b, c -> Triple(a, b, c) },
            combine(flowOf<Long?>(null), flowOf(false), localConfig) { a, b, c -> Triple(a, b, c) },
            combine(trafficVisible, connectionsVisible, logsVisible) { a, b, c -> Triple(a, b, c) },
        ) { (foreground, screenOn, started), (remoteId, remoteConnected, config), (traffic, connections, logs) ->
            Inputs(foreground, screenOn, started, remoteId, remoteConnected, config, traffic, connections, logs)
        }.distinctUntilChanged()
            .map(::compute)
            .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    private fun compute(inputs: Inputs): Map<Stream, Target> {
        val result = runCatching {
            CoreStore.call("sessions.plan") {
                put("foreground", inputs.foreground)
                put("screenOn", inputs.screenOn)
                put("serviceStarted", inputs.serviceStarted)
                inputs.remoteServerId?.let { put("remoteServerId", it) }
                put("remoteConnected", inputs.remoteConnected)
                inputs.localConfig?.let { put("localConfig", it) }
                put("trafficVisible", inputs.trafficVisible)
                put("connectionsVisible", inputs.connectionsVisible)
                put("logsVisible", inputs.logsVisible)
            }
        }.getOrElse {
            android.util.Log.w("CommandSessions", "plan failed", it)
            return emptyMap()
        }
        return decodeSessionPlan(result)
    }

    /** [stream]'s target; a change means reconnect (or disconnect). */
    fun target(stream: Stream): Flow<Target> = plan.map { it[stream] ?: Target() }.distinctUntilChanged()

    fun setServiceStatus(status: Status) { serviceStarted.value = status == Status.Started }

    /**
     * Which pages want the streams (mobile-ffi bridge.rs combines them): the
     * traffic stream, how many pages show the connection list or its counts,
     * and the Logs page.
     */
    fun setPageVisibility(traffic: Boolean, connections: Int, logs: Boolean) {
        trafficVisible.value = traffic
        connectionsVisible.value = connections.coerceAtLeast(0)
        logsVisible.value = logs
    }
}

/** mobile-ffi `sessions::Plan` as each stream's target; missing streams stay disconnected. */
internal fun decodeSessionPlan(result: JsonElement): Map<CommandSessions.Stream, CommandSessions.Target> {
    val plan = result as? JsonObject ?: return emptyMap()
    return CommandSessions.Stream.entries.associateWith { stream ->
        val target = plan[stream.key]?.jsonObject ?: return@associateWith CommandSessions.Target()
        CommandSessions.Target(
            connect = target["connect"]?.jsonPrimitive?.booleanOrNull ?: false,
            remoteServerId = target["remoteServerId"]?.jsonPrimitive?.longOrNull,
            localConfig = target["localConfig"]?.jsonPrimitive?.longOrNull,
        )
    }
}
