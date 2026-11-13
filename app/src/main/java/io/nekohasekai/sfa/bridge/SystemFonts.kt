package io.nekohasekai.sfa.bridge

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.text.TextRunShaper
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * The font files Android itself draws the UI text with, for the Slint UI
 * (ui-slint/src/fonts.rs): the default sans-serif face (a vendor system font
 * such as Flyme's included), the face Chinese text falls back to, and the
 * monospace face. Shaping a sample with each typeface reports the exact file
 * and collection index of every glyph (TextRunShaper, API 31+). Written to
 * files/fkui-fonts.json before the native UI starts; below API 31 nothing is
 * written and the UI uses fontique's own /system/fonts defaults.
 */
object SystemFonts {
    const val FILE = "fkui-fonts.json"

    fun write(context: Context) {
        val target = File(context.filesDir, FILE)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            target.delete()
            return
        }
        try {
            val locale = Locale.getDefault()
            // Han glyphs follow the device locale; other languages still show Chinese in Simplified forms.
            val han = if (locale.language == "zh") locale else Locale.SIMPLIFIED_CHINESE
            val sans = listOf(
                Typeface.DEFAULT,
                Typeface.create(Typeface.DEFAULT, 500, false),
                Typeface.create(Typeface.DEFAULT, 600, false),
                Typeface.DEFAULT_BOLD,
            )
            val json = JSONObject()
                .put("sans", faces(sans, "AaGg0123", locale))
                .put("cjk", faces(sans, "中文汉字设置", han))
                .put("mono", faces(listOf(Typeface.MONOSPACE), "Ag0", locale))
            val temp = File(context.filesDir, "$FILE.tmp")
            temp.writeText(json.toString())
            temp.renameTo(target)
        } catch (e: Throwable) {
            Log.w("FKui", "system font probe failed", e)
            target.delete()
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
    private fun faces(typefaces: List<Typeface>, sample: String, locale: Locale): JSONArray {
        val seen = LinkedHashSet<Pair<String, Int>>()
        for (typeface in typefaces) {
            val paint = Paint().apply {
                this.typeface = typeface
                textSize = 32f
                textLocale = locale
            }
            val glyphs = TextRunShaper.shapeTextRun(sample, 0, sample.length, 0, sample.length, 0f, 0f, false, paint)
            for (i in 0 until glyphs.glyphCount()) {
                val font = glyphs.getFont(i)
                val file = font.file ?: continue
                seen.add(file.absolutePath to font.ttcIndex)
            }
        }
        return JSONArray().apply {
            for ((path, index) in seen) put(JSONObject().put("path", path).put("index", index))
        }
    }
}
