package io.nekohasekai.sfa.bridge

import io.nekohasekai.sfa.database.TypedProfile
import io.nekohasekai.sfa.database.subscriptionInfo

/**
 * The profile metadata the UI reads and a `bbox-1` backup carries. Backups are
 * packed, checked and restored by client-core (crates/client-core/src/backup.rs,
 * `restore_wire_fields`, which also keeps a restored website only as an
 * absolute http/https address).
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
}
