package io.nekohasekai.sfa.flutter

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
        val api = BoxUpdates.Candidate("1.15.0", 750, "b", "notes", setOf("FK-box-1.15.0-arm64-v8a.apk"))
        val prerelease = BoxUpdates.Candidate("1.15.1-alpha.1", 751, "c", "", emptySet())
        assertEquals(api, BoxUpdates.newest(listOf(latest, api)))
        assertEquals(prerelease, BoxUpdates.newest(listOf(latest, api, prerelease)))
        assertEquals(latest, BoxUpdates.newest(listOf(latest)))
    }

    @Test
    fun apiRateLimitIsNamed() {
        assertEquals("Release request failed: GitHub API rate limit (HTTP 403)", BoxUpdates.statusMessage("https://api.github.com/x", 403))
        assertEquals("Release request failed: HTTP 404", BoxUpdates.statusMessage("https://github.com/x", 404))
    }
}
