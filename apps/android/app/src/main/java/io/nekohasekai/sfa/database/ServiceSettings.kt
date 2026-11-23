package io.nekohasekai.sfa.database

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The service settings as the settings pages and backups see them, kept by
 * `fkbox-client-core` (crates/client-core/src/config/service_settings.rs, with the
 * routing rule model in routing.rs and the geo policy in geo.rs): the
 * snapshot, validation, the write and the routing rule set a core compiles.
 *
 * What a change does outside the store stays here in Kotlin and is driven
 * by [plan]: switching the package query mode, scanning the apps for the
 * managed list, re-reading the setup options, rescheduling geo updates,
 * restarting the service. Everything the store writes is adopted into
 * [Settings]' cache ([adopt]) so the service reads the new values at once.
 */
internal object ServiceSettings {
    data class Plan(val queryMode: String?, val scan: Boolean, val reloadSetup: Boolean, val rescheduleGeo: Boolean)

    /** Every editable setting by its wire name (no platform state such as the battery exemption). */
    fun snapshot(): Map<String, Any?> = plainMap(CoreStore.call("serviceSettings.snapshot"))

    /** The settings a backup carries (the snapshot without the scanned managed list). */
    fun backup(): Map<String, Any?> = plainMap(CoreStore.call("serviceSettings.backup"))

    /** Checks each value on its own; throws with the first invalid setting. */
    fun validate(values: Map<String, Any?>) {
        CoreStore.call("serviceSettings.validate") { put("values", element(values)) }
    }

    /** Checks [values] against each other and the stored settings and says what to do around the write. */
    fun plan(values: Map<String, Any?>): Plan = decodePlan(CoreStore.call("serviceSettings.plan") { put("values", element(values)) })

    /** Writes [values] in one step (all or nothing) and adopts them into the settings cache. */
    fun patch(values: Map<String, Any?>): Plan {
        val result = CoreStore.call("serviceSettings.patch") { put("values", element(values)) }.jsonObject
        adopt(result.getValue("written"))
        return decodePlan(result.getValue("plan"))
    }

    /**
     * Entries the client store already holds (`[{key, t, v}]`, written by
     * client-core: here, by the UI, or by the geo address editor), put in
     * the settings cache without writing them again. Returns their keys.
     */
    fun adopt(written: JsonElement): List<String> = written.jsonArray.map { item ->
        val entry = item.jsonObject
        val key = entry.getValue("key").jsonPrimitive.content
        val value = entry["v"]?.takeIf { it !is JsonNull }
        Settings.dataStore.adopt(key, entry.getValue("t").jsonPrimitive.int, value)
        key
    }

    /** The wire form of [value] as the model reads it (defaults filled in); throws when invalid. */
    fun normalizeRouting(value: Map<String, Any?>): Map<String, Any?> =
        plainMap(CoreStore.call("routing.normalize") { put("value", element(value)) })

    /**
     * The rule set libbox compiles for [core]: of [draft] when given (the
     * preview of unsaved rules), else the saved one. "" when the core has no
     * rules; throws when the rules cannot be read.
     */
    fun routingCoreJson(core: String, draft: Map<String, Any?>? = null): String = CoreStore.call("routing.coreJson") {
        put("core", core)
        if (draft != null) put("value", element(draft))
    }.jsonPrimitive.content

    private fun decodePlan(element: JsonElement): Plan {
        val plan = element.jsonObject
        return Plan(
            queryMode = plan["queryMode"]?.jsonPrimitive?.contentOrNull,
            scan = plan["scan"]?.jsonPrimitive?.booleanOrNull == true,
            reloadSetup = plan["reloadSetup"]?.jsonPrimitive?.booleanOrNull == true,
            rescheduleGeo = plan["rescheduleGeo"]?.jsonPrimitive?.booleanOrNull == true,
        )
    }

    /** A bridge value (maps, lists, strings, numbers, booleans) as JSON. */
    fun element(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to element(it.value) })
        is Collection<*> -> JsonArray(value.map(::element))
        is Array<*> -> JsonArray(value.map(::element))
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is String -> JsonPrimitive(value)
        else -> throw IllegalArgumentException("Invalid setting value")
    }

    @Suppress("UNCHECKED_CAST")
    private fun plainMap(element: JsonElement): Map<String, Any?> = plain(element) as Map<String, Any?>

    /** JSON as the bridge carries it: maps, lists, strings, booleans, Ints (Longs when larger) and Doubles. */
    fun plain(element: JsonElement): Any? = when (element) {
        is JsonNull -> null
        is JsonObject -> element.mapValues { plain(it.value) }
        is JsonArray -> element.map(::plain)
        is JsonPrimitive -> when {
            element.isString -> element.content
            element.booleanOrNull != null -> element.booleanOrNull
            element.longOrNull != null -> element.longOrNull!!.let { if (it >= Int.MIN_VALUE && it <= Int.MAX_VALUE) it.toInt() else it }
            else -> element.doubleOrNull ?: element.contentOrNull
        }
    }
}
