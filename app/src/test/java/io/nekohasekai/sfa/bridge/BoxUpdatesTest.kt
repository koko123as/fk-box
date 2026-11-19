package io.nekohasekai.sfa.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BoxUpdatesTest {
    @Test
    fun latestDownloadCandidatePointsAtTheTagPage() {
        val candidate = BoxUpdates.candidate("io.fkbox.app", 750, "1.15.0", "io.fkbox.app", null, "", null)!!
        assertEquals("https://github.com/koko123as/fk-box/releases/tag/1.15.0", candidate.url)
        assertNull(BoxUpdates.candidate("other.app", 750, "1.15.0", "io.fkbox.app", null, "", null))
        assertNull(BoxUpdates.candidate("io.fkbox.app", 0, "1.15.0", "io.fkbox.app", null, "", null))
    }

    @Test
    fun newestPrefersHigherCodeThenTheApiCopy() {
        val latest = BoxUpdates.Candidate("1.15.0", 750, "a", "", null)
        val api = BoxUpdates.Candidate("1.15.0", 750, "b", "notes", setOf("fk-box-1.15.0-arm64-v8a.apk"))
        val prerelease = BoxUpdates.Candidate("1.15.1-alpha.1", 751, "c", "", emptySet())
        assertEquals(api, BoxUpdates.newest(listOf(latest, api)))
        assertEquals(prerelease, BoxUpdates.newest(listOf(latest, api, prerelease)))
        assertEquals(latest, BoxUpdates.newest(listOf(latest)))
    }

    @Test
    fun releaseAssetsUnderEitherPrefixAreFound() {
        // Releases since the rename use fk-box-*; older ones FK-box-*. The current name wins.
        assertEquals("fk-box-version-metadata.json" to "fk-box", BoxUpdates.metadataAsset(listOf("FK-box-version-metadata.json", "fk-box-version-metadata.json")))
        assertEquals("FK-box-version-metadata.json" to "FK-box", BoxUpdates.metadataAsset(listOf("FK-box-1.15.0-arm64-v8a.apk", "FK-box-version-metadata.json")))
        assertNull(BoxUpdates.metadataAsset(listOf("SFA-1.15.0-arm64-v8a.apk", "version-metadata.json")))

        val renamed = BoxUpdates.Candidate("2026.11.20", 26112001, "u", "", setOf("fk-box-2026.11.20-arm64-v8a.apk", "fk-box-version-metadata.json"))
        assertEquals("fk-box-2026.11.20-arm64-v8a.apk", BoxUpdates.apkAsset(renamed, "arm64-v8a"))
        assertNull(BoxUpdates.apkAsset(renamed, "x86_64"))
        val old = BoxUpdates.Candidate("1.15.0", 750, "u", "", setOf("FK-box-1.15.0-arm64-v8a.apk"), "FK-box")
        assertEquals("FK-box-1.15.0-arm64-v8a.apk", BoxUpdates.apkAsset(old, "arm64-v8a"))

        // latest/download lists no assets: the APK follows the metadata's prefix, ARM64 only.
        val latestOld = BoxUpdates.Candidate("1.15.0", 750, "u", "", null, "FK-box")
        assertEquals("FK-box-1.15.0-arm64-v8a.apk", BoxUpdates.apkAsset(latestOld, "arm64-v8a"))
        assertNull(BoxUpdates.apkAsset(latestOld, "armeabi-v7a"))
        val latestNew = BoxUpdates.Candidate("2026.11.20", 26112001, "u", "", null)
        assertEquals("fk-box-2026.11.20-arm64-v8a.apk", BoxUpdates.apkAsset(latestNew, "arm64-v8a"))
    }

    @Test
    fun apiRateLimitIsNamed() {
        assertEquals("Release request failed: GitHub API rate limit (HTTP 403)", BoxUpdates.statusMessage("https://api.github.com/x", 403))
        assertEquals("Release request failed: HTTP 404", BoxUpdates.statusMessage("https://github.com/x", 404))
    }
}
