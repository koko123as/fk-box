package io.nekohasekai.sfa.compose.pages

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.compose.BoxController
import io.nekohasekai.sfa.compose.Json
import io.nekohasekai.sfa.compose.bytes
import io.nekohasekai.sfa.compose.rows
import io.nekohasekai.sfa.compose.ui.AppGlyphs
import io.nekohasekai.sfa.compose.ui.BoxPage
import io.nekohasekai.sfa.compose.ui.CommonCircleLoading
import io.nekohasekai.sfa.compose.ui.ContextMenuRegion
import io.nekohasekai.sfa.compose.ui.IconAction
import io.nekohasekai.sfa.compose.ui.ListItem
import io.nekohasekai.sfa.compose.ui.ListItemToggle
import io.nekohasekai.sfa.compose.ui.LocalDialogs
import io.nekohasekai.sfa.compose.ui.PopupMenuItem
import io.nekohasekai.sfa.compose.ui.Section
import io.nekohasekai.sfa.compose.ui.setClipboard
import io.nekohasekai.sfa.compose.ui.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

// ---------------------------------------------------------------------------
// views/resources.dart (FlClash): auto update, its interval in hours, and one
// row per geo file with its size, last update, single update, URL edit and
// copy. Only the geo section is carried over; FK-box has no icon sets,
// scripts or rule providers of FlClash's kind.
// ---------------------------------------------------------------------------

@Composable
fun ResourcesPage(box: BoxController) {
    val scope = rememberCoroutineScope()
    val dialogs = LocalDialogs.current
    val context = LocalContext.current
    var items by remember { mutableStateOf<List<Json>?>(null) }
    val updating = remember { mutableStateListOf<String>() }
    val updated = tr("Updated")
    val upToDate = tr("Already up to date")
    val copied = tr("Copied")
    val urlTitle = tr("Download URL")
    val invalidUrl = tr("Not a valid HTTP or HTTPS URL")
    val hours = tr("hours")
    val intervalTitle = tr("Update interval")

    suspend fun reload() {
        try {
            items = rows(box.call("geo.list"))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            items = items ?: emptyList()
            box.report(error)
        }
    }

    /** Returns false when the update failed; the old file stays either way. */
    suspend fun updateOne(name: String, label: String, announce: Boolean): Boolean {
        if (name in updating) return true
        updating.add(name)
        return try {
            val changed = box.call("geo.update", mapOf("name" to name)) == true
            if (announce) box.message("$label: " + if (changed) updated else upToDate)
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            box.report(Exception("$label: " + (error.message ?: error.toString()), error))
            false
        } finally {
            updating.remove(name)
        }
    }

    fun updateAll() {
        val current = items ?: return
        scope.launch {
            val results = current.map { item ->
                async { updateOne(item["name"] as String, item["label"] as String, announce = false) }
            }.awaitAll()
            reload()
            if (results.all { it }) box.message(updated)
        }
    }

    LaunchedEffect(Unit) { reload() }

    val autoUpdate = box.settings["geoAutoUpdate"] == true
    val interval = (box.settings["geoUpdateInterval"] as? Number)?.toInt() ?: 24
    BoxPage(
        "Resources",
        loading = items == null,
        actions = listOf(IconAction(AppGlyphs.sync, tr("Update all"), if (items.isNullOrEmpty() || updating.isNotEmpty()) null else ({ updateAll() }), loading = updating.isNotEmpty())),
    ) {
        Section(count = if (autoUpdate) 2 else 1) { index, position ->
            if (index == 0) {
                ListItemToggle(tr("Auto update"), autoUpdate, position) { value ->
                    scope.launch { box.setSetting("geoAutoUpdate", value, restart = false) }
                }
            } else {
                ListItem(
                    title = { Text(tr("Update interval")) },
                    trailing = { Text("$interval $hours") },
                    position = position,
                    onClick = {
                        scope.launch {
                            val value = dialogs?.input(
                                intervalTitle,
                                interval.toString(),
                                KeyboardType.Number,
                                suffixText = hours,
                                validator = { text -> if ((text.toIntOrNull() ?: 0) in 1..8760) null else "1–8760" },
                            )
                            value?.toIntOrNull()?.let { box.setSetting("geoUpdateInterval", it, restart = false) }
                        }
                    },
                )
            }
        }
        val list = items.orEmpty()
        Section(
            title = tr("Geo resources"),
            count = list.size,
            footer = tr("Xray and mihomo share these files. A failed update keeps the current file; changes apply when the core next starts."),
        ) { index, position ->
            val item = list[index]
            val name = item["name"] as String
            val label = item["label"] as String
            val url = item["url"] as? String ?: ""
            val defaultUrl = item["defaultUrl"] as? String ?: ""
            val size = item["size"] as? Number
            val updatedAt = (item["updatedAt"] as? Number)?.toLong()
            val source = when (item["source"]) {
                "builtin" -> tr("Built in")
                "download" -> tr("Downloaded")
                null -> null
                else -> "mihomo"
            }
            fun editUrl() {
                scope.launch {
                    val value = dialogs?.input(
                        "$label · $urlTitle",
                        url,
                        KeyboardType.Uri,
                        resetValue = defaultUrl,
                        validator = { text -> if (text.trim().matches(Regex("^https?://[^\\s/]+.*$"))) null else invalidUrl },
                    ) ?: return@launch
                    try {
                        items = rows(box.call("geo.setUrl", mapOf("name" to name, "url" to value.trim())))
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        box.report(error)
                    }
                }
            }
            val busy = name in updating
            val menu = buildList {
                if (!busy) add(PopupMenuItem(tr("Update"), AppGlyphs.sync, onPressed = { scope.launch { updateOne(name, label, announce = true); reload() } }))
                add(PopupMenuItem(tr("Edit"), AppGlyphs.edit, onPressed = { editUrl() }))
                add(PopupMenuItem(tr("Copy link"), AppGlyphs.copy, onPressed = { setClipboard(context, url); box.message(copied) }))
            }
            ContextMenuRegion(menu) {
                ListItem(
                    title = { Text(label) },
                    subtitle = {
                        Text(
                            if (updatedAt != null) {
                                listOfNotNull(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(updatedAt)), source).joinToString(" · ")
                            } else {
                                tr("Unknown")
                            },
                        )
                    },
                    trailing = {
                        if (busy) CommonCircleLoading(size = 24.dp) else Text(size?.let { bytes(it) } ?: "—")
                    },
                    position = position,
                    onClick = { editUrl() },
                )
            }
        }
    }
}
