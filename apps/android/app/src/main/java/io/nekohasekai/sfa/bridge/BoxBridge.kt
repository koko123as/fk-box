package io.nekohasekai.sfa.bridge

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import androidx.lifecycle.lifecycleScope
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.backend.base.GlobalEventBus
import io.nekohasekai.sfa.backend.base.UiEvent
import io.nekohasekai.sfa.bg.BoxService
import io.nekohasekai.sfa.bg.FkHost
import io.nekohasekai.sfa.bg.GeoResources
import io.nekohasekai.sfa.bg.UpdateGeoWork
import io.nekohasekai.sfa.database.CoreStore
import io.nekohasekai.sfa.database.ProfileContent
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.RemoteServerManager
import io.nekohasekai.sfa.database.ServiceSettings
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.utils.AppLifecycleObserver
import io.nekohasekai.sfa.utils.CommandSessions
import io.nekohasekai.sfa.vendor.PackageQueryManager
import io.nekohasekai.sfa.vendor.PerAppProxyScanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
    internal val terminalSettings = BoxTerminalSettings(activity)
    private lateinit var dashboardDetails: BoxDashboard
    private val operations = Mutex()
    private val apps = AppListCache.installed(activity)
    internal val reports = BoxReports(activity)
    private val profiles = BoxProfiles { recover -> withContext(Dispatchers.Main) { activity.applyProfile(recover = recover) } }
    private val qrScanner = BoxQrScanner(profiles::decode)
    private val backups = BoxBackups(activity, ::patchSettings,
        { restart, recover -> withContext(Dispatchers.Main) { activity.applyProfile(restart, recover) } }, ::publishProfiles, ::emit)
    private val profileCallback: () -> Unit = { scope.launch { operations.withLock { publishProfiles() } } }
    private val initialize = scope.async {
        withContext(Dispatchers.IO) {
            Settings.dataStore.initialize()
        }
        dashboardDetails = BoxDashboard(activity, ::emit)
        // The kernel's state in mobile-ffi runtime.rs's stream events.
        streams = BoxStreams(scope, ::emit)
        ProfileManager.registerCallback(profileCallback)
        PackageQueryManager.registerListeners()
        scope.launch {
            activity.serviceStatus.collect { CommandSessions.setServiceStatus(it) }
        }
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
        if (::streams.isInitialized) CommandSessions.setPageVisibility(false, 0, false)
        if (::dashboardDetails.isInitialized) {
            dashboardDetails.setMemoryVisible(false)
            dashboardDetails.setLanVisible(false)
        }
    }

    // -----------------------------------------------------------------------
    // Commands. mobile-ffi bridge.rs checks them first: required arguments,
    // their defaults, and what the service or a remote session rules out.
    // -----------------------------------------------------------------------

    /** fk-box's own release check (client-core updates.rs; requests through utils/HttpRequests.kt). */
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

    suspend fun setLanProxy(profileId: Long, revision: String, enabled: Boolean, port: Int?) = localProfiles {
        profiles.setLanProxy(profileId, revision, enabled, port ?: 0)
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
            // One kernel: sing-box with the Rust brain (the Xray and mihomo cores are gone).
            "version" to BuildConfig.VERSION_NAME, "coreVersion" to BuildConfig.CORE_VERSION,
            "xrayVersion" to "", "mihomoVersion" to "",
            "systemAccentColor" to systemAccentColor(),
            "hostState" to hostState(),
            "settings" to settingsValue,
            "diagnostics" to mapOf("stun" to emptyMap<String, Any?>(), "quality" to emptyMap<String, Any?>()),
            "intent" to activity.takeIntent(),
        )
    }

    fun startService() = activity.startService()

    fun stopService() = activity.stopService()

    suspend fun reloadService(restart: Boolean = false) {
        operations.withLock {
            activity.applyProfile(restart)
        }
    }

    suspend fun readProfile(id: Long) = withContext(Dispatchers.IO) { profiles.read(id) }

    suspend fun profileRevision(id: Long) = withContext(Dispatchers.IO) { profiles.revisionOf(id) }

    suspend fun profileConfig(id: Long) = withContext(Dispatchers.IO) { profiles.config(id) }

    suspend fun profileStats(id: Long) = withContext(Dispatchers.IO) { profiles.stats(id) }

    suspend fun writeProfile(id: Long, content: String, revision: String) = localProfiles {
        val saved = profiles.writeContent(id, content, revision)
        publishProfiles()
        saved
    }

    suspend fun selectProfile(id: Long) {
        localProfiles {
            profiles.select(id) { activity.serviceStatus.value }
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

    suspend fun updateProfile(id: Long) {
        localProfiles {
            profiles.update(id)
            publishProfiles()
        }
    }

    suspend fun deleteProfile(id: Long) {
        localProfiles {
            profiles.delete(id)
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

    /** A profile link or document; mobile-ffi bridge.rs answers web links itself and says which [kind] this is. */
    suspend fun decodeProfileIntent(value: String, kind: String): Map<String, Any?> = withContext(Dispatchers.IO) {
        if (kind == "remoteLink") {
            parseRemoteProfileLink(value)
        } else {
            val (name, bytes) = activity.readDocument(Uri.parse(value))
            profiles.decode(bytes, name)
        }
    }

    fun takeIntent() = activity.takeIntent()

    suspend fun checkConfig(core: String, content: String): Boolean = withContext(Dispatchers.IO) {
        ProfileContent.prepare(core, content)
        true
    }

    /** No core formats configurations on the single-core path: the content is kept as written. */
    @Suppress("UNUSED_PARAMETER")
    suspend fun formatConfig(core: String, content: String): String = content

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

    suspend fun selectOutbound(groupTag: String, tag: String) {
        operations.withLock {
            // The UI checks the group and node against its list first
            // (client-core groups.rs); the brain rejects what has gone since
            // and refuses automatic groups.
            withContext(Dispatchers.IO) { FkHost.selectGroup(groupTag, tag) }
        }
    }

    suspend fun urlTest(tag: String) {
        // fkb_brain_group_test blocks until the test finishes: never on the main thread.
        withContext(Dispatchers.IO) { FkHost.testGroup(tag) }
    }

    suspend fun setMode(mode: String) {
        error("Mode switching is not available in the single-core build")
    }

    /** Switches the local service's system HTTP proxy (the UI keeps the switch state, mobile-ffi runtime.rs). */
    suspend fun setSystemProxy(enabled: Boolean) {
        // Takes effect when the VPN interface is next built (VpnRouting).
        withContext(Dispatchers.IO) {
            Settings.systemProxyEnabled = enabled
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
        if (local && BoxService.startedAt > 0) {
            result["startedAt"] = BoxService.startedAt
        }
        if (notes && !Settings.disableDeprecatedWarnings) {
            result["deprecated"] = emptyList<Map<String, Any?>>()
        }
        result
    }

    /** The service and remote session state for the stream runtime (mobile-ffi runtime.rs). */
    private fun hostState(): Map<String, Any?> = mapOf(
        "status" to activity.serviceStatus.value.name,
        "selectedProfileId" to Settings.selectedProfile,
        "remote" to null,
        "remoteId" to null,
        "remoteConnected" to false,
        "remoteStartedAt" to 0L,
    )

    /**
     * Which pages keep the traffic, connection and log streams open
     * (mobile-ffi bridge.rs combines the pages and sends each change).
     */
    fun pageVisibility(traffic: Boolean, connections: Int, logs: Boolean) {
        CommandSessions.setPageVisibility(traffic, connections, logs)
    }

    /** The per-connection list was the Clash API's; the single-core kernel has none to close. */
    @Suppress("UNUSED_PARAMETER")
    suspend fun closeConnection(id: String) {
        error("Closing connections is not available in the single-core build")
    }

    suspend fun closeConnections() {
        error("Closing connections is not available in the single-core build")
    }

    /**
     * Clears the kernel's log ring; false when the kernel is not loaded, and
     * the UI clears its own lines.
     */
    suspend fun clearLogs(): Boolean = withContext(Dispatchers.IO) {
        if (!FkHost.loaded) return@withContext false
        FkHost.clearLogs()
        true
    }

    /** One setting; [restartService] (decided by mobile-ffi bridge.rs) restarts the running local service for it. */
    suspend fun setSetting(key: String, value: Any?, restartService: Boolean) = operations.withLock {
        saveRestarting(mapOf(key to value), restartService)
        platformSettings()
    }

    /**
     * The UI wrote service settings to the client store itself (ones that
     * need nothing from Android first): they enter the settings cache, the
     * setup options are re-read and geo updates rescheduled when needed.
     * Returns the Android state the UI shows with them.
     */
    suspend fun serviceSettingsWritten(written: List<Any?>, rescheduleGeo: Boolean) = operations.withLock {
        ServiceSettings.adopt(ServiceSettings.element(written))
        if (rescheduleGeo) UpdateGeoWork.reconfigure()
        platformSettings()
    }

    /**
     * Saves the routing rules; with [restartService] the running local
     * service reloads with them and the previous rules come back if the
     * reload fails.
     */
    suspend fun saveRouting(value: Map<String, Any?>, restartService: Boolean) = operations.withLock {
        saveRestarting(mapOf("routingRules" to ServiceSettings.normalizeRouting(value)), restartService, restart = false, skipUnchanged = true)
        platformSettings()
    }

    /**
     * What the selected profile would start with under [draft] (unsaved rules):
     * the core that runs it, the compiled content and the warnings.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun routingPreview(draft: Map<String, Any?>): Map<String, Any?> {
        return withContext(Dispatchers.IO) {
            val profile = ProfileManager.get(Settings.selectedProfile) ?: error("Profile no longer exists")
            val content = java.io.File(profile.typed.path).readText()
            // The brain runs the profile's own rules; the app's routing rules
            // are not merged into it on the single-core path.
            mapOf(
                "profile" to profile.name, "core" to ProfileContent.coreOf(profile, content), "content" to content,
                "applied" to 0, "active" to false,
                "warnings" to "The single-core kernel runs the profile's own rules",
            )
        }
    }

    /**
     * The names a "node or group" rule of [core] can target in the selected
     * profile, one per line (libbox CoreRoutingTargets: mihomo proxy-provider
     * nodes are left out); empty when the selected profile runs on another core.
     */
    suspend fun routingTargets(core: String): String = withContext(Dispatchers.IO) {
        val profile = ProfileManager.get(Settings.selectedProfile) ?: return@withContext ""
        val content = java.io.File(profile.typed.path).readText()
        if (ProfileContent.coreOf(profile, content) != core) "" else routingTargetNames(content)
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

    /** Display names of [packages] (routing rules list their apps by name); unknown packages are left out. */
    suspend fun appLabels(packages: List<String>): Map<String, String> = withContext(Dispatchers.IO) {
        val packageManager = activity.packageManager
        packages.mapNotNull { name ->
            runCatching { name to packageManager.getApplicationInfo(name, 0).loadLabel(packageManager).toString() }.getOrNull()
        }.toMap()
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

    fun openUrl(url: String) {
        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    suspend fun deleteRemoteServer(id: Long) {
        withContext(Dispatchers.IO) { RemoteServerManager.get(id)?.let { RemoteServerManager.delete(it) } }
    }

    /** Remote control was libbox's command client; saved servers are kept but not connected. */
    @Suppress("UNUSED_PARAMETER")
    suspend fun connectRemoteServer(id: Long) {
        error("Remote control is not available in the single-core build")
    }

    fun disconnectRemoteServer() {}

    /**
     * Creates a backup and writes it to a document the user picks. The backup
     * (up to 64 MiB) stays on this side: it never crosses JNI to the UI.
     * False when the user dismissed the file picker.
     */
    suspend fun exportBackup(name: String): Boolean {
        val bytes = operations.withLock { withContext(Dispatchers.IO) { backups.create().toByteArray(Charsets.UTF_8) } }
        val uri = activity.pickDocument(name) ?: return false
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
    suspend fun readBackup(scope: String): Any? {
        val uri = activity.pickDocument() ?: return null
        return withContext(Dispatchers.IO) {
            backups.read(activity.readDocument(uri, BoxBackups.MAX_BYTES).second.toString(Charsets.UTF_8), scope)
        }
    }

    suspend fun restoreBackup(token: String, scope: String, strategy: String) = localProfiles {
        backups.restore(token, scope, strategy)
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

    suspend fun readWebdavBackup(scope: String): Any? = withContext(Dispatchers.IO) { backups.readWebdav(scope) }

    // Terminal settings that change files or several settings at once run
    // under the same lock as the other setting writes.
    suspend fun setTerminalSetting(key: String?, value: Any?) = operations.withLock { terminalSettings.set(key, value) }

    suspend fun importTerminalFont() = operations.withLock { terminalSettings.importFont() }

    /** Terminal settings the UI wrote to the client store itself: into the settings cache. */
    fun terminalSettingsWritten(written: List<Any?>): Boolean {
        ServiceSettings.adopt(ServiceSettings.element(written))
        return true
    }

    suspend fun deleteTerminalFont(path: String?) = operations.withLock { terminalSettings.deleteFont(path) }

    suspend fun terminalSettings() = withContext(Dispatchers.IO) {
        Settings.dataStore.flush()
        terminalSettings.snapshot()
    }

    suspend fun terminalThemes(dark: Boolean) = terminalSettings.themes(dark)

    suspend fun terminalFonts() = terminalSettings.fonts()

    // HTTP capture (MITM, HTTP rewrite) is removed from the runtime on the
    // single-core path; its saved settings and certificates are kept.
    fun captureUnavailable(): Nothing = error("HTTP capture is not available in the single-core build")

    // A remote session rules these out before they get here (bridge.rs).
    private suspend fun <T> localProfiles(block: suspend () -> T): T =
        operations.withLock { withContext(Dispatchers.IO) { block() } }

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

    /**
     * Saves [values]. With [restartService] a changed value is applied to the
     * running local service (restarted, or reloaded when [restart] is false)
     * and the previous values come back if that fails.
     */
    private suspend fun saveRestarting(
        values: Map<String, Any?>,
        restartService: Boolean,
        restart: Boolean = true,
        skipUnchanged: Boolean = restartService,
    ) {
        val previous = if (skipUnchanged) {
            val snapshot = withContext(Dispatchers.IO) { ServiceSettings.snapshot() }
            values.mapValues { snapshot[it.key] }.also { if (it == values) return }
        } else emptyMap()
        if (!restartService) return patchSettings(values)
        ServiceSettingsChange.apply(previous, values, ::patchSettings) { recover ->
            withContext(Dispatchers.Main) { activity.applyProfile(restart = restart, recover = recover) }
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

    /**
     * A remote profile import link (sing-box's
     * `sing-box://import-remote-profile?url=…#name`, formerly parsed by libbox).
     */
    private fun parseRemoteProfileLink(value: String): Map<String, Any?> {
        val uri = Uri.parse(value)
        require(uri.scheme == "sing-box" && uri.host == "import-remote-profile") { "Unsupported import link" }
        val url = uri.getQueryParameter("url") ?: error("Missing subscription URL")
        BoxProfiles.validateUrl(url)
        val name = uri.fragment?.takeIf { it.isNotBlank() } ?: Uri.parse(url).host ?: "Imported profile"
        return mapOf("name" to name, "url" to url)
    }

    /**
     * The names a "node or group" rule can target in [content]: the brain's
     * import is not exposed here, so the Clash `proxies` / `proxy-groups`
     * names are read line by line (`name:` keys); empty for other formats.
     */
    private fun routingTargetNames(content: String): String {
        val names = LinkedHashSet<String>()
        val pattern = Regex("""^\s*-?\s*\{?\s*name:\s*['"]?([^,'"}]+)['"]?""")
        for (line in content.lineSequence()) {
            pattern.find(line)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }?.let(names::add)
        }
        return names.joinToString("\n")
    }

    override fun close() {
        qrScanner.close()
        streamJob?.cancel()
        sink = null
        if (::streams.isInitialized) {
            streams.close()
            CommandSessions.setPageVisibility(false, 0, false)
            ProfileManager.unregisterCallback(profileCallback)
            PackageQueryManager.unregisterListeners()
            dashboardDetails.close()
        }
    }
}
