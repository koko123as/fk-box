package io.nekohasekai.sfa.bridge

import android.content.Intent
import android.os.Build
import android.provider.Settings as AndroidSettings
import android.security.KeyChain
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.bg.BoxService
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.ProfileContent
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.utils.CommandTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import java.io.File

/** Local routed HTTP recording and opt-in TLS interception. */
internal class BoxCapture(private val activity: BoxActivity) {
    private val directory = File(activity.noBackupFilesDir, "http-debug")
    private val captures = File(directory, "captures")
    private val settings = CaptureSettings(directory)
    private val certificate = MitmCertificate(activity)

    private companion object {
        val mutex = Mutex()
    }

    private fun requireLocal() {
        require(!CommandTarget.isRemote) { "HTTP debugging is only available for the local service" }
    }
    private fun status() = JSONObject(Libbox.readUICaptureStatus())

    private enum class Operation { CaSnapshot, CaGenerate, Snapshot, Settings, Logs, ClearLogs, Configure, Start, Stop, Clear, Domains, Detail }

    suspend fun caSnapshot(): Any? = session(Operation.CaSnapshot)

    suspend fun caGenerate(revision: String?): Any? = session(Operation.CaGenerate, revision = revision)

    suspend fun snapshot(limit: Int?, query: String?): Any? = session(Operation.Snapshot, limit = limit, query = query)

    suspend fun captureSettings(): Any? = session(Operation.Settings)

    suspend fun logs(): Any? = session(Operation.Logs)

    suspend fun clearLogs() { session(Operation.ClearLogs) }

    suspend fun configure(revision: String?, config: Map<String, Any?>?): Any? = session(Operation.Configure, revision = revision, config = config)

    suspend fun start(): Any? = session(Operation.Start)

    suspend fun stop(): Any? = session(Operation.Stop)

    suspend fun clear() { session(Operation.Clear) }

    suspend fun domains(query: String?): Any? = session(Operation.Domains, query = query)

    suspend fun detail(id: String?): Any? = session(Operation.Detail, id = id)

    /** Saves a capture export; false when the user dismissed the file picker. */
    suspend fun export(format: String?, id: String? = null, query: String? = null): Boolean {
        requireLocal()
        return exportFile(format, id, query)
    }

    suspend fun exportCertificate(revision: String?, module: Boolean): Boolean {
        requireLocal()
        return exportCertificateFile(revision, module)
    }

    suspend fun installCertificate(revision: String?): Boolean {
        requireLocal()
        return openCertificateInstall(revision)
    }

    private suspend fun session(
        method: Operation,
        revision: String? = null,
        limit: Int? = null,
        query: String? = null,
        config: Map<String, Any?>? = null,
        id: String? = null,
    ): Any? {
        requireLocal()
        val certificateOperation = method == Operation.CaSnapshot || method == Operation.CaGenerate
        return mutex.withLock {
            var started = false
            try {
                withContext(Dispatchers.IO) {
                    requireLocal()
                    if (method != Operation.Logs && method != Operation.ClearLogs && !certificateOperation) Libbox.openUICapture(captures.absolutePath)
                    when (method) {
                        Operation.CaSnapshot -> certificate.snapshot() + mapOf(
                            "captureActive" to status().optBoolean("active"),
                            "rewriteActive" to status().optBoolean("rewriteActive"),
                        )
                        Operation.CaGenerate -> {
                            require(!status().optBoolean("active")) { "Stop capture before changing the MITM certificate" }
                            certificate.generate(revision ?: error("Missing certificate revision"))
                        }
                        Operation.Snapshot -> mapOf(
                            "state" to jsonObject(status()),
                            "page" to jsonObject(JSONObject(Libbox.listUICapture(
                                limit?.coerceIn(1, 300) ?: 50,
                                query.orEmpty().take(1024),
                            ))),
                        )
                        Operation.Settings -> settings.snapshot()
                        Operation.Logs -> jsonObject(JSONObject(Libbox.readUICaptureLogs()))
                        Operation.ClearLogs -> { Libbox.clearUICaptureLogs(); null }
                        Operation.Configure -> {
                            require(!status().optBoolean("active")) { "Stop capture before changing its settings" }
                            val value = config ?: error("Missing capture settings")
                            settings.save(revision ?: error("Missing capture settings revision"), JSONObject(value).toString())
                        }
                        Operation.Start -> start { started = true }
                        Operation.Stop -> { Libbox.stopUICapture(); jsonObject(status()) }
                        Operation.Clear -> { Libbox.clearUICapture(); null }
                        Operation.Domains -> {
                            val domains = JSONArray(Libbox.extractUICaptureHosts(query.orEmpty().take(1024)))
                            List(domains.length()) { domains.getString(it) }
                        }
                        Operation.Detail -> jsonObject(JSONObject(Libbox.readUICapture(id ?: error("Missing request ID"))))
                    }
                }
            } catch (error: Exception) {
                // Covers cancellation while returning from Dispatchers.IO as
                // well as failures after the native session has been created.
                if (started) withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { Libbox.stopUICapture() }.onFailure(error::addSuppressed)
                }
                if (method == Operation.Start && error !is CancellationException) {
                    Libbox.appendUICaptureLog("error", "Capture startup failed: ${error.message}")
                }
                throw error
            }
        }
    }

    private suspend fun start(onStarted: () -> Unit): Map<String, Any?> {
        require(!status().optBoolean("active")) { "Capture is already running" }
        require(activity.serviceStatus.value == Status.Started) { "Start the local service before capturing requests" }
        val running = BoxService.dashboardConfig.value ?: error("Local service is not running")
        val value = settings.config()
        return ProfileContent.mutex.withLock {
            requireLocal()
            require(activity.serviceStatus.value == Status.Started &&
                running.profileId == Settings.selectedProfile &&
                running === BoxService.dashboardConfig.value && running.server === BoxService.dashboardServer) {
                "Local service changed; start capture again"
            }
            synchronized(MitmCertificate.accessLock) {
                val material = if (JSONObject(value).optBoolean("https")) certificate.material() else null
                running.server.startUICapture(captures.absolutePath, value, material?.first.orEmpty(), material?.second)
            }
            onStarted()
            currentCoroutineContext().ensureActive()
            requireLocal()
            val state = status()
            check(state.optBoolean("active")) { state.optString("error", "Capture startup failed") }
            jsonObject(state)
        }
    }

    private suspend fun openCertificateInstall(revision: String?): Boolean = mutex.withLock {
        val ca = withContext(Dispatchers.IO) {
            requireLocal()
            certificate.publicCertificate(revision ?: error("Missing certificate revision"))
        }
        withContext(Dispatchers.Main) {
            requireLocal()
            // Android 11+ requires the user to select the exported CA in Settings.
            val intent = if (Build.VERSION.SDK_INT >= 30) Intent(AndroidSettings.ACTION_SECURITY_SETTINGS)
                else KeyChain.createInstallIntent().putExtra(KeyChain.EXTRA_CERTIFICATE, ca.encoded)
                    .putExtra(KeyChain.EXTRA_NAME, ca.subjectX500Principal.name)
            activity.startActivity(intent)
        }
        true // Only confirms that settings opened, never that trust was granted.
    }

    private suspend fun exportCertificateFile(revisionArgument: String?, module: Boolean): Boolean {
        val revision = revisionArgument ?: error("Missing certificate revision")
        val ca = mutex.withLock { withContext(Dispatchers.IO) { requireLocal(); certificate.publicCertificate(revision) } }
        val name = "FK-box-MITM-${MitmCertificate.fingerprint(ca).take(12)}" + if (module) ".zip" else ".crt"
        val destination = activity.pickDocument(name) ?: return false
        return mutex.withLock { withContext(Dispatchers.IO) {
            requireLocal()
            certificate.publicCertificate(revision)
            activity.contentResolver.openOutputStream(destination, "wt")?.use { output ->
                if (module) MitmCertificateModule.write(activity, output, ca)
                else output.write(MitmCertificate.pem(ca).toByteArray(Charsets.US_ASCII))
            } ?: error("Unable to export MITM certificate")
            true
        } }
    }

    private suspend fun exportFile(format: String?, id: String?, query: String?): Boolean {
        val name = when (format) {
            "curl" -> "FK-box-request.sh"
            "har" -> "FK-box-requests.har"
            "domains" -> "FK-box-domains.txt"
            else -> error("Unknown capture export format")
        }
        val destination = activity.pickDocument(name) ?: return false
        requireLocal()
        return mutex.withLock { withContext(Dispatchers.IO) {
            requireLocal()
            Libbox.openUICapture(captures.absolutePath)
            check(directory.isDirectory || directory.mkdirs()) { "Unable to create capture directory" }
            val file = File.createTempFile("export-", ".tmp", directory)
            try {
                when {
                    format == "curl" -> file.writeText(Libbox.exportUICaptureCurl(id ?: error("Missing request ID")))
                    format == "domains" -> {
                        val domains = JSONArray(Libbox.extractUICaptureHosts(query.orEmpty().take(1024)))
                        file.writeText(List(domains.length()) { domains.getString(it) }.joinToString("\n", postfix = "\n"))
                    }
                    else -> Libbox.exportUICaptureHAR(file.absolutePath, query.orEmpty().take(1024))
                }
                activity.contentResolver.openOutputStream(destination, "wt")?.use { output ->
                    file.inputStream().use { it.copyTo(output) }
                } ?: error("Unable to export capture")
                true
            } finally { file.delete() }
        } }
    }
}
