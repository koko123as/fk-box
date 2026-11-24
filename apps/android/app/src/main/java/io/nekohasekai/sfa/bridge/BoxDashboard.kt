package io.nekohasekai.sfa.bridge

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.lifecycleScope
import io.nekohasekai.sfa.bg.BoxService
import io.nekohasekai.sfa.bg.FkHost
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.utils.AppLifecycleObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.Closeable
import java.io.File
import java.net.NetworkInterface

/**
 * The dashboard's memory and intranet cards. On the single-core path the
 * kernel's memory is fkhost's (Go heap and stacks in use); the IP probe and
 * service checks (libbox egress probes) and the LAN proxy switch are not
 * available (UI entries go in stage 6b).
 */
internal class BoxDashboard(private val activity: BoxActivity, private val emit: (String, Any?) -> Unit) : Closeable {
    @Volatile var remoteMemory = 0L
    private var networkSnapshot: Map<String, Any?>? = null
    private val networkMutex = Mutex()
    private val memoryVisible = MutableStateFlow(false)
    private val memoryPolling = activity.lifecycleScope.pollWhileVisible(
        memoryVisible, AppLifecycleObserver.isForeground, AppLifecycleObserver.isScreenOn,
    ) {
        val snapshot = withContext(Dispatchers.IO) { memory() }
        emit("memory", snapshot)
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

    private fun memory(): Map<String, Any?> {
        val rss = File("/proc/self/status").useLines { lines -> lines.firstOrNull { it.startsWith("VmRSS:") }
            ?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLongOrNull()?.times(1024) ?: 0L }
        val running = activity.serviceStatus.value == Status.Started
        // The kernel runs in this process once the service started: its Go
        // heap and stacks are the core's share of the process RSS.
        val stats = FkHost.stats()
        val core = stats?.memoryInuse ?: 0L
        return mapOf("total" to maxOf(rss, core), "rss" to rss, "app" to maxOf(0L, rss - core), "core" to core,
            "remote" to false, "running" to running, "heapInuse" to stats?.memoryInuse, "heapIdle" to null,
            "stack" to null, "runtimeOther" to null, "goroutines" to stats?.goroutines)
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
        // The LAN proxy (a sing-box inbound listening on the LAN) has no
        // place on the single-core path (no local listener): nothing to bind.
        val running = activity.serviceStatus.value == Status.Started
        val active = BoxService.dashboardConfig.value
        val stamp = "${manager.activeNetwork}:$encodedAddresses:${networks.map { (network, capabilities, _) ->
            "$network:${capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}"
        }.sorted()}"
        return mapOf("addresses" to addresses, "bindings" to emptyList<Any>(), "activeBindings" to emptyList<Any>(),
            "enabled" to false, "running" to running, "activeProfileId" to (active?.profileId ?: -1L),
            "profileId" to Settings.selectedProfile, "revision" to null, "canToggle" to false,
            "error" to null, "stamp" to stamp)
    }

    suspend fun refreshNetwork(force: Boolean = false): Map<String, Any?> = networkMutex.withLock {
        val value = withContext(Dispatchers.IO) { network() }
        if (value != networkSnapshot) {
            networkSnapshot = value
            emit("lan", value)
        }
        value
    }

    private fun unavailable(): Nothing = error("The IP probe is not available in the single-core build")

    suspend fun openProbe(id: String) { unavailable() }

    suspend fun egress(id: String): Map<String, Any?> = unavailable()

    suspend fun query(id: String, url: String, timeoutMillis: Long): Map<String, Any?> = unavailable()

    suspend fun serviceCheck(id: String, name: String, timeoutMillis: Long): Map<String, Any?> = unavailable()

    fun closeProbe(id: String) {}

    suspend fun releaseMemory(): Map<String, Any?> = withContext(Dispatchers.IO) {
        FkHost.freeMemory()
        memory().also { emit("memory", it) }
    }

    override fun close() {
        polling.cancel()
        memoryPolling.cancel()
    }
}
