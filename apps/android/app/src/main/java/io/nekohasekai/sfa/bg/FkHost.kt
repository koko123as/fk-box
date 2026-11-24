package io.nekohasekai.sfa.bg

import io.fkbox.mobile.Mobile
import io.fkbox.mobile.Platform
import io.fkbox.mobile.Stats
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.BuildConfig
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.Locale

/**
 * The fk-box kernel: fkhost.aar (libfkhost.so, arm64-v8a), one sing-box
 * kernel driven by the Rust brain (docs/architecture/brain-android.md). It
 * replaces the three-core libbox.aar.
 *
 * libfkhost.so (the Go runtime) is loaded the first time the service starts,
 * never at app start: [loaded] tells the UI whether it may ask for groups,
 * stats or logs without loading it.
 */
internal object FkHost {
    private val setupLock = Any()

    @Volatile var loaded = false
        private set

    /** Loads the library and prepares its working and temporary directories (idempotent). */
    fun setup() {
        if (loaded) return
        synchronized(setupLock) {
            if (loaded) return
            val app = Application.application
            val working = File(app.filesDir, "fkbox/host").apply { mkdirs() }
            Mobile.setup(working.path, app.cacheDir.path)
            loaded = true
        }
    }

    /** Where the brain keeps fake-ip allocations across service restarts. */
    private fun fakeIpStore(): File = File(Application.application.filesDir, "fkbox/fakeip.json")

    /** Where a profile's manual group selections are kept (one file per profile). */
    fun groupStore(profileId: Long): File = File(Application.application.filesDir, "fkbox/groups/$profileId.json")

    /**
     * The brain's profile options (crates/brain-ffi/src/profile.rs): the
     * persisted stores, the geo data files and the log level. Everything
     * else keeps the brain's defaults (brain-dns.md).
     */
    fun options(profileId: Long): String {
        groupStore(profileId).parentFile?.mkdirs()
        val geo = GeoResources.dataDir()
        return buildJsonObject {
            put("log_level", if (BuildConfig.DEBUG) "info" else "warn")
            put("group_store", groupStore(profileId).path)
            put("geosite", File(geo, GeoResources.fileName(GeoPolicy.GEOSITE)).path)
            put("geoip", File(geo, GeoResources.fileName(GeoPolicy.GEOIP)).path)
            put("mmdb", File(geo, GeoResources.fileName(GeoPolicy.MMDB)).path)
            put("dns", buildJsonObject { put("fake_ip_store", fakeIpStore().path) })
        }.toString()
    }

    fun start(platform: Platform, content: String, profileId: Long) {
        setup()
        Mobile.start(platform, content, options(profileId))
    }

    fun reload(content: String, profileId: Long) {
        setup()
        Mobile.reload(content, options(profileId))
    }

    fun stop() {
        if (!loaded) return
        Mobile.stop()
    }

    fun running(): Boolean = loaded && Mobile.running()

    /** The counters, or null before the library was loaded. */
    fun stats(): Stats? = if (loaded) Mobile.readStats() else null

    /** The brain's group document (brain-groups.md), or null when nothing runs. */
    fun groups(): String? = if (loaded) runCatching { Mobile.groups() }.getOrNull() else null

    fun selectGroup(group: String, member: String) {
        check(loaded) { "The service is not running" }
        Mobile.selectGroup(group, member)
    }

    /** Blocks up to the group's timeout; call it off the main thread. */
    fun testGroup(group: String) {
        check(loaded) { "The service is not running" }
        Mobile.testGroup(group)
    }

    /** The kernel log from [from] on, as {"total", "lines"}; null before the library was loaded. */
    fun logs(from: Int): String? = if (loaded) Mobile.logs(from) else null

    fun clearLogs() {
        if (loaded) Mobile.clearLogs()
    }

    fun freeMemory() {
        if (loaded) Mobile.freeMemory()
    }

    /** The kernel version; loads the library. */
    fun version(): String {
        setup()
        return Mobile.version()
    }

    /** 1536 -> "1.5 KB": binary units, as the traffic lines showed them. */
    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB", "PB")
        var value = bytes.toDouble() / 1024
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        return String.format(Locale.ROOT, if (value < 10) "%.2f %s" else if (value < 100) "%.1f %s" else "%.0f %s", value, units[unit])
    }
}
