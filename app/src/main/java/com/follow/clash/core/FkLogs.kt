// Core "log" events for FlClash's Logs page (lib/core/event.dart onLog), read
// from libbox's log stream while Dart has asked for them (log.start/log.stop).
package com.follow.clash.core

import com.follow.clash.FkHost
import io.nekohasekai.libbox.LogEntry
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.utils.CommandClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

internal class FkLogs(private val send: (JSONArray) -> Unit) : CommandClient.Handler {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val wanted = MutableStateFlow(false)
    private val client = CommandClient(scope, CommandClient.ConnectionType.Log, this)
    private val ansi = Regex("\u001B\\[[;\\d]*m")

    init {
        scope.launch {
            combine(wanted, FkHost.status) { wanted, status -> wanted && status == Status.Started }
                .distinctUntilChanged()
                .collect { connect -> if (connect) client.connect() else client.disconnect() }
        }
    }

    fun start() {
        wanted.value = true
    }

    fun stop() {
        wanted.value = false
    }

    override fun appendLogs(message: List<LogEntry>) {
        if (message.isEmpty()) return
        val events = JSONArray()
        for (entry in message) {
            val text = entry.message.replace(ansi, "")
            events.put(
                JSONObject().put("type", "log").put(
                    "data",
                    JSONObject().put("LogLevel", level(entry.level)).put("Payload", text),
                ),
            )
        }
        send(events)
    }

    // libbox levels (sing-box): 0 panic … 6 trace; FlClash's: debug, info, warning, error, silent.
    private fun level(priority: Int) = when (priority) {
        0, 1, 2 -> "error"
        3 -> "warning"
        4 -> "info"
        else -> "debug"
    }
}
