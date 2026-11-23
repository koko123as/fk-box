package io.nekohasekai.sfa.bg

import android.content.pm.PackageManager.NameNotFoundException
import android.net.IpPrefix
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.RoutePrefixIterator
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.ktx.toList
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.InetAddress

/**
 * The VPN interface (Clash-of-Rust's `VpnRouting`): mobile-ffi
 * `vpn_routing.rs` decides what goes into the builder; this converts the
 * gomobile options and makes the `VpnService.Builder` calls.
 */
internal object VpnRouting {
    fun plan(options: TunOptions): JsonObject =
        CoreBridge.call("vpnRouting.plan") {
            put("sdk", Build.VERSION.SDK_INT)
            put("allowBypass", Settings.allowBypass)
            put("systemProxy", Settings.systemProxyEnabled)
            put("mtu", options.mtu)
            put("autoRoute", options.autoRoute)
            put("dnsDisabled", options.dnsMode.value == Libbox.DNSModeDisabled)
            put("inet4Address", options.inet4Address.prefixes())
            put("inet6Address", options.inet6Address.prefixes())
            put("dnsServers", CoreBridge.strings(options.dnsServerAddress.toList()))
            put("inet4RouteAddress", options.inet4RouteAddress.prefixes())
            put("inet6RouteAddress", options.inet6RouteAddress.prefixes())
            put("inet4RouteExclude", options.inet4RouteExcludeAddress.prefixes())
            put("inet6RouteExclude", options.inet6RouteExcludeAddress.prefixes())
            put("inet4RouteRange", options.inet4RouteRange.prefixes())
            put("inet6RouteRange", options.inet6RouteRange.prefixes())
            put("include", CoreBridge.strings(options.includePackage.toList()))
            put("exclude", CoreBridge.strings(options.excludePackage.toList()))
            put("httpProxy", buildJsonObject {
                put("enabled", options.isHTTPProxyEnabled)
                if (options.isHTTPProxyEnabled) {
                    put("server", options.httpProxyServer)
                    put("port", options.httpProxyServerPort)
                    put("bypass", CoreBridge.strings(options.httpProxyBypassDomain.toList()))
                }
            })
        }

    fun build(builder: VpnService.Builder, plan: JsonObject): VpnService.Builder {
        builder.setSession(plan.text("session")).setMtu(plan.int("mtu"))
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
        val proxy = plan["httpProxy"] as? JsonObject
        if (proxy != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setHttpProxy(ProxyInfo.buildDirectProxy(proxy.text("server"), proxy.int("port"), proxy.texts("bypass")))
        }
        return builder
    }

    private fun RoutePrefixIterator.prefixes() = JsonArray(
        buildList {
            while (hasNext()) {
                val prefix = next()
                add(buildJsonObject { put("address", prefix.address()); put("prefix", prefix.prefix()) })
            }
        },
    )

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun JsonObject.ipPrefix() = IpPrefix(InetAddress.getByName(text("address")), int("prefix"))
}
