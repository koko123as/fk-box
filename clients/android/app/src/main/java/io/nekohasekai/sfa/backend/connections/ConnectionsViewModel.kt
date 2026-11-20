package io.nekohasekai.sfa.backend.connections

import androidx.lifecycle.viewModelScope
import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.backend.base.BaseViewModel
import io.nekohasekai.sfa.bg.BoxService
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.utils.AppLifecycleObserver
import io.nekohasekai.sfa.utils.CommandClient
import io.nekohasekai.sfa.utils.CommandTarget
import io.nekohasekai.sfa.utils.RemoteControlManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Connections stream's Android side: the core subscription (local or
 * remote) and when it should run. The connection store, its payloads and the
 * send cadence live in libbox (core_connection_stream.go); each event batch is
 * handed back to it as the Go object, so no row is read over JNI here.
 */
class ConnectionsViewModel :
    BaseViewModel<Unit>(),
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

    // Every event batch, reset and clear is handed to libbox on one lane, in
    // arrival order, so NEW/UPDATE/CLOSED deltas can never be applied out of
    // order.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val connectionsLane = Dispatchers.Default.limitedParallelism(1)

    override fun createInitialState() = Unit

    init {
        // The libbox store is process-wide; a new view model starts empty, as
        // its own store did.
        viewModelScope.launch(connectionsLane) { Libbox.uiConnectionStreamClear(CommandTarget.isRemote) }
        viewModelScope.launch {
            combine(
                AppLifecycleObserver.isForeground,
                AppLifecycleObserver.isScreenOn,
                _visibleCount,
                _serviceStatus,
                combine(
                    RemoteControlManager.remoteServer,
                    RemoteControlManager.isConnected,
                    BoxService.reloadGeneration,
                ) { remoteServer, remoteConnected, reload -> Triple(remoteServer?.id, remoteConnected, reload) },
            ) { foreground, screenOn, visibleCount, status, (remoteServerId, remoteConnected, reload) ->
                val serviceReady = if (remoteServerId != null) remoteConnected else status == Status.Started
                val shouldConnect = foreground && screenOn && visibleCount > 0 && serviceReady
                // A reload starts another core: subscribe to it again.
                Triple(shouldConnect, remoteServerId, reload)
            }.distinctUntilChanged().collect { (shouldConnect, _, _) ->
                if (shouldConnect) {
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
            withContext(connectionsLane) { Libbox.uiConnectionStreamClear(CommandTarget.isRemote) }
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
        viewModelScope.launch(connectionsLane) { Libbox.uiConnectionStreamClear(CommandTarget.isRemote) }
    }

    override fun writeConnectionEvents(events: ConnectionEvents) {
        viewModelScope.launch(connectionsLane) { Libbox.uiConnectionStreamApply(events, CommandTarget.isRemote) }
    }
}
