package io.nekohasekai.sfa.database

import android.util.Log
import io.nekohasekai.sfa.fkcore.FkLib
import androidx.room.Room
import androidx.room.withTransaction
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.constant.Path
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Profiles and remote servers. The rows live in libbox's profile store
 * (core/core_profile_store.go, `databases/profiles-store.json`); this object
 * keeps the API the app used over Room and raises the same change callbacks.
 *
 * The first time this version runs, the rows of the Room database
 * (`profiles.db`, version 3) are imported once: Go stages them, the staged
 * file is read back and compared field by field with Room, and only then is
 * it put in place. Room is only read, never changed or deleted, so an older
 * APK still finds every profile as it was at the import; changes made after
 * the import live only in the store. If the import fails, this process keeps
 * using Room exactly as before and the import is tried again next start.
 */
@Suppress("RedundantSuspendModifier")
object ProfileManager {
    private const val TAG = "ProfileManager"
    private const val PROFILES = "profiles"
    private const val SERVERS = "remote_servers"

    private val callbacks = CopyOnWriteArrayList<() -> Unit>()

    fun registerCallback(callback: () -> Unit) {
        callbacks.add(callback)
    }

    fun unregisterCallback(callback: () -> Unit) {
        callbacks.remove(callback)
    }

    private fun notifyChanged() {
        for (callback in callbacks) {
            callback()
        }
    }

    /** The rows, wherever they live: [GoStore] normally, [RoomStore] when the import could not run. */
    private interface Store {
        suspend fun nextOrder(table: String): Long
        suspend fun getProfile(id: Long): Profile?
        suspend fun listProfiles(): List<Profile>
        suspend fun insert(profile: Profile): Long
        suspend fun updateProfiles(profiles: List<Profile>): Int
        suspend fun deleteProfiles(profiles: List<Profile>): Int
        suspend fun getServer(id: Long): RemoteServer?
        suspend fun listServers(): List<RemoteServer>
        suspend fun insert(server: RemoteServer): Long
        suspend fun updateServers(servers: List<RemoteServer>): Int
        suspend fun deleteServers(servers: List<RemoteServer>): Int
        suspend fun <T> transaction(block: suspend () -> T): T
    }

    private object GoStore : Store {
        /** The open transaction of the calling coroutine, as Room's withTransaction scopes its own. */
        private class Transaction(val token: Long) : AbstractCoroutineContextElement(Transaction) {
            companion object Key : CoroutineContext.Key<Transaction>
        }

        private suspend fun tx(): Long = currentCoroutineContext()[Transaction]?.token ?: 0L

        override suspend fun nextOrder(table: String): Long = FkLib.uiProfileStoreNextOrder(tx(), table)

        override suspend fun getProfile(id: Long): Profile? =
            ProfileStoreCodec.parse(FkLib.uiProfileStoreGet(tx(), PROFILES, id)).takeUnless { it is JsonNull }?.let(ProfileStoreCodec::decodeProfile)

        override suspend fun listProfiles(): List<Profile> = ProfileStoreCodec.decodeProfiles(FkLib.uiProfileStoreList(tx(), PROFILES))

        override suspend fun insert(profile: Profile): Long = FkLib.uiProfileStoreInsert(tx(), PROFILES, ProfileStoreCodec.encode(profile).toString())

        override suspend fun updateProfiles(profiles: List<Profile>): Int =
            FkLib.uiProfileStoreUpdate(tx(), PROFILES, ProfileStoreCodec.encodeProfiles(profiles)).toInt()

        override suspend fun deleteProfiles(profiles: List<Profile>): Int =
            FkLib.uiProfileStoreDelete(tx(), PROFILES, ProfileStoreCodec.encodeIds(profiles.map { it.id })).toInt()

        override suspend fun getServer(id: Long): RemoteServer? =
            ProfileStoreCodec.parse(FkLib.uiProfileStoreGet(tx(), SERVERS, id)).takeUnless { it is JsonNull }?.let(ProfileStoreCodec::decodeServer)

        override suspend fun listServers(): List<RemoteServer> = ProfileStoreCodec.decodeServers(FkLib.uiProfileStoreList(tx(), SERVERS))

        override suspend fun insert(server: RemoteServer): Long = FkLib.uiProfileStoreInsert(tx(), SERVERS, ProfileStoreCodec.encode(server).toString())

        override suspend fun updateServers(servers: List<RemoteServer>): Int =
            FkLib.uiProfileStoreUpdate(tx(), SERVERS, ProfileStoreCodec.encodeServers(servers)).toInt()

        override suspend fun deleteServers(servers: List<RemoteServer>): Int =
            FkLib.uiProfileStoreDelete(tx(), SERVERS, ProfileStoreCodec.encodeIds(servers.map { it.id })).toInt()

        override suspend fun <T> transaction(block: suspend () -> T): T {
            // Nested calls join the open transaction, as Room's do.
            if (currentCoroutineContext()[Transaction] != null) return block()
            val token = FkLib.uiProfileStoreBegin()
            var ended = false
            try {
                val result = withContext(Transaction(token)) { block() }
                ended = true
                withContext(NonCancellable) { FkLib.uiProfileStoreCommit(token) }
                return result
            } finally {
                if (!ended) withContext(NonCancellable) { runCatching { FkLib.uiProfileStoreRollback(token) } }
            }
        }
    }

    private class RoomStore(private val database: ProfileDatabase) : Store {
        override suspend fun nextOrder(table: String): Long =
            (if (table == PROFILES) database.profileDao().nextOrder() else database.remoteServerDao().nextOrder()) ?: 0

        override suspend fun getProfile(id: Long): Profile? = database.profileDao().get(id)

        override suspend fun listProfiles(): List<Profile> = database.profileDao().list()

        override suspend fun insert(profile: Profile): Long = database.profileDao().insert(profile)

        override suspend fun updateProfiles(profiles: List<Profile>): Int = database.profileDao().update(profiles)

        override suspend fun deleteProfiles(profiles: List<Profile>): Int = database.profileDao().delete(profiles)

        override suspend fun getServer(id: Long): RemoteServer? = database.remoteServerDao().get(id)

        override suspend fun listServers(): List<RemoteServer> = database.remoteServerDao().list()

        override suspend fun insert(server: RemoteServer): Long = database.remoteServerDao().insert(server)

        override suspend fun updateServers(servers: List<RemoteServer>): Int = database.remoteServerDao().update(servers)

        override suspend fun deleteServers(servers: List<RemoteServer>): Int = servers.sumOf { database.remoteServerDao().delete(it) }

        override suspend fun <T> transaction(block: suspend () -> T): T = database.withTransaction(block)
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun openRoom(): ProfileDatabase = Room
        .databaseBuilder(
            Application.application,
            ProfileDatabase::class.java,
            Path.PROFILES_DATABASE_PATH,
        )
        .addMigrations(ProfileDatabase.MIGRATION_1_2, ProfileDatabase.MIGRATION_2_3)
        .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = false)
        .enableMultiInstanceInvalidation()
        .setQueryExecutor { GlobalScope.launch { it.run() } }
        .build()

    // A store that exists but cannot be read is an error on every call (and
    // retried on the next), never a reason to import again or fall back to
    // Room's older rows.
    private val store: Store by lazy<Store> {
        val roomFile = Application.application.getDatabasePath(Path.PROFILES_DATABASE_PATH)
        roomFile.parentFile?.mkdirs()
        val storeFile = Application.application.getDatabasePath(Path.PROFILES_STORE_PATH)
        if (FkLib.uiProfileStoreOpen(storeFile.path)) return@lazy GoStore
        // A fresh install has no Room database; do not create one just to read nothing.
        val room = if (roomFile.exists()) openRoom() else null
        try {
            importFromRoom(room, storeFile)
        } catch (e: Exception) {
            Log.e(TAG, "Profile import from Room failed; keeping Room for now", e)
            return@lazy RoomStore(room ?: openRoom())
        }
        // Imported: from here on the store is the only place rows change.
        runCatching { room?.close() }
        GoStore
    }

    private fun importFromRoom(room: ProfileDatabase?, storeFile: File) {
        val profiles = room?.profileDao()?.list().orEmpty()
        val servers = room?.remoteServerDao()?.list().orEmpty()
        val sequences = mutableMapOf<String, Long>()
        room?.openHelper?.readableDatabase?.query("SELECT name, seq FROM sqlite_sequence")?.use { cursor ->
            while (cursor.moveToNext()) sequences[cursor.getString(0)] = cursor.getLong(1)
        }
        val dataset = ProfileStoreCodec.importDataset(sequences[PROFILES] ?: 0L, sequences[SERVERS] ?: 0L, profiles, servers)
        val readBack = FkLib.uiProfileStoreStageImport(storeFile.path, dataset)
        ProfileStoreCodec.importMismatch(readBack, profiles, servers)?.let { error("Profile import check failed: $it") }
        val digest = ProfileStoreCodec.parse(readBack).jsonObject["sha256"]!!.jsonPrimitive.content
        FkLib.uiProfileStoreCommitImport(storeFile.path, digest)
        Log.i(TAG, "Imported ${profiles.size} profiles and ${servers.size} remote servers from Room")
    }

    suspend fun nextOrder(): Long = store.nextOrder(PROFILES)

    suspend fun get(id: Long): Profile? = store.getProfile(id)

    suspend fun create(profile: Profile, andSelect: Boolean = false): Profile {
        profile.id = store.insert(profile)
        if (andSelect) {
            Settings.selectedProfile = profile.id
        }
        notifyChanged()
        return profile
    }

    suspend fun update(profile: Profile): Int {
        try {
            return store.updateProfiles(listOf(profile))
        } finally {
            notifyChanged()
        }
    }

    suspend fun update(profiles: List<Profile>): Int {
        try {
            return store.updateProfiles(profiles)
        } finally {
            notifyChanged()
        }
    }

    suspend fun delete(profile: Profile): Int {
        try {
            return store.deleteProfiles(listOf(profile))
        } finally {
            notifyChanged()
        }
    }

    suspend fun delete(profiles: List<Profile>): Int {
        try {
            return store.deleteProfiles(profiles)
        } finally {
            notifyChanged()
        }
    }

    suspend fun list(): List<Profile> = store.listProfiles()

    /** Profile and server changes in [block] are kept together or not at all. */
    internal suspend fun <T> transaction(block: suspend () -> T): T = store.transaction(block)

    internal suspend fun nextServerOrder(): Long = store.nextOrder(SERVERS)

    internal suspend fun getServer(id: Long): RemoteServer? = store.getServer(id)

    internal suspend fun listServers(): List<RemoteServer> = store.listServers()

    internal suspend fun insertServer(server: RemoteServer): Long = store.insert(server)

    internal suspend fun updateServers(servers: List<RemoteServer>): Int = store.updateServers(servers)

    internal suspend fun deleteServer(server: RemoteServer): Int = store.deleteServers(listOf(server))
}
