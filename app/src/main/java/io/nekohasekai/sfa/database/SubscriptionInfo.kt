package io.nekohasekai.sfa.database

import java.net.URI
import java.util.Locale

/**
 * Traffic and expiry a provider reports in `subscription-userinfo`
 * (`upload=..; download=..; total=..; expire=..`). Bytes and Unix seconds;
 * 0 means not reported, and an expire of 0 means no expiry.
 */
data class SubscriptionInfo(
    val upload: Long = 0L,
    val download: Long = 0L,
    val total: Long = 0L,
    val expire: Long = 0L,
) {
    val used: Long get() = saturatedAdd(upload, download)
    val remaining: Long get() = (total - used).coerceAtLeast(0L)

    val isEmpty: Boolean get() = total <= 0 && expire <= 0 && upload <= 0 && download <= 0

    companion object {
        private fun saturatedAdd(a: Long, b: Long): Long = if (Long.MAX_VALUE - a < b) Long.MAX_VALUE else a + b
    }
}

/** What one subscription fetch returned: the body and its provider headers, already parsed. */
data class SubscriptionFetch(
    val content: String,
    val info: SubscriptionInfo? = null,
    val updateIntervalMinutes: Int? = null,
    val webPageUrl: String = "",
)

/** The subscription-derived metadata saved on a profile, restorable as one unit. */
data class SubscriptionMetadata(
    val upload: Long,
    val download: Long,
    val total: Long,
    val expire: Long,
    val webPageUrl: String,
    val autoUpdateInterval: Int,
)

/**
 * subscription-userinfo and profile-update-interval are parsed by libbox
 * (core_subscription_info.go) and arrive with the fetch; the provider's web
 * page address is checked here with java.net.URI.
 */
object SubscriptionHeaders {
    /** Android WorkManager's minimum periodic interval. */
    const val MIN_INTERVAL_MINUTES = 15

    /** `profile-web-page-url`, accepted only as an absolute http/https address. */
    fun parseWebPageUrl(header: String?): String {
        val value = header?.trim().orEmpty()
        if (value.isEmpty() || value.length > 4096) return ""
        val uri = runCatching { URI(value) }.getOrNull() ?: return ""
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return ""
        if (uri.host.isNullOrBlank()) return ""
        return value
    }
}

/** Current subscription metadata of this profile. */
fun TypedProfile.subscriptionMetadata() = SubscriptionMetadata(upload, download, total, expire, webPageUrl, autoUpdateInterval)

fun TypedProfile.restoreSubscriptionMetadata(metadata: SubscriptionMetadata) {
    upload = metadata.upload
    download = metadata.download
    total = metadata.total
    expire = metadata.expire
    webPageUrl = metadata.webPageUrl
    autoUpdateInterval = metadata.autoUpdateInterval
}

/**
 * The metadata a successful [fetch] leaves on this profile. A fetch without
 * `subscription-userinfo` clears stale traffic/expiry instead of keeping it;
 * the provider's update interval applies only while the user has not chosen one.
 */
fun TypedProfile.metadataAfter(fetch: SubscriptionFetch): SubscriptionMetadata {
    val info = fetch.info ?: SubscriptionInfo()
    val interval = fetch.updateIntervalMinutes?.takeIf { !intervalUserSet }
        ?.coerceAtLeast(SubscriptionHeaders.MIN_INTERVAL_MINUTES) ?: autoUpdateInterval
    return SubscriptionMetadata(info.upload, info.download, info.total, info.expire, fetch.webPageUrl, interval)
}

val TypedProfile.subscriptionInfo: SubscriptionInfo?
    get() = SubscriptionInfo(upload, download, total, expire).takeUnless { it.isEmpty }
