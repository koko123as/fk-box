package io.nekohasekai.sfa.bridge

import org.json.JSONArray
import org.json.JSONObject

/**
 * JNI entry points of the Rust + Slint UI (apps/android/native, libfkui.so). Rust calls
 * [BoxActivity.slintCall]; results and BoxBridge events come back here as
 * `{"v": value}` JSON.
 */
object SlintHost {
    @JvmStatic external fun nativeEvent(payload: String)

    @JvmStatic external fun nativeReply(id: Long, ok: Boolean, payload: String)

    fun wire(value: Any?): String = JSONObject().put("v", json(value)).toString()

    private fun json(value: Any?): Any = when (value) {
        null, Unit -> JSONObject.NULL
        is Map<*, *> -> JSONObject().also { obj -> value.forEach { (k, v) -> obj.put(k.toString(), json(v)) } }
        is Iterable<*> -> JSONArray().also { array -> value.forEach { array.put(json(it)) } }
        is Array<*> -> JSONArray().also { array -> value.forEach { array.put(json(it)) } }
        is Double -> if (value.isFinite()) value else JSONObject.NULL
        is Float -> if (value.isFinite()) value.toDouble() else JSONObject.NULL
        is Number, is Boolean, is String -> value
        is Enum<*> -> value.name
        else -> value.toString()
    }
}

private fun JSONObject.str(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null

private fun JSONObject.int(key: String): Int? = if (has(key) && !isNull(key)) getInt(key) else null

private fun JSONObject.map(key: String): Map<String, Any?> = if (has(key) && !isNull(key)) jsonObject(getJSONObject(key)) else emptyMap()

private fun JSONObject.list(key: String): List<Any?> = if (has(key) && !isNull(key)) {
    val array = getJSONArray(key)
    (0 until array.length()).map { index -> array.get(index).let { if (it is JSONObject) jsonObject(it) else if (it == JSONObject.NULL) null else it } }
} else {
    emptyList()
}

private fun JSONObject.any(key: String): Any? = if (has(key) && !isNull(key)) {
    when (val value = get(key)) {
        is JSONObject -> jsonObject(value)
        is JSONArray -> (0 until value.length()).map { value.get(it).let { item -> if (item is JSONObject) jsonObject(item) else if (item == JSONObject.NULL) null else item } }
        else -> value
    }
} else {
    null
}

/**
 * Maps the Slint UI's method names onto [BoxBridge] commands: every command the
 * Compose pages ran through `box.call { ... }` (compose/BoxController.kt).
 */
internal suspend fun BoxBridge.slintDispatch(method: String, args: JSONObject): Any? = when (method) {
    "ready" -> ready()
    "startService" -> startService()
    "stopService" -> stopService()
    "reloadService" -> reloadService(args.optBoolean("restart"))
    "checkUpdates" -> checkUpdates()
    // Resources
    "geoList" -> geoList()
    "geoUpdate" -> geoUpdate(args.getString("name"))
    "geoSetUrl" -> geoSetUrl(args.getString("name"), args.getString("url"))
    // Dashboard details
    "egress" -> egress(args.getString("id"))
    "openProbe" -> openProbe(args.getString("id"))
    "probeGet" -> probeGet(args.getString("id"), args.getString("url"), args.optLong("timeoutMillis", 10_000))
    "serviceCheck" -> serviceCheck(args.getString("id"), args.getString("name"), args.optLong("timeoutMillis", 10_000))
    "closeProbe" -> closeProbe(args.getString("id"))
    "releaseMemory" -> releaseMemory()
    "setMemoryVisible" -> setMemoryVisible(args.getBoolean("visible"))
    "setLanVisible" -> setLanVisible(args.getBoolean("visible"))
    "refreshLan" -> refreshLan()
    "setLanProxy" -> setLanProxy(args.getLong("profileId"), args.getString("revision"), args.getBoolean("enabled"), args.int("port"))
    // Profiles
    "readProfile" -> readProfile(args.getLong("id"))
    "profileRevision" -> profileRevision(args.getLong("id"))
    "profileConfig" -> profileConfig(args.getLong("id"))
    "profileStats" -> profileStats(args.getLong("id"))
    "writeProfile" -> writeProfile(args.getLong("id"), args.getString("content"), args.getString("revision"))
    "selectProfile" -> selectProfile(args.getLong("id"))
    "saveProfile" -> saveProfile(args.map("profile"))
    "saveProfiles" -> saveProfiles(args.list("entries"), args.optString("core", "auto"))
    "decodeProfileText" -> decodeProfileText(args.getString("content"), args.optString("name", "Imported profile"))
    "updateProfile" -> updateProfile(args.getLong("id"))
    "deleteProfile" -> deleteProfile(args.getLong("id"))
    "importProfileFile" -> importProfileFile()
    "decodeProfileIntent" -> decodeProfileIntent(args.getString("value"), args.getString("kind"))
    "takeIntent" -> takeIntent()
    "checkConfig" -> checkConfig(args.getString("core"), args.getString("content"))
    "formatConfig" -> formatConfig(args.getString("core"), args.getString("content"))
    "saveDocument" -> saveDocument(args.getString("name"), args.getString("content"))
    "shareText" -> shareText(args.getString("content"))
    // Proxies, mode, connections, logs
    "selectOutbound" -> selectOutbound(args.getString("group"), args.getString("tag"))
    "urlTest" -> urlTest(args.getString("tag"))
    "setMode" -> setMode(args.getString("mode"))
    "setSystemProxy" -> setSystemProxy(args.getBoolean("enabled"))
    "dashboardProbe" -> dashboardProbe(args.optBoolean("local"), args.optBoolean("notes"))
    // The stream pages, combined by mobile-ffi bridge.rs.
    "pages.visible" -> pageVisibility(args.getBoolean("traffic"), args.getInt("connections"), args.getBoolean("logs"))
    "closeConnection" -> closeConnection(args.getString("id"))
    "closeConnections" -> closeConnections()
    "clearLogs" -> clearLogs()
    // Settings
    "setSetting" -> setSetting(args.getString("key"), args.any("value"), args.optBoolean("restartService"))
    "saveRouting" -> saveRouting(args.map("value"), args.optBoolean("restartService"))
    "routingPreview" -> routingPreview(args.map("draft"))
    "routingTargets" -> routingTargets(args.getString("core"))
    // BoxService.routingStatus, as mobile-ffi service.rs shaped it.
    "routingStatus" -> io.nekohasekai.sfa.bg.BoxService.routingStatus.value?.let(io.nekohasekai.sfa.database.ServiceSettings::plain)
    "updateSettings" -> updateSettings(args.map("values"))
    "serviceSettingsWritten" -> serviceSettingsWritten(args.list("written"), args.optBoolean("rescheduleGeo"))
    "listApps" -> listApps(args.optBoolean("refresh"), args.optBoolean("system"), args.optBoolean("nonInternet"))
    "appLabels" -> appLabels(args.list("packages").mapNotNull { it as? String })
    "releaseApps" -> releaseApps()
    "requestAppsPermission" -> requestAppsPermission()
    "rescanApps" -> rescanApps()
    "openBatterySettings" -> openBatterySettings()
    "openUrl" -> openUrl(args.getString("url"))
    // Remote control
    "deleteRemoteServer" -> deleteRemoteServer(args.getLong("id"))
    "connectRemoteServer" -> connectRemoteServer(args.getLong("id"))
    "disconnectRemoteServer" -> disconnectRemoteServer()
    // Backups and WebDAV
    "exportBackup" -> exportBackup(args.getString("name"))
    "readBackup" -> readBackup(args.getString("scope"))
    "restoreBackup" -> restoreBackup(args.getString("token"), args.getString("scope"), args.getString("strategy"))
    "webdavAccount" -> webdavAccount()
    "setWebdavAccount" -> setWebdavAccount(args.map("value").ifEmpty { null })
    "checkWebdav" -> checkWebdav()
    "uploadWebdavBackup" -> uploadWebdavBackup()
    "readWebdavBackup" -> readWebdavBackup(args.getString("scope"))
    // Terminal settings
    "setTerminalSetting" -> setTerminalSetting(args.str("key"), args.any("value"))
    "importTerminalFont" -> importTerminalFont()
    "deleteTerminalFont" -> deleteTerminalFont(args.str("path"))
    "terminalSettings" -> terminalSettings()
    "terminalSettingsWritten" -> terminalSettingsWritten(args.list("written"))
    "terminalThemes" -> terminalThemes(args.optBoolean("dark"))
    "terminalFonts" -> terminalFonts()
    // HTTP capture: removed from the single-core runtime (its settings are kept).
    "configureCapture", "startCapture", "stopCapture", "capture.caSnapshot", "capture.caGenerate", "capture.snapshot",
    "capture.settings", "capture.logs", "capture.clearLogs", "capture.clear", "capture.domains", "capture.detail",
    "capture.export", "capture.exportCertificate", "capture.installCertificate" -> captureUnavailable()
    // Reports (crash and power)
    "reports.list" -> reports.list(args.getString("kind"))
    "reports.clear" -> reports.clear(args.getString("kind"))
    "reports.delete" -> reports.delete(args.getString("kind"), args.str("id"))
    "reports.files" -> reports.files(args.getString("kind"), args.str("id"))
    "reports.read" -> reports.read(args.getString("kind"), args.str("id"), args.str("name"))
    "reports.save" -> reports.save(args.getString("kind"), args.str("id"), args.str("name"))
    "reports.export" -> reports.export(args.getString("kind"), args.str("id"), args.optBoolean("config"), args.optBoolean("logs"), args.optBoolean("encrypt"))
    // Tools: diagnostics (STUN, network quality, Tailscale ping), Tailscale,
    // Taildrop, OpenVPN, OpenConnect, USB/IP and SSH were libbox services;
    // the single-core build has none of them (their saved data is kept).
    "tools.metered" -> false
    "tools.runDiagnostic", "tools.cancelDiagnostic", "tools.setTailscaleExitNode", "tools.tailscaleLogout",
    "tools.openTaildropInbox", "tools.closeTaildropInbox", "tools.markTaildropRead", "tools.deleteTaildropFile",
    "tools.cancelTaildropReceive", "tools.saveTaildropFile", "tools.discardShare", "tools.sendShare",
    "tools.sendTaildropFile", "tools.cancelTaildropSend", "tools.dismissTaildropSend", "tools.submitOpenConnect",
    "tools.cancelOpenConnect", "tools.submitOpenVPN", "tools.cancelOpenVPN", "tools.usbDevices", "tools.attachUsb",
    "tools.detachUsb", "ssh.snapshot", "ssh.options", "ssh.start", "ssh.select", "ssh.duplicate", "ssh.closeSession",
    -> error("Not available in the single-core build: $method")
    else -> error("Unknown method: $method")
}
