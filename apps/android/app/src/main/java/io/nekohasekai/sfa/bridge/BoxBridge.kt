package io.nekohasekai.sfa.bridge

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import androidx.lifecycle.lifecycleScope
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.backend.base.GlobalEventBus
import io.nekohasekai.sfa.backend.base.UiEvent
import io.nekohasekai.sfa.bg.GeoResources
import io.nekohasekai.sfa.bg.UpdateGeoWork
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.CoreStore
import io.nekohasekai.sfa.database.ProfileContent
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.RemoteServerManager
import io.nekohasekai.sfa.database.ServiceSettings
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.utils.AppLifecycleObserver
import io.nekohasekai.sfa.utils.CommandSessions
import io.nekohasekai.sfa.utils.CommandTarget
import io.nekohasekai.sfa.utils.RemoteControlManager
import io.nekohasekai.sfa.vendor.PackageQueryManager
import io.nekohasekai.sfa.vendor.PerAppProxyScanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable

/**
 * Native facade for the Slint UI: typed commands run through [call] once the
 * bridge is initialized, and state is streamed as events ([listen]).
 */
class BoxBridge(private val activity: BoxActivity) : Closeable {
    fun interface EventSink {
        fun success(event: Map<String, Any?>)
    }

    // The activity's scope, with failures of the bridge's own jobs (stream
    // collectors, profile callbacks, tool sessions) reported to the UI instead
    // of crashing the app. reportError is not used for its own failure.
    private val scope = kotlinx.coroutines.CoroutineScope(
        activity.lifecycleScope.coroutineContext + kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
            android.util.Log.e("BoxBridge", "bridge job failed", e)
            if (e !is CancellationException) runCatching { reportError(e.message ?: e.toString()) }
        },
    )
    private var sink: EventSink? = null
    private var streamJob: Job? = null
    private val queuedErrors = ArrayDeque<String>()
    private lateinit var streams: BoxStreams
    internal lateinit var toolBridge: BoxTools
    private lateinit var dashboardDetails: BoxDashboard
    private var connectionsVisible = false
    private var connectionStatsVisible = false
    private val logsVisible = MutableStateFlow(false)
    private val operations = Mutex()
    private val apps = AppListCache {
        val packageManager = activity.packageManager
        PackageQueryManager.getInstalledPackages(android.content.pm.PackageManager.GET_PERMISSIONS).mapNotNull { info ->
            if (info.packageName == activity.packageName) return@mapNotNull null
            val application = info.applicationInfo ?: return@mapNotNull null
            AppListCache.Entry(
                packageName = info.packageName,
                system = application.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0,
                internet = info.requestedPermissions?.contains(android.Manifest.permission.INTERNET) == true,
                updatedAt = info.lastUpdateTime,
                loadLabel = { application.loadLabel(packageManager).toString() },
            )
        }
    }
    internal val reports = BoxReports(activity)
    private val profiles = BoxProfiles { recover -> withContext(Dispatchers.Main) { activity.applyProfile(recover = recover) } }
    internal val capture = BoxCapture(activity)
    private val qrScanner = BoxQrScanner(profiles::decode)
    private val backups = BoxBackups(activity, ::patchSettings,
        { restart, recover -> withContext(Dispatchers.Main) { activity.applyProfile(restart, recover) } }, ::publishProfiles, ::emit)
    private val profileCallback: () -> Unit = { scope.launch { operations.withLock { publishProfiles() } } }
    private val initialize = scope.async {
        withContext(Dispatchers.IO) {
            Settings.dataStore.initialize()
            Application.application.setupLibbox()
        }
        toolBridge = BoxTools(activity, ::emit)
        dashboardDetails = BoxDashboard(activity, ::emit)
        // The libbox command streams go to the UI raw (mobile-ffi runtime.rs).
        streams = BoxStreams(scope, ::emit) { dashboardDetails.remoteMemory = it.memory }
        ProfileManager.registerCallback(profileCallback)
        PackageQueryManager.registerListeners()
        RemoteControlManager.restore()
        scope.launch {
            activity.serviceStatus.collect { CommandSessions.setServiceStatus(it) }
        }
        // The Log client follows the Logs page instead of running on every page.
        scope.launch { logsVisible.collect { CommandSessions.setLogsVisible(it) } }
        scope.launch {
            GlobalEventBus.events.collect { event ->
                when (event) {
                    is UiEvent.ErrorMessage -> reportError(event.message)
                }
            }
        }
    }

    /** Runs one command (a function of this bridge) after initialization. */
    suspend fun <R> call(command: suspend BoxBridge.() -> R): R {
        initialize.await()
        return command()
    }

    /** Former EventChannel onListen: starts streaming state to [eventSink]. */
    fun listen(eventSink: EventSink) {
        sink = eventSink
        streamJob?.cancel()
        streamJob = scope.launch {
            try {
                initialize.await()
                while (queuedErrors.isNotEmpty()) emit("error", queuedErrors.removeFirst())
                // The service and remote session state, in the background too:
                // the stream runtime keeps what the dashboard, groups and
                // connections show.
                launch { activity.serviceStatus.collect { emit("hostState", hostState()) } }
                launch { RemoteControlManager.remoteServer.collect { emit("hostState", hostState()) } }
                launch { RemoteControlManager.isConnected.collect { emit("hostState", hostState()) } }
                launch { RemoteControlManager.startedAt.collect { emit("hostState", hostState()) } }
                // The UI turns its subscriptions and timers off in the
                // background (mobile-ffi sessions.rs disconnects the streams).
                AppLifecycleObserver.isForeground.collect { foreground -> emit("foreground", foreground) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportError(e.message ?: e.toString())
            }
        }
    }

    /** Former EventChannel onCancel. */
    fun cancel() {
        sink = null
        streamJob?.cancel()
        logsVisible.value = false
        if (::streams.isInitialized) CommandSessions.setTrafficVisible(false)
        if (::dashboardDetails.isInitialized) {
            dashboardDetails.setMemoryVisible(false)
            dashboardDetails.setLanVisible(false)
        }
        if (::streams.isInitialized && connectionsVisible) {
            connectionsVisible = false
            CommandSessions.setConnectionsVisible(false)
        }
        if (::streams.isInitialized && connectionStatsVisible) {
            connectionStatsVisible = false
            CommandSessions.setConnectionsVisible(false)
        }
    }

    private fun required(value: String?, key: String): String = value ?: error("Missing argument: " + key)

    private fun required(value: Long?, key: String = "id"): Long = value ?: error("Missing argument: " + key)

    // -----------------------------------------------------------------------
    // Commands. Arguments the UI may leave out stay nullable and fail with a
    // "Missing argument" message.
    // -----------------------------------------------------------------------

    /** fk-box's own release check (client-core updates.rs; requests through libbox). */
    suspend fun checkUpdates() = withContext(Dispatchers.IO) {
        ServiceSettings.plain(CoreStore.callKernel("updates.check") {
            put("build", buildJsonObject {
                put("applicationId", BuildConfig.APPLICATION_ID)
                put("versionCode", BuildConfig.VERSION_CODE.toLong())
                put("abis", JsonArray(Build.SUPPORTED_ABIS.map { JsonPrimitive(it) }))
            })
        })
    }

    suspend fun geoList() = GeoResources.list(activity)

    suspend fun geoUpdate(name: String): Boolean = GeoResources.update(name)

    suspend fun geoSetUrl(name: String, url: String) = operations.withLock {
        GeoResources.setUrl(name, url)
        publishSettings()
        GeoResources.list(activity)
    }

    suspend fun egress(id: String) = dashboardDetails.egress(id)

    suspend fun openProbe(id: String) { dashboardDetails.openProbe(id) }

    suspend fun probeGet(id: String, url: String, timeoutMillis: Long) = dashboardDetails.query(id, url, timeoutMillis)

    suspend fun serviceCheck(id: String, name: String, timeoutMillis: Long) = dashboardDetails.serviceCheck(id, name, timeoutMillis)

    fun closeProbe(id: String) { dashboardDetails.closeProbe(id) }

    suspend fun releaseMemory() = dashboardDetails.releaseMemory()

    fun setMemoryVisible(visible: Boolean) { dashboardDetails.setMemoryVisible(visible) }

    fun setLanVisible(visible: Boolean) { dashboardDetails.setLanVisible(visible) }

    suspend fun refreshLan() = dashboardDetails.refreshNetwork(force = true)

    suspend fun setLanProxy(profileId: Long?, revision: String?, enabled: Boolean, port: Int?) = localProfiles {
        require(port == null || port in 1..65535) { "Invalid proxy port" }
        profiles.setLanProxy(required(profileId, "profileId"), required(revision, "revision"), enabled, port ?: 0)
        publishProfiles()
        dashboardDetails.refreshNetwork()
    }

    /**
     * The first snapshot behind the splash. The UI reads the profile list and
     * its preferences from the client store itself once this answers (the
     * store is open: initialization touched the settings); the profiles' cores
     * are resolved into it first. The LAN scan is left to the intranet card's
     * own visibility polling.
     */
    suspend fun ready(): Map<String, Any?> = coroutineScope {
        val resolved = async(Dispatchers.IO) { profiles.resolveCores() }
        val settingsValue = withContext(Dispatchers.IO) {
            Settings.dataStore.flush()
            platformSettings()
        }
        resolved.await()
        mapOf(
            "version" to BuildConfig.VERSION_NAME, "coreVersion" to Libbox.version(),
            "xrayVersion" to Libbox.xrayVersion(), "mihomoVersion" to Libbox.mihomoVersion(),
            "systemAccentColor" to systemAccentColor(),
            "hostState" to hostState(),
            "settings" to settingsValue,
            "diagnostics" to toolBridge.diagnostics(),
            "intent" to activity.takeIntent(),
        )
    }

    fun startService() {
        require(!CommandTarget.isRemote) { "Exit remote control before starting the local service" }
        activity.startService()
    }

    fun stopService() {
        require(!CommandTarget.isRemote) { "Exit remote control before stopping the local service" }
        activity.stopService()
    }

    suspend fun reloadService(restart: Boolean = false) {
        operations.withLock {
            if (CommandTarget.isRemote) withContext(Dispatchers.IO) { CommandTarget.standaloneClient().serviceReload() }
            else activity.applyProfile(restart)
        }
    }

    suspend fun readProfile(id: Long?) = withContext(Dispatchers.IO) { profiles.read(required(id)) }

    suspend fun profileRevision(id: Long?) = withContext(Dispatchers.IO) { profiles.revisionOf(required(id)) }

    suspend fun profileConfig(id: Long?) = withContext(Dispatchers.IO) { profiles.config(required(id)) }

    suspend fun profileStats(id: Long?) = withContext(Dispatchers.IO) { profiles.stats(required(id)) }

    suspend fun writeProfile(id: Long?, content: String?, revision: String?) = localProfiles {
        val saved = profiles.writeContent(required(id), required(content, "content"), required(revision, "revision"))
        publishProfiles()
        saved
    }

    suspend fun selectProfile(id: Long?) {
        localProfiles {
            profiles.select(required(id)) { activity.serviceStatus.value }
            publishProfiles()
        }
    }

    /** Saves the profile form ([profile] holds the form's fields and, when editing, its "id"). */
    suspend fun saveProfile(profile: Map<String, Any?>) = localProfiles {
        val saved = profiles.save(profile)
        publishProfiles()
        saved
    }

    /** Saves each config of a split JSON array as its own profile. */
    suspend fun saveProfiles(entries: List<*>, core: String) = localProfiles {
        val saved = profiles.saveAll(mapOf("entries" to entries, "core" to core))
        publishProfiles()
        saved
    }

    suspend fun decodeProfileText(content: String, name: String = "Imported profile") =
        withContext(Dispatchers.IO) { profiles.decodeText(content, name) }

    suspend fun updateProfile(id: Long?) {
        localProfiles {
            profiles.update(required(id))
            publishProfiles()
        }
    }

    suspend fun deleteProfile(id: Long?) {
        localProfiles {
            profiles.delete(required(id))
            publishProfiles()
        }
    }

    /** Null when the user dismissed the file picker. */
    suspend fun importProfileFile(): Map<String, Any?>? {
        val uri = activity.pickDocument() ?: return null
        return withContext(Dispatchers.IO) {
            val (name, bytes) = activity.readDocument(uri)
            profiles.decode(bytes, name)
        }
    }

    suspend fun scanProfile(session: String, text: String) = withContext(Dispatchers.Default) { qrScanner.scan(session, text) }

    fun closeProfileScan(session: String) { qrScanner.close(session) }

    suspend fun decodeProfileIntent(value: String): Map<String, Any?> {
        val uri = Uri.parse(value)
        return if (uri.scheme == "sing-box" && uri.host == "import-remote-profile") {
            val info = Libbox.parseRemoteProfileImportLink(value)
            mapOf("name" to info.name, "url" to info.url)
        } else if (uri.scheme == "http" || uri.scheme == "https") {
            BoxProfiles.validateUrl(value)
            mapOf("name" to (uri.host ?: "Profile"), "url" to value)
        } else {
            withContext(Dispatchers.IO) {
                val (name, bytes) = activity.readDocument(uri)
                profiles.decode(bytes, name)
            }
        }
    }

    fun takeIntent() = activity.takeIntent()

    suspend fun checkConfig(core: String?, content: String): Boolean = withContext(Dispatchers.IO) {
        ProfileContent.prepare(core ?: "auto", content)
        true
    }

    suspend fun formatConfig(core: String?, content: String): String = withContext(Dispatchers.IO) {
        val resolved = Libbox.resolveProfileCore(core ?: "auto", content)
        Libbox.formatCoreConfig(resolved, content).value
    }

    /** False when the user dismissed the file picker. */
    suspend fun saveDocument(name: String, content: String): Boolean {
        val uri = activity.pickDocument(name) ?: return false
        withContext(Dispatchers.IO) {
            activity.contentResolver.openOutputStream(uri, "wt")?.use { it.write(content.toByteArray()) }
                ?: error("Unable to write document")
        }
        return true
    }

    fun shareText(content: String) {
        activity.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, content)
        }, null))
    }

    suspend fun selectOutbound(groupTag: String?, tag: String?) {
        operations.withLock {
            // The UI checks the group and node against its list first
            // (client-core groups.rs); the core rejects what has gone since.
            withContext(Dispatchers.IO) { CommandTarget.standaloneClient().selectOutbound(required(groupTag, "group"), required(tag, "tag")) }
        }
    }

    suspend fun urlTest(tagArgument: String?) {
        withContext(Dispatchers.IO) {
            CommandTarget.standaloneClient().urlTest(required(tagArgument, "tag"))
        }
    }

    suspend fun setMode(mode: String) {
        withContext(Dispatchers.IO) { CommandTarget.standaloneClient().setClashMode(mode) }
    }

    /** Switches the local service's system HTTP proxy (the UI keeps the switch state, mobile-ffi runtime.rs). */
    suspend fun setSystemProxy(enabled: Boolean) {
        require(!CommandTarget.isRemote) { "System HTTP proxy is unavailable" }
        withContext(Dispatchers.IO) {
            Settings.systemProxyEnabled = enabled
            Libbox.newStandaloneCommandClient().setSystemProxyEnabled(enabled)
        }
    }

    /**
     * What the dashboard reads from a started service once (mobile-ffi
     * runtime.rs asks): its start time and system proxy status ([local]), and
     * its deprecation notes ([notes], unless turned off). A value that cannot
     * be read is left out.
     */
    suspend fun dashboardProbe(local: Boolean, notes: Boolean): Map<String, Any?> = withContext(Dispatchers.IO) {
        val result = HashMap<String, Any?>()
        if (local) {
            runCatching { result["startedAt"] = Libbox.newStandaloneCommandClient().startedAt }
            runCatching {
                val status = Libbox.newStandaloneCommandClient().systemProxyStatus
                result["systemProxy"] = mapOf("available" to status.available, "enabled" to status.enabled)
            }
        }
        if (notes && !Settings.disableDeprecatedWarnings) {
            runCatching {
                val iterator = Libbox.newStandaloneCommandClient().deprecatedNotes
                val list = ArrayList<Map<String, Any?>>()
                while (iterator.hasNext()) {
                    val note = iterator.next()
                    list.add(mapOf("message" to note.message(), "url" to note.migrationLink))
                }
                result["deprecated"] = list
            }
        }
        result
    }

    /** The service and remote session state for the stream runtime (mobile-ffi runtime.rs). */
    private fun hostState(): Map<String, Any?> = mapOf(
        "status" to activity.serviceStatus.value.name,
        "selectedProfileId" to Settings.selectedProfile,
        "remote" to RemoteControlManager.remoteServer.value?.displayName,
        "remoteId" to RemoteControlManager.remoteServer.value?.id,
        "remoteConnected" to RemoteControlManager.isConnected.value,
        "remoteStartedAt" to RemoteControlManager.startedAt.value,
    )

    fun setConnectionsVisible(visible: Boolean) {
        if (visible != connectionsVisible) {
            connectionsVisible = visible
            CommandSessions.setConnectionsVisible(visible)
        }
    }

    /** The dashboard's connection statistics card: counts only, no rows. */
    fun setConnectionStatsVisible(visible: Boolean) {
        if (visible != connectionStatsVisible) {
            connectionStatsVisible = visible
            CommandSessions.setConnectionsVisible(visible)
        }
    }

    suspend fun closeConnection(id: String?) {
        withContext(Dispatchers.IO) { CommandTarget.standaloneClient().closeConnection(required(id, "id")) }
    }

    suspend fun closeConnections() {
        withContext(Dispatchers.IO) { CommandTarget.standaloneClient().closeConnections() }
    }

    /**
     * Clears the logs through the core, whose broadcast clears the stream;
     * false when it could not be reached (the service stopped), and the UI
     * clears its own lines.
     */
    suspend fun clearLogs(): Boolean = withContext(Dispatchers.IO) {
        runCatching { CommandTarget.standaloneClient().clearLogs() }.isSuccess
    }

    fun setLogsVisible(visible: Boolean) { logsVisible.value = visible }

    /** The core's per-second status stream ("rates") while a page shows it. */
    fun setTrafficVisible(visible: Boolean) { CommandSessions.setTrafficVisible(visible) }

    /** One setting; [restart] restarts a running local service for it (allowBypass, autoRedirect, dynamicNotification). */
    suspend fun setSetting(key: String, value: Any?, restart: Boolean = false) = operations.withLock {
        saveSettings(key, value, restart)
        platformSettings()
    }

    /**
     * The UI wrote service settings to the client store itself (ones that
     * need nothing from Android first): they enter the settings cache, the
     * setup options are re-read and geo updates rescheduled when needed.
     * Returns the Android state the UI shows with them.
     */
    suspend fun serviceSettingsWritten(written: List<Any?>) = operations.withLock {
        val keys = ServiceSettings.adopt(ServiceSettings.element(written))
        withContext(Dispatchers.IO) { Application.application.reloadSetupOptions() }
        if (keys.any { it in GEO_SCHEDULE_STORE_KEYS }) UpdateGeoWork.reconfigure()
        platformSettings()
    }

    /**
     * Saves the routing rules; a running local service reloads with them and
     * the previous rules come back if the reload fails.
     */
    suspend fun saveRouting(value: Map<String, Any?>) = operations.withLock {
        val values = mapOf("routingRules" to ServiceSettings.normalizeRouting(value))
        val previous = mapOf("routingRules" to ServiceSettings.snapshot()["routingRules"])
        if (previous != values) {
            if (CommandTarget.isRemote || activity.serviceStatus.value != Status.Started) {
                patchSettings(values)
            } else {
                ServiceSettingsChange.apply(previous, values, ::patchSettings) { recover ->
                    withContext(Dispatchers.Main) { activity.applyProfile(recover = recover) }
                }
            }
        }
        platformSettings()
    }

    /**
     * What the selected profile would start with under [draft] (unsaved rules):
     * the core that runs it, the compiled content and the warnings.
     */
    suspend fun routingPreview(draft: Map<String, Any?>): Map<String, Any?> {
        require(!CommandTarget.isRemote) { "Routing rules apply to the local service" }
        return withContext(Dispatchers.IO) {
            val id = Settings.selectedProfile
            require(id != -1L) { "No profile selected" }
            val profile = ProfileManager.get(id) ?: error("Profile no longer exists")
            val content = java.io.File(profile.typed.path).readText()
            val core = ProfileContent.coreOf(profile, content)
            val result = Libbox.applyCoreRouting(core, content, ServiceSettings.routingCoreJson(core, draft))
            mapOf(
                "profile" to profile.name, "core" to core, "content" to result.content,
                "applied" to result.applied, "active" to result.active,
                "warnings" to result.warnings.lines().filter { it.isNotBlank() },
            )
        }
    }

    /**
     * The names a "node or group" rule of [core] can target in the selected
     * profile (libbox CoreRoutingTargets: mihomo proxy-provider nodes are
     * left out); empty when the selected profile runs on another core.
     */
    suspend fun routingTargets(core: String): List<String> {
        if (CommandTarget.isRemote) return emptyList()
        return withContext(Dispatchers.IO) {
            val id = Settings.selectedProfile
            if (id == -1L) return@withContext emptyList()
            val profile = ProfileManager.get(id) ?: return@withContext emptyList()
            val content = java.io.File(profile.typed.path).readText()
            if (ProfileContent.coreOf(profile, content) != core) return@withContext emptyList()
            Libbox.coreRoutingTargets(core, content).lines().filter { it.isNotBlank() }
        }
    }

    suspend fun updateSettings(values: Map<String, Any?>) = operations.withLock {
        patchSettings(values)
        platformSettings()
    }

    suspend fun listApps(refresh: Boolean, system: Boolean, nonInternet: Boolean): Any? {
        val result = withContext(Dispatchers.IO) {
            apps.list(
                key = "${PackageQueryManager.strategy}:${activity.resources.configuration.locales.toLanguageTags()}",
                refresh = refresh,
                showSystem = system,
                showOffline = nonInternet,
            )
        }
        // Package broadcasts may have updated managed selections while the page was closed.
        publishSettings()
        return result
    }

    /**
     * An app list closed. The package snapshot goes, then the runtime is asked
     * to collect what the list loaded: each label and icon opened the app's
     * Resources (its APK's resource table mapped into this process) and icon
     * bitmaps keep native pixel memory until their Java objects are collected.
     * Going to the background frees these through the system's own GC and
     * trim; this does the same as soon as the list closes.
     */
    @Suppress("DEPRECATION")
    /** Display names of [packages] (routing rules list their apps by name); unknown packages are left out. */
    suspend fun appLabels(packages: List<String>): Map<String, String> = withContext(Dispatchers.IO) {
        val packageManager = activity.packageManager
        packages.take(1000).mapNotNull { name ->
            runCatching { name to packageManager.getApplicationInfo(name, 0).loadLabel(packageManager).toString() }.getOrNull()
        }.toMap()
    }

    suspend fun releaseApps() {
        apps.clear()
        withContext(Dispatchers.Default) {
            Runtime.getRuntime().gc()
            System.runFinalization()
            Runtime.getRuntime().gc()
        }
    }

    fun requestAppsPermission() { PackageQueryManager.requestShizukuPermission() }

    suspend fun rescanApps() = operations.withLock {
        if (Settings.perAppProxyManagedMode) {
            Settings.perAppProxyManagedList = PerAppProxyScanner.scanAllChinaApps()
            publishSettings()
        }
        platformSettings()
    }

    fun openBatterySettings() {
        activity.startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + activity.packageName)))
    }

    fun openUrl(url: String?) {
        val uri = Uri.parse(required(url, "url"))
        require(uri.scheme in listOf("http", "https")) { "Unsupported URL" }
        activity.startActivity(Intent(Intent.ACTION_VIEW, uri))
    }

    suspend fun deleteRemoteServer(id: Long?) {
        withContext(Dispatchers.IO) {
            require(RemoteControlManager.remoteServer.value?.id != required(id)) { "Disconnect from this server first" }
            RemoteServerManager.get(required(id))?.let { RemoteServerManager.delete(it) }
        }
    }

    suspend fun connectRemoteServer(id: Long?) {
        val server = withContext(Dispatchers.IO) { RemoteServerManager.get(required(id)) } ?: error("Server no longer exists")
        RemoteControlManager.enterRemoteControl(server)
    }

    fun disconnectRemoteServer() { RemoteControlManager.exitRemoteControl() }

    /**
     * Creates a backup and writes it to a document the user picks. The backup
     * (up to 64 MiB) stays on this side: it never crosses JNI to the UI.
     * False when the user dismissed the file picker.
     */
    suspend fun exportBackup(name: String?): Boolean {
        val bytes = operations.withLock { withContext(Dispatchers.IO) { backups.create().toByteArray(Charsets.UTF_8) } }
        val uri = activity.pickDocument(required(name, "name")) ?: return false
        withContext(Dispatchers.IO) {
            activity.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: error("Unable to write document")
        }
        return true
    }

    /**
     * Reads and validates a backup from a document the user picks, keeps it
     * here and returns its summary and token for [restoreBackup]. Null when
     * the user dismissed the file picker.
     */
    suspend fun readBackup(scope: String?): Any? {
        val uri = activity.pickDocument() ?: return null
        return withContext(Dispatchers.IO) {
            backups.read(activity.readDocument(uri, BoxBackups.MAX_BYTES).second.toString(Charsets.UTF_8), scope ?: "all")
        }
    }

    suspend fun restoreBackup(token: String?, scope: String?, strategy: String?) = localProfiles {
        backups.restore(required(token, "token"), scope ?: "all", strategy ?: "Compatible")
    }

    suspend fun webdavAccount() = withContext(Dispatchers.IO) { backups.webdavAccount() }

    /** Saves the WebDAV account, or removes it when [value] is null. */
    suspend fun setWebdavAccount(value: Map<String, Any?>?) = operations.withLock {
        withContext(Dispatchers.IO) { backups.setWebdavAccount(value) }
    }

    suspend fun checkWebdav() = withContext(Dispatchers.IO) { backups.checkWebdav() }

    suspend fun uploadWebdavBackup(): Boolean = operations.withLock {
        withContext(Dispatchers.IO) { backups.uploadWebdav() }
    }

    suspend fun readWebdavBackup(scope: String?): Any? = withContext(Dispatchers.IO) { backups.readWebdav(scope ?: "all") }

    // Terminal settings that change files or several settings at once run
    // under the same lock as the other setting writes.
    suspend fun setTerminalSetting(key: String?, value: Any?) = operations.withLock { toolBridge.terminalSettings.set(key, value) }

    suspend fun importTerminalFont() = operations.withLock { toolBridge.terminalSettings.importFont() }

    /** Terminal settings the UI wrote to the client store itself: into the settings cache. */
    fun terminalSettingsWritten(written: List<Any?>): Boolean {
        ServiceSettings.adopt(ServiceSettings.element(written))
        return true
    }

    suspend fun deleteTerminalFont(path: String?) = operations.withLock { toolBridge.terminalSettings.deleteFont(path) }

    suspend fun terminalSettings() = withContext(Dispatchers.IO) {
        Settings.dataStore.flush()
        toolBridge.terminalSettings.snapshot()
    }

    suspend fun terminalThemes(dark: Boolean) = toolBridge.terminalSettings.themes(dark)

    suspend fun terminalFonts() = toolBridge.terminalSettings.fonts()

    // HTTP capture. Settings changes also refresh the profiles (the capture
    // configuration is part of the local profile set); start and stop are
    // serialized with the other service operations.
    suspend fun configureCapture(revision: String?, config: Map<String, Any?>?) = localProfiles {
        val saved = capture.configure(revision, config)
        publishProfiles()
        saved
    }

    suspend fun startCapture() = operations.withLock { capture.start() }

    suspend fun stopCapture() = operations.withLock { capture.stop() }

    private suspend fun <T> localProfiles(block: suspend () -> T): T {
        require(!CommandTarget.isRemote) { "Local profiles cannot modify a remote sing-box session" }
        return operations.withLock { withContext(Dispatchers.IO) { block() } }
    }

    /** The profiles changed in the client store: their cores are resolved, then the UI re-reads the list. */
    private suspend fun publishProfiles() {
        withContext(Dispatchers.IO) { profiles.resolveCores() }
        emit("profiles", null)
        emit("hostState", hostState())
    }

    /**
     * Android state shown with the service settings. The settings themselves
     * are client-core's snapshot ([ServiceSettings.snapshot]), which the UI
     * reads from the client store and shows with these entries on top.
     */
    private fun platformSettings(): Map<String, Any?> = mapOf(
        "batteryExempt" to (activity.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(activity.packageName) == true),
    )

    /** The settings changed: pending cache writes reach the store before the UI re-reads it. */
    private suspend fun publishSettings() {
        withContext(Dispatchers.IO) { Settings.dataStore.flush() }
        emit("settings", platformSettings())
    }

    private suspend fun saveSettings(key: String, value: Any?, restart: Boolean) {
        val values = mapOf(key to value)
        if (!restart) {
            patchSettings(values)
            return
        }
        require(key in setOf("allowBypass", "autoRedirect", "dynamicNotification")) { "Invalid automatic service restart setting" }
        require(CommandTarget.isRemote || activity.serviceStatus.value in setOf(Status.Started, Status.Stopped)) {
            "Wait for the service to finish starting or stopping"
        }
        val previous = mapOf(key to withContext(Dispatchers.IO) { ServiceSettings.snapshot()[key] })
        if (previous == values) return
        if (CommandTarget.isRemote || activity.serviceStatus.value != Status.Started) {
            patchSettings(values)
            return
        }
        ServiceSettingsChange.apply(previous, values, ::patchSettings) { recover ->
            if (!CommandTarget.isRemote) activity.applyProfile(restart = true, recover = recover)
        }
    }

    /**
     * Writes service settings through client-core ([ServiceSettings]): it
     * checks them and says what Android does first (switch the package query
     * mode, scan the apps for the managed list); the write itself is one
     * step, after which the setup options are re-read and geo updates
     * rescheduled. Managed mode is never on without its first scan's list.
     */
    private suspend fun patchSettings(values: Map<String, Any?>) {
        withContext(Dispatchers.IO) { Settings.dataStore.flush() }
        val plan = withContext(Dispatchers.IO) { ServiceSettings.plan(values) }
        val oldQueryMode = Settings.perAppProxyPackageQueryMode
        val restoreQueryMode = { if (plan.queryMode != null) PackageQueryManager.setQueryMode(oldQueryMode) }
        try {
            plan.queryMode?.let(PackageQueryManager::setQueryMode)
            if (plan.scan) {
                Settings.perAppProxyManagedList = PerAppProxyScanner.scanAllChinaApps()
            }
            withContext(Dispatchers.IO) {
                Settings.dataStore.flush()
                ServiceSettings.patch(values)
            }
        } catch (error: Exception) {
            restoreQueryMode()
            throw error
        }
        withContext(Dispatchers.IO) { Application.application.reloadSetupOptions() }
        if (plan.rescheduleGeo) UpdateGeoWork.reconfigure()
        publishSettings()
    }

    fun reportError(message: String) {
        scope.launch {
            if (sink == null) {
                if (queuedErrors.size >= 10) queuedErrors.removeFirst()
                queuedErrors.addLast(message)
            } else emit("error", message)
        }
    }

    internal fun submitBrowserResult(endpoint: String, challengeID: String, value: io.nekohasekai.sfa.backend.tools.OpenConnectBrowserResultData) {
        scope.launch {
            try {
                require(toolBridge.status.openConnectEndpoint(endpoint)?.authChallenge?.id == challengeID) { "Authentication request expired" }
                // Keep this response on the session whose challenge was checked.
                val client = CommandTarget.standaloneClient()
                withContext(Dispatchers.IO) {
                    val browserResult = Libbox.newOpenConnectBrowserResult(value.finalURL)
                    value.cookies.forEach { browserResult.addCookie(it.name, it.value) }
                    client.submitOpenConnectAuthResponse(endpoint, challengeID, Libbox.newOpenConnectBrowserAuthResponse(browserResult))
                }
                emit("browserComplete", mapOf("id" to challengeID, "success" to true))
            } catch (e: Exception) { reportError(e.message ?: e.toString()) }
        }
    }

    fun notifyIntent() = emit("intentAvailable", true)

    fun systemAccentColor(): Long = (if (android.os.Build.VERSION.SDK_INT >= 31)
        activity.getColor(android.R.color.system_accent1_500) else 0xff007aff.toInt()).toLong() and 0xffffffffL

    fun refreshSystemAppearance() = emit("systemAccent", systemAccentColor())

    internal fun emit(type: String, value: Any?) {
        scope.launch(Dispatchers.Main.immediate) {
            // Never routed to the scope's handler, which reports through emit.
            runCatching { sink?.success(mapOf("type" to type, "value" to value)) }
                .onFailure { android.util.Log.e("BoxBridge", "emit $type failed", it) }
        }
    }

    override fun close() {
        qrScanner.close()
        streamJob?.cancel()
        sink = null
        if (::streams.isInitialized) {
            streams.close()
            CommandSessions.setTrafficVisible(false)
            ProfileManager.unregisterCallback(profileCallback)
            PackageQueryManager.unregisterListeners()
            if (connectionsVisible) CommandSessions.setConnectionsVisible(false)
            if (connectionStatsVisible) CommandSessions.setConnectionsVisible(false)
            CommandSessions.setLogsVisible(false)
            toolBridge.close()
            dashboardDetails.close()
        }
    }
}

/** Store keys of the geo update schedule (client-core geo::AUTO_UPDATE_KEY, UPDATE_INTERVAL_KEY). */
private val GEO_SCHEDULE_STORE_KEYS = setOf("geo_auto_update", "geo_update_interval")

internal fun jsonObject(value: JSONObject): Map<String, Any?> = value.keys().asSequence().associateWith { jsonValue(value.get(it)) }
private fun jsonValue(value: Any?): Any? = when (value) {
    JSONObject.NULL -> null
    is JSONObject -> jsonObject(value)
    is JSONArray -> (0 until value.length()).map { jsonValue(value.get(it)) }
    else -> value
}
