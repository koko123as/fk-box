package io.nekohasekai.sfa.bg

import android.util.Log
import android.net.Network
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.fkcore.FkLib
import java.net.NetworkInterface

object DefaultNetworkMonitor {

    var defaultNetwork: Network? = null
    private var listener: InterfaceUpdateListener? = null

    // The last non-null default network, to tell a switch (Wi-Fi to cellular)
    // from the property updates the same network reports all the time.
    private var lastNetwork: Network? = null

    suspend fun start() {
        DefaultNetworkListener.start(this) {
            defaultNetwork = it
            checkDefaultInterfaceUpdate(it)
            pushSystemDNS()
            if (it != null) {
                val previous = lastNetwork
                lastNetwork = it
                if (previous != null && previous != it) {
                    if (BoxService.coreInFkCore) runCatching { FkLib.serviceNetworkSwitched() }
                }
            }
        }
        defaultNetwork = Application.connectivity.activeNetwork
    }

    suspend fun stop() {
        DefaultNetworkListener.stop(this)
        lastNetwork = null
    }

    /** The underlying network's DNS servers (none while there is no network). */
    fun systemDNS(): List<String> {
        val network = defaultNetwork ?: Application.connectivity.activeNetwork ?: return emptyList()
        val servers = runCatching { Application.connectivity.getLinkProperties(network)?.dnsServers }.getOrNull() ?: return emptyList()
        return servers.mapNotNull { it.hostAddress?.substringBefore('%') }
    }

    /** mihomo resolves "system" DNS with the underlying network's servers (fk-core regenerates its configuration). */
    fun pushSystemDNS() {
        if (!BoxService.coreInFkCore) return
        val servers = systemDNS()
        if (servers.isEmpty()) return
        runCatching { FkLib.serviceDefaultNetworkChanged(org.json.JSONArray(servers).toString()) }
            .onFailure { Log.w("DefaultNetworkMonitor", "push DNS to fk-core", it) }
    }

    fun setListener(listener: InterfaceUpdateListener?) {
        this.listener = listener
        checkDefaultInterfaceUpdate(defaultNetwork)
    }

    private fun checkDefaultInterfaceUpdate(newNetwork: Network?) {
        val listener = listener ?: return
        if (newNetwork != null) {
            for (times in 0 until 10) {
                val linkProperties = Application.connectivity.getLinkProperties(newNetwork)
                if (linkProperties == null) {
                    Thread.sleep(100)
                    continue
                }
                var interfaceIndex: Int
                try {
                    interfaceIndex = NetworkInterface.getByName(linkProperties.interfaceName).index
                } catch (e: Exception) {
                    Thread.sleep(100)
                    continue
                }
                listener.updateDefaultInterface(linkProperties.interfaceName, interfaceIndex, false, false)
                return
            }
        } else {
            listener.updateDefaultInterface("", -1, false, false)
        }
    }
}
