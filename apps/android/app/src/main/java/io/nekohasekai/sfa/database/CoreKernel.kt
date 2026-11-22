package io.nekohasekai.sfa.database

import io.github.sagernet.libghostty.extras.GhosttyFontStore
import io.github.sagernet.libghostty.extras.GhosttyThemeStore
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.utils.HTTPClient
import io.nekohasekai.sfa.utils.SecretBox
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The kernel layer as `fkbox-client-core` sees it (`content::Kernel` in
 * crates/client-core/src/content.rs): the subscription fetch, the split of a
 * JSON array of configs, an element's remarks, and conversion plus
 * validation by each core's own parser, all libbox (Go); and what backups,
 * WebDAV, the update check and the terminal settings need from Android
 * (phase 2d): HTTP requests through libbox (`http.get`, `webdav.request`),
 * Keystore encryption of the WebDAV password (`secret.*`, [SecretBox]) and
 * the Ghostty theme and font checks (`terminal.*`). Rust calls [call]
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
        "validate" -> {
            // The core the content resolves to, checked by that core's parser.
            val content = args.text("content")
            val core = Libbox.resolveProfileCore(args.text("core"), content)
            ProfileContent.validate(core, content)
            JsonPrimitive(core)
        }
        "capture.validate" -> JsonPrimitive(Libbox.validateUICaptureConfig(args.text("content")))
        "http.get" -> {
            // gomobile lowercases the initial uppercase run in UIHTTPGet.
            val response = Libbox.uihttpGet(args.text("url"))
            buildJsonObject { put("status", response.status); put("content", response.content.toString(Charsets.UTF_8)) }
        }
        "webdav.request" -> {
            val response = Libbox.uiWebDAVRequest(args.text("method"), args.text("url"), args.text("user"), args.text("password"),
                args.text("content").toByteArray(Charsets.UTF_8))
            buildJsonObject { put("status", response.status); put("content", response.content.toString(Charsets.UTF_8)) }
        }
        "secret.encrypt" -> JsonPrimitive(SecretBox.encrypt(args.text("value")))
        "secret.decrypt" -> JsonPrimitive(SecretBox.decrypt(args.text("value")))
        // Called on the store call's own (background) thread; runBlocking also
        // covers the library's suspending variants.
        "terminal.themes" -> runBlocking {
            val dark = args["dark"]?.jsonPrimitive?.booleanOrNull == true
            JsonArray(GhosttyThemeStore.listThemes(Application.application, dark).map { JsonPrimitive(it) })
        }
        "terminal.fonts" -> runBlocking { JsonArray(GhosttyFontStore.listImportedFonts(Application.application).map { JsonPrimitive(it.path) }) }
        "terminal.fontValid" -> runBlocking { JsonPrimitive(GhosttyFontStore.loadTypeface(args.text("path")) != null) }
        else -> error("Unknown kernel call: $method")
    }
}
