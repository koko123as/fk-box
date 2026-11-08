package io.nekohasekai.sfa.bg

import java.util.concurrent.ConcurrentHashMap

/**
 * uid → package names for connection owner lookups. sing-box asks for the
 * owner of every new connection; the package list only changes when a
 * package is added, replaced or removed, which clears the cache
 * (AppChangeReceiver).
 */
object UidPackageCache {
    private val packages = ConcurrentHashMap<Int, Array<String>>()

    fun get(uid: Int, lookup: (Int) -> Array<String>?): Array<String> =
        packages[uid] ?: (lookup(uid) ?: emptyArray()).also { packages[uid] = it }

    fun clear() = packages.clear()
}
