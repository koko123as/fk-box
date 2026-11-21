package io.nekohasekai.sfa.backend.log

/** The lines themselves live in fk-core's log stream; the level is kept for the host (and its tests). */
data class LogUiState(val defaultLogLevel: LogLevel? = null)

enum class LogLevel(val label: String, val priority: Int) {
    Default("Default", 7), PANIC("Panic", 0), FATAL("Fatal", 1), ERROR("Error", 2),
    WARNING("Warn", 3), INFO("Info", 4), DEBUG("Debug", 5), TRACE("Trace", 6);

    companion object {
        fun from(priority: Int) = entries.firstOrNull { it.priority == priority } ?: Default
    }
}
