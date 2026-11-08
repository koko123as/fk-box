// FK-box: FlClash's shareLink.encode (mihomo proxy definitions to share links).
// FlClash's Go core encodes through its own encoder; libbox carries mihomo's
// decoder only, so the common schemes are written here in the forms mihomo's
// common/convert reads back (ss, vmess, vless, trojan, hysteria2, tuic,
// socks5, http). A type without a share-link form answers "".
package com.follow.clash.core

import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

internal object FkShareLinks {
    fun encode(proxies: JSONArray): JSONArray {
        val links = JSONArray()
        for (index in 0 until proxies.length()) {
            links.put(runCatching { proxies.optJSONObject(index)?.let(::encode) }.getOrNull().orEmpty())
        }
        return links
    }

    private fun encoded(value: String) = Uri.encode(value)

    private fun host(server: String) = if (server.contains(':') && !server.startsWith("[")) "[$server]" else server

    private fun query(values: List<Pair<String, String?>>): String = values
        .filter { !it.second.isNullOrEmpty() }
        .joinToString("&") { "${it.first}=${encoded(it.second!!)}" }
        .let { if (it.isEmpty()) "" else "?$it" }

    private fun JSONObject.str(key: String): String? = if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotEmpty() } else null

    private fun transport(proxy: JSONObject): List<Pair<String, String?>> {
        val network = proxy.str("network") ?: "tcp"
        val values = mutableListOf<Pair<String, String?>>("type" to network)
        when (network) {
            "ws" -> proxy.optJSONObject("ws-opts")?.let { ws ->
                values += "path" to ws.str("path")
                values += "host" to ws.optJSONObject("headers")?.str("Host")
            }
            "grpc" -> values += "serviceName" to proxy.optJSONObject("grpc-opts")?.str("grpc-service-name")
            "h2" -> proxy.optJSONObject("h2-opts")?.let { h2 ->
                values += "path" to h2.str("path")
                values += "host" to h2.optJSONArray("host")?.optString(0)
            }
            "http" -> proxy.optJSONObject("http-opts")?.let { http ->
                values += "path" to http.optJSONArray("path")?.optString(0)
            }
            "xhttp" -> proxy.optJSONObject("xhttp-opts")?.let { xhttp ->
                values += "path" to xhttp.str("path")
                values += "host" to xhttp.str("host")
                values += "mode" to xhttp.str("mode")
            }
        }
        return values
    }

    private fun encode(proxy: JSONObject): String {
        val name = encoded(proxy.optString("name"))
        val server = host(proxy.optString("server"))
        val port = proxy.optInt("port")
        val sni = proxy.str("servername") ?: proxy.str("sni")
        val insecure = if (proxy.optBoolean("skip-cert-verify")) "1" else null
        return when (proxy.optString("type")) {
            "ss" -> {
                val user = Base64.encodeToString(
                    "${proxy.optString("cipher")}:${proxy.optString("password")}".toByteArray(),
                    Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE,
                )
                val plugin = proxy.str("plugin")?.let { plugin ->
                    val opts = proxy.optJSONObject("plugin-opts")
                    val parts = mutableListOf(if (plugin == "obfs") "obfs-local" else plugin)
                    opts?.keys()?.forEach { key ->
                        val option = when {
                            plugin != "obfs" -> key
                            key == "mode" -> "obfs"
                            else -> "obfs-$key"
                        }
                        parts += option + "=" + opts.optString(key)
                    }
                    parts.joinToString(";")
                }
                "ss://$user@$server:$port${query(listOf("plugin" to plugin))}#$name"
            }
            "vmess" -> {
                val network = proxy.str("network") ?: "tcp"
                val ws = proxy.optJSONObject("ws-opts")
                val json = JSONObject()
                    .put("v", "2").put("ps", proxy.optString("name")).put("add", proxy.optString("server"))
                    .put("port", port.toString()).put("id", proxy.optString("uuid"))
                    .put("aid", proxy.optInt("alterId").toString()).put("scy", proxy.str("cipher") ?: "auto")
                    .put("net", network).put("type", "none")
                    .put("host", ws?.optJSONObject("headers")?.str("Host") ?: "")
                    .put("path", ws?.str("path") ?: proxy.optJSONObject("grpc-opts")?.str("grpc-service-name") ?: "")
                    .put("tls", if (proxy.optBoolean("tls")) "tls" else "").put("sni", sni ?: "")
                "vmess://" + Base64.encodeToString(json.toString().toByteArray(), Base64.NO_WRAP)
            }
            "vless" -> {
                val reality = proxy.optJSONObject("reality-opts")
                val security = when {
                    reality != null -> "reality"
                    proxy.optBoolean("tls") -> "tls"
                    else -> "none"
                }
                val values = listOf(
                    "encryption" to (proxy.str("encryption") ?: "none"),
                    "security" to security,
                    "sni" to sni,
                    "fp" to proxy.str("client-fingerprint"),
                    "flow" to proxy.str("flow"),
                    "pbk" to reality?.str("public-key"),
                    "sid" to reality?.str("short-id"),
                    "allowInsecure" to insecure,
                ) + transport(proxy)
                "vless://${encoded(proxy.optString("uuid"))}@$server:$port${query(values)}#$name"
            }
            "trojan" -> {
                val values = listOf("security" to "tls", "sni" to sni, "fp" to proxy.str("client-fingerprint"), "allowInsecure" to insecure) +
                    transport(proxy)
                "trojan://${encoded(proxy.optString("password"))}@$server:$port${query(values)}#$name"
            }
            "hysteria2" -> {
                val values = listOf(
                    "sni" to sni, "obfs" to proxy.str("obfs"), "obfs-password" to proxy.str("obfs-password"),
                    "insecure" to insecure, "mport" to proxy.str("ports"),
                )
                "hysteria2://${encoded(proxy.optString("password"))}@$server:$port${query(values)}#$name"
            }
            "tuic" -> {
                val alpn = proxy.optJSONArray("alpn")?.let { list -> (0 until list.length()).joinToString(",") { list.optString(it) } }
                val values = listOf(
                    "sni" to sni, "congestion_control" to proxy.str("congestion-controller"), "alpn" to alpn,
                    "udp_relay_mode" to proxy.str("udp-relay-mode"), "allow_insecure" to insecure,
                )
                "tuic://${encoded(proxy.optString("uuid"))}:${encoded(proxy.optString("password"))}@$server:$port${query(values)}#$name"
            }
            "socks5", "http" -> {
                val user = proxy.str("username")?.let { "${encoded(it)}:${encoded(proxy.optString("password"))}@" }.orEmpty()
                "${proxy.optString("type")}://$user$server:$port#$name"
            }
            else -> ""
        }
    }
}
