package io.nekohasekai.sfa.backend.dashboard

import androidx.lifecycle.viewModelScope
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.sfa.backend.base.BaseViewModel
import io.nekohasekai.sfa.bg.BoxService
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.utils.AppLifecycleObserver
import io.nekohasekai.sfa.utils.CommandClient
import io.nekohasekai.sfa.utils.RemoteControlManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DashboardUiState(
    val serviceStatus: Status = Status.Stopped,
    val serviceStartTime: Long? = null,
    val deprecatedNotes: List<DeprecatedNote> = emptyList(),
    // Per-second values (rates, traffic totals, memory) are not kept here:
    // they travel in the bridge's "rates" event, so this state only changes
    // on real status, profile or mode changes.
    val trafficVisible: Boolean = false,
    val clashModeVisible: Boolean = false,
    val clashModes: List<String> = emptyList(),
    val selectedClashMode: String = "",
    val systemProxyVisible: Boolean = false,
    val systemProxyEnabled: Boolean = false,
    val systemProxySwitching: Boolean = false,
) {
    data class DeprecatedNote(val message: String, val migrationLink: String?)
}

class DashboardViewModel :
    BaseViewModel<DashboardUiState>(),
    CommandClient.Handler {
    private val _serviceStatus = MutableStateFlow(Status.Stopped)
    val serviceStatus: StateFlow<Status> = _serviceStatus.asStateFlow()

    // The mode follows every page, so its subscription runs whenever the
    // service is reachable in the foreground.
    private val modeClient =
        CommandClient(
            viewModelScope,
            CommandClient.ConnectionType.ClashMode,
            this,
        )

    // The per-second status (rates, traffic totals, memory) only while a page
    // shows it ([setTrafficVisible]); the bridge reads it through addHandler.
    // Its own handler, so onConnected and the mode callbacks are not doubled.
    internal val commandClient =
        CommandClient(
            viewModelScope,
            CommandClient.ConnectionType.Status,
            object : CommandClient.Handler {
                override fun updateStatus(status: StatusMessage) = this@DashboardViewModel.updateStatus(status)
            },
        )
    private val trafficWanted = MutableStateFlow(false)

    override fun createInitialState() = DashboardUiState()

    init {

        viewModelScope.launch {
            combine(
                AppLifecycleObserver.isForeground,
                RemoteControlManager.remoteServer,
                RemoteControlManager.isConnected,
                _serviceStatus,
                BoxService.reloadGeneration,
            ) { foreground, remoteServer, remoteConnected, status, reload ->
                SessionTarget(
                    connect = foreground &&
                        if (remoteServer != null) remoteConnected else status == Status.Started,
                    remoteServerId = remoteServer?.id,
                    // A reload starts another core: subscribe to it again.
                    reload = if (remoteServer == null) reload else 0L,
                )
            }.distinctUntilChanged().collect { target ->
                if (target.connect) {
                    modeClient.connect()
                } else {
                    modeClient.disconnect()
                }
            }
        }
        viewModelScope.launch {
            combine(
                AppLifecycleObserver.isForeground,
                AppLifecycleObserver.isScreenOn,
                trafficWanted,
                RemoteControlManager.remoteServer,
                combine(RemoteControlManager.isConnected, _serviceStatus, BoxService.reloadGeneration) { remoteConnected, status, reload ->
                    Triple(remoteConnected, status, reload)
                },
            ) { foreground, screenOn, wanted, remoteServer, (remoteConnected, status, reload) ->
                SessionTarget(
                    connect = foreground && screenOn && wanted &&
                        if (remoteServer != null) remoteConnected else status == Status.Started,
                    remoteServerId = remoteServer?.id,
                    reload = if (remoteServer == null) reload else 0L,
                )
            }.distinctUntilChanged().collect { target ->
                // A fresh subscription reports the current status at once, so
                // a page shown again starts from current values.
                if (target.connect) {
                    commandClient.connect()
                } else {
                    commandClient.disconnect()
                }
            }
        }
    }

    private data class SessionTarget(val connect: Boolean, val remoteServerId: Long?, val reload: Long)

    /** Whether a page shows the per-second status (the Slint UI's visibility sync). */
    fun setTrafficVisible(visible: Boolean) { trafficWanted.value = visible }

    override fun onCleared() {
        super.onCleared()
        modeClient.disconnect()
        commandClient.disconnect()
    }

    private fun checkDeprecatedNotes() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                // Check if deprecated warnings are disabled
                if (Settings.disableDeprecatedWarnings) {
                    return@launch
                }

                val notes = Libbox.newStandaloneCommandClient().deprecatedNotes
                if (notes.hasNext()) {
                    val notesList = mutableListOf<DashboardUiState.DeprecatedNote>()
                    while (notes.hasNext()) {
                        val note = notes.next()
                        notesList.add(
                            DashboardUiState.DeprecatedNote(
                                message = note.message(),
                                migrationLink = note.migrationLink,
                            ),
                        )
                    }
                    withContext(Dispatchers.Main) {
                        updateState {
                            copy(
                                deprecatedNotes = notesList,
                            )
                        }
                    }
                }
            }
        }
    }

    fun updateServiceStatus(status: Status) {
        viewModelScope.launch {
            _serviceStatus.emit(status)
            updateState {
                copy(
                    serviceStatus = status,
                )
            }
            handleServiceStatusChange(status)
        }
    }

    private fun handleServiceStatusChange(status: Status) {
        val isRemote = RemoteControlManager.remoteServer.value != null
        when (status) {
            Status.Started -> {
                checkDeprecatedNotes()
                if (isRemote) {
                    return
                }
                reloadSystemProxyStatus()
                reloadStartedAt()
            }

            Status.Stopped -> {
                if (isRemote) {
                    return
                }
                updateState {
                    copy(
                        serviceStartTime = null,
                        clashModeVisible = false,
                        systemProxyVisible = false,
                        trafficVisible = false,
                    )
                }
            }

            else -> {}
        }
    }

    private fun reloadStartedAt() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val startedAt = Libbox.newStandaloneCommandClient().startedAt
                withContext(Dispatchers.Main) {
                    updateState {
                        copy(serviceStartTime = startedAt)
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun reloadSystemProxyStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val status = Libbox.newStandaloneCommandClient().systemProxyStatus
                withContext(Dispatchers.Main) {
                    updateState {
                        copy(
                            systemProxyVisible = status.available,
                            systemProxyEnabled = status.enabled,
                        )
                    }
                }
            } catch (e: Exception) {
                // Ignore errors
            }
        }
    }

    fun toggleSystemProxy(enabled: Boolean) {
        if (currentState.systemProxySwitching) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                updateState { copy(systemProxySwitching = true) }
                Settings.systemProxyEnabled = enabled
                Libbox.newStandaloneCommandClient().setSystemProxyEnabled(enabled)
                delay(1000L)
                withContext(Dispatchers.Main) {
                    updateState {
                        copy(
                            systemProxyEnabled = enabled,
                            systemProxySwitching = false,
                        )
                    }
                }
            } catch (e: Exception) {
                sendError(e)
                updateState { copy(systemProxySwitching = false) }
            }
        }
    }

    // CommandClient.Handler implementation
    override fun onConnected() {
        viewModelScope.launch(Dispatchers.Main) {
            // Returning from remote control skipped the local reloads that
            // normally run when the service starts; after a reload (which
            // connects again) the core has a new start time.
            if (RemoteControlManager.remoteServer.value == null && _serviceStatus.value == Status.Started) {
                reloadSystemProxyStatus()
                reloadStartedAt()
            }
        }
    }

    override fun updateStatus(status: StatusMessage) {
        // Only set trafficVisible to true, never back to false from status updates.
        if (!status.trafficAvailable || currentState.trafficVisible) return
        viewModelScope.launch(Dispatchers.Main) {
            updateState { copy(trafficVisible = true) }
        }
    }

    override fun initializeClashMode(modeList: List<String>, currentMode: String) {
        viewModelScope.launch(Dispatchers.Main) {
            updateState {
                copy(
                    clashModeVisible = modeList.size > 1,
                    clashModes = modeList,
                    selectedClashMode = currentMode,
                )
            }
        }
    }

    override fun updateClashMode(newMode: String) {
        viewModelScope.launch(Dispatchers.Main) {
            updateState {
                copy(selectedClashMode = newMode)
            }
        }
    }

}
