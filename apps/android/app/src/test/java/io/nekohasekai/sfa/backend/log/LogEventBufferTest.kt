package io.nekohasekai.sfa.backend.log

import org.junit.Assert.*
import org.junit.Test

class LogEventBufferTest {
    private fun line(message: String) = mapOf("level" to 4, "message" to message)

    @Test fun drainsOnlyWhatArrivedSinceTheLastDrain() {
        val buffer = LogEventBuffer()
        assertNull(buffer.drain(force = false))
        buffer.add(listOf(line("a"), line("b")))
        val first = buffer.drain(force = false)!!
        assertEquals(false, first["reset"])
        assertFalse(first.containsKey("defaultLogLevel"))
        assertEquals(listOf("a", "b"), (first["entries"] as List<*>).map { (it as Map<*, *>)["message"] })
        assertNull(buffer.drain(force = false))
        assertEquals(emptyList<Any>(), buffer.drain(force = true)!!["entries"])
    }

    @Test fun clearDropsWaitingLinesAndMarksReset() {
        val buffer = LogEventBuffer()
        buffer.add(listOf(line("old")))
        buffer.reset()
        buffer.add(listOf(line("new")))
        val batch = buffer.drain(force = false)!!
        assertEquals(true, batch["reset"])
        assertEquals(listOf("new"), (batch["entries"] as List<*>).map { (it as Map<*, *>)["message"] })
        assertEquals(false, buffer.drain(force = true)!!["reset"])
    }

    @Test fun levelIsSentOnceAfterItChanges() {
        val buffer = LogEventBuffer()
        buffer.setLevel(3)
        assertEquals(3, buffer.drain(force = false)!!["defaultLogLevel"])
        buffer.setLevel(3)
        assertNull(buffer.drain(force = false))
        buffer.setLevel(5)
        assertEquals(5, buffer.drain(force = false)!!["defaultLogLevel"])
    }

    @Test fun waitingLinesStayBounded() {
        val buffer = LogEventBuffer(limit = 3)
        buffer.add(listOf(line("old")))
        buffer.add((1..10).map { line("$it") })
        val entries = buffer.drain(force = false)!!["entries"] as List<*>
        assertEquals(listOf("8", "9", "10"), entries.map { (it as Map<*, *>)["message"] })
    }
}
