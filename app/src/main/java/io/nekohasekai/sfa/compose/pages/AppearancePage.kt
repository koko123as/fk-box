package io.nekohasekai.sfa.compose.pages

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import io.nekohasekai.sfa.compose.ui.ListItemToggle
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.compose.BoxController
import io.nekohasekai.sfa.compose.Json
import io.nekohasekai.sfa.compose.asDouble
import io.nekohasekai.sfa.compose.theme.AppShape
import io.nekohasekai.sfa.compose.theme.AppTextStyle
import io.nekohasekai.sfa.compose.theme.BoxScheme
import io.nekohasekai.sfa.compose.theme.BoxTheme
import io.nekohasekai.sfa.compose.theme.ColorStyle
import io.nekohasekai.sfa.compose.theme.SurfaceLevel
import io.nekohasekai.sfa.compose.theme.appFontFamily
import io.nekohasekai.sfa.compose.theme.seededColorScheme
import io.nekohasekai.sfa.compose.theme.tabular
import io.nekohasekai.sfa.compose.ui.AppIcons
import io.nekohasekai.sfa.compose.ui.BoxPage
import io.nekohasekai.sfa.compose.ui.CommonPopupBox
import io.nekohasekai.sfa.compose.ui.CommonScaffold
import androidx.compose.ui.graphics.vector.ImageVector
import io.nekohasekai.sfa.compose.ui.AppIconButton
import io.nekohasekai.sfa.compose.ui.AppIcon
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

// ---------------------------------------------------------------------------
// Theme preferences
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
// Appearance: theme, pure black, accent colour, font family and font scale
// ---------------------------------------------------------------------------

@Composable
private fun InfoHeader(label: String, icon: ImageVector, actions: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp).height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AppIcon(icon, size = 20.dp, color = BoxTheme.scheme.primary)
        Text(label, Modifier.weight(1f), style = AppTextStyle.Subheadline.of(true).copy(color = BoxTheme.scheme.primary))
        actions()
    }
}

@Composable
fun AppearancePage(box: BoxController) {
    val navigator = LocalBoxNavigator.current
    val scope = rememberCoroutineScope()
    val prefs = box.preferences
    fun setPreference(key: String, value: Any?) {
        scope.launch { box.setPreference(key, value) }
    }
    CommonScaffold(title = tr("Appearance")) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = contentTopPadding(), bottom = 32.dp + bottomInset())) {
            item(key = "theme") {
                InfoHeader(tr("Theme"), AppIcons.sun)
                val theme = prefs["theme"] as? String ?: "system"
                val choices = listOf("system" to tr("System"), "light" to tr("Light"), "dark" to tr("Dark"))
                Column {
                    choices.forEachIndexed { index, (value, label) ->
                        ListItem(
                            leading = { RadioButton(selected = theme == value, onClick = null) },
                            title = { Text(label) },
                            position = ItemPosition.of(index, choices.size),
                            onClick = { setPreference("theme", value) },
                        )
                    }
                }
            }
            item(key = "pureBlack") {
                Spacer(Modifier.height(16.dp))
                ListItemToggle(tr("Pure black mode"), prefs["pureBlack"] == true, ItemPosition.StartAndEnd) { setPreference("pureBlack", it) }
            }
            item(key = "accent") {
                Spacer(Modifier.height(16.dp))
                AccentColors(box)
            }
            item(key = "font") {
                Spacer(Modifier.height(16.dp))
                InfoHeader(tr("Font family"), AppIcons.font)
                ListItem(
                    leading = { Text("Aa", style = AppTextStyle.Headline.of().copy(color = BoxTheme.scheme.onSurface)) },
                    title = { Text(prefs["fontFamily"] as? String ?: tr("Default")) },
                    trailing = { AppIcon(AppIcons.chevronForward) },
                    position = ItemPosition.StartAndEnd,
                    onClick = { navigator?.push { FontFamilyPage(box) } },
                )
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
        InfoHeader(tr("Accent color"), AppIcons.palette) {
            AppIconButton(AppIcons.reset, tr("Reset"), {
                scope.launch {
                    if (dialogs?.confirm("Reset accent colors?", action = "Reset") == true) {
                        box.setPreferences(mapOf("accentColors" to defaultAccentColors, "color" to 0xff007aff, "systemColor" to false, "colorStyle" to "vivid"))
                    }
                }
            }, color = BoxTheme.scheme.onSurfaceVariant)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            .background(raisedSurfaceLevel.colorOf(scheme), AppShape.card)
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
                            ) { AppIcon(AppIcons.eyedropper, size = 12.dp, color = scheme.onSurface) }
                        }
                        if (isSelected) AppIcon(AppIcons.check, size = 18.dp, color = if (fill.luminance() < 0.5f) Color.White else Color.Black)
                    }
                }
                if (color == null) {
                    swatch {}
                } else {
                    CommonPopupBox(
                        listOf(
                            PopupMenuItem(tr("Edit"), AppIcons.edit, onPressed = { onEdit(color) }),
                            PopupMenuItem(tr("Delete"), AppIcons.delete, danger = true, onPressed = { onDelete(color) }),
                        ),
                    ) { open -> swatch(open) }
                }
            }
            if (onAdd != null) {
                Box(Modifier.size(SWATCH.dp).clip(CircleShape).clickable(onClick = onAdd), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(SWATCH_DOT.dp).background(raisedSurfaceLevel.colorOf(scheme), CircleShape), contentAlignment = Alignment.Center) {
                        AppIcon(AppIcons.add, size = 20.dp, color = scheme.onSurfaceVariant)
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
        confirmationAction = IconAction(AppIcons.check, tr("Save"), { navigator?.pop(color.toArgb().toLong() and 0xffffffffL) }),
    ) {
        Row(
            Modifier.fillMaxWidth().background(raisedSurfaceLevel.colorOf(BoxTheme.scheme), AppShape.card).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (dark in listOf(false, true)) {
                val preview = appColorScheme(box, dark, color = color.toArgb())
                Box(Modifier.size(48.dp).background(preview.primaryContainer, AppShape.card), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(24.dp).background(preview.primary, CircleShape))
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
        InfoHeader(tr("Font scale"), AppIcons.textSize) {
            if (enabled && scale != 1f) AppIconButton(AppIcons.reset, tr("Reset"), { scope.launch { box.setPreference("fontScale", 1) } }, color = scheme.onSurfaceVariant)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SegmentedControl(listOf(false to tr("Follow system"), true to tr("Custom")), enabled) {
                draft = null
                scope.launch { box.setPreference("customFontScale", it) }
            }
            Column(Modifier.fillMaxWidth().background(raisedSurfaceLevel.colorOf(scheme), AppShape.card).padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> SegmentedControl(segments: List<Pair<T, String>>, value: T, onChanged: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        segments.forEachIndexed { index, (segment, label) ->
            SegmentedButton(
                selected = segment == value,
                onClick = { onChanged(segment) },
                shape = SegmentedButtonDefaults.itemShape(index, segments.size),
            ) { Text(label, maxLines = 1) }
        }
    }
}

// ---------------------------------------------------------------------------
// Colour picker
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
        Box {
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
            .clip(AppShape.card)
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
            .clip(AppShape.card)
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
        Modifier.fillMaxWidth().background(raisedSurfaceLevel.colorOf(scheme), AppShape.card).padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 20.dp),
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
        Modifier.fillMaxWidth().background(SurfaceLevel.Page.colorOf(scheme), AppShape.card).padding(horizontal = 12.dp, vertical = 10.dp),
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
