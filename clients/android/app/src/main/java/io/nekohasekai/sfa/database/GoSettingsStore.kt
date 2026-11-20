package io.nekohasekai.sfa.database

import io.nekohasekai.libbox.UISettingsStore

/**
 * [Settings] as libbox reads it (core/core_settings.go): the same keys, the
 * stored values and Go's defaults for keys never written. Reads come from the
 * store's in-memory cache (a ConcurrentHashMap), so Go may call from any
 * goroutine; the first read loads the cache from Room, which callers already
 * do off the main thread (Settings.dataStore.initialize before setupLibbox).
 */
internal object GoSettingsStore : UISettingsStore {
    override fun getBool(key: String, defaultValue: Boolean): Boolean = Settings.dataStore.getBoolean(key, defaultValue)

    override fun getLong(key: String, defaultValue: Long): Long = Settings.dataStore.getLong(key, defaultValue)

    override fun getString(key: String, defaultValue: String): String = Settings.dataStore.getString(key, defaultValue) ?: defaultValue
}
