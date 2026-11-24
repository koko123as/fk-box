package io.nekohasekai.sfa.bg

import android.content.pm.PackageManager.NameNotFoundException
import android.net.IpPrefix
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import io.fkbox.mobile.TunOptions
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.database.CoreStore
import io.nekohasekai.sfa.database.Settings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.InetAddress

/**
 * The VPN interface (Clash-of-Rust's `VpnRouting`): mobile-ffi
 * `vpn_routing.rs` decides what goes into the builder from the kernel's
 * TUN options; this makes the `VpnService.Builder` calls.
 */
internal object VpnRouting {
    /**
     * The kernel's interface (fkhost TunOptions.plan(), the keys of
     * vpn_routing.rs) with the app's own settings: bypass, system proxy and
     * the per-app proxy list (the generated configuration has none; the app
     * itself always stays proxied, mobile-ffi service.rs).
     */
    fun plan(options: TunOptions): JsonObject {
        val kernel = CoreStore.json.parseToJsonElement(options.plan()).jsonObject
        var include: JsonArray = (kernel["include"] as? JsonArray) ?: JsonArray(emptyList())
        var exclude: JsonArray = (kernel["exclude"] as? JsonArray) ?: JsonArray(emptyList())
        if (Settings.perAppProxyEnabled) {
            val packages = CoreStore.call("service.perAppPackages") {
                put("enabled", true)
                put("mode", Settings.getEffectivePerAppProxyMode())
                put("list", JsonArray(Settings.getEffectivePerAppProxyList().map { JsonPrimitive(it) }))
                put("own", Application.application.packageName)
            }.jsonObject
            (packages["include"] as? JsonArray)?.let { include = it }
            (packages["exclude"] as? JsonArray)?.let { exclude = it }
        }
        return CoreBridge.call("vpnRouting.plan") {
            kernel.forEach { (key, value) -> put(key, value) }
            put("sdk", Build.VERSION.SDK_INT)
            put("allowBypass", Settings.allowBypass)
            put("systemProxy", Settings.systemProxyEnabled)
            put("include", include)
            put("exclude", exclude)
            put("httpProxy", buildJsonObject { put("enabled", false) })
        }
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

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun JsonObject.ipPrefix() = IpPrefix(InetAddress.getByName(text("address")), int("prefix"))
}
