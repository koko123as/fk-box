package io.nekohasekai.sfa.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreRoutingTest {
    /** What org.json gives a backup reader: maps, lists, strings, booleans and Ints. */
    private fun plain(element: JsonElement): Any? = when (element) {
        is JsonNull -> null
        is JsonObject -> element.mapValues { plain(it.value) }
        is JsonArray -> element.map(::plain)
        is JsonPrimitive -> if (element.isString) element.content else element.booleanOrNull ?: element.intOrNull ?: element.content.toDouble()
    }

    private fun encodeWire(value: Any?): String = when (value) {
        null -> "null"
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { "\"${it.key}\":" + encodeWire(it.value) }
        is List<*> -> value.joinToString(",", "[", "]") { encodeWire(it) }
        is String -> JsonPrimitive(value).toString()
        else -> value.toString()
    }

    private fun sample(): CoreRouting.Rules {
        val rules = listOf(
            CoreRouting.Rule(id = "a", name = "ads", content = "preset", preset = "ads-block", target = CoreRouting.Target("block")),
            CoreRouting.Rule(id = "b", content = "match", values = listOf("example.com", "geosite:cn"), target = CoreRouting.Target("node", "HK"), network = "tcp", ports = "443, 8000-9000", apps = listOf("com.example.app")),
            CoreRouting.Rule(id = "c", enabled = false, content = "list", url = "https://lists.example.test/ads.txt", target = CoreRouting.Target("direct")),
        )
        return CoreRouting.Rules().with("sing-box", CoreRouting.CoreSet(mode = "prepend", final = CoreRouting.Target("direct"), rules = rules))
    }

    @Test
    fun encodesDecodesAndKeepsCoresIndependent() {
        val rules = sample()
        val decoded = CoreRouting.decode(CoreRouting.encode(rules))
        assertEquals(rules, decoded)
        assertEquals(CoreRouting.CoreSet(), decoded.core("xray"))
        assertEquals("", CoreRouting.coreJson(decoded, "mihomo"))
        val json = Json.parseToJsonElement(CoreRouting.coreJson(decoded, "sing-box")).jsonObject
        assertEquals("prepend", json["mode"]!!.jsonPrimitive.content)
        assertEquals("direct", json["final"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val second = (json["rules"] as JsonArray)[1].jsonObject
        assertEquals("node", second["target"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("HK", second["target"]!!.jsonObject["tag"]!!.jsonPrimitive.content)
        assertEquals("tcp", second["network"]!!.jsonPrimitive.content)
        assertEquals(true, second["enabled"]!!.jsonPrimitive.boolean)
        assertEquals(CoreRouting.Rules(), CoreRouting.decode(""))
    }

    @Test
    fun wireFormRoundTripsThroughBackups() {
        val rules = sample()
        val wire = CoreRouting.toWire(rules)
        assertEquals(rules, CoreRouting.fromWire(wire))
        // A backup stores the wire form as JSON and reads it back as maps, lists and numbers.
        val restored = plain(Json.parseToJsonElement(encodeWire(wire)))
        assertEquals(rules, CoreRouting.fromWire(restored))
        assertTrue(CoreRouting.validWire(restored))
        assertTrue(BoxSettings.validate(mapOf("routingRules" to restored)).isNotEmpty())
        assertFalse(CoreRouting.validWire("text"))
        assertThrows(IllegalArgumentException::class.java) { BoxSettings.validate(mapOf("routingRules" to mapOf("version" to 9))) }
    }

    @Test
    fun rejectsInvalidRules() {
        val valid = CoreRouting.Rule(id = "x", content = "match", values = listOf("example.com"))
        CoreRouting.validateRule(valid)
        val invalid = listOf(
            valid.copy(content = "sideways"),
            valid.copy(values = emptyList()),
            valid.copy(target = CoreRouting.Target("node", "")),
            valid.copy(target = CoreRouting.Target("teleport")),
            valid.copy(network = "icmp"),
            valid.copy(ports = "0"),
            valid.copy(ports = "70000"),
            valid.copy(ports = "9000-8000"),
            valid.copy(ports = "abc"),
            valid.copy(apps = listOf("not a package")),
            valid.copy(content = "list", url = "ftp://example.com/list"),
            valid.copy(content = "preset", preset = "unknown"),
        )
        for (rule in invalid) assertThrows(rule.toString(), IllegalArgumentException::class.java) { CoreRouting.validateRule(rule) }
        assertThrows(IllegalArgumentException::class.java) {
            CoreRouting.validate(CoreRouting.Rules().with("sing-box", CoreRouting.CoreSet(mode = "merge")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CoreRouting.validate(CoreRouting.Rules().with("sing-box", CoreRouting.CoreSet(rules = listOf(valid, valid))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CoreRouting.validate(CoreRouting.Rules(cores = mapOf("v2ray" to CoreRouting.CoreSet())))
        }
        assertTrue(CoreRouting.portsValid("443, 8000-9000"))
        assertTrue(CoreRouting.portsValid(""))
        assertFalse(CoreRouting.portsValid((1..29).joinToString(",")))
    }

    @Test
    fun splitsValues() {
        assertEquals(listOf("a.com", "b.com", "geosite:cn", "1.2.3.0/24"), CoreRouting.splitValues(" a.com\nb.com, geosite:cn\t1.2.3.0/24 \n"))
        // A regexp line stays whole: patterns may contain commas and spaces.
        assertEquals(
            listOf("a.com", "regexp:^a{1,3}\\.b, c$", "d.com"),
            CoreRouting.splitValues("a.com\n  regexp:^a{1,3}\\.b, c$ \nd.com"),
        )
    }

    @Test
    fun presetsAddTheirRules() {
        val gfw = CoreRouting.presets.first { it.id == "gfw-proxy" }.rules()
        assertEquals(listOf("preset", "all"), gfw.map { it.content })
        assertEquals(listOf("proxy", "direct"), gfw.map { it.target.type })
        assertNotEquals(gfw[0].id, gfw[1].id)
        CoreRouting.presets.forEach { preset -> preset.rules().forEach(CoreRouting::validateRule) }
    }

    @Test
    fun appOnlyRulesNeedNoDestination() {
        // Only apps (the UI saves it as "all"; an empty "match" with apps reads the same).
        CoreRouting.validateRule(CoreRouting.Rule(id = "a", content = "all", apps = listOf("com.tencent.mm"), target = CoreRouting.Target("direct")))
        CoreRouting.validateRule(CoreRouting.Rule(id = "b", content = "match", apps = listOf("com.tencent.mm"), target = CoreRouting.Target("direct")))
        CoreRouting.validateRule(CoreRouting.Rule(id = "c", content = "match", network = "udp", target = CoreRouting.Target("proxy")))
        CoreRouting.validateRule(CoreRouting.Rule(id = "d", content = "match", ports = "443", target = CoreRouting.Target("proxy")))
        // Nothing to match by at all.
        assertThrows(IllegalArgumentException::class.java) {
            CoreRouting.validateRule(CoreRouting.Rule(id = "e", content = "match", values = listOf(" "), target = CoreRouting.Target("proxy")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CoreRouting.validateRule(CoreRouting.Rule(id = "f", content = "match", apps = listOf("not a package!"), target = CoreRouting.Target("proxy")))
        }
        val rules = CoreRouting.Rules().with("mihomo", CoreRouting.CoreSet(rules = listOf(CoreRouting.Rule(id = "g", content = "all", apps = listOf("com.tencent.mm"), target = CoreRouting.Target("direct")))))
        assertEquals(rules, CoreRouting.decode(CoreRouting.encode(rules)))
    }
}
