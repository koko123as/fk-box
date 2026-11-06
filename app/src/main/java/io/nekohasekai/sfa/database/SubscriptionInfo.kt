package io.nekohasekai.sfa.database

import java.net.URI
import java.util.Locale
import kotlin.math.roundToLong

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

    /** Used share of the quota in 0..1, or null when the provider gave no total. */
    val usage: Double? get() = if (total > 0) (used.toDouble() / total.toDouble()).coerceIn(0.0, 1.0) else null

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

object SubscriptionHeaders {
    /** Android WorkManager's minimum periodic interval. */
    const val MIN_INTERVAL_MINUTES = 15

    /** One year; anything longer is a provider error, not an update hint. */
    const val MAX_INTERVAL_MINUTES = 365 * 24 * 60

    /**
     * Parses `subscription-userinfo`. Keys are case-insensitive, spaces are
     * tolerated, missing keys read as 0, decimals are truncated and negative or
     * non-numeric values are ignored. Returns null when nothing usable is present.
     */
    fun parseUserInfo(header: String?): SubscriptionInfo? {
        if (header.isNullOrBlank()) return null
        val values = HashMap<String, Long>()
        for (field in header.split(';')) {
            val separator = field.indexOf('=')
            if (separator <= 0) continue
            val key = field.substring(0, separator).trim().lowercase(Locale.ROOT)
            if (key !in KEYS) continue
            val value = parseCount(field.substring(separator + 1)) ?: continue
            values[key] = value
        }
        if (values.isEmpty()) return null
        return SubscriptionInfo(
            upload = values["upload"] ?: 0L,
            download = values["download"] ?: 0L,
            total = values["total"] ?: 0L,
            expire = values["expire"] ?: 0L,
        )
    }

    /** `profile-update-interval` is in hours; returns minutes clamped to Android's minimum. */
    fun parseUpdateIntervalMinutes(header: String?): Int? {
        val hours = header?.trim()?.toDoubleOrNull() ?: return null
        if (!hours.isFinite() || hours <= 0) return null
        val minutes = (hours * 60).coerceAtMost(MAX_INTERVAL_MINUTES.toDouble()).roundToLong()
        return minutes.toInt().coerceIn(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES)
    }

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

    fun fetch(content: String, userInfo: String?, updateInterval: String?, webPage: String?) = SubscriptionFetch(
        content = content,
        info = parseUserInfo(userInfo),
        updateIntervalMinutes = parseUpdateIntervalMinutes(updateInterval),
        webPageUrl = parseWebPageUrl(webPage),
    )

    private val KEYS = setOf("upload", "download", "total", "expire")

    private fun parseCount(raw: String): Long? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        value.toLongOrNull()?.let { return it.takeIf { count -> count >= 0 } }
        val decimal = value.toDoubleOrNull() ?: return null
        if (!decimal.isFinite() || decimal < 0) return null
        return if (decimal >= Long.MAX_VALUE.toDouble()) Long.MAX_VALUE else decimal.toLong()
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
