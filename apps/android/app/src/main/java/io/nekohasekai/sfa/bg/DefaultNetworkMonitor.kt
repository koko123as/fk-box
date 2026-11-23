package io.nekohasekai.sfa.bg

import android.net.Network
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.database.CoreStore
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.net.NetworkInterface

object DefaultNetworkMonitor {

    var defaultNetwork: Network? = null
    private var listener: InterfaceUpdateListener? = null

    // The handle of the last non-null default network, to tell a switch (Wi-Fi
    // to cellular) from the property updates the same network reports all the
    // time (mobile-ffi network.rs decides).
    private var lastNetwork: Long? = null

    suspend fun start() {
        DefaultNetworkListener.start(this) {
            defaultNetwork = it
            checkDefaultInterfaceUpdate(it)
            pushSystemDNS()
            val update = CoreStore.call("network.update") {
                put("network", it?.networkHandle)
                put("last", lastNetwork)
            }.jsonObject
            lastNetwork = update["last"]?.jsonPrimitive?.longOrNull
            if (update["reconnect"]?.jsonPrimitive?.booleanOrNull == true) {
                runCatching { Libbox.coreDefaultNetworkChanged() }
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
        val line = CoreStore.call("network.systemDns") {
            put("servers", JsonArray(servers.mapNotNull { it.hostAddress }.map { JsonPrimitive(it) }))
        }.jsonObject
        Libbox.updateCoreSystemDNS(line["dns"]!!.jsonPrimitive.content)
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
