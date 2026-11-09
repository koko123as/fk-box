package io.nekohasekai.sfa.bridge

import android.graphics.Bitmap
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.Closeable

/**
 * The Flutter UI's MethodChannel ("io.koko123as.flbox/box") and EventChannel
 * ("io.koko123as.flbox/events"). Each channel method is the command name the
 * Flutter UI has always sent; it is dispatched to the typed [BoxBridge]
 * function behind it, and the bridge's events are streamed unchanged.
 */
class BoxChannel(private val activity: BoxActivity, private val bridge: BoxBridge, messenger: BinaryMessenger) : Closeable {
    private val channel = MethodChannel(messenger, "io.koko123as.flbox/box")
    private val events = EventChannel(messenger, "io.koko123as.flbox/events")

    init {
        channel.setMethodCallHandler { call, result ->
            activity.lifecycleScope.launch {
                try {
                    result.success(bridge.call { dispatch(call) })
                } catch (e: CancellationException) {
                    result.error("cancelled", "Operation cancelled", null)
                } catch (e: Exception) {
                    result.error("box_error", e.message ?: e.toString(), null)
                }
            }
        }
        events.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, sink: EventChannel.EventSink) {
                bridge.listen { event -> sink.success(event) }
            }

            override fun onCancel(arguments: Any?) = bridge.cancel()
        })
    }

    private suspend fun BoxBridge.dispatch(call: MethodCall): Any? {
        @Suppress("UNCHECKED_CAST")
        val args = (call.arguments as? Map<String, Any?>).orEmpty()
        fun string(key: String): String = args[key] as? String ?: error("Missing argument: " + key)
        fun optional(key: String): String? = args[key] as? String
        fun long(key: String = "id"): Long? = (args[key] as? Number)?.toLong()
        fun flag(key: String): Boolean = args[key] == true

        @Suppress("UNCHECKED_CAST")
        fun map(key: String): Map<String, Any?>? = args[key] as? Map<String, Any?>
        return when (val method = call.method) {
            "ready" -> ready()
            "updates.check" -> checkUpdates()
            "geo.list" -> geoList()
            "geo.update" -> geoUpdate(string("name"))
            "geo.setUrl" -> geoSetUrl(string("name"), string("url"))
            "dashboard.egress" -> egress(string("id"))
            "dashboard.probe.open" -> openProbe(string("id")).let { null }
            "dashboard.probe.get" -> probeGet(string("id"), string("url"), long("timeoutMillis") ?: error("Missing argument: timeoutMillis"))
            "dashboard.probe.close" -> closeProbe(string("id")).let { null }
            "dashboard.qualityCache.get" -> readQualityCache(string("ip"))
            "dashboard.qualityCache.put" -> writeQualityCache(string("ip"), map("value") ?: error("Missing IP quality record")).let { null }
            "dashboard.releaseMemory" -> releaseMemory()
            "dashboard.memory.visible" -> setMemoryVisible(flag("visible")).let { null }
            "dashboard.lan.visible" -> setLanVisible(flag("visible")).let { null }
            "dashboard.lan.refresh" -> refreshLan()
            "dashboard.lan.set" -> {
                val enabled = args["enabled"] as? Boolean ?: error("Missing LAN proxy state")
                val port = args["port"]?.let {
                    val number = it as? Number ?: error("Invalid proxy port")
                    require(number.toDouble() == number.toInt().toDouble()) { "Invalid proxy port" }
                    number.toInt()
                }
                setLanProxy(long("profileId"), optional("revision"), enabled, port)
            }
            "service.start" -> startService().let { null }
            "service.stop" -> stopService().let { null }
            "service.reload" -> reloadService(flag("restart")).let { null }
            "profiles.read" -> readProfile(long())
            "profiles.config" -> profileConfig(long())
            "profiles.stats" -> profileStats(long())
            "profiles.write" -> writeProfile(long(), optional("content"), optional("revision"))
            "profiles.select" -> selectProfile(long()).let { null }
            "profiles.save" -> saveProfile(args)
            "profiles.saveAll" -> saveProfiles(args["entries"] as? List<*> ?: error("Missing argument: entries"), optional("core") ?: "auto")
            "profiles.decodeText" -> decodeProfileText(string("content"), optional("name") ?: "Imported profile")
            "profiles.update" -> updateProfile(long()).let { null }
            "profiles.delete" -> deleteProfile(long()).let { null }
            "profiles.reorder" -> reorderProfiles((args["ids"] as List<*>).map { (it as Number).toLong() }).let { null }
            "profiles.importFile" -> importProfileFile()
            "profiles.scan" -> scanProfile(string("session"), string("text"))
            "profiles.scan.close" -> closeProfileScan(string("session")).let { null }
            "profiles.decodeIntent" -> decodeProfileIntent(string("uri"))
            "intent.take" -> takeIntent()
            "config.check" -> checkConfig(optional("core"), string("content"))
            "config.format" -> formatConfig(optional("core"), string("content"))
            "document.save" -> saveDocument(string("name"), string("content"))
            "share.text" -> shareText(string("content")).let { null }
            "groups.select" -> selectOutbound(optional("group"), optional("tag")).let { null }
            "groups.test" -> urlTest(optional("tag")).let { null }
            "mode.set" -> setMode(string("mode")).let { null }
            "systemProxy.set" -> setSystemProxy(flag("enabled")).let { null }
            "connections.visible" -> setConnectionsVisible(flag("visible")).let { null }
            "connections.close" -> closeConnection(optional("id")).let { null }
            "connections.closeAll" -> closeConnections().let { null }
            "logs.clear" -> clearLogs().let { null }
            "logs.visible" -> setLogsVisible(flag("visible")).let { null }
            "preferences.set" -> setPreferences(map("value") ?: error("Missing preferences"))
            "settings.set" -> setSetting(string("key"), args["value"], flag("restart"))
            "settings.patch" -> updateSettings(map("value") ?: error("Missing settings"))
            "apps.list" -> listApps(flag("refresh"), flag("system"), flag("nonInternet"))
            "apps.icon" -> appIcon(string("package")).let { bitmap ->
                withContext(Dispatchers.IO) {
                    ByteArrayOutputStream().use { output ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                        output.toByteArray()
                    }
                }
            }
            "apps.permission" -> requestAppsPermission().let { null }
            "apps.rescan" -> rescanApps()
            "battery.open" -> openBatterySettings().let { null }
            "url.open" -> openUrl(optional("url")).let { null }
            "remote.list" -> remoteServers()
            "remote.save" -> saveRemoteServer(long(), string("name"), string("url"), string("secret"))
            "remote.delete" -> deleteRemoteServer(long()).let { null }
            "remote.connect" -> connectRemoteServer(long()).let { null }
            "remote.disconnect" -> disconnectRemoteServer().let { null }
            "backup.create" -> createBackup()
            "backup.read" -> readBackup(optional("scope"))
            "backup.restore" -> restoreBackup(map("backup"), optional("scope"), optional("strategy"))
            "webdav.get" -> webdavAccount()
            "webdav.set" -> {
                require(args.containsKey("value")) { "Missing WebDAV account" }
                setWebdavAccount(args["value"]?.let { map("value") ?: error("Invalid WebDAV account") })
            }
            "webdav.check" -> checkWebdav()
            "webdav.upload" -> uploadWebdavBackup()
            "webdav.read" -> readWebdavBackup(optional("scope"))
            "terminal.settings" -> terminalSettings()
            "terminal.themes" -> terminalThemes(flag("dark"))
            "terminal.fonts" -> terminalFonts()
            "terminal.set" -> setTerminalSetting(optional("key"), args["value"])
            "terminal.importFont" -> importTerminalFont()
            "terminal.deleteFont" -> deleteTerminalFont(optional("path"))
            "capture.configure" -> configureCapture(optional("revision"), map("config"))
            "capture.start" -> startCapture()
            "capture.stop" -> stopCapture()
            "capture.ca.snapshot" -> capture.caSnapshot()
            "capture.ca.generate" -> capture.caGenerate(optional("revision"))
            "capture.ca.export" -> capture.exportCertificate(optional("revision"), module = false)
            "capture.ca.module" -> capture.exportCertificate(optional("revision"), module = true)
            "capture.ca.install" -> capture.installCertificate(optional("revision"))
            "capture.snapshot" -> capture.snapshot((args["limit"] as? Number)?.toInt(), optional("query"))
            "capture.settings" -> capture.captureSettings()
            "capture.logs" -> capture.logs()
            "capture.logs.clear" -> capture.clearLogs().let { null }
            "capture.clear" -> capture.clear().let { null }
            "capture.domains" -> capture.domains(optional("query"))
            "capture.detail" -> capture.detail(optional("id"))
            "capture.export" -> capture.export(optional("format"), optional("id"), optional("query"))
            "reports.list" -> reports.list(string("kind"))
            "reports.clear" -> reports.clear(string("kind")).let { null }
            "reports.delete" -> reports.delete(string("kind"), optional("id")).let { null }
            "reports.files" -> reports.files(string("kind"), optional("id"))
            "reports.read" -> reports.read(string("kind"), optional("id"), optional("name"))
            "reports.save" -> reports.save(string("kind"), optional("id"), optional("name"))
            "reports.export" -> reports.export(string("kind"), optional("id"), flag("config"), flag("logs"), flag("encrypt"))
            "ssh.snapshot" -> toolBridge.ssh.snapshot()
            "ssh.options" -> toolBridge.ssh.options(optional("endpoint"), optional("peer"))
            "ssh.start" -> toolBridge.ssh.start(optional("endpoint"), optional("peer"), optional("username"), optional("terminalType"), flag("remember"))
            "ssh.select" -> toolBridge.ssh.select(optional("id"))
            "ssh.duplicate" -> toolBridge.ssh.duplicate()
            "ssh.close" -> toolBridge.ssh.closeSession(optional("id"))
            "stun.start" -> toolBridge.startStun(optional("server"), optional("outbound")).let { null }
            "stun.stop" -> toolBridge.stopStun().let { null }
            "quality.start" -> toolBridge.startQuality(optional("url"), optional("outbound"), flag("serial"), flag("http3"), (args["seconds"] as? Number)?.toInt()).let { null }
            "quality.confirm" -> toolBridge.confirmQuality().let { null }
            "quality.dismiss" -> toolBridge.dismissQuality().let { null }
            "quality.stop" -> toolBridge.stopQuality().let { null }
            "tailscale.exit" -> toolBridge.setTailscaleExitNode(optional("endpoint"), optional("peer")).let { null }
            "tailscale.logout" -> toolBridge.tailscaleLogout(optional("endpoint")).let { null }
            "tailscale.ping" -> toolBridge.tailscalePing(optional("endpoint"), optional("peer")).let { null }
            "tailscale.stopPing" -> toolBridge.stopTailscalePing().let { null }
            "taildrop.subscribe" -> toolBridge.subscribeTaildrop(optional("endpoint")).let { null }
            "taildrop.unsubscribe" -> toolBridge.unsubscribeTaildrop().let { null }
            "taildrop.delete" -> toolBridge.deleteTaildropFile(optional("endpoint"), optional("name")).let { null }
            "taildrop.cancelReceive" -> toolBridge.cancelTaildropReceive(optional("endpoint"), optional("sender"), optional("name")).let { null }
            "taildrop.save" -> toolBridge.saveTaildropFile(optional("endpoint"), optional("name"))
            "taildrop.send" -> toolBridge.sendTaildropFile(optional("endpoint"), optional("peer"))
            "taildrop.cancelSend" -> toolBridge.cancelTaildropSend(long() ?: error("Missing argument: id")).let { null }
            "taildrop.dismissSend" -> toolBridge.dismissTaildropSend(long() ?: error("Missing argument: id")).let { null }
            "share.discard" -> toolBridge.discardShare(optional("token")).let { null }
            "share.send" -> toolBridge.sendShare(optional("token"), optional("endpoint"), optional("peer"))
            "openconnect.submit" -> {
                @Suppress("UNCHECKED_CAST")
                val values = args["values"] as? Map<String, String> ?: emptyMap()
                toolBridge.submitOpenConnect(optional("endpoint"), optional("challenge"), values).let { null }
            }
            "openconnect.cancel" -> toolBridge.cancelOpenConnect(optional("endpoint"), optional("challenge")).let { null }
            "openvpn.submit" -> toolBridge.submitOpenVPN(optional("endpoint"), optional("challenge"), optional("username"), optional("password"), optional("secret")).let { null }
            "openvpn.cancel" -> toolBridge.cancelOpenVPN(optional("endpoint"), optional("challenge")).let { null }
            "usb.list" -> toolBridge.usbDevices()
            "usb.attach" -> toolBridge.attachUsb(optional("server"), optional("device")).let { null }
            "usb.detach" -> toolBridge.detachUsb(optional("id")).let { null }
            else -> error("Unknown command: $method")
        }
    }

    override fun close() {
        channel.setMethodCallHandler(null)
        events.setStreamHandler(null)
        bridge.cancel()
    }
}
