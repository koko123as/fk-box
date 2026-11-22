package io.nekohasekai.sfa.backend.dashboard.groups

import androidx.lifecycle.viewModelScope
import io.nekohasekai.libbox.OutboundGroup
import io.nekohasekai.sfa.backend.base.BaseViewModel
import io.nekohasekai.sfa.bridge.toWire
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.utils.AppLifecycleObserver
import io.nekohasekai.sfa.utils.CommandClient
import io.nekohasekai.sfa.utils.CommandSessions
import io.nekohasekai.sfa.utils.RemoteControlManager
import io.nekohasekai.sfa.utils.follow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The groups as the UI reads them (client-core `groups.rs`), newest first converted. */
data class GroupsUiState(val groups: List<Map<String, Any?>> = emptyList())

class GroupsViewModel : BaseViewModel<GroupsUiState>(), CommandClient.Handler {
    private val commandClient = CommandClient(viewModelScope, CommandClient.ConnectionType.Groups, this)

    private val serviceStatus = MutableStateFlow(Status.Stopped)
    private var lastServiceStatus: Status = Status.Stopped
    private val updateSequence = java.util.concurrent.atomic.AtomicLong()

    init {
        // When to subscribe: client-core sessions.rs.
        commandClient.follow(viewModelScope, CommandSessions.Stream.Groups)
    }

    override fun createInitialState() = GroupsUiState()

    override fun onCleared() {
        super.onCleared()
        commandClient.disconnect()
    }

    private fun handleServiceStatusChange(status: Status) {
        if (RemoteControlManager.remoteServer.value != null) {
            return
        }
        if (status != Status.Started) {
            updateState {
                copy(
                    groups = emptyList(),
                )
            }
        }
    }

    fun updateServiceStatus(status: Status) {
        if (status == lastServiceStatus) {
            return
        }
        lastServiceStatus = status
        viewModelScope.launch {
            serviceStatus.emit(status)
            handleServiceStatusChange(status)
        }
    }

    override fun onDisconnected() {
        // Closing the UI subscription in the background does not change the
        // local route. Keep its snapshot until fresh groups arrive on return.
        // Updates still converting belong to the closed subscription.
        updateSequence.incrementAndGet()
        val keepSnapshot = !AppLifecycleObserver.isForeground.value &&
            RemoteControlManager.remoteServer.value == null && serviceStatus.value == Status.Started
        viewModelScope.launch(Dispatchers.Main) {
            updateState {
                copy(
                    groups = if (keepSnapshot) groups else emptyList(),
                )
            }
        }
    }

    override fun updateGroups(newGroups: MutableList<OutboundGroup>) {
        // Each update converts on its own Default coroutine; a later one can
        // finish first. Only the newest update may be applied.
        val sequence = updateSequence.incrementAndGet()
        viewModelScope.launch(Dispatchers.Default) {
            val mergedGroups = newGroups.map { it.toWire() }

            withContext(Dispatchers.Main) {
                if (sequence != updateSequence.get()) return@withContext
                updateState {
                    copy(
                        groups = if (mergedGroups == groups) groups else mergedGroups,
                    )
                }
            }
        }
    }
}
