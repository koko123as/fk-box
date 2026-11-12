package io.nekohasekai.sfa.bridge

import org.json.JSONArray
import org.json.JSONObject

/**
 * JNI entry points of the Rust + Slint UI (ui-slint, libfkui.so). Rust calls
 * [BoxActivity.slintCall]; results and BoxBridge events come back here as
 * `{"v": value}` JSON.
 */
object SlintHost {
    @JvmStatic external fun nativeEvent(payload: String)

    @JvmStatic external fun nativeReply(id: Long, ok: Boolean, payload: String)

    fun wire(value: Any?): String = JSONObject().put("v", json(value)).toString()

    private fun json(value: Any?): Any = when (value) {
        null, Unit -> JSONObject.NULL
        is Map<*, *> -> JSONObject().also { obj -> value.forEach { (k, v) -> obj.put(k.toString(), json(v)) } }
        is Iterable<*> -> JSONArray().also { array -> value.forEach { array.put(json(it)) } }
        is Array<*> -> JSONArray().also { array -> value.forEach { array.put(json(it)) } }
        is Double -> if (value.isFinite()) value else JSONObject.NULL
        is Float -> if (value.isFinite()) value.toDouble() else JSONObject.NULL
        is Number, is Boolean, is String -> value
        is Enum<*> -> value.name
        else -> value.toString()
    }
}

/** Maps the Slint UI's method names onto [BoxBridge] commands. */
internal suspend fun BoxBridge.slintDispatch(method: String, args: JSONObject): Any? = when (method) {
    "ready" -> ready()
    "startService" -> startService()
    "stopService" -> stopService()
    "reloadService" -> reloadService(args.optBoolean("restart"))
    "selectProfile" -> selectProfile(args.getLong("id"))
    "updateProfile" -> updateProfile(args.getLong("id"))
    "deleteProfile" -> deleteProfile(args.getLong("id"))
    "saveProfile" -> saveProfile(jsonObject(args.getJSONObject("profile")))
    "selectOutbound" -> selectOutbound(args.getString("group"), args.getString("tag"))
    "urlTest" -> urlTest(args.getString("tag"))
    "setMode" -> setMode(args.getString("mode"))
    "setLogsVisible" -> setLogsVisible(args.getBoolean("visible"))
    "clearLogs" -> clearLogs()
    "openUrl" -> openUrl(args.optString("url"))
    else -> error("Unknown method: $method")
}
