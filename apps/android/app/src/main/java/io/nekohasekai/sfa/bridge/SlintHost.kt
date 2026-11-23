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
    // Capture
    "configureCapture" -> configureCapture(args.str("revision"), args.map("config"))
    "startCapture" -> startCapture()
    "stopCapture" -> stopCapture()
    "capture.caSnapshot" -> capture.caSnapshot()
    "capture.caGenerate" -> capture.caGenerate(args.str("revision"))
    "capture.snapshot" -> capture.snapshot(args.int("limit"), args.str("query"))
    "capture.settings" -> capture.captureSettings()
    "capture.logs" -> capture.logs()
    "capture.clearLogs" -> capture.clearLogs()
    "capture.clear" -> capture.clear()
    "capture.domains" -> capture.domains(args.str("query"))
    "capture.detail" -> capture.detail(args.str("id"))
    "capture.export" -> capture.export(args.str("format"), args.str("id"), args.str("query"))
    "capture.exportCertificate" -> capture.exportCertificate(args.str("revision"), args.optBoolean("module"))
    "capture.installCertificate" -> capture.installCertificate(args.str("revision"))
    // Reports (crash and power)
    "reports.list" -> reports.list(args.getString("kind"))
    "reports.clear" -> reports.clear(args.getString("kind"))
    "reports.delete" -> reports.delete(args.getString("kind"), args.str("id"))
    "reports.files" -> reports.files(args.getString("kind"), args.str("id"))
    "reports.read" -> reports.read(args.getString("kind"), args.str("id"), args.str("name"))
    "reports.save" -> reports.save(args.getString("kind"), args.str("id"), args.str("name"))
    "reports.export" -> reports.export(args.getString("kind"), args.str("id"), args.optBoolean("config"), args.optBoolean("logs"), args.optBoolean("encrypt"))
    // Tools
    // Diagnostics: mobile-ffi diagnostics.rs runs them (BoxDiagnostics).
    "tools.runDiagnostic" -> toolBridge.diagnostics.run(args.getString("kind"), args.getLong("id"), jsonObject(args))
    "tools.cancelDiagnostic" -> toolBridge.diagnostics.cancel(args.getString("kind"))
    "tools.metered" -> toolBridge.diagnostics.metered()
    "tools.setTailscaleExitNode" -> toolBridge.setTailscaleExitNode(args.str("endpoint"), args.str("peer"))
    "tools.tailscaleLogout" -> toolBridge.tailscaleLogout(args.str("endpoint"))
    "tools.openTaildropInbox" -> toolBridge.status.openInbox(args.getString("endpoint"), args.getLong("run"))
    "tools.closeTaildropInbox" -> toolBridge.status.closeInbox()
    "tools.markTaildropRead" -> toolBridge.markTaildropRead(args.str("endpoint"))
    "tools.deleteTaildropFile" -> toolBridge.deleteTaildropFile(args.str("endpoint"), args.str("name"))
    "tools.cancelTaildropReceive" -> toolBridge.cancelTaildropReceive(args.str("endpoint"), args.str("sender"), args.str("name"))
    "tools.saveTaildropFile" -> toolBridge.saveTaildropFile(args.str("endpoint"), args.str("name"))
    "tools.discardShare" -> toolBridge.discardShare(args.str("token"))
    "tools.sendShare" -> toolBridge.sendShare(args.str("token"), args.str("endpoint"), args.map("peerInfo"))
    "tools.sendTaildropFile" -> toolBridge.sendTaildropFile(args.str("endpoint"), args.map("peerInfo"))
    "tools.cancelTaildropSend" -> toolBridge.cancelTaildropSend(args.getLong("id"))
    "tools.dismissTaildropSend" -> toolBridge.dismissTaildropSend(args.getLong("id"))
    "tools.submitOpenConnect" -> toolBridge.submitOpenConnect(args.str("endpoint"), args.str("challenge"), args.map("values").mapValues { it.value?.toString() ?: "" })
    "tools.cancelOpenConnect" -> toolBridge.cancelOpenConnect(args.str("endpoint"), args.str("challenge"))
    "tools.submitOpenVPN" -> toolBridge.submitOpenVPN(args.str("endpoint"), args.str("challenge"), args.str("username"), args.str("password"), args.str("secret"))
    "tools.cancelOpenVPN" -> toolBridge.cancelOpenVPN(args.str("endpoint"), args.str("challenge"))
    "tools.usbDevices" -> toolBridge.usbDevices()
    "tools.attachUsb" -> toolBridge.attachUsb(args.str("server"), args.str("device"))
    "tools.detachUsb" -> toolBridge.detachUsb(args.str("id"))
    "ssh.snapshot" -> toolBridge.ssh.snapshot()
    "ssh.options" -> toolBridge.ssh.options(args.map("peerInfo"))
    "ssh.start" -> toolBridge.ssh.start(args.str("endpoint"), args.map("peerInfo"), args.str("username"), args.str("terminalType"), args.optBoolean("remember"))
    "ssh.select" -> toolBridge.ssh.select(args.str("id"))
    "ssh.duplicate" -> toolBridge.ssh.duplicate()
    "ssh.closeSession" -> toolBridge.ssh.closeSession(args.str("id"))
    else -> error("Unknown method: $method")
}
