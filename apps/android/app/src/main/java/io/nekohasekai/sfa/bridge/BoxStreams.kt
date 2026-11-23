package io.nekohasekai.sfa.bridge

import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LogEntry
import io.nekohasekai.libbox.OutboundGroup
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.sfa.utils.CommandClient
import io.nekohasekai.sfa.utils.CommandSessions
import io.nekohasekai.sfa.utils.CommandTarget
import io.nekohasekai.sfa.utils.follow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong

/**
 * The libbox command streams, forwarded raw to the Rust UI (phase 4d). Each
 * stream's [CommandClient] connects and disconnects as mobile-ffi
 * `sessions.rs` plans ([follow]); every callback becomes one "stream" event
 * (`stream`, `kind`, `value`), and mobile-ffi `runtime.rs` makes the
 * dashboard, groups, connections and logs of them. Only the conversion of
 * the libbox (gomobile) objects to plain values stays here.
 */
internal class BoxStreams(
    private val scope: CoroutineScope,
    private val emit: (String, Any?) -> Unit,
    /** Every per-second status message, before it is forwarded (the remote memory card). */
    private val onStatus: (StatusMessage) -> Unit = {},
) : Closeable {
    private fun send(stream: String, kind: String, value: Any? = null) {
        emit("stream", mapOf("stream" to stream, "kind" to kind, "value" to value))
    }

    private open inner class Forward(private val stream: String) : CommandClient.Handler {
        override fun onConnected() = send(stream, "connected")

        override fun onDisconnected() = send(stream, "disconnected")
    }

    private val status = CommandClient(scope, CommandClient.ConnectionType.Status, object : Forward("status") {
        override fun updateStatus(status: StatusMessage) {
            onStatus(status)
            send("status", "status", mapOf(
                "uplink" to status.uplink, "downlink" to status.downlink, "memory" to status.memory,
                "uplinkTotalText" to Libbox.formatBytes(status.uplinkTotal),
                "downlinkTotalText" to Libbox.formatBytes(status.downlinkTotal),
                "connectionsIn" to status.connectionsIn, "trafficAvailable" to status.trafficAvailable,
            ))
        }
    })

    private val clashMode = CommandClient(scope, CommandClient.ConnectionType.ClashMode, object : Forward("clashMode") {
        override fun initializeClashMode(modeList: List<String>, currentMode: String) =
            send("clashMode", "init", mapOf("modes" to modeList, "mode" to currentMode))

        override fun updateClashMode(newMode: String) = send("clashMode", "mode", newMode)
    })

    // Each groups update converts on its own Default coroutine (many JNI
    // reads); a later one can finish first, so only the newest is sent.
    private val groupsSequence = AtomicLong()
    private val groups = CommandClient(scope, CommandClient.ConnectionType.Groups, object : Forward("groups") {
        override fun onDisconnected() {
            groupsSequence.incrementAndGet()
            super.onDisconnected()
        }

        override fun updateGroups(newGroups: MutableList<OutboundGroup>) {
            val sequence = groupsSequence.incrementAndGet()
            scope.launch(Dispatchers.Default) {
                val wire = newGroups.map { it.toWire() }
                if (sequence == groupsSequence.get()) send("groups", "groups", wire)
            }
        }
    })

    // Connection batches convert on one lane, in arrival order, so the
    // NEW/UPDATE/CLOSED deltas reach the UI in order.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val connectionsLane = Dispatchers.Default.limitedParallelism(1)
    private val connections = CommandClient(scope, CommandClient.ConnectionType.Connections, object : Forward("connections") {
        override fun onDisconnected() {
            scope.launch(connectionsLane) { send("connections", "disconnected") }
        }

        override fun writeConnectionEvents(events: ConnectionEvents) {
            scope.launch(connectionsLane) {
                val batch = ArrayList<Map<String, Any?>>()
                val iterator = events.iterator()
                while (iterator.hasNext()) {
                    val event = iterator.next() ?: continue
                    // Only NEW and CLOSED-with-snapshot events read a libbox
                    // connection over JNI; UPDATE carries the deltas alone.
                    batch.add(mapOf(
                        "type" to event.type, "id" to event.id,
                        "connection" to event.connection?.toWire(),
                        "uplinkDelta" to event.uplinkDelta, "downlinkDelta" to event.downlinkDelta,
                        "closedAt" to event.closedAt,
                    ))
                }
                // The DNS queries the local core answered (hijack-dns never
                // becomes a connection); a remote session has only its list.
                val dns = if (CommandTarget.isRemote) null else runCatching { Libbox.readUIDNSQueryCount() }.getOrNull()
                send("connections", "events", mapOf("reset" to events.reset, "events" to batch, "dnsQueries" to dns))
            }
        }
    })

    private val log = CommandClient(scope, CommandClient.ConnectionType.Log, object : Forward("log") {
        override fun setDefaultLogLevel(level: Int) {
            _defaultLogLevel.value = level
            send("log", "level", level)
        }

        override fun clearLogs() = send("log", "clear")

        override fun appendLogs(message: List<LogEntry>) =
            send("log", "lines", message.map { mapOf("level" to it.level, "message" to it.message) })
    })

    private val clients = listOf(status, clashMode, groups, connections, log)

    init {
        status.follow(scope, CommandSessions.Stream.Status)
        clashMode.follow(scope, CommandSessions.Stream.ClashMode)
        groups.follow(scope, CommandSessions.Stream.Groups)
        connections.follow(scope, CommandSessions.Stream.Connections)
        log.follow(scope, CommandSessions.Stream.Log)
    }

    override fun close() {
        clients.forEach { it.disconnect() }
    }

    companion object {
        private val _defaultLogLevel = MutableStateFlow<Int?>(null)

        /** The log level the core last reported (libbox priority); for instrumented tests. */
        val defaultLogLevel: StateFlow<Int?> = _defaultLogLevel.asStateFlow()
    }
}
