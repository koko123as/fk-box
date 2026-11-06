package io.nekohasekai.sfa.flutter

import io.nekohasekai.sfa.bg.GeoPolicy

/** Shared validation for individual edits and complete backup imports. */
internal object BoxSettings {
    private val booleans = setOf(
        "perAppEnabled", "perAppManaged", "allowBypass", "autoRedirect", "dynamicNotification",
        "disableDeprecatedWarnings", "singBoxEnabled", "xrayEnabled", "mihomoEnabled", "oomKillerEnabled", "oomKillerDisabled", "powerReportEnabled", "geoAutoUpdate",
    )

    fun validate(values: Map<String, Any?>): Map<String, Any?> {
        values.forEach { (key, value) ->
            val valid = when (key) {
                in booleans -> value is Boolean
                "perAppMode" -> value is Number && value.toDouble() in listOf(1.0, 2.0)
                "perAppList" -> value is List<*> && value.size <= 10000 && value.all { it is String && it.isNotBlank() && it.length < 512 }
                "oomMemoryLimitMB" -> value is Number && value.toDouble() == value.toInt().toDouble() && value.toInt() in 10..4096
                "queryMode" -> value in listOf("ROOT", "SHIZUKU")
                "geoUpdateInterval" -> value is Number && value.toDouble() == value.toInt().toDouble() && value.toInt() in 1..8760
                "geoUrls" -> value is Map<*, *> && value.all { (name, url) ->
                    name in setOf("geoip", "geosite", "mmdb", "asn") && url is String && GeoPolicy.validUrl(url)
                }
                else -> false
            }
            require(valid) { "Invalid service setting: $key" }
        }
        return values
    }
}
