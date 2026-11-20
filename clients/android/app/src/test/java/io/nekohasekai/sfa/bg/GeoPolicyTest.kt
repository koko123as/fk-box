package io.nekohasekai.sfa.bg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Extraction, update timing and per-core needs are tested in libbox (core_geo_policy_test.go). */
class GeoPolicyTest {
    private val bundleTime = 1_762_396_920_000L
    private val bundleSha = "a".repeat(64)

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

    @Test
    fun manifestListsBundledAndOnDemandFiles() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val current = json.decodeFromString(
            GeoPolicy.Bundle.serializer(),
            """{"source":"MetaCubeX/meta-rules-dat","commit":"c","time":5,""" +
                """"files":{"geosite":{"file":"geosite.dat","sha256":"$bundleSha","size":3}},""" +
                """"remote":{"geoip":{"file":"geoip.dat","url":"https://raw.githubusercontent.com/MetaCubeX/meta-rules-dat/c/geoip.dat","sha256":"$bundleSha"}}}""",
        )
        assertEquals(setOf(GeoPolicy.GEOSITE), current.files.keys)
        assertEquals("geoip.dat", current.remote.getValue(GeoPolicy.GEOIP).file)
        // A manifest from before on-demand downloads has no "remote" key.
        val older = json.decodeFromString(GeoPolicy.Bundle.serializer(), """{"time":5,"files":{}}""")
        assertEquals(emptyMap<String, GeoPolicy.RemoteFile>(), older.remote)
    }
}
