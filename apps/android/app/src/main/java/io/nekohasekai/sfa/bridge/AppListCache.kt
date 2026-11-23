package io.nekohasekai.sfa.bridge

import io.nekohasekai.sfa.vendor.PackageQueryManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One package snapshot, with labels loaded only after filtering, like SFA's PackageCache. */
internal class AppListCache(private val query: suspend () -> List<Entry>) {
    companion object {
        /** The installed apps other than this one, through [PackageQueryManager] (Shizuku/root when set). */
        fun installed(activity: android.app.Activity) = AppListCache {
            val packageManager = activity.packageManager
            PackageQueryManager.getInstalledPackages(android.content.pm.PackageManager.GET_PERMISSIONS).mapNotNull { info ->
                if (info.packageName == activity.packageName) return@mapNotNull null
                val application = info.applicationInfo ?: return@mapNotNull null
                Entry(
                    packageName = info.packageName,
                    system = application.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0,
                    internet = info.requestedPermissions?.contains(android.Manifest.permission.INTERNET) == true,
                    updatedAt = info.lastUpdateTime,
                    loadLabel = { application.loadLabel(packageManager).toString() },
                )
            }
        }
    }

    class Entry(
        val packageName: String,
        val system: Boolean,
        val internet: Boolean,
        val updatedAt: Long,
        loadLabel: () -> String,
    ) {
        val name by lazy(loadLabel)
    }

    private val mutex = Mutex()
    private var queryKey: String? = null
    private var packages: List<Entry>? = null

    /** The app lists closed: the snapshot (and the labels it loaded) goes until the next list. */
    suspend fun clear() = mutex.withLock {
        packages = null
        queryKey = null
    }

    suspend fun list(
        key: String,
        refresh: Boolean = false,
        showSystem: Boolean = false,
        showOffline: Boolean = false,
    ): List<Map<String, Any>> = mutex.withLock {
        if (refresh || key != queryKey || packages == null) {
            // A failed refresh must remain retryable, not silently reuse an old package snapshot.
            packages = null
            packages = query()
            queryKey = key
        }
        packages!!.asSequence()
            .filter { (showSystem || !it.system) && (showOffline || it.internet) }
            .map { entry ->
                mapOf(
                    "package" to entry.packageName,
                    "name" to entry.name,
                    "system" to entry.system,
                    "internet" to entry.internet,
                    "updatedAt" to entry.updatedAt,
                )
            }.toList()
    }
}
