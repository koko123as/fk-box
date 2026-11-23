package io.nekohasekai.sfa.bridge

import io.nekohasekai.sfa.database.CoreStore
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.json.JSONObject
import java.io.File
import java.security.KeyStore

/**
 * Private capture settings, independent of profiles and their service revisions. The file, its
 * one-time upgrade, revision check and atomic writes are client-core's
 * (crates/client-core/src/tools/capture.rs), which validates through the capture engine (libbox, via
 * [io.nekohasekai.sfa.database.CoreKernel]); only the retired Keystore key stays here.
 */
internal class CaptureSettings(private val directory: File, private val retiredKeyAlias: String? = "b_box_http_debug_ca_v1") {
    private var retiredKeyRemoved = false

    // Caller holds BoxCapture's process-wide operation mutex.
    private fun removeRetiredKey() {
        if (retiredKeyRemoved) return
        if (retiredKeyAlias != null) KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
            if (containsAlias(retiredKeyAlias)) deleteEntry(retiredKeyAlias)
        }
        retiredKeyRemoved = true
    }

    private fun wire(value: kotlinx.serialization.json.JsonElement): Map<String, Any?> {
        val result = value.jsonObject
        return mapOf("revision" to result.getValue("revision").jsonPrimitive.content,
            "config" to jsonObject(JSONObject(result.getValue("config").toString())))
    }

    fun snapshot(): Map<String, Any?> {
        removeRetiredKey()
        return wire(CoreStore.callKernel("capture.snapshot") { put("dir", directory.path) })
    }

    fun config(): String {
        removeRetiredKey()
        return CoreStore.callKernel("capture.config") { put("dir", directory.path) }.jsonPrimitive.content
    }

    fun save(expectedRevision: String, content: String): Map<String, Any?> {
        removeRetiredKey()
        return wire(CoreStore.callKernel("capture.save") {
            put("dir", directory.path)
            put("revision", expectedRevision)
            put("content", content)
        })
    }
}
