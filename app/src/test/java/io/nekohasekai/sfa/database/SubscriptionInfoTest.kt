package io.nekohasekai.sfa.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class SubscriptionInfoTest {
    @Test fun parsesUserInfoWithSpacesCaseAndMissingKeys() {
        assertEquals(
            SubscriptionInfo(upload = 1024, download = 2048, total = 10_737_418_240, expire = 1_790_000_000),
            SubscriptionHeaders.parseUserInfo(" upload=1024; Download = 2048 ;TOTAL=10737418240; expire=1790000000 "),
        )
        assertEquals(SubscriptionInfo(download = 5, total = 100), SubscriptionHeaders.parseUserInfo("download=5; total=100"))
        assertEquals(SubscriptionInfo(upload = 1, total = 3), SubscriptionHeaders.parseUserInfo("upload=1;;total=3;"))
    }

    @Test fun parsesFloatsAndIgnoresBadValues() {
        assertEquals(SubscriptionInfo(upload = 12, download = 0, total = 1_000_000_000), SubscriptionHeaders.parseUserInfo("upload=12.9; download=-4; total=1e9; expire=never"))
        assertEquals(Long.MAX_VALUE, SubscriptionHeaders.parseUserInfo("total=1e30")!!.total)
    }

    @Test fun noUsableHeaderMeansNoInfo() {
        assertNull(SubscriptionHeaders.parseUserInfo(null))
        assertNull(SubscriptionHeaders.parseUserInfo(""))
        assertNull(SubscriptionHeaders.parseUserInfo("   "))
        assertNull(SubscriptionHeaders.parseUserInfo("foo=1; bar"))
        assertNull(SubscriptionHeaders.parseUserInfo("upload=abc"))
    }

    @Test fun expireZeroIsNoExpiry() {
        val info = SubscriptionHeaders.parseUserInfo("upload=0; download=0; total=0; expire=0")!!
        assertTrue(info.isEmpty)
        assertNull(info.usage)
        val quota = SubscriptionHeaders.parseUserInfo("upload=30; download=70; total=200; expire=0")!!
        assertEquals(0L, quota.expire)
        assertEquals(0.5, quota.usage!!, 1e-9)
        assertEquals(100L, quota.remaining)
        assertEquals(1.0, SubscriptionInfo(upload = 300, total = 200).usage!!, 1e-9)
        assertEquals(0L, SubscriptionInfo(upload = 300, total = 200).remaining)
    }

    @Test fun updateIntervalIsHoursClampedToAndroidMinimum() {
        assertEquals(720, SubscriptionHeaders.parseUpdateIntervalMinutes("12"))
        assertEquals(90, SubscriptionHeaders.parseUpdateIntervalMinutes(" 1.5 "))
        assertEquals(15, SubscriptionHeaders.parseUpdateIntervalMinutes("0.1"))
        assertEquals(SubscriptionHeaders.MAX_INTERVAL_MINUTES, SubscriptionHeaders.parseUpdateIntervalMinutes("1000000"))
        assertNull(SubscriptionHeaders.parseUpdateIntervalMinutes("0"))
        assertNull(SubscriptionHeaders.parseUpdateIntervalMinutes("-3"))
        assertNull(SubscriptionHeaders.parseUpdateIntervalMinutes("daily"))
        assertNull(SubscriptionHeaders.parseUpdateIntervalMinutes(null))
    }

    @Test fun webPageAcceptsOnlyHttpAndHttps() {
        assertEquals("https://example.com/user", SubscriptionHeaders.parseWebPageUrl(" https://example.com/user "))
        assertEquals("http://panel.example.com", SubscriptionHeaders.parseWebPageUrl("http://panel.example.com"))
        assertEquals("", SubscriptionHeaders.parseWebPageUrl("javascript:alert(1)"))
        assertEquals("", SubscriptionHeaders.parseWebPageUrl("intent://example.com"))
        assertEquals("", SubscriptionHeaders.parseWebPageUrl("https://"))
        assertEquals("", SubscriptionHeaders.parseWebPageUrl("not a url"))
        assertEquals("", SubscriptionHeaders.parseWebPageUrl(null))
    }

    @Test fun providerIntervalAppliesOnlyWhileUserHasNotChosenOne() {
        val profile = TypedProfile().apply { autoUpdateInterval = 60 }
        val fetch = SubscriptionHeaders.fetch("{}", "upload=1; download=2; total=3", "24", "https://example.com")
        assertEquals(SubscriptionMetadata(1, 2, 3, 0, "https://example.com", 1440), profile.metadataAfter(fetch))
        profile.intervalUserSet = true
        assertEquals(60, profile.metadataAfter(fetch).autoUpdateInterval)
    }

    @Test fun fetchWithoutHeadersClearsStaleInfo() {
        val profile = TypedProfile().apply {
            upload = 5; download = 6; total = 7; expire = 8; webPageUrl = "https://old.example.com"; autoUpdateInterval = 120
        }
        assertEquals(SubscriptionMetadata(0, 0, 0, 0, "", 120), profile.metadataAfter(SubscriptionHeaders.fetch("{}", null, null, null)))
        profile.restoreSubscriptionMetadata(profile.metadataAfter(SubscriptionHeaders.fetch("{}", "", "", "")))
        assertNull(profile.subscriptionInfo)
        assertEquals("", profile.webPageUrl)
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
