// FlClash's probe.outboundIp (core/outbound_ip.go): fetch the given IP-echo
// URLs and answer the first body that comes back, with its delay. FK-box's
// own traffic goes through its VPN while the service runs, so the answer is
// the exit the running profile routes to; stopped, it is the device's.
package com.follow.clash.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal object FkProbe {
    private const val BODY_LIMIT = 64 * 1024

    private class Answer(val url: String, val body: String, val delay: Long)

    private fun fetch(url: String, timeout: Int): Answer {
        val started = System.nanoTime()
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = timeout
            connection.readTimeout = timeout
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "curl/8.0")
            connection.setRequestProperty("Accept", "application/json, text/plain, */*")
            val code = connection.responseCode
            if (code !in 200..299) error("HTTP $code")
            val bytes = connection.inputStream.use { input ->
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (buffer.size() < BODY_LIMIT) {
                    val read = input.read(chunk)
                    if (read < 0) break
                    buffer.write(chunk, 0, read)
                }
                buffer.toByteArray()
            }
            return Answer(url, String(bytes, Charsets.UTF_8), (System.nanoTime() - started) / 1_000_000)
        } finally {
            connection.disconnect()
        }
    }

    suspend fun outboundIp(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val urls = args.optJSONArray("urls") ?: JSONArray()
        val timeout = args.optInt("timeout", 5000).coerceIn(1000, 30_000)
        val result = JSONObject().put("chains", JSONArray())
        if (urls.length() == 0) return@withContext result.put("error", "no probe URL")
        val errors = mutableListOf<String>()
        val answer = withTimeoutOrNull(timeout.toLong() + 1000) {
            coroutineScope {
                val tasks = (0 until urls.length()).map { index ->
                    val url = urls.optString(index)
                    async {
                        runCatching { fetch(url, timeout) }.onFailure { synchronized(errors) { errors += "$url: ${it.message}" } }.getOrNull()
                    }
                }
                // In list order, as FlClash prefers earlier sources when several answer.
                tasks.awaitAll().firstOrNull { it != null }
            }
        }
        if (answer == null) {
            result.put("error", errors.firstOrNull() ?: "timeout")
        } else {
            result.put("url", answer.url).put("body", answer.body).put("delay", answer.delay)
        }
        result
    }
}
