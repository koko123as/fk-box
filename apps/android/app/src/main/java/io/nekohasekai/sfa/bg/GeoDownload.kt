package io.nekohasekai.sfa.bg

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * One cancellable geo resource download (formerly libbox's GeoUpdate, Go):
 * streamed into a temporary file beside the target and hashed on the way,
 * then moved over the installed file. Any failure leaves the installed file
 * as it was. [cancel] aborts a download in progress from another thread.
 */
internal class GeoDownload {
    data class Result(val size: Long, val sha256: String, val skipped: Boolean, val etag: String, val lastModified: String)

    @Volatile private var connection: HttpURLConnection? = null

    @Volatile private var cancelled = false

    fun cancel() {
        cancelled = true
        runCatching { connection?.disconnect() }
    }

    /**
     * Downloads [link] into [dir]/[file]. When [installedSha256] still matches
     * the installed file and [etag] or [lastModified] is known, the request is
     * conditional and a 304 answer leaves the file untouched (skipped).
     */
    fun fetch(dir: File, file: String, link: String, installedSha256: String, etag: String, lastModified: String): Result {
        val target = File(dir, file)
        val conditional = installedSha256.isNotEmpty() && (etag.isNotEmpty() || lastModified.isNotEmpty()) &&
            target.isFile && sha256Of(target) == installedSha256.lowercase()
        return download(dir, file, link, if (conditional) etag else "", if (conditional) lastModified else "", "", installedSha256, target)
    }

    /** Downloads a resource that must have exactly [expectedSha256] (a pinned file of the APK's manifest). */
    fun fetchExpected(dir: File, file: String, link: String, expectedSha256: String): Result {
        val expected = expectedSha256.trim().lowercase()
        require(Regex("[0-9a-f]{64}").matches(expected)) { "invalid expected SHA-256" }
        return download(dir, file, link, "", "", expected, "", File(dir, file))
    }

    private fun download(
        dir: File,
        file: String,
        link: String,
        etag: String,
        lastModified: String,
        expected: String,
        installedSha256: String,
        target: File,
    ): Result {
        val url = URL(link)
        require(url.protocol == "https" || url.protocol == "http") { "invalid geo resource address" }
        dir.mkdirs()
        var location = url
        var redirects = 0
        while (true) {
            if (cancelled) throw IOException("cancelled")
            val http = location.openConnection() as HttpURLConnection
            connection = http
            try {
                http.connectTimeout = 30_000
                http.readTimeout = 60_000
                http.instanceFollowRedirects = false
                http.setRequestProperty("User-Agent", "fk-box")
                if (etag.isNotEmpty()) http.setRequestProperty("If-None-Match", etag)
                if (lastModified.isNotEmpty()) http.setRequestProperty("If-Modified-Since", lastModified)
                val code = http.responseCode
                if (code in 300..399 && code != HttpURLConnection.HTTP_NOT_MODIFIED) {
                    val next = http.getHeaderField("Location") ?: throw IOException("HTTP $code without a location")
                    if (++redirects > 10) throw IOException("too many redirects")
                    location = URL(location, next)
                    continue
                }
                if (code == HttpURLConnection.HTTP_NOT_MODIFIED && (etag.isNotEmpty() || lastModified.isNotEmpty())) {
                    return Result(target.length(), installedSha256.lowercase(), true, http.getHeaderField("ETag") ?: etag,
                        http.getHeaderField("Last-Modified") ?: lastModified)
                }
                if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code")
                val temp = File(dir, "$file.download-${System.nanoTime()}")
                try {
                    val digest = MessageDigest.getInstance("SHA-256")
                    var size = 0L
                    http.inputStream.use { input ->
                        temp.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                if (cancelled) throw IOException("cancelled")
                                val read = input.read(buffer)
                                if (read < 0) break
                                digest.update(buffer, 0, read)
                                output.write(buffer, 0, read)
                                size += read
                            }
                            output.fd.sync()
                        }
                    }
                    val sum = digest.digest().joinToString("") { "%02x".format(it) }
                    if (expected.isNotEmpty() && sum != expected) throw IOException("$file: SHA-256 $sum, expected $expected")
                    if (size < 64) throw IOException("$file: not a geo database ($size bytes)")
                    if (!temp.renameTo(target)) throw IOException("unable to replace $file")
                    return Result(size, sum, false, http.getHeaderField("ETag") ?: "", http.getHeaderField("Last-Modified") ?: "")
                } finally {
                    temp.delete()
                }
            } finally {
                http.disconnect()
                connection = null
            }
        }
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
