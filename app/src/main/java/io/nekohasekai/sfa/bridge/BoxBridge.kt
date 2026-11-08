package io.nekohasekai.sfa.bridge

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.backend.base.GlobalEventBus
import io.nekohasekai.sfa.backend.base.UiEvent
import io.nekohasekai.sfa.backend.connections.ConnectionsViewModel
import io.nekohasekai.sfa.backend.dashboard.DashboardViewModel
import io.nekohasekai.sfa.backend.dashboard.groups.GroupsViewModel
import io.nekohasekai.sfa.backend.log.LogViewModel
import io.nekohasekai.sfa.bg.GeoPolicy
import io.nekohasekai.sfa.bg.GeoResources
import io.nekohasekai.sfa.bg.UpdateGeoWork
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.ProfileContent
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.RemoteServer
import io.nekohasekai.sfa.database.RemoteServerManager
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.utils.AppLifecycleObserver
import io.nekohasekai.sfa.utils.CommandClient
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable

/**
 * Native facade for the Compose UI: typed commands run through [call] once the
 * bridge is initialized, and state is streamed as events ([listen]). Every
 * backend implementation behind them is reused as it was under Flutter.
 */
class BoxBridge(private val activity: BoxActivity) : Closeable {
    fun interface EventSink {
        fun success(event: Map<String, Any?>)
    }

    private val scope = activity.lifecycleScope
    private var sink: EventSink? = null
    private var streamJob: Job? = null
    private val queuedErrors = ArrayDeque<String>()
    private lateinit var dashboard: DashboardViewModel
    private lateinit var groups: GroupsViewModel
    private lateinit var connections: ConnectionsViewModel
    private lateinit var logs: LogViewModel
    internal lateinit var toolBridge: BoxTools
    private lateinit var dashboardDetails: BoxDashboard
    private var connectionsVisible = false
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
    private val preferences = activity.getSharedPreferences("flclash_ui", 0)
    private val webdav = BoxWebDAV(activity)
    private val backups = BoxBackups(activity, ::settings, ::patchSettings,
        { restart, recover -> withContext(Dispatchers.Main) { activity.applyProfile(restart, recover) } }, ::publishProfiles, ::emit, webdav)
    private val rates = object : CommandClient.Handler {
        override fun updateStatus(status: StatusMessage) {
            if (::dashboardDetails.isInitialized) dashboardDetails.remoteMemory = status.memory
            // Traffic totals change every second, so they ride with the rates
            // instead of the dashboard state that most pages read.
            emit("rates", mapOf("upload" to status.uplink, "download" to status.downlink, "memoryBytes" to status.memory,
                "uploadTotal" to Libbox.formatBytes(status.uplinkTotal), "downloadTotal" to Libbox.formatBytes(status.downlinkTotal)))
        }
    }
    private val profileCallback: () -> Unit = { scope.launch { operations.withLock { publishProfiles() } } }
    private val initialize = scope.async {
        withContext(Dispatchers.IO) {
            Settings.dataStore.initialize()
            Application.application.setupLibbox()
        }
        val provider = ViewModelProvider(activity)
        dashboard = provider[DashboardViewModel::class.java]
        groups = provider[GroupsViewModel::class.java]
        connections = provider[ConnectionsViewModel::class.java]
        logs = provider[LogViewModel::class.java]
        toolBridge = BoxTools(activity, ::emit)
        dashboardDetails = BoxDashboard(activity, ::emit)
        dashboard.commandClient.addHandler(rates)
        ProfileManager.registerCallback(profileCallback)
        PackageQueryManager.registerListeners()
        RemoteControlManager.restore()
        scope.launch {
            activity.serviceStatus.collect {
                dashboard.updateServiceStatus(it)
                groups.updateServiceStatus(it)
                connections.updateServiceStatus(it)
                logs.updateServiceStatus(it)
                if (it == Status.Stopped) emit("rates", mapOf("upload" to 0L, "download" to 0L))
            }
        }
        // The Log client follows the Logs page instead of running on every page.
        scope.launch { logsVisible.collect { logs.setVisible(it) } }
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
                coroutineScope {
                    launch { dashboard.uiState.collect { emit("dashboard", it.toWire()) } }
                    launch { groups.uiState.collect { emit("groups", it.groups.map { group -> group.toWire() }) } }
                    launch { connections.uiState.collect { state ->
                        if (connectionsVisible) emit("connections", withContext(Dispatchers.Default) { state.allConnections.map { it.toWire() } })
                    } }
                    launch {
                        val stream = LogStream()
                        combine(logs.uiState, logsVisible, AppLifecycleObserver.isForeground) { state, visible, foreground ->
                            state.takeIf { visible && foreground }
                        }.collect { state ->
                            if (state == null) stream.reset()
                            else {
                                val update = withContext(Dispatchers.Default) { stream.update(state) }
                                if (update != null) emit("logs", update)
                            }
                        }
                    }
                    launch { RemoteControlManager.remoteServer.collect { emit("dashboard", dashboard.uiState.value.toWire()) } }
                    launch { RemoteControlManager.isConnected.collect { emit("dashboard", dashboard.uiState.value.toWire()) } }
                }
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
        if (::dashboardDetails.isInitialized) {
            dashboardDetails.setMemoryVisible(false)
            dashboardDetails.setLanVisible(false)
        }
        if (::connections.isInitialized && connectionsVisible) {
            connectionsVisible = false
            connections.setVisible(false)
        }
    }

    private fun required(value: String?, key: String): String = value ?: error("Missing argument: " + key)

    private fun required(value: Long?, key: String = "id"): Long = value ?: error("Missing argument: " + key)

    // -----------------------------------------------------------------------
    // Commands. Each was a MethodChannel method of the Flutter UI; arguments
    // the UI may leave out stay nullable and fail with the former messages.
    // -----------------------------------------------------------------------

    suspend fun checkUpdates() = withContext(Dispatchers.IO) { BoxUpdates.check() }

    suspend fun geoList() = GeoResources.list(activity)

    suspend fun geoUpdate(name: String): Boolean = GeoResources.update(name)

    suspend fun geoSetUrl(name: String, url: String) = operations.withLock {
        GeoResources.setUrl(name, url)
        withContext(Dispatchers.IO) { Settings.dataStore.flush() }
        emit("settings", settings())
        GeoResources.list(activity)
    }

    suspend fun egress(id: String) = dashboardDetails.egress(id)

    suspend fun openProbe(id: String) { dashboardDetails.openProbe(id) }

    suspend fun probeGet(id: String, url: String, timeoutMillis: Long) = dashboardDetails.query(id, url, timeoutMillis)

    fun closeProbe(id: String) { dashboardDetails.closeProbe(id) }

    fun readQualityCache(ip: String) = dashboardDetails.readQualityCache(ip)

    suspend fun writeQualityCache(ip: String, value: Map<String, Any?>) { dashboardDetails.writeQualityCache(ip, value) }

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

    suspend fun ready(): Map<String, Any?> = mapOf(
        "version" to BuildConfig.VERSION_NAME, "coreVersion" to Libbox.version(),
        "xrayVersion" to Libbox.xrayVersion(), "mihomoVersion" to Libbox.mihomoVersion(),
        "systemAccentColor" to systemAccentColor(),
        "dashboard" to dashboard.uiState.value.toWire(),
        "profiles" to withContext(Dispatchers.IO) { profiles.list() },
        "settings" to settings(), "preferences" to readPreferences(),
        "lan" to dashboardDetails.refreshNetwork(),
        "tools" to toolBridge.snapshot(), "diagnostics" to toolBridge.diagnostics(),
        "intent" to activity.takeIntent(),
    )

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

    suspend fun reorderProfiles(ids: List<Long>) {
        localProfiles {
            profiles.reorder(ids)
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

    suspend fun prepareConfig(core: String?, content: String): Map<String, Any?> = withContext(Dispatchers.IO) {
        val prepared = ProfileContent.prepare(core ?: "auto", content)
        mapOf("content" to prepared.content, "core" to prepared.core)
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
            val group = groups.uiState.value.groups.firstOrNull { it.tag == required(groupTag, "group") } ?: error("Group no longer exists")
            require(group.selectable) { "This group is selected automatically by sing-box" }
            require(group.items.any { it.tag == required(tag, "tag") }) { "Node no longer exists" }
            withContext(Dispatchers.IO) { CommandTarget.standaloneClient().selectOutbound(group.tag, required(tag, "tag")) }
        }
    }

    suspend fun urlTest(tagArgument: String?) {
        withContext(Dispatchers.IO) {
            val tag = required(tagArgument, "tag")
            require(groups.uiState.value.groups.any { it.tag == tag || it.items.any { item -> item.tag == tag } }) { "Node no longer exists" }
            CommandTarget.standaloneClient().urlTest(tag)
        }
    }

    suspend fun setMode(mode: String) {
        require(dashboard.uiState.value.clashModes.contains(mode)) { "This routing mode is not provided by the configuration" }
        withContext(Dispatchers.IO) { CommandTarget.standaloneClient().setClashMode(mode) }
    }

    fun setSystemProxy(enabled: Boolean) {
        require(!CommandTarget.isRemote && dashboard.uiState.value.systemProxyVisible) { "System HTTP proxy is unavailable" }
        dashboard.toggleSystemProxy(enabled)
    }

    fun setConnectionsVisible(visible: Boolean) {
        if (visible != connectionsVisible) {
            connectionsVisible = visible
            connections.setVisible(visible)
        }
        if (visible) emit("connections", connections.uiState.value.allConnections.map { it.toWire() })
    }

    suspend fun closeConnection(id: String?) {
        withContext(Dispatchers.IO) { CommandTarget.standaloneClient().closeConnection(required(id, "id")) }
    }

    suspend fun closeConnections() {
        withContext(Dispatchers.IO) { CommandTarget.standaloneClient().closeConnections() }
    }

    fun clearLogs() { logs.requestClearLogs() }

    fun setLogsVisible(visible: Boolean) { logsVisible.value = visible }

    suspend fun setPreferences(values: Map<String, Any?>) = operations.withLock {
        val value = BoxPreferences.validate(values)
        val old = JSONObject(preferences.getString("value", "{}")!!).apply { remove("locale") }
        value.forEach { (key, item) -> old.put(key.toString(), JSONObject.wrap(item)) }
        withContext(Dispatchers.IO) { check(preferences.edit().putString("value", old.toString()).commit()) { "Unable to save preferences" } }
        readPreferences()
    }

    /** One setting; [restart] restarts a running local service for it (allowBypass, autoRedirect, dynamicNotification). */
    suspend fun setSetting(key: String, value: Any?, restart: Boolean = false) = operations.withLock {
        saveSettings(key, value, restart)
        settings()
    }

    suspend fun updateSettings(values: Map<String, Any?>) = operations.withLock {
        patchSettings(values)
        settings()
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
        emit("settings", settings())
        return result
    }

    suspend fun appIcon(packageName: String): ByteArray = withContext(Dispatchers.IO) {
        val drawable = activity.packageManager.getApplicationIcon(packageName)
        val size = (48 * activity.resources.displayMetrics.density).toInt().coerceIn(48, 192)
        val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            drawable.setBounds(0, 0, size, size)
            drawable.draw(android.graphics.Canvas(bitmap))
            java.io.ByteArrayOutputStream().use { output ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
                output.toByteArray()
            }
        } finally { bitmap.recycle() }
    }

    fun requestAppsPermission() { PackageQueryManager.requestShizukuPermission() }

    suspend fun rescanApps() = operations.withLock {
        if (Settings.perAppProxyManagedMode) {
            Settings.perAppProxyManagedList = PerAppProxyScanner.scanAllChinaApps()
            withContext(Dispatchers.IO) { Settings.dataStore.flush() }
            emit("settings", settings())
        }
        settings()
    }

    fun openBatterySettings() {
        activity.startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + activity.packageName)))
    }

    fun openUrl(url: String?) {
        val uri = Uri.parse(required(url, "url"))
        require(uri.scheme in listOf("http", "https")) { "Unsupported URL" }
        activity.startActivity(Intent(Intent.ACTION_VIEW, uri))
    }

    suspend fun remoteServers() = withContext(Dispatchers.IO) {
        RemoteServerManager.list().map { mapOf("id" to it.id, "name" to it.name, "url" to it.url, "secret" to it.secret) }
    }

    /** Creates the server when [id] is null or 0, otherwise edits it; returns its ID. */
    suspend fun saveRemoteServer(id: Long?, name: String, url: String, secret: String) = withContext(Dispatchers.IO) {
        val serverId = id ?: 0L
        val server = if (serverId == 0L) RemoteServer() else RemoteServerManager.get(serverId) ?: error("Server no longer exists")
        server.name = name
        server.url = RemoteServer.validateURL(url) ?: error("Invalid server address")
        server.secret = secret
        if (serverId == 0L) RemoteServerManager.create(server) else RemoteServerManager.update(server)
        server.id
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

    suspend fun createBackup() = operations.withLock { withContext(Dispatchers.IO) { backups.create() } }

    /** Null when the user dismissed the file picker. */
    suspend fun readBackup(scope: String?): Any? {
        val uri = activity.pickDocument() ?: return null
        return withContext(Dispatchers.IO) {
            backups.read(activity.readDocument(uri, BoxBackups.MAX_BYTES).second, scope ?: "all")
        }
    }

    suspend fun restoreBackup(backup: Map<String, Any?>?, scope: String?, strategy: String?) = localProfiles {
        backups.restore(backup ?: error("Missing backup"), scope ?: "all", strategy ?: "Compatible")
    }

    fun webdavAccount() = webdav.snapshot()

    /** Saves the WebDAV account, or removes it when [value] is null. */
    suspend fun setWebdavAccount(value: Map<String, Any?>?) = operations.withLock {
        withContext(Dispatchers.IO) {
            webdav.save(value)
            webdav.snapshot()
        }
    }

    suspend fun checkWebdav() = withContext(Dispatchers.IO) { webdav.check() }

    suspend fun uploadWebdavBackup(): Boolean = operations.withLock {
        withContext(Dispatchers.IO) {
            webdav.upload(backups.encode(backups.create()))
            true
        }
    }

    suspend fun readWebdavBackup(scope: String?) = withContext(Dispatchers.IO) { backups.read(webdav.download(), scope ?: "all") }

    // Terminal settings that change files or several settings at once run
    // under the same lock as the other setting writes.
    suspend fun setTerminalSetting(key: String?, value: Any?) = operations.withLock { toolBridge.terminalSettings.set(key, value) }

    suspend fun importTerminalFont() = operations.withLock { toolBridge.terminalSettings.importFont() }

    suspend fun deleteTerminalFont(path: String?) = operations.withLock { toolBridge.terminalSettings.deleteFont(path) }

    fun terminalSettings() = toolBridge.terminalSettings.snapshot()

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

    private suspend fun publishProfiles() {
        val value = withContext(Dispatchers.IO) { profiles.list() }
        emit("profiles", value)
        if (::dashboard.isInitialized) emit("dashboard", dashboard.uiState.value.toWire())
    }

    private fun readPreferences(): Map<String, Any?> = runCatching {
        BoxPreferences.validate(jsonObject(JSONObject(preferences.getString("value", "{}")!!)))
    }.getOrDefault(emptyMap())

    private fun settings(): Map<String, Any?> = mapOf(
        "perAppEnabled" to Settings.perAppProxyEnabled, "perAppMode" to Settings.perAppProxyMode,
        "perAppList" to Settings.perAppProxyList.toList(), "perAppManaged" to Settings.perAppProxyManagedMode,
        "perAppManagedList" to Settings.perAppProxyManagedList.toList(),
        "allowBypass" to Settings.allowBypass, "autoRedirect" to Settings.autoRedirect,
        "dynamicNotification" to Settings.dynamicNotification, "disableDeprecatedWarnings" to Settings.disableDeprecatedWarnings,
        "singBoxEnabled" to Settings.singBoxEnabled, "xrayEnabled" to Settings.xrayEnabled, "mihomoEnabled" to Settings.mihomoEnabled,
        "oomKillerEnabled" to Settings.oomKillerEnabled, "oomKillerDisabled" to Settings.oomKillerDisabled,
        "oomMemoryLimitMB" to Settings.oomMemoryLimitMB, "powerReportEnabled" to Settings.powerReportEnabled,
        "queryMode" to Settings.perAppProxyPackageQueryMode,
        "geoAutoUpdate" to Settings.geoAutoUpdate, "geoUpdateInterval" to Settings.geoUpdateInterval,
        "geoUrls" to GeoPolicy.decodeUrls(Settings.geoUrls),
        "batteryExempt" to (activity.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(activity.packageName) == true),
    )

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
        val previous = mapOf(key to settings()[key])
        if (previous == values) return
        if (CommandTarget.isRemote || activity.serviceStatus.value != Status.Started) {
            patchSettings(values)
            return
        }
        ServiceSettingsChange.apply(previous, values, ::patchSettings) { recover ->
            if (!CommandTarget.isRemote) activity.applyProfile(restart = true, recover = recover)
        }
    }

    private suspend fun patchSettings(values: Map<String, Any?>) {
        BoxSettings.validate(values)
        val managed = values["perAppManaged"] as? Boolean ?: Settings.perAppProxyManagedMode
        val enabled = values["perAppEnabled"] as? Boolean ?: Settings.perAppProxyEnabled
        val oldQueryMode = Settings.perAppProxyPackageQueryMode
        val managedApps = try {
            (values["queryMode"] as? String)?.let(PackageQueryManager::setQueryMode)
            if (managed && enabled && values.keys.any { it in setOf("perAppManaged", "perAppEnabled", "queryMode") }) {
                PerAppProxyScanner.scanAllChinaApps()
            } else null
        } catch (error: Exception) {
            PackageQueryManager.setQueryMode(oldQueryMode)
            throw error
        }
        // Do not enable managed mode until its first scan has succeeded.
        if (managedApps != null) Settings.perAppProxyManagedList = managedApps
        for ((key, item) in values) {
        val args = mapOf("value" to item)
        when (key) {
            "perAppEnabled" -> Settings.perAppProxyEnabled = args["value"] == true
            "perAppMode" -> {
                val value = (args["value"] as Number).toInt()
                require(value == Settings.PER_APP_PROXY_INCLUDE || value == Settings.PER_APP_PROXY_EXCLUDE)
                Settings.perAppProxyMode = value
            }
            "perAppList" -> Settings.perAppProxyList = (args["value"] as List<*>).map { it as String }.toSet()
            "perAppManaged" -> Settings.perAppProxyManagedMode = args["value"] == true
            "allowBypass" -> Settings.allowBypass = args["value"] == true
            "autoRedirect" -> Settings.autoRedirect = args["value"] == true
            "dynamicNotification" -> Settings.dynamicNotification = args["value"] == true
            "disableDeprecatedWarnings" -> Settings.disableDeprecatedWarnings = args["value"] == true
            "singBoxEnabled", "xrayEnabled", "mihomoEnabled" -> {
                val value = args["value"] == true
                val next = mapOf(
                    "singBoxEnabled" to Settings.singBoxEnabled, "xrayEnabled" to Settings.xrayEnabled, "mihomoEnabled" to Settings.mihomoEnabled,
                ) + (key to value)
                require(next.values.any { it }) { "Keep at least one core turned on" }
                when (key) {
                    "singBoxEnabled" -> Settings.singBoxEnabled = value
                    "xrayEnabled" -> Settings.xrayEnabled = value
                    else -> Settings.mihomoEnabled = value
                }
            }
            "oomKillerEnabled" -> Settings.oomKillerEnabled = args["value"] == true
            "oomKillerDisabled" -> Settings.oomKillerDisabled = args["value"] == true
            "oomMemoryLimitMB" -> {
                val value = (args["value"] as Number).toInt()
                require(value in 10..4096) { "Memory limit must be 10–4096 MiB" }
                Settings.oomMemoryLimitMB = value
            }
            "powerReportEnabled" -> Settings.powerReportEnabled = args["value"] == true
            "geoAutoUpdate" -> Settings.geoAutoUpdate = args["value"] == true
            "geoUpdateInterval" -> Settings.geoUpdateInterval = (args["value"] as Number).toInt()
            "geoUrls" -> Settings.geoUrls = GeoPolicy.encodeUrls((args["value"] as Map<*, *>).entries.associate { it.key as String to it.value as String })
            "queryMode" -> {
                val value = args["value"] as String
                require(value in listOf(Settings.PACKAGE_QUERY_MODE_ROOT, Settings.PACKAGE_QUERY_MODE_SHIZUKU))
                PackageQueryManager.setQueryMode(value)
            }
            else -> error("Unknown setting: " + key)
        }
        }
        withContext(Dispatchers.IO) {
            Settings.dataStore.flush()
            Application.application.reloadSetupOptions()
        }
        if (values.keys.any { it == "geoAutoUpdate" || it == "geoUpdateInterval" }) UpdateGeoWork.reconfigure()
        emit("settings", settings())
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
                require(toolBridge.openConnect.endpoint(endpoint)?.authChallenge?.id == challengeID) { "Authentication request expired" }
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
        scope.launch(Dispatchers.Main.immediate) { sink?.success(mapOf("type" to type, "value" to value)) }
    }

    override fun close() {
        qrScanner.close()
        streamJob?.cancel()
        sink = null
        if (::dashboard.isInitialized) {
            dashboard.commandClient.removeHandler(rates)
            ProfileManager.unregisterCallback(profileCallback)
            PackageQueryManager.unregisterListeners()
            if (connectionsVisible) connections.setVisible(false)
            logs.setVisible(false)
            toolBridge.close()
            dashboardDetails.close()
        }
    }
}

internal fun jsonObject(value: JSONObject): Map<String, Any?> = value.keys().asSequence().associateWith { jsonValue(value.get(it)) }
private fun jsonValue(value: Any?): Any? = when (value) {
    JSONObject.NULL -> null
    is JSONObject -> jsonObject(value)
    is JSONArray -> (0 until value.length()).map { jsonValue(value.get(it)) }
    else -> value
}
