package io.nekohasekai.sfa.bridge

// The Tools page's sessions and the endpoint status streams, over sing-box's
// official libbox CommandClient. They were Go state machines inside the
// fk-box libbox fork (core_quality_tool.go, core_stun_tool.go,
// core_tailscale_ping.go, core_endpoint_status.go); the payloads of the
// "quality", "stun", "ping" and "tools" events are unchanged.
//
// Each tool runs one session at a time. A start takes a generation; a stop
// moves to the next one, so callbacks and sessions of an older run are ignored
// (late sessions are closed).

import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.NetworkQualityProgress
import io.nekohasekai.libbox.NetworkQualityResult
import io.nekohasekai.libbox.NetworkQualityTest
import io.nekohasekai.libbox.NetworkQualityTestHandler
import io.nekohasekai.libbox.OpenConnectAuthChallenge
import io.nekohasekai.libbox.OpenConnectEndpointStatus
import io.nekohasekai.libbox.OpenConnectStatusHandler
import io.nekohasekai.libbox.OpenConnectStatusUpdate
import io.nekohasekai.libbox.OpenVPNEndpointStatus
import io.nekohasekai.libbox.OpenVPNStatusHandler
import io.nekohasekai.libbox.OpenVPNStatusUpdate
import io.nekohasekai.libbox.STUNTest
import io.nekohasekai.libbox.STUNTestHandler
import io.nekohasekai.libbox.STUNTestProgress
import io.nekohasekai.libbox.STUNTestResult
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.TailscaleEndpointStatus
import io.nekohasekai.libbox.TailscalePeer
import io.nekohasekai.libbox.TailscalePingHandler
import io.nekohasekai.libbox.TailscalePingResult
import io.nekohasekai.libbox.TailscaleStatusHandler
import io.nekohasekai.libbox.TailscaleStatusUpdate
import kotlin.concurrent.thread

/** Events raised for the UI by the Kotlin tools (protocol/methods.json event names). */
internal object UiEvents {
    @Volatile
    var sink: ((kind: String, payload: String) -> Unit)? = null

    fun emit(kind: String, payload: String) {
        sink?.invoke(kind, payload)
    }
}

/** What the tools need from the app: the command client of the running service and the error report path. */
internal interface UiToolHost {
    fun commandClient(): CommandClient

    fun reportError(message: String)
}

private fun StringIterator?.strings(): List<String> = mutableListOf<String>().also { list ->
    if (this != null) while (hasNext()) list.add(next())
}

private fun closeQuietly(close: (() -> Unit)?) {
    if (close != null) runCatching { close() }
}

/** Starts a session off the caller's thread (the CommandClient call blocks on gRPC). */
private fun background(name: String, block: () -> Unit) {
    thread(name = name, isDaemon = true) { block() }
}

/** Sends a payload only when it differs from the last one sent (the old StateFlow semantics). */
private class Emitter(private val kind: String) {
    private var last: String? = null

    fun send(payload: String) {
        if (payload == last) return
        last = payload
        UiEvents.emit(kind, payload)
    }
}

// --- Network quality ----------------------------------------------------------

internal object UiQualityTool {
    private class State(
        var phase: Int = -1,
        var idleLatencyMs: Int = 0,
        var downloadCapacity: Long = 0,
        var uploadCapacity: Long = 0,
        var downloadRPM: Int = 0,
        var uploadRPM: Int = 0,
        var isRunning: Boolean = false,
        var configURL: String = Libbox.NetworkQualityDefaultConfigURL,
        var serial: Boolean = false,
        var http3: Boolean = false,
        var showMeteredWarning: Boolean = false,
        var maxRuntime: Int = 30,
        var outbound: String = "",
    ) {
        fun wire(): String = SlintHost.jsonText(linkedMapOf(
            "phase" to phase, "idleLatencyMs" to idleLatencyMs, "downloadCapacity" to downloadCapacity,
            "uploadCapacity" to uploadCapacity, "downloadRPM" to downloadRPM, "uploadRPM" to uploadRPM,
            "isRunning" to isRunning, "configURL" to configURL, "serial" to serial, "http3" to http3,
            "showMeteredWarning" to showMeteredWarning,
        ))
    }

    private val lock = Any()
    private var state = State()
    private var generation = 0L
    private var session: (() -> Unit)? = null
    private var standalone: NetworkQualityTest? = null
    private val emitter = Emitter("quality")

    private fun changedLocked() = emitter.send(state.wire())

    fun payload(): String = synchronized(lock) { state.wire() }

    fun running(): Boolean = synchronized(lock) { state.isRunning }

    /** tools.startQuality: records the options, then asks on a metered network or starts. */
    fun request(running: Boolean, metered: Boolean, configURL: String, outbound: String, serial: Boolean, http3: Boolean, seconds: Int, host: UiToolHost) {
        synchronized(lock) {
            check(!state.isRunning) { "A network quality test is already running" }
            state.configURL = configURL
            state.outbound = outbound
            state.serial = serial
            state.http3 = http3
            state.maxRuntime = if (seconds == 60) 60 else 30
            if (metered) {
                state.showMeteredWarning = true
                changedLocked()
                return
            }
            changedLocked()
        }
        start(running, host)
    }

    fun confirm(running: Boolean, host: UiToolHost) {
        synchronized(lock) {
            state.showMeteredWarning = false
            changedLocked()
        }
        start(running, host)
    }

    fun dismiss() = synchronized(lock) {
        state.showMeteredWarning = false
        changedLocked()
    }

    private fun start(running: Boolean, host: UiToolHost) {
        val previous: (() -> Unit)?
        val current: Long
        val configURL: String
        val outbound: String
        val serial: Boolean
        val http3: Boolean
        val maxRuntime: Int
        var runner: NetworkQualityTest? = null
        synchronized(lock) {
            state.phase = -1
            state.idleLatencyMs = 0
            state.downloadCapacity = 0
            state.uploadCapacity = 0
            state.downloadRPM = 0
            state.uploadRPM = 0
            state.isRunning = true
            changedLocked()
            generation++
            current = generation
            previous = session
            session = null
            configURL = state.configURL
            outbound = state.outbound
            serial = state.serial
            http3 = state.http3
            maxRuntime = state.maxRuntime
            if (!running) {
                runner = Libbox.newNetworkQualityTest()
                standalone = runner
            }
        }
        closeQuietly(previous)
        val handler = Handler(current, host)
        runner?.let {
            it.start(configURL, serial, maxRuntime, http3, handler)
            return
        }
        background("quality") {
            val result = runCatching { host.commandClient().startNetworkQualityTest(configURL, outbound, serial, maxRuntime, http3, handler) }
            val started = result.getOrNull()
            synchronized(lock) {
                if (started == null) {
                    if (current != generation || !state.isRunning) return@background
                    state.isRunning = false
                    changedLocked()
                } else if (current == generation) {
                    session = { started.close() }
                    return@background
                }
            }
            if (started == null) {
                host.reportError(result.exceptionOrNull()?.message ?: "network quality test failed")
            } else {
                closeQuietly { started.close() }
            }
        }
    }

    /** Ends the current test; disconnected also forgets the outbound, reset returns to the initial state. */
    fun stopWith(disconnected: Boolean, reset: Boolean) {
        val previous: (() -> Unit)?
        val runner: NetworkQualityTest?
        synchronized(lock) {
            generation++
            previous = session
            session = null
            runner = standalone
            standalone = null
        }
        closeQuietly(previous)
        runner?.let { runCatching { it.cancel() } }
        synchronized(lock) {
            if (reset) {
                state = State()
            } else {
                state.isRunning = false
                if (disconnected) state.outbound = ""
            }
            changedLocked()
        }
    }

    private class Handler(private val current: Long, private val host: UiToolHost) : NetworkQualityTestHandler {
        private fun currentLocked() = current == generation && state.isRunning

        override fun onProgress(progress: NetworkQualityProgress?) {
            progress ?: return
            synchronized(lock) {
                if (!currentLocked()) return
                state.phase = progress.phase
                state.idleLatencyMs = progress.idleLatencyMs
                state.downloadCapacity = progress.downloadCapacity
                state.uploadCapacity = progress.uploadCapacity
                state.downloadRPM = progress.getDownloadRPM()
                state.uploadRPM = progress.getUploadRPM()
                changedLocked()
            }
        }

        override fun onResult(result: NetworkQualityResult?) {
            result ?: return
            synchronized(lock) {
                if (!currentLocked()) return
                state.phase = Libbox.NetworkQualityPhaseDone
                state.idleLatencyMs = result.idleLatencyMs
                state.downloadCapacity = result.downloadCapacity
                state.uploadCapacity = result.uploadCapacity
                state.downloadRPM = result.getDownloadRPM()
                state.uploadRPM = result.getUploadRPM()
                state.isRunning = false
                changedLocked()
                standalone = null
                session = null
            }
        }

        override fun onError(message: String?) {
            synchronized(lock) {
                if (!currentLocked()) return
                state.isRunning = false
                changedLocked()
                standalone = null
                session = null
            }
            host.reportError(message ?: "network quality test failed")
        }
    }
}

// --- STUN -----------------------------------------------------------------------

internal object UiStunTool {
    private class State(
        var phase: Int = -1,
        var externalAddr: String = "",
        var latencyMs: Int = 0,
        var natTypeSupported: Boolean = false,
        var isRunning: Boolean = false,
        var server: String = Libbox.STUNDefaultServer,
        var natMapping: Int = 0,
        var natFiltering: Int = 0,
    ) {
        fun fields(): LinkedHashMap<String, Any?> = linkedMapOf(
            "phase" to phase, "externalAddr" to externalAddr, "latencyMs" to latencyMs,
            "natTypeSupported" to natTypeSupported, "isRunning" to isRunning, "server" to server,
        )

        fun wire(): String = SlintHost.jsonText(fields().apply {
            put("mapping", Libbox.formatNATMapping(natMapping))
            put("filtering", Libbox.formatNATFiltering(natFiltering))
        })
    }

    private val lock = Any()
    private var state = State()
    private var generation = 0L
    private var session: (() -> Unit)? = null
    private var standalone: STUNTest? = null
    private val emitter = Emitter("stun")

    private fun changedLocked() = emitter.send(state.wire())

    fun payload(): String = synchronized(lock) { state.wire() }

    /** The state for diagnostics, without the NAT texts. */
    fun diagnostics(): String = synchronized(lock) { SlintHost.jsonText(state.fields()) }

    fun start(running: Boolean, server: String, outbound: String, host: UiToolHost) {
        val previous: (() -> Unit)?
        val current: Long
        var runner: STUNTest? = null
        synchronized(lock) {
            check(!state.isRunning) { "A STUN test is already running" }
            state = State(isRunning = true, server = server)
            changedLocked()
            generation++
            current = generation
            previous = session
            session = null
            if (!running) {
                runner = Libbox.newSTUNTest()
                standalone = runner
            }
        }
        closeQuietly(previous)
        val handler = Handler(current, host)
        runner?.let {
            it.start(server, handler)
            return
        }
        background("stun") {
            val result = runCatching { host.commandClient().startSTUNTest(server, outbound, handler) }
            val started = result.getOrNull()
            synchronized(lock) {
                if (started == null) {
                    if (current != generation || !state.isRunning) return@background
                    state.isRunning = false
                    changedLocked()
                } else if (current == generation) {
                    session = { started.close() }
                    return@background
                }
            }
            if (started == null) {
                host.reportError(result.exceptionOrNull()?.message ?: "STUN test failed")
            } else {
                closeQuietly { started.close() }
            }
        }
    }

    fun stop() = stopWith(false)

    /** Ends the current test; reset also returns to the initial state. */
    fun stopWith(reset: Boolean) {
        val previous: (() -> Unit)?
        val runner: STUNTest?
        synchronized(lock) {
            generation++
            previous = session
            session = null
            runner = standalone
            standalone = null
        }
        closeQuietly(previous)
        runner?.let { runCatching { it.cancel() } }
        synchronized(lock) {
            if (reset) state = State() else state.isRunning = false
            changedLocked()
        }
    }

    private class Handler(private val current: Long, private val host: UiToolHost) : STUNTestHandler {
        private fun currentLocked() = current == generation && state.isRunning

        override fun onProgress(progress: STUNTestProgress?) {
            progress ?: return
            synchronized(lock) {
                if (!currentLocked()) return
                state.phase = progress.phase
                state.externalAddr = progress.externalAddr
                state.latencyMs = progress.latencyMs
                state.natMapping = progress.getNATMapping()
                state.natFiltering = progress.getNATFiltering()
                changedLocked()
            }
        }

        override fun onResult(result: STUNTestResult?) {
            result ?: return
            synchronized(lock) {
                if (!currentLocked()) return
                state.phase = Libbox.STUNPhaseDone
                state.isRunning = false
                state.externalAddr = result.externalAddr
                state.latencyMs = result.latencyMs
                state.natMapping = result.getNATMapping()
                state.natFiltering = result.getNATFiltering()
                state.natTypeSupported = result.getNATTypeSupported()
                changedLocked()
                standalone = null
                session = null
            }
        }

        override fun onError(message: String?) {
            synchronized(lock) {
                if (!currentLocked()) return
                state.isRunning = false
                changedLocked()
                standalone = null
                session = null
            }
            host.reportError(message ?: "STUN test failed")
        }
    }
}

// --- Tailscale ping ---------------------------------------------------------------

internal object UiPingTool {
    private class State(
        var isRunning: Boolean = false,
        var latencyMs: Double = 0.0,
        var isDirect: Boolean = false,
        var derpRegionCode: String = "",
        var endpoint: String = "",
        var error: String = "",
    ) {
        // A non-finite latency is sent as null (SlintHost.jsonText).
        fun wire(): String = SlintHost.jsonText(linkedMapOf(
            "isRunning" to isRunning, "latencyMs" to latencyMs, "isDirect" to isDirect,
            "derpRegionCode" to derpRegionCode, "endpoint" to endpoint, "error" to error,
        ))
    }

    private val lock = Any()
    private var state = State()
    private var generation = 0L
    private var session: (() -> Unit)? = null
    private val emitter = Emitter("ping")

    private fun changedLocked() = emitter.send(state.wire())

    fun payload(): String = synchronized(lock) { state.wire() }

    /** Pings peerIP through endpoint, replacing any running ping. Start failures only stop the ping. */
    fun start(endpoint: String, peerIP: String, host: UiToolHost) {
        val previous: (() -> Unit)?
        val current: Long
        synchronized(lock) {
            state.isRunning = true
            state.error = ""
            changedLocked()
            generation++
            current = generation
            previous = session
            session = null
        }
        closeQuietly(previous)
        val handler = Handler(current)
        background("tailscale-ping") {
            val started = runCatching { host.commandClient().startTailscalePing(endpoint, peerIP, handler) }.getOrNull()
            synchronized(lock) {
                if (started == null) {
                    if (current == generation && state.isRunning) {
                        state.isRunning = false
                        changedLocked()
                    }
                    return@background
                }
                if (current == generation) {
                    session = { started.close() }
                    return@background
                }
            }
            closeQuietly { started.close() }
        }
    }

    fun stopWith(reset: Boolean) {
        val previous: (() -> Unit)?
        synchronized(lock) {
            generation++
            previous = session
            session = null
        }
        closeQuietly(previous)
        synchronized(lock) {
            if (reset) state = State() else state.isRunning = false
            changedLocked()
        }
    }

    private class Handler(private val current: Long) : TailscalePingHandler {
        private fun currentLocked() = current == generation && state.isRunning

        override fun onPingResult(result: TailscalePingResult?) {
            result ?: return
            synchronized(lock) {
                if (!currentLocked()) return
                if (!result.error.isNullOrEmpty()) {
                    state.error = result.error
                    changedLocked()
                    return
                }
                state.latencyMs = result.latencyMs
                state.isDirect = result.isDirect
                state.derpRegionCode = result.getDERPRegionCode()
                state.endpoint = result.endpoint
                state.error = ""
                changedLocked()
            }
        }

        override fun onError(message: String?) {
            synchronized(lock) {
                if (!currentLocked()) return
                state.isRunning = false
                changedLocked()
                session = null
            }
        }
    }
}

// --- Endpoint status (Tailscale, OpenConnect, OpenVPN) and the "tools" event --------

internal object UiEndpointStatus {
    private const val TAILSCALE = 0
    private const val OPEN_CONNECT = 1
    private const val OPEN_VPN = 2
    private const val COUNT = 3

    /** Status streams before this API version do not exist (OpenConnect, OpenVPN). */
    private const val MIN_API_VERSION = 3

    private class Slot(var subscribed: Boolean = false, var generation: Long = 0, var session: (() -> Unit)? = null)

    private val lock = Any()
    private var tailscale: List<TailscaleEndpointStatus> = emptyList()
    private var openConnect: List<OpenConnectEndpointStatus> = emptyList()
    private var openVPN: List<OpenVPNEndpointStatus> = emptyList()
    private var usbip: GoWire = GoWire("[]")
    private val slots = Array(COUNT) { Slot() }
    private val emitter = Emitter("tools")

    private fun clearLocked(kind: Int) {
        when (kind) {
            TAILSCALE -> tailscale = emptyList()
            OPEN_CONNECT -> openConnect = emptyList()
            else -> openVPN = emptyList()
        }
    }

    private fun changedLocked() = emitter.send(payloadLocked())

    /** Opens every status stream that is not open yet. */
    fun subscribe(host: UiToolHost) {
        for (kind in 0 until COUNT) subscribeKind(kind, host)
    }

    private fun subscribeKind(kind: Int, host: UiToolHost) {
        val current: Long
        synchronized(lock) {
            val slot = slots[kind]
            if (slot.subscribed) return
            slot.subscribed = true
            slot.generation++
            current = slot.generation
        }
        background("endpoint-status") {
            val opened = runCatching { open(kind, host, current) }.getOrNull()
            val slot = slots[kind]
            synchronized(lock) {
                if (opened == null) {
                    // Open failures and old services were never reported.
                    if (current == slot.generation) {
                        slot.subscribed = false
                        clearLocked(kind)
                        changedLocked()
                    }
                    return@background
                }
                if (current == slot.generation && slot.subscribed) {
                    slot.session = opened
                    return@background
                }
            }
            closeQuietly(opened)
        }
    }

    private fun open(kind: Int, host: UiToolHost, current: Long): (() -> Unit)? {
        val client = host.commandClient()
        if (kind != TAILSCALE && client.getAPIVersion() < MIN_API_VERSION) return null
        return when (kind) {
            TAILSCALE -> client.subscribeTailscaleStatus(object : TailscaleStatusHandler {
                override fun onStatusUpdate(status: TailscaleStatusUpdate?) {
                    val endpoints = mutableListOf<TailscaleEndpointStatus>()
                    status?.endpoints()?.let { while (it.hasNext()) it.next()?.let(endpoints::add) }
                    apply(kind, current) { tailscale = endpoints }
                }

                override fun onError(message: String?) = failed(kind, current, host, message)
            }).let { session -> { session.close() } }
            OPEN_CONNECT -> client.subscribeOpenConnectStatus(object : OpenConnectStatusHandler {
                override fun onStatusUpdate(status: OpenConnectStatusUpdate?) {
                    val endpoints = mutableListOf<OpenConnectEndpointStatus>()
                    status?.endpoints()?.let { while (it.hasNext()) it.next()?.let(endpoints::add) }
                    apply(kind, current) { openConnect = endpoints }
                }

                override fun onError(message: String?) = failed(kind, current, host, message)
            }).let { session -> { session.close() } }
            else -> client.subscribeOpenVPNStatus(object : OpenVPNStatusHandler {
                override fun onStatusUpdate(status: OpenVPNStatusUpdate?) {
                    val endpoints = mutableListOf<OpenVPNEndpointStatus>()
                    status?.endpoints()?.let { while (it.hasNext()) it.next()?.let(endpoints::add) }
                    apply(kind, current) { openVPN = endpoints }
                }

                override fun onError(message: String?) = failed(kind, current, host, message)
            }).let { session -> { session.close() } }
        }
    }

    private fun currentLocked(kind: Int, current: Long): Boolean = slots[kind].let { current == it.generation && it.subscribed }

    private fun apply(kind: Int, current: Long, update: () -> Unit) = synchronized(lock) {
        if (!currentLocked(kind, current)) return@synchronized
        update()
        changedLocked()
    }

    private fun failed(kind: Int, current: Long, host: UiToolHost, message: String?) {
        synchronized(lock) {
            if (!currentLocked(kind, current)) return
            slots[kind].subscribed = false
            slots[kind].session = null
            clearLocked(kind)
            changedLocked()
        }
        host.reportError(message ?: "status stream failed")
    }

    /** Closes every stream and drops their endpoints. */
    fun cancel() {
        val sessions = mutableListOf<() -> Unit>()
        synchronized(lock) {
            for (kind in 0 until COUNT) {
                val slot = slots[kind]
                slot.generation++
                slot.subscribed = false
                slot.session?.let(sessions::add)
                slot.session = null
                clearLocked(kind)
            }
            changedLocked()
        }
        sessions.forEach(::closeQuietly)
    }

    /** The USBIP servers part of the payload (a JSON array). */
    fun setUSBIP(content: String) = synchronized(lock) {
        usbip = GoWire(content)
        changedLocked()
    }

    fun payload(): String = synchronized(lock) { payloadLocked() }

    private fun payloadLocked(): String = SlintHost.jsonText(linkedMapOf(
        "tailscale" to tailscale.map(::tailscaleWire),
        "openconnect" to openConnect.map(::openConnectWire),
        "openvpn" to openVPN.map(::openVPNWire),
        "usbip" to usbip,
    ))

    // Wire shapes (the old BoxToolWire.kt toWire() maps).

    private fun peerID(peer: TailscalePeer): String = peer.getDNSName().ifEmpty { peer.hostName }

    private fun peerWire(peer: TailscalePeer?): Map<String, Any?>? = peer?.let {
        linkedMapOf(
            "id" to peerID(it), "stableID" to it.stableID, "hostName" to it.hostName, "dnsName" to it.getDNSName(),
            "os" to it.getOS(), "tailscaleIPs" to it.tailscaleIPs().strings(), "sshHostKeys" to it.sshHostKeys().strings(),
            "online" to it.online, "exitNode" to it.exitNode, "exitNodeOption" to it.exitNodeOption, "active" to it.active,
            "rxBytes" to it.rxBytes, "txBytes" to it.txBytes, "canReceiveFiles" to it.canReceiveFiles,
        )
    }

    private fun tailscaleWire(endpoint: TailscaleEndpointStatus): Map<String, Any?> {
        val groups = mutableListOf<Map<String, Any?>>()
        val iterator = endpoint.userGroups()
        while (iterator.hasNext()) {
            val group = iterator.next() ?: continue
            val peers = mutableListOf<Map<String, Any?>?>()
            val peerIterator = group.peers()
            while (peerIterator.hasNext()) peers.add(peerWire(peerIterator.next()))
            groups.add(linkedMapOf("id" to group.userID, "loginName" to group.loginName, "displayName" to group.displayName, "peers" to peers))
        }
        return linkedMapOf(
            "endpointTag" to endpoint.endpointTag, "stateText" to endpoint.stateText, "authURL" to endpoint.authURL,
            "networkName" to endpoint.networkName, "selfPeer" to peerWire(endpoint.getSelf()), "exitNode" to peerWire(endpoint.exitNode),
            "userGroups" to groups, "canShareFiles" to endpoint.canShareFiles, "waitingFileCount" to endpoint.waitingFileCount,
        )
    }

    private fun openConnectWire(endpoint: OpenConnectEndpointStatus): Map<String, Any?> {
        val challenge = endpoint.authChallenge?.let { c ->
            val form = c.form?.let { form ->
                val fields = mutableListOf<Map<String, Any?>>()
                val fieldIterator = form.fields()
                while (fieldIterator.hasNext()) {
                    val field = fieldIterator.next() ?: continue
                    val options = mutableListOf<Map<String, Any?>>()
                    val optionIterator = field.options()
                    while (optionIterator.hasNext()) optionIterator.next()?.let { options.add(linkedMapOf("value" to it.value, "label" to it.label)) }
                    fields.add(linkedMapOf(
                        "submissionKey" to field.submissionKey, "name" to field.name, "label" to field.label,
                        "kind" to field.kind, "value" to field.value, "options" to options,
                    ))
                }
                linkedMapOf("fields" to fields)
            }
            linkedMapOf(
                "id" to c.id, "banner" to c.banner, "message" to c.message, "error" to c.error,
                "form" to form, "browser" to c.browser?.let { linkedMapOf("url" to it.url) },
            )
        }
        val tunnel = endpoint.tunnelInfo?.let { info ->
            linkedMapOf(
                "server" to info.server, "flavor" to info.flavor, "transport" to info.transport,
                "ipv4" to info.iPv4().strings(), "ipv6" to info.iPv6().strings(), "dns" to info.dns().strings(),
                "mtu" to info.mtu, "connectedSince" to info.connectedSince,
            )
        }
        return linkedMapOf(
            "endpointTag" to endpoint.endpointTag, "state" to endpoint.state, "stateText" to endpoint.stateText,
            "authChallenge" to challenge, "error" to endpoint.error, "tunnelInfo" to tunnel,
        )
    }

    private fun openVPNWire(endpoint: OpenVPNEndpointStatus): Map<String, Any?> {
        val challenge = endpoint.challenge?.let { c ->
            linkedMapOf(
                "id" to c.id, "kind" to c.kind, "username" to c.username, "message" to c.message, "url" to c.url,
                "secretMessage" to c.secretMessage, "echo" to c.echo, "previousError" to c.previousError, "deadline" to c.deadline,
            )
        }
        val tunnel = endpoint.tunnelInfo?.let { info ->
            linkedMapOf(
                "server" to info.server, "network" to info.network, "cipher" to info.cipher,
                "ipv4" to info.iPv4().strings(), "ipv6" to info.iPv6().strings(), "dns" to info.dns().strings(),
                "mtu" to info.mtu, "connectedSince" to info.connectedSince,
            )
        }
        return linkedMapOf(
            "endpointTag" to endpoint.endpointTag, "state" to endpoint.state, "stateText" to endpoint.stateText,
            "challenge" to challenge, "error" to endpoint.error, "tunnelInfo" to tunnel,
        )
    }

    // Lookups.

    private fun tailscaleEndpoint(tag: String): TailscaleEndpointStatus? = synchronized(lock) { tailscale.firstOrNull { it.endpointTag == tag } }

    fun tailscaleHasEndpoint(tag: String): Boolean = tailscaleEndpoint(tag) != null

    private fun forEachPeer(endpoint: TailscaleEndpointStatus, visit: (TailscalePeer) -> Boolean): TailscalePeer? {
        val groups = endpoint.userGroups()
        while (groups.hasNext()) {
            val peers = groups.next()?.peers() ?: continue
            while (peers.hasNext()) {
                val peer = peers.next() ?: continue
                if (visit(peer)) return peer
            }
        }
        return null
    }

    /** Whether a peer of the endpoint with this stable ID can be used as exit node. */
    fun tailscaleIsExitNodeOption(tag: String, stableID: String): Boolean {
        val endpoint = tailscaleEndpoint(tag) ?: return false
        return forEachPeer(endpoint) { it.stableID == stableID && it.exitNodeOption } != null
    }

    /** A peer (or the endpoint's own node) by its UI id (DNS name, else host name); null when gone. */
    fun tailscalePeer(tag: String, peerID: String): TailscalePeer? {
        val endpoint = tailscaleEndpoint(tag) ?: return null
        endpoint.getSelf()?.let { if (peerID(it) == peerID) return it }
        return forEachPeer(endpoint) { peerID(it) == peerID }
    }

    /** The old TailscalePeerData.displayName. */
    fun tailscalePeerDisplayName(peer: TailscalePeer?): String {
        peer ?: return ""
        return peer.getDNSName().substringBefore('.').ifEmpty { peer.hostName }
    }

    /** The endpoint's pending sign-in challenge; null when none. */
    fun openConnectChallenge(tag: String): OpenConnectAuthChallenge? = synchronized(lock) {
        openConnect.firstOrNull { it.endpointTag == tag }?.authChallenge
    }
}
