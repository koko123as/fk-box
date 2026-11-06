package io.nekohasekai.sfa.flutter

import android.os.Build
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.libbox.Libbox
import org.json.JSONArray
import org.json.JSONObject

/**
 * Uses FK-box's release metadata, including prereleases, rather than SFA's updater.
 *
 * Two sources are read and the higher version code wins:
 * - the latest release's metadata asset through github.com/…/releases/latest/download, which is a plain
 *   redirect to the asset origin and is not subject to the anonymous REST API limit (60 requests an hour
 *   per IP, so a shared proxy exit answers 403 quickly);
 * - the REST release list, which also sees prereleases and release notes.
 * A check fails only when both sources fail; a dropped connection (EOF) is retried once.
 */
internal object BoxUpdates {
    private const val REPO = "koko123as/fk-box"
    private const val METADATA = "FK-box-version-metadata.json"
    private const val RELEASES_PAGE = "https://github.com/$REPO/releases"

    internal data class Candidate(
        val version: String,
        val code: Long,
        val url: String,
        val notes: String,
        val assetNames: Set<String>?,
    )

    private fun get(url: String): String {
        var failure: Throwable? = null
        for (attempt in 0..1) {
            val response = try {
                // gomobile lowercases the initial uppercase run in UIHTTPGet.
                Libbox.uihttpGet(url)
            } catch (error: Throwable) {
                failure = error // EOF, reset or timeout: try once more
                continue
            }
            if (response.status == 200) return response.content.toString(Charsets.UTF_8)
            failure = IllegalStateException(statusMessage(url, response.status))
            // 403/404 would answer the same again; only a server-side error is repeated.
            if (response.status < 500) break
        }
        throw failure ?: IllegalStateException("Release request failed")
    }

    internal fun statusMessage(url: String, status: Int): String = when {
        status == 403 && url.contains("api.github.com") -> "Release request failed: GitHub API rate limit (HTTP 403)"
        else -> "Release request failed: HTTP $status"
    }

    /** A metadata asset's candidate, or null when it is for another app or malformed. */
    internal fun candidate(metadata: JSONObject, applicationId: String, url: String?, notes: String, assetNames: Set<String>?): Candidate? =
        candidate(metadata.optString("application_id"), metadata.optLong("version_code", -1L), metadata.optString("version_name"), applicationId, url, notes, assetNames)

    internal fun candidate(metadataId: String, code: Long, version: String, applicationId: String, url: String?, notes: String, assetNames: Set<String>?): Candidate? {
        if (metadataId != applicationId || code <= 0 || version.isBlank()) return null
        return Candidate(version, code, url ?: "$RELEASES_PAGE/tag/$version", notes, assetNames)
    }

    /** The newest candidate; on a tie the REST one (with notes and the asset list) wins. */
    internal fun newest(candidates: List<Candidate>): Candidate? =
        candidates.maxWithOrNull(compareBy<Candidate> { it.code }.thenBy { it.assetNames != null })

    internal fun apkName(version: String, abi: String) = "FK-box-$version-$abi.apk"

    private fun fromLatest(): Candidate? =
        candidate(JSONObject(get("$RELEASES_PAGE/latest/download/$METADATA")), BuildConfig.APPLICATION_ID, null, "", null)

    private fun fromApi(): Candidate? {
        val releases = JSONArray(get("https://api.github.com/repos/$REPO/releases?per_page=20"))
        val found = mutableListOf<Candidate>()
        for (index in 0 until releases.length()) {
            val release = releases.getJSONObject(index)
            if (release.optBoolean("draft")) continue
            val assets = release.optJSONArray("assets") ?: continue
            val assetList = (0 until assets.length()).map { assets.getJSONObject(it) }
            val metadataAsset = assetList.firstOrNull { it.optString("name") == METADATA } ?: continue
            val metadata = try {
                JSONObject(get(metadataAsset.getString("browser_download_url")))
            } catch (_: Throwable) {
                continue
            }
            candidate(
                metadata, BuildConfig.APPLICATION_ID, release.getString("html_url"),
                if (release.isNull("body")) "" else release.optString("body"),
                assetList.map { it.optString("name") }.toSet(),
            )?.let(found::add)
        }
        return newest(found)
    }

    fun check(): Map<String, Any?> {
        val errors = mutableListOf<Throwable>()
        val candidates = listOf(::fromLatest, ::fromApi).mapNotNull { source ->
            try {
                source()
            } catch (error: Throwable) {
                errors += error
                null
            }
        }
        // Both sources failed: surface the first (non-API) failure rather than claiming no update.
        if (candidates.isEmpty() && errors.size == 2) throw errors.first()
        val latest = newest(candidates)
            ?: return mapOf("available" to false, "hasMetadata" to false, "url" to RELEASES_PAGE)
        val abi = Build.SUPPORTED_ABIS.firstOrNull { abi -> latest.assetNames?.contains(apkName(latest.version, abi)) ?: (abi == "arm64-v8a") }
        return mapOf(
            "available" to (latest.code > BuildConfig.VERSION_CODE), "hasMetadata" to true,
            "version" to latest.version, "versionCode" to latest.code,
            "url" to latest.url, "notes" to latest.notes,
            "downloadUrl" to abi?.let { "$RELEASES_PAGE/download/${latest.version}/${apkName(latest.version, it)}" },
        )
    }
}
