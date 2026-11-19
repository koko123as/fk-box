package io.nekohasekai.sfa.database

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class ProfileStoreCodecTest {
    private fun remote() = Profile(id = 7L, userOrder = 2L, name = "Remote", icon = null).apply {
        typed.path = "/data/user/0/io.fkbox.app/files/configs/7.json"
        typed.type = TypedProfile.Type.Remote
        typed.remoteURL = "https://example.com/sub"
        typed.lastUpdated = Date(1_763_600_000_123L)
        typed.autoUpdate = true
        typed.autoUpdateInterval = 180
        typed.core = TypedProfile.CORE_XRAY
        typed.upload = 1L
        typed.download = 2L
        typed.total = 5_000_000_000_000L
        typed.expire = 1_790_000_000L
        typed.webPageUrl = "https://example.com"
        typed.intervalUserSet = true
    }

    private fun local() = Profile(id = 3L, userOrder = 0L, name = "Local", icon = "").apply {
        typed.path = "/data/user/0/io.fkbox.app/files/configs/3.json"
    }

    private fun assertSame(expected: Profile, actual: Profile) {
        assertEquals(ProfileStoreCodec.encode(expected), ProfileStoreCodec.encode(actual))
    }

    @Test fun profilesRoundTripEveryField() {
        for (profile in listOf(remote(), local())) {
            val decoded = ProfileStoreCodec.decodeProfile(ProfileStoreCodec.parse(ProfileStoreCodec.encode(profile).toString()))
            assertSame(profile, decoded)
            assertEquals(profile.icon, decoded.icon)
            assertEquals(profile.typed.lastUpdated, decoded.typed.lastUpdated)
            assertEquals(profile.typed.type, decoded.typed.type)
            assertEquals(profile.typed.total, decoded.typed.total)
            assertEquals(profile.typed.intervalUserSet, decoded.typed.intervalUserSet)
        }
        // null and "" icons stay distinct.
        assertNull(ProfileStoreCodec.decodeProfile(ProfileStoreCodec.encode(remote())).icon)
        assertEquals("", ProfileStoreCodec.decodeProfile(ProfileStoreCodec.encode(local())).icon)
    }

    @Test fun serversRoundTrip() {
        val server = RemoteServer(4L, 1L, "Box", "192.168.1.2:9090", "secret")
        val decoded = ProfileStoreCodec.decodeServers(ProfileStoreCodec.encodeServers(listOf(server))).single()
        assertEquals(ProfileStoreCodec.encode(server), ProfileStoreCodec.encode(decoded))
    }

    @Test fun missingOrMistypedFieldsAreErrors() {
        val row = ProfileStoreCodec.encode(remote())
        val withoutName = buildJsonObject { row.filterKeys { it != "name" }.forEach { (k, v) -> put(k, v) } }
        assertTrue(runCatching { ProfileStoreCodec.decodeProfile(withoutName) }.isFailure)
        val typed = row["typed"]!!.jsonObject
        val numericPath = buildJsonObject {
            row.forEach { (k, v) -> put(k, v) }
            put("typed", buildJsonObject { typed.forEach { (k, v) -> put(k, v) }; put("path", 1) })
        }
        assertTrue(runCatching { ProfileStoreCodec.decodeProfile(numericPath) }.isFailure)
        val withoutTyped = buildJsonObject { row.filterKeys { it != "typed" }.forEach { (k, v) -> put(k, v) } }
        assertTrue(runCatching { ProfileStoreCodec.decodeProfile(withoutTyped) }.isFailure)
    }

    @Test fun importDatasetCarriesSequencesAndRows() {
        val dataset = ProfileStoreCodec.parse(
            ProfileStoreCodec.importDataset(9L, 2L, listOf(remote(), local()), listOf(RemoteServer(2L, 0L, "", "h", ""))),
        ).jsonObject
        assertEquals("9", dataset["profileSeq"].toString())
        assertEquals("2", dataset["serverSeq"].toString())
        assertEquals(2, dataset["profiles"]!!.jsonArray.size)
        assertEquals(1, dataset["servers"]!!.jsonArray.size)
    }

    private fun readBack(profiles: List<Profile>, servers: List<RemoteServer> = emptyList()) = buildJsonObject {
        put("sha256", "00")
        put("profiles", JsonArray(profiles.sortedWith(compareBy({ it.userOrder }, { it.id })).map(ProfileStoreCodec::encode)))
        put("servers", JsonArray(servers.map(ProfileStoreCodec::encode)))
    }.toString()

    @Test fun importCheckAcceptsAnExactCopyInListOrder() {
        val rows = listOf(local(), remote())
        val server = RemoteServer(2L, 0L, "Box", "h", "s")
        assertNull(ProfileStoreCodec.importMismatch(readBack(rows, listOf(server)), rows, listOf(server)))
        assertNull(ProfileStoreCodec.importMismatch(readBack(emptyList()), emptyList(), emptyList()))
    }

    @Test fun importCheckRejectsAnyDifference() {
        val rows = listOf(local(), remote())
        assertNotNull(ProfileStoreCodec.importMismatch(readBack(listOf(local())), rows, emptyList()))
        val changed = remote().apply { typed.expire = 1L }
        assertNotNull(ProfileStoreCodec.importMismatch(readBack(listOf(local(), changed)), rows, emptyList()))
        val renamed = remote().apply { name = "Other" }
        assertNotNull(ProfileStoreCodec.importMismatch(readBack(listOf(local(), renamed)), rows, emptyList()))
        val otherId = remote().apply { id = 8L }
        assertNotNull(ProfileStoreCodec.importMismatch(readBack(listOf(local(), otherId)), rows, emptyList()))
        val iconSet = local().apply { icon = null }
        assertNotNull(ProfileStoreCodec.importMismatch(readBack(listOf(iconSet, remote())), rows, emptyList()))
        assertNotNull(ProfileStoreCodec.importMismatch(readBack(rows), rows, listOf(RemoteServer(1L, 0L, "", "h", ""))))
    }
}
