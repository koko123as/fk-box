package io.nekohasekai.sfa.bg

import io.nekohasekai.sfa.database.CoreStore
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The service host's calls into mobile-ffi (Clash-of-Rust's `CoreBridge`):
 * the decisions in `service_state.rs`, `vpn_routing.rs` and
 * `network_recovery.rs`, answered as JSON objects.
 */
internal object CoreBridge {
    fun call(method: String, build: JsonObjectBuilder.() -> Unit = {}): JsonObject = CoreStore.call(method, build).jsonObject

    fun strings(values: Iterable<String>) = JsonArray(values.map { JsonPrimitive(it) })
}

internal fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

internal fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.intOrNull!!

/** The number at [key], or null when it is missing or null. */
internal fun JsonObject.intOrNull(key: String): Int? = (get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

internal fun JsonObject.longOrNull(key: String): Long? = (get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

internal fun JsonObject.bool(key: String): Boolean = (get(key) as? JsonPrimitive)?.booleanOrNull == true

/** The string at [key], or null when it is missing or null. */
internal fun JsonObject.textOrNull(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.items(key: String): List<JsonObject> = (get(key) as? JsonArray).orEmpty().map { it.jsonObject }

internal fun JsonObject.texts(key: String): List<String> = (get(key) as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
