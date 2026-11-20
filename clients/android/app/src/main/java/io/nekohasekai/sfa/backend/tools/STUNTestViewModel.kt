package io.nekohasekai.sfa.backend.tools

import androidx.lifecycle.ViewModel
import io.nekohasekai.libbox.Libbox

/**
 * Scopes the Go STUN tool (libbox core_stun_tool.go) to the activity, as the
 * old Kotlin state holder was: a new one starts from the initial state, and
 * clearing it cancels the running test.
 */
class STUNTestViewModel : ViewModel() {
    init {
        Libbox.uiStunReset()
    }

    override fun onCleared() {
        Libbox.uiStunStop()
        super.onCleared()
    }
}
