package io.nekohasekai.sfa.bridge

import android.os.Build
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.libbox.Libbox
import org.json.JSONArray
import org.json.JSONObject

/**
 * Uses fk-box's release metadata, including prereleases, rather than SFA's updater.
 *
 * Two sources are read and the higher version code wins:
 * - the latest release's metadata asset through github.com/…/releases/latest/download, which is a plain
 *   redirect to the asset origin and is not subject to the anonymous REST API limit (60 requests an hour
 *   per IP, so a shared proxy exit answers 403 quickly);
 * - the REST release list, which also sees prereleases and release notes.
 * A check fails only when both sources fail; a dropped connection (EOF) is retried once.
 *
 * Release assets are named `fk-box-*`; releases published before the rename use `FK-box-*`. Both
 * prefixes are accepted, the current one first, and the APK offered is the one the release
 * actually lists, so old and new releases are both found.
 */
internal object BoxUpdates {
    private const val REPO = "koko123as/fk-box"
    internal const val ASSET_PREFIX = "fk-box"
    internal const val LEGACY_ASSET_PREFIX = "FK-box"
    private val ASSET_PREFIXES = listOf(ASSET_PREFIX, LEGACY_ASSET_PREFIX)
    private const val RELEASES_PAGE = "https://github.com/$REPO/releases"

    internal data class Candidate(
        val version: String,
        val code: Long,
        val url: String,
        val notes: String,
        val assetNames: Set<String>?,
        /** The asset prefix of the metadata this candidate was read from. */
        val assetPrefix: String = ASSET_PREFIX,
    )

    /** An HTTP answer other than 200. */
    private class StatusException(val status: Int, message: String) : IllegalStateException(message)

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
            failure = StatusException(response.status, statusMessage(url, response.status))
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
    internal fun candidate(metadata: JSONObject, applicationId: String, url: String?, notes: String, assetNames: Set<String>?, assetPrefix: String = ASSET_PREFIX): Candidate? =
        candidate(metadata.optString("application_id"), metadata.optLong("version_code", -1L), metadata.optString("version_name"), applicationId, url, notes, assetNames, assetPrefix)

    internal fun candidate(metadataId: String, code: Long, version: String, applicationId: String, url: String?, notes: String, assetNames: Set<String>?, assetPrefix: String = ASSET_PREFIX): Candidate? {
        if (metadataId != applicationId || code <= 0 || version.isBlank()) return null
        return Candidate(version, code, url ?: "$RELEASES_PAGE/tag/$version", notes, assetNames, assetPrefix)
    }

    /** The newest candidate; on a tie the REST one (with notes and the asset list) wins. */
    internal fun newest(candidates: List<Candidate>): Candidate? =
        candidates.maxWithOrNull(compareBy<Candidate> { it.code }.thenBy { it.assetNames != null })

    internal fun metadataName(prefix: String) = "$prefix-version-metadata.json"

    internal fun apkName(version: String, abi: String, prefix: String = ASSET_PREFIX) = "$prefix-$version-$abi.apk"

    /**
     * The release APK for [abi]: with an asset list, the listed one under either prefix (current
     * first); without one (the latest/download source), the ARM64 APK under the metadata's prefix.
     */
    internal fun apkAsset(candidate: Candidate, abi: String): String? {
        val names = candidate.assetNames
            ?: return if (abi == "arm64-v8a") apkName(candidate.version, abi, candidate.assetPrefix) else null
        return ASSET_PREFIXES.map { apkName(candidate.version, abi, it) }.firstOrNull(names::contains)
    }

    /** The metadata asset of a release's asset names, current prefix first, with its prefix. */
    internal fun metadataAsset(names: List<String>): Pair<String, String>? =
        ASSET_PREFIXES.firstNotNullOfOrNull { prefix -> metadataName(prefix).takeIf(names::contains)?.let { it to prefix } }

    private fun fromLatest(): Candidate? {
        var notFound: Throwable? = null
        for (prefix in ASSET_PREFIXES) {
            val metadata = try {
                get("$RELEASES_PAGE/latest/download/${metadataName(prefix)}")
            } catch (error: StatusException) {
                // Only a missing asset tries the other name; other failures are reported as they are.
                if (error.status != 404) throw error
                if (notFound == null) notFound = error
                continue
            }
            return candidate(JSONObject(metadata), BuildConfig.APPLICATION_ID, null, "", null, prefix)
        }
        throw notFound ?: IllegalStateException("Release request failed")
    }

    private fun fromApi(): Candidate? {
        val releases = JSONArray(get("https://api.github.com/repos/$REPO/releases?per_page=20"))
        val found = mutableListOf<Candidate>()
        for (index in 0 until releases.length()) {
            val release = releases.getJSONObject(index)
            if (release.optBoolean("draft")) continue
            val assets = release.optJSONArray("assets") ?: continue
            val assetList = (0 until assets.length()).map { assets.getJSONObject(it) }
            val (metadataFile, prefix) = metadataAsset(assetList.map { it.optString("name") }) ?: continue
            val metadataEntry = assetList.first { it.optString("name") == metadataFile }
            val metadata = try {
                JSONObject(get(metadataEntry.getString("browser_download_url")))
            } catch (_: Throwable) {
                continue
            }
            candidate(
                metadata, BuildConfig.APPLICATION_ID, release.getString("html_url"),
                if (release.isNull("body")) "" else release.optString("body"),
                assetList.map { it.optString("name") }.toSet(),
                prefix,
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
        val apk = Build.SUPPORTED_ABIS.firstNotNullOfOrNull { abi -> apkAsset(latest, abi) }
        return mapOf(
            "available" to (latest.code > BuildConfig.VERSION_CODE), "hasMetadata" to true,
            "version" to latest.version, "versionCode" to latest.code,
            "url" to latest.url, "notes" to latest.notes,
            "downloadUrl" to apk?.let { "$RELEASES_PAGE/download/${latest.version}/$it" },
        )
    }
}
