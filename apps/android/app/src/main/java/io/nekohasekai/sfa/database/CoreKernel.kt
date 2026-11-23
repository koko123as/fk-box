package io.nekohasekai.sfa.database

import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.utils.HTTPClient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The kernel layer as `fkbox-client-core` sees it (`content::Kernel` in
 * crates/client-core/src/content.rs): the subscription fetch, the split of a
 * JSON array of configs, an element's remarks, and conversion plus
 * validation by each core's own parser, all libbox (Go). Rust calls [call]
 * back over JNI on the thread of a [CoreStore.callKernel] call
 * (apps/android/native/src/store.rs); it never throws, an error is returned
 * as `{"error": message}`.
 */
internal object CoreKernel {
    fun call(method: String, args: String): String = try {
        val input = CoreStore.json.parseToJsonElement(args).jsonObject
        JsonObject(mapOf("ok" to dispatch(method, input))).toString()
    } catch (error: Throwable) {
        JsonObject(mapOf("error" to JsonPrimitive(error.message ?: error.toString()))).toString()
    }

    private fun JsonObject.text(key: String): String = this[key]?.jsonPrimitive?.content ?: error("Missing argument: $key")

    private fun dispatch(method: String, args: JsonObject): JsonElement = when (method) {
        "fetch" -> {
            // gomobile lowercases the initial uppercase run in UISubscriptionGet.
            val response = Libbox.uiSubscriptionGet(args.text("url"), HTTPClient.userAgentFor(args.text("core")))
            buildJsonObject {
                put("content", response.content)
                put("userInfo", response.userInfo)
                put("updateInterval", response.updateInterval)
                put("webPage", response.webPage)
            }
        }
        "split" -> ProfileContent.splitArray(args.text("content"))?.let { elements ->
            JsonArray(elements.map { buildJsonObject { put("remarks", it.remarks); put("content", it.content); put("core", it.core) } })
        } ?: JsonNull
        "remarks" -> JsonPrimitive(Libbox.coreConfigRemarks(args.text("content")))
        "prepare" -> ProfileContent.prepare(args.text("core"), args.text("content")).let {
            buildJsonObject { put("core", it.core); put("content", it.content) }
        }
        else -> error("Unknown kernel call: $method")
    }
}
