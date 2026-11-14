package io.nekohasekai.sfa.backend.tools

import androidx.lifecycle.viewModelScope
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.STUNTestHandler
import io.nekohasekai.libbox.STUNTestProgress
import io.nekohasekai.libbox.STUNTestResult
import io.nekohasekai.libbox.STUNTestSession
import io.nekohasekai.sfa.backend.base.BaseViewModel
import io.nekohasekai.sfa.backend.base.SessionSlot
import io.nekohasekai.sfa.utils.CommandTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class STUNTestState(
    val phase: Int = -1,
    val externalAddr: String = "",
    val latencyMs: Int = 0,
    val natMapping: Int = 0,
    val natFiltering: Int = 0,
    val natTypeSupported: Boolean = false,
    val isRunning: Boolean = false,
    val server: String = Libbox.STUNDefaultServer,
    val selectedOutbound: String = "",
)

class STUNTestViewModel : BaseViewModel<STUNTestState>() {
    private var standaloneTest: io.nekohasekai.libbox.STUNTest? = null
    private val stunSession = SessionSlot<STUNTestSession> { it.close() }

    override fun createInitialState() = STUNTestState()

    fun updateServer(server: String) {
        updateState { copy(server = server) }
    }

    fun selectOutbound(tag: String) {
        updateState { copy(selectedOutbound = tag) }
    }

    fun onVpnDisconnected() {
        cancelTest()
        updateState { copy(selectedOutbound = "") }
    }

    fun startTest(vpnRunning: Boolean) {
        updateState {
            copy(
                phase = -1,
                externalAddr = "",
                latencyMs = 0,
                natMapping = 0,
                natFiltering = 0,
                natTypeSupported = false,
                isRunning = true,
            )
        }

        val server = currentState.server
        val outboundTag = currentState.selectedOutbound
        // A new test replaces (and closes) the previous remote session; the
        // handler only reports for the test it was created for.
        val generation = stunSession.begin()
        val handler = createHandler(generation)

        if (vpnRunning) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val session =
                        CommandTarget.standaloneClient()
                            .startSTUNTest(server, outboundTag, handler)
                    // Cancelled or replaced while it was starting: attach closes it.
                    stunSession.attach(generation, session)
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        if (!stunSession.isCurrent(generation) || !currentState.isRunning) return@withContext
                        updateState { copy(isRunning = false) }
                        sendError(e)
                    }
                }
            }
        } else {
            val test = Libbox.newSTUNTest()
            standaloneTest = test
            viewModelScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        test.start(server, handler)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (standaloneTest !== test || !currentState.isRunning) return@launch
                    standaloneTest = null
                    updateState { copy(isRunning = false) }
                    sendError(e)
                }
            }
        }
    }

    fun cancelTest() {
        stunSession.cancel()
        standaloneTest?.cancel()
        standaloneTest = null
        updateState { copy(isRunning = false) }
    }

    override fun onCleared() {
        cancelTest()
        super.onCleared()
    }

    private fun createHandler(generation: Long): STUNTestHandler {
        return object : STUNTestHandler {
            override fun onProgress(progress: STUNTestProgress?) {
                progress ?: return
                viewModelScope.launch {
                    if (!stunSession.isCurrent(generation) || !currentState.isRunning) return@launch
                    updateState {
                        copy(
                            phase = progress.phase.toInt(),
                            externalAddr = progress.externalAddr,
                            latencyMs = progress.latencyMs.toInt(),
                            natMapping = progress.natMapping.toInt(),
                            natFiltering = progress.natFiltering.toInt(),
                        )
                    }
                }
            }

            override fun onResult(result: STUNTestResult?) {
                result ?: return
                viewModelScope.launch {
                    if (!stunSession.isCurrent(generation) || !currentState.isRunning) return@launch
                    updateState {
                        copy(
                            phase = Libbox.STUNPhaseDone.toInt(),
                            isRunning = false,
                            externalAddr = result.externalAddr,
                            latencyMs = result.latencyMs.toInt(),
                            natMapping = result.natMapping.toInt(),
                            natFiltering = result.natFiltering.toInt(),
                            natTypeSupported = result.natTypeSupported,
                        )
                    }
                    standaloneTest = null
                    stunSession.clear(generation)
                }
            }

            override fun onError(message: String?) {
                viewModelScope.launch {
                    if (!stunSession.isCurrent(generation) || !currentState.isRunning) return@launch
                    updateState { copy(isRunning = false) }
                    standaloneTest = null
                    stunSession.clear(generation)
                    if (message != null) {
                        sendErrorMessage(message)
                    }
                }
            }
        }
    }
}
