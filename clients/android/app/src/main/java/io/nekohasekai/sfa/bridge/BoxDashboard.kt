package io.nekohasekai.sfa.bridge

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.lifecycleScope
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.sfa.fkcore.FkLib
import io.nekohasekai.sfa.bg.BoxService
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.ProfileContent
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.utils.AppLifecycleObserver
import io.nekohasekai.sfa.utils.CommandTarget
import io.nekohasekai.sfa.utils.RemoteControlManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import java.io.Closeable
import java.io.File
import java.net.NetworkInterface

private val nextMemoryOwner = java.util.concurrent.atomic.AtomicInteger()

internal class BoxDashboard(private val activity: BoxActivity, private val emit: (String, Any?) -> Unit) : Closeable {
    /** The remote core's memory (its status stream); shown by the Memory card under remote control. */
    @Volatile var remoteMemory = 0L
        set(value) { field = value; pushMemorySource() }
    private var networkSnapshot: Map<String, Any?>? = null
    private val networkMutex = Mutex()
    private val configCache = DashboardConfigCache(ProfileContent::revision, FkLib::detectCoreConfig) { content, addresses ->
        val values = JSONArray(FkLib.readUILANProxyListeners(content, addresses).value)
        (0 until values.length()).map { jsonObject(values.getJSONObject(it)) }
    }
    private class EgressSession(val server: CommandServer?) {
        @Volatile var closed = false
            private set
        private var probe: EgressProbe? = null
        fun attach(value: EgressProbe) = synchronized(this) {
            if (closed) { value.close(); throw CancellationException("IP probe cancelled") }
            probe = value
        }
        fun get(): EgressProbe = synchronized(this) { probe ?: error("IP probe is not available") }
        fun close() {
            val value = synchronized(this) { closed = true; probe.also { probe = null } }
            value?.close()
        }
    }
    private val egressSessions = mutableMapOf<String, EgressSession>()
    private val serviceChanges = activity.lifecycleScope.launch {
        activity.serviceStatus.collect {
            pushMemorySource()
            egressSessions.filterValues { !isCurrent(it) }.keys.toList().forEach(::closeProbe)
        }
    }
    private val remoteChanges = activity.lifecycleScope.launch {
        RemoteControlManager.remoteServer.collect { pushMemorySource() }
    }
    private val qualityCache = activity.getSharedPreferences("dashboard_ip_quality", 0)
    // libbox's dashboard stream (core_dashboard_stream.go) samples memory and
    // sends "memory" every 2 s while some owner is subscribed, in the
    // foreground and with the screen on; this bridge only reports its signals.
    private val memoryOwner = nextMemoryOwner.incrementAndGet()
    private val memoryVisible = MutableStateFlow(false)
    private val memorySignals = activity.lifecycleScope.launch {
        try {
            combine(memoryVisible, AppLifecycleObserver.isForeground, AppLifecycleObserver.isScreenOn) { visible, foreground, screenOn ->
                Triple(visible, foreground, screenOn)
            }.distinctUntilChanged().collect { (visible, foreground, screenOn) ->
                withContext(Dispatchers.Default) { Libbox.uiDashboardMemorySetSignals(memoryOwner, visible, foreground, screenOn) }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.Default) { Libbox.uiDashboardMemorySetSignals(memoryOwner, false, false, false) }
        }
    }
    // The LAN/intranet snapshot is only shown by the Intranet IP card and its
    // page; poll it while one of them is on screen, as the memory card is.
    private val lanVisible = MutableStateFlow(false)
    private val polling = activity.lifecycleScope.pollWhileVisible(
        lanVisible, AppLifecycleObserver.isForeground, AppLifecycleObserver.isScreenOn,
    ) {
        refreshNetwork()
    }

    fun setMemoryVisible(visible: Boolean) { memoryVisible.value = visible }

    fun setLanVisible(visible: Boolean) { lanVisible.value = visible }

    /**
     * Where the next memory sample reads from. sing-box runs in this process:
     * whatever the status says, the Go runtime's memory is its. Xray and
     * mihomo run as fk-core's child processes: their status reports it.
     */
    private fun pushMemorySource() {
        Libbox.uiDashboardMemorySetSource(CommandTarget.isRemote || BoxService.coreInFkCore, remoteMemory, activity.serviceStatus.value == Status.Started)
    }

    @Suppress("DEPRECATION")
    private suspend fun network(): Map<String, Any?> {
        val manager = activity.getSystemService(ConnectivityManager::class.java)
        val networks = manager.allNetworks.mapNotNull { network ->
            val capabilities = manager.getNetworkCapabilities(network) ?: return@mapNotNull null
            val properties = manager.getLinkProperties(network) ?: return@mapNotNull null
            Triple(network, capabilities, properties)
        }
        val excluded = networks.filter { (_, capabilities, _) ->
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        }.mapNotNull { it.third.interfaceName }.toSet()
        val priorities = networks.associate { (_, capabilities, properties) ->
            properties.interfaceName to when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 0
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 1
                else -> 2
            }
        }
        // Interface enumeration also includes hotspot/tethering addresses, which
        // ConnectivityManager.allNetworks does not necessarily expose.
        val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.name !in excluded && it.isUp && !it.isLoopback && !it.isPointToPoint }
        fun priority(name: String) = priorities[name] ?: when {
            name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("swlan") -> 0
            name.startsWith("eth") -> 1
            else -> 2
        }
        val interfaceAddresses = interfaces.flatMap { networkInterface ->
            networkInterface.inetAddresses.toList()
                .filterNot { it.isLoopbackAddress || it.isLinkLocalAddress || it.isAnyLocalAddress || it.isMulticastAddress }
                .mapNotNull { address -> address.hostAddress?.substringBefore('%')?.let {
                    mapOf("address" to it, "interface" to networkInterface.name)
                } }
        }.distinct().sortedWith(compareBy<Map<String, String>> { ':' in it.getValue("address") }
            .thenBy { priority(it.getValue("interface")) }.thenBy { it.getValue("address") })
        val addresses = interfaceAddresses.map { it.getValue("address") }.distinct()
        val encodedAddresses = JSONArray(interfaceAddresses).toString()
        var bindings = emptyList<Map<String, Any?>>()
        var activeBindings = emptyList<Map<String, Any?>>()
        var profileId = -1L
        var revision: String? = null
        var configurationError: String? = null
        var running = false
        var activeProfileId = -1L
        // The LAN proxy switch edits sing-box inbounds; Xray/mihomo profiles have none.
        var lanSwitchable = true
        if (!CommandTarget.isRemote) {
            try {
                val config = ProfileContent.mutex.withLock {
                    profileId = Settings.selectedProfile
                    val profile = ProfileManager.get(profileId)
                    if (profile == null) {
                        configCache.clearSaved()
                        null
                    } else {
                        val file = File(profile.typed.path)
                        val key = DashboardConfigCache.FileKey(profileId, file.path, ProfileContent.generation,
                            file.lastModified(), file.length(), file.exists())
                        configCache.saved(key) { file.readText() }
                    }
                }
                if (config != null) {
                    revision = config.revision
                    lanSwitchable = config.core !in setOf(Libbox.CoreXray, Libbox.CoreMihomo)
                    bindings = config.listeners(encodedAddresses)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                configurationError = error.message ?: error.toString()
            }
            val active = BoxService.dashboardConfig.value
            running = activity.serviceStatus.value == Status.Started && active != null &&
                BoxService.dashboardServer === active.server
            if (running && active != null) {
                activeProfileId = active.profileId
                try {
                    activeBindings = configCache.active(active) { active.content }.listeners(encodedAddresses)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    configurationError = configurationError ?: error.message ?: error.toString()
                }
            } else {
                configCache.clearActive()
            }
        }
        val stamp = "${manager.activeNetwork}:$encodedAddresses:${networks.map { (network, capabilities, _) ->
            "$network:${capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}"
        }.sorted()}"
        return mapOf("addresses" to addresses, "bindings" to bindings, "activeBindings" to activeBindings,
            "enabled" to (if (running) activeBindings else bindings).any { it["lan"] == true },
            "running" to running, "activeProfileId" to activeProfileId,
            "profileId" to profileId, "revision" to revision, "canToggle" to (revision != null && configurationError == null && lanSwitchable),
            "error" to configurationError, "stamp" to stamp)
    }

    suspend fun refreshNetwork(force: Boolean = false): Map<String, Any?> = networkMutex.withLock {
        val value = withContext(Dispatchers.IO) {
            if (force) configCache.refresh()
            network()
        }
        if (value != networkSnapshot) {
            networkSnapshot = value
            emit("lan", value)
        }
        value
    }

    private fun requireLocalProbe() {
        require(!CommandTarget.isRemote) { "Remote servers do not expose an egress probe" }
        require(activity.serviceStatus.value in listOf(Status.Stopped, Status.Started)) { "Service is changing during egress check" }
    }

    private fun isCurrent(session: EgressSession): Boolean = !session.closed && !CommandTarget.isRemote &&
        BoxService.dashboardServer === session.server &&
        activity.serviceStatus.value == (if (session.server == null) Status.Stopped else Status.Started)

    private fun session(id: String): EgressSession {
        requireLocalProbe()
        val session = egressSessions[id] ?: error("IP probe is no longer active")
        check(isCurrent(session)) { "Service changed during egress check" }
        return session
    }

    private suspend fun createProbe(id: String): EgressSession {
        requireLocalProbe()
        require(id.length in 1..128 && id !in egressSessions && egressSessions.size < 8) { "Invalid IP probe session" }
        val server = when (activity.serviceStatus.value) {
            Status.Started -> BoxService.dashboardServer ?: error("Service is not available")
            Status.Stopped -> {
                check(BoxService.dashboardServer == null) { "Service is changing during egress check" }
                null
            }
            else -> error("Service is changing during egress check")
        }
        val active = EgressSession(server)
        egressSessions[id] = active
        return try {
            withContext(Dispatchers.IO) {
                val probe = when {
                    server == null -> EgressProbe.of(FkLib.newUIDirectEgressProbe())
                    // Xray/mihomo run in fk-core: probe through its kernel.
                    BoxService.coreInFkCore -> EgressProbe.of(FkLib.newUIServiceEgressProbe())
                    else -> EgressProbe.of(server.newUIEgressProbe())
                }
                active.attach(probe)
            }
            check(session(id) === active) { "IP probe changed during creation" }
            active
        } catch (error: Exception) {
            if (egressSessions[id] === active) egressSessions.remove(id)
            active.close()
            throw error
        }
    }

    suspend fun openProbe(id: String) { createProbe(id) }

    suspend fun egress(id: String): Map<String, Any?> {
        val active = createProbe(id)
        return try {
            val info = withContext(Dispatchers.IO) { jsonObject(JSONObject(active.get().lookupOutboundIP())) }
            check(session(id) === active) { "IP probe changed during egress check" }
            info
        } catch (error: Exception) {
            if (egressSessions[id] === active) egressSessions.remove(id)
            active.close()
            throw error
        }
    }

    suspend fun query(id: String, url: String, timeoutMillis: Long): Map<String, Any?> {
        val active = session(id)
        val response = withContext(Dispatchers.IO) { active.get().get(url, timeoutMillis) }
        check(session(id) === active) { "IP probe changed during query" }
        return mapOf("status" to response.first, "body" to response.second.toString(Charsets.UTF_8))
    }

    /**
     * Service status: one service's rule (FlClash core/service_rules.go, ported
     * to core/ui_service_check.go) through this probe session.
     */
    suspend fun serviceCheck(id: String, name: String, timeoutMillis: Long): Map<String, Any?> {
        val active = session(id)
        val result = withContext(Dispatchers.IO) { active.get().serviceCheck(name, timeoutMillis) }
        check(session(id) === active) { "IP probe changed during query" }
        return result
    }

    fun closeProbe(id: String) { egressSessions.remove(id)?.close() }

    fun readQualityCache(ip: String): Map<String, Any?>? = runCatching {
        qualityCache.getString(ip, null)?.let { jsonObject(JSONObject(it)) }
    }.getOrNull()

    suspend fun writeQualityCache(ip: String, value: Map<String, Any?>) = withContext(Dispatchers.IO) {
        val encoded = JSONObject(value).toString()
        val record = JSONObject(encoded)
        val now = System.currentTimeMillis()
        require(ip.length in 1..128 && encoded.toByteArray().size <= 16384 &&
            record.optJSONObject("quality")?.optString("ip") == ip && record.optLong("checkedAt", -1) in 0..now) { "Invalid IP quality record" }
        val editor = qualityCache.edit().putString(ip, encoded)
        qualityCache.all.forEach { (key, stored) ->
            val checkedAt = runCatching { JSONObject(stored as String).optLong("checkedAt", -1) }.getOrDefault(-1)
            if (key != ip && (checkedAt < now - 3 * 24 * 60 * 60 * 1000L || checkedAt > now)) editor.remove(key)
        }
        check(editor.commit()) { "Unable to cache IP quality" }
    }

    suspend fun releaseMemory(): Map<String, Any?> = withContext(Dispatchers.IO) {
        require(!CommandTarget.isRemote) { "Remote memory collection is not supported" }
        require(!BoxService.coreInFkCore) { "Memory collection is not supported for Xray and mihomo" }
        pushMemorySource()
        jsonObject(JSONObject(Libbox.uiDashboardReleaseMemory()))
    }

    override fun close() {
        serviceChanges.cancel()
        remoteChanges.cancel()
        polling.cancel()
        memorySignals.cancel()
        egressSessions.values.forEach { it.close() }
        egressSessions.clear()
    }
}

/**
 * One egress probe session: fk-core's direct probe (service stopped) or the
 * running service's routed probe (legacy libbox, until fk-core owns the
 * service; docs/kernel-isolation.md).
 */
private interface EgressProbe {
    fun lookupOutboundIP(): String
    fun get(url: String, timeoutMillis: Long): Pair<Int, ByteArray>
    fun serviceCheck(name: String, timeoutMillis: Long): Map<String, Any?>
    fun close()

    companion object {
        fun of(probe: io.nekohasekai.sfa.fkcore.UIEgressProbe): EgressProbe = object : EgressProbe {
            override fun lookupOutboundIP() = probe.lookupOutboundIP()
            override fun get(url: String, timeoutMillis: Long) = probe.get(url, timeoutMillis).let { it.status to it.content }
            override fun serviceCheck(name: String, timeoutMillis: Long) = probe.serviceCheck(name, timeoutMillis).let {
                mapOf("name" to it.name, "status" to it.status, "region" to it.region, "delay" to it.delayMillis, "checkedAt" to it.checkedAt)
            }
            override fun close() = probe.close()
        }

        fun of(probe: io.nekohasekai.libbox.UIEgressProbe): EgressProbe = object : EgressProbe {
            override fun lookupOutboundIP(): String = probe.lookupOutboundIP()
            override fun get(url: String, timeoutMillis: Long) = probe.get(url, timeoutMillis).let { it.status to it.content }
            override fun serviceCheck(name: String, timeoutMillis: Long) = probe.serviceCheck(name, timeoutMillis).let {
                mapOf("name" to it.name, "status" to it.status, "region" to it.region, "delay" to it.delayMillis, "checkedAt" to it.checkedAt)
            }
            override fun close() = probe.close()
        }
    }
}
