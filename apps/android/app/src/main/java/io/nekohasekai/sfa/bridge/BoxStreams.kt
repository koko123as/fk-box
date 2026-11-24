package io.nekohasekai.sfa.bridge

import io.nekohasekai.sfa.bg.FkHost
import io.nekohasekai.sfa.database.CoreStore
import io.nekohasekai.sfa.utils.CommandSessions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.Closeable

/**
 * The kernel's state for the Rust UI, in the stream events mobile-ffi
 * `runtime.rs` reads (`stream`, `kind`, `value`). The single-core kernel
 * runs in this process, so each stream polls it while mobile-ffi
 * `sessions.rs` says the stream is wanted ([CommandSessions]):
 *
 * - status: traffic rates and totals, live connections, memory (fkhost stats), every second;
 * - groups: the brain's groups (`fkb_brain_groups`, brain-groups.md) as
 *   client-core `groups.rs` reads them, sent when their version changes;
 * - log: the kernel's log ring, new lines every second;
 * - connections: the per-connection list was the Clash API's; only the
 *   counts are left (status), so the list is sent empty once.
 *
 * Mode switching (the Clash API's modes) does not exist on this path.
 */
internal class BoxStreams(
    private val scope: CoroutineScope,
    private val emit: (String, Any?) -> Unit,
    /** Every per-second memory reading, before it is forwarded (the memory card). */
    private val onMemory: (Long) -> Unit = {},
) : Closeable {
    private fun send(stream: String, kind: String, value: Any? = null) {
        emit("stream", mapOf("stream" to stream, "kind" to kind, "value" to value))
    }

    private val jobs = ArrayList<Job>()

    private fun follow(stream: CommandSessions.Stream, name: String, run: suspend CoroutineScope.() -> Unit) {
        jobs += scope.launch {
            val follower = this
            var current: Job? = null
            CommandSessions.target(stream).collect { target ->
                current?.cancel()
                current = null
                if (target.connect) {
                    current = follower.launch(Dispatchers.IO) {
                        send(name, "connected")
                        try {
                            run()
                        } finally {
                            send(name, "disconnected")
                        }
                    }
                }
            }
        }
    }

    init {
        follow(CommandSessions.Stream.Status, "status") {
            while (isActive) {
                FkHost.stats()?.let { stats ->
                    onMemory(stats.memoryInuse)
                    send(
                        "status",
                        "status",
                        mapOf(
                            "uplink" to stats.uplinkRate, "downlink" to stats.downlinkRate, "memory" to stats.memoryInuse,
                            "uplinkTotalText" to FkHost.formatBytes(stats.uplinkBytes),
                            "downlinkTotalText" to FkHost.formatBytes(stats.downlinkBytes),
                            "connectionsIn" to (stats.connectionsTCP + stats.connectionsUDP), "trafficAvailable" to true,
                        ),
                    )
                }
                delay(1000)
            }
        }
        follow(CommandSessions.Stream.Groups, "groups") {
            var version: Long? = null
            while (isActive) {
                val text = FkHost.groups()
                if (text != null) {
                    val document = runCatching { CoreStore.json.parseToJsonElement(text).jsonObject }.getOrNull()
                    val current = document?.get("version")?.jsonPrimitive?.longOrNull
                    if (document != null && (current == null || current != version)) {
                        version = current
                        send("groups", "groups", groupsWire(document))
                    }
                }
                delay(1000)
            }
        }
        follow(CommandSessions.Stream.Connections, "connections") {
            send("connections", "events", mapOf("reset" to true, "events" to emptyList<Any>(), "dnsQueries" to null))
        }
        follow(CommandSessions.Stream.Log, "log") {
            var from = 0
            send("log", "clear")
            while (isActive) {
                val text = FkHost.logs(from)
                if (text != null) {
                    val batch = runCatching { CoreStore.json.parseToJsonElement(text).jsonObject }.getOrNull()
                    val total = batch?.get("total")?.jsonPrimitive?.intOrNull ?: 0
                    if (total < from) {
                        // The ring was cleared or a new service started: read it again.
                        from = 0
                        send("log", "clear")
                        continue
                    }
                    val lines = (batch?.get("lines") as? JsonArray).orEmpty().map { line ->
                        val item = line.jsonObject
                        mapOf("level" to (item["level"]?.jsonPrimitive?.intOrNull ?: 0), "message" to (item["message"]?.jsonPrimitive?.content ?: ""))
                    }
                    if (lines.isNotEmpty()) send("log", "lines", lines)
                    from = total
                }
                delay(1000)
            }
        }
    }

    override fun close() {
        jobs.forEach { it.cancel() }
        jobs.clear()
    }

    companion object {
        private val _defaultLogLevel = MutableStateFlow<Int?>(null)

        /** The log level the kernel last reported; for instrumented tests. */
        val defaultLogLevel: StateFlow<Int?> = _defaultLogLevel.asStateFlow()

        private fun groupType(type: String): String = when (type) {
            "select" -> "Selector"
            "url-test" -> "URLTest"
            "fallback" -> "Fallback"
            "load-balance" -> "LoadBalance"
            else -> type
        }

        private fun memberType(kind: String, name: String): String = when (kind) {
            "group" -> "Group"
            "builtin" -> if (name.startsWith("REJECT")) "Reject" else "Direct"
            else -> "Proxy"
        }

        /**
         * The brain's group document as client-core `groups.rs` reads the
         * groups (`tag`, `type`, `selectable`, `selected`, `expanded`,
         * `items`: `tag`, `type`, `delay`, `testedAt`): a member that failed
         * its test is tested with delay 0 (unreachable), an untested one has
         * neither.
         */
        internal fun groupsWire(document: JsonObject): List<Map<String, Any?>> {
            val now = System.currentTimeMillis() / 1000
            return (document["groups"] as? JsonArray).orEmpty().mapNotNull { element ->
                val group = element as? JsonObject ?: return@mapNotNull null
                if (group["hidden"]?.jsonPrimitive?.booleanOrNull == true) return@mapNotNull null
                val testedAgo = group["tested_secs_ago"]?.jsonPrimitive?.longOrNull
                val testedAt = testedAgo?.let { (now - it).coerceAtLeast(1) } ?: 0L
                val name = group["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                mapOf(
                    "tag" to name,
                    "type" to groupType(group["type"]?.jsonPrimitive?.content ?: ""),
                    "selectable" to (group["selectable"]?.jsonPrimitive?.booleanOrNull == true),
                    "selected" to ((group["now"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""),
                    "expanded" to false,
                    "items" to (group["members"] as? JsonArray).orEmpty().mapNotNull { memberElement ->
                        val member = memberElement as? JsonObject ?: return@mapNotNull null
                        val memberName = member["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                        val alive = (member["alive"] as? JsonPrimitive)?.booleanOrNull
                        val delayMs = member["delay_ms"]?.jsonPrimitive?.longOrNull ?: 0L
                        mapOf(
                            "tag" to memberName,
                            "type" to memberType(member["kind"]?.jsonPrimitive?.content ?: "", memberName),
                            "delay" to if (alive == true) delayMs.coerceAtLeast(1) else 0L,
                            "testedAt" to if (alive == null) 0L else maxOf(testedAt, 1L),
                        )
                    },
                )
            }
        }
    }
}
