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
import java.util.concurrent.atomic.AtomicLong

class LogViewModel :
    BaseViewModel<LogUiState>(),
    CommandClient.Handler {
    // Lines wait here until the bridge drains them to the UI, which keeps the
    // records (client-core logs.rs strips ANSI codes and bounds the ring).
    // One lane keeps clear/append in arrival order.
    private val buffer = LogEventBuffer()
    private val version = AtomicLong(0)
    override fun createInitialState() = LogUiState()

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
        viewModelScope.launch(bufferLane) {
            buffer.setLevel(level)
            val next = version.incrementAndGet()
            updateState { copy(version = next, defaultLogLevel = LogLevel.from(level)) }
        }
    }

    override fun clearLogs() {
        viewModelScope.launch(bufferLane) {
            buffer.reset()
            published()
        }
    }

    private fun published() {
        val next = version.incrementAndGet()
        updateState { copy(version = next) }
    }

    /** What arrived since the last call (`reset`, `defaultLogLevel`, `entries`); null when nothing did, unless [force]. */
    fun drain(force: Boolean = false): Map<String, Any?>? = buffer.drain(force)

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
        val entries = message.map { mapOf("level" to it.level, "message" to it.message) }
        viewModelScope.launch(bufferLane) {
            buffer.add(entries)
            published()
        }
    }

    override fun onCleared() {
        super.onCleared()
        commandClient.disconnect()
    }
}
