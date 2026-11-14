package io.nekohasekai.sfa.bg

import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.BuildConfig
import org.json.JSONObject
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Crash reports under `crash_reports/`: the core's Go crashes and the app's own JVM crashes. */
object CrashReportManager : ReportManager("crash_reports") {
    private const val JVM_LOG_FILE_NAME = "jvm.log"
    private const val UI_PANIC_FILE_NAME = "fkui-panic.txt"
    private const val PENDING_JVM_CRASH_FILE_NAME = "CrashReport-JVM.log"
    private const val PENDING_JVM_METADATA_FILE_NAME = "CrashReport-JVM-metadata.json"

    private lateinit var baseDir: File

    @Volatile private var handlerInstalled = false

    /** Cheap: only chains the uncaught exception handler (main thread, at start). */
    fun installHandler() {
        if (handlerInstalled) return
        handlerInstalled = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            writePendingJvmCrashReport(thread, throwable)
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Disk work: sets the directories and archives a pending JVM crash (off the main thread). */
    fun install(workingDir: File, baseDir: File) {
        setWorkingDir(workingDir)
        this.baseDir = baseDir
        archivePendingJvmCrashReport()
        installHandler()
    }

    private fun writePendingJvmCrashReport(thread: Thread, throwable: Throwable) {
        try {
            // A crash before the working directory was installed resolves it here.
            val dir = workingDirOrNull ?: Application.application.getExternalFilesDir(null) ?: return
            val writer = StringWriter()
            throwable.printStackTrace(PrintWriter(writer))
            File(dir, PENDING_JVM_CRASH_FILE_NAME).writeText(writer.toString())
            val metadata = JSONObject().apply {
                put("source", "Application")
                put("crashedAt", formatTimestampISO8601(Date()))
                put("exceptionName", throwable.javaClass.name)
                put("exceptionReason", throwable.message ?: "")
                put("processName", Application.application.packageName)
                put("appVersion", BuildConfig.VERSION_CODE.toString())
                put("appMarketingVersion", BuildConfig.VERSION_NAME)
                runCatching {
                    put("coreVersion", Libbox.version())
                    put("goVersion", Libbox.goVersion())
                }
            }
            File(dir, PENDING_JVM_METADATA_FILE_NAME).writeText(metadata.toString())
        } catch (_: Throwable) {
        }
    }

    /**
     * Archives the crash the last process recorded. An I/O failure here (full or
     * read-only storage, a damaged file) must not crash the starting app, which
     * would record the same crash again and loop: the pending files are dropped
     * instead.
     */
    private fun archivePendingJvmCrashReport() {
        try {
            archivePendingJvmCrashReportOrThrow()
        } catch (e: Exception) {
            android.util.Log.w("CrashReportManager", "archive pending crash report", e)
            runCatching { File(workingDir, PENDING_JVM_CRASH_FILE_NAME).delete() }
            runCatching { File(workingDir, PENDING_JVM_METADATA_FILE_NAME).delete() }
            runCatching { File(baseDir, CONFIG_FILE_NAME).delete() }
        }
    }

    private fun archivePendingJvmCrashReportOrThrow() {
        val crashFile = File(workingDir, PENDING_JVM_CRASH_FILE_NAME)
        val metadataFile = File(workingDir, PENDING_JVM_METADATA_FILE_NAME)
        val configFile = File(baseDir, CONFIG_FILE_NAME)
        if (!crashFile.exists()) return
        val content = crashFile.readText().trim()
        if (content.isEmpty()) {
            crashFile.delete()
            metadataFile.delete()
            configFile.delete()
            return
        }
        val crashDate = Date(crashFile.lastModified())
        val reportDir = nextAvailableReportDir(crashDate)
        reportDir.mkdirs()
        crashFile.copyTo(File(reportDir, JVM_LOG_FILE_NAME), overwrite = true)
        crashFile.delete()
        if (metadataFile.exists()) {
            metadataFile.copyTo(File(reportDir, METADATA_FILE_NAME), overwrite = true)
            metadataFile.delete()
        }
        if (configFile.exists()) {
            val configContent = runCatching { configFile.readText() }.getOrNull()?.trim()
            if (!configContent.isNullOrEmpty()) {
                configFile.copyTo(File(reportDir, CONFIG_FILE_NAME), overwrite = true)
            }
            configFile.delete()
        }
    }

    override fun availableFiles(report: Report): List<ReportFile> = listFiles(
        report,
        listOf(
            Triple(METADATA_FILE_NAME, ReportFile.METADATA, "Metadata"),
            Triple(GO_LOG_FILE_NAME, ReportFile.GO_LOG, "Go Crash Log"),
            Triple(JVM_LOG_FILE_NAME, ReportFile.JVM_LOG, "JVM Crash Log"),
            Triple(CONFIG_FILE_NAME, ReportFile.CONFIG, "Configuration"),
        ),
    )

    /**
     * jvm.log is the crash's stack trace, not a log: it stays in every export.
     * The UI's latest panic (Rust, written by libfkui's panic hook) is added
     * as plain text.
     */
    override fun beforeArchive(dir: File) {
        File(Application.application.filesDir, UI_PANIC_FILE_NAME).takeIf { it.isFile }?.let {
            runCatching { it.copyTo(File(dir, UI_PANIC_FILE_NAME), overwrite = true) }
        }
    }

    private fun formatTimestampISO8601(date: Date): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return format.format(date)
    }
}
