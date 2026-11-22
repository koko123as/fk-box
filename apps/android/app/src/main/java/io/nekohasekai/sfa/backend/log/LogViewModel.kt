package io.nekohasekai.sfa.backend.log

import androidx.lifecycle.viewModelScope
import io.nekohasekai.libbox.LogEntry
import io.nekohasekai.sfa.backend.base.BaseViewModel
import io.nekohasekai.sfa.utils.CommandClient
import io.nekohasekai.sfa.utils.CommandSessions
import io.nekohasekai.sfa.utils.CommandTarget
import io.nekohasekai.sfa.utils.follow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

    // The log stream is only subscribed while the Logs page is shown (and,
    // locally, renewed on every configuration load; client-core sessions.rs);
    // the service replays its saved lines (with a reset) on every subscribe.
    init {
        commandClient.follow(viewModelScope, CommandSessions.Stream.Log)
    }

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
