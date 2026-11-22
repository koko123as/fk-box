package io.nekohasekai.sfa.bg

import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** One report: a directory named by its UTC timestamp under the manager's reports directory. */
data class Report(
    val id: String,
    val date: Date,
    val directory: File,
    val isRead: Boolean,
)

/**
 * A file of a report. [kind] is the wire name the UI reads (METADATA, CONFIG,
 * GO_LOG, JVM_LOG, TIMELINE, EVENTS, PROFILE).
 */
data class ReportFile(
    val kind: String,
    val displayName: String,
    val file: File,
) {
    companion object {
        const val METADATA = "METADATA"
        const val CONFIG = "CONFIG"
        const val GO_LOG = "GO_LOG"
        const val JVM_LOG = "JVM_LOG"
        const val TIMELINE = "TIMELINE"
        const val EVENTS = "EVENTS"
        const val PROFILE = "PROFILE"
    }
}

/**
 * The shared part of the crash, OOM and power report managers: scanning the
 * report directories, read markers, deletion and the export archive. Each
 * manager names its directory and lists its own files.
 */
abstract class ReportManager(private val directoryName: String) {
    companion object {
        const val METADATA_FILE_NAME = "metadata.json"
        const val CONFIG_FILE_NAME = "configuration.json"
        const val GO_LOG_FILE_NAME = "go.log"
        const val READ_MARKER_FILE_NAME = ".read"
    }

    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd'T'HH-mm-ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private var workingDirectory: File? = null

    /** Set by `install`; the reports live in its [directoryName]. */
    protected val workingDir: File
        get() = workingDirectory ?: error("$directoryName not installed")

    /** The working directory, or null before `install`. */
    protected val workingDirOrNull: File?
        get() = workingDirectory

    private val _reports = MutableStateFlow<List<Report>>(emptyList())
    val reports: StateFlow<List<Report>> = _reports
    private val _unreadCount = MutableStateFlow(0)
    val unreadCount: StateFlow<Int> = _unreadCount

    /** Files at most this long are shown whole; longer ones by their tail. */
    protected open val contentTailLimit: Long? = null

    protected fun setWorkingDir(dir: File) {
        workingDirectory = dir
    }

    protected fun reportsDir(): File = File(workingDir, directoryName)

    abstract fun availableFiles(report: Report): List<ReportFile>

    /** Adds files to an export after the report was copied into [dir]. */
    protected open fun beforeArchive(dir: File) {}

    suspend fun refresh() = withContext(Dispatchers.IO) {
        publish(scanReports())
    }

    private fun publish(reports: List<Report>) {
        _reports.value = reports
        _unreadCount.value = reports.count { !it.isRead }
    }

    private fun scanReports(): List<Report> {
        val reportsDir = reportsDir()
        if (!reportsDir.isDirectory) return emptyList()
        val directories = reportsDir.listFiles { file -> file.isDirectory } ?: return emptyList()
        return directories.mapNotNull { dir ->
            val date = parseTimestamp(dir.name) ?: return@mapNotNull null
            Report(
                id = dir.name,
                date = date,
                directory = dir,
                isRead = File(dir, READ_MARKER_FILE_NAME).exists(),
            )
        }.sortedByDescending { it.date }
    }

    /**
     * The [named] files (file name, kind, label) that exist, then, when
     * [othersKind] is set, every other plain file except [skip] under that
     * kind, labelled by its name.
     */
    protected fun listFiles(report: Report, named: List<Triple<String, String, String>>, othersKind: String? = null, skip: Set<String> = emptySet()): List<ReportFile> {
        val files = named.mapNotNull { (name, kind, label) ->
            File(report.directory, name).takeIf { it.exists() }?.let { ReportFile(kind, label, it) }
        }.toMutableList()
        if (othersKind != null) {
            val excluded = named.map { it.first }.toSet() + skip + READ_MARKER_FILE_NAME
            report.directory.listFiles()?.filter { it.isFile && it.name !in excluded }?.sortedBy { it.name }?.forEach {
                files.add(ReportFile(othersKind, it.name, it))
            }
        }
        return files
    }

    fun loadFileContent(file: ReportFile): String {
        if (!file.file.exists()) return ""
        val limit = contentTailLimit
        val length = file.file.length()
        val content = if (limit != null && length > limit) {
            RandomAccessFile(file.file, "r").use { randomAccess ->
                randomAccess.seek(length - limit)
                val bytes = ByteArray(limit.toInt())
                randomAccess.readFully(bytes)
                "…\n" + String(bytes)
            }
        } else {
            file.file.readText()
        }
        if (file.kind == ReportFile.METADATA) {
            return runCatching { JSONObject(content).toString(2) }.getOrDefault(content)
        }
        return content
    }

    fun markAsRead(report: Report) {
        File(report.directory, READ_MARKER_FILE_NAME).createNewFile()
        publish(_reports.value.map { if (it.id == report.id) it.copy(isRead = true) else it })
    }

    suspend fun delete(report: Report) = withContext(Dispatchers.IO) {
        report.directory.deleteRecursively()
        publish(_reports.value.filter { it.id != report.id })
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        reportsDir().deleteRecursively()
        publish(emptyList())
    }

    /** The report as a zip (age-encrypted on request), without the read marker and, unless asked, its configuration and Go log. */
    suspend fun createZipArchive(report: Report, includeConfig: Boolean, includeLog: Boolean, useAgeEncryption: Boolean): File = withContext(Dispatchers.IO) {
        val cacheDir = File(Application.application.cacheDir, directoryName)
        cacheDir.mkdirs()
        val zipFile = File(cacheDir, if (useAgeEncryption) "${report.id}.zip.age" else "${report.id}.zip")
        zipFile.delete()
        val strippedDir = File(cacheDir, report.id)
        strippedDir.deleteRecursively()
        report.directory.copyRecursively(strippedDir, overwrite = true)
        File(strippedDir, READ_MARKER_FILE_NAME).delete()
        if (!includeConfig) {
            File(strippedDir, CONFIG_FILE_NAME).delete()
        }
        if (!includeLog) {
            File(strippedDir, GO_LOG_FILE_NAME).delete()
        }
        beforeArchive(strippedDir)
        Libbox.createZipArchive(strippedDir.path, zipFile.path, useAgeEncryption)
        zipFile
    }

    /** A free report directory for [date]: its timestamp, then "-1", "-2", … */
    protected fun nextAvailableReportDir(date: Date): File {
        val baseName = timestampFormat.format(date)
        var index = 0
        while (true) {
            val suffix = if (index == 0) "" else "-$index"
            val dir = File(reportsDir(), baseName + suffix)
            if (!dir.exists()) return dir
            index++
        }
    }

    private fun parseTimestamp(name: String): Date? {
        val components = name.split("-")
        val baseName = if (components.size > 5 && components.last().toIntOrNull() != null) {
            components.dropLast(1).joinToString("-")
        } else {
            name
        }
        return try {
            synchronized(timestampFormat) { timestampFormat.parse(baseName) }
        } catch (_: ParseException) {
            null
        }
    }
}
