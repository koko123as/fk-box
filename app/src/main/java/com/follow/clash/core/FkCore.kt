// FK-box's answer to FlClash's Core protocol (lib/core/method.dart, interface.dart).
//
// FlClash on Android forwards each CoreMethodCall as JSON over
// com.follow.clash/core to its in-process Go core (core/protocol.go). FK-box has
// no FlClash core: libbox runs sing-box, Xray or mihomo inside BoxService. This
// dispatcher answers the same wire methods with the same JSON shapes from the
// FK-box backend, so lib/core/interface.dart and every provider above it stay
// FlClash's code unchanged. A method FK-box cannot serve answers
// {"error":{"code":"unsupported"}}, which the Dart side reports like any Core error.
package com.follow.clash.core

import android.os.Debug
import com.follow.clash.common.GlobalState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class CoreMethodError(val code: String, override val message: String) : Exception(message)

object FkCore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var eventListener: ((ByteArray) -> Unit)? = null

    @Volatile
    var homeDir: String? = null
        private set

    fun setEventListener(listener: ((ByteArray) -> Unit)?) {
        eventListener = listener
    }

    /** Sends Core events (lib/core/event.dart CoreEvent list) to Dart. */
    fun sendEvents(events: JSONArray) {
        val listener = eventListener ?: return
        val call = JSONObject()
            .put("method", "message")
            .put("arguments", events)
        listener(call.toString().toByteArray(Charsets.UTF_8))
    }

    fun invokeMethod(data: String, reply: (ByteArray) -> Unit) {
        scope.launch {
            val response = JSONObject()
            var id: Any? = null
            try {
                val call = JSONObject(data)
                id = call.opt("id")
                val method = call.getString("method")
                val arguments = call.opt("arguments").takeUnless { it == JSONObject.NULL }
                response.put("result", dispatch(method, arguments) ?: JSONObject.NULL)
            } catch (error: CoreMethodError) {
                response.put("error", errorOf(error.code, error.message))
            } catch (error: Exception) {
                GlobalState.log("Core method failed: $error")
                response.put("error", errorOf("core_error", error.message ?: error.toString()))
            }
            if (id != null) response.put("id", id)
            reply(response.toString().toByteArray(Charsets.UTF_8))
        }
    }

    private fun errorOf(code: String, message: String) =
        JSONObject().put("code", code).put("message", message).put("details", JSONObject.NULL)

    private suspend fun dispatch(method: String, arguments: Any?): Any? {
        val args = arguments as? JSONObject ?: JSONObject()
        return when (method) {
            "core.init" -> {
                homeDir = args.optString("home-dir").takeIf { it.isNotEmpty() }
                null
            }
            "core.initialized" -> true
            "core.gc" -> {
                System.gc()
                null
            }
            "core.memory" -> memoryStats()
            "core.crash" -> null
            // The Android service owns the listeners (FlClash's CoreLib does the same).
            "listener.start", "listener.stop" -> null
            "log.start", "log.stop" -> null
            "network.changed", "dns.clearCache", "traffic.reset", "connection.reset" -> null
            else -> throw CoreMethodError("unsupported", "FK-box does not provide $method yet")
        }
    }

    private fun memoryStats(): JSONObject {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        return JSONObject().put("rss", info.totalPss.toLong() * 1024)
    }
}
