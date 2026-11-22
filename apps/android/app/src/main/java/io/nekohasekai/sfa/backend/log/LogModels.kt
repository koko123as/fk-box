package io.nekohasekai.sfa.backend.log

/**
 * [version] changes whenever lines or a clear wait in the buffer
 * ([LogViewModel.drain]). The log records themselves live in the UI
 * (client-core `logs.rs`), which applies the batches the bridge forwards.
 */
data class LogUiState(
    val version: Long = 0,
    val defaultLogLevel: LogLevel? = null,
)

enum class LogLevel(val priority: Int) {
    Default(7), PANIC(0), FATAL(1), ERROR(2), WARNING(3), INFO(4), DEBUG(5), TRACE(6);

    companion object {
        fun from(priority: Int) = entries.firstOrNull { it.priority == priority } ?: Default
    }
}

/**
 * The core's log lines since the bridge last drained them, at most [limit]
 * (the UI keeps no more). A clear drops what waits and makes the next batch a
 * reset; the default level is sent once after it changes.
 */
internal class LogEventBuffer(private val limit: Int = 3000) {
    private var reset = false
    private var level: Int? = null
    private var levelChanged = false
    private val lines = ArrayDeque<Map<String, Any?>>()

    init { require(limit > 0) }

    @Synchronized
    fun reset() {
        reset = true
        lines.clear()
    }

    @Synchronized
    fun setLevel(priority: Int) {
        if (level == priority && !levelChanged) return
        level = priority
        levelChanged = true
    }

    @Synchronized
    fun add(batch: List<Map<String, Any?>>) {
        for (entry in batch.takeLast(limit)) {
            if (lines.size == limit) lines.removeFirst()
            lines.addLast(entry)
        }
    }

    /** One batch for client-core `Logs::apply_batch`; null when nothing waits, unless [force]. */
    @Synchronized
    fun drain(force: Boolean): Map<String, Any?>? {
        if (!force && !reset && !levelChanged && lines.isEmpty()) return null
        val batch = HashMap<String, Any?>()
        batch["reset"] = reset
        if (levelChanged) batch["defaultLogLevel"] = level
        batch["entries"] = lines.toList()
        reset = false
        levelChanged = false
        lines.clear()
        return batch
    }
}
