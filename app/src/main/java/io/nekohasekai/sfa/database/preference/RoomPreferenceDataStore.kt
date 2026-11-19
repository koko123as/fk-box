package io.nekohasekai.sfa.database.preference

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** Settings key/value store backed by Room, read from an in-memory cache and written in order off the caller thread. */
class RoomPreferenceDataStore(createDao: () -> KeyValueEntity.Dao) {
    private val kvPairDao by lazy(createDao)
    private val writeAccess = Any()
    private val pendingWrites = Channel<() -> Unit>(Channel.UNLIMITED)
    private val cache: MutableMap<String, KeyValueEntity> by lazy {
        ConcurrentHashMap<String, KeyValueEntity>().apply {
            kvPairDao.all().forEach { put(it.key, it) }
        }
    }

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            for (write in pendingWrites) {
                try {
                    write()
                } catch (exception: Exception) {
                    Log.e("RoomPreferenceDataStore", "write preferences", exception)
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

    private fun put(entity: KeyValueEntity) {
        val values = cache
        synchronized(writeAccess) {
            values[entity.key] = entity
            check(pendingWrites.trySend { kvPairDao.put(entity) }.isSuccess)
        }
        fireChangeListener(entity.key)
    }

    fun getBoolean(key: String, defValue: Boolean) = cache[key]?.boolean ?: defValue

    fun getInt(key: String, defValue: Int) = cache[key]?.long?.toInt() ?: defValue

    fun getLong(key: String, defValue: Long) = cache[key]?.long ?: defValue

    fun getString(key: String, defValue: String?) = cache[key]?.string ?: defValue

    fun getStringSet(key: String, defValue: Set<String>?) = cache[key]?.stringSet ?: defValue

    fun putBoolean(key: String, value: Boolean) = put(KeyValueEntity(key).put(value))

    fun putInt(key: String, value: Int) = put(KeyValueEntity(key).put(value.toLong()))

    fun putLong(key: String, value: Long) = put(KeyValueEntity(key).put(value))

    fun putString(key: String, value: String?) = if (value == null) {
        remove(key)
    } else {
        put(KeyValueEntity(key).put(value))
    }

    fun putStringSet(key: String, values: Set<String>?) = if (values == null) {
        remove(key)
    } else {
        put(KeyValueEntity(key).put(values))
    }

    fun remove(key: String) {
        val values = cache
        synchronized(writeAccess) {
            values.remove(key)
            check(pendingWrites.trySend { kvPairDao.delete(key) }.isSuccess)
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
}
