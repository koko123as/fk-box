package io.nekohasekai.sfa.utils

import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.database.SubscriptionFetch
import io.nekohasekai.sfa.database.SubscriptionHeaders
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

        fun userAgentFor(core: String): String = when (if (core == "auto" || core.isEmpty()) Settings.autoFetchCore() else core) {
            "xray" -> Libbox.coreSubscriptionUserAgent("xray", "")
            "mihomo" -> Libbox.coreSubscriptionUserAgent("mihomo", "")
            else -> userAgent
        }
    }

    /**
     * Fetches a subscription; [core] picks the User-Agent so the provider answers in
     * that core's format. Provider traffic/expiry/update headers come back parsed.
     */
    fun getSubscription(url: String, core: String = "sing-box"): SubscriptionFetch {
        // gomobile lowercases the initial uppercase run in UISubscriptionGet.
        val response = Libbox.uiSubscriptionGet(url, userAgentFor(core))
        return SubscriptionHeaders.fetch(response.content, response.userInfo, response.updateInterval, response.webPage)
    }

    override fun close() = Unit
}
