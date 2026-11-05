package io.nekohasekai.sfa.utils

import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.ktx.unwrap
import java.io.Closeable
import java.util.Locale

class HTTPClient : Closeable {
    companion object {
        val userAgent by lazy {
            var userAgent = "SFA (sing-box "
            userAgent += Libbox.version()
            userAgent += "; language "
            userAgent += Locale.getDefault().toLanguageTag().replace("-", "_")
            userAgent += ")"
            userAgent
        }

        fun userAgentFor(core: String): String = when (core) {
            "xray", "mihomo" -> Libbox.coreSubscriptionUserAgent(core, "")
            else -> userAgent
        }
    }

    private val client = Libbox.newHTTPClient()

    init {
        client.modernTLS()
    }

    /** [core] picks the User-Agent so the provider answers in that core's format. */
    fun getString(url: String, core: String = "sing-box"): String {
        val request = client.newRequest()
        request.setUserAgent(userAgentFor(core))
        request.setURL(url)
        val response = request.execute()
        return response.content.unwrap
    }

    override fun close() {
        client.close()
    }
}
