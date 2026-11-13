package io.nekohasekai.sfa.backend.connections

import androidx.lifecycle.viewModelScope
import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.sfa.backend.base.BaseViewModel
import io.nekohasekai.sfa.backend.model.Connection
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.utils.AppLifecycleObserver
import io.nekohasekai.sfa.utils.CommandClient
import io.nekohasekai.sfa.utils.RemoteControlManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

data class ConnectionsUiState(
    val allConnections: List<Connection> = emptyList(),
    // DNS lookups the store holds (live, or closed within its five-minute
    // window); kept out of [allConnections], counted for the dashboard.
    val dnsCount: Int = 0,
    val isLoading: Boolean = false,
)

class ConnectionsViewModel :
    BaseViewModel<ConnectionsUiState>(),
    CommandClient.Handler {
    private val commandClient = CommandClient(
        viewModelScope,
        CommandClient.ConnectionType.Connections,
        this,
    )

    private val _serviceStatus = MutableStateFlow(Status.Stopped)
    val serviceStatus = _serviceStatus.asStateFlow()
    private var lastServiceStatus: Status = Status.Stopped

    private val _visibleCount = MutableStateFlow(0)

    // Every event batch, reset and clear runs on one lane, in arrival order
    // (as LogViewModel's buffer lane), so NEW/UPDATE/CLOSED deltas can never be
    // applied out of order. Only the lane touches [connectionStore].
    @OptIn(ExperimentalCoroutinesApi::class)
    private val connectionsLane = Dispatchers.Default.limitedParallelism(1)
    private val connectionStore = ConnectionStore()
    private val connectionsGeneration = AtomicLong(0)

    override fun createInitialState() = ConnectionsUiState()

    init {
        viewModelScope.launch {
            combine(
                AppLifecycleObserver.isForeground,
                AppLifecycleObserver.isScreenOn,
                _visibleCount,
                _serviceStatus,
                combine(
                    RemoteControlManager.remoteServer,
                    RemoteControlManager.isConnected,
                ) { remoteServer, remoteConnected -> remoteServer?.id to remoteConnected },
            ) { foreground, screenOn, visibleCount, status, (remoteServerId, remoteConnected) ->
                val serviceReady = if (remoteServerId != null) remoteConnected else status == Status.Started
                val shouldConnect = foreground && screenOn && visibleCount > 0 && serviceReady
                shouldConnect to remoteServerId
            }.distinctUntilChanged().collect { (shouldConnect, _) ->
                if (shouldConnect) {
                    updateState { copy(isLoading = true) }
                    commandClient.connect()
                } else {
                    commandClient.disconnect()
                }
            }
        }
    }

    fun setVisible(visible: Boolean) {
        _visibleCount.value = (_visibleCount.value + if (visible) 1 else -1).coerceAtLeast(0)
    }

    override fun onCleared() {
        super.onCleared()
        commandClient.disconnect()
    }

    private suspend fun handleServiceStatusChange(status: Status) {
        if (RemoteControlManager.remoteServer.value != null) {
            return
        }
        if (status != Status.Started) {
            withContext(connectionsLane) {
                connectionStore.clear()
                connectionsGeneration.incrementAndGet()
            }
            updateState {
                copy(allConnections = emptyList(), dnsCount = 0, isLoading = false)
            }
        }
    }

    fun updateServiceStatus(status: Status) {
        if (status == lastServiceStatus) return
        lastServiceStatus = status
        viewModelScope.launch {
            _serviceStatus.emit(status)
            handleServiceStatusChange(status)
        }
    }

    override fun onDisconnected() {
        viewModelScope.launch(connectionsLane) {
            connectionStore.clear()
            connectionsGeneration.incrementAndGet()
            withContext(Dispatchers.Main) {
                updateState {
                    copy(allConnections = emptyList(), dnsCount = 0, isLoading = false)
                }
            }
        }
    }

    override fun writeConnectionEvents(events: ConnectionEvents) {
        viewModelScope.launch(connectionsLane) {
            val generation = connectionsGeneration.get()
            val snapshot = connectionStore.apply(events, System.currentTimeMillis())
            val dnsCount = connectionStore.dnsCount()
            withContext(Dispatchers.Main) {
                if (connectionsGeneration.get() != generation) {
                    return@withContext
                }
                updateState {
                    copy(
                        allConnections = snapshot,
                        dnsCount = dnsCount,
                        isLoading = false,
                    )
                }
            }
        }
    }
}

/**
 * A Kotlin port of libbox Connections.ApplyEvents (sing-box
 * experimental/libbox/command_types.go): an id → [Connection] map updated from
 * the NEW/UPDATE/CLOSED deltas. Only NEW and CLOSED-with-snapshot events read
 * a libbox connection over JNI; UPDATE copies the existing row, and unchanged
 * rows keep their instance across batches. Not thread-safe; callers serialize.
 */
internal class ConnectionStore {
    private val connections = LinkedHashMap<String, Connection>()

    fun clear() = connections.clear()

    /** Applies one event batch in order and returns the visible connections. */
    fun apply(events: ConnectionEvents, nowMillis: Long): List<Connection> {
        if (events.reset) connections.clear()
        val iterator = events.iterator()
        while (iterator.hasNext()) {
            val event = iterator.next() ?: continue
            apply(event.type, event.id, event.connection?.let(Connection::from),
                event.uplinkDelta, event.downlinkDelta, event.closedAt)
        }
        evictClosed(nowMillis)
        return snapshot()
    }

    fun apply(type: Int, id: String, connection: Connection?, uplinkDelta: Long, downlinkDelta: Long, closedAt: Long): Boolean {
        when (type) {
            EVENT_NEW -> {
                if (connection == null) return false
                connections[id] = connection
            }
            EVENT_UPDATE -> {
                val current = connections[id] ?: return false
                connections[id] = current.copy(
                    upload = uplinkDelta,
                    download = downlinkDelta,
                    uploadTotal = current.uploadTotal + uplinkDelta,
                    downloadTotal = current.downloadTotal + downlinkDelta,
                )
            }
            EVENT_CLOSED -> {
                val current = connection ?: connections[id] ?: return false
                connections[id] = current.copy(closedAt = closedAt.takeIf { it > 0 }, upload = 0, download = 0)
            }
            else -> return false
        }
        return true
    }

    fun evictClosed(nowMillis: Long) {
        val iterator = connections.values.iterator()
        while (iterator.hasNext()) {
            val closedAt = iterator.next().closedAt ?: continue
            if (nowMillis - closedAt > CLOSED_MAX_AGE_MILLIS) {
                iterator.remove()
            }
        }
    }

    fun snapshot(): List<Connection> = connections.values.filter { it.outboundType != "dns" }

    /**
     * The DNS lookups among the connections the core reports: rows of a DNS
     * outbound, sniffed as DNS, or to port 53. Queries a core answers inside
     * its own resolver (sing-box hijack-dns, mihomo dns-hijack) never become
     * connections, so they are not counted here.
     */
    fun dnsCount(): Int = connections.values.count {
        it.outboundType == "dns" || it.protocolName.equals("dns", ignoreCase = true) || it.destination.endsWith(":53")
    }

    companion object {
        // libbox ConnectionEventNew/Update/Closed (iota) and closedConnectionMaxAge.
        const val EVENT_NEW = 0
        const val EVENT_UPDATE = 1
        const val EVENT_CLOSED = 2
        const val CLOSED_MAX_AGE_MILLIS = 5 * 60 * 1000L
    }
}
