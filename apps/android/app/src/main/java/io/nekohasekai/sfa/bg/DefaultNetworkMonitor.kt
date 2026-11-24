package io.nekohasekai.sfa.bg

import android.net.Network
import io.fkbox.mobile.InterfaceListener
import io.nekohasekai.sfa.Application
import kotlinx.serialization.json.put
import java.net.NetworkInterface

object DefaultNetworkMonitor {

    var defaultNetwork: Network? = null
    private var listener: InterfaceListener? = null

    // The handle of the last non-null default network, to tell a switch (Wi-Fi
    // to cellular) from the property updates the same network reports all the
    // time (mobile-ffi network_recovery.rs decides).
    private var lastNetwork: Long? = null

    suspend fun start() {
        DefaultNetworkListener.start(this) {
            defaultNetwork = it
            // The kernel follows the default network itself (sing-box resets
            // its connections when the interface changes).
            checkDefaultInterfaceUpdate(it)
            val update = CoreBridge.call("networkRecovery.update") {
                put("network", it?.networkHandle)
                put("last", lastNetwork)
            }
            lastNetwork = update.longOrNull("last")
        }
        defaultNetwork = Application.connectivity.activeNetwork
    }

    suspend fun stop() {
        DefaultNetworkListener.stop(this)
        lastNetwork = null
    }

    fun setListener(listener: InterfaceListener?) {
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
                listener.updateDefaultInterface(linkProperties.interfaceName, interfaceIndex)
                return
            }
        } else {
            listener.updateDefaultInterface("", -1)
        }
    }
}
