package io.nekohasekai.sfa.database

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.put

/**
 * Traffic and expiry a provider reports in `subscription-userinfo`
 * (`upload=..; download=..; total=..; expire=..`). Bytes and Unix seconds;
 * 0 means not reported, and an expire of 0 means no expiry. Parsed by
 * `fkbox-client-core` (crates/client-core/src/subscription.rs).
 */
@Serializable
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

/** What one subscription fetch returned: the body and its provider headers, already parsed (client-core `Fetch`). */
@Serializable
data class SubscriptionFetch(
    val content: String = "",
    val info: SubscriptionInfo? = null,
    val updateIntervalMinutes: Int? = null,
    val webPageUrl: String = "",
)

/** The subscription-derived metadata saved on a profile, restorable as one unit (client-core `Metadata`). */
@Serializable
data class SubscriptionMetadata(
    val upload: Long,
    val download: Long,
    val total: Long,
    val expire: Long,
    val webPageUrl: String,
    val autoUpdateInterval: Int,
)

/** How WorkManager should run automatic updates (client-core `Schedule`). */
@Serializable
data class SubscriptionSchedule(val intervalMinutes: Long, val initialDelaySeconds: Long)

/**
 * Subscriptions in `fkbox-client-core`: the fetch (the request itself in
 * libbox, through [CoreKernel]), header parsing, metadata and the automatic
 * update policy. Only the scheduling itself (WorkManager) stays in Kotlin.
 */
internal object CoreSubscriptions {
    /** Fetches [url]; [core] picks the User-Agent so the provider answers in that core's format. Blocks. */
    fun fetch(url: String, core: String): SubscriptionFetch = CoreStore.decode(
        SubscriptionFetch.serializer(),
        CoreStore.callKernel("subscription.fetch") {
            put("url", url)
            put("core", core)
        },
    )

    /** The periodic schedule for automatic updates, null when no profile updates automatically. */
    fun schedule(nowMillis: Long = System.currentTimeMillis()): SubscriptionSchedule? {
        val value = CoreStore.call("subscription.schedule") { put("now", nowMillis) }
        if (value is JsonNull) return null
        return CoreStore.decode(SubscriptionSchedule.serializer(), value)
    }

    /** The profiles whose automatic update is due, in list order. */
    fun due(nowMillis: Long = System.currentTimeMillis()): List<Long> =
        CoreStore.decode(ListSerializer(Long.serializer()), CoreStore.call("subscription.due") { put("now", nowMillis) })

    fun isDue(id: Long, nowMillis: Long = System.currentTimeMillis()): Boolean = CoreStore.decode(
        Boolean.serializer(),
        CoreStore.call("subscription.isDue") {
            put("id", id)
            put("now", nowMillis)
        },
    )
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
 * The metadata a successful [fetch] leaves on this profile (client-core
 * `Metadata::after`): a fetch without `subscription-userinfo` clears stale
 * traffic/expiry; the provider's interval applies only while the user has not chosen one.
 */
fun TypedProfile.metadataAfter(fetch: SubscriptionFetch): SubscriptionMetadata = CoreStore.decode(
    SubscriptionMetadata.serializer(),
    CoreStore.call("subscription.metadataAfter") {
        put("interval", autoUpdateInterval)
        put("intervalUserSet", intervalUserSet)
        put("fetch", CoreStore.encode(SubscriptionFetch.serializer(), fetch.copy(content = "")))
    },
)

val TypedProfile.subscriptionInfo: SubscriptionInfo?
    get() = SubscriptionInfo(upload, download, total, expire).takeUnless { it.isEmpty }
