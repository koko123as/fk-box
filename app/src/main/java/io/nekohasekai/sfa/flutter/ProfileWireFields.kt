package io.nekohasekai.sfa.flutter

import io.nekohasekai.sfa.database.SubscriptionHeaders
import io.nekohasekai.sfa.database.TypedProfile
import io.nekohasekai.sfa.database.subscriptionInfo
import java.util.Date

/**
 * The profile metadata the UI reads and a `bbox-1` backup carries, kept in one
 * place so a backup restores every field it saved (core included). Entries from
 * older backups lack core and subscription keys and restore as auto / none.
 */
internal object ProfileWireFields {
    fun export(typed: TypedProfile): Map<String, Any> = buildMap {
        put("remote", typed.type == TypedProfile.Type.Remote)
        put("url", typed.remoteURL)
        put("updatedAt", typed.lastUpdated.time)
        put("autoUpdate", typed.autoUpdate)
        put("interval", typed.autoUpdateInterval)
        put("core", typed.core)
        // Absent keys mean the provider reported no traffic or expiry.
        typed.subscriptionInfo?.let { info ->
            put("upload", info.upload)
            put("download", info.download)
            put("total", info.total)
            put("expire", info.expire)
        }
        if (typed.webPageUrl.isNotEmpty()) put("webPageUrl", typed.webPageUrl)
        put("intervalUserSet", typed.intervalUserSet)
    }

    /** Rejects malformed values; every key is optional. */
    fun validate(entry: Map<String, Any?>) {
        entry["core"]?.let { require(it is String && it in TypedProfile.CORES) { "Invalid profile core" } }
        for (key in listOf("upload", "download", "total", "expire")) {
            entry[key]?.let { require(it is Number && it.toDouble().isFinite() && it.toDouble() >= 0) { "Invalid subscription $key" } }
        }
        entry["webPageUrl"]?.let { require(it is String) { "Invalid subscription website" } }
        entry["intervalUserSet"]?.let { require(it is Boolean) { "Invalid update interval option" } }
    }

    fun declaredCore(entry: Map<String, Any?>): String =
        (entry["core"] as? String)?.takeIf { it in TypedProfile.CORES } ?: TypedProfile.CORE_AUTO

    /** Writes [entry]'s metadata onto [typed]; path, name and order are the caller's. */
    fun restore(typed: TypedProfile, entry: Map<String, Any?>) {
        typed.remoteURL = entry["url"] as? String ?: ""
        val remote = typed.remoteURL.isNotEmpty()
        typed.type = if (remote) TypedProfile.Type.Remote else TypedProfile.Type.Local
        typed.autoUpdate = remote && entry["autoUpdate"] == true
        typed.autoUpdateInterval = (entry["interval"] as? Number)?.toInt() ?: TypedProfile.DEFAULT_INTERVAL
        typed.lastUpdated = Date((entry["updatedAt"] as? Number)?.toLong() ?: 0L)
        typed.core = declaredCore(entry)
        typed.intervalUserSet = entry["intervalUserSet"] as? Boolean ?: (typed.autoUpdateInterval != TypedProfile.DEFAULT_INTERVAL)
        fun count(key: String): Long = if (remote) (entry[key] as? Number)?.toDouble()?.takeIf { it.isFinite() && it >= 0 }
            ?.let { if (it >= Long.MAX_VALUE.toDouble()) Long.MAX_VALUE else it.toLong() } ?: 0L else 0L
        typed.upload = count("upload")
        typed.download = count("download")
        typed.total = count("total")
        typed.expire = count("expire")
        typed.webPageUrl = if (remote) SubscriptionHeaders.parseWebPageUrl(entry["webPageUrl"] as? String) else ""
    }
}
