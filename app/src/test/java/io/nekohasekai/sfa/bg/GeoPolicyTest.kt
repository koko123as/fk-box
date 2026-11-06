package io.nekohasekai.sfa.bg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoPolicyTest {
    private val bundleTime = 1_762_396_920_000L
    private val bundleSha = "a".repeat(64)

    @Test
    fun missingFileIsAlwaysExtracted() {
        assertTrue(GeoPolicy.shouldExtract(false, null, bundleSha, bundleTime, 0L))
        val newer = GeoPolicy.Installed(GeoPolicy.SOURCE_DOWNLOAD, "b".repeat(64), bundleTime + 1)
        assertTrue(GeoPolicy.shouldExtract(false, newer, bundleSha, bundleTime, 0L))
    }

    @Test
    fun newerDownloadIsNeverOverwritten() {
        val download = GeoPolicy.Installed(GeoPolicy.SOURCE_DOWNLOAD, "b".repeat(64), bundleTime + 60_000)
        assertFalse(GeoPolicy.shouldExtract(true, download, bundleSha, bundleTime, bundleTime + 60_000))
        val sameTime = download.copy(time = bundleTime)
        assertFalse(GeoPolicy.shouldExtract(true, sameTime, bundleSha, bundleTime, bundleTime))
    }

    @Test
    fun newerBundleReplacesOlderData() {
        val oldBundle = GeoPolicy.Installed(GeoPolicy.SOURCE_BUILTIN, "c".repeat(64), bundleTime - 86_400_000)
        assertTrue(GeoPolicy.shouldExtract(true, oldBundle, bundleSha, bundleTime, 1L))
        val oldDownload = GeoPolicy.Installed(GeoPolicy.SOURCE_DOWNLOAD, "d".repeat(64), bundleTime - 1)
        assertTrue(GeoPolicy.shouldExtract(true, oldDownload, bundleSha, bundleTime, 1L))
        val current = GeoPolicy.Installed(GeoPolicy.SOURCE_BUILTIN, bundleSha, bundleTime)
        assertFalse(GeoPolicy.shouldExtract(true, current, bundleSha, bundleTime, 1L))
    }

    @Test
    fun untrackedFileFollowsItsModificationTime() {
        // A file mihomo downloaded by itself has no state entry.
        assertFalse(GeoPolicy.shouldExtract(true, null, bundleSha, bundleTime, bundleTime + 1))
        assertTrue(GeoPolicy.shouldExtract(true, null, bundleSha, bundleTime, bundleTime - 1))
        assertFalse(GeoPolicy.shouldExtract(true, null, bundleSha, bundleTime, 0L))
    }

    @Test
    fun updateIsDueAfterTheInterval() {
        val hour = 3_600_000L
        assertTrue(GeoPolicy.isDue(0L, 24, 10 * hour))
        assertFalse(GeoPolicy.isDue(10 * hour, 24, 33 * hour))
        assertTrue(GeoPolicy.isDue(10 * hour, 24, 34 * hour))
        assertTrue(GeoPolicy.isDue(10 * hour, 0, 11 * hour))
    }

    @Test
    fun stateRoundTripsAndToleratesDamage() {
        val state = mapOf(
            GeoPolicy.GEOIP to GeoPolicy.Installed(GeoPolicy.SOURCE_BUILTIN, bundleSha, bundleTime, 5L),
            GeoPolicy.ASN to GeoPolicy.Installed(GeoPolicy.SOURCE_DOWNLOAD, "e".repeat(64), bundleTime + 9, bundleTime + 9),
        )
        assertEquals(state, GeoPolicy.decodeState(GeoPolicy.encodeState(state)))
        assertEquals(emptyMap<String, GeoPolicy.Installed>(), GeoPolicy.decodeState("{not json"))
        assertEquals(emptyMap<String, GeoPolicy.Installed>(), GeoPolicy.decodeState(""))
    }

    @Test
    fun urlsKeepOnlyKnownResourcesWithHttpAddresses() {
        val urls = mapOf(
            GeoPolicy.GEOSITE to "https://example.com/geosite.dat",
            GeoPolicy.MMDB to "http://mirror.example/geoip.metadb",
        )
        assertEquals(urls, GeoPolicy.decodeUrls(GeoPolicy.encodeUrls(urls)))
        assertEquals("", GeoPolicy.encodeUrls(emptyMap()))
        val dirty = """{"geoip":"ftp://example.com/geoip.dat","other":"https://example.com/x","asn":"https://example.com/asn.mmdb"}"""
        assertEquals(mapOf(GeoPolicy.ASN to "https://example.com/asn.mmdb"), GeoPolicy.decodeUrls(dirty))
        assertFalse(GeoPolicy.validUrl("https://"))
        assertFalse(GeoPolicy.validUrl("not a url"))
        assertTrue(GeoPolicy.validUrl("https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/geoip.dat"))
    }
}
