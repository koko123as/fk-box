package io.nekohasekai.sfa.backend.connections

import androidx.lifecycle.viewModelScope
import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.sfa.backend.base.BaseViewModel
import io.nekohasekai.sfa.bridge.toWire
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.utils.CommandClient
import io.nekohasekai.sfa.utils.CommandSessions
import io.nekohasekai.sfa.utils.RemoteControlManager
import io.nekohasekai.sfa.utils.follow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/**
 * [version] changes whenever events wait in the buffer ([ConnectionsViewModel.drain]).
 * The connection list itself lives in the UI (client-core `connections.rs`),
 * which applies the core's event batches the bridge forwards.
 */
data class ConnectionsUiState(val version: Long = 0)

class ConnectionsViewModel :
    BaseViewModel<ConnectionsUiState>(),
    CommandClient.Handler {
    private val commandClient = CommandClient(
        viewModelScope,
        CommandClient.ConnectionType.Connections,
        this,
    )

    private var lastServiceStatus: Status = Status.Stopped

    // Every event batch and clear runs on one lane, in arrival order (as
    // LogViewModel's buffer lane), so NEW/UPDATE/CLOSED deltas reach the UI in
    // order. The buffer holds what arrived since the bridge last drained it.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val connectionsLane = Dispatchers.Default.limitedParallelism(1)
    private val buffer = EventBuffer()
    private val version = AtomicLong(0)

    override fun createInitialState() = ConnectionsUiState()

    init {
        // When to subscribe: client-core sessions.rs (the connection pages shown).
        commandClient.follow(viewModelScope, CommandSessions.Stream.Connections)
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
            withContext(connectionsLane) { reset() }
        }
    }

    fun updateServiceStatus(status: Status) {
        if (status == lastServiceStatus) return
        lastServiceStatus = status
        viewModelScope.launch { handleServiceStatusChange(status) }
    }

    override fun onDisconnected() {
        viewModelScope.launch(connectionsLane) { reset() }
    }

    private fun reset() {
        buffer.reset()
        published()
    }

    private fun published() {
        val next = version.incrementAndGet()
        updateState { copy(version = next) }
    }

    /**
     * What arrived since the last call, as one batch for client-core
     * `Connections::apply_batch` (`reset`, `events`); null when nothing did,
     * unless [force] (an empty batch then).
     */
    fun drain(force: Boolean = false): Map<String, Any?>? = buffer.drain(force)

    override fun writeConnectionEvents(events: ConnectionEvents) {
        viewModelScope.launch(connectionsLane) {
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
            buffer.add(events.reset, batch)
            published()
        }
    }
}

/**
 * The core's connection events since the bridge last drained them. A reset
 * (the core's own, a stopped service, a lost command connection) drops what
 * waits and makes the next batch a reset.
 */
internal class EventBuffer {
    private var reset = false
    private var events = ArrayList<Map<String, Any?>>()

    @Synchronized
    fun reset() {
        reset = true
        events = ArrayList()
    }

    @Synchronized
    fun add(isReset: Boolean, batch: List<Map<String, Any?>>) {
        if (isReset) reset()
        events.addAll(batch)
    }

    @Synchronized
    fun drain(force: Boolean): Map<String, Any?>? {
        if (!force && !reset && events.isEmpty()) return null
        val batch = mapOf("reset" to reset, "events" to events)
        reset = false
        events = ArrayList()
        return batch
    }
}
