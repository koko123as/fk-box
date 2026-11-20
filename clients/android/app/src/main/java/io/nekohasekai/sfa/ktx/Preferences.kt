package io.nekohasekai.sfa.ktx

import io.nekohasekai.sfa.database.preference.RoomPreferenceDataStore
import kotlinx.serialization.json.Json
import kotlin.reflect.KProperty

fun RoomPreferenceDataStore.string(name: String, defaultValue: () -> String = { "" }) = PreferenceProxy(name, defaultValue, ::getString, ::putString)


fun RoomPreferenceDataStore.boolean(name: String, defaultValue: () -> Boolean = { false }) = PreferenceProxy(name, defaultValue, ::getBoolean, ::putBoolean)

fun RoomPreferenceDataStore.int(name: String, defaultValue: () -> Int = { 0 }) = PreferenceProxy(name, defaultValue, ::getInt, ::putInt)



fun RoomPreferenceDataStore.long(name: String, defaultValue: () -> Long = { 0L }) = PreferenceProxy(name, defaultValue, ::getLong, ::putLong)


fun RoomPreferenceDataStore.stringSet(name: String, defaultValue: () -> Set<String> = { emptySet() }) = PreferenceProxy(name, defaultValue, ::getStringSet, ::putStringSet)

fun RoomPreferenceDataStore.map(name: String, defaultValue: () -> Map<String, String> = { emptyMap() }) = PreferenceProxy(
    name,
    defaultValue,
    { key, default ->
        val json = getString(key, null)
        if (json.isNullOrBlank()) {
            default
        } else {
            try {
                Json.decodeFromString<Map<String, String>>(json)
            } catch (_: Exception) {
                default
            }
        }
    },
    { key, value -> putString(key, Json.encodeToString(value)) },
)

class PreferenceProxy<T>(
    val name: String,
    val defaultValue: () -> T,
    val getter: (String, T) -> T?,
    val setter: (String, value: T) -> Unit,
) {
    operator fun setValue(thisObj: Any?, property: KProperty<*>, value: T) = setter(name, value)

    operator fun getValue(thisObj: Any?, property: KProperty<*>) = getter(name, defaultValue())!!
}
