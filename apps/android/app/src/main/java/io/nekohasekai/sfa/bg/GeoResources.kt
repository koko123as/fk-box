package io.nekohasekai.sfa.bg

import android.content.Context
import android.util.Log
import io.nekohasekai.libbox.GeoUpdate
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.database.CoreStore
import io.nekohasekai.sfa.database.ServiceSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest

/**
 * Geo data shared by Xray and mihomo (as FlClash handles it). The directory is
 * libbox's GeoDataDir (mihomo's home, also Xray's xray.location.asset).
 *
 * The APK's `assets/geo` carries only the resources in GEO_BUNDLED of
 * geo-assets.properties (geosite.dat and geoip.metadb); its manifest lists the
 * rest (geoip.dat, ASN.mmdb) with their pinned URL and SHA-256. Nothing is
 * extracted or downloaded until Xray or mihomo starts: [prepareForCore] then
 * extracts the bundled files that core needs and downloads the missing pinned
 * ones. Each file can be replaced from its own URL afterwards.
 */
object GeoResources {
    private const val TAG = "GeoResources"
    private const val STATE_FILE = "geo-resources.json"
    private const val ASSET_DIR = "geo"

    /**
     * How long a core start may wait for on-demand downloads in total. The
     * service start itself is confirmed within 120 s (ServiceStartResult).
     */
    private const val ON_DEMAND_BUDGET_MS = 90_000L

    /** Display order and labels match FlClash's GeoResource enum. */
    val names = listOf(GeoPolicy.GEOIP, GeoPolicy.GEOSITE, GeoPolicy.MMDB, GeoPolicy.ASN)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()

    /** Bundled resources already checked in this process; the APK cannot change while it runs. */
    private val bundleChecked = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun label(name: String): String = when (name) {
        GeoPolicy.GEOIP -> "GeoIP"
        GeoPolicy.GEOSITE -> "GeoSite"
        GeoPolicy.MMDB -> "MMDB"
        else -> "ASN"
    }

    fun defaultUrl(name: String): String = Libbox.geoResourceDefaultURL(name)

    /** The user's address for [name] (client-core geo::url), else libbox's default. */
    fun url(name: String): String = CoreStore.call("geo.url") {
        put("name", name)
        put("defaultUrl", defaultUrl(name))
    }.jsonPrimitive.content

    private fun dir(): File = File(Libbox.geoDataDir())

    private fun installed(dir: File, name: String): Boolean = File(dir, Libbox.geoResourceFileName(name)).let { it.isFile && it.length() > 0 }

    /**
     * For the service start path, which is not a coroutine: puts the files
     * [core] needs for [text] (its profile and routing rules) in place.
     * Returns why some could not be, or null; the start goes on either way,
     * and a core that really needs a missing file reports it itself.
     */
    fun prepareForCoreBlocking(core: String, text: String, context: Context = Application.application): String? {
        val wanted = GeoPolicy.needed(core, text)
        if (wanted.isEmpty()) return null
        return try {
            runBlocking { prepareForCore(wanted, context) }
        } catch (error: Exception) {
            Log.e(TAG, "prepare geo data for $core", error)
            error.message ?: error.javaClass.simpleName
        }
    }

    suspend fun prepareForCore(wanted: Set<String>, context: Context = Application.application): String? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val bundle = readBundle(context)
            extractBundledLocked(context, bundle, wanted)
            val dir = dir().apply { mkdirs() }
            val deadline = System.nanoTime() + ON_DEMAND_BUDGET_MS * 1_000_000
            val failures = wanted.filter { !installed(dir, it) }.mapNotNull { name ->
                downloadMissingLocked(dir, name, bundle, deadline)?.let { "${Libbox.geoResourceFileName(name)}: $it" }
            }
            failures.takeIf { it.isNotEmpty() }?.joinToString("\n")
        }
    }

    /** Extract the bundled files among [wanted] that are missing or older than the bundle. */
    private fun extractBundledLocked(context: Context, bundle: GeoPolicy.Bundle?, wanted: Set<String>) {
        if (bundle == null) return
        val dir = dir().apply { mkdirs() }
        // Only a missing file needs another look after the first check
        // (mihomo or the user may have removed one).
        val pending = wanted.filter { it in bundle.files && !(it in bundleChecked && installed(dir, it)) }
        if (pending.isEmpty()) return
        val state = readState(dir).toMutableMap()
        var changed = false
        for (name in pending) {
            val bundled = bundle.files.getValue(name)
            val target = File(dir, bundled.file)
            val exists = target.isFile && target.length() > 0
            if (GeoPolicy.shouldExtract(exists, state[name], bundled.sha256, bundle.time, target.lastModified())) {
                try {
                    extract(context, bundled, target)
                    state[name] = GeoPolicy.Installed(GeoPolicy.SOURCE_BUILTIN, bundled.sha256, bundle.time, System.currentTimeMillis())
                    changed = true
                } catch (error: Exception) {
                    // The previous file (if any) stays; the cores report a missing file themselves.
                    Log.e(TAG, "extract $name", error)
                    continue
                }
            }
            bundleChecked += name
        }
        if (changed) writeState(dir, state)
    }

    /**
     * Download a missing resource on first use, within the start's time
     * budget: the pinned file from the manifest (the URL and SHA-256 the build
     * would have bundled) and the configured address (latest release, checked
     * by format only). An address the user set (a mirror) is tried first; the
     * first attempt gets at most two thirds of what is left so a blocked host
     * cannot use up the whole budget. Returns the error, or null.
     */
    private suspend fun downloadMissingLocked(dir: File, name: String, bundle: GeoPolicy.Bundle?, deadline: Long): String? {
        val pinned = bundle?.remote?.get(name)?.let { it.url to it.sha256 }
        val configured = url(name) to ""
        val custom = name in GeoPolicy.customUrls()
        val attempts = (if (custom) listOf(configured, pinned) else listOf(pinned, configured)).filterNotNull().distinctBy { it.first }
        var failure = "not available"
        for ((index, attempt) in attempts.withIndex()) {
            val (link, sha256) = attempt
            val remaining = (deadline - System.nanoTime()) / 1_000_000
            if (remaining <= 0) {
                failure = "timed out"
                break
            }
            val limit = if (index == attempts.lastIndex) remaining else remaining * 2 / 3
            val started = System.nanoTime()
            try {
                val result = withTimeout(limit) {
                    cancellable { task ->
                        if (sha256.isNotEmpty()) task.fetchExpected(dir.path, name, link, sha256) else task.fetch(dir.path, name, link, "", "", "")
                    }
                }
                val now = System.currentTimeMillis()
                val state = readState(dir).toMutableMap()
                state[name] = if (sha256.isNotEmpty()) {
                    // The pinned data is as old as the pin; no validators, they belong to another URL.
                    GeoPolicy.Installed(GeoPolicy.SOURCE_DOWNLOAD, result.sha256, bundle?.time ?: now, now)
                } else {
                    GeoPolicy.Installed(GeoPolicy.SOURCE_DOWNLOAD, result.sha256, now, now, etag = result.etag, lastModified = result.lastModified)
                }
                writeState(dir, state)
                Log.i(TAG, "downloaded ${Libbox.geoResourceFileName(name)} on first use (${if (sha256.isNotEmpty()) "pinned" else "configured address"})")
                return null
            } catch (_: TimeoutCancellationException) {
                failure = "timed out"
                Log.w(TAG, "download $name (${if (sha256.isNotEmpty()) "pinned" else "configured address"}): timed out")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // The timeout may surface as Go's own "context canceled".
                val timedOut = (System.nanoTime() - started) / 1_000_000 >= limit
                failure = if (timedOut) "timed out" else error.message ?: error.javaClass.simpleName
                Log.w(TAG, "download $name (${if (sha256.isNotEmpty()) "pinned" else "configured address"}): $failure")
            }
        }
        return failure
    }

    /**
     * A download in Go cannot see coroutine cancellation (leaving the page,
     * WorkManager stopping the worker, a timeout); cancel it explicitly.
     */
    private suspend fun <T> cancellable(block: (GeoUpdate) -> T): T {
        val task = Libbox.newGeoUpdate()
        return coroutineScope {
            val watcher = launch {
                try {
                    awaitCancellation()
                } finally {
                    task.cancel()
                }
            }
            try {
                block(task)
            } finally {
                watcher.cancel()
            }
        }
    }

    private fun extract(context: Context, bundled: GeoPolicy.BundledFile, target: File) {
        val temp = File(target.parentFile, target.name + ".tmp-" + System.nanoTime())
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            context.assets.open("$ASSET_DIR/${bundled.file}").use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                    output.fd.sync()
                }
            }
            val sum = digest.digest().joinToString("") { "%02x".format(it) }
            check(sum == bundled.sha256) { "bundled ${bundled.file} checksum mismatch" }
            check(temp.renameTo(target)) { "unable to replace ${target.name}" }
        } finally {
            temp.delete()
        }
    }

    private fun readBundle(context: Context): GeoPolicy.Bundle? = try {
        context.assets.open("$ASSET_DIR/manifest.json").use { json.decodeFromString(GeoPolicy.Bundle.serializer(), it.readBytes().decodeToString()) }
    } catch (_: FileNotFoundException) {
        null
    } catch (error: Exception) {
        Log.e(TAG, "read bundled geo manifest", error)
        null
    }

    private fun readStateText(dir: File): String = runCatching { File(dir, STATE_FILE).readText() }.getOrDefault("")

    private fun readState(dir: File): Map<String, GeoPolicy.Installed> = GeoPolicy.decodeState(readStateText(dir))

    private fun writeState(dir: File, state: Map<String, GeoPolicy.Installed>) {
        val target = File(dir, STATE_FILE)
        val temp = File(dir, "$STATE_FILE.tmp")
        temp.writeText(GeoPolicy.encodeState(state))
        if (!temp.renameTo(target)) {
            temp.delete()
            Log.w(TAG, "unable to save $STATE_FILE")
        }
    }

    /** Rows for the Resources page. */
    suspend fun list(context: Context = Application.application): List<Map<String, Any?>> = withContext(Dispatchers.IO) {
        mutex.withLock {
            // Listing never extracts or downloads: a file not in place yet is
            // shown as the APK copy that a core start extracts, or as one that
            // is downloaded on first use.
            val bundle = readBundle(context)
            val dir = dir()
            val state = readState(dir)
            names.map { name ->
                val file = File(dir, Libbox.geoResourceFileName(name))
                val exists = file.isFile
                val installed = state[name]
                val bundled = bundle?.files?.get(name)
                mapOf(
                    "name" to name,
                    "label" to label(name),
                    "file" to file.name,
                    "url" to url(name),
                    "defaultUrl" to defaultUrl(name),
                    "size" to when {
                        exists -> file.length()
                        bundled != null -> bundled.size.takeIf { it > 0 }
                        else -> null
                    },
                    "updatedAt" to when {
                        exists -> installed?.time ?: file.lastModified()
                        bundled != null -> bundle.time
                        else -> null
                    },
                    "source" to when {
                        exists -> installed?.source ?: GeoPolicy.SOURCE_CORE
                        bundled != null -> GeoPolicy.SOURCE_BUILTIN
                        else -> GeoPolicy.SOURCE_ON_DEMAND
                    },
                )
            }
        }
    }

    /**
     * Download one resource. A failure leaves the installed file as it was;
     * returns true when the file changed.
     */
    suspend fun update(name: String): Boolean = withContext(Dispatchers.IO) {
        require(name in names) { "Unknown geo resource: $name" }
        val link = url(name)
        mutex.withLock {
            val dir = dir()
            val previous = readState(dir)[name]
            val result = cancellable { task ->
                task.fetch(dir.path, name, link, previous?.sha256.orEmpty(), previous?.etag.orEmpty(), previous?.lastModified.orEmpty())
            }
            val now = System.currentTimeMillis()
            val state = readState(dir).toMutableMap()
            state[name] = if (result.skipped && previous != null) {
                previous.copy(checked = now, etag = result.etag, lastModified = result.lastModified)
            } else {
                GeoPolicy.Installed(
                    GeoPolicy.SOURCE_DOWNLOAD, result.sha256, if (result.skipped) previous?.time ?: now else now, now,
                    etag = result.etag, lastModified = result.lastModified,
                )
            }
            writeState(dir, state)
            !result.skipped
        }
    }

    /**
     * Installed resources whose last check is older than the update interval
     * (client-core geo::due, which reads the interval from the settings). A
     * file no core has needed yet is left for its first use, so automatic
     * updates never fetch data nothing reads.
     */
    suspend fun due(): List<String> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val dir = dir()
            GeoPolicy.due(readStateText(dir), names.filter { installed(dir, it) }, System.currentTimeMillis())
        }
    }

    /** Sets or clears (empty, or the default) [name]'s address through client-core (geo::set_url). */
    fun setUrl(name: String, url: String) {
        require(name in names) { "Unknown geo resource: $name" }
        val result = CoreStore.call("geo.setUrl") {
            put("name", name)
            put("url", url)
            put("defaultUrl", defaultUrl(name))
        }
        ServiceSettings.adopt(result.jsonObject.getValue("written"))
    }
}

/**
 * The decisions behind [GeoResources], made by `fkbox-client-core`
 * (crates/client-core/src/geo.rs): which files a core needs, whether the
 * APK copy replaces an installed file, which files an automatic update
 * fetches and how often it runs, and the addresses the user set. The
 * bundled manifest and the bookkeeping records stay here with the files.
 */
object GeoPolicy {
    const val GEOIP = "geoip"
    const val GEOSITE = "geosite"
    const val MMDB = "mmdb"
    const val ASN = "asn"

    const val SOURCE_BUILTIN = "builtin"
    const val SOURCE_DOWNLOAD = "download"

    /** A file written by mihomo itself (its own geo-auto-update or first-run download). */
    const val SOURCE_CORE = "core"

    /** Not installed and not in the APK: downloaded when a core first needs it. */
    const val SOURCE_ON_DEMAND = "ondemand"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val stateSerializer by lazy { MapSerializer(String.serializer(), Installed.serializer()) }

    @Serializable
    data class BundledFile(val file: String, val sha256: String, val size: Long = 0)

    /** A pinned resource the APK does not carry: downloaded from [url] on first use and checked against [sha256]. */
    @Serializable
    data class RemoteFile(val file: String, val url: String, val sha256: String)

    /**
     * assets/geo/manifest.json, written by the app's prepareGeoAssets task:
     * [files] are in the APK, [remote] are pinned but downloaded on first use.
     */
    @Serializable
    data class Bundle(
        val source: String = "",
        val commit: String = "",
        val time: Long,
        val files: Map<String, BundledFile>,
        val remote: Map<String, RemoteFile> = emptyMap(),
    )

    /** What the app last put in place: [time] is the age of the data, [checked] the last download attempt that succeeded. */
    /** [etag] and [lastModified] are the server's validators for the downloaded file, for a conditional re-check. */
    @Serializable
    data class Installed(
        val source: String,
        val sha256: String,
        val time: Long,
        val checked: Long = 0L,
        val etag: String = "",
        val lastModified: String = "",
    )

    /** Whether the bundled copy should replace what is installed (client-core geo::should_extract). */
    fun shouldExtract(exists: Boolean, installed: Installed?, bundledSha256: String, bundleTime: Long, fileModified: Long): Boolean =
        CoreStore.call("geo.shouldExtract") {
            put("exists", exists)
            put("installed", installed?.let { CoreStore.encode(Installed.serializer(), it) } ?: JsonNull)
            put("bundledSha256", bundledSha256)
            put("bundleTime", bundleTime)
            put("fileModified", fileModified)
        }.jsonPrimitive.boolean

    /** The resources [core] reads for [text], its profile plus the routing rules compiled for it (geo::needed). */
    fun needed(core: String, text: String): Set<String> = CoreStore.call("geo.needed") {
        put("core", core)
        put("text", text)
    }.jsonArray.map { it.jsonPrimitive.content }.toSet()

    /** Of the [installed] resources, those an automatic update fetches now (geo::due, interval from the settings). */
    fun due(state: String, installed: List<String>, now: Long): List<String> = CoreStore.call("geo.due") {
        put("state", state)
        put("installed", JsonArray(installed.map(::JsonPrimitive)))
        put("now", now)
    }.jsonArray.map { it.jsonPrimitive.content }

    /** Whether the automatic update is on, and its period in hours (at least one). */
    data class Schedule(val enabled: Boolean, val intervalHours: Int)

    fun schedule(): Schedule = CoreStore.call("geo.schedule").jsonObject.let {
        Schedule(it.getValue("enabled").jsonPrimitive.boolean, it.getValue("intervalHours").jsonPrimitive.int)
    }

    /** The addresses the user set, by resource. */
    fun customUrls(): Map<String, String> = CoreStore.call("geo.custom").jsonObject.mapValues { it.value.jsonPrimitive.content }

    fun decodeState(text: String): Map<String, Installed> = if (text.isBlank()) emptyMap() else try {
        json.decodeFromString(stateSerializer, text)
    } catch (_: Exception) {
        emptyMap()
    }

    fun encodeState(state: Map<String, Installed>): String = json.encodeToString(stateSerializer, state)
}
