package io.nekohasekai.sfa.database

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** The profile list, kept in the client store ([CoreStore]). */
@Suppress("RedundantSuspendModifier")
object ProfileManager {
    private val callbacks = mutableListOf<() -> Unit>()

    fun registerCallback(callback: () -> Unit) {
        callbacks.add(callback)
    }

    fun unregisterCallback(callback: () -> Unit) {
        callbacks.remove(callback)
    }

    private fun changed() {
        for (callback in callbacks.toList()) {
            callback()
        }
    }

    suspend fun nextOrder(): Long = CoreStore.call("profiles.nextOrder").jsonPrimitive.long

    suspend fun get(id: Long): Profile? {
        val value = CoreStore.call("profiles.get") { put("id", id) }
        if (value is JsonNull) return null
        return CoreStore.decode(StoredProfile.serializer(), value).toProfile()
    }

    /** Inserts [profile]: under a new id when its id is 0, otherwise under that id (a restore putting it back). */
    suspend fun create(profile: Profile, andSelect: Boolean = false): Profile {
        profile.id = CoreStore.call("profiles.insert") { put("profile", CoreStore.encode(StoredProfile.serializer(), StoredProfile.of(profile))) }.jsonPrimitive.long
        profile.storedOrder = profile.userOrder
        if (andSelect) {
            Settings.selectedProfile = profile.id
        }
        changed()
        return profile
    }

    private fun write(profile: Profile, forceOrder: Boolean = false): Int {
        // The order is written only when this copy changed it (or [forceOrder],
        // a rollback putting the old order back), so saving a copy read before
        // the UI reordered the list keeps the new order.
        val writeOrder = forceOrder || profile.storedOrder != profile.userOrder
        val count = CoreStore.call("profiles.update") {
            put("profile", CoreStore.encode(StoredProfile.serializer(), StoredProfile.of(profile)))
            put("writeOrder", writeOrder)
        }.jsonPrimitive.long.toInt()
        profile.storedOrder = profile.userOrder
        return count
    }

    suspend fun update(profile: Profile, forceOrder: Boolean = false): Int {
        try {
            return write(profile, forceOrder)
        } finally {
            changed()
        }
    }

    suspend fun update(profiles: List<Profile>): Int {
        try {
            return profiles.sumOf { write(it) }
        } finally {
            changed()
        }
    }

    suspend fun delete(profile: Profile): Int = delete(listOf(profile))

    suspend fun delete(profiles: List<Profile>): Int {
        try {
            return CoreStore.call("profiles.delete") { put("ids", JsonArray(profiles.map { JsonPrimitive(it.id) })) }.jsonPrimitive.long.toInt()
        } finally {
            changed()
        }
    }

    suspend fun list(): List<Profile> =
        CoreStore.decode(ListSerializer(StoredProfile.serializer()), CoreStore.call("profiles.list")).map { it.toProfile() }

    /** Records the core a profile's file resolved to (no change callback: it is a cache). */
    internal fun setResolvedCore(profile: Profile, core: String, stamp: String) {
        CoreStore.call("profiles.setResolvedCore") {
            put("id", profile.id)
            put("core", core)
            put("stamp", stamp)
        }
        profile.resolvedCore = core
        profile.resolvedStamp = stamp
    }

    /** Runs [block] as one store transaction: it commits when [block] returns and rolls back when it throws. */
    internal suspend fun <T> transaction(block: suspend () -> T): T {
        CoreStore.call("tx.begin")
        val value = try {
            block()
        } catch (error: Throwable) {
            runCatching { CoreStore.call("tx.rollback") }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
        CoreStore.call("tx.commit")
        return value
    }
}
