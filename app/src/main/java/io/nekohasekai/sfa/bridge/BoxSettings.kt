package io.nekohasekai.sfa.bridge

import io.nekohasekai.sfa.bg.GeoPolicy

/** Shared validation for individual edits and complete backup imports. */
internal object BoxSettings {
    private val booleans = setOf(
        "perAppEnabled", "perAppManaged", "allowBypass", "autoRedirect", "dynamicNotification",
        "disableDeprecatedWarnings", "singBoxEnabled", "xrayEnabled", "mihomoEnabled", "oomKillerEnabled", "oomKillerDisabled", "powerReportEnabled", "geoAutoUpdate",
    )

    /** The memory limit as the dialog edits it: a whole number of MiB, 50 when unset. */
    fun memoryLimitText(value: Any?): String = ((value as? Number)?.toInt() ?: 50).toString()

    /** A typed memory limit (spaces and a trailing "MiB"/"MB" tolerated), or null outside 10–4096. */
    fun parseMemoryLimit(text: String): Int? =
        text.trim().removeSuffix("MiB").removeSuffix("MB").trim().toIntOrNull()?.takeIf { it in 10..4096 }

    fun validate(values: Map<String, Any?>): Map<String, Any?> {
        values.forEach { (key, value) ->
            val valid = when (key) {
                in booleans -> value is Boolean
                "perAppMode" -> value is Number && value.toDouble() in listOf(1.0, 2.0)
                "perAppList" -> value is List<*> && value.size <= 10000 && value.all { it is String && it.isNotBlank() && it.length < 512 }
                "oomMemoryLimitMB" -> value is Number && value.toDouble() == value.toInt().toDouble() && value.toInt() in 10..4096
                "queryMode" -> value in listOf("ROOT", "SHIZUKU")
                "geoUpdateInterval" -> value is Number && value.toDouble() == value.toInt().toDouble() && value.toInt() in 1..8760
                "routingRules" -> value is Map<*, *> && CoreRouting.validWire(value)
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
