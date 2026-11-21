package io.nekohasekai.sfa.backend.tools

import io.nekohasekai.sfa.bridge.UiQualityTool
import androidx.lifecycle.ViewModel

/**
 * Scopes the network quality tool (UiQualityTool) to the
 * activity, as the old Kotlin state holder was: a new one starts from the
 * initial state, and clearing it cancels the running test.
 */
class NetworkQualityViewModel : ViewModel() {
    init {
        UiQualityTool.stopWith(false, true)
    }

    override fun onCleared() {
        UiQualityTool.stopWith(false, false)
        super.onCleared()
    }
}
