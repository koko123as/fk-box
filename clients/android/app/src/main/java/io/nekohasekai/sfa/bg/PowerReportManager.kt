package io.nekohasekai.sfa.bg

import java.io.File

/** Power reports the core writes under `power_reports/`; long timelines are shown by their tail. */
object PowerReportManager : ReportManager("power_reports") {
    private const val TIMELINE_FILE_NAME = "timeline.jsonl"
    private const val EVENTS_FILE_NAME = "events.jsonl"

    override val contentTailLimit: Long = 512L * 1024L

    fun install(workingDir: File) {
        setWorkingDir(workingDir)
    }

    override fun availableFiles(report: Report): List<ReportFile> = listFiles(
        report,
        listOf(
            Triple(METADATA_FILE_NAME, ReportFile.METADATA, "Metadata"),
            Triple(CONFIG_FILE_NAME, ReportFile.CONFIG, "Configuration"),
            Triple(TIMELINE_FILE_NAME, ReportFile.TIMELINE, TIMELINE_FILE_NAME),
            Triple(EVENTS_FILE_NAME, ReportFile.EVENTS, EVENTS_FILE_NAME),
            Triple(GO_LOG_FILE_NAME, ReportFile.GO_LOG, "Log"),
        ),
        othersKind = ReportFile.PROFILE,
    )
}
