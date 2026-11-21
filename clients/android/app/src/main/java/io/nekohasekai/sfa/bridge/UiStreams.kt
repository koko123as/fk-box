package io.nekohasekai.sfa.bridge

// The Connections page's stream ("connections", "connectionStats") and the
// dashboard Memory card's stream ("memory"). They were Go in the fk-box libbox
// fork (core_connection_stream.go, core_dashboard_stream.go); the payloads are
// unchanged except that memory now comes from the kernel's own process.

import io.nekohasekai.libbox.Connection
import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.Libbox
import java.io.File
import kotlin.concurrent.thread

/**
 * The connection store, a port of libbox Connections.ApplyEvents: NEW stores
 * the snapshot, UPDATE adds the row's increments, CLOSED marks the row with
 * zero rates; closed rows are evicted five minutes after they closed; DNS rows
 * are counted, not listed. Rows keep their insertion order.
 *
 * While some owner is in the foreground one worker sends the current state at
 * once, then each changed state at most once per second. Showing a kind sends
 * it at once. An unchanged row is encoded once and its text reused.
 */
internal object UiConnectionStream {
    private const val INTERVAL_MS = 1000L
    private const val CLOSED_MAX_AGE_MS = 5 * 60 * 1000L

    private class Entry(
        val row: LinkedHashMap<String, Any?>,
        val outboundType: String,
    ) {
        var encoded: String? = null

        val closedAt: Long? get() = row["closedAt"] as Long?

        val isDNS: Boolean
            get() = outboundType == "dns" || (row["protocol"] as String).equals("dns", ignoreCase = true) ||
                (row["destination"] as String).endsWith(":53")

        fun copy(): Entry = Entry(LinkedHashMap(row), outboundType)
    }

    private fun entryFrom(c: Connection): Entry {
        val process = c.processInfo?.let { p ->
            linkedMapOf<String, Any?>("id" to p.processID, "path" to p.processPath, "user" to p.userName, "packages" to strings(p.packageNames()))
        }
        val row = linkedMapOf<String, Any?>(
            "id" to c.id, "inbound" to c.inbound, "network" to c.network, "source" to c.source, "destination" to c.destination,
            "domain" to c.domain, "displayDestination" to c.displayDestination(), "protocol" to c.protocol, "user" to c.user,
            "createdAt" to c.createdAt, "closedAt" to (if (c.closedAt > 0) c.closedAt else null),
            "upload" to c.uplink, "download" to c.downlink, "uploadTotal" to c.uplinkTotal, "downloadTotal" to c.downlinkTotal,
            "rule" to c.rule, "outbound" to c.outbound, "chain" to strings(c.chain()), "process" to process,
        )
        return Entry(row, c.outboundType)
    }

    private fun strings(iterator: io.nekohasekai.libbox.StringIterator?): List<String> = mutableListOf<String>().also { list ->
        if (iterator != null) while (iterator.hasNext()) list.add(iterator.next())
    }

    private val control = Any()
    private val lock = Object()
    private val entries = LinkedHashMap<String, Entry>()
    private var version = 0L
    private var rows = 0
    private var stats = 0
    private var foreground = 0
    private var worker: Thread? = null
    private var stopped = true

    private fun changedLocked() {
        version++
        lock.notifyAll()
    }

    private fun put(id: String, entry: Entry) {
        // A row keeps its first position (Go kept the insertion order list).
        entries[id] = entry
    }

    private fun applyEvent(event: io.nekohasekai.libbox.ConnectionEvent): Boolean {
        val id = event.id
        when (event.type) {
            Libbox.ConnectionEventNew -> {
                val connection = event.connection ?: return false
                put(id, entryFrom(connection))
            }
            Libbox.ConnectionEventUpdate -> {
                val current = entries[id] ?: return false
                if (event.uplinkDelta == 0L && event.downlinkDelta == 0L && current.row["upload"] == 0L && current.row["download"] == 0L) return false
                val next = current.copy()
                next.row["upload"] = event.uplinkDelta
                next.row["download"] = event.downlinkDelta
                next.row["uploadTotal"] = (next.row["uploadTotal"] as Long) + event.uplinkDelta
                next.row["downloadTotal"] = (next.row["downloadTotal"] as Long) + event.downlinkDelta
                entries[id] = next
            }
            Libbox.ConnectionEventClosed -> {
                val next = event.connection?.let(::entryFrom) ?: entries[id]?.copy() ?: return false
                next.row["closedAt"] = if (event.closedAt > 0) event.closedAt else null
                next.row["upload"] = 0L
                next.row["download"] = 0L
                put(id, next)
            }
            else -> return false
        }
        return true
    }

    private fun evictLocked(now: Long): Boolean {
        val before = entries.size
        entries.values.removeAll { entry -> entry.closedAt?.let { now - it > CLOSED_MAX_AGE_MS } == true }
        return entries.size != before
    }

    /** Applies one core batch, in arrival order (the caller serializes calls). */
    fun apply(events: ConnectionEvents?) {
        events ?: return
        synchronized(lock) {
            var changed = false
            if (events.reset && entries.isNotEmpty()) {
                entries.clear()
                changed = true
            }
            val iterator = events.iterator()
            while (iterator.hasNext()) {
                val event = iterator.next() ?: continue
                if (applyEvent(event)) changed = true
            }
            if (evictLocked(System.currentTimeMillis())) changed = true
            if (changed) changedLocked()
        }
    }

    /** Empties the store (service stopped, disconnected). */
    fun clear() = synchronized(lock) {
        if (entries.isNotEmpty()) {
            entries.clear()
            changedLocked()
        }
    }

    private fun rowsPayload(): String {
        val parts = synchronized(lock) {
            entries.values.filter { it.outboundType != "dns" }.map { entry ->
                entry.encoded ?: SlintHost.jsonText(entry.row).also { entry.encoded = it }
            }
        }
        return parts.joinToString(",", "[", "]")
    }

    // DNS is the number of DNS rows the store holds: no kernel reports a query
    // count (docs/kernel-isolation.md §10).
    private fun statsPayload(): String {
        var active = 0
        var recent = 0
        var dns = 0L
        synchronized(lock) {
            for (entry in entries.values) {
                if (entry.isDNS) dns++
                if (entry.outboundType == "dns") continue
                if (entry.closedAt == null) active++ else recent++
            }
        }
        return SlintHost.jsonText(linkedMapOf("active" to active, "recent" to recent, "dns" to dns))
    }

    private fun send(rows: Boolean, stats: Boolean): Boolean {
        if (rows) UiEvents.emit("connections", rowsPayload())
        if (stats) UiEvents.emit("connectionStats", statsPayload())
        return rows || stats
    }

    private fun run() {
        var first = true
        var sent = 0L
        while (true) {
            val (current, showRows, showStats) = synchronized(lock) {
                if (stopped) return
                Triple(version, rows > 0, stats > 0)
            }
            if (first || current != sent) {
                first = false
                sent = current
                if (send(showRows, showStats)) {
                    synchronized(lock) {
                        if (stopped) return
                        lock.wait(INTERVAL_MS)
                        if (stopped) return
                    }
                    continue
                }
            }
            synchronized(lock) {
                while (!stopped && version == sent) lock.wait()
                if (stopped) return
            }
        }
    }

    /**
     * Counts an owner entering (true) or leaving the foreground; while any
     * owner is in it the worker runs. Stopping waits until it has exited, so
     * nothing is sent after.
     */
    fun setForeground(entering: Boolean) = synchronized(control) {
        var stopping: Thread? = null
        synchronized(lock) {
            if (entering) foreground++ else if (foreground > 0) foreground--
            val active = foreground > 0
            if (active == !stopped) return@synchronized
            if (active) {
                stopped = false
                worker = thread(name = "connection-stream", isDaemon = true) { run() }
                return@synchronized
            }
            stopped = true
            stopping = worker
            worker = null
            lock.notifyAll()
        }
        stopping?.join()
    }

    /** Counts an owner showing or hiding a kind; showing sends it at once. */
    fun show(rowsKind: Boolean, visible: Boolean) = synchronized(control) {
        synchronized(lock) {
            if (rowsKind) {
                rows = if (visible) rows + 1 else (rows - 1).coerceAtLeast(0)
            } else {
                stats = if (visible) stats + 1 else (stats - 1).coerceAtLeast(0)
            }
        }
        if (visible) send(rowsKind, !rowsKind)
    }

    /** Sends a shown kind again at once. */
    fun resend(rowsKind: Boolean) = synchronized(control) {
        send(rowsKind, !rowsKind)
    }
}

/**
 * The dashboard Memory card. While some owner has the card subscribed, the app
 * in the foreground and the screen on, one worker samples at once and then
 * every two seconds. The core share is the memory the kernel's status
 * reports: Xray and mihomo run as fk-core's children, so it adds to this
 * process's resident set; sing-box runs in this process, so it is part of it.
 */
internal object UiDashboardMemory {
    private const val INTERVAL_MS = 2000L

    private val control = Any()
    private val lock = Object()
    private val owners = HashMap<Int, Boolean>()
    private var remote = false
    private var separate = false
    private var coreMemory = 0L
    private var running = false
    private var worker: Thread? = null
    private var stopped = true

    private fun processRSS(): Long = runCatching {
        File("/proc/self/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("VmRSS:") }?.removePrefix("VmRSS:")?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.toLong()?.times(1024)
        } ?: 0L
    }.getOrDefault(0L)

    private fun sample(): String {
        val isRemote: Boolean
        val isSeparate: Boolean
        val core: Long
        val isRunning: Boolean
        synchronized(lock) {
            isRemote = remote
            isSeparate = separate
            core = coreMemory
            isRunning = running
        }
        if (isRemote) return SlintHost.jsonText(linkedMapOf("total" to core, "core" to core, "remote" to true))
        val rss = processRSS()
        val kernel = if (isRunning) core else 0L
        val total = if (isSeparate) rss + kernel else maxOf(rss, kernel)
        return SlintHost.jsonText(linkedMapOf(
            "total" to total, "rss" to rss, "app" to (total - kernel).coerceAtLeast(0L), "core" to kernel,
            "remote" to false, "running" to isRunning,
        ))
    }

    private fun run() {
        while (true) {
            val payload = sample()
            synchronized(lock) {
                // A hide that raced the sample wins: nothing is sent after it.
                if (stopped) return
                UiEvents.emit("memory", payload)
                lock.wait(INTERVAL_MS)
                if (stopped) return
            }
        }
    }

    /** One owner's signals; all false forgets the owner. Turning the stream off waits for its worker. */
    fun setSignals(owner: Int, subscribed: Boolean, foreground: Boolean, screenOn: Boolean) = synchronized(control) {
        var stopping: Thread? = null
        synchronized(lock) {
            if (subscribed || foreground || screenOn) owners[owner] = subscribed && foreground && screenOn else owners.remove(owner)
            val active = owners.values.any { it }
            if (active == !stopped) return@synchronized
            if (active) {
                stopped = false
                worker = thread(name = "dashboard-memory", isDaemon = true) { run() }
                return@synchronized
            }
            stopped = true
            stopping = worker
            worker = null
            lock.notifyAll()
        }
        stopping?.join()
    }

    /**
     * Where the numbers come from: a remote session, or the local kernel's
     * status memory, [isSeparate] when the kernel runs in another process.
     */
    fun setSource(isRemote: Boolean, isSeparate: Boolean, memory: Long, isRunning: Boolean) = synchronized(lock) {
        remote = isRemote
        separate = isSeparate
        coreMemory = memory
        running = isRunning
    }
}
