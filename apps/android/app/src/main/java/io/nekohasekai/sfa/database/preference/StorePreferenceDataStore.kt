package io.nekohasekai.sfa.database.preference

import android.util.Log
import io.nekohasekai.sfa.database.CoreStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

/**
 * Settings key/value store backed by the client store ([CoreStore], table
 * `settings`): read from an in-memory cache and written in order off the
 * caller thread. Value kinds keep the codes the Room store used.
 */
class StorePreferenceDataStore {
    private class Entry(val kind: Int, val value: JsonElement)

    private val writeAccess = Any()
    private val pendingWrites = Channel<() -> Unit>(Channel.UNLIMITED)
    private val cache: MutableMap<String, Entry> by lazy {
        ConcurrentHashMap<String, Entry>().apply {
            for ((key, item) in CoreStore.call("settings.all").jsonObject) {
                val entry = item.jsonObject
                put(key, Entry(entry.getValue("t").jsonPrimitive.int, entry.getValue("v")))
            }
        }
    }

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            for (write in pendingWrites) {
                try {
                    write()
                } catch (exception: Exception) {
                    Log.e("StorePreferenceDataStore", "write preferences", exception)
                }
            }
        }
    }

    suspend fun initialize() = withContext(Dispatchers.IO) {
        cache.size
        Unit
    }

    suspend fun flush() {
        val completion = CompletableDeferred<Unit>()
        synchronized(writeAccess) {
            check(pendingWrites.trySend { completion.complete(Unit) }.isSuccess)
        }
        completion.await()
    }

    private fun put(key: String, kind: Int, value: JsonElement) {
        val values = cache
        synchronized(writeAccess) {
            values[key] = Entry(kind, value)
            check(pendingWrites.trySend {
                CoreStore.call("settings.put") {
                    put("key", key)
                    put("t", kind)
                    put("v", value)
                }
            }.isSuccess)
        }
        fireChangeListener(key)
    }

    private fun read(key: String, kind: Int): JsonElement? = cache[key]?.takeIf { it.kind == kind }?.value

    fun getBoolean(key: String, defValue: Boolean) = read(key, TYPE_BOOLEAN)?.jsonPrimitive?.booleanOrNull ?: defValue

    fun getInt(key: String, defValue: Int) = read(key, TYPE_LONG)?.jsonPrimitive?.longOrNull?.toInt() ?: defValue

    fun getLong(key: String, defValue: Long) = read(key, TYPE_LONG)?.jsonPrimitive?.longOrNull ?: defValue

    fun getString(key: String, defValue: String?) = read(key, TYPE_STRING)?.jsonPrimitive?.contentOrNull ?: defValue

    fun getStringSet(key: String, defValue: Set<String>?): Set<String>? =
        (read(key, TYPE_STRING_SET) as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet() ?: defValue

    fun putBoolean(key: String, value: Boolean) = put(key, TYPE_BOOLEAN, JsonPrimitive(value))

    fun putInt(key: String, value: Int) = put(key, TYPE_LONG, JsonPrimitive(value.toLong()))

    fun putLong(key: String, value: Long) = put(key, TYPE_LONG, JsonPrimitive(value))

    fun putString(key: String, value: String?) = if (value == null) {
        remove(key)
    } else {
        put(key, TYPE_STRING, JsonPrimitive(value))
    }

    fun putStringSet(key: String, values: Set<String>?) = if (values == null) {
        remove(key)
    } else {
        put(key, TYPE_STRING_SET, JsonArray(values.map(::JsonPrimitive)))
    }

    fun remove(key: String) {
        val values = cache
        synchronized(writeAccess) {
            values.remove(key)
            check(pendingWrites.trySend { CoreStore.call("settings.remove") { put("key", key) } }.isSuccess)
        }
        fireChangeListener(key)
    }

    private val listeners = HashSet<OnPreferenceDataStoreChangeListener>()

    private fun fireChangeListener(key: String) {
        val listeners =
            synchronized(listeners) {
                listeners.toList()
            }
        listeners.forEach { it.onPreferenceDataStoreChanged(this, key) }
    }

    fun registerChangeListener(listener: OnPreferenceDataStoreChangeListener) {
        synchronized(listeners) {
            listeners.add(listener)
        }
    }

    fun unregisterChangeListener(listener: OnPreferenceDataStoreChangeListener) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

    companion object {
        // KeyValueEntity.TYPE_* of the Room store, kept by the client store.
        const val TYPE_BOOLEAN = 1
        const val TYPE_LONG = 3
        const val TYPE_STRING = 4
        const val TYPE_STRING_SET = 5
    }
}
