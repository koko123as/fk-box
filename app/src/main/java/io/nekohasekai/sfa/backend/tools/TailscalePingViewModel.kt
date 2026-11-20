package io.nekohasekai.sfa.backend.tools

import androidx.lifecycle.ViewModel
import io.nekohasekai.libbox.Libbox

/**
 * Scopes the Go Tailscale ping (libbox core_tailscale_ping.go) to the
 * activity, as the old Kotlin state holder was: a new one starts from the
 * initial state, and clearing it stops the ping.
 */
class TailscalePingViewModel : ViewModel() {
    init {
        Libbox.uiTailscalePingReset()
    }

    override fun onCleared() {
        Libbox.uiTailscalePingStop()
        super.onCleared()
    }
}
