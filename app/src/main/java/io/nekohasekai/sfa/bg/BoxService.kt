package io.nekohasekai.sfa.bg

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.MutableLiveData
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.MainActivity
import io.nekohasekai.sfa.constant.Action
import io.nekohasekai.sfa.constant.Alert
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.TypedProfile
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.bridge.CoreRouting
import io.nekohasekai.sfa.bridge.MitmCertificate
import io.nekohasekai.sfa.ktx.hasPermission
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

class BoxService(private val service: Service, private val platformInterface: PlatformInterface) : CommandServerHandler {
    internal data class DashboardConfig(val server: CommandServer, val profileId: Long, val content: String)

    /**
     * What the routing rules did at the last start or reload (libbox
     * CoreRoutingResult): shown on Settings › Traffic routing. [error] is
     * why the profile runs without its rules; [fallback] is set when the
     * core was started again without them.
     */
    internal data class RoutingStatus(
        val core: String,
        val applied: Int,
        val active: Boolean,
        val fallback: Boolean,
        val error: String,
        val warnings: List<String>,
        val time: Long,
    )

    companion object {
        @Volatile internal var dashboardServer: CommandServer? = null
            private set
        private val _dashboardConfig = MutableStateFlow<DashboardConfig?>(null)
        internal val dashboardConfig = _dashboardConfig.asStateFlow()
        private const val TAG = "BoxService"

        /**
         * Set when the last reload failed but the previous configuration of the same
         * profile was started again, so the service never stopped.
         */
        @Volatile internal var lastReloadFellBack = false
            private set

        private val _routingStatus = MutableStateFlow<RoutingStatus?>(null)
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
    }

    var fileDescriptor: ParcelFileDescriptor? = null

    private val status = MutableLiveData(Status.Stopped)
    private val binder = ServiceBinder(status)
    private val notification = ServiceNotification(status, service)
    private lateinit var commandServer: CommandServer
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

    private fun startCommandServer() {
        Libbox.promoteOOMDraft()
        Libbox.discardPowerReportDraft()
        val commandServer = CommandServer(this, platformInterface)
        commandServer.start()
        this.commandServer = commandServer
        dashboardServer = commandServer
    }

    private var lastProfileName = ""

    // The configuration the core last started with, used to fall back when a
    // reload of the same profile fails (for example a remote rule set that cannot
    // be downloaded while the phone sleeps) instead of stopping the service.
    // [routing] is the routing rule set the core was compiled with (CoreRouting.coreJson).
    private class StartedConfig(val profileId: Long, val content: String, val core: String, val routing: String?)
    @Volatile private var lastStarted: StartedConfig? = null
    private var startupJob: Job? = null
    @Volatile private var stopRequested = false

    // A start that arrived while the previous run was stopping: the stop job
    // starts the service again instead of stopping it (onStartCommand).
    private var restartAfterStop = false

    private suspend fun startService() {
        try {
            val selectedProfileId = Settings.selectedProfile
            if (selectedProfileId == -1L) {
                stopAndAlert(Alert.EmptyConfiguration)
                return
            }

            val profile = ProfileManager.get(selectedProfileId)
            if (profile == null) {
                stopAndAlert(Alert.EmptyConfiguration)
                return
            }

            val content = File(profile.typed.path).readText()
            if (content.isBlank()) {
                stopAndAlert(Alert.EmptyConfiguration)
                return
            }

            lastProfileName = profile.name
            withContext(Dispatchers.Main) {
                notification.show(lastProfileName, R.string.status_starting)
            }

            DefaultNetworkMonitor.start()

            try {
                val routing = startOrReloadCore(content, profile.typed.core)
                lastStarted = StartedConfig(selectedProfileId, content, profile.typed.core, routing)
            } catch (e: Exception) {
                stopAndAlert(Alert.CreateService, e.message)
                return
            }

            dashboardServer = commandServer
            _dashboardConfig.value = DashboardConfig(commandServer, selectedProfileId, content)
            if (commandServer.needWIFIState()) {
                val wifiPermission =
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                        android.Manifest.permission.ACCESS_FINE_LOCATION
                    } else {
                        android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
                    }
                if (!service.hasPermission(wifiPermission)) {
                    stopAndAlert(Alert.RequestLocationPermission)
                    return
                }
            }

            withContext(Dispatchers.Main) {
                if (stopRequested) return@withContext
                notification.show(lastProfileName, R.string.status_started)
                notification.start()
                if (!stopRequested) status.value = Status.Started
            }
        } catch (e: Exception) {
            stopAndAlert(Alert.StartService, e.message)
            return
        }
    }

    override fun serviceStop() {
        notification.close()
        status.postValue(Status.Starting)
        val pfd = fileDescriptor
        if (pfd != null) {
            pfd.close()
            fileDescriptor = null
        }
        closeService()
    }

    override fun serviceReload() {
        runBlocking {
            serviceReload0()
        }
    }

    suspend fun serviceReload0() {
        val selectedProfileId = Settings.selectedProfile
        if (selectedProfileId == -1L) {
            stopAndAlert(Alert.EmptyConfiguration)
            return
        }

        val profile = ProfileManager.get(selectedProfileId)
        if (profile == null) {
            stopAndAlert(Alert.EmptyConfiguration)
            return
        }

        val content = File(profile.typed.path).readText()
        if (content.isBlank()) {
            stopAndAlert(Alert.EmptyConfiguration)
            return
        }
        lastProfileName = profile.name
        lastReloadFellBack = false
        try {
            val routing = startOrReloadCore(content, profile.typed.core)
            lastStarted = StartedConfig(selectedProfileId, content, profile.typed.core, routing)
        } catch (e: Exception) {
            val previous = lastStarted
            // Fall back when the profile or its routing rules changed since the last good start.
            if (previous != null && previous.profileId == selectedProfileId &&
                (previous.content != content || previous.routing != currentRouting(previous.core, previous.content))
            ) {
                val fellBack = runCatching { startOrReloadCore(previous.content, previous.core, previous.routing) }
                if (fellBack.isSuccess) {
                    Log.w(TAG, "reload failed, kept the previous configuration", e)
                    lastReloadFellBack = true
                    throw e
                }
                fellBack.exceptionOrNull()?.let(e::addSuppressed)
            }
            stopAndAlert(Alert.CreateService, e.message)
            // Return reload failures to the command client so configuration
            // editors can restore their previous content instead of reporting success.
            throw e
        }

        dashboardServer = commandServer
        _dashboardConfig.value = DashboardConfig(commandServer, selectedProfileId, content)
        if (commandServer.needWIFIState()) {
            val wifiPermission =
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    android.Manifest.permission.ACCESS_FINE_LOCATION
                } else {
                    android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
                }
            if (!service.hasPermission(wifiPermission)) {
                stopAndAlert(Alert.RequestLocationPermission)
                return
            }
        }
    }

    /** The routing rule set libbox compiles for this profile's core now (Settings › Routing rules). */
    private fun currentRouting(declaredCore: String, content: String): String? = runCatching {
        CoreRouting.coreJson(CoreRouting.decode(Settings.routingRules), Libbox.resolveProfileCore(declaredCore, content))
    }.getOrNull()

    /**
     * Starts or reloads the profile on its core with that core's routing rules
     * applied to the content handed to the core (the file is not changed), and
     * returns the rule set used. [routing] overrides the saved rules (fallback).
     */
    private fun startOrReloadCore(content: String, declaredCore: String, routing: String? = null): String? = synchronized(MitmCertificate.accessLock) {
        val core = Libbox.resolveProfileCore(declaredCore, content)
        Settings.requireCoreEnabled(core)
        val overrides = OverrideOptions().apply {
            autoRedirect = Settings.autoRedirect
            if (Settings.perAppProxyEnabled) {
                val appList = Settings.getEffectivePerAppProxyList()
                if (Settings.getEffectivePerAppProxyMode() == Settings.PER_APP_PROXY_INCLUDE) {
                    includePackage = PlatformInterfaceWrapper.StringArray((appList + Application.application.packageName).iterator())
                } else {
                    excludePackage = PlatformInterfaceWrapper.StringArray((appList - Application.application.packageName).iterator())
                }
            }
            rewriteDirectory = File(service.noBackupFilesDir, "http-debug/captures").absolutePath
            if (core == TypedProfile.CORE_SING_BOX && Libbox.needsUIHTTPRewriteCA(content)) {
                val material = MitmCertificate(service).material()
                rewriteCertificate = material.first
                rewriteSigner = material.second
            }
        }
        // The libbox lifecycle prepares the rules/CA, starts the core, then
        // activates profile processing before reporting startup success.
        // Xray and mihomo get the VpnService TUN fd directly (CommandServer.startOrReloadCoreService).
        if (core != TypedProfile.CORE_SING_BOX) {
            // geoip.dat / geosite.dat / MMDB / ASN come from the APK until downloaded.
            GeoResources.ensureBundledBlocking(service)
            DefaultNetworkMonitor.pushSystemDNS()
        }
        var unreadable: String? = null
        val rules = routing ?: runCatching { CoreRouting.coreJson(CoreRouting.decode(Settings.routingRules), core) }
            .onFailure {
                Log.w(TAG, "routing rules unreadable, starting without them", it)
                unreadable = it.message ?: it.javaClass.simpleName
            }.getOrDefault("")
        // The rules never keep the profile from running: libbox starts it
        // without them and reports why (CoreRoutingResult.error).
        val result = commandServer.startOrReloadCoreServiceWithRouting(core, content, rules, overrides)
        publishRoutingStatus(core, result, unreadable)
        rules
    }

    private fun publishRoutingStatus(core: String, result: io.nekohasekai.libbox.CoreRoutingResult?, unreadable: String?) {
        val error = unreadable?.let { "Routing rules unreadable: $it" } ?: result?.error.orEmpty()
        val previous = routingStatus.value
        val status = RoutingStatus(
            core = core,
            applied = result?.applied ?: 0,
            active = result?.active == true,
            fallback = result?.fallback == true,
            error = error,
            warnings = result?.warnings.orEmpty().lines().filter { it.isNotBlank() },
            time = System.currentTimeMillis(),
        )
        _routingStatus.value = status
        if (error.isNotEmpty() && error != previous?.error) {
            // A notice even when the app is closed: the service is running,
            // but without the rules the user set.
            val zh = java.util.Locale.getDefault().language == "zh"
            val title = if (zh) "分流规则未生效，已按原配置运行" else "Routing rules not applied; the profile runs without them"
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(service, title + "\n" + error.take(160), android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun getSystemProxyStatus(): SystemProxyStatus? {
        val status = SystemProxyStatus()
        if (service is VPNService) {
            status.available = service.systemProxyAvailable
            status.enabled = service.systemProxyEnabled
        }
        return status
    }

    override fun setSystemProxyEnabled(isEnabled: Boolean) {
        serviceReload()
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun stopService() {
        if (status.value != Status.Started && status.value != Status.Starting) return
        stopRequested = true
        status.value = Status.Stopping
        if (receiverRegistered) {
            service.unregisterReceiver(receiver)
            receiverRegistered = false
        }
        notification.close()
        lastStarted = null
        GlobalScope.launch(Dispatchers.IO) {
            startupJob?.join()
            val pfd = fileDescriptor
            if (pfd != null) {
                pfd.close()
                fileDescriptor = null
            }
            DefaultNetworkMonitor.stop()
            if (::commandServer.isInitialized) {
                closeService()
                runCatching { commandServer.close() }
            }
            Libbox.discardPowerReportDraft()
            PowerReportManager.refresh()
            Settings.startedByUser = false
            Settings.dataStore.flush()
            withContext(Dispatchers.Main) {
                status.value = Status.Stopped
                if (restartAfterStop) {
                    restartAfterStop = false
                    onStartCommand()
                } else {
                    service.stopSelf()
                }
            }
        }
    }

    private fun closeService() {
        if (dashboardConfig.value?.server === commandServer) _dashboardConfig.value = null
        if (dashboardServer === commandServer) dashboardServer = null
        runCatching {
            commandServer.closeCoreService()
        }.onFailure {
            commandServer.setError("android: close service: ${it.message}")
        }
    }

    private suspend fun stopAndAlert(type: Alert, message: String? = null) {
        if (stopRequested) return // The stop job joins startup and owns teardown.
        Settings.startedByUser = false
        Settings.dataStore.flush()
        val pfd = fileDescriptor
        if (pfd != null) {
            runCatching { pfd.close() }
            fileDescriptor = null
        }
        runCatching { DefaultNetworkMonitor.stop() }.onFailure { Log.w(TAG, "stop network monitor", it) }
        if (::commandServer.isInitialized) {
            closeService()
            // A failing close must not skip the alert and stopSelf below, which
            // would leave the service in Starting with its notification up.
            runCatching { commandServer.close() }.onFailure { Log.w(TAG, "close command server", it) }
        }
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
        when (status.value) {
            Status.Stopped -> Unit
            Status.Stopping -> {
                // Started with startForegroundService while the last run is still
                // tearing down: the system requires startForeground within seconds,
                // so show the notification now and start again once stopped.
                notification.show(lastProfileName, R.string.status_starting)
                restartAfterStop = true
                return Service.START_STICKY
            }
            else -> {
                // Starting or Started: already in the foreground; repeating the
                // call satisfies this start's startForeground requirement too.
                notification.show(lastProfileName, if (status.value == Status.Started) R.string.status_started else R.string.status_starting)
                return Service.START_STICKY
            }
        }
        stopRequested = false
        status.value = Status.Starting
        notification.show(lastProfileName, R.string.status_starting)

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
                Application.application.setupLibbox()
                startCommandServer()
            } catch (e: Exception) {
                stopAndAlert(Alert.StartCommandServer, e.message)
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
        if (::commandServer.isInitialized && dashboardConfig.value?.server === commandServer) _dashboardConfig.value = null
        if (::commandServer.isInitialized && dashboardServer === commandServer) dashboardServer = null
        binder.close()
    }

    internal fun onRevoke() {
        stopService()
    }

    internal fun sendNotification(notification: Notification) {
        val channel = "notification-${notification.typeID}"
        val builder =
            NotificationCompat.Builder(service, channel).setShowWhen(false)
                .setContentTitle(notification.title).setContentText(notification.body)
                .setOnlyAlertOnce(true).setSmallIcon(R.drawable.ic_menu)
                .setCategory(NotificationCompat.CATEGORY_EVENT)
                .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true)
        if (!notification.subtitle.isNullOrBlank()) {
            builder.setContentInfo(notification.subtitle)
        }
        if (!notification.openURL.isNullOrBlank()) {
            builder.setContentIntent(
                PendingIntent.getActivity(
                    service,
                    0,
                    Intent(
                        service,
                        MainActivity::class.java,
                    ).apply {
                        setAction(Action.OPEN_URL).setData(Uri.parse(notification.openURL))
                        setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    },
                    ServiceNotification.flags,
                ),
            )
        }
        GlobalScope.launch(Dispatchers.Main) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Application.notification.createNotificationChannel(
                    NotificationChannel(
                        channel,
                        notification.typeName,
                        NotificationManager.IMPORTANCE_HIGH,
                    ),
                )
            }
            Application.notification.notify(notification.identifier, notification.typeID, builder.build())
        }
    }

    internal fun cancelNotification(identifier: String, typeID: Int) {
        GlobalScope.launch(Dispatchers.Main) {
            Application.notification.cancel(identifier, typeID)
        }
    }

    override fun triggerNativeCrash() {
        Thread {
            Thread.sleep(200)
            throw RuntimeException("debug native crash")
        }.start()
    }

    override fun writeDebugMessage(message: String?) {
        Log.d("sing-box", message!!)
    }

    override fun connectSSHAgent(): Int = -1
}
