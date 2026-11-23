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
import io.nekohasekai.sfa.database.CoreStore
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.TypedProfile
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.database.ServiceSettings
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

class BoxService(private val service: Service, private val platformInterface: PlatformInterface) : CommandServerHandler {
    internal data class DashboardConfig(val server: CommandServer, val profileId: Long, val content: String)

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

        /**
         * What the routing rules did at the last start or reload, shown on
         * Settings › Traffic routing: mobile-ffi service.rs `routing_status`
         * (`core`, `applied`, `active`, `fallback`, `error`, `warnings`, `time`).
         */
        private val _routingStatus = MutableStateFlow<JsonElement?>(null)
        internal val routingStatus = _routingStatus.asStateFlow()

        /** The UI language picks the notices' text (mobile-ffi service.rs). */
        private fun zh() = java.util.Locale.getDefault().language == "zh"


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
    // [routing] is the routing rule set the core was compiled with (client-core routing::core_json).
    private class StartedConfig(val profileId: Long, val content: String, val core: String, val routing: String?)
    @Volatile private var lastStarted: StartedConfig? = null
    private var startupJob: Job? = null
    @Volatile private var stopRequested = false

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

            try {
                val routing = startOrReloadCore(content, profile.typed.core)
                lastStarted = StartedConfig(selectedProfileId, content, profile.typed.core, routing)
            } catch (e: Exception) {
                failStart("core", e)
                return
            }

            dashboardServer = commandServer
            _dashboardConfig.value = DashboardConfig(commandServer, selectedProfileId, content)
            if (!wifiPermitted()) {
                stopAndAlert(Alert.RequestLocationPermission)
                return
            }

            withContext(Dispatchers.Main) {
                if (stopRequested) return@withContext
                notification.show(lastProfileName, "started")
                notification.start()
                val started = life("startSucceeded") { put("stopRequested", stopRequested); put("profile", lastProfileName) }
                started.int("status")?.let { status.value = Status.entries[it] }
            }
        } catch (e: Exception) {
            failStart("service", e)
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
        val (selectedProfileId, profile, content) = selectedProfile() ?: return
        lastProfileName = profile.name
        lastReloadFellBack = false
        try {
            val routing = startOrReloadCore(content, profile.typed.core)
            lastStarted = StartedConfig(selectedProfileId, content, profile.typed.core, routing)
        } catch (e: Exception) {
            val previous = lastStarted
            // mobile-ffi lifecycle.rs: fall back when the profile or its routing
            // rules changed since the last good start.
            val fallback = life("reloadFallback") {
                put("previous", previous?.let {
                    buildJsonObject {
                        put("profileId", it.profileId)
                        put("content", it.content)
                        put("routing", it.routing)
                    }
                } ?: JsonNull)
                put("selected", selectedProfileId)
                put("content", content)
                put("currentRouting", previous?.takeIf { it.profileId == selectedProfileId }?.let { currentRouting(it.core, it.content) })
            }
            if (previous != null && fallback.bool("fallback")) {
                val fellBack = runCatching { startOrReloadCore(previous.content, previous.core, previous.routing) }
                if (fellBack.isSuccess) {
                    Log.w(TAG, "reload failed, kept the previous configuration", e)
                    lastReloadFellBack = true
                    throw e
                }
                fellBack.exceptionOrNull()?.let(e::addSuppressed)
            }
            failStart("core", e)
            // Return reload failures to the command client so configuration
            // editors can restore their previous content instead of reporting success.
            throw e
        }

        dashboardServer = commandServer
        _dashboardConfig.value = DashboardConfig(commandServer, selectedProfileId, content)
        if (!wifiPermitted()) {
            stopAndAlert(Alert.RequestLocationPermission)
            return
        }
    }

    private data class Selected(val id: Long, val profile: io.nekohasekai.sfa.database.Profile, val content: String)

    /** mobile-ffi lifecycle.rs (`lifecycle.*`): the start and stop decisions. */
    private fun life(method: String, build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}): JsonObject =
        CoreStore.call("lifecycle.$method", build).jsonObject

    /** The selected profile and its content, or null after the empty configuration alert. */
    private suspend fun selectedProfile(): Selected? {
        val id = Settings.selectedProfile
        val profile = if (id == -1L) null else ProfileManager.get(id)
        val content = profile?.let { File(it.typed.path).readText() }
        val check = life("profileCheck") {
            put("selected", id)
            put("found", profile != null)
            put("blank", content.isNullOrBlank())
        }
        check.int("alert")?.let {
            stopAndAlert(Alert.entries[it])
            return null
        }
        return Selected(id, profile!!, content!!)
    }

    /** False when the core reads the Wi-Fi state without the permission it needs. */
    private fun wifiPermitted(): Boolean {
        if (!commandServer.needWIFIState()) return true
        val permission = life("wifiPermission") { put("sdk", Build.VERSION.SDK_INT) }.string("permission")!!
        return service.hasPermission(permission)
    }

    /** A failed start step ([stage]: commandServer, core, service), alerted as mobile-ffi classifies it. */
    private suspend fun failStart(stage: String, error: Exception) {
        val failure = life("startFailure") { put("stage", stage); put("stopRequested", stopRequested) }
        stopAndAlert(Alert.entries[failure.int("alert")!!], error.message)
    }

    private fun JsonObject.int(key: String): Int? = (get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()

    private fun JsonObject.bool(key: String): Boolean = (get(key) as? JsonPrimitive)?.content == "true"

    /** The routing rule set libbox compiles for this profile's core now (Settings › Routing rules). */
    private fun currentRouting(declaredCore: String, content: String): String? = runCatching {
        ServiceSettings.routingCoreJson(Libbox.resolveProfileCore(declaredCore, content))
    }.getOrNull()

    /**
     * Starts or reloads the profile on its core with that core's routing rules
     * applied to the content handed to the core (the file is not changed), and
     * returns the rule set used. [routing] overrides the saved rules (fallback).
     */
    private fun startOrReloadCore(content: String, declaredCore: String, routing: String? = null): String? {
        val core = Libbox.resolveProfileCore(declaredCore, content)
        Settings.requireCoreEnabled(core)
        var unreadable: String? = null
        val rules = routing ?: runCatching { ServiceSettings.routingCoreJson(core) }
            .onFailure {
                Log.w(TAG, "routing rules unreadable, starting without them", it)
                unreadable = it.message ?: it.javaClass.simpleName
            }.getOrDefault("")
        // Xray/mihomo geo data: the bundled files are extracted and the others
        // (geoip.dat, ASN.mmdb) downloaded the first time the profile or its
        // routing rules need them, outside the CA lock since it may take a while.
        val geoMissing = if (core != TypedProfile.CORE_SING_BOX) GeoResources.prepareForCoreBlocking(core, content + "\n" + rules, service) else null
        // Geo data neither in the APK nor downloadable now: a notice, and the core may still start.
        val geoNotice = geoMissing?.let { detail ->
            Log.w(TAG, "geo data missing: $detail")
            CoreStore.call("service.geoMissing") { put("detail", detail); put("zh", zh()) }.jsonObject
        }
        geoNotice?.string("notice")?.let(::toast)
        return try {
            startCoreLocked(core, content, rules, unreadable)
        } catch (error: Exception) {
            if (geoNotice == null) throw error
            throw Exception("${error.message}\n\n${geoNotice.string("text")}", error)
        }
    }

    private fun startCoreLocked(core: String, content: String, rules: String, unreadable: String?): String = synchronized(MitmCertificate.accessLock) {
        val overrides = OverrideOptions().apply {
            autoRedirect = Settings.autoRedirect
            if (Settings.perAppProxyEnabled) {
                // mobile-ffi service.rs: the app itself always stays proxied.
                val packages = CoreStore.call("service.perAppPackages") {
                    put("enabled", true)
                    put("mode", Settings.getEffectivePerAppProxyMode())
                    put("list", JsonArray(Settings.getEffectivePerAppProxyList().map { JsonPrimitive(it) }))
                    put("own", Application.application.packageName)
                }.jsonObject
                packages["include"]?.let { includePackage = PlatformInterfaceWrapper.StringArray(it.strings().iterator()) }
                packages["exclude"]?.let { excludePackage = PlatformInterfaceWrapper.StringArray(it.strings().iterator()) }
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
            DefaultNetworkMonitor.pushSystemDNS()
        }
        // The rules never keep the profile from running: libbox starts it
        // without them and reports why (CoreRoutingResult.error).
        val result = commandServer.startOrReloadCoreServiceWithRouting(core, content, rules, overrides)
        publishRoutingStatus(core, result, unreadable)
        rules
    }

    private fun toast(text: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(service, text, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonElement.strings(): List<String> = jsonArray.map { it.jsonPrimitive.content }

    /**
     * Publishes what the routing rules did (mobile-ffi service.rs shapes it)
     * and, when they newly failed, a notice even if the app is closed: the
     * service is running, but without the rules the user set.
     */
    private fun publishRoutingStatus(core: String, result: io.nekohasekai.libbox.CoreRoutingResult?, unreadable: String?) {
        val previousError = (routingStatus.value as? JsonObject)?.string("error").orEmpty()
        val answer = CoreStore.call("service.routingStatus") {
            put("core", core)
            put("result", result?.let {
                buildJsonObject {
                    put("applied", it.applied)
                    put("active", it.active)
                    put("fallback", it.fallback)
                    put("error", it.error.orEmpty())
                    put("warnings", it.warnings.orEmpty())
                }
            } ?: JsonNull)
            unreadable?.let { put("unreadable", it) }
            put("previousError", previousError)
            put("now", System.currentTimeMillis())
            put("zh", zh())
        }.jsonObject
        _routingStatus.value = answer["status"]
        answer.string("notice")?.let(::toast)
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
        val stop = life("stopRequest") { put("status", status.value!!.ordinal) }
        if (!stop.bool("stop")) return
        stopRequested = true
        status.value = Status.entries[stop.int("status")!!]
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
                val finished = life("stopFinished") { put("restartAfterStop", restartAfterStop) }
                status.value = Status.entries[finished.int("status")!!]
                if (finished.bool("restart")) {
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
        // mobile-ffi lifecycle.rs: a start while stopping restarts once stopped;
        // a start while starting or started only repeats startForeground, which
        // a start through startForegroundService needs within seconds.
        val start = life("startCommand") {
            put("status", status.value!!.ordinal)
            put("profile", lastProfileName)
        }
        notification.show(start["notification"]!!.jsonObject)
        when (start.string("action")) {
            "start" -> Unit
            "restartAfterStop" -> {
                restartAfterStop = true
                return Service.START_STICKY
            }
            else -> return Service.START_STICKY
        }
        stopRequested = false
        status.value = Status.entries[start.int("status")!!]

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
