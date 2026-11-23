package io.nekohasekai.sfa.bridge

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import io.nekohasekai.sfa.backend.tools.TaildropFiles
import io.nekohasekai.sfa.backend.tools.TaildropSendManager
import io.nekohasekai.sfa.backend.tools.TaildropViewModel
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

/** A detail of the peerInfo mobile-ffi tool_status.rs adds to commands naming a Tailscale peer. */
internal fun Map<String, Any?>.string(key: String): String = this[key] as? String ?: error("Missing peer detail: $key")

internal class BoxTools(private val activity: BoxActivity, private val emit: (String, Any?) -> Unit) : Closeable {
    private val scope = activity.lifecycleScope
    private val provider = ViewModelProvider(activity)
    /** Tailscale, OpenConnect, OpenVPN and USBIP status (mobile-ffi tool_status.rs keeps the lists). */
    val status = BoxToolStatus(scope, emit)
    /** STUN, network quality and Tailscale ping (mobile-ffi diagnostics.rs decides when). */
    val diagnostics = BoxDiagnostics(activity, scope, emit)
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
                outbounds.disconnect()
                if (sessionKey != target || !available) {
                    inbox.cancel()
                    inboxTag = null
                }
                sessionKey = target
                if (connected) {
                    outbounds.connect()
                    inboxTag?.let { inbox.subscribe(it) }
                } else {
                    inbox.cancel()
                }
            }
        }
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

    fun diagnostics(): Map<String, Any?> = diagnostics.defaults()

    private fun required(value: String?, key: String): String = value ?: error("Missing argument: " + key)

    /** mobile-ffi tool_status.rs checked that the endpoint offers this exit node. */
    suspend fun setTailscaleExitNode(endpointTag: String?, peer: String?) {
        withContext(Dispatchers.IO) { CommandTarget.standaloneClient().setTailscaleExitNode(required(endpointTag, "endpoint"), required(peer, "peer")) }
    }

    suspend fun tailscaleLogout(endpoint: String?) {
        withContext(Dispatchers.IO) { CommandTarget.standaloneClient().tailscaleLogout(required(endpoint, "endpoint")) }
    }

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

    /** [peer] is the peerInfo mobile-ffi tool_status.rs checked and added. */
    suspend fun sendShare(token: String?, endpointTag: String?, peer: Map<String, Any?>): Any? {
        val endpoint = required(endpointTag, "endpoint")
        val files = withContext(Dispatchers.IO) { activity.openShare(required(token, "token")) }
        return TaildropSendManager.send(endpoint, peer.string("stableID"), peer.string("displayName"), files)
    }

    /** Null when the user dismissed the file picker. */
    suspend fun sendTaildropFile(endpointTag: String?, peer: Map<String, Any?>): Any? {
        val endpoint = required(endpointTag, "endpoint")
        val uri = activity.pickDocument() ?: return null
        val files = withContext(Dispatchers.IO) { TaildropSendManager.openURIs(listOf(uri)) }
        return TaildropSendManager.send(endpoint, peer.string("stableID"), peer.string("displayName"), files)
    }

    suspend fun cancelTaildropSend(id: Long) { TaildropSendManager.cancel(id) }

    suspend fun dismissTaildropSend(id: Long) { TaildropSendManager.dismiss(id) }

    suspend fun submitOpenConnect(endpoint: String?, challenge: String?, values: Map<String, String>) {
        status.submitOpenConnect(required(endpoint, "endpoint"), required(challenge, "challenge"), values)
    }

    suspend fun cancelOpenConnect(endpoint: String?, challenge: String?) {
        status.cancelOpenConnect(required(endpoint, "endpoint"), required(challenge, "challenge"))
    }

    suspend fun submitOpenVPN(endpoint: String?, challenge: String?, username: String?, password: String?, secret: String?) {
        status.submitOpenVPN(required(endpoint, "endpoint"), required(challenge, "challenge"), username ?: "", password ?: "", secret ?: "")
    }

    suspend fun cancelOpenVPN(endpoint: String?, challenge: String?) {
        withContext(Dispatchers.IO) {
            CommandTarget.standaloneClient().cancelOpenVPNChallenge(required(endpoint, "endpoint"), required(challenge, "challenge"))
        }
    }

    suspend fun usbDevices() = usbController.devices()

    suspend fun attachUsb(server: String?, device: String?) {
        usbController.attach(required(server, "server"), required(device, "device"))
    }

    suspend fun detachUsb(id: String?) { USBIPManager.detach(required(id, "id")) }

    override fun close() {
        jobs.forEach { it.cancel() }
        status.close()
        outbounds.disconnect()
        inbox.cancel()
        diagnostics.close()
        usbController.close()
        ssh.close()
    }
}
