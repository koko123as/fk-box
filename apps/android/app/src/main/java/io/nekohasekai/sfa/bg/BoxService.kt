package io.nekohasekai.sfa.bg

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.MutableLiveData
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.constant.Action
import io.nekohasekai.sfa.constant.Alert
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * The service host of the single-core kernel (stage 6,
 * docs/architecture/brain-android.md): the foreground service, its
 * notification and the start/stop state machine (mobile-ffi
 * service_state.rs decides), over [FkHost]: the Rust brain imports the
 * selected profile and runs it on the one sing-box kernel in this process.
 * There is no command server: the UI calls the running service and the
 * kernel directly (same process).
 */
class BoxService(private val service: Service) {
    /** The profile the kernel runs now (UI: dashboard, subscription updates). */
    internal data class DashboardConfig(val profileId: Long, val content: String)

    companion object {
        private const val TAG = "BoxService"

        private val _dashboardConfig = MutableStateFlow<DashboardConfig?>(null)
        internal val dashboardConfig = _dashboardConfig.asStateFlow()

        /** The running service instance, for in-process commands ([reloadRunning]). */
        @Volatile private var current: BoxService? = null

        /** When the running profile started (epoch ms), 0 when stopped. */
        @Volatile internal var startedAt = 0L
            private set

        /**
         * Set when the last reload failed but the previous configuration of the same
         * profile was started again, so the service never stopped.
         */
        @Volatile internal var lastReloadFellBack = false
            private set

        /**
         * What the routing rules did at the last start or reload (Settings ›
         * Traffic routing). The brain runs the profile's own rules; the app's
         * routing rules are not applied on the single-core path, so nothing
         * is published here.
         */
        private val _routingStatus = MutableStateFlow<JsonElement?>(null)
        internal val routingStatus = _routingStatus.asStateFlow()

        @OptIn(DelicateCoroutinesApi::class)
        fun start() = GlobalScope.launch(Dispatchers.Main.immediate) {
            Settings.dataStore.initialize()
            withContext(Dispatchers.IO) {
                Settings.rebuildServiceMode()
            }
            val intent = Intent(Application.application, Settings.serviceClass())
            ContextCompat.startForegroundService(Application.application, intent)
        }

        fun stop() {
            Application.application.sendBroadcast(
                Intent(Action.SERVICE_CLOSE).setPackage(
                    Application.application.packageName,
                ),
            )
        }

        /**
         * Reloads the running service with the selected profile (formerly the
         * command client's serviceReload). Throws the reload's failure.
         */
        suspend fun reloadRunning() {
            val running = current ?: error("The service is not running")
            running.serviceReload0()
        }
    }

    var fileDescriptor: ParcelFileDescriptor? = null

    private val status = MutableLiveData(Status.Stopped)
    private val binder = ServiceBinder(status)
    private val notification = SystemNotifications(status, service)
    private var receiverRegistered = false
    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Action.SERVICE_CLOSE -> {
                        stopService()
                    }
                }
            }
        }

    private var lastProfileName = ""

    // The profile the kernel last started with, used to fall back when a
    // reload of the same profile fails instead of stopping the service.
    private class StartedConfig(val profileId: Long, val content: String)

    @Volatile private var lastStarted: StartedConfig? = null
    private var startupJob: Job? = null

    @Volatile private var stopRequested = false

    // Start, reload and stop of the kernel never overlap.
    private val kernelLock = Mutex()

    // A start that arrived while the previous run was stopping: the stop job
    // starts the service again instead of stopping it (onStartCommand).
    private var restartAfterStop = false

    private suspend fun startService() {
        try {
            val (selectedProfileId, profile, content) = selectedProfile() ?: return

            lastProfileName = profile.name
            withContext(Dispatchers.Main) {
                notification.show(lastProfileName, "starting")
            }

            DefaultNetworkMonitor.start()

            // Kill switch: block the network while the kernel starts; its own TUN
            // replaces this one.
            if (state("startGuard") { put("killSwitch", Settings.killSwitch); put("vpn", service is VPNService) }.bool("block")) {
                runCatching { (service as VPNService).openBlockingTun() }.onFailure { Log.w(TAG, "kill switch: open blocking tun", it) }
            }

            try {
                startKernel(selectedProfileId, content, reload = false)
                lastStarted = StartedConfig(selectedProfileId, content)
            } catch (e: Exception) {
                failStart("core", e)
                return
            }

            _dashboardConfig.value = DashboardConfig(selectedProfileId, content)

            withContext(Dispatchers.Main) {
                if (stopRequested) return@withContext
                notification.show(lastProfileName, "started")
                notification.start()
                val started = state("startSucceeded") { put("stopRequested", stopRequested); put("profile", lastProfileName) }
                started.intOrNull("status")?.let { status.value = Status.entries[it] }
            }
        } catch (e: Exception) {
            failStart("service", e)
            return
        }
    }

    /**
     * Runs [content] on the kernel: the geo data its rules read is put in
     * place first, then the brain imports it and starts (or reloads) the
     * kernel. Only the VPN service can host the kernel (its TUN inbound).
     */
    private suspend fun startKernel(profileId: Long, content: String, reload: Boolean) = kernelLock.withLock {
        val vpn = service as? VPNService ?: error("android: the single-core kernel runs in VPN mode only")
        GeoResources.prepareForBrainBlocking(content, service)?.let { Log.w(TAG, "geo data missing: $it") }
        withContext(Dispatchers.IO) {
            if (reload && FkHost.running()) {
                FkHost.reload(content, profileId)
            } else {
                FkHost.start(vpn.platform, content, profileId)
            }
        }
        startedAt = System.currentTimeMillis()
    }

    suspend fun serviceReload0() {
        val (selectedProfileId, profile, content) = selectedProfile() ?: return
        lastReloadFellBack = false
        try {
            startKernel(selectedProfileId, content, reload = true)
            lastStarted = StartedConfig(selectedProfileId, content)
            // The notification names the running profile: retitle it when the reload
            // switched profiles (a failed reload keeps the previous name).
            if (profile.name != lastProfileName) {
                lastProfileName = profile.name
                notification.retitle(lastProfileName)
            }
        } catch (e: Exception) {
            val previous = lastStarted
            // mobile-ffi service_state.rs: fall back when the profile changed
            // since the last good start.
            val fallback = state("reloadFallback") {
                put(
                    "previous",
                    previous?.let {
                        buildJsonObject {
                            put("profileId", it.profileId)
                            put("content", it.content)
                            put("routing", JsonNull)
                        }
                    } ?: JsonNull,
                )
                put("selected", selectedProfileId)
                put("content", content)
                put("currentRouting", JsonNull)
            }
            if (previous != null && fallback.bool("fallback")) {
                val fellBack = runCatching { startKernel(previous.profileId, previous.content, reload = true) }
                if (fellBack.isSuccess) {
                    Log.w(TAG, "reload failed, kept the previous configuration", e)
                    lastReloadFellBack = true
                    throw e
                }
                fellBack.exceptionOrNull()?.let(e::addSuppressed)
            }
            failStart("core", e)
            // Reload failures go back to the caller so configuration editors
            // can restore their previous content instead of reporting success.
            throw e
        }
        _dashboardConfig.value = DashboardConfig(selectedProfileId, content)
    }

    private data class Selected(val id: Long, val profile: io.nekohasekai.sfa.database.Profile, val content: String)

    /** mobile-ffi service_state.rs (`serviceState.*`): the start and stop decisions. */
    private fun state(method: String, build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) =
        CoreBridge.call("serviceState.$method", build)

    /** The selected profile and its content, or null after the empty configuration alert. */
    private suspend fun selectedProfile(): Selected? {
        val id = Settings.selectedProfile
        val profile = if (id == -1L) null else ProfileManager.get(id)
        val content = profile?.let { File(it.typed.path).readText() }
        val check = state("profileCheck") {
            put("selected", id)
            put("found", profile != null)
            put("blank", content.isNullOrBlank())
        }
        check.intOrNull("alert")?.let {
            stopAndAlert(Alert.entries[it])
            return null
        }
        return Selected(id, profile!!, content!!)
    }

    /** A failed start step ([stage]: commandServer, core, service), alerted as mobile-ffi classifies it. */
    private suspend fun failStart(stage: String, error: Exception) {
        val failure = state("startFailure") {
            put("stage", stage)
            put("stopRequested", stopRequested)
            put("killSwitch", Settings.killSwitch)
            put("vpn", service is VPNService)
        }
        val alert = Alert.entries[failure.int("alert")]
        if (failure.bool("block") && blockAfterFailure(alert, error.message)) return
        stopAndAlert(alert, error.message)
    }

    /**
     * Kill switch: the kernel failed, so stop it and keep the VPN up on a TUN
     * nothing reads, dropping traffic until the user stops the service. The
     * alert still reports the failure. False when the blocking TUN cannot be
     * opened; the service then stops.
     */
    private suspend fun blockAfterFailure(alert: Alert, message: String?): Boolean {
        closeKernel()
        val blocked = runCatching { (service as VPNService).openBlockingTun() }
            .onFailure { Log.w(TAG, "kill switch: open blocking tun", it) }
            .isSuccess
        if (!blocked) return false
        lastStarted = null
        withContext(Dispatchers.Main) {
            if (stopRequested) return@withContext
            notification.close()
            notification.show(state("blockedNotification") { put("profile", lastProfileName) })
            status.value = Status.Started
            binder.broadcast { callback -> callback.onServiceAlert(alert.ordinal, message) }
        }
        return true
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun stopService() {
        val stop = state("stopRequest") { put("status", status.value!!.ordinal) }
        if (!stop.bool("stop")) return
        stopRequested = true
        status.value = Status.entries[stop.int("status")]
        if (receiverRegistered) {
            service.unregisterReceiver(receiver)
            receiverRegistered = false
        }
        notification.close()
        lastStarted = null
        GlobalScope.launch(Dispatchers.IO) {
            startupJob?.join()
            closeKernel()
            closeTun()
            DefaultNetworkMonitor.stop()
            Settings.startedByUser = false
            Settings.dataStore.flush()
            withContext(Dispatchers.Main) {
                val finished = state("stopFinished") { put("restartAfterStop", restartAfterStop) }
                status.value = Status.entries[finished.int("status")]
                if (finished.bool("restart")) {
                    restartAfterStop = false
                    onStartCommand()
                } else {
                    service.stopSelf()
                }
            }
        }
    }

    /** Stops the kernel (the brain stops the host); the TUN stays until [closeTun]. */
    private suspend fun closeKernel() {
        _dashboardConfig.value = null
        startedAt = 0L
        kernelLock.withLock {
            runCatching { withContext(Dispatchers.IO) { FkHost.stop() } }.onFailure { Log.w(TAG, "stop kernel", it) }
        }
    }

    private fun closeTun() {
        val pfd = fileDescriptor ?: return
        fileDescriptor = null
        runCatching { pfd.close() }
    }

    private suspend fun stopAndAlert(type: Alert, message: String? = null) {
        if (stopRequested) return // The stop job joins startup and owns teardown.
        Settings.startedByUser = false
        Settings.dataStore.flush()
        closeKernel()
        closeTun()
        runCatching { DefaultNetworkMonitor.stop() }.onFailure { Log.w(TAG, "stop network monitor", it) }
        withContext(Dispatchers.Main) {
            if (stopRequested) return@withContext
            if (receiverRegistered) {
                service.unregisterReceiver(receiver)
                receiverRegistered = false
            }
            notification.close()
            binder.broadcast { callback ->
                callback.onServiceAlert(type.ordinal, message)
            }
            status.value = Status.Stopped
            service.stopSelf()
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Suppress("SameReturnValue")
    internal fun onStartCommand(): Int {
        // mobile-ffi service_state.rs: a start while stopping restarts once stopped;
        // a start while starting or started only repeats startForeground, which
        // a start through startForegroundService needs within seconds.
        val start = state("startCommand") {
            put("status", status.value!!.ordinal)
            put("profile", lastProfileName)
        }
        notification.show(start["notification"]!!.jsonObject)
        when (start.textOrNull("action")) {
            "start" -> Unit
            "restartAfterStop" -> {
                restartAfterStop = true
                return Service.START_STICKY
            }
            else -> return Service.START_STICKY
        }
        stopRequested = false
        status.value = Status.entries[start.int("status")]
        current = this

        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                service,
                receiver,
                IntentFilter(Action.SERVICE_CLOSE),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        }

        startupJob = GlobalScope.launch(Dispatchers.IO) {
            Settings.startedByUser = true
            try {
                Application.application.setupHost()
            } catch (e: Exception) {
                failStart("commandServer", e)
                return@launch
            }
            startService()
        }
        // Sticky: if the system kills the process while connected, it recreates the
        // service (null intent) and the connection is brought back up.
        return Service.START_STICKY
    }

    internal fun onBind(): IBinder = binder

    internal fun onDestroy() {
        if (current === this) current = null
        binder.close()
    }

    internal fun onRevoke() {
        stopService()
    }
}
