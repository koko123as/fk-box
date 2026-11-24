package io.nekohasekai.sfa.utils

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * The app's own HTTP requests for client-core (database/CoreKernel.kt):
 * subscription fetches, the update check and WebDAV backups. These were
 * libbox's Go clients (UISubscriptionGet, UIHTTPGet, UIWebDAVRequest); the
 * single-core build keeps the kernel out of the UI, so they run on the
 * platform's HttpURLConnection with the same limits and redirect rules.
 * While the service runs they go through the VPN like any app traffic.
 */
internal object HttpRequests {
    class Response(val status: Int, val content: ByteArray, private val headers: Map<String?, List<String>>) {
        /** The first value of [name], case-insensitive, trimmed; empty when missing or over 4 KiB. */
        fun header(name: String): String {
            val value = headers.entries.firstOrNull { it.key?.equals(name, ignoreCase = true) == true }?.value?.firstOrNull()?.trim().orEmpty()
            return if (value.length > 4096) "" else value
        }
    }

    private const val SUBSCRIPTION_LIMIT = 32L * 1024 * 1024
    private const val BACKUP_LIMIT = 64L * 1024 * 1024

    /** A subscription: HTTP(S) GET with [userAgent], user info of the address as basic auth, 32 MiB, 60 s. */
    fun subscription(link: String, userAgent: String): Response {
        val uri = runCatching { URI(link) }.getOrNull()
        require(uri != null && !uri.host.isNullOrEmpty() && (uri.scheme == "http" || uri.scheme == "https")) {
            "invalid HTTP or HTTPS subscription address"
        }
        var auth: String? = null
        uri.rawUserInfo?.let { info ->
            val user = java.net.URLDecoder.decode(info.substringBefore(':'), "UTF-8")
            val password = if (':' in info) java.net.URLDecoder.decode(info.substringAfter(':'), "UTF-8") else ""
            auth = basic(user, password)
        }
        val target = uri.rawUserInfo?.let { link.replaceFirst("$it@", "") } ?: link
        val response = request("GET", target, null, auth, userAgent, SUBSCRIPTION_LIMIT, 60_000, sameOriginOnly = false, keepAuth = true)
        if (response.status != 200) {
            val detail = response.content.copyOf(minOf(response.content.size, 4096)).toString(Charsets.UTF_8).trim()
            throw IOException(if (detail.isNotEmpty()) "HTTP ${response.status}: $detail" else "HTTP ${response.status}")
        }
        return response
    }

    /** The update check: an anonymous GET, 2 MiB, 15 s; redirects may change origin. */
    fun get(link: String): Response {
        checkAddress(link)
        return request("GET", link, null, null, "FK-box", 2L * 1024 * 1024, 15_000, sameOriginOnly = false, keepAuth = false)
    }

    /** A WebDAV request (GET, PUT, OPTIONS, MKCOL), 64 MiB, 30 s; redirects stay on the origin. */
    fun webdav(method: String, link: String, username: String, password: String, content: ByteArray): Response {
        require(method in setOf("GET", "PUT", "OPTIONS", "MKCOL")) { "unsupported WebDAV method" }
        require(content.size <= BACKUP_LIMIT && username.length <= 4096 && password.length <= 4096) { "WebDAV request exceeds size limit" }
        checkAddress(link)
        val auth = if (username.isNotEmpty() || password.isNotEmpty()) basic(username, password) else null
        return request(method, link, content.takeIf { method == "PUT" || it.isNotEmpty() }, auth, "FK-box", BACKUP_LIMIT, 30_000, sameOriginOnly = true, keepAuth = true)
    }

    private fun checkAddress(link: String) {
        val uri = runCatching { URI(link) }.getOrNull()
        require(uri != null && !uri.host.isNullOrEmpty() && uri.rawUserInfo == null && uri.rawFragment == null && (uri.scheme == "http" || uri.scheme == "https")) {
            "invalid HTTP or HTTPS address"
        }
    }

    private fun basic(user: String, password: String) = "Basic " + Base64.encodeToString("$user:$password".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    private fun port(url: URL) = if (url.port != -1) url.port else url.defaultPort

    private fun request(
        method: String,
        link: String,
        body: ByteArray?,
        authorization: String?,
        userAgent: String,
        limit: Long,
        timeoutMillis: Int,
        sameOriginOnly: Boolean,
        keepAuth: Boolean,
    ): Response {
        val root = URL(link)
        var location = root
        var redirects = 0
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (true) {
            val remaining = (deadline - System.currentTimeMillis()).toInt()
            if (remaining <= 0) throw IOException("timed out")
            val connection = location.openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = remaining
                connection.readTimeout = remaining
                connection.instanceFollowRedirects = false
                setMethod(connection, method)
                connection.setRequestProperty("User-Agent", userAgent)
                if (authorization != null && (keepAuth || location == root)) connection.setRequestProperty("Authorization", authorization)
                if (body != null) {
                    if (method == "PUT") connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.doOutput = true
                    connection.setFixedLengthStreamingMode(body.size)
                    connection.outputStream.use { it.write(body) }
                }
                val status = connection.responseCode
                if (status in 300..399 && status != HttpURLConnection.HTTP_NOT_MODIFIED) {
                    val next = connection.getHeaderField("Location") ?: return read(connection, status, limit)
                    val target = URL(location, next)
                    if (++redirects > 5) throw IOException("too many HTTP redirects")
                    if (target.userInfo != null || target.host.isNullOrEmpty() || (target.protocol != "https" && target.protocol != "http")) {
                        throw IOException("invalid HTTP redirect address")
                    }
                    if (location.protocol == "https" && target.protocol != "https") throw IOException("HTTP redirect downgraded HTTPS")
                    val sameOrigin = target.protocol == root.protocol && target.host.equals(root.host, ignoreCase = true) && port(target) == port(root)
                    if (sameOriginOnly && !sameOrigin) throw IOException("HTTP redirect changed origin")
                    // A redirect that would change the method (POST to GET) is not followed.
                    if (method != "GET" && status != 307 && status != 308) return read(connection, status, limit)
                    location = target
                    continue
                }
                return read(connection, status, limit)
            } finally {
                connection.disconnect()
            }
        }
    }

    /**
     * HttpURLConnection refuses WebDAV's MKCOL; set it on the field the
     * request line is built from (on the HTTPS wrapper's delegate too).
     */
    private fun setMethod(connection: HttpURLConnection, method: String) {
        try {
            connection.requestMethod = method
        } catch (_: java.net.ProtocolException) {
            val field = HttpURLConnection::class.java.getDeclaredField("method")
            field.isAccessible = true
            field.set(connection, method)
            var type: Class<*>? = connection.javaClass
            while (type != null) {
                val current: Class<*> = type
                val delegate = try {
                    current.getDeclaredField("delegate")
                } catch (_: NoSuchFieldException) {
                    null
                }
                if (delegate != null) {
                    delegate.isAccessible = true
                    (delegate.get(connection) as? HttpURLConnection)?.let { field.set(it, method) }
                    break
                }
                type = current.superclass
            }
        }
    }

    private fun read(connection: HttpURLConnection, status: Int, limit: Long): Response {
        if (connection.contentLengthLong > limit) throw IOException("HTTP response exceeds size limit")
        val stream = try {
            connection.inputStream
        } catch (_: IOException) {
            connection.errorStream
        }
        val output = ByteArrayOutputStream()
        stream?.use { input ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > limit) throw IOException("HTTP response exceeds size limit")
                output.write(buffer, 0, read)
            }
        }
        return Response(status, output.toByteArray(), connection.headerFields)
    }
}
