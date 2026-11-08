package io.nekohasekai.sfa.bg

import android.content.Context
import android.util.Log
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.database.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileNotFoundException
import java.net.URI
import java.security.MessageDigest

/**
 * Geo data shared by Xray and mihomo (FlClash's views/resources.dart and
 * Controller.initGeo): a copy ships in the APK's `assets/geo`, is extracted
 * on first use, and each file can be replaced from its own URL. The directory
 * is libbox's GeoDataDir (mihomo's home, also Xray's xray.location.asset).
 */
object GeoResources {
    private const val TAG = "GeoResources"
    private const val STATE_FILE = "geo-resources.json"
    private const val ASSET_DIR = "geo"

    /** Display order and labels match FlClash's GeoResource enum. */
    val names = listOf(GeoPolicy.GEOIP, GeoPolicy.GEOSITE, GeoPolicy.MMDB, GeoPolicy.ASN)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()

    /** Set once the bundle was checked in this process; the APK cannot change while it runs. */
    @Volatile private var bundleChecked = false

    fun label(name: String): String = when (name) {
        GeoPolicy.GEOIP -> "GeoIP"
        GeoPolicy.GEOSITE -> "GeoSite"
        GeoPolicy.MMDB -> "MMDB"
        else -> "ASN"
    }

    fun defaultUrl(name: String): String = Libbox.geoResourceDefaultURL(name)

    fun url(name: String): String = GeoPolicy.decodeUrls(Settings.geoUrls)[name]?.takeIf { it.isNotBlank() } ?: defaultUrl(name)

    private fun dir(): File = File(Libbox.geoDataDir())

    /** Extract the bundled files that are missing or older than the bundle. */
    suspend fun ensureBundled(context: Context = Application.application) = withContext(Dispatchers.IO) {
        mutex.withLock { ensureBundledLocked(context) }
    }

    /** For the service start path, which is not a coroutine. */
    fun ensureBundledBlocking(context: Context = Application.application) {
        runCatching { runBlocking { ensureBundled(context) } }.onFailure { Log.e(TAG, "extract bundled geo data", it) }
    }

    private fun ensureBundledLocked(context: Context, force: Boolean = false) {
        // Only a missing file needs another look after the first check
        // (mihomo or the user may have removed one).
        if (bundleChecked && !force && names.all { File(dir(), Libbox.geoResourceFileName(it)).isFile }) return
        val bundle = readBundle(context) ?: return
        val dir = dir().apply { mkdirs() }
        val state = readState(dir).toMutableMap()
        var changed = false
        for (name in names) {
            val bundled = bundle.files[name] ?: continue
            val target = File(dir, bundled.file)
            val installed = state[name]
            val exists = target.isFile && target.length() > 0
            if (!GeoPolicy.shouldExtract(exists, installed, bundled.sha256, bundle.time, target.lastModified())) continue
            try {
                extract(context, bundled, target)
                state[name] = GeoPolicy.Installed(GeoPolicy.SOURCE_BUILTIN, bundled.sha256, bundle.time, System.currentTimeMillis())
                changed = true
            } catch (error: Exception) {
                // The previous file (if any) stays; the cores report a missing file themselves.
                Log.e(TAG, "extract $name", error)
            }
        }
        if (changed) writeState(dir, state)
        bundleChecked = true
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

    private fun readState(dir: File): Map<String, GeoPolicy.Installed> = GeoPolicy.decodeState(
        runCatching { File(dir, STATE_FILE).readText() }.getOrDefault(""),
    )

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
            ensureBundledLocked(context)
            val dir = dir()
            val state = readState(dir)
            names.map { name ->
                val file = File(dir, Libbox.geoResourceFileName(name))
                val exists = file.isFile
                val installed = state[name]
                mapOf(
                    "name" to name,
                    "label" to label(name),
                    "file" to file.name,
                    "url" to url(name),
                    "defaultUrl" to defaultUrl(name),
                    "size" to if (exists) file.length() else null,
                    "updatedAt" to if (exists) (installed?.time ?: file.lastModified()) else null,
                    "source" to if (exists) (installed?.source ?: GeoPolicy.SOURCE_CORE) else null,
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
            // A download in Go cannot see coroutine cancellation (leaving the
            // page, WorkManager stopping the worker); cancel it explicitly.
            val task = Libbox.newGeoUpdate()
            val result = coroutineScope {
                val watcher = launch {
                    try {
                        awaitCancellation()
                    } finally {
                        task.cancel()
                    }
                }
                try {
                    task.fetch(dir.path, name, link, previous?.sha256.orEmpty(), previous?.etag.orEmpty(), previous?.lastModified.orEmpty())
                } finally {
                    watcher.cancel()
                }
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

    /** Resources whose last check is older than [intervalHours]. */
    suspend fun due(intervalHours: Int): List<String> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val state = readState(dir())
            names.filter { GeoPolicy.isDue(state[it]?.checked ?: 0L, intervalHours, System.currentTimeMillis()) }
        }
    }

    fun setUrl(name: String, url: String) {
        require(name in names) { "Unknown geo resource: $name" }
        val value = url.trim()
        val urls = GeoPolicy.decodeUrls(Settings.geoUrls).toMutableMap()
        if (value.isEmpty() || value == defaultUrl(name)) {
            urls.remove(name)
        } else {
            require(GeoPolicy.validUrl(value)) { "Not a valid HTTP or HTTPS URL" }
            urls[name] = value
        }
        Settings.geoUrls = GeoPolicy.encodeUrls(urls)
    }
}

/** The decisions behind [GeoResources], free of Android and libbox for unit tests. */
object GeoPolicy {
    const val GEOIP = "geoip"
    const val GEOSITE = "geosite"
    const val MMDB = "mmdb"
    const val ASN = "asn"

    const val SOURCE_BUILTIN = "builtin"
    const val SOURCE_DOWNLOAD = "download"

    /** A file written by mihomo itself (its own geo-auto-update or first-run download). */
    const val SOURCE_CORE = "core"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val stateSerializer by lazy { MapSerializer(String.serializer(), Installed.serializer()) }
    private val urlsSerializer = MapSerializer(String.serializer(), String.serializer())

    @Serializable
    data class BundledFile(val file: String, val sha256: String, val size: Long = 0)

    /** assets/geo/manifest.json, written by the app's prepareGeoAssets task. */
    @Serializable
    data class Bundle(val source: String = "", val commit: String = "", val time: Long, val files: Map<String, BundledFile>)

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

    /**
     * Whether the bundled copy should replace what is installed. A missing file
     * is always restored; otherwise the bundle only replaces data older than
     * itself, so a newer download (or a file mihomo fetched) is never
     * overwritten, while an app upgrade with a newer bundle replaces an older
     * bundled or downloaded file.
     */
    fun shouldExtract(exists: Boolean, installed: Installed?, bundledSha256: String, bundleTime: Long, fileModified: Long): Boolean {
        if (!exists) return true
        if (installed == null) return fileModified in 1 until bundleTime
        if (installed.sha256 == bundledSha256) return false
        return installed.time < bundleTime
    }

    fun isDue(lastChecked: Long, intervalHours: Int, now: Long): Boolean =
        lastChecked <= 0L || now - lastChecked >= intervalHours.coerceAtLeast(1) * 3_600_000L

    fun validUrl(value: String): Boolean = try {
        val uri = URI(value)
        (uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrEmpty()
    } catch (_: Exception) {
        false
    }

    fun decodeState(text: String): Map<String, Installed> = if (text.isBlank()) emptyMap() else try {
        json.decodeFromString(stateSerializer, text)
    } catch (_: Exception) {
        emptyMap()
    }

    fun encodeState(state: Map<String, Installed>): String = json.encodeToString(stateSerializer, state)

    fun decodeUrls(text: String): Map<String, String> = if (text.isBlank()) emptyMap() else try {
        json.decodeFromString(urlsSerializer, text).filter { (name, url) -> name in setOf(GEOIP, GEOSITE, MMDB, ASN) && validUrl(url) }
    } catch (_: Exception) {
        emptyMap()
    }

    fun encodeUrls(urls: Map<String, String>): String = if (urls.isEmpty()) "" else json.encodeToString(urlsSerializer, urls.toSortedMap())
}
