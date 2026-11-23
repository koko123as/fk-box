package io.nekohasekai.sfa.bridge

import io.github.sagernet.libghostty.GhosttyTheme
import io.github.sagernet.libghostty.extras.GhosttyFontStore
import io.github.sagernet.libghostty.extras.GhosttyThemeStore
import io.nekohasekai.sfa.database.CoreStore
import io.nekohasekai.sfa.database.ServiceSettings
import io.nekohasekai.sfa.database.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID

/**
 * The terminal (Ghostty) settings. Their values, checks and writes are
 * `fkbox-client-core` (crates/client-core/src/tools/terminal.rs, which asks for the
 * theme list and font checks through [io.nekohasekai.sfa.database.CoreKernel]);
 * this class keeps what is Android's: the bundled theme catalog and previews,
 * picking, importing and deleting font files. Every value client-core writes
 * is put in the settings cache, which the terminal reads.
 */
internal class BoxTerminalSettings(private val activity: BoxActivity) {
    fun snapshot(): Map<String, Any?> = plainMap(CoreStore.call("terminal.snapshot")) + mapOf(
        "defaultLightTheme" to GhosttyThemeStore.DEFAULT_LIGHT_THEME, "defaultDarkTheme" to GhosttyThemeStore.DEFAULT_DARK_THEME,
    )

    suspend fun themes(dark: Boolean): List<Map<String, Any?>> = withContext(Dispatchers.IO) {
        GhosttyThemeStore.listThemes(activity, dark).map { name ->
            mapOf("name" to name) + themeWire(GhosttyThemeStore.loadTheme(activity, name))
        }
    }

    suspend fun fonts(): Map<String, Any?> = withContext(Dispatchers.IO) {
        mapOf("system" to listOf("monospace", "Droid Sans Mono", "Courier New", "Courier"),
            "imported" to GhosttyFontStore.listImportedFonts(activity).map { mapOf("name" to it.name, "path" to it.path) })
    }

    /** Null when the user dismissed the file picker. */
    suspend fun importFont(): Map<String, Any?>? {
        val uri = activity.pickDocument() ?: return null
        return withContext(Dispatchers.IO) {
            val (name, bytes) = activity.readDocument(uri)
            val extension = name.substringAfterLast('.', "").lowercase()
            require(extension in setOf("ttf", "otf", "ttc", "otc")) { "Select a TTF, OTF, TTC or OTC font" }
            val safeName = name.substringBeforeLast('.').replace(Regex("[^\\p{L}\\p{N} ._-]"), "_").take(80).ifBlank { "Font" }
            val file = File(File(activity.filesDir, "fonts").apply { mkdirs() }, "$safeName-${UUID.randomUUID().toString().take(8)}.$extension")
            try {
                file.writeBytes(bytes)
                require(GhosttyFontStore.loadTypeface(file.path) != null) { "Invalid font file" }
            } catch (e: Exception) { file.delete(); throw e }
            ServiceSettings.adopt(CoreStore.call("terminal.useFont") { put("path", file.path) }.jsonObject.getValue("written"))
            snapshot()
        }
    }

    suspend fun deleteFont(path: String?): Map<String, Any?> {
        withContext(Dispatchers.IO) {
            val font = GhosttyFontStore.listImportedFonts(activity).firstOrNull { it.path == path } ?: error("Font no longer exists")
            check(File(font.path).delete()) { "Unable to delete font" }
            ServiceSettings.adopt(CoreStore.call("terminal.fontDeleted") { put("path", font.path) }.jsonObject.getValue("written"))
        }
        return snapshot()
    }

    /** One terminal setting: [key] names the setting, as in a backup. */
    suspend fun set(key: String?, value: Any?): Map<String, Any?> = withContext(Dispatchers.IO) {
        Settings.dataStore.flush()
        val result = CoreStore.callKernel("terminal.set") {
            put("key", key ?: "")
            put("value", ServiceSettings.element(value))
        }.jsonObject
        ServiceSettings.adopt(result.getValue("written"))
        snapshot()
    }

    @Suppress("UNCHECKED_CAST")
    private fun plainMap(element: JsonElement): Map<String, Any?> = ServiceSettings.plain(element) as Map<String, Any?>

    private fun themeWire(theme: GhosttyTheme?): Map<String, Any?> = mapOf(
        "foreground" to theme?.foreground?.toLong()?.and(0xffffffffL),
        "background" to theme?.background?.toLong()?.and(0xffffffffL),
        "palette" to theme?.palette?.take(16)?.map { it?.toLong()?.and(0xffffffffL) },
    )
}
