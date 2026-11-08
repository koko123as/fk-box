// FlClash's geo.update and the geo files of its Resources page, answered with
// FK-box's GeoResources (libbox GeoUpdate). The files live in libbox's
// GeoDataDir, which mihomo uses as its home and Xray as xray.location.asset
// (geoip.dat, geosite.dat); FlClash's Dart side reads sizes and times of its
// own copies in its home directory (GEOIP.dat, GEOSITE.dat, GEOIP.metadb,
// ASN.mmdb), so every installed file is mirrored there.
//
// Like FlClash's core/geo.go, an update is accepted at once and reported
// through "geoUpdate" events (updating, then done/skipped/error).
package com.follow.clash.core

import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.bg.GeoPolicy
import io.nekohasekai.sfa.bg.GeoResources
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Collections

internal object FkGeo {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val claims = Collections.synchronizedSet(mutableSetOf<String>())

    /** FlClash's lib/common/constant.dart names (MMDB, ASN, GEOIP, GEOSITE). */
    private val homeNames = mapOf(
        GeoPolicy.GEOIP to "GEOIP.dat",
        GeoPolicy.GEOSITE to "GEOSITE.dat",
        GeoPolicy.MMDB to "GEOIP.metadb",
        GeoPolicy.ASN to "ASN.mmdb",
    )

    private fun nameOf(type: String): String? = when (type.uppercase()) {
        "GEOIP", "GEO-IP" -> GeoPolicy.GEOIP
        "GEOSITE", "GEO-SITE" -> GeoPolicy.GEOSITE
        "MMDB" -> GeoPolicy.MMDB
        "ASN" -> GeoPolicy.ASN
        else -> null
    }

    /** Extracts the bundled files libbox's cores read and mirrors them into FlClash's home. */
    suspend fun syncHome(home: String?) = withContext(Dispatchers.IO) {
        runCatching { GeoResources.ensureBundled() }
        if (home != null) for (name in homeNames.keys) runCatching { mirror(name, home) }
    }

    private fun mirror(name: String, home: String) {
        val source = File(Libbox.geoDataDir(), Libbox.geoResourceFileName(name))
        val target = File(home, homeNames.getValue(name))
        if (!source.isFile) return
        if (target.isFile && target.length() == source.length() && target.lastModified() == source.lastModified()) return
        val partial = File(home, "${target.name}.part")
        source.copyTo(partial, overwrite = true)
        partial.setLastModified(source.lastModified())
        if (!partial.renameTo(target)) {
            partial.delete()
            error("Unable to replace ${target.path}")
        }
    }

    private fun event(type: String, updating: Boolean, skipped: Boolean = false, error: String? = null): JSONArray {
        val data = JSONObject().put("type", type).put("updating", updating).put("skipped", skipped)
        if (error != null) data.put("error", error)
        return JSONArray().put(JSONObject().put("type", "geoUpdate").put("data", data))
    }

    fun update(type: String, geoxUrl: JSONObject?, home: String?, send: (JSONArray) -> Unit): Any? {
        val name = nameOf(type) ?: throw CoreMethodError("unknown_geo_resource", "Unknown geo resource: $type")
        val geoType = type.uppercase()
        // FlClash keeps the URLs in its config's geox-url; FK-box's updater reads its own setting.
        geoxUrl?.optString(name)?.trim()?.takeIf { it.isNotEmpty() && it != GeoResources.url(name) }?.let { url ->
            runCatching { GeoResources.setUrl(name, url) }
        }
        if (!claims.add(name)) {
            throw CoreMethodError("geo_update_running", "geo update already in progress: $geoType")
        }
        scope.launch {
            send(event(geoType, updating = true))
            var skipped = false
            var error: String? = null
            try {
                skipped = !GeoResources.update(name)
                if (home != null) mirror(name, home)
            } catch (failure: Exception) {
                error = failure.message ?: failure.toString()
            } finally {
                claims.remove(name)
            }
            send(event(geoType, updating = false, skipped = skipped, error = error))
        }
        return null
    }
}
