package io.nekohasekai.sfa.bridge

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.util.UUID

/**
 * Per-core routing rules (Settings › Routing rules): one independent rule set
 * for each core, kept in the app settings and in backups. libbox compiles the
 * set of the core that actually runs into that core's routing syntax at start
 * (`CommandServer.startOrReloadCoreServiceWithRouting`); the profile on disk
 * is never changed. The model follows the design of Lemonochka/keqdroid's
 * TrafficRoute / RoutingProfile (content, target, apps, network, ports, order);
 * the field names are the JSON libbox reads (libbox/core_routing.go).
 */
internal object CoreRouting {
    const val VERSION = 1
    val cores = listOf("sing-box", "xray", "mihomo")
    val modes = listOf("prepend", "override", "off")
    val contents = listOf("all", "match", "preset", "list")
    val targets = listOf("proxy", "direct", "block", "node")
    val finals = listOf("config") + targets
    val networks = listOf("any", "tcp", "udp")

    const val MAX_RULES = 500
    const val MAX_VALUES = 5000
    const val MAX_TEXT = 2048
    const val MAX_APPS = 1000
    const val MAX_PORT_RANGES = 28

    @Serializable
    data class Target(val type: String = "proxy", val tag: String = "")

    @Serializable
    data class Rule(
        val id: String = UUID.randomUUID().toString(),
        val enabled: Boolean = true,
        val name: String = "",
        val content: String = "match",
        val values: List<String> = emptyList(),
        val preset: String = "",
        val url: String = "",
        val target: Target = Target(),
        val apps: List<String> = emptyList(),
        val network: String = "any",
        val ports: String = "",
    )

    @Serializable
    data class CoreSet(
        val mode: String = "prepend",
        val final: Target = Target("config"),
        val rules: List<Rule> = emptyList(),
    )

    @Serializable
    data class Rules(val version: Int = VERSION, val cores: Map<String, CoreSet> = emptyMap()) {
        fun core(core: String): CoreSet = cores[core] ?: CoreSet()
        fun with(core: String, set: CoreSet): Rules = copy(cores = cores + (core to set))
    }

    /** A preset: what libbox expands it to (libbox/core_routing.go routingPresets) and the rules it adds. */
    data class Preset(val id: String, val title: String, val description: String, val rules: () -> List<Rule>)

    val presets = listOf(
        Preset("cn-direct", "Bypass mainland China", "geosite:cn, geoip:cn and private addresses go direct") {
            listOf(Rule(name = "Bypass mainland China", content = "preset", preset = "cn-direct", target = Target("direct")))
        },
        Preset("gfw-proxy", "GFW list through proxy, the rest direct", "geosite:gfw through the proxy; all other traffic direct") {
            listOf(
                Rule(name = "GFW list", content = "preset", preset = "gfw-proxy", target = Target("proxy")),
                Rule(name = "Everything else", content = "all", target = Target("direct")),
            )
        },
        Preset("ads-block", "Block ads", "geosite:category-ads-all is blocked") {
            listOf(Rule(name = "Block ads", content = "preset", preset = "ads-block", target = Target("block")))
        },
        Preset("lan-direct", "LAN direct", "Private and LAN addresses go direct") {
            listOf(Rule(name = "LAN direct", content = "preset", preset = "lan-direct", target = Target("direct")))
        },
    )
    private val presetIds = presets.map { it.id }.toSet()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val portsPattern = Regex("""^\s*\d{1,5}(\s*-\s*\d{1,5})?(\s*[, ]\s*\d{1,5}(\s*-\s*\d{1,5})?)*\s*$""")
    private val packagePattern = Regex("""^[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)*$""")

    fun decode(text: String): Rules = if (text.isBlank()) Rules() else validate(json.decodeFromString(Rules.serializer(), text))

    fun encode(rules: Rules): String = json.encodeToString(Rules.serializer(), validate(rules))

    /** The JSON libbox compiles for [core] ("" when the core has no rules). */
    fun coreJson(rules: Rules, core: String): String {
        val set = rules.cores[core] ?: return ""
        return json.encodeToString(CoreSet.serializer(), set)
    }

    fun portsValid(text: String): Boolean {
        if (text.isBlank()) return true
        if (!portsPattern.matches(text)) return false
        val ranges = text.split(',', ' ').filter { it.isNotBlank() }
        if (ranges.size > MAX_PORT_RANGES) return false
        return ranges.all { part ->
            val bounds = part.split('-').map { it.trim().toIntOrNull() ?: return false }
            bounds.all { it in 1..65535 } && (bounds.size == 1 || bounds[0] <= bounds[1])
        }
    }

    fun packageValid(name: String) = name.length < 256 && packagePattern.matches(name)

    fun urlValid(url: String) = url.length <= MAX_TEXT && (url.startsWith("https://") || url.startsWith("http://")) && url.length > 8

    /** Checks the limits and choices; returns the same rules or throws IllegalArgumentException. */
    fun validate(rules: Rules): Rules {
        require(rules.version == VERSION) { "Unsupported routing rules version ${rules.version}" }
        for ((core, set) in rules.cores) {
            require(core in cores) { "Unknown core in routing rules: $core" }
            require(set.mode in modes) { "Unknown routing mode: ${set.mode}" }
            require(set.final.type in finals) { "Unknown fallback: ${set.final.type}" }
            require(set.final.type != "node" || set.final.tag.isNotBlank()) { "Choose the fallback node" }
            require(set.final.tag.length <= 256)
            require(set.rules.size <= MAX_RULES) { "At most $MAX_RULES rules per core" }
            require(set.rules.map { it.id }.toSet().size == set.rules.size) { "Duplicate routing rule id" }
            set.rules.forEach(::validateRule)
        }
        return rules
    }

    fun validateRule(rule: Rule) {
        require(rule.id.isNotBlank() && rule.id.length <= 64) { "Invalid routing rule id" }
        require(rule.name.length <= 256)
        require(rule.content in contents) { "Unknown rule content: ${rule.content}" }
        require(rule.target.type in targets) { "Unknown rule target: ${rule.target.type}" }
        require(rule.target.type != "node" || rule.target.tag.isNotBlank()) { "Choose a node or group" }
        require(rule.target.tag.length <= 256)
        require(rule.values.size <= MAX_VALUES && rule.values.all { it.length <= MAX_TEXT }) { "Too many or too long entries" }
        // An empty "match" with apps, a network or ports matches by those alone (an app-only rule).
        require(rule.content != "match" || rule.values.any { it.isNotBlank() } || hasConditions(rule)) {
            "Add a domain, IP or geo entry, or choose apps, a network or ports"
        }
        require(rule.content != "preset" || rule.preset in presetIds) { "Unknown preset: ${rule.preset}" }
        require(rule.content != "list" || urlValid(rule.url)) { "Enter an http(s) rule list URL" }
        require(rule.apps.size <= MAX_APPS && rule.apps.all(::packageValid)) { "Invalid package name" }
        require(rule.network in networks) { "Unknown network: ${rule.network}" }
        require(portsValid(rule.ports)) { "Ports are 1–65535, as 443 or 8000-9000, separated by commas" }
    }

    /** Apps, a network or ports: what a rule can match by without destination entries. */
    fun hasConditions(rule: Rule): Boolean =
        rule.apps.any { it.isNotBlank() } || (rule.network != "any" && rule.network.isNotBlank()) || rule.ports.isNotBlank()

    /**
     * Splits what the user typed into entries: one per line, or several on a
     * line separated by commas or spaces. A `regexp:` line is one entry as it
     * is, since a pattern may contain commas and spaces (`a{1,3}`).
     */
    fun splitValues(text: String): List<String> = text.lines().flatMap { line ->
        val trimmed = line.trim()
        if (trimmed.startsWith("regexp:", ignoreCase = true)) listOf(trimmed)
        else trimmed.split(',', ' ', '\t')
    }.map { it.trim() }.filter { it.isNotEmpty() }

    /** Copies another core's rules (new ids, so both can be edited independently). */
    fun copyFrom(rules: Rules, from: String, to: String): Rules {
        require(from in cores && to in cores && from != to)
        val source = rules.core(from)
        return rules.with(to, source.copy(rules = source.rules.map { it.copy(id = UUID.randomUUID().toString()) }))
    }

    fun move(rules: List<Rule>, from: Int, to: Int): List<Rule> {
        if (from !in rules.indices || to !in rules.indices || from == to) return rules
        val list = rules.toMutableList()
        list.add(to, list.removeAt(from))
        return list
    }

    // ---- wire form (bridge events and backups): maps and lists only ----

    fun toWire(rules: Rules): Map<String, Any?> = fromElement(json.encodeToJsonElement(Rules.serializer(), rules)) as Map<String, Any?>

    fun fromWire(value: Any?): Rules {
        require(value is Map<*, *>) { "Invalid routing rules" }
        return validate(json.decodeFromJsonElement(Rules.serializer(), toElement(value)))
    }

    fun validWire(value: Any?): Boolean = runCatching { fromWire(value) }.isSuccess

    private fun toElement(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to toElement(it.value) })
        is List<*> -> JsonArray(value.map(::toElement))
        is Boolean -> JsonPrimitive(value)
        is Int -> JsonPrimitive(value)
        is Long -> JsonPrimitive(value)
        is Number -> {
            val number = value.toDouble()
            if (number == Math.floor(number) && !number.isInfinite()) JsonPrimitive(number.toLong()) else JsonPrimitive(number)
        }
        is String -> JsonPrimitive(value)
        else -> throw IllegalArgumentException("Invalid routing rules value")
    }

    private fun fromElement(element: JsonElement): Any? = when (element) {
        is JsonNull -> null
        is JsonObject -> element.mapValues { fromElement(it.value) }
        is JsonArray -> element.map(::fromElement)
        is JsonPrimitive -> when {
            element.isString -> element.content
            element.booleanOrNull != null -> element.booleanOrNull
            element.longOrNull != null -> element.longOrNull!!.toInt()
            else -> element.doubleOrNull ?: element.contentOrNull
        }
    }
}
