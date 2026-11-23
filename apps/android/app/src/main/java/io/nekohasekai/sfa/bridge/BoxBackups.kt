package io.nekohasekai.sfa.bridge

import io.nekohasekai.sfa.bg.UpdateProfileWork
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.CoreStore
import io.nekohasekai.sfa.database.ProfileContent
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.ServiceSettings
import io.nekohasekai.sfa.database.Settings
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * `bbox-1` backups and the WebDAV account. Packing, validation, the restore
 * (with its rollback) and the WebDAV flow are `fkbox-client-core`
 * (crates/client-core/src/storage/backup.rs, webdav.rs), reached through
 * [CoreStore.callKernel] so Rust can ask libbox and the Keystore for what it
 * needs ([io.nekohasekai.sfa.database.CoreKernel]). What stays here is
 * Android's part of a restore: applying the service settings (package query
 * mode, app scans), putting the written values in the settings cache,
 * applying the selected profile to the running service and recovering it
 * when that fails.
 */
internal class BoxBackups(
    private val activity: BoxActivity,
    private val writeSettings: suspend (Map<String, Any?>) -> Unit,
    private val applySelected: suspend (restart: Boolean, recover: Boolean) -> Unit,
    private val publish: suspend () -> Unit,
    private val emit: (String, Any?) -> Unit,
) {
    /** A backup of everything, as the text written to a file. */
    suspend fun create(): String = ProfileContent.mutex.withLock {
        Settings.dataStore.flush()
        CoreStore.callKernel("backup.create") { put("generated", UUID.randomUUID().toString()) }.jsonPrimitive.content
    }

    /**
     * Reads and checks a backup and keeps it in client-core; returns its
     * token and summary (`profileCount`) for [restore].
     */
    fun read(text: String, scope: String): Map<String, Any?> =
        plainMap(CoreStore.callKernel("backup.read") { put("text", text); put("scope", scope) })

    fun webdavAccount(): Map<String, Any?>? = CoreStore.callKernel("webdav.account") {}.takeIf { it !is JsonNull }?.let(::plainMap)

    /** Saves the WebDAV account, or removes it when [value] is null; returns what is saved. */
    fun setWebdavAccount(value: Map<String, Any?>?): Map<String, Any?>? =
        CoreStore.callKernel("webdav.save") { put("value", ServiceSettings.element(value)) }.takeIf { it !is JsonNull }?.let(::plainMap)

    fun checkWebdav(): Boolean = CoreStore.callKernel("webdav.check") {}.jsonPrimitive.content == "true"

    suspend fun uploadWebdav(): Boolean = ProfileContent.mutex.withLock {
        Settings.dataStore.flush()
        CoreStore.callKernel("webdav.upload") { put("generated", UUID.randomUUID().toString()) }
        true
    }

    fun readWebdav(scope: String): Map<String, Any?> = plainMap(CoreStore.callKernel("webdav.read") { put("scope", scope) })

    /**
     * Restores the backup read under [token]. client-core writes the store
     * and the files in one step; then the service settings are applied and
     * the selected profile is applied to the service. When that fails,
     * client-core puts everything back, the earlier service settings are
     * applied again and a running service is recovered.
     */
    suspend fun restore(token: String, scope: String, strategy: String): Int {
        val count = ProfileContent.mutex.withLock {
            val wasRunning = activity.serviceStatus.value == Status.Started
            Settings.dataStore.flush()
            val result = CoreStore.callKernel("backup.restore") {
                put("token", token)
                put("scope", scope)
                put("strategy", strategy)
                put("filesDir", activity.filesDir.path)
                put("cacheDir", activity.cacheDir.path)
            }.jsonObject
            val restoreToken = result.getValue("token").jsonPrimitive.long
            ServiceSettings.adopt(result.getValue("written"))
            ProfileManager.notifyChanged()
            try {
                result["settings"]?.takeIf { it !is JsonNull }?.let { writeSettings(plainMap(it)) }
                Settings.dataStore.flush()
                applySelected(scope == "all", false)
                publish()
                emit("preferences", null)
                CoreStore.call("backup.finish") { put("token", restoreToken) }
                result.getValue("count").jsonPrimitive.int
            } catch (error: Exception) {
                withContext(NonCancellable) {
                    suspend fun attempt(task: suspend () -> Unit) {
                        try { task() } catch (rollback: Exception) { error.addSuppressed(rollback) }
                    }
                    attempt {
                        ServiceSettings.adopt(CoreStore.call("backup.rollback") { put("token", restoreToken) }.jsonObject.getValue("written"))
                        ProfileManager.notifyChanged()
                    }
                    result["previousSettings"]?.takeIf { it !is JsonNull }?.let { previous -> attempt { writeSettings(plainMap(previous)) } }
                    attempt { Settings.dataStore.flush() }
                    if (wasRunning) attempt { applySelected(true, true) }
                    attempt { publish(); emit("preferences", null) }
                }
                throw error
            }
        }
        UpdateProfileWork.reconfigureUpdater()
        return count
    }

    companion object {
        const val MAX_BYTES = 64 * 1024 * 1024

        @Suppress("UNCHECKED_CAST")
        private fun plainMap(element: JsonElement): Map<String, Any?> = ServiceSettings.plain(element) as Map<String, Any?>
    }
}
