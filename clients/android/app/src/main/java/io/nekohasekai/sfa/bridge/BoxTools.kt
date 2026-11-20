package io.nekohasekai.sfa.bridge

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.OpenVPNChallengeResponse
import io.nekohasekai.libbox.UIToolHost
import io.nekohasekai.sfa.backend.base.GlobalEventBus
import io.nekohasekai.sfa.backend.base.UiEvent
import io.nekohasekai.sfa.backend.tools.NetworkQualityViewModel
import io.nekohasekai.sfa.backend.tools.STUNTestViewModel
import io.nekohasekai.sfa.backend.tools.TaildropFiles
import io.nekohasekai.sfa.backend.tools.TaildropSendManager
import io.nekohasekai.sfa.backend.tools.TaildropViewModel
import io.nekohasekai.sfa.backend.tools.TailscalePingViewModel
import io.nekohasekai.sfa.backend.usbip.USBIPStatusViewModel
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.ktx.toList
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
    // Tailscale / OpenConnect / OpenVPN status streams and the "tools" event are
    // kept by Go (libbox core_endpoint_status.go); readers use its lookups.
    private val usb = provider[USBIPStatusViewModel::class.java]
    // Scopes the Go STUN tool to this activity (reset on create, stopped on clear).
    private val stun = provider[STUNTestViewModel::class.java]
    // Scopes the Go network quality tool to this activity (reset on create, stopped on clear).
    private val quality = provider[NetworkQualityViewModel::class.java]
    // Scopes the Go Tailscale ping to this activity (reset on create, stopped on clear).
    private val ping = provider[TailscalePingViewModel::class.java]
    private val inbox = provider[TaildropViewModel::class.java]
    private val jobs = mutableListOf<Job>()
    private val usbController = BoxUsb(activity)
    val ssh = BoxSSH(scope, emit)
    val terminalSettings = BoxTerminalSettings(activity)
    private var inboxTag: String? = null
    private var sessionKey: Long? = null
    private val outbounds = CommandClient(scope, CommandClient.ConnectionType.Outbounds, object : CommandClient.Handler {
        override fun updateOutbounds(outbounds: List<io.nekohasekai.libbox.OutboundGroupItem>) {
            emit("outbounds", outbounds.map { mapOf("tag" to it.tag, "type" to it.type) })
        }
    })

    private val toolHost = object : UIToolHost {
        override fun commandClient(): io.nekohasekai.libbox.CommandClient = CommandTarget.standaloneClient()

        override fun reportError(message: String) {
            scope.launch { GlobalEventBus.emit(UiEvent.ErrorMessage(message)) }
        }
    }

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
                Libbox.uiEndpointStatusCancel()
                usb.cancel()
                outbounds.disconnect()
                if (sessionKey != target || !available) {
                    Libbox.uiStunStop()
                    Libbox.uiQualityDisconnected()
                    Libbox.uiTailscalePingStop()
                    inbox.cancel()
                    inboxTag = null
                }
                sessionKey = target
                if (connected) {
                    outbounds.connect()
                    Libbox.uiEndpointStatusSubscribe(toolHost)
                    usb.subscribe()
                    inboxTag?.let { inbox.subscribe(it) }
                } else {
                    inbox.cancel()
                }
            }
        }
        // Later "tools" payloads are sent by Go; the USBIP part is handed in here.
        Libbox.uiToolsSetUSBIP(usbWire())
        emit("tools", snapshot())
        jobs += scope.launch { usb.uiState.collect { Libbox.uiToolsSetUSBIP(usbWire()) } }
        // Later STUN states are sent by Go (the "stun" event).
        emit("stun", GoWire(Libbox.uiStunPayload()))
        // Later quality states are sent by Go (the "quality" event).
        emit("quality", GoWire(Libbox.uiQualityPayload()))
        // Later ping states are sent by Go (the "ping" event).
        emit("ping", GoWire(Libbox.uiTailscalePingPayload()))
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

    private fun usbWire(): String = SlintHost.jsonText(usb.uiState.value.servers.map { it.toWire() })

    fun snapshot(): GoWire = GoWire(Libbox.uiToolsPayload())

    fun diagnostics(): Map<String, Any?> = mapOf("quality" to GoWire(Libbox.uiQualityPayload()), "stun" to GoWire(Libbox.uiStunDiagnostics()))

    private fun required(value: String?, key: String): String = value ?: error("Missing argument: " + key)

    suspend fun startStun(server: String?, outbound: String?) {
        Libbox.uiStunStart(running, required(server, "server"), outbound ?: "", toolHost)
    }

    suspend fun stopStun() { Libbox.uiStunStop() }

    suspend fun startQuality(url: String?, outbound: String?, serial: Boolean, http3: Boolean, seconds: Int?) {
        require(!Libbox.uiQualityRunning()) { "A network quality test is already running" }
        val configURL = BoxProfiles.validateUrl(required(url, "url"))
        val connectivity = activity.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        Libbox.uiQualityStart(running, connectivity.isActiveNetworkMetered, configURL, outbound ?: "", serial, http3, seconds ?: 30, toolHost)
    }

    suspend fun confirmQuality() { Libbox.uiQualityConfirm(running, toolHost) }

    suspend fun dismissQuality() { Libbox.uiQualityDismiss() }

    suspend fun stopQuality() { Libbox.uiQualityStop() }

    suspend fun setTailscaleExitNode(endpointTag: String?, peer: String?) {
        val tag = required(endpointTag, "endpoint")
        if (!Libbox.uiTailscaleHasEndpoint(tag)) error("Endpoint no longer exists")
        val stableID = required(peer, "peer")
        require(stableID.isEmpty() || Libbox.uiTailscaleIsExitNodeOption(tag, stableID)) { "Not an available exit node" }
        withContext(Dispatchers.IO) { CommandTarget.standaloneClient().setTailscaleExitNode(tag, stableID) }
    }

    suspend fun tailscaleLogout(endpoint: String?) {
        withContext(Dispatchers.IO) { CommandTarget.standaloneClient().tailscaleLogout(required(endpoint, "endpoint")) }
    }

    suspend fun tailscalePing(endpoint: String?, peerID: String?) {
        val peer = Libbox.uiTailscalePeer(required(endpoint, "endpoint"), required(peerID, "peer")) ?: error("Peer no longer exists")
        Libbox.uiTailscalePingStart(required(endpoint, "endpoint"), peer.tailscaleIPs().toList().firstOrNull() ?: error("Peer has no address"), toolHost)
    }

    suspend fun stopTailscalePing() { Libbox.uiTailscalePingStop() }

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
        val uri = activity.pickDocument(TaildropFiles.safeName(name)) ?: return false
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
        val peer = Libbox.uiTailscalePeer(endpoint, required(peerID, "peer")) ?: error("Peer no longer exists")
        require(peer.canReceiveFiles && peer.online) { "Peer is not available for file transfer" }
        val files = withContext(Dispatchers.IO) { activity.openShare(required(token, "token")) }
        return TaildropSendManager.send(endpoint, peer.stableID, Libbox.uiTailscalePeerDisplayName(peer), files)
    }

    /** Null when the user dismissed the file picker. */
    suspend fun sendTaildropFile(endpointTag: String?, peerID: String?): Any? {
        val endpoint = required(endpointTag, "endpoint")
        val peer = Libbox.uiTailscalePeer(endpoint, required(peerID, "peer")) ?: error("Peer no longer exists")
        require(peer.canReceiveFiles) { "This peer cannot receive files" }
        val uri = activity.pickDocument() ?: return null
        val files = withContext(Dispatchers.IO) { TaildropSendManager.openURIs(listOf(uri)) }
        return TaildropSendManager.send(endpoint, peer.stableID, Libbox.uiTailscalePeerDisplayName(peer), files)
    }

    suspend fun cancelTaildropSend(id: Long) { TaildropSendManager.cancel(id) }

    suspend fun dismissTaildropSend(id: Long) { TaildropSendManager.dismiss(id) }

    suspend fun submitOpenConnect(endpoint: String?, challenge: String?, values: Map<String, String>) {
        val endpointTag = required(endpoint, "endpoint")
        val challengeID = required(challenge, "challenge")
        val error = withContext(Dispatchers.IO) {
            try {
                val formValues = Libbox.newOpenConnectFormValues()
                for ((key, value) in values) {
                    formValues.add(key, value)
                }
                val response = Libbox.newOpenConnectAuthFormResponse(formValues)
                CommandTarget.standaloneClient().submitOpenConnectAuthResponse(endpointTag, challengeID, response)
                null
            } catch (e: Exception) {
                e.message ?: "submit auth form failed"
            }
        }
        if (error != null) kotlin.error(error)
    }

    suspend fun cancelOpenConnect(endpoint: String?, challenge: String?) {
        val endpointTag = required(endpoint, "endpoint")
        val challengeID = required(challenge, "challenge")
        scope.launch(Dispatchers.IO) {
            try {
                CommandTarget.standaloneClient().cancelOpenConnectAuthChallenge(endpointTag, challengeID)
            } catch (exception: Exception) {
                toolHost.reportError(exception.message ?: "cancel authentication failed")
            }
        }
    }

    suspend fun submitOpenVPN(endpoint: String?, challenge: String?, username: String?, password: String?, secret: String?) {
        val endpointTag = required(endpoint, "endpoint")
        val challengeID = required(challenge, "challenge")
        val error = withContext(Dispatchers.IO) {
            try {
                val response = OpenVPNChallengeResponse()
                response.username = username ?: ""
                response.password = password ?: ""
                response.secret = secret ?: ""
                CommandTarget.standaloneClient().submitOpenVPNChallengeResponse(endpointTag, challengeID, response)
                null
            } catch (e: Exception) {
                e.message ?: "submit challenge response failed"
            }
        }
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
        Libbox.uiEndpointStatusCancel()
        usb.cancel()
        outbounds.disconnect()
        inbox.cancel()
        Libbox.uiTailscalePingStop()
        Libbox.uiStunStop()
        Libbox.uiQualityStop()
        usbController.close()
        ssh.close()
    }
}
