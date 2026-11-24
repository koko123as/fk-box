package io.nekohasekai.sfa.bg

import android.net.NetworkCapabilities
import android.os.Build
import android.os.Process
import android.system.OsConstants
import android.util.Log
import io.fkbox.mobile.ConnectionOwner
import io.fkbox.mobile.InterfaceListener
import io.fkbox.mobile.Platform
import io.fkbox.mobile.TunOptions
import io.nekohasekai.sfa.Application
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.InterfaceAddress
import java.net.NetworkInterface

/**
 * What the kernel (fkhost, io.fkbox.mobile.Platform) asks of Android:
 * VpnService.protect, the TUN descriptor, the connection owner and the
 * network state. Called from the kernel's goroutines, never on the main
 * thread. Shell, SSH, Tailscale, bridges, neighbours and root
 * auto-redirect are not part of the single-core path.
 */
internal class AndroidPlatform(private val service: VPNService) : Platform {
    override fun protect(fd: Int) {
        if (!service.protect(fd)) error("android: protect($fd) failed")
    }

    override fun openTun(options: TunOptions): Int = service.openTun(options)

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int,
    ): ConnectionOwner {
        // getConnectionOwnerUid exists from Android 10; earlier releases get
        // no owner, so process/package rules do not match there.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) error("android: connection owner needs Android 10")
        val uid = Application.connectivity.getConnectionOwnerUid(
            ipProtocol,
            InetSocketAddress(sourceAddress, sourcePort),
            InetSocketAddress(destinationAddress, destinationPort),
        )
        if (uid == Process.INVALID_UID) error("android: connection owner not found")
        val packages = UidPackageCache.get(uid) { Application.packageManager.getPackagesForUid(it) }
        return ConnectionOwner().also {
            it.uid = uid
            it.packages = packages.joinToString("\n")
        }
    }

    override fun networkInterfaces(): String {
        val networkInterfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        val list = ArrayList<kotlinx.serialization.json.JsonObject>()
        for (network in Application.connectivity.allNetworks) {
            val linkProperties = Application.connectivity.getLinkProperties(network) ?: continue
            val capabilities = Application.connectivity.getNetworkCapabilities(network) ?: continue
            val name = linkProperties.interfaceName ?: continue
            val networkInterface = networkInterfaces.find { it.name == name } ?: continue
            var flags = 0
            if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) flags = OsConstants.IFF_UP or OsConstants.IFF_RUNNING
            if (networkInterface.isLoopback) flags = flags or OsConstants.IFF_LOOPBACK
            if (networkInterface.isPointToPoint) flags = flags or OsConstants.IFF_POINTOPOINT
            if (networkInterface.supportsMulticast()) flags = flags or OsConstants.IFF_MULTICAST
            val mtu = runCatching { networkInterface.mtu }.onFailure { Log.e(TAG, "mtu of $name", it) }.getOrDefault(0)
            val type = when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 0
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 1
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 2
                else -> 3
            }
            list.add(
                buildJsonObject {
                    put("name", name)
                    put("index", networkInterface.index)
                    put("mtu", mtu)
                    put("addresses", strings(networkInterface.interfaceAddresses.map { it.toPrefix() }))
                    put("flags", flags)
                    put("type", type)
                    put("dns", strings(linkProperties.dnsServers.mapNotNull { it.hostAddress }))
                    put("domains", strings(linkProperties.domains?.split(' ')?.filter { it.isNotEmpty() }.orEmpty()))
                    put(
                        "gateways",
                        strings(
                            linkProperties.routes.filter { it.destination.prefixLength == 0 }.mapNotNull { it.gateway }
                                .filterNot { it.isAnyLocalAddress }.mapNotNull { it.hostAddress },
                        ),
                    )
                    put("metered", !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
                },
            )
        }
        return JsonArray(list).toString()
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceListener) {
        DefaultNetworkMonitor.setListener(listener)
    }

    override fun closeDefaultInterfaceMonitor() {
        DefaultNetworkMonitor.setListener(null)
    }

    private fun strings(values: List<String>) = JsonArray(values.map { JsonPrimitive(it) })

    private fun InterfaceAddress.toPrefix(): String = if (address is Inet6Address) {
        "${Inet6Address.getByAddress(address.address).hostAddress}/$networkPrefixLength"
    } else {
        "${address.hostAddress}/$networkPrefixLength"
    }

    private companion object {
        const val TAG = "AndroidPlatform"
    }
}
