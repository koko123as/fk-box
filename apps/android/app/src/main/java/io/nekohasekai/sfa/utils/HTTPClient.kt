package io.nekohasekai.sfa.utils

import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.database.Settings
import java.util.Locale

/**
 * Subscription User-Agents. The fetch itself is libbox's `UISubscriptionGet`,
 * run for client-core through `database/CoreKernel.kt`; its headers are parsed
 * in client-core (`crates/client-core/src/subscription.rs`).
 */
object HTTPClient {
    val userAgent by lazy {
        var userAgent = "SFA (sing-box "
        userAgent += Libbox.version()
        userAgent += "; language "
        userAgent += Locale.getDefault().toLanguageTag().replace("-", "_")
        userAgent += ")"
        userAgent
    }

    /** [core] ("auto" resolves to the first enabled core) picks the User-Agent so the provider answers in that core's format. */
    fun userAgentFor(core: String): String = when (if (core == "auto" || core.isEmpty()) Settings.autoFetchCore() else core) {
        "xray" -> Libbox.coreSubscriptionUserAgent("xray", "")
        "mihomo" -> Libbox.coreSubscriptionUserAgent("mihomo", "")
        else -> userAgent
    }
}
