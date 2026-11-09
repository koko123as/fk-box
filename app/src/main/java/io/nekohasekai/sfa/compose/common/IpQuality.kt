@file:Suppress("EnumEntryName")

package io.nekohasekai.sfa.compose.common

import io.nekohasekai.sfa.compose.BoxController
import io.nekohasekai.sfa.compose.Json
import io.nekohasekai.sfa.compose.asLong
import io.nekohasekai.sfa.compose.json
import io.nekohasekai.sfa.bridge.jsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/*
 * Outbound IP and IP quality probing, adapted from FlClash (GPL-3.0): provider order, parsing, inferred fallback, quality rules and the
 * cache lifetimes are unchanged; requests use the cancellable sing-box probe.
 */

class IpProbeResponse(val status: Int, val body: String)

class IpProbeCancelled : CancellationException("IP probe cancelled")

class IpProbeTimeout : Exception("IP source timed out")

interface IpProbeTransport {
    val cancelled: Boolean
    suspend fun get(url: String, timeoutMillis: Long): IpProbeResponse
    suspend fun close()
}

/** The native session uses the device network while stopped and sing-box while running. */
class RoutedIpProbe(private val box: BoxController) : IpProbeTransport {
    val id = "${System.nanoTime() / 1000}:${serial++}"

    @Volatile
    override var cancelled = false
        private set
    private var closed = false

    suspend fun open() {
        if (cancelled) throw IpProbeCancelled()
        box.call { openProbe(id) }
        if (cancelled) throw IpProbeCancelled()
    }

    suspend fun egress(): Json {
        if (cancelled) throw IpProbeCancelled()
        val result = json(box.call { egress(id) })
        if (cancelled) throw IpProbeCancelled()
        return result
    }

    override suspend fun get(url: String, timeoutMillis: Long): IpProbeResponse {
        if (cancelled) throw IpProbeCancelled()
        try {
            val result = json(box.call { probeGet(id, url, timeoutMillis) })
            if (cancelled) throw IpProbeCancelled()
            return IpProbeResponse(result["status"].asLong()?.toInt() ?: 503, result["body"] as? String ?: "")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (cancelled) throw IpProbeCancelled()
            if (error.message?.contains("deadline exceeded") == true) throw IpProbeTimeout()
            throw error
        }
    }

    fun cancel() {
        cancelled = true
        box.launch { close() }
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        try {
            withContext(NonCancellable) { box.call { closeProbe(id) } }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            android.util.Log.w("RoutedIpProbe", "Unable to close IP probe: ${BoxController.describe(error)}")
        }
    }

    companion object {
        private var serial = 0
    }
}

enum class IpType(val label: String) { residential("Residential"), mobile("Mobile network"), business("Business"), hosting("Data center") }

enum class IpQualityLevel(val label: String) { good("Good"), normal("Normal"), risky("Risky") }

enum class IpQualitySource(val label: String) {
    identMe("ident.me"), ipApiCom("ip-api.com"), ipQuery("ipquery.io"),
    ipLocate("iplocate.io"), proxyCheck("proxycheck.io"), ipApiIs("ipapi.is"),
}

enum class IpQualitySourceStatus { noType, timeout, rateLimited, failed, ipMismatch }

data class IpQuality(
    val ip: String,
    val source: IpQualitySource,
    val type: IpType,
    val organization: String? = null,
    val asn: Long? = null,
    val isProxy: Boolean = false,
    val isVpn: Boolean = false,
    val isTor: Boolean = false,
    val isAbuser: Boolean = false,
) {
    val level: IpQualityLevel
        get() = if (isTor || isAbuser) IpQualityLevel.risky else if (type == IpType.hosting) IpQualityLevel.normal else IpQualityLevel.good

    fun toJson(): Map<String, Any?> = mapOf(
        "ip" to ip, "source" to source.name, "type" to type.name, "organization" to organization, "asn" to asn,
        "isProxy" to isProxy, "isVpn" to isVpn, "isTor" to isTor, "isAbuser" to isAbuser,
    )

    fun toDashboard(checkedAt: Long): Map<String, Any?> = mapOf(
        "organization" to organization, "asn" to asn?.let { "AS$it" }, "type" to type.label, "quality" to level.label,
        "source" to source.label, "qualityCheckedAt" to checkedAt,
        "isProxy" to isProxy, "isVpn" to isVpn, "isTor" to isTor, "isAbuser" to isAbuser,
    )

    companion object {
        fun fromJson(value: Json) = IpQuality(
            ip = value["ip"] as String,
            source = IpQualitySource.valueOf(value["source"] as String),
            type = IpType.valueOf(value["type"] as String),
            organization = value["organization"] as? String,
            asn = value["asn"].asLong(),
            isProxy = value["isProxy"] as? Boolean ?: false,
            isVpn = value["isVpn"] as? Boolean ?: false,
            isTor = value["isTor"] as? Boolean ?: false,
            isAbuser = value["isAbuser"] as? Boolean ?: false,
        )
    }
}

class IpQualityRecord(val quality: IpQuality, val checkedAt: Long) {
    fun toJson(): Map<String, Any?> = mapOf("quality" to quality.toJson(), "checkedAt" to checkedAt)

    companion object {
        fun fromJson(value: Json) = IpQualityRecord(IpQuality.fromJson(json(value["quality"])), value["checkedAt"].asLong()!!)
    }
}

class IpQualityFailure(val source: IpQualitySource, val status: IpQualitySourceStatus)

class IpQualityLookupException(val failures: List<IpQualityFailure>) : Exception("IP quality unavailable")

private class SourceFailure(val status: IpQualitySourceStatus) : Exception()

private class Answer(val quality: IpQuality, val inferred: Boolean)

private val waves = listOf(
    listOf(IpQualitySource.identMe, IpQualitySource.ipApiCom, IpQualitySource.ipQuery),
    listOf(IpQualitySource.ipLocate),
    listOf(IpQualitySource.ipApiIs),
    listOf(IpQualitySource.proxyCheck),
)

suspend fun lookupIpQuality(transport: IpProbeTransport, ip: String, timeoutMillis: Long = 8000, hedgeMillis: Long = 1000): IpQuality = coroutineScope {
    val result = CompletableDeferred<IpQuality>()
    val failures = mutableListOf<IpQualityFailure>()
    var fallback: IpQuality? = null
    var wave = 0
    var inFlight = 0
    var timer: Job? = null
    lateinit var launchWave: () -> Unit

    fun cancelLookup() {
        timer?.cancel()
        if (!result.isCompleted) result.completeExceptionally(IpProbeCancelled())
    }

    fun settle() {
        if (inFlight > 0) return
        val value = fallback
        if (value != null) {
            result.complete(value)
        } else {
            result.completeExceptionally(IpQualityLookupException(failures.sortedBy { it.source.ordinal }))
        }
        // The cache closes the transport once the lookup returns.
    }

    fun advance() {
        if (inFlight == 0) launchWave()
    }

    fun onAnswer(answer: Answer) {
        inFlight--
        if (result.isCompleted) return
        if (transport.cancelled) return cancelLookup()
        if (answer.inferred) {
            if (fallback == null) fallback = answer.quality
            advance()
            return
        }
        timer?.cancel()
        result.complete(answer.quality)
    }

    fun onError(source: IpQualitySource, error: Throwable) {
        inFlight--
        if (result.isCompleted) return
        if (transport.cancelled) return cancelLookup()
        failures += IpQualityFailure(source, statusOf(error))
        advance()
    }

    launchWave = {
        timer?.cancel()
        if (!result.isCompleted) {
            if (transport.cancelled) {
                cancelLookup()
            } else if (wave == waves.size) {
                settle()
            } else {
                val sources = waves[wave++]
                inFlight += sources.size
                for (source in sources) {
                    launch {
                        val answer = try {
                            query(transport, source, ip, timeoutMillis)
                        } catch (error: TimeoutCancellationException) {
                            onError(source, IpProbeTimeout())
                            return@launch
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            onError(source, error)
                            return@launch
                        }
                        onAnswer(answer)
                    }
                }
                if (wave < waves.size) {
                    timer = launch {
                        delay(hedgeMillis)
                        launchWave()
                    }
                }
            }
        }
    }

    launchWave()
    try {
        result.await()
    } finally {
        coroutineContext[Job]?.children?.forEach { it.cancel() }
    }
}

private fun statusOf(error: Throwable): IpQualitySourceStatus = when (error) {
    is SourceFailure -> error.status
    is IpProbeTimeout -> IpQualitySourceStatus.timeout
    else -> IpQualitySourceStatus.failed
}

private suspend fun query(transport: IpProbeTransport, source: IpQualitySource, ip: String, timeoutMillis: Long): Answer {
    val response = withTimeout(timeoutMillis) { transport.get(urlOf(source, ip), timeoutMillis) }
    if (response.status == 429) throw SourceFailure(IpQualitySourceStatus.rateLimited)
    if (response.status != 200) throw SourceFailure(IpQualitySourceStatus.failed)
    val json = jsonObject(JSONObject(response.body))
    return parse(source, ip, json) ?: throw SourceFailure(IpQualitySourceStatus.noType)
}

private fun urlOf(source: IpQualitySource, ip: String): String = when (source) {
    IpQualitySource.identMe -> "https://ident.me/json"
    // ip-api.com serves HTTPS only on its paid plan.
    IpQualitySource.ipApiCom -> "http://ip-api.com/json/$ip?fields=status,isp,org,as,mobile,proxy,hosting"
    IpQualitySource.ipQuery -> "https://api.ipquery.io/$ip?format=json"
    IpQualitySource.ipLocate -> "https://iplocate.io/api/lookup/$ip"
    IpQualitySource.proxyCheck -> "https://proxycheck.io/v3/$ip"
    IpQualitySource.ipApiIs -> "https://api.ipapi.is/?q=$ip"
}

private fun parse(source: IpQualitySource, ip: String, json: Json): Answer? = when (source) {
    IpQualitySource.identMe -> fromIdentMe(ip, json)
    IpQualitySource.ipApiCom -> fromIpApiCom(ip, json)
    IpQualitySource.ipQuery -> fromIpQuery(ip, json)
    IpQualitySource.ipLocate -> fromIpLocate(ip, json)
    IpQualitySource.proxyCheck -> fromProxyCheck(ip, json)
    IpQualitySource.ipApiIs -> fromIpApiIs(ip, json)
}

private fun fromIdentMe(ip: String, json: Json): Answer? {
    if (json["ip"] != ip) throw SourceFailure(IpQualitySourceStatus.ipMismatch)
    return quality(ip, IpQualitySource.identMe, declaredType(json["type"]), organization = text(json["aso"]), asn = asnOf(json["asn"]))
}

private fun fromIpApiCom(ip: String, json: Json): Answer? {
    if (json["status"] != "success") throw SourceFailure(IpQualitySourceStatus.failed)
    val type = pickType(hosting = flag(json["hosting"]), mobile = flag(json["mobile"]))
    return quality(
        ip, IpQualitySource.ipApiCom, type ?: IpType.residential, inferred = type == null,
        organization = text(json["org"]) ?: text(json["isp"]), asn = asnOf(json["as"]), isProxy = flag(json["proxy"]),
    )
}

private fun fromIpQuery(ip: String, json: Json): Answer? {
    val risk = obj(json["risk"])
    val isp = obj(json["isp"])
    val type = pickType(hosting = flag(risk["is_datacenter"]), mobile = flag(risk["is_mobile"]))
    return quality(
        ip, IpQualitySource.ipQuery, type ?: IpType.residential, inferred = type == null,
        organization = text(isp["org"]) ?: text(isp["isp"]), asn = asnOf(isp["asn"]),
        isProxy = flag(risk["is_proxy"]), isVpn = flag(risk["is_vpn"]), isTor = flag(risk["is_tor"]),
    )
}

private fun fromIpLocate(ip: String, json: Json): Answer? {
    val privacy = obj(json["privacy"])
    val company = obj(json["company"])
    val asn = obj(json["asn"])
    return quality(
        ip, IpQualitySource.ipLocate,
        pickType(hosting = flag(privacy["is_hosting"]), declared = declaredType(company["type"]) ?: declaredType(asn["type"])),
        organization = text(company["name"]) ?: text(asn["name"]), asn = asnOf(asn["asn"]),
        isProxy = flag(privacy["is_proxy"]), isVpn = flag(privacy["is_vpn"]), isTor = flag(privacy["is_tor"]), isAbuser = flag(privacy["is_abuser"]),
    )
}

private fun fromProxyCheck(ip: String, json: Json): Answer? {
    val status = json["status"]
    if (status == "denied") throw SourceFailure(IpQualitySourceStatus.rateLimited)
    if (status != "ok" && status != "warning") throw SourceFailure(IpQualitySourceStatus.failed)
    val result = obj(json[ip])
    val network = obj(result["network"])
    val detections = obj(result["detections"])
    val networkType = network["type"]
    return quality(
        ip, IpQualitySource.proxyCheck,
        pickType(
            hosting = flag(detections["hosting"]) || networkType == "Hosting",
            mobile = networkType == "Wireless",
            declared = when (networkType) {
                "Residential" -> IpType.residential
                "Business" -> IpType.business
                else -> null
            },
        ),
        organization = text(network["organisation"]), asn = asnOf(network["asn"]),
        isProxy = flag(detections["proxy"]), isVpn = flag(detections["vpn"]), isTor = flag(detections["tor"]),
    )
}

private fun fromIpApiIs(ip: String, json: Json): Answer? {
    val company = obj(json["company"])
    val asn = obj(json["asn"])
    return quality(
        ip, IpQualitySource.ipApiIs,
        pickType(hosting = flag(json["is_datacenter"]), mobile = flag(json["is_mobile"]), declared = declaredType(company["type"]) ?: declaredType(asn["type"])),
        organization = text(company["name"]) ?: text(asn["org"]), asn = asnOf(asn["asn"]),
        isProxy = flag(json["is_proxy"]), isVpn = flag(json["is_vpn"]), isTor = flag(json["is_tor"]), isAbuser = flag(json["is_abuser"]),
    )
}

/** An inferred type is the source's silence about hosting and mobile taken as residential. */
private fun quality(
    ip: String,
    source: IpQualitySource,
    type: IpType?,
    inferred: Boolean = false,
    organization: String? = null,
    asn: Long? = null,
    isProxy: Boolean = false,
    isVpn: Boolean = false,
    isTor: Boolean = false,
    isAbuser: Boolean = false,
): Answer? {
    if (type == null) return null
    return Answer(IpQuality(ip, source, type, organization, asn, isProxy, isVpn, isTor, isAbuser), inferred)
}

private fun pickType(hosting: Boolean, mobile: Boolean = false, declared: IpType? = null): IpType? = when {
    hosting || declared == IpType.hosting -> IpType.hosting
    mobile -> IpType.mobile
    else -> declared
}

private fun declaredType(value: Any?): IpType? = when (value) {
    "isp" -> IpType.residential
    "business", "education", "government", "banking" -> IpType.business
    "hosting" -> IpType.hosting
    else -> null
}

private fun obj(value: Any?): Json = if (value is Map<*, *>) json(value) else emptyMap()

private fun flag(value: Any?): Boolean = value == true

private fun text(value: Any?): String? = (value as? String)?.takeIf { it.isNotEmpty() }

private fun asnOf(value: Any?): Long? {
    val asn = when (value) {
        is Int, is Long -> (value as Number).toLong()
        is String -> Regex("\\d+").find(value)?.value?.toLongOrNull()
        else -> null
    }
    return if (asn == 0L) null else asn
}

/** Records live three days; failures five minutes in memory. */
class IpQualityCache(
    private val read: suspend (String) -> Any?,
    private val write: suspend (String, Map<String, Any?>) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val onCacheError: ((Throwable) -> Unit)? = null,
) {
    companion object {
        const val MAX_AGE = 3L * 24 * 60 * 60 * 1000
        const val FAILURE_COOLDOWN = 5L * 60 * 1000
    }

    private val records = HashMap<String, IpQualityRecord>()
    private val failures = HashMap<String, Pair<Long, IpQualityLookupException>>()
    private val pending = HashMap<String, Pair<IpProbeTransport, Deferred<IpQualityRecord>>>()

    private fun fresh(record: IpQualityRecord, ip: String): Boolean {
        val age = now() - record.checkedAt
        return record.quality.ip == ip && age >= 0 && age < MAX_AGE
    }

    suspend fun lookup(transport: IpProbeTransport, ip: String, refresh: Boolean = false): IpQualityRecord {
        if (transport.cancelled) throw IpProbeCancelled()
        records.entries.removeAll { !fresh(it.value, it.key) }
        failures.entries.removeAll { val age = now() - it.value.first; age < 0 || age >= FAILURE_COOLDOWN }
        if (refresh) {
            records.remove(ip)
            failures.remove(ip)
        } else {
            records[ip]?.let { return it }
            failures[ip]?.let { throw it.second }
            var raw: Any? = null
            try {
                raw = read(ip)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                onCacheError?.invoke(error)
            }
            if (transport.cancelled) throw IpProbeCancelled()
            try {
                if (raw is Map<*, *>) {
                    val record = IpQualityRecord.fromJson(json(raw))
                    if (fresh(record, ip)) {
                        records[ip] = record
                        return record
                    }
                }
            } catch (error: Exception) {
                onCacheError?.invoke(error)
            }
        }
        if (transport.cancelled) throw IpProbeCancelled()
        pending[ip]?.let { (owner, result) ->
            if (!owner.cancelled) {
                val record = result.await()
                if (transport.cancelled) throw IpProbeCancelled()
                return record
            }
        }
        return coroutineScope {
            val result = async { doLookup(transport, ip) }
            pending[ip] = transport to result
            try {
                result.await()
            } finally {
                if (pending[ip]?.second === result) pending.remove(ip)
            }
        }
    }

    private suspend fun doLookup(transport: IpProbeTransport, ip: String): IpQualityRecord {
        try {
            val quality = lookupIpQuality(transport, ip)
            if (transport.cancelled) throw IpProbeCancelled()
            val record = IpQualityRecord(quality, now())
            records[ip] = record
            failures.remove(ip)
            try {
                write(ip, record.toJson())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                onCacheError?.invoke(error)
            }
            return record
        } catch (error: IpQualityLookupException) {
            if (!transport.cancelled) failures[ip] = now() to error
            throw error
        } finally {
            transport.close()
        }
    }
}
