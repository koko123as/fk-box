package io.nekohasekai.sfa.bridge

import org.json.JSONArray
import org.json.JSONObject

/** An org.json object as the bridge carries it (maps, lists, null for JSON null). */
internal fun jsonObject(value: JSONObject): Map<String, Any?> = value.keys().asSequence().associateWith { jsonValue(value.get(it)) }

private fun jsonValue(value: Any?): Any? = when (value) {
    JSONObject.NULL -> null
    is JSONObject -> jsonObject(value)
    is JSONArray -> (0 until value.length()).map { jsonValue(value.get(it)) }
    else -> value
}
