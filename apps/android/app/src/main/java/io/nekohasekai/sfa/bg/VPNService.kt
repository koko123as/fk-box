package io.nekohasekai.sfa.bg

import android.content.Intent
import android.content.pm.PackageManager.NameNotFoundException
import android.net.IpPrefix
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.libbox.RoutePrefixIterator
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.sfa.database.CoreStore
import io.nekohasekai.sfa.ktx.toList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.InetAddress

class VPNService :
    VpnService(),
    PlatformInterfaceWrapper {
    private val service = BoxService(this, this)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = service.onStartCommand()

    override fun onBind(intent: Intent): IBinder {
        val binder = super.onBind(intent)
        if (binder != null) {
            return binder
        }
        return service.onBind()
    }

    override fun onDestroy() {
        service.onDestroy()
    }

    override fun onRevoke() {
        runBlocking {
            withContext(Dispatchers.Main) {
                service.onRevoke()
            }
        }
    }

    override fun autoDetectInterfaceControl(fd: Int) {
        protect(fd)
    }

    var systemProxyAvailable = false
    var systemProxyEnabled = false

    override fun openTun(options: TunOptions): Int {
        if (prepare(this) != null) error("android: missing vpn permission")

        // mobile-ffi tun.rs decides what goes into the builder; this only
        // converts the gomobile options and makes the builder calls.
        val plan = CoreStore.call("tun.plan") {
            put("sdk", Build.VERSION.SDK_INT)
            put("allowBypass", Settings.allowBypass)
            put("systemProxy", Settings.systemProxyEnabled)
            put("mtu", options.mtu)
            put("autoRoute", options.autoRoute)
            put("dnsDisabled", options.dnsMode.value == Libbox.DNSModeDisabled)
            put("inet4Address", options.inet4Address.prefixes())
            put("inet6Address", options.inet6Address.prefixes())
            put("dnsServers", options.dnsServerAddress.strings())
            put("inet4RouteAddress", options.inet4RouteAddress.prefixes())
            put("inet6RouteAddress", options.inet6RouteAddress.prefixes())
            put("inet4RouteExclude", options.inet4RouteExcludeAddress.prefixes())
            put("inet6RouteExclude", options.inet6RouteExcludeAddress.prefixes())
            put("inet4RouteRange", options.inet4RouteRange.prefixes())
            put("inet6RouteRange", options.inet6RouteRange.prefixes())
            put("include", options.includePackage.strings())
            put("exclude", options.excludePackage.strings())
            put("httpProxy", buildJsonObject {
                put("enabled", options.isHTTPProxyEnabled)
                if (options.isHTTPProxyEnabled) {
                    put("server", options.httpProxyServer)
                    put("port", options.httpProxyServerPort)
                    put("bypass", options.httpProxyBypassDomain.strings())
                }
            })
        }.jsonObject

        val builder = Builder().setSession(plan.text("session")).setMtu(plan.int("mtu"))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && plan["metered"]?.jsonPrimitive?.booleanOrNull == false) {
            builder.setMetered(false)
        }
        if (plan.bool("allowBypass")) builder.allowBypass()
        plan.items("addresses").forEach { builder.addAddress(it.text("address"), it.int("prefix")) }
        plan.texts("dns").forEach { builder.addDnsServer(it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && plan.bool("ipPrefix")) {
            plan.items("routes").forEach { builder.addRoute(it.ipPrefix()) }
            plan.items("excludeRoutes").forEach { builder.excludeRoute(it.ipPrefix()) }
        } else {
            plan.items("routes").forEach { builder.addRoute(it.text("address"), it.int("prefix")) }
        }
        plan.texts("allowed").forEach { name ->
            try {
                builder.addAllowedApplication(name)
                Log.d("VPNService", "addAllowedApplication: $name")
            } catch (e: NameNotFoundException) {
                Log.e("VPNService", "addAllowedApplication failed", e)
            }
        }
        plan.texts("disallowed").forEach { name ->
            try {
                builder.addDisallowedApplication(name)
                Log.d("VPNService", "addDisallowedApplication: $name")
            } catch (e: NameNotFoundException) {
                Log.e("VPNService", "addDisallowedApplication failed", e)
            }
        }
        systemProxyAvailable = plan.bool("systemProxyAvailable")
        systemProxyEnabled = plan.bool("systemProxyEnabled")
        val proxy = plan["httpProxy"] as? JsonObject
        if (proxy != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setHttpProxy(ProxyInfo.buildDirectProxy(proxy.text("server"), proxy.int("port"), proxy.texts("bypass")))
        }

        val pfd =
            builder.establish() ?: error("android: the application is not prepared or is revoked")
        // Switching cores opens a new TUN while the service runs: close the
        // previous descriptor now instead of leaving it to the finalizer.
        val previous = service.fileDescriptor
        service.fileDescriptor = pfd
        if (previous != null && previous !== pfd) {
            runCatching { previous.close() }
        }
        return pfd.fd
    }

    override fun sendNotification(notification: Notification) = service.sendNotification(notification)

    override fun cancelNotification(identifier: String, typeID: Int) = service.cancelNotification(identifier, typeID)

    private fun RoutePrefixIterator.prefixes() = JsonArray(
        buildList {
            while (hasNext()) {
                val prefix = next()
                add(buildJsonObject { put("address", prefix.address()); put("prefix", prefix.prefix()) })
            }
        },
    )

    private fun StringIterator.strings() = JsonArray(toList().map { JsonPrimitive(it) })

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content

    private fun JsonObject.int(key: String) = getValue(key).jsonPrimitive.int

    private fun JsonObject.bool(key: String) = this[key]?.jsonPrimitive?.booleanOrNull == true

    private fun JsonObject.items(key: String) = this[key]?.jsonArray.orEmpty().map { it.jsonObject }

    private fun JsonObject.texts(key: String) = this[key]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun JsonObject.ipPrefix() = IpPrefix(InetAddress.getByName(text("address")), int("prefix"))
}
