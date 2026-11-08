// FK-box: the channel for FK-box's own features in the FlClash UI
// (flutter/lib/fkbox/). FlClash's channels stay as FlClash defines them; this
// one carries what FlClash has no Core method for: the per-profile core choice,
// legacy Room profiles, split multi-config imports, the core switches and the
// FK-box tools (Tailscale, SSH, HTTP capture, USB/IP, diagnostics, reports, geo
// resources, updates). Every call is answered by the existing FK-box backend
// (BoxBridge, BoxTools, BoxCapture, BoxReports, GeoResources, BoxUpdates).
package com.follow.clash.plugins

import android.content.Context
import com.follow.clash.FkHost
import com.follow.clash.common.GlobalState
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.bridge.BoxBridge
import io.nekohasekai.sfa.bridge.BoxTerminalFactory
import io.nekohasekai.sfa.bridge.TerminalChannel
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.database.TypedProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class FkBoxPlugin : FlutterPlugin, MethodChannel.MethodCallHandler, EventChannel.StreamHandler {
    private lateinit var channel: MethodChannel
    private lateinit var events: EventChannel
    private lateinit var messenger: BinaryMessenger
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listening: BoxBridge? = null

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        messenger = binding.binaryMessenger
        channel = MethodChannel(messenger, CHANNEL)
        channel.setMethodCallHandler(this)
        events = EventChannel(messenger, EVENTS)
        events.setStreamHandler(this)
        binding.platformViewRegistry.registerViewFactory(TERMINAL_VIEW, TerminalViewFactory(messenger))
        instance = this
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        if (instance === this) instance = null
        channel.setMethodCallHandler(null)
        events.setStreamHandler(null)
        listening?.cancel()
        listening = null
        scope.cancel()
    }

    // ---- events: BoxBridge's state stream while an FK-box page listens ----

    override fun onListen(arguments: Any?, sink: EventChannel.EventSink) {
        val bridge = FkHost.activity?.boxBridge ?: return
        listening = bridge
        bridge.listen { event -> scope.launch { sink.success(event) } }
    }

    override fun onCancel(arguments: Any?) {
        listening?.cancel()
        listening = null
    }

    // ---- calls ----

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        scope.launch {
            try {
                val value = dispatch(call.method, call.arguments as? Map<*, *> ?: emptyMap<String, Any?>())
                result.success(if (value is Unit) null else value)
            } catch (error: Exception) {
                result.error("fkbox", error.message ?: error.toString(), null)
            }
        }
    }

    private fun bridge(): BoxBridge = FkHost.activity?.boxBridge ?: error("FK-box is not ready")

    private suspend fun dispatch(method: String, args: Map<*, *>): Any? {
        fun string(key: String) = args[key] as? String
        fun bool(key: String) = args[key] == true
        fun long(key: String) = (args[key] as? Number)?.toLong() ?: error("Missing $key")
        return when (method) {
            "info" -> mapOf(
                "version" to BuildConfig.VERSION_NAME, "singBoxVersion" to Libbox.version(),
                "xrayVersion" to Libbox.xrayVersion(), "mihomoVersion" to Libbox.mihomoVersion(),
                "settings" to bridge().call { settings() },
                "userAgents" to userAgents(),
                "coreOverrides" to coreOverrides(),
            )
            "settings.set" -> bridge().call { setSetting(string("key") ?: error("Missing key"), args["value"], bool("restart")) }

            "profileCore.get" -> withContext(Dispatchers.IO) { profileCore(string("path") ?: error("Missing path")) }
            "profileCore.set" -> {
                val id = string("id") ?: error("Missing id")
                val core = string("core") ?: TypedProfile.CORE_AUTO
                require(core in TypedProfile.CORES) { "Unknown core: $core" }
                setCoreOverride(id, core)
                coreOverrides()
            }

            "profileCore.overrides" -> coreOverrides()

            "links.expectCore" -> {
                expectLinksCore(string("core"))
                null
            }

            "legacy.list" -> withContext(Dispatchers.IO) { legacyProfiles() }
            "legacy.done" -> {
                preferences.edit().putBoolean(LEGACY_MIGRATED, true).apply()
                null
            }

            "tools.snapshot" -> bridge().call {
                toolBridge.snapshot() + mapOf("diagnostics" to toolBridge.diagnostics(), "terminal" to toolBridge.ssh.snapshot())
            }
            "tailscale.exitNode" -> bridge().call { toolBridge.setTailscaleExitNode(string("endpoint"), string("peer")) }
            "tailscale.logout" -> bridge().call { toolBridge.tailscaleLogout(string("endpoint")) }
            "tailscale.ping" -> bridge().call { toolBridge.tailscalePing(string("endpoint"), string("peer")) }
            "tailscale.stopPing" -> bridge().call { toolBridge.stopTailscalePing() }
            "ssh.snapshot" -> bridge().call { toolBridge.ssh.snapshot() }
            "ssh.options" -> bridge().call { toolBridge.ssh.options(string("endpoint"), string("peer")) }
            "ssh.start" -> bridge().call {
                toolBridge.ssh.start(string("endpoint"), string("peer"), string("username"), string("terminalType"), bool("remember"))
            }
            "ssh.select" -> bridge().call { toolBridge.ssh.select(string("id")) }
            "ssh.duplicate" -> bridge().call { toolBridge.ssh.duplicate() }
            "ssh.close" -> bridge().call { toolBridge.ssh.closeSession(string("id")) }
            "stun.start" -> bridge().call { toolBridge.startStun(string("server"), string("outbound")) }
            "stun.stop" -> bridge().call { toolBridge.stopStun() }
            "quality.start" -> bridge().call {
                toolBridge.startQuality(string("url"), string("outbound"), bool("serial"), bool("http3"), (args["seconds"] as? Number)?.toInt())
            }
            "quality.confirm" -> bridge().call { toolBridge.confirmQuality() }
            "quality.dismiss" -> bridge().call { toolBridge.dismissQuality() }
            "quality.stop" -> bridge().call { toolBridge.stopQuality() }
            "usb.devices" -> bridge().call { toolBridge.usbDevices() }
            "usb.attach" -> bridge().call { toolBridge.attachUsb(string("server"), string("device")) }
            "usb.detach" -> bridge().call { toolBridge.detachUsb(string("id")) }

            "reports.list" -> bridge().call { reports.list(string("kind") ?: error("Missing kind")) }
            "reports.clear" -> bridge().call { reports.clear(string("kind") ?: error("Missing kind")) }
            "reports.delete" -> bridge().call { reports.delete(string("kind") ?: error("Missing kind"), string("id")) }
            "reports.files" -> bridge().call { reports.files(string("kind") ?: error("Missing kind"), string("id")) }
            "reports.read" -> bridge().call { reports.read(string("kind") ?: error("Missing kind"), string("id"), string("name")) }
            "reports.save" -> bridge().call { reports.save(string("kind") ?: error("Missing kind"), string("id"), string("name")) }
            "reports.export" -> bridge().call {
                reports.export(string("kind") ?: error("Missing kind"), string("id"), bool("config"), bool("logs"), bool("encrypt"))
            }

            "capture.ca" -> bridge().call { capture.caSnapshot() }
            "capture.caGenerate" -> bridge().call { capture.caGenerate(string("revision")) }
            "capture.settings" -> bridge().call { capture.captureSettings() }
            "capture.configure" -> bridge().call {
                @Suppress("UNCHECKED_CAST")
                configureCapture(string("revision"), args["config"] as? Map<String, Any?>)
            }
            "capture.snapshot" -> bridge().call { capture.snapshot((args["limit"] as? Number)?.toInt(), string("query")) }
            "capture.start" -> bridge().call { startCapture() }
            "capture.stop" -> bridge().call { stopCapture() }
            "capture.clear" -> bridge().call { capture.clear() }
            "capture.detail" -> bridge().call { capture.detail(string("id")) }
            "capture.logs" -> bridge().call { capture.logs() }
            "capture.export" -> bridge().call { capture.export(string("format"), string("id"), string("query")) }
            "capture.exportCertificate" -> bridge().call { capture.exportCertificate(string("revision"), bool("module")) }
            "capture.installCertificate" -> bridge().call { capture.installCertificate(string("revision")) }

            "geo.list" -> bridge().call { geoList() }
            "geo.update" -> bridge().call { geoUpdate(string("name") ?: error("Missing name")) }
            "geo.setUrl" -> bridge().call { geoSetUrl(string("name") ?: error("Missing name"), string("url").orEmpty()) }
            "updates.check" -> bridge().call { checkUpdates() }
            "url.open" -> bridge().call { openUrl(string("url")) }

            // Intents FK-box's activity received (links, opened files, the share sheet).
            "intent.take" -> FkHost.activity?.takeIntent()
            "intent.link" -> bridge().call { decodeProfileIntent(string("value") ?: error("Missing value")) }
            "intent.read" -> {
                val activity = FkHost.activity ?: error("FK-box is not ready")
                val uri = android.net.Uri.parse(string("value") ?: error("Missing value"))
                withContext(Dispatchers.IO) {
                    val (name, bytes) = activity.readDocument(uri)
                    mapOf("name" to name, "content" to bytes)
                }
            }
            "share.text" -> FkHost.activity?.shareText(string("token") ?: error("Missing token"))
            "share.discard" -> bridge().call { toolBridge.discardShare(string("token")) }
            "share.send" -> bridge().call { toolBridge.sendShare(string("token"), string("endpoint"), string("peer")) != null }

            // Taildrop
            "taildrop.inbox" -> bridge().call { toolBridge.inboxSnapshot() }
            "taildrop.subscribe" -> bridge().call { toolBridge.subscribeTaildrop(string("endpoint")) }
            "taildrop.unsubscribe" -> bridge().call { toolBridge.unsubscribeTaildrop() }
            "taildrop.save" -> bridge().call { toolBridge.saveTaildropFile(string("endpoint"), string("name")) }
            "taildrop.delete" -> bridge().call { toolBridge.deleteTaildropFile(string("endpoint"), string("name")) }
            "taildrop.cancelReceive" -> bridge().call { toolBridge.cancelTaildropReceive(string("endpoint"), string("sender"), string("name")) }
            "taildrop.send" -> bridge().call { toolBridge.sendTaildropFile(string("endpoint"), string("peer")) != null }
            "taildrop.transfers" -> bridge().call { toolBridge.transfers() }
            "taildrop.cancelSend" -> bridge().call { toolBridge.cancelTaildropSend(long("id")) }
            "taildrop.dismissSend" -> bridge().call { toolBridge.dismissTaildropSend(long("id")) }

            // OpenVPN / OpenConnect authentication challenges
            "openvpn.submit" -> bridge().call {
                toolBridge.submitOpenVPN(string("endpoint"), string("challenge"), string("username"), string("password"), string("secret"))
            }
            "openvpn.cancel" -> bridge().call { toolBridge.cancelOpenVPN(string("endpoint"), string("challenge")) }
            "openconnect.submit" -> bridge().call {
                val values = (args["values"] as? Map<*, *>).orEmpty().entries.associate { it.key.toString() to (it.value?.toString() ?: "") }
                toolBridge.submitOpenConnect(string("endpoint"), string("challenge"), values)
            }
            "openconnect.cancel" -> bridge().call { toolBridge.cancelOpenConnect(string("endpoint"), string("challenge")) }

            // Remote control of another FK-box / sing-box instance
            "remote.list" -> bridge().call { remoteServers() }
            "remote.save" -> bridge().call {
                saveRemoteServer((args["id"] as? Number)?.toLong(), string("name").orEmpty(), string("url").orEmpty(), string("secret").orEmpty())
            }
            "remote.delete" -> bridge().call { deleteRemoteServer(long("id")) }
            "remote.connect" -> bridge().call { connectRemoteServer(long("id")) }
            "remote.disconnect" -> bridge().call { disconnectRemoteServer() }
            "remote.state" -> mapOf(
                "server" to io.nekohasekai.sfa.utils.RemoteControlManager.remoteServer.value?.let { mapOf("id" to it.id, "name" to it.name, "url" to it.url) },
                "connected" to io.nekohasekai.sfa.utils.RemoteControlManager.isConnected.value,
            )
            else -> error("Unknown FK-box method: $method")
        }
    }

    private fun userAgents(): Map<String, String> = listOf(TypedProfile.CORE_SING_BOX, TypedProfile.CORE_XRAY, TypedProfile.CORE_MIHOMO)
        .associateWith { Libbox.coreSubscriptionUserAgent(it, BuildConfig.VERSION_NAME) }

    /** The core a profile file runs with: the detected one, or the one chosen for it when the content allows it. */
    private fun profileCore(path: String): Map<String, Any?> {
        val file = File(path)
        val id = file.nameWithoutExtension
        val content = runCatching { file.readText() }.getOrDefault("")
        val detected = runCatching { Libbox.detectCoreConfig(content) }.getOrDefault(TypedProfile.CORE_MIHOMO)
        val choices = when (detected) {
            TypedProfile.CORE_SING_BOX, TypedProfile.CORE_XRAY -> listOf(TypedProfile.CORE_AUTO, TypedProfile.CORE_SING_BOX, TypedProfile.CORE_XRAY)
            else -> listOf(TypedProfile.CORE_AUTO, TypedProfile.CORE_MIHOMO)
        }
        return mapOf(
            "detected" to detected,
            "override" to (coreOverride(id) ?: TypedProfile.CORE_AUTO),
            "effective" to effectiveCore(path, content),
            "choices" to choices,
        )
    }

    // ---- legacy Room profiles ----

    private suspend fun legacyProfiles(): List<Map<String, Any?>> {
        if (preferences.getBoolean(LEGACY_MIGRATED, false)) return emptyList()
        Settings.dataStore.initialize()
        val running = GlobalState.application.getSharedPreferences("flclash_host", 0).getLong("profileId", 0L)
        return ProfileManager.list().filter { it.id != running }.mapNotNull { profile ->
            val typed = profile.typed
            val content = runCatching { File(typed.path).readText() }.getOrNull() ?: return@mapNotNull null
            mapOf(
                "name" to profile.name,
                "url" to if (typed.type == TypedProfile.Type.Remote) typed.remoteURL else "",
                "content" to content,
                "core" to typed.core,
                "autoUpdate" to typed.autoUpdate,
                "interval" to typed.autoUpdateInterval,
                "upload" to typed.upload, "download" to typed.download, "total" to typed.total, "expire" to typed.expire,
            )
        }
    }

    /** A Kotlin-side terminal surface (libghostty) shown by Dart's AndroidView. */
    private class TerminalViewFactory(private val messenger: BinaryMessenger) : PlatformViewFactory(StandardMessageCodec.INSTANCE) {
        override fun create(context: Context, viewId: Int, args: Any?): PlatformView {
            val channel = MethodChannel(messenger, "$TERMINAL_VIEW/$viewId")
            val surface = BoxTerminalFactory().create(context, viewId, args, object : TerminalChannel {
                override fun request(method: String, value: Any?, respond: (Any?) -> Unit) {
                    channel.invokeMethod(method, value, object : MethodChannel.Result {
                        override fun success(result: Any?) = respond(result)
                        override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) = respond(null)
                        override fun notImplemented() = respond(null)
                    })
                }

                override fun notify(method: String, value: Any?) {
                    channel.invokeMethod(method, value)
                }
            })
            channel.setMethodCallHandler { call, result ->
                try {
                    surface.invoke(call.method, call.arguments)
                    result.success(null)
                } catch (error: Exception) {
                    result.error("terminal", error.message, null)
                }
            }
            return object : PlatformView {
                override fun getView() = surface.getView()
                override fun dispose() {
                    channel.setMethodCallHandler(null)
                    surface.dispose()
                }
            }
        }
    }

    companion object {
        private const val CHANNEL = "io.fkbox/bridge"
        private const val EVENTS = "io.fkbox/events"
        const val TERMINAL_VIEW = "io.fkbox/terminal"
        private const val LEGACY_MIGRATED = "legacyMigrated"
        private const val OVERRIDE_PREFIX = "core."

        @Volatile
        private var instance: FkBoxPlugin? = null

        private val preferences by lazy { GlobalState.application.getSharedPreferences("fkbox_ui", 0) }

        /** The core chosen for a FlClash profile (by its id), or null for automatic. */
        fun coreOverride(id: String): String? = preferences.getString(OVERRIDE_PREFIX + id, null)
            ?.takeIf { it != TypedProfile.CORE_AUTO && it in TypedProfile.CORES }

        fun coreOverrides(): Map<String, String> = preferences.all.mapNotNull { (key, value) ->
            if (key.startsWith(OVERRIDE_PREFIX) && value is String && value != TypedProfile.CORE_AUTO) key.removePrefix(OVERRIDE_PREFIX) to value else null
        }.toMap()

        fun setCoreOverride(id: String, core: String) {
            preferences.edit().apply {
                if (core == TypedProfile.CORE_AUTO) remove(OVERRIDE_PREFIX + id) else putString(OVERRIDE_PREFIX + id, core)
            }.apply()
        }

        /**
         * The core for a profile at [path] (FlClash names profile files by id): the chosen core when one is
         * set and fits the content (sing-box and Xray both read JSON), otherwise the detected one.
         */
        fun effectiveCore(path: String, content: String): String {
            val detected = Libbox.detectCoreConfig(content)
            val chosen = coreOverride(File(path).nameWithoutExtension) ?: return detected
            val json = detected == TypedProfile.CORE_SING_BOX || detected == TypedProfile.CORE_XRAY
            return when {
                chosen == detected -> detected
                json && (chosen == TypedProfile.CORE_SING_BOX || chosen == TypedProfile.CORE_XRAY) -> chosen
                else -> detected
            }
        }

        /** Tells Dart (lib/fkbox/fkbox.dart) that FK-box's activity queued an intent to take. */
        fun notifyIntent() {
            val plugin = instance ?: return
            plugin.scope.launch { plugin.channel.invokeMethod("intentAvailable", null) }
        }

        @Volatile
        private var linksCore: Pair<String, Long>? = null

        /** The core the next share-link download or import is converted for (see FkCore.validateConfig). */
        fun expectLinksCore(core: String?) {
            linksCore = core?.takeIf { it in TypedProfile.CORES && it != TypedProfile.CORE_AUTO }?.let { it to System.currentTimeMillis() }
        }

        fun takeLinksCore(): String? {
            val value = linksCore ?: return null
            linksCore = null
            return value.first.takeIf { System.currentTimeMillis() - value.second < 120_000 }
        }

        /** Hands the further configs of a split JSON array to Dart, which adds each as its own profile. */
        fun publishSplit(firstRemarks: String, firstContent: String, extras: List<Pair<String, String>>) {
            val plugin = instance ?: return
            plugin.scope.launch {
                plugin.channel.invokeMethod(
                    "splitProfiles",
                    mapOf(
                        "first" to mapOf("label" to firstRemarks, "content" to firstContent),
                        "extras" to extras.map { (label, content) -> mapOf("label" to label, "content" to content) },
                    ),
                )
            }
        }
    }
}
