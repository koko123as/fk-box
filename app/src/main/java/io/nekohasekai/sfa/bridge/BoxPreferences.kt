package io.nekohasekai.sfa.bridge

internal object BoxPreferences {
    // Keep accepting the retired Connections key so older layouts and backups retain their other preferences.
    private val cards = setOf("Status", "Network speed", "Outbound mode", "Current profile", "Connections", "Proxy groups", "Memory", "Intranet IP", "System HTTP proxy")
    private val choices = mapOf(
        "theme" to setOf("system", "light", "dark"),
        "colorStyle" to setOf("vivid", "tinted", "soft", "monochrome"),
        "proxySort" to setOf("Default", "Name", "Latency"),
        "proxyLayout" to setOf("Tabs", "List", "Compact"),
        "proxyCardSize" to setOf("Compact", "Standard", "Loose"),
        "proxyDensity" to setOf("Tight", "Standard", "Loose"),
        "proxyIconStyle" to setOf("Filled", "Plain", "Hidden"),
        "restoreStrategy" to setOf("Compatible", "Override"),
        // The requests page's feed: recent and live connections, or captured HTTP.
        "requestFeed" to setOf("recent", "live", "captured"),
    )

    /**
     * Retired keys older settings and backups may still carry: the language
     * choice, and the appearance options of the earlier decorated UI
     * (tab animation, liquid glass, style, floating dock, sidebar width).
     */
    private val retired = setOf("locale", "tabAnimation", "liquidGlass", "appearanceStyle", "floatingNavigation", "sidebarExpanded")

    fun validate(values: Map<String, Any?>): Map<String, Any?> {
        val activeValues = values.filterKeys { it !in retired }
        activeValues.forEach { (key, value) ->
            val valid = when (key) {
                in choices -> value in choices.getValue(key)
                "closeConnectionsOnSelect", "hideTimeoutProxies", "pureBlack", "systemColor", "customFontScale", "railExtended" -> value is Boolean
                "proxyColumns" -> value is Number && value.toDouble() == value.toInt().toDouble() && value.toInt() in 1..4
                "proxyUnfolded" -> value is Map<*, *> && value.size <= 128 && value.all { (profile, groups) ->
                    profile is String && profile.length in 1..4096 && groups is List<*> && groups.size <= 4096 &&
                        groups.distinct().size == groups.size && groups.all { it is String && it.length in 1..4096 }
                }
                // A record page id to its grouping.
                "recordGroupings" -> value is Map<*, *> && value.size <= 16 && value.all { (page, grouping) ->
                    page is String && page.length in 1..64 && grouping in setOf("app", "host", "rule", "exit")
                }
                "fontScale" -> value is Number && value.toDouble().isFinite() && value.toDouble() in 0.8..1.4
                "fontFamily" -> value == null || value is String && value.length in 1..128
                "accentColors" -> value is List<*> && value.size <= 64 && value.distinct().size == value.size && value.all { it is Number && it.toLong() in 0..0xffffffffL }
                "color" -> value is Number && value.toLong() in 0..0xffffffffL
                "dashboardCards", "hiddenCards" -> value is List<*> && value.all { it in cards } &&
                    value.distinct().size == value.size &&
                    if (key == "dashboardCards") "Status" in value else "Status" !in value
                else -> false
            }
            require(valid) { "Invalid interface preference: " + key }
        }
        return activeValues
    }
}
