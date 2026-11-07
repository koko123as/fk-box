package io.nekohasekai.sfa.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nekohasekai.sfa.compose.ui.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * gpu-bisect branch only: runtime switches for finding what holds the
 * "Graphics" memory. Kept in memory, never persisted; every switch starts on.
 * A change bumps [epoch], which rebuilds the app UI under BoxApp so shapes,
 * layers and caches are created afresh with the new setting.
 */
object DebugSwitches {
    var epoch by mutableIntStateOf(0)
        private set

    var icons by switch()
    var superellipse by switch()
    var shadows by switch()
    var glass by switch()
    var backgroundTabs by switch()
    var dashboardCards by switch()
    var navigation by switch()
    var animations by switch()

    /** Off: BoxApp shows only [GpuBisectScreen]; no rebuild needed. */
    var allUi by mutableStateOf(true)

    private fun switch() = object : kotlin.properties.ReadWriteProperty<Any?, Boolean> {
        private val state = mutableStateOf(true)
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = state.value
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Boolean) {
            if (state.value != value) {
                state.value = value
                epoch++
            }
        }
    }
}

/** dumpsys meminfo's App Summary "Graphics", the value the memory page shows. */
fun graphicsBytes(): Long? = runCatching {
    val info = android.os.Debug.MemoryInfo().also { android.os.Debug.getMemoryInfo(it) }
    info.getMemoryStat("summary.graphics")?.toLongOrNull()?.times(1024)
}.getOrNull()

private fun mib(bytes: Long?): String = if (bytes == null) "—" else String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1048576.0)

/** Drops the app's raster caches, asks HWUI to trim everything, then collects garbage. */
private suspend fun releaseGraphics(): String {
    io.nekohasekai.sfa.compose.ui.clearRasterCaches()
    val trim = withContext(Dispatchers.Main) {
        runCatching {
            val global = Class.forName("android.view.WindowManagerGlobal")
            val instance = global.getMethod("getInstance").invoke(null)
            global.getMethod("trimMemory", Int::class.javaPrimitiveType).invoke(instance, 80)
            "trimMemory(80) OK"
        }.getOrElse { "trimMemory(80) failed: ${(it.cause ?: it).javaClass.simpleName}: ${(it.cause ?: it).message}" }
    }
    System.gc()
    Runtime.getRuntime().gc()
    return trim
}

/** The "GPU memory bisect" section: live Graphics readout, the switches and the release button. */
@Composable
fun GpuBisectSection(modifier: Modifier = Modifier) {
    val fg = MaterialTheme.colorScheme.onSurface
    var graphics by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            graphics = withContext(Dispatchers.IO) { graphicsBytes() }
            delay(2000)
        }
    }
    val scope = rememberCoroutineScope()
    var releasing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    val before = tr("Before")
    val after = tr("After")
    Column(modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(tr("GPU memory bisect"), color = fg, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text("${tr("Graphics")}: ${mib(graphics)}", color = fg, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(tr("Refreshes every 2 s. Switches are not saved and reset when the app restarts."), color = fg.copy(alpha = .7f), fontSize = 12.sp)
        BisectItem(fg, "Bisect: icons", DebugSwitches.icons) { DebugSwitches.icons = it }
        BisectItem(fg, "Bisect: superellipse", DebugSwitches.superellipse) { DebugSwitches.superellipse = it }
        BisectItem(fg, "Bisect: shadows", DebugSwitches.shadows) { DebugSwitches.shadows = it }
        BisectItem(fg, "Bisect: glass panes", DebugSwitches.glass) { DebugSwitches.glass = it }
        BisectItem(fg, "Bisect: background pages", DebugSwitches.backgroundTabs) { DebugSwitches.backgroundTabs = it }
        BisectItem(fg, "Bisect: dashboard cards", DebugSwitches.dashboardCards) { DebugSwitches.dashboardCards = it }
        BisectItem(fg, "Bisect: sidebar and dock", DebugSwitches.navigation) { DebugSwitches.navigation = it }
        BisectItem(fg, "Bisect: animations and charts", DebugSwitches.animations) { DebugSwitches.animations = it }
        BisectItem(fg, "Bisect: entire UI", DebugSwitches.allUi) { DebugSwitches.allUi = it }
        Button(
            enabled = !releasing,
            onClick = {
                releasing = true
                scope.launch {
                    val start = withContext(Dispatchers.IO) { graphicsBytes() }
                    val trim = releaseGraphics()
                    delay(1500)
                    val end = withContext(Dispatchers.IO) { graphicsBytes() }
                    graphics = end
                    result = "$before ${mib(start)} → $after ${mib(end)}\n$trim"
                    releasing = false
                }
            },
        ) { Text(tr("Release GPU memory")) }
        result?.let { Text(it, color = fg, fontSize = 13.sp) }
    }
}

@Composable
private fun BisectItem(fg: Color, label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(tr(label), color = fg, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** What BoxApp shows with "entire UI" off: a blank page holding only the bisect section. */
@Composable
fun GpuBisectScreen() {
    MaterialTheme(colorScheme = lightColorScheme()) {
        Box(Modifier.fillMaxSize().background(Color.White)) {
            GpuBisectSection(
                Modifier
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 16.dp),
            )
        }
    }
}
