package io.nekohasekai.sfa.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class SubscriptionInfoTest {
    // Header parsing and the metadata a fetch leaves are tested in Rust
    // (crates/client-core/src/subscription.rs).

    @Test fun usedAndRemainingSaturate() {
        val quota = SubscriptionInfo(upload = 30, download = 70, total = 200)
        assertEquals(100L, quota.remaining)
        assertEquals(0L, SubscriptionInfo(upload = 300, total = 200).remaining)
        assertEquals(Long.MAX_VALUE, SubscriptionInfo(upload = Long.MAX_VALUE, download = 5).used)
        assertTrue(SubscriptionInfo().isEmpty)
    }

    @Test fun metadataRestoresAsOneUnit() {
        val profile = TypedProfile().apply {
            upload = 5; download = 6; total = 7; expire = 8; webPageUrl = "https://old.example.com"; autoUpdateInterval = 120
        }
        val saved = profile.subscriptionMetadata()
        profile.restoreSubscriptionMetadata(SubscriptionMetadata(0, 0, 0, 0, "", 120))
        assertNull(profile.subscriptionInfo)
        assertEquals("", profile.webPageUrl)
        profile.restoreSubscriptionMetadata(saved)
        assertEquals(SubscriptionInfo(5, 6, 7, 8), profile.subscriptionInfo)
    }

    // --- Parcel format ----------------------------------------------------

    private class Fields(vararg initial: Any?) : TypedProfile.FieldReader, TypedProfile.FieldWriter {
        val values = mutableListOf(*initial)
        private var position = 0
        override fun readInt() = values[position++] as Int
        override fun readLong() = values[position++] as Long
        override fun readString() = values[position++] as String?
        override fun writeInt(value: Int) { values.add(value) }
        override fun writeLong(value: Long) { values.add(value) }
        override fun writeString(value: String?) { values.add(value) }
    }

    @Test fun readsVersionOneUnchanged() {
        val profile = TypedProfile().apply { readFields(Fields(1, "/data/a.json", 1, "https://example.com/sub", 1, 1234L, 180)) }
        assertEquals("/data/a.json", profile.path)
        assertEquals(TypedProfile.Type.Remote, profile.type)
        assertEquals("https://example.com/sub", profile.remoteURL)
        assertTrue(profile.autoUpdate)
        assertEquals(Date(1234L), profile.lastUpdated)
        assertEquals(180, profile.autoUpdateInterval)
        assertEquals(TypedProfile.CORE_AUTO, profile.core)
        assertNull(profile.subscriptionInfo)
        assertEquals("", profile.webPageUrl)
        // A non-default interval written before provider hints existed was the user's.
        assertTrue(profile.intervalUserSet)
    }

    @Test fun readsVersionZeroAndTwoUnchanged() {
        val zero = TypedProfile().apply { readFields(Fields(0, "/data/z.json", 0, "", 0, 5L)) }
        assertEquals(TypedProfile.Type.Local, zero.type)
        assertEquals(60, zero.autoUpdateInterval)
        assertFalse(zero.intervalUserSet)
        val two = TypedProfile().apply { readFields(Fields(2, "/data/b.yaml", 1, "https://example.com/c", 0, 99L, 60, "mihomo")) }
        assertEquals(TypedProfile.CORE_MIHOMO, two.core)
        assertEquals(60, two.autoUpdateInterval)
        assertFalse(two.intervalUserSet)
        assertFalse(two.autoUpdate)
        val unknownCore = TypedProfile().apply { readFields(Fields(2, "/p", 0, "", 0, 0L, 60, "clash")) }
        assertEquals(TypedProfile.CORE_AUTO, unknownCore.core)
    }

    @Test fun versionThreeRoundTripsEveryField() {
        val original = TypedProfile().apply {
            path = "/data/c.json"; type = TypedProfile.Type.Remote; remoteURL = "https://example.com/x"
            autoUpdate = true; lastUpdated = Date(42L); autoUpdateInterval = 720; core = TypedProfile.CORE_XRAY
            upload = 1; download = 2; total = 3; expire = 4; webPageUrl = "https://example.com"; intervalUserSet = false
        }
        val fields = Fields()
        original.writeFields(fields)
        assertEquals(TypedProfile.PARCEL_VERSION, fields.values.first())
        assertEquals(3, TypedProfile.PARCEL_VERSION)
        val read = TypedProfile().apply { readFields(fields) }
        val copy = original.copy()
        for (profile in listOf(read, copy)) {
            assertEquals(original.path, profile.path)
            assertEquals(original.type, profile.type)
            assertEquals(original.remoteURL, profile.remoteURL)
            assertEquals(original.autoUpdate, profile.autoUpdate)
            assertEquals(original.lastUpdated, profile.lastUpdated)
            assertEquals(original.autoUpdateInterval, profile.autoUpdateInterval)
            assertEquals(original.core, profile.core)
            assertEquals(original.subscriptionMetadata(), profile.subscriptionMetadata())
            assertEquals(original.intervalUserSet, profile.intervalUserSet)
        }
    }
}
