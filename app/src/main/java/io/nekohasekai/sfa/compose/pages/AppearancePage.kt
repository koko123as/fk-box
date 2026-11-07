package io.nekohasekai.sfa.compose.pages

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.compose.BoxController
import io.nekohasekai.sfa.compose.Json
import io.nekohasekai.sfa.compose.asDouble
import io.nekohasekai.sfa.compose.theme.AppShape
import io.nekohasekai.sfa.compose.theme.AppTextStyle
import io.nekohasekai.sfa.compose.theme.BoxScheme
import io.nekohasekai.sfa.compose.theme.AppearanceStyle
import io.nekohasekai.sfa.compose.theme.BoxTheme
import io.nekohasekai.sfa.compose.theme.ColorStyle
import io.nekohasekai.sfa.compose.theme.SurfaceLevel
import io.nekohasekai.sfa.compose.theme.appFontFamily
import io.nekohasekai.sfa.compose.theme.seededColorScheme
import io.nekohasekai.sfa.compose.theme.tabular
import io.nekohasekai.sfa.compose.ui.AppGlyphs
import io.nekohasekai.sfa.compose.ui.BoxPage
import io.nekohasekai.sfa.compose.ui.CommonPopupBox
import io.nekohasekai.sfa.compose.ui.CommonScaffold
import io.nekohasekai.sfa.compose.ui.Glyph
import io.nekohasekai.sfa.compose.ui.GlyphButton
import io.nekohasekai.sfa.compose.ui.GlyphIcon
import io.nekohasekai.sfa.compose.ui.IconAction
import io.nekohasekai.sfa.compose.ui.ItemPosition
import io.nekohasekai.sfa.compose.ui.ListItem
import io.nekohasekai.sfa.compose.ui.ListItemOptions
import io.nekohasekai.sfa.compose.ui.LocalBoxNavigator
import io.nekohasekai.sfa.compose.ui.LocalDialogs
import io.nekohasekai.sfa.compose.ui.PopupMenuItem
import io.nekohasekai.sfa.compose.ui.SearchState
import io.nekohasekai.sfa.compose.ui.SelectionCheck
import io.nekohasekai.sfa.compose.ui.bottomInset
import io.nekohasekai.sfa.compose.ui.contentTopPadding
import io.nekohasekai.sfa.compose.ui.raisedSurfaceLevel
import io.nekohasekai.sfa.compose.ui.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

// ---------------------------------------------------------------------------
// theme_settings.dart
// ---------------------------------------------------------------------------

val defaultAccentColors: List<Long> = listOf(0xff007aff, 0xffcb30e0, 0xffff2d55, 0xffff383c, 0xffff8d28, 0xffffcc00, 0xff34c759, 0xff8e8e93)

private fun Any?.argbOrNull(): Int? = (this as? Number)?.toLong()?.toInt()

/** appColorScheme: the scheme the preferences pick for [dark], or for a [color] being tried. */
fun appColorScheme(box: BoxController, dark: Boolean, color: Int? = null, pureBlack: Boolean? = null): BoxScheme {
    val prefs = box.preferences
    val accent = color ?: if (prefs["systemColor"] == true) box.systemAccentColor else prefs["color"].argbOrNull() ?: 0xff007aff.toInt()
    return seededColorScheme(accent, dark, ColorStyle.of(prefs["colorStyle"])).toPureBlack(pureBlack ?: (prefs["pureBlack"] == true))
}

fun customFontScale(prefs: Json): Boolean = prefs["customFontScale"] as? Boolean ?: prefs.containsKey("fontScale")

private fun textScaleOf(prefs: Json, systemScale: Float): Float =
    (if (customFontScale(prefs)) prefs["fontScale"].asDouble()?.toFloat() ?: 1f else systemScale).coerceIn(0.8f, 1.4f)

// ---------------------------------------------------------------------------
// pages/appearance.dart: FlClash's Android appearance controls, driven by B-box preferences
// ---------------------------------------------------------------------------

private class PreviewChoice<T>(val value: T, val label: String, val pictogram: @Composable () -> Unit)

@Composable
private fun InfoHeader(label: String, glyph: Glyph, actions: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = 32.dp, end = 20.dp, top = 8.dp).height(40.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GlyphIcon(glyph, size = 20.dp, color = BoxTheme.scheme.onSurfaceVariant)
        Text(label, Modifier.weight(1f), style = AppTextStyle.Headline.of().copy(color = BoxTheme.scheme.onSurfaceVariant))
        actions()
    }
}

@Composable
private fun <T> PreviewChoiceGroup(label: String, glyph: Glyph, choices: List<PreviewChoice<T>>, value: T, onChanged: (T) -> Unit) {
    Column {
        InfoHeader(label, glyph)
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
            choices.forEachIndexed { index, choice ->
                ListItem(
                    leading = { choice.pictogram() },
                    title = { Text(choice.label) },
                    trailing = if (choice.value == value) ({ SelectionCheck() }) else null,
                    position = ItemPosition.of(index, choices.size),
                    onClick = { onChanged(choice.value) },
                )
            }
        }
    }
}

@Composable
fun AppearancePage(box: BoxController) {
    val navigator = LocalBoxNavigator.current
    val scope = rememberCoroutineScope()
    val prefs = box.preferences
    val floating = prefs["floatingNavigation"] != false
    val glass = (prefs["liquidGlass"] ?: "none") != "none"
    val dark = BoxTheme.scheme.dark
    val tabAnimation = if (prefs["tabAnimation"] == "fade") "fade" else "slide"
    fun setPreference(key: String, value: Any?) {
        scope.launch { box.setPreference(key, value) }
    }
    val preview: @Composable (Boolean?, Boolean?, Boolean?, Boolean?, String?) -> Unit = { previewDark, pureBlack, floatingBar, liquidGlass, animation ->
        MiniScreenThumb {
            MiniScreen(
                scheme = appColorScheme(box, previewDark ?: dark, pureBlack = pureBlack),
                floatingBar = floatingBar ?: floating,
                liquidGlass = liquidGlass ?: glass,
                selected = if (animation == null) 0 else 1,
                previous = if (animation == null) null else 0,
                progress = if (animation == null) 1f else 0.55f,
                tabAnimation = animation ?: tabAnimation,
            )
        }
    }
    CommonScaffold(title = tr("Appearance")) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = contentTopPadding(), bottom = 32.dp + bottomInset())) {
            item(key = "preview") { ThemeLivePreview(box) }
            item(key = "style") {
                Spacer(Modifier.height(16.dp))
                // Standard is the FlClash look; Minimal is flat and quiet (no glass, shadows or flourishes).
                PreviewChoiceGroup(
                    tr("Style"),
                    AppGlyphs.sparkle,
                    listOf(
                        PreviewChoice(AppearanceStyle.Standard.key, tr("Standard")) { preview(null, null, null, null, null) },
                        PreviewChoice(AppearanceStyle.Minimal.key, tr("Minimal")) { preview(null, null, null, false, null) },
                    ),
                    AppearanceStyle.of(prefs["appearanceStyle"]).key,
                ) { setPreference("appearanceStyle", it) }
            }
            item(key = "theme") {
                Spacer(Modifier.height(16.dp))
                PreviewChoiceGroup(
                    tr("Theme"),
                    AppGlyphs.sun,
                    listOf(
                        PreviewChoice("system", tr("System")) {
                            MiniScreenThumb {
                                MiniSplitScreen(
                                    light = { MiniScreen(appColorScheme(box, false), floating, glass) },
                                    dark = { MiniScreen(appColorScheme(box, true), floating, glass) },
                                )
                            }
                        },
                        PreviewChoice("light", tr("Light")) { preview(false, null, null, null, null) },
                        PreviewChoice("dark", tr("Dark")) { preview(true, null, null, null, null) },
                    ),
                    prefs["theme"] as? String ?: "system",
                ) { setPreference("theme", it) }
            }
            item(key = "pureBlack") {
                Spacer(Modifier.height(16.dp))
                PreviewChoiceGroup(
                    tr("Pure black mode"),
                    AppGlyphs.pureBlack,
                    listOf(
                        PreviewChoice(false, tr("Standard")) { preview(true, false, null, null, null) },
                        PreviewChoice(true, tr("Pure black")) { preview(true, true, null, null, null) },
                    ),
                    prefs["pureBlack"] == true,
                ) { setPreference("pureBlack", it) }
            }
            item(key = "accent") {
                Spacer(Modifier.height(16.dp))
                AccentColors(box)
            }
            item(key = "navigation") {
                Spacer(Modifier.height(16.dp))
                PreviewChoiceGroup(
                    tr("Navigation bar style"),
                    AppGlyphs.layers,
                    listOf(
                        PreviewChoice(true, tr("Floating")) { preview(null, null, true, null, null) },
                        PreviewChoice(false, tr("Docked")) { preview(null, null, false, null, null) },
                    ),
                    floating,
                ) { setPreference("floatingNavigation", it) }
            }
            item(key = "tabAnimation") {
                Spacer(Modifier.height(16.dp))
                PreviewChoiceGroup(
                    tr("Tab animation"),
                    AppGlyphs.motion,
                    listOf("slide", "fade").map { mode -> PreviewChoice(mode, tr(if (mode == "slide") "Slide" else "Fade")) { preview(null, null, null, null, mode) } },
                    tabAnimation,
                ) { setPreference("tabAnimation", it) }
            }
            item(key = "liquidGlass") {
                Spacer(Modifier.height(16.dp))
                PreviewChoiceGroup(
                    tr("Liquid glass"),
                    AppGlyphs.liquidGlass,
                    listOf("none" to "None", "standard" to "Standard", "ultra" to "Ultra").map { (mode, label) ->
                        PreviewChoice(mode, tr(label)) { preview(null, null, true, mode != "none", null) }
                    },
                    prefs["liquidGlass"] as? String ?: "none",
                ) { setPreference("liquidGlass", it) }
            }
            item(key = "font") {
                Spacer(Modifier.height(16.dp))
                InfoHeader(tr("Font family"), AppGlyphs.font)
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
                    ListItem(
                        leading = { Text("Aa", style = AppTextStyle.Headline.of().copy(color = BoxTheme.scheme.onSurface)) },
                        title = { Text(prefs["fontFamily"] as? String ?: tr("Default")) },
                        trailing = { GlyphIcon(AppGlyphs.chevronForward) },
                        position = ItemPosition.StartAndEnd,
                        onClick = { navigator?.push { FontFamilyPage(box) } },
                    )
                }
            }
            item(key = "scale") {
                Spacer(Modifier.height(16.dp))
                TextScaleControl(box)
            }
        }
    }
}

@Composable
private fun AccentColors(box: BoxController) {
    val navigator = LocalBoxNavigator.current
    val dialogs = LocalDialogs.current
    val scope = rememberCoroutineScope()
    val prefs = box.preferences
    val colors = (prefs["accentColors"] as? List<*>)?.mapNotNull { (it as? Number)?.toLong() } ?: defaultAccentColors
    val selected: Long? = if (prefs["systemColor"] == true) null else (prefs["color"] as? Number)?.toLong() ?: 0xff007aff
    fun edit(old: Long?) {
        val initial = (old ?: (prefs["color"] as? Number)?.toLong() ?: box.systemAccentColor.toLong()).toInt()
        navigator?.openForm(onClosed = { result ->
            val value = (result as? Number)?.toLong()?.and(0xffffffffL) ?: return@openForm
            val next = colors.toMutableList()
            val index = if (old == null) -1 else next.indexOf(old)
            if (index >= 0) next.removeAt(index)
            if (value !in next) next.add(if (index < 0) next.size else index, value)
            scope.launch { box.setPreferences(mapOf("accentColors" to next, "color" to value, "systemColor" to false)) }
        }) { CustomColorPage(box, initial) }
    }
    Column {
        InfoHeader(tr("Accent color"), AppGlyphs.palette) {
            GlyphButton(AppGlyphs.reset, tr("Reset"), {
                scope.launch {
                    if (dialogs?.confirm("Reset accent colors?", action = "Reset") == true) {
                        box.setPreferences(mapOf("accentColors" to defaultAccentColors, "color" to 0xff007aff, "systemColor" to false, "colorStyle" to "vivid"))
                    }
                }
            }, color = BoxTheme.scheme.onSurfaceVariant)
        }
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ColorSwatches(
                box,
                listOf<Long?>(null) + colors,
                selected,
                onSelect = { value -> scope.launch { box.setPreferences(buildMap { put("systemColor", value == null); if (value != null) put("color", value) }) } },
                onEdit = { edit(it) },
                onAdd = if (colors.size >= 64) null else ({ edit(null) }),
                onDelete = { value ->
                    scope.launch {
                        if (dialogs?.confirm("Delete this color?") == true) {
                            box.setPreferences(buildMap { put("accentColors", colors.filter { it != value }); if (selected == value) put("systemColor", true) })
                        }
                    }
                },
            )
            val styles = ColorStyle.entries.map { it.name.lowercase() }
            val styleLabels = styles.associateWith { name -> trOrSelf(name.replaceFirstChar { it.uppercase() }) }
            ListItemOptions(
                tr("Color style"),
                styles,
                (prefs["colorStyle"] as? String)?.lowercase() ?: "vivid",
                ItemPosition.StartAndEnd,
                { name -> styleLabels[name] ?: name },
            ) { value -> scope.launch { box.setPreference("colorStyle", value) } }
        }
    }
}

@Composable
private fun trOrSelf(value: String): String = tr(value)

private const val SWATCH = 44
private const val SWATCH_DOT = 32

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorSwatches(
    box: BoxController,
    colors: List<Long?>,
    selected: Long?,
    onSelect: (Long?) -> Unit,
    onEdit: (Long) -> Unit,
    onAdd: (() -> Unit)?,
    onDelete: (Long) -> Unit,
) {
    val scheme = BoxTheme.scheme
    val inset = (16 - (SWATCH - SWATCH_DOT) / 2f).dp
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .background(raisedSurfaceLevel.colorOf(scheme), AppShape.xl)
            .padding(inset),
    ) {
        val width = maxWidth.value
        val columns = max(1, floor((width + 6) / (SWATCH + 6)).toInt())
        val gap = if (columns > 1) floor((width - columns * SWATCH) / (columns - 1)) else 0f
        FlowRow(horizontalArrangement = Arrangement.spacedBy(gap.dp), verticalArrangement = Arrangement.spacedBy(gap.dp)) {
            for (color in colors) {
                val fill = Color((color ?: box.systemAccentColor.toLong()).toInt())
                val isSelected = color == selected
                val swatch: @Composable (() -> Unit) -> Unit = { open ->
                    Box(
                        Modifier
                            .size(SWATCH.dp)
                            .clip(CircleShape)
                            .pointerInput(color) { detectTapGestures(onTap = { onSelect(color) }, onLongPress = { open() }) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isSelected) Box(Modifier.fillMaxSize().border(2.5.dp, fill, CircleShape))
                        Box(Modifier.size(SWATCH_DOT.dp).background(fill, CircleShape))
                        if (color == null) {
                            Box(
                                Modifier.align(Alignment.BottomEnd).size(20.dp).background(LocalSurfaceColor(), CircleShape).padding(2.dp)
                                    .background(raisedSurfaceLevel.colorOf(scheme), CircleShape),
                                contentAlignment = Alignment.Center,
                            ) { GlyphIcon(AppGlyphs.eyedropper, size = 12.dp, color = scheme.onSurface) }
                        }
                        if (isSelected) GlyphIcon(AppGlyphs.check, size = 18.dp, fill = 1f, color = if (fill.luminance() < 0.5f) Color.White else Color.Black)
                    }
                }
                if (color == null) {
                    swatch {}
                } else {
                    CommonPopupBox(
                        listOf(
                            PopupMenuItem(tr("Edit"), AppGlyphs.edit, onPressed = { onEdit(color) }),
                            PopupMenuItem(tr("Delete"), AppGlyphs.delete, danger = true, onPressed = { onDelete(color) }),
                        ),
                    ) { open -> swatch(open) }
                }
            }
            if (onAdd != null) {
                Box(Modifier.size(SWATCH.dp).clip(CircleShape).clickable(onClick = onAdd), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(SWATCH_DOT.dp).background(raisedSurfaceLevel.colorOf(scheme), CircleShape), contentAlignment = Alignment.Center) {
                        GlyphIcon(AppGlyphs.add, size = 20.dp, color = scheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalSurfaceColor(): Color = io.nekohasekai.sfa.compose.theme.LocalSurfaceLevel.current.colorOf(BoxTheme.scheme)

@Composable
private fun CustomColorPage(box: BoxController, initial: Int) {
    val navigator = LocalBoxNavigator.current
    var color by remember { mutableStateOf(Color(initial)) }
    BoxPage(
        "Custom color",
        confirmationAction = IconAction(AppGlyphs.check, tr("Save"), { navigator?.pop(color.toArgb().toLong() and 0xffffffffL) }),
    ) {
        Row(
            Modifier.fillMaxWidth().background(raisedSurfaceLevel.colorOf(BoxTheme.scheme), AppShape.xl).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (dark in listOf(false, true)) {
                Box(Modifier.width(48.dp).height(72.dp).clip(AppShape.sm)) {
                    MiniScreen(appColorScheme(box, dark, color = color.toArgb()), floatingBar = true)
                }
            }
            Box(Modifier.weight(1f)) { HexColorField(color) { color = it } }
        }
        Spacer(Modifier.height(16.dp))
        Palette(color) { color = it }
    }
}

@Composable
private fun TextScaleControl(box: BoxController) {
    val scope = rememberCoroutineScope()
    val prefs = box.preferences
    val enabled = customFontScale(prefs)
    var draft by remember { mutableStateOf<Float?>(null) }
    val systemScale = LocalConfiguration.current.fontScale
    val scale = draft ?: textScaleOf(prefs, systemScale)
    val scheme = BoxTheme.scheme
    Column {
        InfoHeader(tr("Font scale"), AppGlyphs.textSize) {
            if (enabled && scale != 1f) GlyphButton(AppGlyphs.reset, tr("Reset"), { scope.launch { box.setPreference("fontScale", 1) } }, color = scheme.onSurfaceVariant)
        }
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SegmentedControl(listOf(false to tr("Follow system"), true to tr("Custom")), enabled) {
                draft = null
                scope.launch { box.setPreference("customFontScale", it) }
            }
            Column(Modifier.fillMaxWidth().background(raisedSurfaceLevel.colorOf(scheme), AppShape.lg).padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    val density = LocalDensity.current
                    // The preview follows the chosen scale on top of the app's own.
                    androidx.compose.runtime.CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Aa", style = AppTextStyle.Title3.of().copy(color = scheme.onSurface))
                            Text(tr("Text size preview"), style = AppTextStyle.Subheadline.of().copy(color = scheme.onSurface))
                        }
                    }
                    Text("${(scale * 100).roundToInt()}%", style = AppTextStyle.Subheadline.of().tabular.copy(color = scheme.onSurfaceVariant))
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("A", style = AppTextStyle.Footnote.of().copy(color = scheme.onSurface))
                    Slider(
                        value = scale,
                        onValueChange = { draft = it },
                        onValueChangeFinished = {
                            val value = draft ?: return@Slider
                            scope.launch {
                                box.setPreference("fontScale", (value * 20).roundToInt() / 20.0)
                                draft = null
                            }
                        },
                        valueRange = 0.8f..1.4f,
                        steps = 11,
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                    )
                    Text("A", style = AppTextStyle.Title2.of().copy(color = scheme.onSurface))
                }
            }
        }
    }
}

@Composable
private fun FontFamilyPage(box: BoxController) {
    val scope = rememberCoroutineScope()
    var fonts by remember { mutableStateOf<List<String>?>(null) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val found = sortedSetOf(String.CASE_INSENSITIVE_ORDER, "JetBrainsMono")
        try {
            val xml = withContext(Dispatchers.IO) { File("/system/etc/fonts.xml").readText() }
            Regex("<family\\b[^>]*\\bname=\"([^\"]+)\"").findAll(xml).forEach { found.add(it.groupValues[1]) }
        } catch (error: Exception) {
            box.report(error)
        }
        fonts = found.toList()
    }
    val selected = box.preferences["fontFamily"] as? String
    val visible = (fonts ?: emptyList()).filter { it.lowercase().contains(query.lowercase()) }
    val defaultLabel = tr("Default")
    CommonScaffold(title = tr("Font family"), loading = fonts == null, searchState = SearchState { query = it }) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = contentTopPadding(), bottom = 24.dp + bottomInset())) {
            items(listOf<String?>(null) + visible, key = { it ?: "" }) { family ->
                val fontFamily: FontFamily? = remember(family) { family?.let { appFontFamily(it) } }
                Box(Modifier.padding(bottom = 8.dp)) {
                    ListItem(
                        title = { Text(family ?: defaultLabel, style = TextStyle(fontFamily = fontFamily)) },
                        trailing = if (selected == family) ({ SelectionCheck() }) else null,
                        position = ItemPosition.StartAndEnd,
                        onClick = { scope.launch { box.setPreference("fontFamily", family) } },
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// ui/segmented_control.dart
// ---------------------------------------------------------------------------

@Composable
private fun <T> SegmentedControl(segments: List<Pair<T, String>>, value: T, onChanged: (T) -> Unit) {
    val scheme = BoxTheme.scheme
    Row(
        Modifier.fillMaxWidth().height(36.dp).background(raisedSurfaceLevel.colorOf(scheme), AppShape.full).padding(3.dp),
    ) {
        for ((segment, label) in segments) {
            val selected = segment == value
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .clip(AppShape.full)
                    .background(if (selected) SurfaceLevel.floatingOf(scheme).colorOf(scheme) else Color.Transparent, AppShape.full)
                    .clickable { onChanged(segment) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, textAlign = TextAlign.Center, maxLines = 1, style = AppTextStyle.Footnote.of(emphasized = selected).copy(color = if (selected) scheme.onSurface else scheme.onSurfaceVariant))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// components/theme_preview.dart
// ---------------------------------------------------------------------------

/** MiniScreenThumb: the foot of a [MiniScreen], where the options differ, at the size of an icon. */
@Composable
private fun MiniScreenThumb(screen: @Composable () -> Unit) {
    // A 96 x 128 screen whose lower 66 show, scaled to 30 high.
    val height = 30f
    val width = height * 96f / 66f
    Box(Modifier.width(width.dp).height(height.dp).clip(AppShape.sm)) {
        val scale = height / 66f
        Box(
            Modifier
                .requiredSizeAligned((96 * scale).dp, (128 * scale).dp),
        ) { screen() }
    }
}

private fun Modifier.requiredSizeAligned(width: androidx.compose.ui.unit.Dp, height: androidx.compose.ui.unit.Dp): Modifier =
    this.then(Modifier.layout { measurable, constraints ->
        val w = width.roundToPx()
        val h = height.roundToPx()
        val placeable = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(w, h))
        layout(constraints.maxWidth, constraints.maxHeight) {
            // Bottom-aligned, as the thumb shows the screen's foot.
            placeable.place((constraints.maxWidth - w) / 2, constraints.maxHeight - h)
        }
    })

@Composable
private fun MiniSplitScreen(light: @Composable () -> Unit, dark: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        light()
        Box(Modifier.fillMaxSize().clip(DiagonalShape)) { dark() }
    }
}

private object DiagonalShape : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): androidx.compose.ui.graphics.Outline =
        androidx.compose.ui.graphics.Outline.Generic(
            Path().apply {
                moveTo(size.width * 0.7f, 0f)
                lineTo(size.width, 0f)
                lineTo(size.width, size.height)
                lineTo(size.width * 0.3f, size.height)
                close()
            },
        )
}

private const val DESTINATIONS = 4

/** ThemeLivePreview: the home page as the settings will draw it; its destinations switch with the tab animation. */
@Composable
private fun ThemeLivePreview(box: BoxController) {
    val prefs = box.preferences
    val scheme = appColorScheme(box, BoxTheme.scheme.dark)
    val floatingBar = prefs["floatingNavigation"] != false
    val tabAnimation = if (prefs["tabAnimation"] == "fade") "fade" else "slide"
    val liquidGlass = (prefs["liquidGlass"] ?: "none") != "none"
    var selected by remember { mutableIntStateOf(0) }
    var previous by remember { mutableStateOf<Int?>(null) }
    val progress = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    fun select(index: Int) {
        if (index == selected) return
        previous = selected
        selected = index
        scope.launch {
            progress.snapTo(0f)
            progress.animateTo(1f, if (tabAnimation == "fade") tween(250, easing = LinearOutSlowInEasing) else tween(300, easing = CubicBezierEasing(0f, 0f, 0.58f, 1f)))
            if (selected == index) previous = null
        }
    }
    var lastAnimation by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(tabAnimation) {
        if (lastAnimation != null && lastAnimation != tabAnimation) select((selected + 1) % DESTINATIONS)
        lastAnimation = tabAnimation
    }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .background(raisedSurfaceLevel.colorOf(BoxTheme.scheme), AppShape.xxl)
            .padding(vertical = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.width(168.dp).aspectRatio(9f / 17f).clip(AppShape.all(36f))) {
            MiniScreen(
                scheme,
                floatingBar,
                liquidGlass,
                selected = selected,
                previous = previous,
                progress = if (previous == null) 1f else progress.value,
                tabAnimation = tabAnimation,
                onSelect = ::select,
            )
        }
    }
}

private class MiniGeometry(val size: Size, floatingBar: Boolean) {
    val unit = size.width / 20
    val margin = unit * 1.5f
    private val barHeight = if (floatingBar) unit * 3.2f else unit * 3.6f
    val pageBottom = if (floatingBar) 0f else barHeight
    val bar: Rect = if (floatingBar) {
        Rect(margin, size.height - margin - barHeight, size.width - margin, size.height - margin)
    } else {
        Rect(0f, size.height - barHeight, size.width, size.height)
    }
}

/** MiniScreen: a phone-shaped sketch of the home page, drawn in [scheme]. */
@Composable
private fun MiniScreen(
    scheme: BoxScheme,
    floatingBar: Boolean,
    liquidGlass: Boolean = false,
    selected: Int = 0,
    previous: Int? = null,
    progress: Float = 1f,
    tabAnimation: String = "slide",
    onSelect: ((Int) -> Unit)? = null,
) {
    val density = LocalDensity.current
    Canvas(
        Modifier
            .fillMaxSize()
            .then(
                if (onSelect == null) {
                    Modifier
                } else {
                    Modifier.pointerInput(floatingBar) {
                        detectTapGestures { position ->
                            val geometry = MiniGeometry(Size(size.width.toFloat(), size.height.toFloat()), floatingBar)
                            if (geometry.bar.contains(position)) {
                                val index = ((position.x - geometry.bar.left) / (geometry.bar.width / DESTINATIONS)).toInt().coerceIn(0, DESTINATIONS - 1)
                                onSelect(index)
                            }
                        }
                    }
                },
            ),
    ) {
        MiniPainter(this, scheme, floatingBar, liquidGlass, selected, previous, progress, tabAnimation, density).paint()
    }
}

private class MiniPainter(
    val scope: DrawScope,
    val scheme: BoxScheme,
    val floatingBar: Boolean,
    val liquidGlass: Boolean,
    val selected: Int,
    val previous: Int?,
    val progress: Float,
    val tabAnimation: String,
    val density: Density,
) {
    private val glass get() = floatingBar && liquidGlass

    fun fill(rect: Rect, color: Color, shape: Shape) {
        val outline = shape.createOutline(rect.size, LayoutDirection.Ltr, density)
        scope.translate(rect.left, rect.top) { drawOutline(outline, color) }
    }

    private fun DrawScope.translate(x: Float, y: Float, block: DrawScope.() -> Unit) {
        drawContext.transform.translate(x, y)
        block()
        drawContext.transform.translate(-x, -y)
    }

    fun paint() {
        val geometry = MiniGeometry(scope.size, floatingBar)
        paintPages(geometry)
        val unit = geometry.unit
        val dock = SurfaceLevel.floatingOf(scheme).colorOf(scheme)
        if (glass) {
            // LiquidGlass at sketch size: a tint and a lit rim; the frosting blur is not drawn.
            val path = Path().apply { addRoundRect(RoundRect(geometry.bar, CornerRadius(geometry.bar.height / 2))) }
            scope.drawPath(path, scheme.onSurface.copy(alpha = if (scheme.dark) 0.12f else 0.06f))
            scope.drawPath(path, Color.White.copy(alpha = if (scheme.dark) 0.18f else 0.42f))
            val rim = unit * 0.15f
            scope.drawRoundRect(
                Brush.linearGradient(listOf(Color.White.copy(alpha = 0.6f), Color.White.copy(alpha = 0.09f), Color.White.copy(alpha = 0.3f)), geometry.bar.topLeft, geometry.bar.bottomRight),
                topLeft = Offset(geometry.bar.left + rim / 2, geometry.bar.top + rim / 2),
                size = Size(geometry.bar.width - rim, geometry.bar.height - rim),
                cornerRadius = CornerRadius((geometry.bar.height - rim) / 2),
                style = Stroke(rim),
            )
        } else if (floatingBar) {
            scope.drawRoundRect(scheme.shadow.copy(alpha = 0.12f), Offset(geometry.bar.left, geometry.bar.top + unit * 0.3f), geometry.bar.size, CornerRadius(geometry.bar.height / 2))
            fill(geometry.bar, dock, AppShape.full)
        } else {
            scope.drawRect(dock, geometry.bar.topLeft, geometry.bar.size)
        }
        paintDestinations(geometry.bar, unit)
    }

    private fun paintPages(geometry: MiniGeometry) {
        val size = geometry.size
        scope.drawRect(SurfaceLevel.Page.colorOf(scheme), size = size)
        scope.clipRect(0f, 0f, size.width, size.height) {
            val prev = previous
            when {
                prev == null -> paintPage(geometry, selected, 0f)
                tabAnimation == "fade" -> {
                    paintPage(geometry, prev, 0f, 1 - progress)
                    paintPage(geometry, selected, 0f, progress)
                }
                else -> {
                    val direction = if (prev > selected) -1 else 1
                    paintPage(geometry, prev, -direction * size.width * progress)
                    paintPage(geometry, selected, direction * size.width * (1 - progress))
                }
            }
        }
    }

    private fun paintPage(geometry: MiniGeometry, index: Int, offset: Float, opacity: Float = 1f) {
        if (opacity <= 0f) return
        val size = geometry.size
        val content = Rect(offset + geometry.margin, geometry.margin, offset + size.width - geometry.margin, size.height - geometry.pageBottom)
        scope.clipRect(content.left, content.top, content.right, content.bottom) {
            PageSketch(this@MiniPainter, scheme, geometry.unit, content, opacity).paint(index % DESTINATIONS)
        }
    }

    private fun paintDestinations(bar: Rect, unit: Float) {
        val cell = bar.width / DESTINATIONS
        val indicator = if (glass) scheme.onSurface.copy(alpha = if (scheme.dark) 0.14f else 0.08f) else SurfaceLevel.floatingOf(scheme).next.colorOf(scheme)
        for (i in 0 until DESTINATIONS) {
            val center = Offset(bar.left + cell * i + cell / 2, bar.center.y)
            val icon = center + Offset(0f, -unit * 0.4f)
            val isSelected = i == selected
            if (isSelected) {
                fill(
                    if (floatingBar) {
                        Rect(center.x - (cell - unit * 0.5f) / 2, center.y - (bar.height - unit * 0.5f) / 2, center.x + (cell - unit * 0.5f) / 2, center.y + (bar.height - unit * 0.5f) / 2)
                    } else {
                        Rect(icon.x - unit * 1.4f, icon.y - unit * 0.75f, icon.x + unit * 1.4f, icon.y + unit * 0.75f)
                    },
                    indicator,
                    AppShape.full,
                )
            }
            val ink = if (isSelected) scheme.primary else scheme.onSurfaceVariant
            scope.drawCircle(ink, unit * 0.45f, icon)
            val label = center + Offset(0f, unit * 0.8f)
            fill(Rect(label.x - unit * 0.85f, label.y - unit * 0.175f, label.x + unit * 0.85f, label.y + unit * 0.175f), if (isSelected) ink else ink.copy(alpha = 0.6f), AppShape.full)
        }
    }
}

/** _PageSketch: one page after the app's own, laid out in twentieths of the screen's width. */
private class PageSketch(val painter: MiniPainter, val scheme: BoxScheme, val unit: Float, val content: Rect, val opacity: Float) {
    private val width = content.width / unit
    private val card = SurfaceLevel.Card.colorOf(scheme).fade()
    private val inset = SurfaceLevel.Inset.colorOf(scheme).fade()
    private val text = scheme.onSurface.copy(alpha = 0.72f).fade()
    private val line = scheme.onSurfaceVariant.copy(alpha = 0.4f).fade()
    private val faint = scheme.onSurfaceVariant.copy(alpha = 0.16f).fade()
    private val primary = scheme.primary.fade()
    private val onPrimary = scheme.onPrimary.fade()
    private val gap = 0.6f

    private fun Color.fade(): Color = copy(alpha = alpha * opacity)

    fun paint(page: Int) {
        when (page) {
            0 -> dashboard(header(1))
            1 -> proxies(header(2))
            2 -> profiles(header(2))
            else -> tools(header(0))
        }
    }

    private fun rect(x: Float, y: Float, w: Float, h: Float) = Rect(content.left + x * unit, content.top + y * unit, content.left + (x + w) * unit, content.top + (y + h) * unit)

    private fun shape(extent: Float): Shape = AppShape.all(fitCorner(unit * extent))

    private fun fitCorner(extent: Float): Float = (extent / 4f).coerceAtMost(28f) / painter.density.density

    private fun block(rect: Rect, color: Color, extent: Float = 5f) = painter.fill(rect, color, shape(extent))

    private fun pill(x: Float, y: Float, w: Float, h: Float, color: Color) = painter.fill(rect(x, y, w, h), color, AppShape.full)

    private fun dot(x: Float, y: Float, radius: Float, color: Color) {
        painter.scope.drawCircle(color, radius * unit, Offset(content.left + x * unit, content.top + y * unit))
    }

    private fun header(actions: Int): Float {
        pill(0f, 0.45f, 7f, 1.1f, text)
        for (i in 0 until actions) {
            val x = width - 1 - i * 2.4f
            dot(x, 1f, 1f, card)
            dot(x, 1f, 0.3f, line)
        }
        return 2.8f
    }

    private fun dashboard(top: Float) {
        var y = top
        val status = rect(0f, y, width, 6.2f)
        val buttonX = width - 3
        val buttonY = y + 3.1f
        block(status, card)
        val outline = shape(5f).createOutline(status.size, LayoutDirection.Ltr, painter.density)
        val clip = Path().apply {
            when (outline) {
                is androidx.compose.ui.graphics.Outline.Generic -> addPath(outline.path)
                is androidx.compose.ui.graphics.Outline.Rounded -> addRoundRect(outline.roundRect)
                is androidx.compose.ui.graphics.Outline.Rectangle -> addRect(outline.rect)
            }
            translate(status.topLeft)
        }
        painter.scope.clipPath(clip) {
            drawCircle(scheme.primary.copy(alpha = 0.1f * opacity), unit * 4.6f, Offset(content.left + buttonX * unit, content.top + buttonY * unit))
        }
        dot(buttonX, buttonY, 1.8f, primary)
        for (dx in listOf(-0.675f, 0.325f)) pill(buttonX + dx, buttonY - 0.6f, 0.35f, 1.2f, onPrimary)
        pill(1f, y + 0.9f, 3f, 0.7f, line)
        dot(1.9f, y + 3f, 0.9f, inset)
        pill(3.5f, y + 2.3f, 5f, 0.9f, text)
        pill(3.5f, y + 3.5f, 2.4f, 0.7f, inset)
        pill(1f, y + 4.8f, 2.4f, 0.7f, line)
        pill(4.6f, y + 4.8f, 3.6f, 0.7f, line)
        y += 6.2f + gap

        block(rect(0f, y, width, 5.8f), card)
        pill(1f, y + 0.9f, 4.5f, 0.7f, line)
        val tileWidth = (width - 2.6f) / 2
        for (i in 0 until 2) {
            val x = 1 + i * (tileWidth + 0.6f)
            val tile = rect(x, y + 2.1f, tileWidth, 2.9f)
            block(tile, inset, 2.9f)
            pill(x + 0.6f, y + 2.65f, 2.4f, 0.7f, line)
            speedGraph(tile, i * 1.7f, if (i == 0) scheme.primary.copy(alpha = 0.5f) else scheme.primary)
        }
        y += 5.8f + gap

        block(rect(0f, y, width, 4.4f), card)
        pill(1f, y + 0.9f, 4f, 0.7f, line)
        val segment = (width - 2.6f) / 3
        for (i in 0 until 3) {
            val x = 1 + i * (segment + 0.3f)
            pill(x, y + 2.1f, segment, 1.6f, if (i == 0) primary else inset)
            dot(x + segment / 2, y + 2.9f, 0.3f, if (i == 0) onPrimary else line)
        }
        y += 4.4f + gap

        block(rect(0f, y, width, 3f), card)
        dot(1.9f, y + 1.5f, 0.9f, primary)
        pill(3.4f, y + 1.1f, 2.4f, 0.8f, line)
        pill(width - 3.6f, y + 0.8f, 2.6f, 1.4f, primary)
        dot(width - 1.7f, y + 1.5f, 0.55f, onPrimary)
        y += 3f + gap

        for (i in 0 until 3) {
            block(rect(0f, y, width, 4.5f), card)
            pill(1f, y + 0.9f, 3.5f + i % 2, 0.7f, line)
            y += 4.5f + gap
        }
    }

    private fun speedGraph(tile: Rect, phase: Float, color: Color) {
        val steps = 16
        val path = Path()
        for (i in 0..steps) {
            val x = tile.left + tile.width * i / steps
            val y = tile.top + tile.height * 0.68f + sin(i * 0.9f + phase) * unit * 0.35f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        val area = Path().apply {
            addPath(path)
            lineTo(tile.right, tile.bottom)
            lineTo(tile.left, tile.bottom)
            close()
        }
        painter.scope.clipRect(tile.left, tile.top, tile.right, tile.bottom) {
            drawPath(area, color.copy(alpha = color.alpha * 0.2f * opacity))
            drawPath(path, color.copy(alpha = color.alpha * opacity), style = Stroke(unit * 0.15f))
        }
    }

    private fun proxies(top: Float) {
        var y = top
        for (i in 0 until 9) {
            block(rect(0f, y, width, 2.6f), card)
            dot(1.5f, y + 1.3f, 0.8f, inset)
            pill(3f, y + 0.6f, 3f + i % 3, 0.7f, line)
            pill(3f, y + 1.55f, 4f, 0.5f, faint)
            pill(width - 4.4f, y + 1.05f, 1.8f, 0.5f, faint)
            dot(width - 1.3f, y + 1.3f, 0.55f, inset)
            y += 2.6f + gap
        }
    }

    private fun profiles(top: Float) {
        var y = top
        for (i in 0 until 3) {
            block(rect(0f, y, width, 2.8f), card)
            pill(1f, y + 0.7f, 3.4f - i * 0.6f, 0.75f, line)
            pill(4.8f - i * 0.6f, y + 0.72f, 2.6f, 0.7f, scheme.primary.copy(alpha = 0.16f * opacity))
            pill(1f, y + 1.7f, 3f, 0.5f, faint)
            pill(width - 4.1f, y + 1.15f, 1.6f, 0.5f, faint)
            if (i == 0) dot(width - 1.4f, y + 1.4f, 0.6f, primary)
            y += 2.8f + gap
        }
    }

    private fun tools(top: Float) {
        val rowHeight = 2.4f
        var y = top
        for (rows in listOf(4, 7)) {
            pill(0.8f, y + 0.2f, 1.6f + rows * 0.15f, 0.5f, faint)
            y += 1.3f
            block(rect(0f, y, width, rows * rowHeight), card)
            for (i in 0 until rows) {
                val row = y + i * rowHeight
                dot(1.3f, row + rowHeight / 2, 0.45f, line)
                pill(2.6f, row + 0.85f, 4f + (i * 3 + rows) % 4, 0.7f, line)
                if (i < rows - 1) {
                    val r = rect(2.6f, row + rowHeight - 0.03f, width - 3.4f, 0.06f)
                    painter.scope.drawRect(faint, r.topLeft, r.size)
                }
            }
            y += rows * rowHeight + gap * 2
        }
    }
}

// ---------------------------------------------------------------------------
// components/palette.dart: grid, spectrum and HSV sliders, as iOS's color picker
// ---------------------------------------------------------------------------

private val gridHues = listOf(230.0, 262.0, 285.0, 310.0, 340.0, 20.0, 40.0, 60.0, 80.0, 100.0, 120.0, 145.0)
private val gridTones = listOf(25.0, 33.0, 41.0, 49.0, 57.0, 65.0, 73.0, 81.0, 89.0)
private val gridRows: List<List<Color>> by lazy {
    fun from(hue: Double, chroma: Double, tone: Double): Color = Color(com.google.android.material.color.utilities.Hct.from(hue, chroma, tone).toInt())
    listOf(gridHues.indices.map { from(0.0, 0.0, 100 - it * 100.0 / (gridHues.size - 1)) }) + gridTones.map { tone -> gridHues.map { from(it, 200.0, tone) } }
}

private fun hsvOf(color: Color): FloatArray = FloatArray(3).also { android.graphics.Color.colorToHSV(color.toArgb(), it) }
private fun colorOfHsv(h: Float, s: Float, v: Float): Color = Color(android.graphics.Color.HSVToColor(floatArrayOf(h, s, v)))

private fun hslToColor(h: Float, s: Float, l: Float): Color {
    val c = (1 - kotlin.math.abs(2 * l - 1)) * s
    val x = c * (1 - kotlin.math.abs((h / 60f) % 2 - 1))
    val m = l - c / 2
    val (r, g, b) = when {
        h < 60 -> Triple(c, x, 0f)
        h < 120 -> Triple(x, c, 0f)
        h < 180 -> Triple(0f, c, x)
        h < 240 -> Triple(0f, x, c)
        h < 300 -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Color(r + m, g + m, b + m)
}

@Composable
private fun Palette(color: Color, onPicked: (Color) -> Unit) {
    var mode by remember { mutableStateOf("grid") }
    var hsv by remember { mutableStateOf(hsvOf(color)) }
    LaunchedEffect(color) { if (colorOfHsv(hsv[0], hsv[1], hsv[2]) != color) hsv = hsvOf(color) }
    Column(Modifier.fillMaxWidth()) {
        SegmentedControl(listOf("grid" to tr("Grid"), "spectrum" to tr("Spectrum"), "sliders" to tr("Sliders")), mode) { mode = it }
        Spacer(Modifier.height(16.dp))
        Box(Modifier.animateContentSize(tween(250, easing = CubicBezierEasing(0.33f, 1f, 0.68f, 1f)))) {
            when (mode) {
                "grid" -> ColorGrid(color, onPicked)
                "spectrum" -> Spectrum(color, onPicked)
                else -> HsvSliders(hsv) { next ->
                    hsv = next
                    onPicked(colorOfHsv(next[0], next[1], next[2]))
                }
            }
        }
    }
}

private fun Modifier.pickArea(onPicked: (Offset, Size) -> Unit): Modifier = pointerInput(Unit) {
    val size = Size(size.width.toFloat(), size.height.toFloat())
    fun pick(position: Offset) = onPicked(Offset(position.x.coerceIn(0f, size.width), position.y.coerceIn(0f, size.height)), size)
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            for (change in event.changes) {
                if (change.pressed) {
                    pick(change.position)
                    change.consume()
                }
            }
        }
    }
}

@Composable
private fun ColorGrid(color: Color, onPicked: (Color) -> Unit) {
    val columns = gridHues.size
    val rows = gridRows.size
    Canvas(
        Modifier
            .fillMaxWidth()
            .aspectRatio(columns.toFloat() / rows)
            .clip(AppShape.sm)
            .pickArea { position, size ->
                val column = (position.x / size.width * columns).toInt().coerceIn(0, columns - 1)
                val row = (position.y / size.height * rows).toInt().coerceIn(0, rows - 1)
                onPicked(gridRows[row][column])
            },
    ) {
        val cellWidth = size.width / columns
        val cellHeight = size.height / rows
        var selectedCell: Rect? = null
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                val cell = Rect(column * cellWidth, row * cellHeight, (column + 1) * cellWidth + 0.5f, (row + 1) * cellHeight + 0.5f)
                val cellColor = gridRows[row][column]
                drawRect(cellColor, cell.topLeft, cell.size)
                if (cellColor == color) selectedCell = Rect(column * cellWidth, row * cellHeight, (column + 1) * cellWidth, (row + 1) * cellHeight)
            }
        }
        selectedCell?.let { cell ->
            val ring = 3.dp.toPx()
            drawRoundRect(
                if (color.luminance() < 0.5f) Color.White else Color.Black,
                Offset(cell.left + ring / 2, cell.top + ring / 2),
                Size(cell.width - ring, cell.height - ring),
                CornerRadius(4.dp.toPx()),
                style = Stroke(ring),
            )
        }
    }
}

private fun DrawScope.colorThumb(center: Offset, color: Color) {
    val radius = 14.dp.toPx()
    drawCircle(Color.Black.copy(alpha = 0.2f), radius + 1.dp.toPx(), center + Offset(0f, 1.dp.toPx()))
    drawCircle(Color.White, radius, center)
    drawCircle(color, radius - 3.dp.toPx(), center)
}

@Composable
private fun Spectrum(color: Color, onPicked: (Color) -> Unit) {
    var picked by remember { mutableStateOf<Pair<Offset, Color>?>(null) }
    val position = picked?.takeIf { it.second == color }?.first ?: run {
        val r = color.red
        val g = color.green
        val b = color.blue
        val maxC = maxOf(r, g, b)
        val minC = minOf(r, g, b)
        val lightness = (maxC + minC) / 2
        Offset(hsvOf(color)[0] / 360f, 1 - lightness)
    }
    val hues = (0..360 step 60).map { hslToColor((it % 360).toFloat(), 1f, 0.5f) }
    Canvas(
        Modifier
            .fillMaxWidth()
            .aspectRatio(6f / 5f)
            .clip(AppShape.sm)
            .pickArea { point, size ->
                val fraction = Offset(point.x / size.width, point.y / size.height)
                val next = hslToColor((fraction.x * 360).coerceIn(0f, 359.99f), 1f, 1 - fraction.y)
                picked = fraction to next
                onPicked(next)
            },
    ) {
        drawRect(Brush.horizontalGradient(hues))
        drawRect(Brush.verticalGradient(0f to Color.White, 0.5f to Color.White.copy(alpha = 0f), 0.5f to Color.Black.copy(alpha = 0f), 1f to Color.Black))
        colorThumb(Offset(position.x * size.width, position.y * size.height), color)
    }
}

@Composable
private fun HsvSliders(hsv: FloatArray, onChanged: (FloatArray) -> Unit) {
    val scheme = BoxTheme.scheme
    val color = colorOfHsv(hsv[0], hsv[1], hsv[2])
    val hues = (0..360 step 60).map { colorOfHsv((it % 360).toFloat(), 1f, 1f) }
    Column(
        Modifier.fillMaxWidth().background(raisedSurfaceLevel.colorOf(scheme), AppShape.xl).padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        LabeledSlider(tr("Hue"), "${hsv[0].roundToInt()}°", hsv[0] / 360f, colorOfHsv(hsv[0], 1f, 1f), hues) { onChanged(floatArrayOf(it * 360f, hsv[1], hsv[2])) }
        LabeledSlider(tr("Saturation"), "${(hsv[1] * 100).roundToInt()}%", hsv[1], color, listOf(colorOfHsv(hsv[0], 0f, hsv[2]), colorOfHsv(hsv[0], 1f, hsv[2]))) {
            onChanged(floatArrayOf(hsv[0], it, hsv[2]))
        }
        LabeledSlider(tr("Brightness"), "${(hsv[2] * 100).roundToInt()}%", hsv[2], color, listOf(colorOfHsv(hsv[0], hsv[1], 0f), colorOfHsv(hsv[0], hsv[1], 1f))) {
            onChanged(floatArrayOf(hsv[0], hsv[1], it))
        }
    }
}

@Composable
private fun LabeledSlider(label: String, value: String, fraction: Float, thumb: Color, colors: List<Color>, onChanged: (Float) -> Unit) {
    val scheme = BoxTheme.scheme
    val thumbRadius = with(LocalDensity.current) { 14.dp.toPx() }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row {
            Text(label, Modifier.weight(1f), style = AppTextStyle.Subheadline.of(emphasized = true).copy(color = scheme.onSurfaceVariant))
            Text(value, style = AppTextStyle.Subheadline.of().tabular.copy(color = scheme.onSurfaceVariant))
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .pickArea { position, size ->
                    onChanged(((position.x - thumbRadius) / (size.width - 2 * thumbRadius)).coerceIn(0f, 1f))
                },
        ) {
            val track = 20.dp.toPx()
            val radius = 14.dp.toPx()
            val top = (size.height - track) / 2
            drawRoundRect(Brush.horizontalGradient(colors), Offset(0f, top), Size(size.width, track), CornerRadius(track / 2))
            drawRoundRect(scheme.outlineVariant, Offset(0f, top), Size(size.width, track), CornerRadius(track / 2), style = Stroke(1.dp.toPx()))
            colorThumb(Offset(radius + (size.width - 2 * radius) * fraction, size.height / 2), thumb)
        }
    }
}

@Composable
private fun HexColorField(color: Color, onChanged: (Color) -> Unit) {
    val scheme = BoxTheme.scheme
    fun hexOf(value: Color) = "%06X".format(value.toArgb() and 0xffffff)
    var text by remember { mutableStateOf(hexOf(color)) }
    LaunchedEffect(color) { if (text.uppercase() != hexOf(color)) text = hexOf(color) }
    Row(
        Modifier.fillMaxWidth().background(SurfaceLevel.Page.colorOf(scheme), AppShape.md).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("#", style = AppTextStyle.Body.of().copy(color = scheme.onSurfaceVariant))
        BasicTextField(
            value = text,
            onValueChange = { next ->
                val filtered = next.filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }.take(6)
                text = filtered
                if (filtered.length == 6) onChanged(Color(0xff000000.toInt() or filtered.toInt(16)))
            },
            singleLine = true,
            textStyle = AppTextStyle.Body.of().tabular.copy(color = scheme.onSurface),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(scheme.primary),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(),
            modifier = Modifier.weight(1f).padding(start = 4.dp),
        )
        Box(Modifier.size(24.dp).background(color, CircleShape))
    }
}
