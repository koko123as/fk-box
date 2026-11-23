package io.nekohasekai.sfa.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Interface preference validation; the service settings' checks are client-core's (service_settings.rs tests). */
class BoxPreferencesTest {
    @Test
    fun preferencesRejectInventedFeaturesAndMissingStatusCard() {
        assertThrows(IllegalArgumentException::class.java) { BoxPreferences.validate(mapOf("clashScript" to "code")) }
        assertThrows(IllegalArgumentException::class.java) { BoxPreferences.validate(mapOf("hiddenCards" to listOf("Status"))) }
        assertThrows(IllegalArgumentException::class.java) { BoxPreferences.validate(mapOf("proxyColumns" to 1.5)) }
        BoxPreferences.validate(mapOf("theme" to "dark", "proxyColumns" to 2, "hiddenCards" to listOf("Memory")))
    }

    @Test
    fun retiredAppearanceOptionsAreDroppedWithoutRejectingTheRest() {
        val current = mapOf("theme" to "dark", "pureBlack" to true)
        val retired = mapOf(
            "appearanceStyle" to "minimal",
            "liquidGlass" to "ultra",
            "tabAnimation" to "fade",
            "floatingNavigation" to false,
            "sidebarExpanded" to true,
        )
        assertEquals(current, BoxPreferences.validate(current + retired))
    }

    @Test
    fun retiredLanguageChoicePreservesOtherPreferences() {
        val current = mapOf("theme" to "dark", "fontScale" to 1.4, "proxyLayout" to "List")
        for (locale in listOf("system", "en", "zh")) {
            assertEquals(current, BoxPreferences.validate(current + ("locale" to locale)))
        }
    }

    @Test
    fun systemHttpProxyCardLayoutSurvivesPreferenceAndBackupValidation() {
        val shown = mapOf("dashboardCards" to listOf("System HTTP proxy", "Status"), "hiddenCards" to listOf("Memory"))
        assertEquals(shown, BoxPreferences.validate(shown))
        val hidden = shown + ("hiddenCards" to listOf("Memory", "System HTTP proxy"))
        assertEquals(hidden, BoxPreferences.validate(hidden))
    }

    @Test
    fun tabletDashboardCardsSurvivePreferenceValidation() {
        val layout = mapOf(
            "dashboardCards" to listOf("Status", "Network speed", "Outbound mode", "VPN", "Intranet IP", "Current profile", "Service status", "Memory", "Connection stats", "Proxy groups"),
            "hiddenCards" to listOf("System HTTP proxy"),
        )
        assertEquals(layout, BoxPreferences.validate(layout))
    }
}
