package io.nekohasekai.sfa.bridge

import io.nekohasekai.sfa.bg.CrashReportManager
import io.nekohasekai.sfa.bg.OOMReportManager
import io.nekohasekai.sfa.bg.PowerReportManager
import io.nekohasekai.sfa.bg.ReportFile
import io.nekohasekai.sfa.bg.ReportManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal class BoxReports(private val activity: BoxActivity) {
    private enum class Operation { List, Clear, Delete, Files, Read, Save, Export }

    suspend fun list(kind: String): Any? = invoke(Operation.List, kind)

    suspend fun clear(kind: String) { invoke(Operation.Clear, kind) }

    suspend fun delete(kind: String, id: String?) { invoke(Operation.Delete, kind, id) }

    suspend fun files(kind: String, id: String?): Any? = invoke(Operation.Files, kind, id)

    suspend fun read(kind: String, id: String?, name: String?): Any? = invoke(Operation.Read, kind, id, name)

    /** Returns false when the user dismissed the file picker. */
    suspend fun save(kind: String, id: String?, name: String?): Boolean = invoke(Operation.Save, kind, id, name) == true

    suspend fun export(kind: String, id: String?, config: Boolean, logs: Boolean, encrypt: Boolean): Boolean =
        invoke(Operation.Export, kind, id, config = config, logs = logs, encrypt = encrypt) == true

    private suspend fun invoke(
        method: Operation,
        kind: String,
        id: String? = null,
        name: String? = null,
        config: Boolean = false,
        logs: Boolean = false,
        encrypt: Boolean = false,
    ): Any? {
        val manager: ReportManager = when (kind) {
            "crash" -> CrashReportManager
            "oom" -> OOMReportManager
            "power" -> PowerReportManager
            else -> error("Unknown report kind")
        }
        val result = withContext(Dispatchers.IO) {
            manager.refresh()
            if (method == Operation.List) return@withContext manager.reports.value.map {
                mapOf("id" to it.id, "date" to it.date.time, "read" to it.isRead)
            }
            if (method == Operation.Clear) { manager.deleteAll(); return@withContext null }
            val report = manager.reports.value.firstOrNull { it.id == id } ?: error("Report no longer exists")
            when (method) {
                Operation.Delete -> { manager.delete(report); null }
                Operation.Files -> {
                    manager.markAsRead(report)
                    manager.availableFiles(report).map { mapOf("name" to it.file.name, "label" to it.displayName, "kind" to it.kind, "size" to it.file.length()) }
                }
                Operation.Read, Operation.Save -> {
                    val file = manager.availableFiles(report).firstOrNull { it.file.name == name } ?: error("Report file no longer exists")
                    if (method == Operation.Save) file.file else {
                        require(file.kind != ReportFile.PROFILE) { "Download binary profiles to inspect them" }
                        require(file.file.length() <= 16 * 1024 * 1024) { "Report is too large to view; download it" }
                        manager.loadFileContent(file)
                    }
                }
                Operation.Export -> manager.createZipArchive(report, config, logs, encrypt)
                Operation.List, Operation.Clear -> error("Unknown report operation")
            }
        }
        if (result is File) {
            val destination = activity.pickDocument(result.name) ?: return false
            withContext(Dispatchers.IO) {
                activity.contentResolver.openOutputStream(destination, "wt")?.use { output -> result.inputStream().use { it.copyTo(output) } }
                    ?: error("Unable to save report")
            }
            return true
        }
        return result
    }
}
