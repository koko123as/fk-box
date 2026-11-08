package io.nekohasekai.sfa.bridge

import io.nekohasekai.sfa.bg.CrashReportManager
import io.nekohasekai.sfa.bg.OOMReportManager
import io.nekohasekai.sfa.bg.PowerReportManager
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
        require(kind in listOf("crash", "oom", "power")) { "Unknown report kind" }
        val result = withContext(Dispatchers.IO) {
            when (kind) {
                "crash" -> {
                    CrashReportManager.refresh()
                    if (method == Operation.List) return@withContext CrashReportManager.reports.value.map {
                        mapOf("id" to it.id, "date" to it.date.time, "read" to it.isRead)
                    }
                    if (method == Operation.Clear) { CrashReportManager.deleteAll(); return@withContext null }
                    val report = CrashReportManager.reports.value.firstOrNull { it.id == id } ?: error("Report no longer exists")
                    when (method) {
                        Operation.Delete -> { CrashReportManager.delete(report); null }
                        Operation.Files -> {
                            CrashReportManager.markAsRead(report)
                            CrashReportManager.availableFiles(report).map { mapOf("name" to it.file.name, "label" to it.displayName, "kind" to it.kind.name, "size" to it.file.length()) }
                        }
                        Operation.Read, Operation.Save -> {
                            val file = CrashReportManager.availableFiles(report).firstOrNull { it.file.name == name } ?: error("Report file no longer exists")
                            if (method == Operation.Save) file.file else {
                                require(file.kind.name != "PROFILE") { "Download binary profiles to inspect them" }
                                require(file.file.length() <= 16 * 1024 * 1024) { "Report is too large to view; download it" }
                                CrashReportManager.loadFileContent(file)
                            }
                        }
                        Operation.Export -> CrashReportManager.createZipArchive(report, config, logs, encrypt)
                        Operation.List, Operation.Clear -> error("Unknown report operation")
                    }
                }
                "oom" -> {
                    OOMReportManager.refresh()
                    if (method == Operation.List) return@withContext OOMReportManager.reports.value.map {
                        mapOf("id" to it.id, "date" to it.date.time, "read" to it.isRead)
                    }
                    if (method == Operation.Clear) { OOMReportManager.deleteAll(); return@withContext null }
                    val report = OOMReportManager.reports.value.firstOrNull { it.id == id } ?: error("Report no longer exists")
                    when (method) {
                        Operation.Delete -> { OOMReportManager.delete(report); null }
                        Operation.Files -> {
                            OOMReportManager.markAsRead(report)
                            OOMReportManager.availableFiles(report).map { mapOf("name" to it.file.name, "label" to it.displayName, "kind" to it.kind.name, "size" to it.file.length()) }
                        }
                        Operation.Read, Operation.Save -> {
                            val file = OOMReportManager.availableFiles(report).firstOrNull { it.file.name == name } ?: error("Report file no longer exists")
                            if (method == Operation.Save) file.file else {
                                require(file.kind.name != "PROFILE") { "Download binary profiles to inspect them" }
                                require(file.file.length() <= 16 * 1024 * 1024) { "Report is too large to view; download it" }
                                OOMReportManager.loadFileContent(file)
                            }
                        }
                        Operation.Export -> OOMReportManager.createZipArchive(report, config, logs, encrypt)
                        Operation.List, Operation.Clear -> error("Unknown report operation")
                    }
                }
                "power" -> {
                    PowerReportManager.refresh()
                    if (method == Operation.List) return@withContext PowerReportManager.reports.value.map {
                        mapOf("id" to it.id, "date" to it.date.time, "read" to it.isRead)
                    }
                    if (method == Operation.Clear) { PowerReportManager.deleteAll(); return@withContext null }
                    val report = PowerReportManager.reports.value.firstOrNull { it.id == id } ?: error("Report no longer exists")
                    when (method) {
                        Operation.Delete -> { PowerReportManager.delete(report); null }
                        Operation.Files -> {
                            PowerReportManager.markAsRead(report)
                            PowerReportManager.availableFiles(report).map { mapOf("name" to it.file.name, "label" to it.displayName, "kind" to it.kind.name, "size" to it.file.length()) }
                        }
                        Operation.Read, Operation.Save -> {
                            val file = PowerReportManager.availableFiles(report).firstOrNull { it.file.name == name } ?: error("Report file no longer exists")
                            if (method == Operation.Save) file.file else {
                                require(file.kind.name != "PROFILE") { "Download binary profiles to inspect them" }
                                require(file.file.length() <= 16 * 1024 * 1024) { "Report is too large to view; download it" }
                                PowerReportManager.loadFileContent(file)
                            }
                        }
                        Operation.Export -> PowerReportManager.createZipArchive(report, config, logs, encrypt)
                        Operation.List, Operation.Clear -> error("Unknown report operation")
                    }
                }
                else -> error("Unknown report kind")
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
