// FK-box host state for FlClash's Android service channel.
//
// FlClash keeps the run intent in ServiceState/ServiceStateMachine and starts its
// own VpnService. FK-box keeps BoxService (VPN permission, foreground service,
// notification, per-app proxy, libbox), so this object only translates FlClash's
// start/stop/getRunTime/syncState calls to BoxActivity and BoxService and reports
// a run that ends back to Dart.
package com.follow.clash

import com.follow.clash.models.SharedState
import com.follow.clash.plugins.ServicePlugin
import io.nekohasekai.sfa.bg.BoxService
import io.nekohasekai.sfa.bridge.BoxActivity
import io.nekohasekai.sfa.constant.Status
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.ref.WeakReference

object FkHost {
    private var activityRef = WeakReference<BoxActivity>(null)
    private var servicePlugin: ServicePlugin? = null
    private val statusFlow = MutableStateFlow(Status.Stopped)

    @Volatile
    var sharedState = SharedState()
        private set

    @Volatile
    var isRunRequested = false
        private set

    @Volatile
    var startedAtMillis = 0L
        private set

    val status: StateFlow<Status> = statusFlow

    val activity: BoxActivity?
        get() = activityRef.get()

    fun attachActivity(activity: BoxActivity) {
        activityRef = WeakReference(activity)
    }

    fun detachActivity(activity: BoxActivity) {
        if (activityRef.get() === activity) activityRef = WeakReference(null)
    }

    fun attachService(plugin: ServicePlugin) {
        servicePlugin = plugin
    }

    fun detachService(plugin: ServicePlugin) {
        if (servicePlugin === plugin) servicePlugin = null
    }

    fun syncSharedState(state: SharedState) {
        sharedState = state
    }

    fun requestStart(): Boolean {
        val activity = activity ?: return false
        isRunRequested = true
        activity.runOnUiThread { activity.startService() }
        return true
    }

    fun requestStop(): Boolean {
        isRunRequested = false
        val activity = activity
        if (activity != null) activity.runOnUiThread { activity.stopService() } else BoxService.stop()
        return true
    }

    /** FlClash's ServiceStateMachine.awaitRunTime: the start time of a settled run, or 0. */
    suspend fun awaitRunTime(): Long {
        withTimeoutOrNull(SETTLE_TIMEOUT_MS) {
            statusFlow.first { it == Status.Started || it == Status.Stopped }
        }
        return startedAtMillis.takeIf { isRunRequested && statusFlow.value == Status.Started } ?: 0L
    }

    fun onServiceStatusChanged(status: Status) {
        statusFlow.value = status
        when (status) {
            Status.Started -> {
                isRunRequested = true
                if (startedAtMillis == 0L) startedAtMillis = System.currentTimeMillis()
            }
            Status.Stopped -> {
                startedAtMillis = 0L
                isRunRequested = false
                servicePlugin?.notifyStopped()
            }
            else -> Unit
        }
    }

    private const val SETTLE_TIMEOUT_MS = 20_000L
}
