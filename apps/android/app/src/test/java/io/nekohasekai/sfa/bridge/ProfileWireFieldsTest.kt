package io.nekohasekai.sfa.bridge

import io.nekohasekai.sfa.database.SubscriptionMetadata
import io.nekohasekai.sfa.database.TypedProfile
import io.nekohasekai.sfa.database.subscriptionMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class ProfileWireFieldsTest {
    private fun remote(core: String) = TypedProfile().apply {
        type = TypedProfile.Type.Remote; remoteURL = "https://example.com/sub"; autoUpdate = true
        autoUpdateInterval = 720; lastUpdated = Date(1_700_000_000_000L); this.core = core
        upload = 10; download = 20; total = 300; expire = 1_800_000_000; webPageUrl = "https://example.com/panel"
    }

    @Test fun backupRoundTripKeepsCoreAndSubscription() {
        for (core in TypedProfile.CORES) {
            val source = remote(core)
            val restored = TypedProfile()
            ProfileWireFields.validate(ProfileWireFields.export(source))
            ProfileWireFields.restore(restored, ProfileWireFields.export(source))
            assertEquals(core, restored.core)
            assertEquals(source.remoteURL, restored.remoteURL)
            assertEquals(TypedProfile.Type.Remote, restored.type)
            assertTrue(restored.autoUpdate)
            assertEquals(source.lastUpdated, restored.lastUpdated)
            assertEquals(source.subscriptionMetadata(), restored.subscriptionMetadata())
            assertEquals(source.intervalUserSet, restored.intervalUserSet)
        }
    }

    @Test fun oldBackupWithoutCoreRestoresAuto() {
        val restored = TypedProfile().apply { core = TypedProfile.CORE_MIHOMO; upload = 5; total = 9 }
        val entry = mapOf<String, Any?>("name" to "Old", "url" to "https://example.com/sub", "autoUpdate" to true, "interval" to 60, "updatedAt" to 5L)
        ProfileWireFields.validate(entry)
        ProfileWireFields.restore(restored, entry)
        assertEquals(TypedProfile.CORE_AUTO, restored.core)
        assertEquals(SubscriptionMetadata(0, 0, 0, 0, "", 60), restored.subscriptionMetadata())
        assertFalse(restored.intervalUserSet)
        val custom = TypedProfile().apply { ProfileWireFields.restore(this, entry + ("interval" to 240)) }
        assertTrue(custom.intervalUserSet)
    }

    @Test fun localProfilesCarryNoSubscriptionInfo() {
        val local = TypedProfile().apply { core = TypedProfile.CORE_XRAY }
        val wire = ProfileWireFields.export(local)
        assertNull(wire["total"])
        assertNull(wire["webPageUrl"])
        val restored = TypedProfile().apply { ProfileWireFields.restore(this, wire + mapOf("total" to 99L, "webPageUrl" to "https://x.example")) }
        assertEquals(TypedProfile.Type.Local, restored.type)
        assertEquals(TypedProfile.CORE_XRAY, restored.core)
        assertEquals(0L, restored.total)
        assertEquals("", restored.webPageUrl)
    }

    @Test fun rejectsMalformedBackupFields() {
        assertThrows(IllegalArgumentException::class.java) { ProfileWireFields.validate(mapOf("core" to "clash")) }
        assertThrows(IllegalArgumentException::class.java) { ProfileWireFields.validate(mapOf("total" to -1)) }
        assertThrows(IllegalArgumentException::class.java) { ProfileWireFields.validate(mapOf("upload" to "1")) }
        assertThrows(IllegalArgumentException::class.java) { ProfileWireFields.validate(mapOf("intervalUserSet" to "yes")) }
        val unsafe = TypedProfile().apply { ProfileWireFields.restore(this, mapOf("url" to "https://example.com/s", "webPageUrl" to "javascript:alert(1)")) }
        assertEquals("", unsafe.webPageUrl)
    }
}
