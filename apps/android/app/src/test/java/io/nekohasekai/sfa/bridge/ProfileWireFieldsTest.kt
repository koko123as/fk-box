package io.nekohasekai.sfa.bridge

import io.nekohasekai.sfa.database.TypedProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Date

/** The export side; backup validation and restore are tested in client-core (backup.rs). */
class ProfileWireFieldsTest {
    private fun remote(core: String) = TypedProfile().apply {
        type = TypedProfile.Type.Remote; remoteURL = "https://example.com/sub"; autoUpdate = true
        autoUpdateInterval = 720; lastUpdated = Date(1_700_000_000_000L); this.core = core
        upload = 10; download = 20; total = 300; expire = 1_800_000_000; webPageUrl = "https://example.com/panel"
    }

    @Test fun exportCarriesCoreAndSubscription() {
        for (core in TypedProfile.CORES) {
            val wire = ProfileWireFields.export(remote(core))
            assertEquals(core, wire["core"])
            assertEquals(true, wire["remote"])
            assertEquals(1_700_000_000_000L, wire["updatedAt"])
            assertEquals(300L, wire["total"])
            assertEquals("https://example.com/panel", wire["webPageUrl"])
        }
    }

    @Test fun localProfilesCarryNoSubscriptionInfo() {
        val wire = ProfileWireFields.export(TypedProfile().apply { core = TypedProfile.CORE_XRAY })
        assertNull(wire["total"])
        assertNull(wire["webPageUrl"])
        assertEquals(false, wire["remote"])
    }
}
