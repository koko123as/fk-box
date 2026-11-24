package io.nekohasekai.sfa.bridge

import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.bg.UpdateProfileWork
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.ProfileChange
import io.nekohasekai.sfa.database.ProfileContent
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.database.TypedProfile
import kotlinx.coroutines.sync.withLock
import io.nekohasekai.sfa.database.CoreStore
import io.nekohasekai.sfa.database.ServiceSettings
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.json.JSONObject
import java.io.File
import java.net.URI

internal class BoxProfiles(private val applySelected: suspend (recover: Boolean) -> Unit) {
    private val mutex = ProfileContent.mutex

    suspend fun get(id: Long): Profile = ProfileManager.get(id) ?: error("Profile no longer exists")

    /**
     * Records in the client store the core each profile's file resolves to
     * (libbox detection, which the UI cannot run), for the files that changed
     * since it was last resolved; the UI reads the list from the store.
     */
    suspend fun resolveCores() {
        for (profile in ProfileManager.list()) {
            val stamp = profile.coreStamp()
            if (stamp != profile.resolvedStamp) ProfileManager.setResolvedCore(profile, profile.resolvedCore(), stamp)
        }
    }

    suspend fun read(id: Long): String = mutex.withLock { File(get(id).typed.path).readText() }

    /** The revision of a profile's file now, which an edit form sends back with its save. */
    suspend fun revisionOf(id: Long): String = mutex.withLock { revision(File(get(id).typed.path).readText()) }

    suspend fun config(id: Long): Map<String, Any?> = mutex.withLock { configLocked(id) }

    /**
     * A profile's configuration for the editor: the content as saved (the
     * brain imports it as written; no core formats it) and what the brain's
     * check counts in it (mobile-ffi `brain.check`).
     */
    private suspend fun configLocked(id: Long): Map<String, Any?> {
        val profile = get(id)
        val content = File(profile.typed.path).readText()
        val core = ProfileContent.coreOf(profile, content)
        val json = content.trimStart().startsWith("{")
        val stats = runCatching { CoreStore.call("brain.check") { put("content", content) } as JsonObject }.getOrNull()
        return mapOf("content" to content, "formatted" to content, "revision" to revision(content),
            "core" to core, "language" to if (json) "json" else "yaml",
            "stats" to stats?.let {
                mapOf("groups" to it.number("groups"), "outbounds" to it.number("nodes"), "rules" to it.number("rules"))
            })
    }

    private fun JsonObject.number(key: String): Int = (get(key) as? JsonPrimitive)?.intOrNull ?: 0

    suspend fun stats(id: Long): Any? = mutex.withLock {
        if (File(get(id).typed.path).length() > 8L * 1024 * 1024) null else configLocked(id)["stats"]
    }

    suspend fun writeContent(id: Long, content: String, expectedRevision: String): Map<String, Any?> = mutex.withLock {
        writeContentLocked(id, content, expectedRevision)
    }

    /** The LAN proxy (a sing-box inbound on the LAN) has no place on the single-core path (no local listener). */
    @Suppress("UNUSED_PARAMETER")
    suspend fun setLanProxy(id: Long, expectedRevision: String, enabled: Boolean, initialPort: Int): Map<String, Any?> =
        error("The LAN proxy is not available in the single-core build")

    private suspend fun writeContentLocked(id: Long, content: String, expectedRevision: String): Map<String, Any?> {
        ProfileContent.replaceLocked(id, content, expectedRevision = expectedRevision, applySelected = applySelected)
        return configLocked(id)
    }

    suspend fun select(id: Long, serviceStatus: () -> Status) = mutex.withLock {
        get(id)
        require(serviceStatus() in setOf(Status.Stopped, Status.Started)) {
            "Wait for the service to finish starting or stopping"
        }
        val previous = Settings.selectedProfile
        val previousMode = Settings.serviceMode
        ProfileChange.apply(
            save = {
                Settings.selectedProfile = id
                Settings.dataStore.flush()
            },
            restore = listOf<suspend () -> Unit>({
                Settings.selectedProfile = previous
                if (Settings.serviceMode != previousMode) Settings.serviceMode = previousMode
                Settings.dataStore.flush()
            }),
            applyService = applySelected,
        )
    }

    /**
     * Saves the profile form ([args] holds its fields and, when editing, its "id"):
     * client-core (crates/client-core/src/config/profiles.rs) checks the form, fetches a new
     * subscription, splits a JSON array, prepares the content with its core (libbox,
     * through [CoreKernel]), writes the files and only the columns the form edits
     * (never the order). Returns the first saved profile's id.
     */
    suspend fun save(args: Map<String, Any?>): Long = mutex.withLock {
        saved("profiles.save") { put("profile", ServiceSettings.element(args)) }.first()
    }

    /**
     * Creates one local profile per entry of a split JSON array of configs
     * ([decode]/[decodeText] return them as "entries"). Every entry is checked first, so a
     * bad element saves nothing. Returns the new profile ids in order.
     */
    suspend fun saveAll(args: Map<String, Any?>): List<Long> = mutex.withLock {
        saved("profiles.saveAll") {
            put("entries", ServiceSettings.element(args["entries"] ?: emptyList<Any?>()))
            put("core", (args["core"] as? String) ?: TypedProfile.CORE_AUTO)
        }
    }

    /** What a client-core form save returned (`profiles::Saved`). */
    @Serializable
    private data class Saved(val ids: List<Long> = emptyList(), val apply: Boolean = false, val written: JsonElement = JsonArray(emptyList()), val token: Long? = null)

    /**
     * Runs a client-core form save, then the Android side: the selection it wrote enters the
     * settings cache, the updater is rescheduled and a change to the selected profile is applied
     * to the service. If applying fails, client-core puts the files, rows and selection back and
     * the service is recovered on the previous configuration. Caller holds [mutex].
     */
    private suspend fun saved(method: String, build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): List<Long> {
        val previousMode = Settings.serviceMode
        val result = CoreStore.decode(Saved.serializer(), CoreStore.callKernel(method) {
            build()
            put("configsDir", File(Application.application.filesDir, "configs").path)
            put("now", System.currentTimeMillis())
        })
        try {
            ProfileChange.apply(
                save = {
                    ServiceSettings.adopt(result.written)
                    UpdateProfileWork.reconfigureUpdater()
                },
                restore = listOf<suspend () -> Unit>(
                    {
                        result.token?.let { token ->
                            val undone = CoreStore.call("profiles.rollback") { put("token", token) }.jsonObject
                            ServiceSettings.adopt(undone.getValue("written"))
                            UpdateProfileWork.reconfigureUpdater()
                        }
                    },
                    {
                        if (result.apply) {
                            if (Settings.serviceMode != previousMode) Settings.serviceMode = previousMode
                            Settings.dataStore.flush()
                        }
                    },
                ),
                applyService = { recover -> if (result.apply) applySelected(recover) },
            )
        } finally {
            result.token?.let { token -> runCatching { CoreStore.call("profiles.finish") { put("token", token) } } }
            ProfileManager.notifyChanged()
        }
        return result.ids
    }

    suspend fun update(id: Long) = mutex.withLock {
        val profile = get(id)
        require(profile.typed.type == TypedProfile.Type.Remote) { "Only remote profiles can be updated" }
        validateUrl(profile.typed.remoteURL)
        val result = ProfileContent.replaceLocked(id, subscription = true, applySelected = applySelected)
        if (result.intervalChanged) UpdateProfileWork.reconfigureUpdater()
    }

    /** Deletes a profile and its file (client-core); deleting the selected one applies the next. */
    suspend fun delete(id: Long) = mutex.withLock {
        val deleted = CoreStore.call("profiles.remove") { put("id", id) }.jsonObject
        ServiceSettings.adopt(deleted.getValue("written"))
        ProfileManager.notifyChanged()
        if (deleted["apply"]?.jsonPrimitive?.booleanOrNull == true) applySelected(false)
        UpdateProfileWork.reconfigureUpdater()
    }

    /**
     * A JSON array of configs, split for [saveAll]: "entries" holds one name/content/core per
     * element, already checked by its core. Null when [text] is not such an array.
     */
    private fun decodeArray(text: String, source: String): Map<String, Any?>? {
        val elements = ProfileContent.splitArray(text) ?: return null
        val entries = ProfileContent.prepareArray(TypedProfile.CORE_AUTO, source, elements)
        return mapOf("name" to source, "core" to TypedProfile.CORE_AUTO,
            "entries" to entries.map { mapOf("name" to it.name, "content" to it.content, "core" to it.core) })
    }

    /** Pasted or scanned text: a JSON array of configs is split, anything else is prepared as one profile. */
    fun decodeText(text: String, name: String): Map<String, Any?> {
        decodeArray(text, name)?.let { return it }
        val prepared = ProfileContent.prepare(TypedProfile.CORE_AUTO, text)
        return mapOf("name" to name, "content" to prepared.content, "core" to TypedProfile.CORE_AUTO,
            "detectedCore" to prepared.core)
    }

    fun decode(data: ByteArray, name: String): Map<String, Any?> {
        val text = data.toString(Charsets.UTF_8)
        decodeArray(text, name.substringBeforeLast('.'))?.let { return it }
        // Text the brain imports (Clash/mihomo YAML, sing-box JSON, share
        // links). sing-box's binary profile files (libbox's decoder) are not
        // read on the single-core path.
        require(!text.contains('\u0000')) { "Unsupported profile file" }
        val prepared = ProfileContent.prepare(TypedProfile.CORE_AUTO, text)
        return mapOf("name" to name.substringBeforeLast('.'), "content" to prepared.content, "core" to TypedProfile.CORE_AUTO,
            "detectedCore" to prepared.core)
    }

    companion object {
        fun revision(content: String): String = ProfileContent.revision(content)
        fun validate(content: String) = ProfileContent.validate(content)

        fun validateUrl(value: String): String {
            val uri = URI(value)
            require(uri.scheme in listOf("https", "http") && !uri.host.isNullOrBlank()) { "Enter an HTTP or HTTPS subscription URL" }
            return value
        }

        fun write(file: File, content: String) = ProfileContent.write(file, content)
    }
}

/** The core each profile file resolves to, cached by path, size and modification time. */
private val resolvedCores = java.util.concurrent.ConcurrentHashMap<String, Pair<String, String>>()

/** What the resolved core depends on: the declared core and the file's size and modification time. */
internal fun Profile.coreStamp(): String {
    val file = File(typed.path)
    return "${typed.core}:${file.length()}:${file.lastModified()}"
}

internal fun Profile.resolvedCore(): String {
    val file = File(typed.path)
    val stamp = coreStamp()
    resolvedCores[typed.path]?.let { if (it.first == stamp) return it.second }
    val core = runCatching { ProfileContent.coreOf(this, file.readText()) }.getOrDefault(typed.core)
    resolvedCores[typed.path] = stamp to core
    return core
}

internal fun Profile.toWire(): Map<String, Any> = mapOf(
    "id" to id,
    "name" to name,
) + ProfileWireFields.export(typed) + mapOf("resolvedCore" to resolvedCore())
