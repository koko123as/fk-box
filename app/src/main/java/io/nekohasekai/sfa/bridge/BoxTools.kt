package io.nekohasekai.sfa.bridge

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.backend.tools.MaxRuntimeOption
import io.nekohasekai.sfa.backend.tools.NetworkQualityViewModel
import io.nekohasekai.sfa.backend.tools.OpenConnectStatusViewModel
import io.nekohasekai.sfa.backend.tools.OpenVPNStatusViewModel
import io.nekohasekai.sfa.backend.tools.STUNTestViewModel
import io.nekohasekai.sfa.backend.tools.TaildropFiles
import io.nekohasekai.sfa.backend.tools.TaildropSendManager
import io.nekohasekai.sfa.backend.tools.TaildropViewModel
import io.nekohasekai.sfa.backend.tools.TailscalePingViewModel
import io.nekohasekai.sfa.backend.tools.TailscaleStatusViewModel
import io.nekohasekai.sfa.backend.usbip.USBIPStatusViewModel
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.usbip.USBIPManager
import io.nekohasekai.sfa.utils.AppLifecycleObserver
import io.nekohasekai.sfa.utils.CommandTarget
import io.nekohasekai.sfa.utils.CommandClient
import io.nekohasekai.sfa.utils.RemoteControlManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable

internal class BoxTools(private val activity: BoxActivity, private val emit: (String, Any?) -> Unit) : Closeable {
    private val scope = activity.lifecycleScope
    private val provider = ViewModelProvider(activity)
    val tailscale = provider[TailscaleStatusViewModel::class.java]
    val openConnect = provider[OpenConnectStatusViewModel::class.java]
    private val openVPN = provider[OpenVPNStatusViewModel::class.java]
    private val usb = provider[USBIPStatusViewModel::class.java]
    private val stun = provider[STUNTestViewModel::class.java]
    private val quality = provider[NetworkQualityViewModel::class.java]
    private val ping = provider[TailscalePingViewModel::class.java]
    private val inbox = provider[TaildropViewModel::class.java]
    private val jobs = mutableListOf<Job>()
    private val usbController = BoxUsb(activity)
    val ssh = BoxSSH(scope, tailscale, emit)
    val terminalSettings = BoxTerminalSettings(activity)
    private var inboxTag: String? = null
    private var sessionKey: Long? = null
    private val outbounds = CommandClient(scope, CommandClient.ConnectionType.Outbounds, object : CommandClient.Handler {
        override fun updateOutbounds(outbounds: List<io.nekohasekai.libbox.OutboundGroupItem>) {
            emit("outbounds", outbounds.map { mapOf("tag" to it.tag, "type" to it.type) })
        }
    })

    val running: Boolean
        get() = if (CommandTarget.isRemote) RemoteControlManager.isConnected.value else activity.serviceStatus.value == Status.Started

    init {
        jobs += scope.launch {
            combine(
                activity.serviceStatus, AppLifecycleObserver.isForeground,
                RemoteControlManager.remoteServer, RemoteControlManager.isConnected,
            ) { status, foreground, remote, connected ->
                val available = if (remote != null) connected else status == Status.Started
                Triple(foreground && available, available, remote?.id ?: 0L)
            }.distinctUntilChanged().collect { (connected, available, target) ->
                tailscale.cancel()
                openConnect.cancel()
                openVPN.cancel()
                usb.cancel()
                outbounds.disconnect()
                if (sessionKey != target || !available) {
                    stun.onVpnDisconnected()
                    quality.onVpnDisconnected()
                    ping.stopPing()
                    inbox.cancel()
                    inboxTag = null
                }
                sessionKey = target
                if (connected) {
                    outbounds.connect()
                    tailscale.subscribe()
                    openConnect.subscribe()
                    openVPN.subscribe()
                    usb.subscribe()
                    inboxTag?.let { inbox.subscribe(it) }
                } else {
                    inbox.cancel()
                }
            }
        }
        jobs += scope.launch { tailscale.uiState.collect { emit("tools", snapshot()) } }
        jobs += scope.launch { openConnect.uiState.collect { emit("tools", snapshot()) } }
        jobs += scope.launch { openVPN.uiState.collect { emit("tools", snapshot()) } }
        jobs += scope.launch { usb.uiState.collect { emit("tools", snapshot()) } }
        jobs += scope.launch { stun.uiState.collect { emit("stun", it.toWire() + mapOf("mapping" to Libbox.formatNATMapping(it.natMapping), "filtering" to Libbox.formatNATFiltering(it.natFiltering))) } }
        jobs += scope.launch { quality.uiState.collect { emit("quality", it.toWire()) } }
        jobs += scope.launch { ping.uiState.collect { emit("ping", it.toWire()) } }
        jobs += scope.launch { inbox.uiState.collect { emit("inbox", it.toWire()) } }
        jobs += scope.launch { TaildropSendManager.sessions.collect { sessions ->
            emit("transfers", sessions.map { session ->
                mapOf("id" to session.id, "endpointTag" to session.endpointTag, "peerName" to session.peerName,
                    "finished" to session.finished, "error" to session.errorMessage,
                    "files" to session.files.map { mapOf("name" to it.name, "size" to it.size, "sentBytes" to it.sentBytes, "completed" to it.completed) })
            })
        } }
        jobs += scope.launch { USBIPManager.state.collect { state ->
            emit("usbDevices", state.devices.map {
                mapOf("id" to it.deviceId, "server" to it.serverTag, "label" to it.label, "state" to it.state.name, "error" to it.error)
            })
        } }
    }

    fun snapshot(): Map<String, Any?> = mapOf(
        "tailscale" to tailscale.uiState.value.endpoints.map { it.toWire() },
        "openconnect" to openConnect.uiState.value.endpoints.map { it.toWire() },
        "openvpn" to openVPN.uiState.value.endpoints.map { it.toWire() },
        "usbip" to usb.uiState.value.servers.map { it.toWire() },
    )

    fun diagnostics(): Map<String, Any?> = mapOf("quality" to quality.uiState.value.toWire(), "stun" to stun.uiState.value.toWire())

    private fun required(value: String?, key: String): String = value ?: error("Missing argument: " + key)

    suspend fun startStun(server: String?, outbound: String?) {
        require(!stun.uiState.value.isRunning) { "A STUN test is already running" }
        stun.updateServer(required(server, "server"))
        stun.selectOutbound(outbound ?: "")
        stun.startTest(running)
    }

    suspend fun stopStun() { stun.cancelTest() }

    suspend fun startQuality(url: String?, outbound: String?, serial: Boolean, http3: Boolean, seconds: Int?) {
        require(!quality.uiState.value.isRunning) { "A network quality test is already running" }
        quality.updateConfigURL(BoxProfiles.validateUrl(required(url, "url")))
        quality.selectOutbound(outbound ?: "")
        quality.setSerial(serial)
        quality.setHttp3(http3)
        quality.setMaxRuntime(if (seconds == 60) MaxRuntimeOption.SIXTY else MaxRuntimeOption.THIRTY)
        quality.requestStartTest(activity, running)
    }

    suspend fun confirmQuality() { quality.confirmMeteredStart(running) }

    suspend fun dismissQuality() { quality.dismissMeteredWarning() }

    suspend fun stopQuality() { quality.cancelTest() }

    suspend fun setTailscaleExitNode(endpointTag: String?, peer: String?) {
        val endpoint = tailscale.endpoint(required(endpointTag, "endpoint")) ?: error("Endpoint no longer exists")
        val stableID = required(peer, "peer")
        require(stableID.isEmpty() || endpoint.userGroups.any { group -> group.peers.any { it.stableID == stableID && it.exitNodeOption } }) { "Not an available exit node" }
        withContext(Dispatchers.IO) { CommandTarget.standaloneClient().setTailscaleExitNode(endpoint.endpointTag, stableID) }
    }

    suspend fun tailscaleLogout(endpoint: String?) {
        withContext(Dispatchers.IO) { CommandTarget.standaloneClient().tailscaleLogout(required(endpoint, "endpoint")) }
    }

    suspend fun tailscalePing(endpoint: String?, peerID: String?) {
        val peer = tailscale.peer(required(endpoint, "endpoint"), required(peerID, "peer")) ?: error("Peer no longer exists")
        ping.stopPing()
        ping.startPing(required(endpoint, "endpoint"), peer.tailscaleIPs.firstOrNull() ?: error("Peer has no address"))
    }

    suspend fun stopTailscalePing() { ping.stopPing() }

    suspend fun subscribeTaildrop(endpoint: String?) {
        inboxTag = required(endpoint, "endpoint")
        inbox.subscribe(inboxTag!!)
        inbox.markInboxRead(inboxTag!!)
    }

    suspend fun unsubscribeTaildrop() { inboxTag = null; inbox.cancel() }

    suspend fun deleteTaildropFile(endpoint: String?, nameArgument: String?) {
        val name = required(nameArgument, "name")
        require(inbox.uiState.value.files.any { it.name == name }) { "File no longer exists" }
        inbox.delete(required(endpoint, "endpoint"), name)
    }

    suspend fun cancelTaildropReceive(endpoint: String?, sender: String?, name: String?) {
        inbox.cancelReceiving(required(endpoint, "endpoint"), required(sender, "sender"), required(name, "name"))
    }

    /** False when the user dismissed the file picker. */
    suspend fun saveTaildropFile(endpoint: String?, nameArgument: String?): Boolean {
        val name = required(nameArgument, "name")
        require(inbox.uiState.value.files.any { it.name == name }) { "File no longer exists" }
        val uri = activity.pickDocument(name) ?: return false
        val file = TaildropFiles.materialize(required(endpoint, "endpoint"), name)
        withContext(Dispatchers.IO) {
            activity.contentResolver.openOutputStream(uri, "wt")?.use { output -> file.inputStream().use { it.copyTo(output) } }
                ?: error("Unable to save received file")
        }
        return true
    }

    suspend fun discardShare(token: String?) { activity.discardShare(required(token, "token")) }

    suspend fun sendShare(token: String?, endpointTag: String?, peerID: String?): Any? {
        val endpoint = required(endpointTag, "endpoint")
        val peer = tailscale.peer(endpoint, required(peerID, "peer")) ?: error("Peer no longer exists")
        require(peer.canReceiveFiles && peer.online) { "Peer is not available for file transfer" }
        val files = withContext(Dispatchers.IO) { activity.openShare(required(token, "token")) }
        return TaildropSendManager.send(endpoint, peer.stableID, peer.displayName, files)
    }

    /** Null when the user dismissed the file picker. */
    suspend fun sendTaildropFile(endpointTag: String?, peerID: String?): Any? {
        val endpoint = required(endpointTag, "endpoint")
        val peer = tailscale.peer(endpoint, required(peerID, "peer")) ?: error("Peer no longer exists")
        require(peer.canReceiveFiles) { "This peer cannot receive files" }
        val uri = activity.pickDocument() ?: return null
        val files = withContext(Dispatchers.IO) { TaildropSendManager.openURIs(listOf(uri)) }
        return TaildropSendManager.send(endpoint, peer.stableID, peer.displayName, files)
    }

    suspend fun cancelTaildropSend(id: Long) { TaildropSendManager.cancel(id) }

    suspend fun dismissTaildropSend(id: Long) { TaildropSendManager.dismiss(id) }

    suspend fun submitOpenConnect(endpoint: String?, challenge: String?, values: Map<String, String>) {
        val error = openConnect.submitAuthFormResponse(required(endpoint, "endpoint"), required(challenge, "challenge"), values)
        if (error != null) kotlin.error(error)
    }

    suspend fun cancelOpenConnect(endpoint: String?, challenge: String?) {
        openConnect.cancelAuthChallenge(required(endpoint, "endpoint"), required(challenge, "challenge"))
    }

    suspend fun submitOpenVPN(endpoint: String?, challenge: String?, username: String?, password: String?, secret: String?) {
        val error = openVPN.submitChallengeResponse(required(endpoint, "endpoint"), required(challenge, "challenge"),
            username ?: "", password ?: "", secret ?: "")
        if (error != null) kotlin.error(error)
    }

    suspend fun cancelOpenVPN(endpoint: String?, challenge: String?) {
        withContext(Dispatchers.IO) {
            CommandTarget.standaloneClient().cancelOpenVPNChallenge(required(endpoint, "endpoint"), required(challenge, "challenge"))
        }
    }

    suspend fun usbDevices() = usbController.devices()

    suspend fun attachUsb(server: String?, device: String?) {
        require(usb.uiState.value.servers.any { it.serverTag == required(server, "server") }) { "USBIP server no longer exists" }
        usbController.attach(required(server, "server"), required(device, "device"))
    }

    suspend fun detachUsb(id: String?) { USBIPManager.detach(required(id, "id")) }

    override fun close() {
        jobs.forEach { it.cancel() }
        tailscale.cancel()
        openConnect.cancel()
        openVPN.cancel()
        usb.cancel()
        outbounds.disconnect()
        inbox.cancel()
        ping.stopPing()
        stun.cancelTest()
        quality.cancelTest()
        usbController.close()
        ssh.close()
    }
}
