package io.nekohasekai.sfa.backend.base

/**
 * Holds one core subscription or session that is opened off the main thread.
 * Each start takes a generation; [cancel] moves to the next one. A session
 * that arrives for an old generation (the user cancelled, or a newer start
 * replaced it, while it was being opened) is closed instead of kept, and
 * callbacks check [isCurrent] so a stale session cannot update the state.
 */
internal class SessionSlot<T : Any>(private val closeSession: (T) -> Unit) {
    private var generation = 0L
    private var current: T? = null

    /** Starts a new generation, closing the session of the previous one. */
    fun begin(): Long {
        val previous = synchronized(this) {
            generation++
            current.also { current = null }
        }
        previous?.let(::close)
        return synchronized(this) { generation }
    }

    fun isCurrent(generation: Long): Boolean = synchronized(this) { generation == this.generation }

    /** Keeps [session] if [generation] is still current; closes it otherwise. */
    fun attach(generation: Long, session: T): Boolean {
        val kept = synchronized(this) {
            if (generation == this.generation) {
                current = session
                true
            } else {
                false
            }
        }
        if (!kept) close(session)
        return kept
    }

    /** Forgets the session of [generation] after it ended on its own. */
    fun clear(generation: Long) {
        synchronized(this) { if (generation == this.generation) current = null }
    }

    /** Ends the current generation and closes its session. */
    fun cancel() {
        val previous = synchronized(this) {
            generation++
            current.also { current = null }
        }
        previous?.let(::close)
    }

    private fun close(session: T) {
        try {
            closeSession(session)
        } catch (_: Exception) {
        }
    }
}
