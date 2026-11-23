package io.nekohasekai.sfa.bridge

import android.content.Context
import android.net.ConnectivityManager
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.NetworkQualityProgress
import io.nekohasekai.libbox.NetworkQualityResult
import io.nekohasekai.libbox.NetworkQualityTestHandler
import io.nekohasekai.libbox.STUNTestHandler
import io.nekohasekai.libbox.STUNTestProgress
import io.nekohasekai.libbox.STUNTestResult
import io.nekohasekai.libbox.TailscalePingHandler
import io.nekohasekai.libbox.TailscalePingResult
import io.nekohasekai.sfa.utils.CommandTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.Closeable

/**
 * The libbox STUN, network quality and Tailscale ping runs (phase 4e). The
 * UI (mobile-ffi `diagnostics.rs`) decides when each runs and keeps its
 * state; this starts and closes the libbox objects and forwards their
 * callbacks raw as "diagnostic" events (`kind`, `id`, `event`, `value`),
 * `failed` when a run could not start.
 */
internal class BoxDiagnostics(
    private val context: Context,
    private val scope: CoroutineScope,
    private val emit: (String, Any?) -> Unit,
) : Closeable {
    /** What closes the current run of each kind, and that run's id. */
    private val closers = HashMap<String, () -> Unit>()
    private val current = HashMap<String, Long>()

    private fun send(kind: String, id: Long, event: String, value: Any? = null) {
        emit("diagnostic", mapOf("kind" to kind, "id" to id, "event" to event, "value" to value))
    }

    /** Keeps [close] for [kind]'s run [id] unless a newer run replaced it, which closes it at once. */
    private fun attach(kind: String, id: Long, close: () -> Unit) {
        val kept = synchronized(closers) {
            if ((current[kind] ?: 0L) == id) {
                closers[kind] = close
                true
            } else {
                false
            }
        }
        if (!kept) runCatching { close() }
    }

    fun defaults(): Map<String, Any?> =
        mapOf("stunServer" to Libbox.STUNDefaultServer, "qualityURL" to Libbox.NetworkQualityDefaultConfigURL)

    fun metered(): Boolean =
        (context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered

    fun run(kind: String, id: Long, args: Map<String, Any?>) {
        cancel(kind)
        synchronized(closers) { current[kind] = id }
        scope.launch(Dispatchers.IO) {
            try {
                when (kind) {
                    "stun" -> runStun(id, args)
                    "quality" -> runQuality(id, args)
                    "ping" -> runPing(id, args)
                    else -> error("Unknown diagnostic: $kind")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                send(kind, id, "failed", e.message ?: e.toString())
            }
        }
    }

    fun cancel(kind: String) {
        val close = synchronized(closers) {
            current.remove(kind)
            closers.remove(kind)
        }
        close?.let { runCatching { it() } }
    }

    private fun runStun(id: Long, args: Map<String, Any?>) {
        val server = args["server"] as? String ?: error("Missing argument: server")
        val handler = object : STUNTestHandler {
            override fun onProgress(progress: STUNTestProgress?) {
                progress ?: return
                send("stun", id, "progress", mapOf(
                    "phase" to progress.phase, "externalAddr" to progress.externalAddr, "latencyMs" to progress.latencyMs,
                    "mapping" to Libbox.formatNATMapping(progress.natMapping), "filtering" to Libbox.formatNATFiltering(progress.natFiltering),
                ))
            }

            override fun onResult(result: STUNTestResult?) {
                result ?: return
                send("stun", id, "result", mapOf(
                    "donePhase" to Libbox.STUNPhaseDone, "externalAddr" to result.externalAddr, "latencyMs" to result.latencyMs,
                    "mapping" to Libbox.formatNATMapping(result.natMapping), "filtering" to Libbox.formatNATFiltering(result.natFiltering),
                    "natTypeSupported" to result.natTypeSupported,
                ))
            }

            override fun onError(message: String?) = send("stun", id, "error", message)
        }
        if (args["service"] == true) {
            val session = CommandTarget.standaloneClient().startSTUNTest(server, args["outbound"] as? String ?: "", handler)
            attach("stun", id) { session.close() }
        } else {
            val test = Libbox.newSTUNTest()
            attach("stun", id) { test.cancel() }
            test.start(server, handler)
        }
    }

    private fun runQuality(id: Long, args: Map<String, Any?>) {
        val url = BoxProfiles.validateUrl(args["url"] as? String ?: error("Missing argument: url"))
        val serial = args["serial"] == true
        val http3 = args["http3"] == true
        val seconds = (args["seconds"] as? Number)?.toInt() ?: 30
        val handler = object : NetworkQualityTestHandler {
            override fun onProgress(progress: NetworkQualityProgress?) {
                progress ?: return
                send("quality", id, "progress", mapOf(
                    "phase" to progress.phase, "idleLatencyMs" to progress.idleLatencyMs,
                    "downloadCapacity" to progress.downloadCapacity, "uploadCapacity" to progress.uploadCapacity,
                    "downloadRPM" to progress.downloadRPM, "uploadRPM" to progress.uploadRPM,
                ))
            }

            override fun onResult(result: NetworkQualityResult?) {
                result ?: return
                send("quality", id, "result", mapOf(
                    "donePhase" to Libbox.NetworkQualityPhaseDone, "idleLatencyMs" to result.idleLatencyMs,
                    "downloadCapacity" to result.downloadCapacity, "uploadCapacity" to result.uploadCapacity,
                    "downloadRPM" to result.downloadRPM, "uploadRPM" to result.uploadRPM,
                ))
            }

            override fun onError(message: String?) = send("quality", id, "error", message)
        }
        if (args["service"] == true) {
            val session = CommandTarget.standaloneClient()
                .startNetworkQualityTest(url, args["outbound"] as? String ?: "", serial, seconds, http3, handler)
            attach("quality", id) { session.close() }
        } else {
            val test = Libbox.newNetworkQualityTest()
            attach("quality", id) { test.cancel() }
            test.start(url, serial, seconds, http3, handler)
        }
    }

    private fun runPing(id: Long, args: Map<String, Any?>) {
        val endpoint = args["endpoint"] as? String ?: error("Missing argument: endpoint")
        val address = args["address"] as? String ?: error("Missing argument: address")
        val session = CommandTarget.standaloneClient().startTailscalePing(endpoint, address, object : TailscalePingHandler {
            override fun onPingResult(result: TailscalePingResult?) {
                result ?: return
                send("ping", id, "result", mapOf(
                    "latencyMs" to result.latencyMs, "isDirect" to result.isDirect,
                    "derpRegionCode" to result.derpRegionCode, "endpoint" to result.endpoint, "error" to result.error,
                ))
            }

            override fun onError(message: String?) = send("ping", id, "error", message)
        })
        attach("ping", id) { session.close() }
    }

    override fun close() {
        listOf("stun", "quality", "ping").forEach(::cancel)
    }
}
