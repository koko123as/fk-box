package io.nekohasekai.sfa.bg

import android.net.Network
import android.os.Build
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.Application
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
                    runCatching { Libbox.coreDefaultNetworkChanged() }
                }
            }
        }
        defaultNetwork = Application.connectivity.activeNetwork
    }

    suspend fun stop() {
        DefaultNetworkListener.stop(this)
        lastNetwork = null
    }

    /** mihomo (cmfa build) resolves "system" DNS with the underlying network's servers. */
    fun pushSystemDNS() {
        val network = defaultNetwork ?: return
        val servers = runCatching { Application.connectivity.getLinkProperties(network)?.dnsServers }.getOrNull() ?: return
        Libbox.updateCoreSystemDNS(servers.mapNotNull { it.hostAddress?.substringBefore('%') }.joinToString(","))
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
