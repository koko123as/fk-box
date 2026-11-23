package io.nekohasekai.sfa.database

import android.util.Log
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.constant.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import java.io.File

/**
 * The client store of `fkbox-client-core` (crates/client-core): service
 * settings, profile metadata, remote servers and interface preferences in one
 * SQLite file, shared with the Rust UI in libfkui.so. [Settings],
 * [ProfileManager] and [RemoteServerManager] read and write it through [call].
 *
 * The first call opens it (loading libfkui.so if the UI has not) and runs the
 * one-time import of the Room databases and the `flclash_ui` preferences. The
 * old files are left as they were, so an older release still finds them.
 */
object CoreStore {
    private const val TAG = "CoreStore"
    internal val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Volatile private var opened = false

    /** The import's result as recorded in the store (logged once per process). */
    @Volatile var migration: String = ""
        private set

    private fun ensureOpen() {
        if (opened) return
        synchronized(this) {
            if (opened) return
            System.loadLibrary("fkui")
            val app = Application.application
            val dataDir = File(app.applicationInfo.dataDir)
            migration = nativeOpen(
                File(app.filesDir, Path.CLIENT_STORE_PATH).path,
                app.getDatabasePath(Path.PROFILES_DATABASE_PATH).path,
                app.getDatabasePath(Path.SETTINGS_DATABASE_PATH).path,
                File(dataDir, "shared_prefs/${Path.UI_PREFERENCES_NAME}.xml").path,
                app.cacheDir.path,
            ) ?: ""
            Log.i(TAG, "client store opened; legacy import: $migration")
            opened = true
        }
    }

    /** One store call (crates/client-core/src/api.rs); a failure throws with the store's message. */
    fun call(method: String, args: JsonObject = JsonObject(emptyMap())): JsonElement {
        ensureOpen()
        val result = nativeCall(method, args.toString()) ?: return JsonNull
        return json.parseToJsonElement(result)
    }

    fun call(method: String, build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonElement = call(method, buildJsonObject(build))

    /**
     * A call that reaches the cores (subscription fetches, profile content
     * saves): Rust calls back into [CoreKernel] on this thread, so it blocks
     * for the fetch; call it off the main thread.
     */
    fun callKernel(method: String, build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonElement {
        ensureOpen()
        val result = nativeCallKernel(method, buildJsonObject(build).toString(), CoreKernel) ?: return JsonNull
        return json.parseToJsonElement(result)
    }

    internal fun <T> decode(serializer: kotlinx.serialization.KSerializer<T>, element: JsonElement): T = json.decodeFromJsonElement(serializer, element)

    internal fun <T> encode(serializer: kotlinx.serialization.KSerializer<T>, value: T): JsonElement = json.encodeToJsonElement(serializer, value)

    @JvmStatic private external fun nativeOpen(storePath: String, profilesDb: String, settingsDb: String, uiPreferences: String, scratchDir: String): String?

    @JvmStatic private external fun nativeCall(method: String, args: String): String?

    @JvmStatic private external fun nativeCallKernel(method: String, args: String, kernel: Any): String?
}
