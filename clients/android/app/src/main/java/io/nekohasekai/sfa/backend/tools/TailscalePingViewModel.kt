package io.nekohasekai.sfa.backend.tools

import io.nekohasekai.sfa.bridge.UiPingTool
import androidx.lifecycle.ViewModel

/**
 * Scopes the Tailscale ping (UiPingTool) to the
 * activity, as the old Kotlin state holder was: a new one starts from the
 * initial state, and clearing it stops the ping.
 */
class TailscalePingViewModel : ViewModel() {
    init {
        UiPingTool.stopWith(true)
    }

    override fun onCleared() {
        UiPingTool.stopWith(false)
        super.onCleared()
    }
}
