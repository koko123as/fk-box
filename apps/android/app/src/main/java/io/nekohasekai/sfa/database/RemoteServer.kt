package io.nekohasekai.sfa.database

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable
import java.net.URI

/** A remote control server, kept in the client store ([RemoteServerManager]). */
@Serializable
@Parcelize
class RemoteServer(
    var id: Long = 0L,
    var userOrder: Long = 0L,
    var name: String = "",
    var url: String = "",
    var secret: String = "",
) : Parcelable {
    val displayName: String
        get() = name.ifEmpty { hostPort(url) }

    companion object {
        private val schemePrefix = Regex("^https?://", RegexOption.IGNORE_CASE)
        private val httpPrefix = Regex("^http://", RegexOption.IGNORE_CASE)

        // The stored form: scheme-less for http (default), keeping an explicit https.
        fun normalizeURL(urlString: String): String = urlString.trim().trimEnd('/').replaceFirst(httpPrefix, "")

        // The form passed to libbox: a scheme is required, defaulting to http.
        fun connectURL(urlString: String): String {
            val value = urlString.trim().trimEnd('/')
            if (value.isEmpty()) {
                return ""
            }
            if (value.contains(schemePrefix)) {
                return value
            }
            return "http://$value"
        }

        fun validateURL(urlString: String): String? {
            val connectURL = connectURL(urlString)
            if (connectURL.isEmpty()) {
                return null
            }
            val uri =
                try {
                    URI(connectURL)
                } catch (_: Exception) {
                    return null
                }
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") {
                return null
            }
            if (uri.host.isNullOrEmpty()) {
                return null
            }
            return normalizeURL(urlString)
        }

        private fun hostPort(urlString: String): String {
            val uri =
                try {
                    URI(connectURL(urlString))
                } catch (_: Exception) {
                    return urlString
                }
            val host = uri.host ?: return urlString
            return if (uri.port != -1) "$host:${uri.port}" else host
        }
    }
}
