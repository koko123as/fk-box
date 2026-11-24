package io.nekohasekai.sfa.database

import io.nekohasekai.sfa.bg.ProxyService
import io.nekohasekai.sfa.bg.VPNService
import io.nekohasekai.sfa.constant.ServiceMode
import io.nekohasekai.sfa.constant.SettingsKey
import io.nekohasekai.sfa.database.preference.StorePreferenceDataStore
import io.nekohasekai.sfa.ktx.boolean
import io.nekohasekai.sfa.ktx.int
import io.nekohasekai.sfa.ktx.long
import io.nekohasekai.sfa.ktx.map
import io.nekohasekai.sfa.ktx.string
import io.nekohasekai.sfa.ktx.stringSet
import java.io.File

/** Service settings, kept in the client store ([CoreStore]). */
object Settings {
    val dataStore = StorePreferenceDataStore()
    var selectedProfile by dataStore.long(SettingsKey.SELECTED_PROFILE) { -1L }
    var serviceMode by dataStore.string(SettingsKey.SERVICE_MODE) { ServiceMode.NORMAL }
    var startedByUser by dataStore.boolean(SettingsKey.STARTED_BY_USER)

    var dynamicNotification by dataStore.boolean(SettingsKey.DYNAMIC_NOTIFICATION) { true }

    // Kill switch (Settings › Service): keep the VPN up and drop traffic while
    // the core starts or after it fails, instead of letting traffic bypass it.
    var killSwitch by dataStore.boolean(SettingsKey.KILL_SWITCH) { false }
    var disableDeprecatedWarnings by dataStore.boolean(SettingsKey.DISABLE_DEPRECATED_WARNINGS) { false }

    // The per-core switches (SettingsKey.CORE_*_ENABLED) went with the
    // three-core libbox in stage 6: one kernel runs every profile. The stored
    // values are left as they are (a downgrade still reads them).

    // Geo resources (SettingsKey.GEO_*) and the per-core routing rules
    // (SettingsKey.ROUTING_RULES) are read and written by client-core
    // (geo.rs, routing.rs, service_settings.rs) through ServiceSettings and
    // GeoPolicy, not through this cache.

    const val PER_APP_PROXY_EXCLUDE = 1
    const val PER_APP_PROXY_INCLUDE = 2

    var autoRedirect by dataStore.boolean(SettingsKey.AUTO_REDIRECT) { false }
    var perAppProxyEnabled by dataStore.boolean(SettingsKey.PER_APP_PROXY_ENABLED) { false }
    var perAppProxyMode by dataStore.int(SettingsKey.PER_APP_PROXY_MODE) { PER_APP_PROXY_EXCLUDE }
    var perAppProxyList by dataStore.stringSet(SettingsKey.PER_APP_PROXY_LIST) { emptySet() }
    var perAppProxyManagedMode by dataStore.boolean(SettingsKey.PER_APP_PROXY_MANAGED_MODE) { false }
    var perAppProxyManagedList by dataStore.stringSet(SettingsKey.PER_APP_PROXY_MANAGED_LIST) { emptySet() }

    const val PACKAGE_QUERY_MODE_SHIZUKU = "SHIZUKU"
    const val PACKAGE_QUERY_MODE_ROOT = "ROOT"
    var perAppProxyPackageQueryMode by dataStore.string(SettingsKey.PER_APP_PROXY_PACKAGE_QUERY_MODE) { PACKAGE_QUERY_MODE_SHIZUKU }

    fun getEffectivePerAppProxyMode(): Int = if (perAppProxyManagedMode) {
        PER_APP_PROXY_EXCLUDE
    } else {
        perAppProxyMode
    }

    fun getEffectivePerAppProxyList(): Set<String> = if (perAppProxyManagedMode) {
        perAppProxyManagedList
    } else {
        perAppProxyList
    }

    var allowBypass by dataStore.boolean(SettingsKey.ALLOW_BYPASS) { false }
    var systemProxyEnabled by dataStore.boolean(SettingsKey.SYSTEM_PROXY_ENABLED) { true }

    var oomKillerEnabled by dataStore.boolean(SettingsKey.OOM_KILLER_ENABLED) { false }
    var oomKillerDisabled by dataStore.boolean(SettingsKey.OOM_KILLER_DISABLED) { true }
    var oomMemoryLimitMB by dataStore.int(SettingsKey.OOM_MEMORY_LIMIT_MB) { 50 }

    var powerReportEnabled by dataStore.boolean(SettingsKey.POWER_REPORT_ENABLED) { false }


    var activeRemoteServerId by dataStore.long(SettingsKey.ACTIVE_REMOTE_SERVER_ID) { 0L }

    // Tailscale SSH
    var tailscaleSSHRememberedUsernames by dataStore.map(SettingsKey.TAILSCALE_SSH_REMEMBERED_USERNAMES)
    var tailscaleSSHRememberedTerminalTypes by dataStore.map(SettingsKey.TAILSCALE_SSH_REMEMBERED_TERMINAL_TYPES)
    var tailscaleSSHQuickConnectPeers by dataStore.stringSet(SettingsKey.TAILSCALE_SSH_QUICK_CONNECT_PEERS)
    var tailscaleSSHLightTheme by dataStore.string(SettingsKey.TAILSCALE_SSH_LIGHT_THEME) { "Alabaster" }
    var tailscaleSSHDarkTheme by dataStore.string(SettingsKey.TAILSCALE_SSH_DARK_THEME) { "Afterglow" }
    var tailscaleSSHFontFamily by dataStore.string(SettingsKey.TAILSCALE_SSH_FONT_FAMILY)
    var tailscaleSSHFontSize by dataStore.int(SettingsKey.TAILSCALE_SSH_FONT_SIZE) { 14 }
    var tailscaleSSHCustomFontPath by dataStore.string(SettingsKey.TAILSCALE_SSH_CUSTOM_FONT_PATH)
    var tailscaleSSHLightConfig by dataStore.string(SettingsKey.TAILSCALE_SSH_LIGHT_CONFIG)
    var tailscaleSSHDarkConfig by dataStore.string(SettingsKey.TAILSCALE_SSH_DARK_CONFIG)
    var tailscaleSSHFontFollowTheme by dataStore.boolean(SettingsKey.TAILSCALE_SSH_FONT_FOLLOW_THEME) { true }


    fun serviceClass(): Class<*> = when (serviceMode) {
        ServiceMode.VPN -> VPNService::class.java
        else -> ProxyService::class.java
    }

    suspend fun rebuildServiceMode() {
        val newMode = detectServiceMode()
        if (serviceMode != newMode) {
            serviceMode = newMode
        }
    }

    suspend fun detectServiceMode(): String {
        try {
            if (needVPNService()) {
                return ServiceMode.VPN
            }
        } catch (_: Exception) {
        }
        return ServiceMode.NORMAL
    }

    /**
     * The single-core kernel always runs a TUN inbound (the brain's generated
     * configuration), so every selected profile needs the VpnService.
     */
    private suspend fun needVPNService(): Boolean {
        val selectedProfileId = selectedProfile
        if (selectedProfileId == -1L) return false
        ProfileManager.get(selectedProfileId) ?: return false
        return true
    }
}
