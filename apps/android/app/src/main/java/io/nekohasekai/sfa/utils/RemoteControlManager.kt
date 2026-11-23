package io.nekohasekai.sfa.utils

import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.backend.base.GlobalEventBus
import io.nekohasekai.sfa.backend.base.UiEvent
import io.nekohasekai.sfa.database.RemoteServer
import io.nekohasekai.sfa.database.RemoteServerManager
import io.nekohasekai.sfa.database.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object RemoteControlManager : CommandClient.Handler {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _remoteServer = MutableStateFlow<RemoteServer?>(null)
    val remoteServer = _remoteServer.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected = _isConnected.asStateFlow()

    private val _startedAt = MutableStateFlow<Long?>(null)
    val startedAt = _startedAt.asStateFlow()

    private val monitorClient = CommandClient(scope, CommandClient.ConnectionType.Status, this)

    private var sessionHadConnected = false
    private var restored = false

    fun restore() {
        if (restored) {
            return
        }
        restored = true
        // The monitor runs for a remote session in the foreground and is
        // renewed for a new server (mobile-ffi sessions.rs). Started here,
        // after this object is set up, since the plan reads its flows.
        monitorClient.follow(scope, CommandSessions.Stream.RemoteMonitor)
        scope.launch {
            val server =
                withContext(Dispatchers.IO) {
                    val serverId = Settings.activeRemoteServerId
                    if (serverId == 0L) {
                        return@withContext null
                    }
                    val storedServer = runCatching { RemoteServerManager.get(serverId) }.getOrNull()
                    if (storedServer == null) {
                        Settings.activeRemoteServerId = 0L
                    }
                    storedServer
                }
            if (server != null && _remoteServer.value == null) {
                enterRemoteControl(server)
            }
        }
    }

    suspend fun enterRemoteControl(server: RemoteServer) {
        CommandTarget.setRemoteServer(server)
        resetSessionState()
        _remoteServer.value = server
        scope.launch(Dispatchers.IO) {
            Settings.activeRemoteServerId = server.id
        }
        // Set the target before stopping: concurrent local starts recheck it after
        // libbox acknowledges capture, including restoration of a saved session.
        withContext(NonCancellable + Dispatchers.IO) { Libbox.stopUICapture() }
    }

    fun exitRemoteControl() {
        if (_remoteServer.value == null) {
            return
        }
        CommandTarget.setRemoteServer(null)
        resetSessionState()
        _remoteServer.value = null
        scope.launch(Dispatchers.IO) {
            Settings.activeRemoteServerId = 0L
        }
    }

    private fun resetSessionState() {
        sessionHadConnected = false
        _isConnected.value = false
        _startedAt.value = null
    }

    override fun onConnected() {
        scope.launch {
            if (_remoteServer.value == null) {
                return@launch
            }
            sessionHadConnected = true
            _isConnected.value = true
            val serviceStartedAt =
                withContext(Dispatchers.IO) {
                    runCatching { CommandTarget.standaloneClient().startedAt }.getOrNull()
                }
            if (_isConnected.value) {
                _startedAt.value = serviceStartedAt?.takeIf { it > 0 }
            }
        }
    }

    override fun onDisconnected() {
        scope.launch {
            _isConnected.value = false
            _startedAt.value = null
        }
    }

    override fun onConnectionError(message: String) {
        scope.launch {
            handleConnectionError(message)
        }
    }

    private suspend fun handleConnectionError(message: String) {
        val server = _remoteServer.value ?: return
        if (!AppLifecycleObserver.isForeground.value) {
            return
        }
        val description =
            if (sessionHadConnected) {
                Application.application.getString(R.string.remote_disconnected_from, server.displayName)
            } else {
                Application.application.getString(R.string.remote_connect_failed, server.displayName)
            }
        exitRemoteControl()
        GlobalEventBus.emit(UiEvent.ErrorMessage("$description\n$message"))
    }
}
