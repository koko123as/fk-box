// FK-box host state for FlClash's Android service channel.
//
// FlClash keeps the run intent in ServiceState/ServiceStateMachine and starts its
// own VpnService. FK-box keeps BoxService (VPN permission, foreground service,
// notification, per-app proxy, libbox), so this object only translates FlClash's
// start/stop/getRunTime/syncState calls to BoxActivity and BoxService and reports
// a run that ends back to Dart.
package com.follow.clash

import com.follow.clash.common.AccessControlMode
import com.follow.clash.common.GlobalState
import com.follow.clash.models.SharedState
import com.follow.clash.service.models.VpnOptions
import io.nekohasekai.sfa.database.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.follow.clash.plugins.ServicePlugin
import io.nekohasekai.libbox.Libbox
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
        state.vpnOptions?.let(::applyVpnOptions)
    }

    private val accessScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * FlClash's access control and VPN bypass, applied to the per-app proxy settings
     * BoxService reads. FK-box's own per-app list stays in effect until FlClash's
     * access control is turned on once; FK-box's managed (China apps) mode keeps its list.
     */
    private fun applyVpnOptions(options: VpnOptions) {
        accessScope.launch {
            runCatching {
                Settings.dataStore.initialize()
                val props = options.accessControlProps
                val preferences = GlobalState.application.getSharedPreferences("flclash_host", 0)
                val adopted = preferences.getBoolean("accessControlAdopted", false)
                var changed = false
                if (props.enable || adopted) {
                    if (!adopted) preferences.edit().putBoolean("accessControlAdopted", true).apply()
                    val include = props.mode == AccessControlMode.ACCEPT_SELECTED
                    val mode = if (include) Settings.PER_APP_PROXY_INCLUDE else Settings.PER_APP_PROXY_EXCLUDE
                    val list = (if (include) props.acceptList else props.rejectList).toSet()
                    if (Settings.perAppProxyEnabled != props.enable) {
                        Settings.perAppProxyEnabled = props.enable
                        changed = true
                    }
                    if (!Settings.perAppProxyManagedMode) {
                        if (Settings.perAppProxyMode != mode) {
                            Settings.perAppProxyMode = mode
                            changed = true
                        }
                        if (Settings.perAppProxyList != list) {
                            Settings.perAppProxyList = list
                            changed = true
                        }
                    }
                }
                if (Settings.allowBypass != options.allowBypass) {
                    Settings.allowBypass = options.allowBypass
                    changed = true
                }
                if (changed) Settings.dataStore.flush()
            }.onFailure { GlobalState.log("Access control sync failed: $it") }
        }
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
        if (!isRunRequested || statusFlow.value != Status.Started) return 0L
        val libboxStart = runCatching { Libbox.newStandaloneCommandClient().startedAt }.getOrDefault(0L)
        if (libboxStart > 0L) startedAtMillis = libboxStart
        return startedAtMillis
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
