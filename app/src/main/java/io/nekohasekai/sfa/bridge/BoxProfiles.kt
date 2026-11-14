package io.nekohasekai.sfa.bridge

import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.bg.UpdateProfileWork
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.ProfileArray
import io.nekohasekai.sfa.database.ProfileChange
import io.nekohasekai.sfa.database.ProfileContent
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.database.SubscriptionFetch
import io.nekohasekai.sfa.database.SubscriptionMetadata
import io.nekohasekai.sfa.database.TypedProfile
import io.nekohasekai.sfa.database.metadataAfter
import io.nekohasekai.sfa.database.restoreSubscriptionMetadata
import io.nekohasekai.sfa.utils.CommandTarget
import io.nekohasekai.sfa.utils.HTTPClient
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.util.Date
import java.util.UUID

internal class BoxProfiles(private val applySelected: suspend (recover: Boolean) -> Unit) {
    private val mutex = ProfileContent.mutex

    suspend fun get(id: Long): Profile = ProfileManager.get(id) ?: error("Profile no longer exists")

    suspend fun list(): List<Map<String, Any>> = ProfileManager.list().map { it.toWire() }

    suspend fun read(id: Long): String = mutex.withLock { File(get(id).typed.path).readText() }

    /** The revision of a profile's file now, which an edit form sends back with its save. */
    suspend fun revisionOf(id: Long): String = mutex.withLock { revision(File(get(id).typed.path).readText()) }

    suspend fun config(id: Long): Map<String, Any?> = mutex.withLock { configLocked(id) }

    private suspend fun configLocked(id: Long): Map<String, Any?> {
        val profile = get(id)
        val content = File(profile.typed.path).readText()
        val core = ProfileContent.coreOf(profile, content)
        if (core != TypedProfile.CORE_SING_BOX) {
            // Xray JSON and Clash YAML are edited as text; sing-box-only stats do not apply.
            val formatted = Libbox.formatCoreConfig(core, content).value
            return mapOf("content" to content, "formatted" to formatted, "revision" to revision(content),
                "core" to core, "language" to if (core == TypedProfile.CORE_MIHOMO) "yaml" else "json",
                "stats" to runCatching { JSONObject(Libbox.coreProfileStats(core, content)).let { stats ->
                    mapOf("groups" to stats.optInt("groups"), "outbounds" to stats.optInt("outbounds"), "rules" to stats.optInt("rules"))
                } }.getOrNull())
        }
        val formatted = Libbox.formatConfig(content).value
        val root = JSONObject(formatted)
        val outbounds = root.optJSONArray("outbounds")
        var groupCount = 0
        for (index in 0 until (outbounds?.length() ?: 0)) {
            if (outbounds?.optJSONObject(index)?.optString("type") in setOf("selector", "urltest")) groupCount++
        }
        val route = root.optJSONObject("route")
        return mapOf("content" to content, "formatted" to formatted, "revision" to revision(content),
            "core" to core, "language" to "json", "stats" to mapOf(
            "groups" to groupCount, "outbounds" to (outbounds?.length() ?: 0),
            "rules" to (route?.optJSONArray("rules")?.length() ?: 0),
            "ruleSets" to (route?.optJSONArray("rule_set")?.length() ?: 0),
            "dnsServers" to (root.optJSONObject("dns")?.optJSONArray("servers")?.length() ?: 0),
            "endpoints" to (root.optJSONArray("endpoints")?.length() ?: 0),
        ))
    }

    suspend fun stats(id: Long): Any? = mutex.withLock {
        if (File(get(id).typed.path).length() > 8L * 1024 * 1024) null else configLocked(id)["stats"]
    }

    suspend fun writeContent(id: Long, content: String, expectedRevision: String): Map<String, Any?> = mutex.withLock {
        writeContentLocked(id, content, expectedRevision)
    }

    suspend fun setLanProxy(id: Long, expectedRevision: String, enabled: Boolean, initialPort: Int): Map<String, Any?> = mutex.withLock {
        require(!CommandTarget.isRemote) { "Disconnect remote session to edit local profiles" }
        require(id == Settings.selectedProfile) { "Selected profile changed; reload it before saving" }
        val previous = File(get(id).typed.path).readText()
        require(revision(previous) == expectedRevision) { "Configuration changed; reload it before saving" }
        require(ProfileContent.coreOf(get(id), previous) == TypedProfile.CORE_SING_BOX) { "LAN proxy switch is only available for sing-box profiles" }
        val content = Libbox.setUILANProxyEnabled(previous, enabled, initialPort).value
        val saved = writeContentLocked(id, content, expectedRevision)
        if (previous == content && id == Settings.selectedProfile) applySelected(false)
        saved
    }

    private suspend fun writeContentLocked(id: Long, content: String, expectedRevision: String): Map<String, Any?> {
        ProfileContent.replaceLocked(get(id), content, expectedRevision = expectedRevision, applySelected = applySelected)
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

    suspend fun save(args: Map<String, Any?>): Long = mutex.withLock { saveLocked(args) }

    /**
     * Creates one local profile per entry of a split JSON array of configs
     * ([decode]/[decodeText] return them as "entries"). Every entry is checked first, so a
     * bad element saves nothing. Returns the new profile ids in order.
     */
    suspend fun saveAll(args: Map<String, Any?>): List<Long> = mutex.withLock {
        val declaredCore = (args["core"] as? String) ?: TypedProfile.CORE_AUTO
        require(declaredCore in TypedProfile.CORES) { "Unknown core: $declaredCore" }
        val entries = (args["entries"] as? List<*>).orEmpty().map { item ->
            val entry = item as? Map<*, *> ?: error("Invalid configuration entry")
            ProfileArray.Entry(entry["name"] as? String ?: "", entry["content"] as? String ?: "", entry["core"] as? String ?: "")
        }
        require(entries.isNotEmpty()) { "No configurations to import" }
        entries.forEachIndexed { index, entry ->
            require(entry.name.isNotBlank()) { "Configuration ${index + 1} has no name" }
            runCatching { ProfileContent.prepare(declaredCore, entry.content) }.onFailure {
                throw IllegalArgumentException("Configuration ${index + 1} (${entry.name}): ${it.message}", it)
            }
        }
        entries.map { entry -> saveLocked(mapOf("name" to entry.name, "content" to entry.content, "core" to declaredCore)) }
    }

    /** One element of a split array, with the fetch it came from (null for local content). */
    private class ArrayElement(val fetch: SubscriptionFetch?)

    private suspend fun saveLocked(args: Map<String, Any?>, element: ArrayElement? = null): Long {
        val id = (args["id"] as? Number)?.toLong() ?: 0L
        val profile = if (id == 0L) Profile() else get(id)
        val name = (args["name"] as? String)?.trim().orEmpty()
        require(name.isNotEmpty()) { "A profile name is required" }
        val url = (args["url"] as? String)?.trim().orEmpty()
        if (url.isNotEmpty()) validateUrl(url)
        val interval = (args["interval"] as? Number)?.toInt() ?: 60
        require(interval >= 15) { "Update interval must be at least 15 minutes" }
        val declaredCore = (args["core"] as? String) ?: profile.typed.core
        require(declaredCore in TypedProfile.CORES) { "Unknown core: $declaredCore" }
        // A newly created remote profile takes the provider's headers from its first fetch.
        val fetched = if (element != null) element.fetch else if (args["content"] !is String && id == 0L && url.isNotEmpty()) {
            HTTPClient().use { it.getSubscription(url, declaredCore) }
        } else {
            null
        }
        val incomingContent = args["content"] as? String ?: if (fetched != null) {
            fetched.content
        } else if (id != 0L) {
            File(profile.typed.path).readText()
        } else {
            // An empty local profile starts on its own core, or the first core
            // left on when auto (so turning sing-box off never blocks creation).
            emptyProfileContent(if (declaredCore == TypedProfile.CORE_AUTO) Settings.autoFetchCore() else declaredCore)
        }
        if (id == 0L && element == null) {
            // A JSON array of configs becomes one profile per element; a subscription URL is
            // shared by all of them, and each update keeps following its own element.
            ProfileContent.splitArray(incomingContent)?.let { elements ->
                val entries = ProfileContent.prepareArray(declaredCore, name, elements)
                return entries.map { entry ->
                    saveLocked(args + mapOf("name" to entry.name, "content" to entry.content), ArrayElement(fetched))
                }.first()
            }
        }
        val prepared = ProfileContent.prepare(declaredCore, incomingContent)
        val content = prepared.content
        val file = if (id == 0L) {
            val extension = if (prepared.core == TypedProfile.CORE_MIHOMO) "yaml" else "json"
            File(Application.application.filesDir, "configs/${UUID.randomUUID()}.$extension").also { it.parentFile!!.mkdirs() }
        } else {
            File(profile.typed.path)
        }
        val oldContent = if (file.exists()) file.readText() else null
        // An edit that carries the revision it was opened at is refused when the
        // file changed since (another editor, a subscription update).
        (args["revision"] as? String)?.let { expected ->
            require(oldContent != null && revision(oldContent) == expected) { "Configuration changed; reload it before saving" }
        }
        val previousUrl = profile.typed.remoteURL
        // Everything an edit changes, to put back if saving or applying fails.
        val previousName = profile.name
        val previousTyped = profile.typed.copy()
        write(file, content)
        profile.name = name
        profile.typed.core = declaredCore
        profile.typed.path = file.path
        profile.typed.type = if (url.isEmpty()) TypedProfile.Type.Local else TypedProfile.Type.Remote
        profile.typed.remoteURL = url
        profile.typed.autoUpdate = url.isNotEmpty() && args["autoUpdate"] == true
        // Edits keep a choice already made; the form reports whether the interval field was touched.
        profile.typed.intervalUserSet = profile.typed.intervalUserSet || args["intervalEdited"] == true ||
            (id != 0L && interval != profile.typed.autoUpdateInterval)
        profile.typed.autoUpdateInterval = interval
        if (url.isEmpty() || (id != 0L && url != previousUrl)) {
            // Traffic and expiry belonged to the old subscription; the next fetch reports the new one.
            profile.typed.restoreSubscriptionMetadata(SubscriptionMetadata(0L, 0L, 0L, 0L, "", interval))
        } else if (fetched != null) {
            profile.typed.restoreSubscriptionMetadata(profile.typed.metadataAfter(fetched))
        }
        if (id == 0L) {
            val importedAt = (args["updatedAt"] as? Number)?.toLong()?.takeIf { it >= 0L }
            profile.typed.lastUpdated = if (url.isNotEmpty() && args["content"] is String && importedAt != null) Date(importedAt) else Date()
        }
        var stored = false
        var applyAttempted = false
        val previousSelection = Settings.selectedProfile
        try {
            if (id == 0L) {
                profile.userOrder = ProfileManager.nextOrder()
                ProfileManager.create(profile, andSelect = Settings.selectedProfile == -1L)
            } else {
                ProfileManager.update(profile)
            }
            stored = true
            UpdateProfileWork.reconfigureUpdater()
            if (profile.id == Settings.selectedProfile && content != oldContent) {
                applyAttempted = true
                applySelected(false)
            }
        } catch (e: Exception) {
            // Roll back the file and the profile row, then bring the service
            // back on the previous configuration, as ProfileChange does.
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                fun attempt(task: () -> Unit) = runCatching(task).exceptionOrNull()?.let(e::addSuppressed)
                attempt { if (oldContent != null) write(file, oldContent) else check(!file.exists() || file.delete()) }
                if (stored) {
                    if (id == 0L) {
                        runCatching { ProfileManager.delete(profile) }.exceptionOrNull()?.let(e::addSuppressed)
                        if (Settings.selectedProfile != previousSelection) {
                            Settings.selectedProfile = previousSelection
                            runCatching { Settings.dataStore.flush() }.exceptionOrNull()?.let(e::addSuppressed)
                        }
                    } else {
                        profile.name = previousName
                        profile.typed = previousTyped
                        runCatching { ProfileManager.update(profile) }.exceptionOrNull()?.let(e::addSuppressed)
                    }
                    runCatching { UpdateProfileWork.reconfigureUpdater() }.exceptionOrNull()?.let(e::addSuppressed)
                }
                if (applyAttempted) runCatching { applySelected(true) }.exceptionOrNull()?.let(e::addSuppressed)
            }
            throw e
        }
        return profile.id
    }

    suspend fun update(id: Long) = mutex.withLock {
        val profile = get(id)
        require(profile.typed.type == TypedProfile.Type.Remote) { "Only remote profiles can be updated" }
        val fetch = HTTPClient().use { it.getSubscription(validateUrl(profile.typed.remoteURL), profile.typed.core) }
        val interval = profile.typed.autoUpdateInterval
        ProfileContent.replaceLocked(profile, fetch.content, updatedAt = Date(), subscription = fetch, applySelected = applySelected)
        if (profile.typed.autoUpdateInterval != interval) UpdateProfileWork.reconfigureUpdater()
    }

    suspend fun delete(id: Long) = mutex.withLock {
        val profile = get(id)
        ProfileManager.delete(profile)
        File(profile.typed.path).delete()
        if (Settings.selectedProfile == id) {
            Settings.selectedProfile = ProfileManager.list().firstOrNull()?.id ?: -1L
            Settings.dataStore.flush()
            applySelected(false)
        }
        UpdateProfileWork.reconfigureUpdater()
    }

    suspend fun reorder(ids: List<Long>) = mutex.withLock {
        val profiles = ProfileManager.list()
        require(ids.size == profiles.size && ids.toSet() == profiles.map { it.id }.toSet()) { "Profile list changed; refresh and retry" }
        val byId = profiles.associateBy { it.id }
        ProfileManager.update(ids.mapIndexed { index, id -> byId.getValue(id).apply { userOrder = index.toLong() } })
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
        val detected = Libbox.detectCoreConfig(text)
        if (text.trimStart().startsWith("{") || detected == Libbox.CoreMihomo || detected == Libbox.CoreLinks) {
            val prepared = ProfileContent.prepare(TypedProfile.CORE_AUTO, text)
            return mapOf("name" to name.substringBeforeLast('.'), "content" to prepared.content, "core" to TypedProfile.CORE_AUTO,
                "detectedCore" to prepared.core)
        }
        val profile = Libbox.decodeProfileContent(data)
        validate(profile.config)
        require(profile.type == Libbox.ProfileTypeLocal || profile.type == Libbox.ProfileTypeRemote) { "Unsupported profile type" }
        val remote = profile.type == Libbox.ProfileTypeRemote
        val url = if (remote) validateUrl(profile.remotePath) else ""
        return mapOf("name" to profile.name, "content" to profile.config,
            "remote" to remote, "url" to url, "autoUpdate" to (remote && profile.autoUpdate),
            "interval" to if (remote) profile.autoUpdateInterval.coerceAtLeast(15) else 60,
            "intervalEdited" to (remote && profile.autoUpdateInterval != TypedProfile.DEFAULT_INTERVAL),
            "updatedAt" to if (remote) profile.lastUpdated.coerceAtLeast(0) else 0L)
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

internal fun Profile.resolvedCore(): String {
    val file = File(typed.path)
    val stamp = "${typed.core}:${file.length()}:${file.lastModified()}"
    resolvedCores[typed.path]?.let { if (it.first == stamp) return it.second }
    val core = runCatching { ProfileContent.coreOf(this, file.readText()) }.getOrDefault(TypedProfile.CORE_SING_BOX)
    resolvedCores[typed.path] = stamp to core
    return core
}

internal fun Profile.toWire(): Map<String, Any> = mapOf(
    "id" to id,
    "name" to name,
) + ProfileWireFields.export(typed) + mapOf("resolvedCore" to resolvedCore())

private fun emptyProfileContent(core: String): String = when (core) {
    TypedProfile.CORE_XRAY -> "{\n  \"outbounds\": [\n    {\n      \"protocol\": \"freedom\",\n      \"tag\": \"direct\"\n    }\n  ]\n}\n"
    TypedProfile.CORE_MIHOMO -> "mode: rule\nproxies: []\nproxy-groups: []\nrules:\n  - MATCH,DIRECT\n"
    else -> "{}"
}
