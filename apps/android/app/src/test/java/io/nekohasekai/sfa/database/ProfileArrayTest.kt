package io.nekohasekai.sfa.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileArrayTest {
    @Test fun namesByRemarksWithSourceAndIndexFallback() {
        assertEquals("Node A", ProfileArray.name("configs", "  Node A ", 0))
        assertEquals("configs #3", ProfileArray.name("configs", "", 2))
        assertEquals("configs #2", ProfileArray.name(" configs ", "   ", 1))
        assertEquals("Profile #1", ProfileArray.name("", "", 0))
    }

    @Test fun entriesKeepOrderContentAndPerElementCore() {
        val entries = ProfileArray.entries(
            "list",
            listOf(
                ProfileArray.Element("a", "{\"x\":1}", "xray"),
                ProfileArray.Element("", "{\"y\":2}", "sing-box"),
            ),
        )
        assertEquals(
            listOf(
                ProfileArray.Entry("a", "{\"x\":1}", "xray"),
                ProfileArray.Entry("list #2", "{\"y\":2}", "sing-box"),
            ),
            entries,
        )
    }

    @Test fun mergedSingleElementTakesTheSourceName() {
        assertEquals(
            listOf(ProfileArray.Entry("MASQUE-To-Xray-H3", "{}", "xray")),
            ProfileArray.entries(" MASQUE-To-Xray-H3 ", listOf(ProfileArray.Element("", "{}", "xray"))),
        )
        assertEquals("Profile", ProfileArray.entries("", listOf(ProfileArray.Element(" ", "{}", "xray"))).single().name)
        // A lone element that has remarks keeps them.
        assertEquals("Node", ProfileArray.entries("src", listOf(ProfileArray.Element("Node", "{}", "xray"))).single().name)
    }

    @Test fun selectFollowsSavedRemarksThenNameThenFallbackIndex() {
        val remarks = listOf("alpha", "", "beta", "")
        // The saved content's remarks win, even after the user renamed the profile.
        assertEquals(2, ProfileArray.select(remarks, "beta", "renamed"))
        assertEquals(0, ProfileArray.select(remarks, "", " alpha "))
        assertEquals(1, ProfileArray.select(remarks, "", "sub #2"))
        assertEquals(3, ProfileArray.select(remarks, "gone", "sub #4"))
        // The fallback index must point at an element without remarks.
        assertNull(ProfileArray.select(remarks, "", "sub #3"))
        assertNull(ProfileArray.select(remarks, "", "sub #9"))
        assertNull(ProfileArray.select(remarks, "missing", "other"))
        assertEquals(0, ProfileArray.select(listOf("only"), "changed", "renamed"))
    }
}
