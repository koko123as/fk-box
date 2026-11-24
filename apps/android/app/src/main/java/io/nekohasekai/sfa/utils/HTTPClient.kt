package io.nekohasekai.sfa.utils

import io.nekohasekai.sfa.BuildConfig

/**
 * Subscription User-Agents (the fetch itself is database/CoreKernel.kt;
 * its headers are parsed in client-core,
 * crates/client-core/src/subscription/mod.rs). The single-core brain
 * imports Clash/mihomo YAML with its rules and groups, sing-box JSON and
 * share links; "auto" asks in mihomo's format, which carries the most.
 */
object HTTPClient {
    val userAgent: String get() = "sing-box ${BuildConfig.CORE_VERSION} (fk-box ${BuildConfig.VERSION_NAME})"

    /** [core] picks the User-Agent so the provider answers in that format. */
    fun userAgentFor(core: String): String = when (core) {
        "xray" -> "v2rayN/${BuildConfig.XRAY_VERSION}"
        "sing-box" -> userAgent
        else -> "clash.meta"
    }
}
