package io.nekohasekai.sfa.backend.tools

import androidx.lifecycle.viewModelScope
import io.nekohasekai.libbox.TailscalePingHandler
import io.nekohasekai.libbox.TailscalePingResult
import io.nekohasekai.libbox.TailscalePingSession
import io.nekohasekai.sfa.backend.base.BaseViewModel
import io.nekohasekai.sfa.backend.base.SessionSlot
import io.nekohasekai.sfa.utils.CommandTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class TailscalePingState(
    val isRunning: Boolean = false,
    val hasResult: Boolean = false,
    val latencyMs: Double = 0.0,
    val isDirect: Boolean = false,
    val derpRegionCode: String = "",
    val endpoint: String = "",
    val peerRelay: String = "",
    val error: String = "",
    val latencyHistory: List<Float> = emptyList(),
)

class TailscalePingViewModel : BaseViewModel<TailscalePingState>() {
    private val maxHistorySize = 30
    private val pingSession = SessionSlot<TailscalePingSession> { it.close() }

    override fun createInitialState() = TailscalePingState()

    fun startPing(endpointTag: String, peerIP: String) {
        updateState {
            copy(
                isRunning = true,
                hasResult = false,
                error = "",
                latencyHistory = emptyList(),
            )
        }
        // A new ping replaces (and closes) the previous session.
        val generation = pingSession.begin()

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val session =
                    CommandTarget.standaloneClient()
                        .startTailscalePing(
                            endpointTag,
                            peerIP,
                            object : TailscalePingHandler {
                                override fun onPingResult(result: TailscalePingResult?) {
                                    result ?: return
                                    viewModelScope.launch {
                                        if (!pingSession.isCurrent(generation) || !currentState.isRunning) return@launch
                                        if (result.error.isNotEmpty()) {
                                            updateState { copy(error = result.error) }
                                            return@launch
                                        }
                                        val newHistory = currentState.latencyHistory.toMutableList()
                                        newHistory.add(result.latencyMs.toFloat())
                                        if (newHistory.size > maxHistorySize) {
                                            newHistory.removeFirstOrNull()
                                        }
                                        updateState {
                                            copy(
                                                hasResult = true,
                                                latencyMs = result.latencyMs,
                                                isDirect = result.isDirect,
                                                derpRegionCode = result.derpRegionCode,
                                                endpoint = result.endpoint,
                                                peerRelay = result.peerRelay,
                                                error = "",
                                                latencyHistory = newHistory,
                                            )
                                        }
                                    }
                                }

                                override fun onError(message: String?) {
                                    viewModelScope.launch {
                                        if (!pingSession.isCurrent(generation) || !currentState.isRunning) return@launch
                                        updateState { copy(isRunning = false) }
                                        pingSession.clear(generation)
                                    }
                                }
                            },
                        )
                // Stopped or replaced while it was starting: attach closes it.
                pingSession.attach(generation, session)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (!pingSession.isCurrent(generation) || !currentState.isRunning) return@withContext
                    updateState { copy(isRunning = false) }
                }
            }
        }
    }

    fun stopPing() {
        pingSession.cancel()
        updateState { copy(isRunning = false) }
    }

    override fun onCleared() {
        super.onCleared()
        stopPing()
    }
}
