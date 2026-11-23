package io.nekohasekai.sfa.bridge

import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.OpenConnectStatusHandler
import io.nekohasekai.libbox.OpenConnectStatusUpdate
import io.nekohasekai.libbox.OpenVPNChallengeResponse
import io.nekohasekai.libbox.OpenVPNStatusHandler
import io.nekohasekai.libbox.OpenVPNStatusUpdate
import io.nekohasekai.libbox.TaildropInbox
import io.nekohasekai.libbox.TaildropInboxHandler
import io.nekohasekai.libbox.TailscaleStatusHandler
import io.nekohasekai.libbox.TailscaleStatusUpdate
import io.nekohasekai.libbox.USBIPServerStatusHandler
import io.nekohasekai.libbox.USBIPServerStatusUpdate
import io.nekohasekai.sfa.backend.tools.OpenConnectEndpointData
import io.nekohasekai.sfa.backend.tools.convertUpdate
import io.nekohasekai.sfa.utils.CommandSessions
import io.nekohasekai.sfa.utils.CommandTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import io.nekohasekai.sfa.backend.usbip.convertUpdate as convertUSBIPUpdate

/**
 * The libbox tool status subscriptions (phase 4e2): Tailscale, OpenConnect,
 * OpenVPN and USBIP. They open while mobile-ffi `sessions.rs` plans the
 * `toolStatus` target and close when it stops or moves to another session;
 * each update is converted to plain values and forwarded as a "toolStatus"
 * event (`kind`, `event`, `value`). mobile-ffi `tool_status.rs` keeps the
 * lists and checks the commands that name their entries.
 *
 * The Taildrop inbox subscription opens and closes with them as mobile-ffi
 * `taildrop.rs` asks ([openInbox], [closeInbox]); its updates carry the
 * run it gave.
 *
 * The latest OpenConnect endpoints stay here too: the sign-in WebView
 * ([BoxBrowserFactory]) needs the typed browser request.
 */
internal class BoxToolStatus(
    private val scope: CoroutineScope,
    private val emit: (String, Any?) -> Unit,
) : Closeable {
    private val closers = mutableListOf<() -> Unit>()
    private var generation = 0L

    @Volatile
    private var openConnectEndpoints: List<OpenConnectEndpointData> = emptyList()

    fun openConnectEndpoint(tag: String): OpenConnectEndpointData? = openConnectEndpoints.firstOrNull { it.endpointTag == tag }

    private val follow = scope.launch {
        CommandSessions.target(CommandSessions.Stream.ToolStatus).collect { target ->
            closeAll(target)
            if (target.connect) openAll()
        }
    }

    private fun send(kind: String, event: String, value: Any? = null) {
        emit("toolStatus", mapOf("kind" to kind, "event" to event, "value" to value))
    }

    private fun current(token: Long) = synchronized(closers) { token == generation }

    /** Keeps [close] for subscriptions of [token]; closes it at once when they were closed meanwhile. */
    private fun keep(token: Long, close: () -> Unit) {
        val kept = synchronized(closers) { (token == generation).also { if (it) closers += close } }
        if (!kept) runCatching { close() }
    }

    private var inbox: (() -> Unit)? = null

    /** Closes every subscription; mobile-ffi reopens the inbox for [target] when it wants it. */
    private fun closeAll(target: CommandSessions.Target) {
        val closing = synchronized(closers) {
            generation++
            closers.toList().also { closers.clear() } + listOfNotNull(inbox.also { inbox = null })
        }
        closing.forEach { runCatching { it() } }
        openConnectEndpoints = emptyList()
        emit("toolStatus", mapOf("kind" to "*", "event" to "reset",
            "target" to mapOf("connect" to target.connect, "remoteServerId" to target.remoteServerId)))
    }

    private fun inboxEvent(run: Long, event: String, value: Any?) {
        emit("toolStatus", mapOf("kind" to "taildrop", "event" to event, "run" to run, "value" to value))
    }

    /** Opens [endpoint]'s inbox subscription for mobile-ffi run [run], replacing any open one. */
    fun openInbox(endpoint: String, run: Long) {
        closeInbox()
        val token = synchronized(closers) { generation }
        scope.launch(Dispatchers.IO) {
            try {
                val session = CommandTarget.standaloneClient().subscribeTaildropInbox(endpoint, object : TaildropInboxHandler {
                    override fun onInboxUpdate(inbox: TaildropInbox) {
                        inboxEvent(run, "update", inboxWire(inbox))
                    }

                    override fun onError(message: String) {
                        inboxEvent(run, "error", message)
                    }
                })
                val kept = synchronized(closers) { (token == generation && inbox == null).also { if (it) inbox = { session.close() } } }
                if (!kept) runCatching { session.close() }
            } catch (e: Exception) {
                inboxEvent(run, "failed", e.message ?: e.toString())
            }
        }
    }

    fun closeInbox() {
        val close = synchronized(closers) { inbox.also { inbox = null } }
        close?.let { runCatching { it() } }
    }

    private fun subscribe(kind: String, token: Long, minAPI: Int, open: (io.nekohasekai.libbox.CommandClient) -> (() -> Unit)) {
        scope.launch(Dispatchers.IO) {
            try {
                val client = CommandTarget.standaloneClient()
                if (minAPI > 0 && client.getAPIVersion() < minAPI) {
                    if (current(token)) send(kind, "failed")
                    return@launch
                }
                keep(token, open(client))
            } catch (_: Exception) {
                if (current(token)) send(kind, "failed")
            }
        }
    }

    private fun openAll() {
        val token = synchronized(closers) { generation }
        subscribe("tailscale", token, 0) { client ->
            val session = client.subscribeTailscaleStatus(object : TailscaleStatusHandler {
                override fun onStatusUpdate(status: TailscaleStatusUpdate) {
                    val wire = convertUpdate(status).map { it.toWire() }
                    if (current(token)) send("tailscale", "update", wire)
                }

                override fun onError(message: String) {
                    if (current(token)) send("tailscale", "error", message)
                }
            })
            ({ session.close() })
        }
        subscribe("openconnect", token, MIN_API_VERSION_OPENCONNECT) { client ->
            val session = client.subscribeOpenConnectStatus(object : OpenConnectStatusHandler {
                override fun onStatusUpdate(status: OpenConnectStatusUpdate) {
                    val endpoints = convertUpdate(status)
                    if (!current(token)) return
                    openConnectEndpoints = endpoints
                    send("openconnect", "update", endpoints.map { it.toWire() })
                }

                override fun onError(message: String) {
                    if (!current(token)) return
                    openConnectEndpoints = emptyList()
                    send("openconnect", "error", message)
                }
            })
            ({ session.close() })
        }
        subscribe("openvpn", token, MIN_API_VERSION_OPENVPN) { client ->
            val session = client.subscribeOpenVPNStatus(object : OpenVPNStatusHandler {
                override fun onStatusUpdate(status: OpenVPNStatusUpdate) {
                    val wire = convertUpdate(status).map { it.toWire() }
                    if (current(token)) send("openvpn", "update", wire)
                }

                override fun onError(message: String) {
                    if (current(token)) send("openvpn", "error", message)
                }
            })
            ({ session.close() })
        }
        subscribe("usbip", token, MIN_API_VERSION_USBIP) { client ->
            val session = client.subscribeUSBIPServerStatus(object : USBIPServerStatusHandler {
                override fun onStatusUpdate(status: USBIPServerStatusUpdate) {
                    val wire = convertUSBIPUpdate(status).map { it.toWire() }
                    if (current(token)) send("usbip", "update", wire)
                }

                override fun onError(message: String) {
                    if (current(token)) send("usbip", "error", message)
                }
            })
            ({ session.close() })
        }
    }

    suspend fun submitOpenConnect(endpoint: String, challengeID: String, values: Map<String, String>) = withContext(Dispatchers.IO) {
        val formValues = Libbox.newOpenConnectFormValues()
        for ((key, value) in values) formValues.add(key, value)
        CommandTarget.standaloneClient()
            .submitOpenConnectAuthResponse(endpoint, challengeID, Libbox.newOpenConnectAuthFormResponse(formValues))
    }

    suspend fun cancelOpenConnect(endpoint: String, challengeID: String) = withContext(Dispatchers.IO) {
        CommandTarget.standaloneClient().cancelOpenConnectAuthChallenge(endpoint, challengeID)
    }

    suspend fun submitOpenVPN(endpoint: String, challengeID: String, username: String, password: String, secret: String) =
        withContext(Dispatchers.IO) {
            val response = OpenVPNChallengeResponse()
            response.username = username
            response.password = password
            response.secret = secret
            CommandTarget.standaloneClient().submitOpenVPNChallengeResponse(endpoint, challengeID, response)
        }

    override fun close() {
        follow.cancel()
        val closing = synchronized(closers) {
            generation++
            closers.toList().also { closers.clear() } + listOfNotNull(inbox.also { inbox = null })
        }
        closing.forEach { runCatching { it() } }
    }

    private fun inboxWire(inbox: TaildropInbox): Map<String, Any?> {
        val files = ArrayList<Map<String, Any?>>()
        val fileIterator = inbox.files()
        while (fileIterator.hasNext()) {
            val file = fileIterator.next()
            files.add(mapOf("name" to file.name, "size" to file.size, "senderName" to file.senderName))
        }
        val receiving = ArrayList<Map<String, Any?>>()
        val receivingIterator = inbox.receiving()
        while (receivingIterator.hasNext()) {
            val file = receivingIterator.next()
            receiving.add(mapOf("name" to file.name, "size" to file.size, "receivedBytes" to file.receivedBytes,
                "senderID" to file.senderID, "senderName" to file.senderName))
        }
        return mapOf("files" to files, "receiving" to receiving)
    }

    private companion object {
        const val MIN_API_VERSION_OPENCONNECT = 3
        const val MIN_API_VERSION_OPENVPN = 3
        const val MIN_API_VERSION_USBIP = 2
    }
}
