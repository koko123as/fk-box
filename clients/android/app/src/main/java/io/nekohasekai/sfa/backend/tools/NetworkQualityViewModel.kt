package io.nekohasekai.sfa.backend.tools

import androidx.lifecycle.ViewModel
import io.nekohasekai.libbox.Libbox

/**
 * Scopes the Go network quality tool (libbox core_quality_tool.go) to the
 * activity, as the old Kotlin state holder was: a new one starts from the
 * initial state, and clearing it cancels the running test.
 */
class NetworkQualityViewModel : ViewModel() {
    init {
        Libbox.uiQualityReset()
    }

    override fun onCleared() {
        Libbox.uiQualityStop()
        super.onCleared()
    }
}
