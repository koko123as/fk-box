package io.nekohasekai.sfa.backend.tools

import io.nekohasekai.sfa.bridge.UiStunTool
import androidx.lifecycle.ViewModel

/**
 * Scopes the STUN tool (UiStunTool) to the activity, as the
 * old Kotlin state holder was: a new one starts from the initial state, and
 * clearing it cancels the running test.
 */
class STUNTestViewModel : ViewModel() {
    init {
        UiStunTool.stopWith(true)
    }

    override fun onCleared() {
        UiStunTool.stop()
        super.onCleared()
    }
}
