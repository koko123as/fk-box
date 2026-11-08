package io.nekohasekai.sfa.backend.log

import androidx.lifecycle.viewModelScope
import io.nekohasekai.libbox.LogEntry
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LogViewModel :
    BaseViewModel<LogUiState>(),
    CommandClient.Handler {
    private val buffer = LogBuffer()
    override fun createInitialState() = LogUiState()

    // Buffer work (ANSI strip, ring append, snapshot) runs off the main
    // thread. One lane keeps clear/append in arrival order.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val bufferLane = Dispatchers.Default.limitedParallelism(1)

    private val commandClient =
        CommandClient(
            scope = viewModelScope,
            connectionType = CommandClient.ConnectionType.Log,
            handler = this,
        )
    private val serviceStatusFlow = MutableStateFlow(Status.Stopped)

    // The log stream is only subscribed while the Logs page is shown; the
    // service replays its saved lines (with a reset) on every subscribe.
    private val visibleFlow = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            combine(
                AppLifecycleObserver.isForeground,
                RemoteControlManager.remoteServer,
                RemoteControlManager.isConnected,
                serviceStatusFlow,
                BoxService.dashboardConfig,
            ) { foreground, remoteServer, remoteConnected, status, config ->
                SessionTarget(
                    connect = foreground &&
                        if (remoteServer != null) remoteConnected else status == Status.Started && config != null,
                    remoteServerId = remoteServer?.id,
                    // libbox reports the default level when subscribing. Reloading
                    // a local configuration must renew that subscription as well.
                    localConfig = if (remoteServer == null) config else null,
                )
            }.combine(visibleFlow) { target, visible ->
                target.copy(connect = target.connect && visible)
            }.distinctUntilChanged().collect { target ->
                if (target.connect) {
                    commandClient.connect()
                } else {
                    commandClient.disconnect()
                }
            }
        }
    }

    private data class SessionTarget(
        val connect: Boolean,
        val remoteServerId: Long?,
        val localConfig: BoxService.DashboardConfig?,
    )

    fun updateServiceStatus(status: Status) { serviceStatusFlow.value = status }

    fun setVisible(visible: Boolean) { visibleFlow.value = visible }

    override fun setDefaultLogLevel(level: Int) {
        updateState { copy(defaultLogLevel = LogLevel.from(level)) }
    }

    override fun clearLogs() {
        viewModelScope.launch(bufferLane) {
            buffer.clear()
            updateState { copy(logs = emptyList()) }
        }
    }

    fun requestClearLogs() {
        viewModelScope.launch {
            val sent =
                withContext(Dispatchers.IO) {
                    runCatching {
                        CommandTarget.standaloneClient().clearLogs()
                    }.isSuccess
                }
            // With the service stopped there is no broadcast to clear the UI,
            // so the local buffer is cleared directly.
            if (!sent) {
                clearLogs()
            }
        }
    }

    override fun appendLogs(message: List<LogEntry>) {
        val entries = message.map { LogEntryData(LogLevel.from(it.level), it.message) }
        viewModelScope.launch(bufferLane) {
            buffer.append(entries)
            updateState { copy(logs = buffer.snapshot()) }
        }
    }

    override fun onCleared() {
        super.onCleared()
        commandClient.disconnect()
    }
}
