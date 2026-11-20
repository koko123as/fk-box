package io.nekohasekai.sfa.bridge

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.SystemClock
import android.util.Log
import io.nekohasekai.sfa.Application
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.BufferedReader
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** A call to fk-core failed: its error text, or the connection ended. */
class FkCoreException(message: String) : IOException(message)

/**
 * The app's side of fk-core, fk-box's host process (docs/kernel-isolation.md):
 * starts `libfkcore.so` from nativeLibraryDir, speaks its JSON-lines protocol
 * over a Unix socket in the app's private files directory, hands it the TUN
 * descriptor (SCM_RIGHTS) and answers its reverse calls.
 *
 * Only the main process uses it. Calls block the calling thread (never call
 * from the main thread); a dead fk-core is restarted on the next call, with a
 * backoff, and its hello repeated.
 */
object FkCoreClient {
    private const val TAG = "FkCore"
    private const val PROTOCOL_VERSION = 1
    private const val CALL_TIMEOUT_SECONDS = 120L

    /** Receives fk-core's UI events. */
    fun interface EventListener {
        fun onEvent(kind: String, payload: String)
    }

    /** Answers one reverse call; returns a JSON-encodable value (JSONObject, JSONArray, String, Number, Boolean, null). */
    fun interface ReverseHandler {
        fun handle(args: JSONArray): Any?
    }

    private class Connection(val process: Process, val socket: LocalSocket, val output: OutputStream) {
        @Volatile var alive = true
    }

    private val lock = Object()
    private val writeLock = Object()
    @Volatile private var connection: Connection? = null
    private val nextId = AtomicLong()
    private val pending = ConcurrentHashMap<Long, LinkedBlockingQueue<Result<String>>>()
    private val handlers = ConcurrentHashMap<String, ReverseHandler>()
    private val reverseExecutor = Executors.newCachedThreadPool { runnable -> Thread(runnable, "fk-core-reverse").apply { isDaemon = true } }
    @Volatile private var eventListener: EventListener? = null
    private val restarts = ArrayDeque<Long>()

    fun setEventListener(listener: EventListener?) {
        eventListener = listener
    }

    /** Registers the answer to fk-core's reverse call [method]. */
    fun handle(method: String, handler: ReverseHandler) {
        handlers[method] = handler
    }

    /** Calls [method] with positional [args]; returns the JSON result text ("null" when none). */
    fun call(method: String, vararg args: Any?): String = callWith(method, null, args.toList())

    /** Like [call], with [fd] attached to the request (the method receives it as its descriptor). */
    fun callWithDescriptor(method: String, fd: FileDescriptor, vararg args: Any?): String = callWith(method, fd, args.toList())

    private fun callWith(method: String, fd: FileDescriptor?, args: List<Any?>): String {
        val current = ensureConnected()
        val id = nextId.incrementAndGet()
        val queue = LinkedBlockingQueue<Result<String>>(1)
        pending[id] = queue
        try {
            val frame = JSONObject().put("id", id).put("method", method).put("args", JSONArray().apply { args.forEach { put(wrap(it)) } })
            if (fd != null) frame.put("fd", true)
            write(current, frame.toString(), fd)
            val result = queue.poll(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                ?: throw FkCoreException("$method: fk-core did not answer in ${CALL_TIMEOUT_SECONDS}s")
            return result.getOrElse { throw it }
        } finally {
            pending.remove(id)
        }
    }

    private fun write(current: Connection, line: String, fd: FileDescriptor? = null) {
        val bytes = (line + "\n").toByteArray()
        synchronized(writeLock) {
            try {
                if (fd != null) current.socket.setFileDescriptorsForSend(arrayOf(fd))
                // One write, so the descriptor arrives with the start of this line.
                current.output.write(bytes)
                current.output.flush()
            } catch (e: IOException) {
                disconnect(current, "write: ${e.message}")
                throw FkCoreException("fk-core connection lost: ${e.message}")
            } finally {
                if (fd != null) runCatching { current.socket.setFileDescriptorsForSend(null) }
            }
        }
    }

    private fun ensureConnected(): Connection {
        connection?.takeIf { it.alive }?.let { return it }
        synchronized(lock) {
            connection?.takeIf { it.alive }?.let { return it }
            throttle()
            val started = start()
            connection = started
            Thread({ readLoop(started) }, "fk-core-reader").apply { isDaemon = true }.start()
            hello(started)
            return started
        }
    }

    /** At most 5 starts in 10 minutes, the later ones 1, 2, 4, 8 s apart. */
    private fun throttle() {
        val now = SystemClock.elapsedRealtime()
        while (restarts.isNotEmpty() && now - restarts.first() > 10 * 60_000L) restarts.removeFirst()
        if (restarts.size >= 5) throw FkCoreException("fk-core keeps exiting; not restarting it again")
        if (restarts.isNotEmpty()) Thread.sleep(1000L shl (restarts.size - 1).coerceAtMost(3))
        restarts.addLast(now)
    }

    private fun start(): Connection {
        val app = Application.application
        val libDir = app.applicationInfo.nativeLibraryDir
        val home = File(app.filesDir, "fkcore").apply { mkdirs() }
        val socketFile = File(app.filesDir, "fkcore.sock")
        socketFile.delete()
        val command = listOf(
            "$libDir/libfkcore.so",
            "-socket", socketFile.path,
            "-dir", home.path,
            "-libdir", libDir,
            "-files", app.filesDir.path,
            "-working", (app.getExternalFilesDir(null) ?: app.filesDir).path,
            "-temp", app.cacheDir.path,
            // fk-core cannot ask sing-box; its subscription User-Agent names this version.
            "-singbox-version", io.nekohasekai.libbox.Libbox.version(),
        )
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        // Keep stdin open: fk-core exits when it ends, i.e. when this process dies.
        Thread({ drainOutput(process) }, "fk-core-output").apply { isDaemon = true }.start()
        val deadline = SystemClock.elapsedRealtime() + 10_000
        var lastError: Exception? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!process.isAlive) throw FkCoreException("fk-core exited at start (status ${process.exitValue()})")
            if (socketFile.exists()) {
                try {
                    val socket = LocalSocket()
                    socket.connect(LocalSocketAddress(socketFile.path, LocalSocketAddress.Namespace.FILESYSTEM))
                    return Connection(process, socket, socket.outputStream)
                } catch (e: IOException) {
                    lastError = e
                }
            }
            Thread.sleep(20)
        }
        process.destroy()
        throw FkCoreException("fk-core did not open its socket: ${lastError?.message ?: "timeout"}")
    }

    private fun hello(current: Connection) {
        val id = nextId.incrementAndGet()
        val queue = LinkedBlockingQueue<Result<String>>(1)
        pending[id] = queue
        try {
            val args = JSONObject().put("protocolVersion", PROTOCOL_VERSION).put("client", "android")
            write(current, JSONObject().put("id", id).put("method", "hello").put("args", args).toString())
            val result = queue.poll(30, TimeUnit.SECONDS) ?: throw FkCoreException("fk-core hello timed out")
            result.getOrElse { throw it }
        } finally {
            pending.remove(id)
        }
    }

    private fun drainOutput(process: Process) {
        runCatching {
            BufferedReader(InputStreamReader(process.inputStream)).useLines { lines ->
                lines.forEach { Log.i(TAG, it) }
            }
        }
    }

    private fun readLoop(current: Connection) {
        try {
            val reader = BufferedReader(InputStreamReader(current.socket.inputStream), 64 * 1024)
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) continue
                val frame = try {
                    JSONObject(line)
                } catch (e: Exception) {
                    Log.w(TAG, "invalid frame from fk-core: ${e.message}")
                    continue
                }
                when {
                    frame.has("event") -> eventListener?.onEvent(frame.getString("event"), rawText(frame.opt("payload")))
                    frame.has("call") -> reverse(current, frame)
                    frame.has("id") -> {
                        val id = frame.optLong("id", -1)
                        val result = if (frame.has("error") && !frame.isNull("error")) {
                            Result.failure(FkCoreException(frame.getString("error")))
                        } else {
                            Result.success(rawText(frame.opt("result")))
                        }
                        pending[id]?.offer(result)
                    }
                }
            }
            disconnect(current, "fk-core closed the connection")
        } catch (e: IOException) {
            disconnect(current, "read: ${e.message}")
        }
    }

    private fun reverse(current: Connection, frame: JSONObject) {
        val callId = frame.get("call")
        val method = frame.optString("method")
        val args = frame.optJSONArray("args") ?: JSONArray()
        reverseExecutor.execute {
            val reply = JSONObject().put("reply", callId)
            val handler = handlers[method]
            try {
                if (handler == null) throw IllegalStateException("$method: not answered by the app")
                reply.put("result", wrap(handler.handle(args)))
            } catch (e: Throwable) {
                reply.put("error", e.message ?: e.toString())
            }
            runCatching { write(current, reply.toString()) }
        }
    }

    private fun disconnect(current: Connection, reason: String) {
        if (!current.alive) return
        current.alive = false
        Log.w(TAG, "fk-core connection ended: $reason")
        runCatching { current.socket.close() }
        val error = FkCoreException("fk-core connection lost: $reason")
        pending.values.forEach { it.offer(Result.failure(error)) }
        synchronized(lock) {
            if (connection === current) connection = null
        }
        if (current.process.isAlive) current.process.destroy()
    }

    /** Stops fk-core (it also exits on its own when this process dies). */
    fun stop() {
        synchronized(lock) {
            connection?.let { disconnect(it, "stopped") }
            connection = null
        }
    }

    private fun rawText(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }

    /** A Kotlin value as a JSON value (ByteArray as base64, as Go's encoding/json expects). */
    fun wrap(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is ByteArray -> android.util.Base64.encodeToString(value, android.util.Base64.NO_WRAP)
        is JSONObject, is JSONArray, is String, is Boolean, is Int, is Long, is Double, is Float -> value
        is Number -> value.toLong()
        is Collection<*> -> JSONArray().apply { value.forEach { put(wrap(it)) } }
        is Map<*, *> -> JSONObject().apply { value.forEach { (k, v) -> put(k.toString(), wrap(v)) } }
        else -> value.toString()
    }

    /** Parses a JSON result text. */
    fun parse(text: String): Any? = when (val value = JSONTokener(text).nextValue()) {
        JSONObject.NULL -> null
        else -> value
    }
}
