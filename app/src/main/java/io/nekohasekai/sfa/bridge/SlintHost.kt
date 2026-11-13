package io.nekohasekai.sfa.bridge

import org.json.JSONArray
import org.json.JSONObject

/**
 * JNI entry points of the Rust + Slint UI (ui-slint, libfkui.so). Rust calls
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

private fun JSONObject.long(key: String): Long? = if (has(key) && !isNull(key)) getLong(key) else null

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
    "probeReach" -> probeReach(args.getString("id"), args.getString("url"), args.optLong("timeoutMillis", 8_000))
    "closeProbe" -> closeProbe(args.getString("id"))
    "readQualityCache" -> readQualityCache(args.getString("ip"))
    "writeQualityCache" -> writeQualityCache(args.getString("ip"), args.map("value"))
    "releaseMemory" -> releaseMemory()
    "setMemoryVisible" -> setMemoryVisible(args.getBoolean("visible"))
    "setLanVisible" -> setLanVisible(args.getBoolean("visible"))
    "refreshLan" -> refreshLan()
    "setLanProxy" -> setLanProxy(args.long("profileId"), args.str("revision"), args.getBoolean("enabled"), args.int("port"))
    // Profiles
    "readProfile" -> readProfile(args.long("id"))
    "profileConfig" -> profileConfig(args.long("id"))
    "profileStats" -> profileStats(args.long("id"))
    "writeProfile" -> writeProfile(args.long("id"), args.str("content"), args.str("revision"))
    "selectProfile" -> selectProfile(args.long("id"))
    "saveProfile" -> saveProfile(args.map("profile"))
    "saveProfiles" -> saveProfiles(args.list("entries"), args.optString("core", "auto"))
    "decodeProfileText" -> decodeProfileText(args.getString("content"), args.optString("name", "Imported profile"))
    "updateProfile" -> updateProfile(args.long("id"))
    "deleteProfile" -> deleteProfile(args.long("id"))
    "reorderProfiles" -> reorderProfiles(args.list("ids").map { (it as Number).toLong() })
    "importProfileFile" -> importProfileFile()
    "scanProfile" -> scanProfile(args.getString("session"), args.getString("text"))
    "closeProfileScan" -> closeProfileScan(args.getString("session"))
    "decodeProfileIntent" -> decodeProfileIntent(args.getString("value"))
    "takeIntent" -> takeIntent()
    "checkConfig" -> checkConfig(args.str("core"), args.getString("content"))
    "formatConfig" -> formatConfig(args.str("core"), args.getString("content"))
    "saveDocument" -> saveDocument(args.getString("name"), args.getString("content"))
    "shareText" -> shareText(args.getString("content"))
    // Proxies, mode, connections, logs
    "selectOutbound" -> selectOutbound(args.str("group"), args.str("tag"))
    "urlTest" -> urlTest(args.str("tag"))
    "setMode" -> setMode(args.getString("mode"))
    "setSystemProxy" -> setSystemProxy(args.getBoolean("enabled"))
    "setConnectionsVisible" -> setConnectionsVisible(args.getBoolean("visible"))
    "setConnectionStatsVisible" -> setConnectionStatsVisible(args.getBoolean("visible"))
    "closeConnection" -> closeConnection(args.str("id"))
    "closeConnections" -> closeConnections()
    "setLogsVisible" -> setLogsVisible(args.getBoolean("visible"))
    "clearLogs" -> clearLogs()
    // Settings
    "setPreferences" -> setPreferences(args.map("values"))
    "setSetting" -> setSetting(args.getString("key"), args.any("value"), args.optBoolean("restart"))
    "saveRouting" -> saveRouting(args.map("value"))
    "routingPreview" -> routingPreview(args.map("draft"))
    "routingTargets" -> routingTargets(args.getString("core"))
    // BoxService.routingStatus, which the Compose pages collected directly.
    "routingStatus" -> io.nekohasekai.sfa.bg.BoxService.routingStatus.value?.let {
        mapOf("core" to it.core, "applied" to it.applied, "active" to it.active, "fallback" to it.fallback, "error" to it.error, "warnings" to it.warnings, "time" to it.time)
    }
    "updateSettings" -> updateSettings(args.map("values"))
    "listApps" -> listApps(args.optBoolean("refresh"), args.optBoolean("system"), args.optBoolean("nonInternet"))
    "releaseApps" -> releaseApps()
    "requestAppsPermission" -> requestAppsPermission()
    "rescanApps" -> rescanApps()
    "openBatterySettings" -> openBatterySettings()
    "openUrl" -> openUrl(args.str("url"))
    // Remote control
    "remoteServers" -> remoteServers()
    "saveRemoteServer" -> saveRemoteServer(args.long("id"), args.getString("name"), args.getString("url"), args.optString("secret"))
    "deleteRemoteServer" -> deleteRemoteServer(args.long("id"))
    "connectRemoteServer" -> connectRemoteServer(args.long("id"))
    "disconnectRemoteServer" -> disconnectRemoteServer()
    // Backups and WebDAV
    "createBackup" -> createBackup()
    "readBackup" -> readBackup(args.str("scope"))
    "restoreBackup" -> restoreBackup(args.map("backup"), args.str("scope"), args.str("strategy"))
    "webdavAccount" -> webdavAccount()
    "setWebdavAccount" -> setWebdavAccount(args.map("value").ifEmpty { null })
    "checkWebdav" -> checkWebdav()
    "uploadWebdavBackup" -> uploadWebdavBackup()
    "readWebdavBackup" -> readWebdavBackup(args.str("scope"))
    // Terminal settings
    "setTerminalSetting" -> setTerminalSetting(args.str("key"), args.any("value"))
    "importTerminalFont" -> importTerminalFont()
    "deleteTerminalFont" -> deleteTerminalFont(args.str("path"))
    "terminalSettings" -> terminalSettings()
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
    "tools.snapshot" -> toolBridge.snapshot()
    "tools.diagnostics" -> toolBridge.diagnostics()
    "tools.startStun" -> toolBridge.startStun(args.str("server"), args.str("outbound"))
    "tools.stopStun" -> toolBridge.stopStun()
    "tools.startQuality" -> toolBridge.startQuality(args.str("url"), args.str("outbound"), args.optBoolean("serial"), args.optBoolean("http3"), args.int("seconds"))
    "tools.confirmQuality" -> toolBridge.confirmQuality()
    "tools.dismissQuality" -> toolBridge.dismissQuality()
    "tools.stopQuality" -> toolBridge.stopQuality()
    "tools.setTailscaleExitNode" -> toolBridge.setTailscaleExitNode(args.str("endpoint"), args.str("peer"))
    "tools.tailscaleLogout" -> toolBridge.tailscaleLogout(args.str("endpoint"))
    "tools.tailscalePing" -> toolBridge.tailscalePing(args.str("endpoint"), args.str("peer"))
    "tools.stopTailscalePing" -> toolBridge.stopTailscalePing()
    "tools.subscribeTaildrop" -> toolBridge.subscribeTaildrop(args.str("endpoint"))
    "tools.unsubscribeTaildrop" -> toolBridge.unsubscribeTaildrop()
    "tools.deleteTaildropFile" -> toolBridge.deleteTaildropFile(args.str("endpoint"), args.str("name"))
    "tools.cancelTaildropReceive" -> toolBridge.cancelTaildropReceive(args.str("endpoint"), args.str("sender"), args.str("name"))
    "tools.saveTaildropFile" -> toolBridge.saveTaildropFile(args.str("endpoint"), args.str("name"))
    "tools.discardShare" -> toolBridge.discardShare(args.str("token"))
    "tools.sendShare" -> toolBridge.sendShare(args.str("token"), args.str("endpoint"), args.str("peer"))
    "tools.sendTaildropFile" -> toolBridge.sendTaildropFile(args.str("endpoint"), args.str("peer"))
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
    "ssh.options" -> toolBridge.ssh.options(args.str("endpoint"), args.str("peer"))
    "ssh.start" -> toolBridge.ssh.start(args.str("endpoint"), args.str("peer"), args.str("username"), args.str("terminalType"), args.optBoolean("remember"))
    "ssh.select" -> toolBridge.ssh.select(args.str("id"))
    "ssh.duplicate" -> toolBridge.ssh.duplicate()
    "ssh.closeSession" -> toolBridge.ssh.closeSession(args.str("id"))
    else -> error("Unknown method: $method")
}
