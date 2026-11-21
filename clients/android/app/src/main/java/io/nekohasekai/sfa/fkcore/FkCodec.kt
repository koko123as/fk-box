package io.nekohasekai.sfa.fkcore

import android.util.Base64
import io.nekohasekai.sfa.bridge.FkCoreClient
import org.json.JSONObject

/** Decodes fk-core results (Go's encoding/json) into the types FkLib.kt returns. */
internal object FkCodec {
    fun string(raw: Any?): String = when (val v = unwrap(raw)) {
        null -> ""
        else -> v.toString()
    }

    fun bool(raw: Any?): Boolean = when (val v = unwrap(raw)) {
        is Boolean -> v
        is String -> v.toBoolean()
        else -> false
    }

    fun int(raw: Any?): Int = (unwrap(raw) as? Number)?.toInt() ?: 0

    fun long(raw: Any?): Long = (unwrap(raw) as? Number)?.toLong() ?: 0L

    fun double(raw: Any?): Double = (unwrap(raw) as? Number)?.toDouble() ?: 0.0

    fun bytes(raw: Any?): ByteArray = when (val v = unwrap(raw)) {
        is String -> Base64.decode(v, Base64.DEFAULT)
        else -> ByteArray(0)
    }

    fun obj(raw: Any?): JSONObject? = unwrap(raw) as? JSONObject

    fun handle(raw: Any?): Long? = obj(raw)?.optLong("\$handle")?.takeIf { it != 0L }

    /** [raw] is either JSON text from a call ([CallResult]) or a value inside a decoded object. */
    private fun unwrap(raw: Any?): Any? = when (raw) {
        is CallResult -> FkCoreClient.parse(raw.text)
        JSONObject.NULL -> null
        else -> raw
    }
}

/** JSON text returned by FkCoreClient.call, still to be parsed. */
@JvmInline
internal value class CallResult(val text: String)
