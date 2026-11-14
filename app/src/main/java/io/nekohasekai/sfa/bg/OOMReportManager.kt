package io.nekohasekai.sfa.bg

import java.io.File

/** Out-of-memory reports the core writes under `oom_reports/`. */
object OOMReportManager : ReportManager("oom_reports") {
    private const val CMDLINE_FILE_NAME = "cmdline"

    fun install(workingDir: File) {
        setWorkingDir(workingDir)
    }

    override fun availableFiles(report: Report): List<ReportFile> = listFiles(
        report,
        listOf(
            Triple(METADATA_FILE_NAME, ReportFile.METADATA, "Metadata"),
            Triple(CONFIG_FILE_NAME, ReportFile.CONFIG, "Configuration"),
            Triple(GO_LOG_FILE_NAME, ReportFile.GO_LOG, "Log"),
        ),
        othersKind = ReportFile.PROFILE,
        skip = setOf(CMDLINE_FILE_NAME),
    )
}
