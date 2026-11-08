package io.nekohasekai.sfa.compose.pages

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import io.nekohasekai.sfa.compose.BoxController
import io.nekohasekai.sfa.compose.Json
import io.nekohasekai.sfa.compose.asLong
import io.nekohasekai.sfa.compose.json
import io.nekohasekai.sfa.compose.qr.ZxingQRCodeAnalyzer
import io.nekohasekai.sfa.compose.theme.AppCorner
import io.nekohasekai.sfa.compose.theme.AppShape
import io.nekohasekai.sfa.compose.theme.AppTextStyle
import io.nekohasekai.sfa.compose.theme.BoxTheme
import io.nekohasekai.sfa.compose.ui.AppGlyphs
import io.nekohasekai.sfa.compose.ui.BoxPage
import io.nekohasekai.sfa.compose.ui.CommonCard
import io.nekohasekai.sfa.compose.ui.CommonCircleLoading
import io.nekohasekai.sfa.compose.ui.CommonPopupBox
import io.nekohasekai.sfa.compose.ui.CommonScaffold
import io.nekohasekai.sfa.compose.ui.CommonSwitch
import io.nekohasekai.sfa.compose.ui.ContextMenuRegion
import io.nekohasekai.sfa.compose.ui.ErrorState
import io.nekohasekai.sfa.compose.ui.FormPage
import io.nekohasekai.sfa.compose.ui.FormRow
import io.nekohasekai.sfa.compose.ui.FormRowTextField
import io.nekohasekai.sfa.compose.ui.GlyphIcon
import io.nekohasekai.sfa.compose.ui.IconAction
import io.nekohasekai.sfa.compose.ui.ItemPosition
import io.nekohasekai.sfa.compose.ui.ListItem
import io.nekohasekai.sfa.compose.ui.ListItemOpen
import io.nekohasekai.sfa.compose.ui.LocalBoxNavigator
import io.nekohasekai.sfa.compose.ui.LocalDialogs
import io.nekohasekai.sfa.compose.ui.LocalNavLevel
import io.nekohasekai.sfa.compose.ui.NullStatus
import io.nekohasekai.sfa.compose.ui.NullStatusIllustration
import io.nekohasekai.sfa.compose.ui.NullStatusSwitcher
import io.nekohasekai.sfa.compose.ui.PopupMenuItem
import io.nekohasekai.sfa.compose.ui.PopupMenuSection
import io.nekohasekai.sfa.compose.ui.QrCodeView
import io.nekohasekai.sfa.compose.ui.ReorderableColumn
import io.nekohasekai.sfa.compose.ui.Section
import io.nekohasekai.sfa.compose.ui.SelectionBadge
import io.nekohasekai.sfa.compose.ui.SortHandle
import io.nekohasekai.sfa.compose.ui.TonalButton
import io.nekohasekai.sfa.compose.ui.TonalButtonSize
import io.nekohasekai.sfa.compose.ui.TonalIconButton
import io.nekohasekai.sfa.compose.ui.bottomInset
import io.nekohasekai.sfa.compose.ui.contentTopPadding
import io.nekohasekai.sfa.compose.ui.isChinese
import io.nekohasekai.sfa.compose.ui.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/** libbox remote_profile.go stores the display name in the URL fragment. */
fun remoteProfileLink(profile: Json): String = Uri.Builder()
    .scheme("sing-box")
    .authority("import-remote-profile")
    .appendQueryParameter("url", profile["url"].toString())
    .fragment(profile["name"].toString())
    .build()
    .toString()

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(null, text))
}

private fun clipboardText(context: Context): String? {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    return clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
}

/** profile_card.dart's profileCardHeight. */
@Composable
fun profileCardHeight(withMeter: Boolean = false): Float {
    val scale = LocalDensity.current.fontScale
    val meter = if (withMeter) (PROFILE_METER_GAP + PROFILE_METER_HEIGHT).toFloat() else 0f
    return AppTextStyle.Headline.leading * scale + 4 + AppTextStyle.Footnote.leading * scale + meter + 2 * 10f
}

// ---------------------------------------------------------------------------
// pages/profiles.dart
// ---------------------------------------------------------------------------

@Composable
fun ProfilesPage(box: BoxController, picker: Boolean = false) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val navigator = LocalBoxNavigator.current
    val dialogs = LocalDialogs.current
    val updating = remember { mutableStateListOf<Long>() }
    var updatingAll by remember { mutableStateOf(false) }
    val profiles = box.profiles
    val copied = tr("Copied")

    suspend fun update(profile: Json) {
        val id = profile["id"].asLong() ?: return
        if (id in updating) return
        updating.add(id)
        try {
            box.act { updateProfile(id.asLong()) }
        } finally {
            updating.remove(id)
        }
    }
    fun updateAll() {
        if (updatingAll || updating.isNotEmpty()) return
        val remotes = box.profiles.filter { it["remote"] == true }
        updatingAll = true
        scope.launch {
            try {
                // Native profile writes share a mutex; keep updates serialized.
                for (profile in remotes) {
                    if (box.remote) break
                    update(profile)
                }
            } finally {
                updatingAll = false
            }
        }
    }
    fun export(profile: Json) = scope.launch {
        try {
            val content = box.call { readProfile(profile["id"].asLong()) }
            box.call { saveDocument(profile["name"].toString() + ".json", content) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            box.report(error)
        }
    }
    fun share(profile: Json) = scope.launch {
        try {
            val content = if (profile["remote"] == true) remoteProfileLink(profile) else box.call { readProfile(profile["id"].asLong()) }
            box.call { shareText(content) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            box.report(error)
        }
    }
    val t = trFor()
    fun menu(profile: Json): List<PopupMenuItem> = buildList {
        add(PopupMenuItem(t("Edit"), AppGlyphs.edit, onPressed = { navigator?.openForm { ProfileDetailsPage(box, profile) } }))
        if (profile["remote"] == true) {
            add(PopupMenuItem(t("Update"), AppGlyphs.sync, onPressed = { scope.launch { update(profile) } }))
            add(
                PopupMenuItem(t("Copy link"), AppGlyphs.copy, onPressed = {
                    copyToClipboard(context, profile["url"].toString())
                    box.message(copied)
                }),
            )
            add(
                PopupMenuItem(t("QR code"), AppGlyphs.qrCode, onPressed = {
                    navigator?.openForm {
                        BoxPage("QR code") {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { QrCodeView(remoteProfileLink(profile)) }
                        }
                    }
                }),
            )
        }
        add(PopupMenuItem(t("Share"), AppGlyphs.upload, onPressed = { share(profile) }))
        add(PopupMenuItem(t("Export"), AppGlyphs.export, onPressed = { export(profile) }))
        add(
            PopupMenuItem(t("Delete"), AppGlyphs.delete, danger = true, onPressed = {
                scope.launch {
                    if (dialogs?.confirm("Delete this profile?", message = "This cannot be undone.") == true) {
                        box.act { deleteProfile(profile["id"].asLong()) }
                    }
                }
            }),
        )
    }
    val busy = updatingAll || updating.isNotEmpty()
    CommonScaffold(
        title = tr("Profiles"),
        actions = if (box.remote || profiles.isEmpty() || picker) emptyList() else listOf({
            AddProfileMenu(box) { open -> TonalIconButton(IconAction(AppGlyphs.addCircle, tr("Add profile"), open)) }
        }),
        iconActions = if (box.remote || picker || profiles.isEmpty()) emptyList() else listOf(
            IconAction(AppGlyphs.sync, tr("Update all"), if (busy || profiles.none { it["remote"] == true }) null else ({ updateAll() }), loading = busy),
            IconAction(AppGlyphs.sort, tr("Reorder"), if (profiles.size < 2) null else ({ navigator?.openForm { ProfileOrderPage(box) } })),
        ),
    ) {
        if (box.remote) {
            NullStatus(label = tr("Disconnect remote session to edit local profiles"), illustration = NullStatusIllustration.Profile)
        } else {
            NullStatusSwitcher(
                isEmpty = profiles.isEmpty(),
                nullStatus = {
                    NullStatus(
                        label = tr("No profiles yet"),
                        illustration = NullStatusIllustration.Profile,
                        description = tr("Import a sing-box JSON configuration to get started."),
                        action = {
                            AddProfileMenu(box) { open -> TonalButton(tr("Add profile"), open, glyph = AppGlyphs.addCircle, size = TonalButtonSize.Bar) }
                        },
                    )
                },
            ) {
                ProfilesGrid(box, profiles, box.selectedProfileId, picker) { profile ->
                    val id = profile["id"].asLong()
                    ProfileCard(
                        profile = profile,
                        selected = id == box.selectedProfileId,
                        updating = id in updating,
                        menuItems = menu(profile),
                        onPressed = if (box.transitioning) null else ({
                            scope.launch {
                                if (!box.transitioning && !box.remote) {
                                    if (box.act { selectProfile(profile["id"].asLong()) } && picker) navigator?.pop()
                                }
                            }
                        }),
                    )
                }
            }
        }
    }
}

@Composable
internal fun trFor(): (String) -> String {
    val chinese = isChinese()
    return { value -> if (chinese) io.nekohasekai.sfa.compose.BoxStrings.zh[value] ?: value else value }
}

@Composable
private fun ProfilesGrid(box: BoxController, profiles: List<Json>, selectedId: Long, picker: Boolean, item: @Composable (Json) -> Unit) {
    val active = LocalPageActive.current
    val state = rememberLazyGridState()
    val height = profileCardHeight(withMeter = profiles.any { it.subscriptionInfo()?.usage != null })
    var revealed by remember { mutableStateOf(false) }
    val spacing = io.nekohasekai.sfa.compose.ui.cardSpacingOf(io.nekohasekai.sfa.compose.ui.LocalViewMode.current).value
    val top = contentTopPadding()
    val bottom = 16.dp + bottomInset()
    val density = LocalDensity.current.density
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = ((maxWidth.value - 32 + spacing) / (300 + spacing)).toInt().coerceIn(1, 3)
        LaunchedEffect(active, profiles.isNotEmpty()) {
            if (!revealed && !picker && active && profiles.isNotEmpty()) {
                revealed = true
                val index = profiles.indexOfFirst { it["id"].asLong() == selectedId }
                if (index >= 0) {
                    val info = state.layoutInfo
                    val viewport = info.viewportEndOffset - info.viewportStartOffset
                    val rowHeight = height + spacing
                    if ((index / columns + 1) * rowHeight * density > viewport) state.scrollToItem(index)
                }
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = state,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = top, bottom = bottom),
            verticalArrangement = Arrangement.spacedBy(spacing.dp),
            horizontalArrangement = Arrangement.spacedBy(spacing.dp),
        ) {
            items(profiles, key = { it["id"].toString() }) { profile ->
                Box(Modifier.fillMaxWidth().height(height.dp)) { item(profile) }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// components/profile_card.dart
// ---------------------------------------------------------------------------

@Composable
fun ProfileCard(profile: Json, selected: Boolean, updating: Boolean, menuItems: List<PopupMenuItem>, onPressed: (() -> Unit)?) {
    val scheme = BoxTheme.scheme
    // FlClash's url-profile row: expiry chip after the title, used / total as the subtitle.
    val info = profile.subscriptionInfo()
    val usage = info?.usage
    ContextMenuRegion(if (updating) null else menuItems) {
        CommonCard(Modifier.fillMaxSize(), radius = AppCorner.XL, isSelected = selected, onPressed = onPressed) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    SubscriptionChipTitle(profile["name"].toString(), info, AppTextStyle.Headline.of().copy(color = scheme.onSurface))
                    Spacer(Modifier.height(4.dp))
                    // fk-box: the core the profile runs on, as a chip before the subtitle.
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ProfileCoreChip(profile)
                        Text(
                            info?.let { subscriptionTrafficLine(it) } ?: tr(if (profile["remote"] == true) "Remote" else "Local"),
                            Modifier.weight(1f, fill = false),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = AppTextStyle.Footnote.of().copy(color = scheme.onSurface.copy(alpha = 0.6f)),
                        )
                    }
                    if (info != null && usage != null) {
                        Spacer(Modifier.height(PROFILE_METER_GAP.dp))
                        SubscriptionProgressBar(usage, subscriptionColor(info, scheme, System.currentTimeMillis()))
                    }
                }
                Spacer(Modifier.padding(start = 16.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AnimatedContent(
                        targetState = updating,
                        transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) },
                        contentAlignment = Alignment.CenterEnd,
                        label = "updating",
                    ) { busy ->
                        if (busy) {
                            CommonCircleLoading(size = 24.dp)
                        } else {
                            ProfileUpdateTime(profile["updatedAt"].asLong(), AppTextStyle.Footnote.of().copy(color = scheme.onSurface.copy(alpha = 0.6f)), showLabel = false)
                        }
                    }
                    if (selected) SelectionBadge(size = 24.dp)
                }
            }
        }
    }
}

/** The core a profile resolves to (sing-box, Xray or mihomo), in the expiry chip's shape. */
@Composable
fun ProfileCoreChip(profile: Json) {
    val core = profile["resolvedCore"] as? String ?: return
    val label = coreLabels().firstOrNull { it.first == core }?.second ?: core
    val color = BoxTheme.scheme.secondary
    Box(Modifier.background(color.copy(alpha = 0.15f), AppShape.full).padding(horizontal = 8.dp, vertical = 2.dp)) {
        Text(label, maxLines = 1, style = AppTextStyle.Caption1.of(true).copy(color = color))
    }
}

/** The quota bar's gap and height, added to the row height while any profile shows one. */
private const val PROFILE_METER_GAP = 6
private const val PROFILE_METER_HEIGHT = 4

// ---------------------------------------------------------------------------
// Adding profiles
// ---------------------------------------------------------------------------

/** decodeImport: links become profiles natively; anything else must pass the config check. */
suspend fun decodeImport(box: BoxController, text: String): Json {
    if (text.startsWith("https://") || text.startsWith("http://") || text.startsWith("sing-box://")) {
        return json(box.call { decodeProfileIntent(text) })
    }
    // A JSON array of configs comes back split as "entries" (see openImported).
    val decoded = json(box.call { decodeProfileText(text, "Imported profile") })
    return if (decoded["entries"] is List<*>) decoded else mapOf("name" to decoded["name"], "content" to decoded["content"], "core" to "auto")
}

/**
 * Opens the profile form for one decoded profile. A JSON array of configs (v2rayNG's multi
 * custom-config list) arrives split as "entries" and is saved as one profile per config;
 * [importedMessage] reports how many, with "%d" for the count.
 */
suspend fun openImported(box: BoxController, navigator: io.nekohasekai.sfa.compose.ui.BoxNavigator?, data: Json, importedMessage: String) {
    val entries = data["entries"] as? List<*>
    if (entries == null) {
        navigator?.openForm { ProfileForm(box, data) }
        return
    }
    val ids = box.call { saveProfiles(entries, data["core"] as? String ?: "auto") } as? List<*>
    box.message(importedMessage.replace("%d", (ids?.size ?: entries.size).toString()))
}

/** The cores a profile can run on, "auto" first. */
@Composable
fun coreLabels(): List<Pair<String, String>> = listOf(
    "auto" to tr("Automatic"),
    "sing-box" to "sing-box",
    "xray" to "Xray",
    "mihomo" to "mihomo",
)

private suspend fun importProfile(box: BoxController, context: Context, source: String, navigator: io.nekohasekai.sfa.compose.ui.BoxNavigator?, emptyClipboard: String, importedMessage: String) {
    try {
        when (source) {
            "File" -> {
                val result = box.call { importProfileFile() }
                if (result != null) openImported(box, navigator, json(result), importedMessage)
            }
            "Clipboard" -> {
                val text = clipboardText(context)?.trim()
                if (text.isNullOrEmpty()) throw IllegalStateException(emptyClipboard)
                val data = decodeImport(box, text)
                openImported(box, navigator, data, importedMessage)
            }
            "Scan QR code" -> navigator?.pushPage({ ScanPage(box) }) { result ->
                if (result is Map<*, *>) box.launch {
                    try {
                        openImported(box, navigator, json(result), importedMessage)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        box.report(error)
                    }
                }
            }
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        box.report(error)
    }
}

/** AddProfileMenu: the add button's menu of import sources. */
@Composable
fun AddProfileMenu(box: BoxController, target: @Composable (open: () -> Unit) -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val navigator = LocalBoxNavigator.current
    var busy by remember { mutableStateOf(false) }
    val emptyClipboard = tr("Empty clipboard")
    val importedMessage = tr("Imported %d profiles")
    val t = trFor()
    val import: (String) -> Unit = { source ->
        if (!busy) {
            busy = true
            scope.launch {
                try {
                    importProfile(box, context, source, navigator, emptyClipboard, importedMessage)
                } finally {
                    busy = false
                }
            }
        }
    }
    CommonPopupBox(
        listOf(
            PopupMenuItem(t("Scan QR code"), AppGlyphs.qrCode, onPressed = { import("Scan QR code") }),
            PopupMenuItem(t("File"), AppGlyphs.importFile, onPressed = { import("File") }),
            PopupMenuItem(t("Import from URL"), AppGlyphs.cloudDownload, onPressed = { navigator?.openForm { ProfileForm(box, mapOf("url" to "", "remote" to true)) } }),
            PopupMenuItem(t("Clipboard"), AppGlyphs.copy, onPressed = { import("Clipboard") }),
            PopupMenuItem(t("New profile"), AppGlyphs.customize, section = PopupMenuSection(), onPressed = { navigator?.openForm { ProfileForm(box, mapOf("content" to "{}")) } }),
        ),
    ) { open -> target(if (busy) ({}) else open) }
}

/** pages/profiles.dart's AddProfilePage. */
@Composable
fun AddProfilePage(box: BoxController) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val navigator = LocalBoxNavigator.current
    var loading by remember { mutableStateOf(false) }
    val emptyClipboard = tr("Empty clipboard")
    val importedMessage = tr("Imported %d profiles")
    val import: (String) -> Unit = { source ->
        if (!loading) {
            loading = true
            scope.launch {
                try {
                    importProfile(box, context, source, navigator, emptyClipboard, importedMessage)
                } finally {
                    loading = false
                }
            }
        }
    }
    BoxPage("Add profile", loading = loading) {
        val sources = listOf("File" to AppGlyphs.profiles, "Clipboard" to AppGlyphs.copy, "Scan QR code" to AppGlyphs.qrCode)
        Section(count = 5, footer = tr("Subscription can be sing-box JSON, Xray JSON, Clash YAML or a node list. Automatic picks the core from the content.")) { index, position ->
            when (index) {
                0 -> ListItemOpen(tr("Import from URL"), position, leading = AppGlyphs.link) { ProfileForm(box, mapOf("url" to "", "remote" to true)) }
                4 -> ListItemOpen(tr("New profile"), position, leading = AppGlyphs.add) { ProfileForm(box, mapOf("content" to "{}")) }
                else -> {
                    val (label, glyph) = sources[index - 1]
                    ListItem(
                        title = { Text(tr(label)) },
                        leading = { GlyphIcon(glyph) },
                        position = position,
                        onClick = if (loading) null else ({ import(label) }),
                    )
                }
            }
        }
    }
}

/** pages/profiles.dart's ProfileForm: name, remote URL and its updates. */
@Composable
fun ProfileForm(box: BoxController, initial: Json) {
    val navigator = LocalBoxNavigator.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initial["name"] as? String ?: "") }
    var url by remember { mutableStateOf(initial["url"] as? String ?: "") }
    val initialInterval = remember { (initial["interval"] ?: 60).toString().removeSuffix(".0") }
    var interval by remember { mutableStateOf(initialInterval) }
    var remote by remember { mutableStateOf(initial["remote"] == true || url.isNotEmpty()) }
    var auto by remember { mutableStateOf(initial["autoUpdate"] != false) }
    var core by remember { mutableStateOf(initial["core"] as? String ?: "auto") }
    var saving by remember { mutableStateOf(false) }
    var attempted by remember { mutableStateOf(false) }
    val nameRequired = tr("Name") + ": " + tr("Required")
    val urlInvalid = tr("Subscription URL")
    val intervalShort = tr("At least 15 minutes")
    fun issue(): Pair<String, String>? {
        if (name.trim().isEmpty()) return "name" to nameRequired
        if (!remote) return null
        val uri = runCatching { Uri.parse(url.trim()) }.getOrNull()
        if (uri == null || uri.scheme !in listOf("http", "https") || uri.host.isNullOrEmpty()) return "url" to urlInvalid
        if (auto && (interval.toIntOrNull() ?: 0) < 15) return "interval" to intervalShort
        return null
    }
    val shown = if (attempted) issue() else null
    val save: () -> Unit = save@{
        if (saving) return@save
        if (issue() != null) {
            attempted = true
            return@save
        }
        saving = true
        scope.launch {
            try {
                val parsed = interval.toIntOrNull()
                val previous = initial["interval"].asLong()?.toInt() ?: 60
                box.call {
                    saveProfile(initial + mapOf(
                        "name" to name.trim(),
                        "url" to if (remote) url.trim() else "",
                        "autoUpdate" to (remote && auto),
                        "interval" to if (parsed != null && parsed >= 15) parsed else if (previous >= 15) previous else 60,
                        // A typed interval outranks the provider's profile-update-interval.
                        "intervalEdited" to (initial["intervalEdited"] == true || (remote && auto && interval != initialInterval)),
                        "core" to core,
                    ))
                }
                navigator?.pop()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                box.report(error)
            }
            saving = false
        }
    }
    FormPage(if (initial["id"] == null) "Add profile" else "Edit", onSave = save, error = shown?.second, saving = saving) {
        val rows = buildList<@Composable (ItemPosition) -> Unit> {
            add { p ->
                FormRow(tr("Name"), p, invalid = shown?.first == "name") {
                    FormRowTextField(name, { name = it }, hint = tr("Required"), imeAction = ImeAction.Next)
                }
            }
            add { p ->
                FormRow(tr("Remote"), p, onPressed = { remote = !remote }) { CommonSwitch(remote, { remote = it }) }
            }
            add { p ->
                val labels = coreLabels()
                val items = labels.map { (value, label) -> PopupMenuItem(label, checked = core == value, onPressed = { core = value }) }
                CommonPopupBox(items) { open ->
                    FormRow(tr("Core"), p, onPressed = open) { Text(labels.first { it.first == core }.second) }
                }
            }
            if (remote) {
                add { p ->
                    FormRow(tr("URL"), p, invalid = shown?.first == "url") {
                        FormRowTextField(url, { url = it }, hint = tr("Required"), keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next, autocorrect = false)
                    }
                }
            }
        }
        Section(footer = tr(if (remote) "Subscription can be sing-box JSON, Xray JSON, Clash YAML or a node list. Automatic picks the core from the content." else "Automatic picks the core from the content."), rows = rows)
        if (remote) {
            val updateRows = buildList<@Composable (ItemPosition) -> Unit> {
                add { p -> FormRow(tr("Auto update"), p, onPressed = { auto = !auto }) { CommonSwitch(auto, { auto = it }) } }
                if (auto) {
                    add { p ->
                        FormRow(tr("Update interval"), p, invalid = shown?.first == "interval") {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Box(Modifier.weight(1f, fill = false)) {
                                    FormRowTextField(interval, { value -> interval = value.filter(Char::isDigit) }, hint = tr("Required"), keyboardType = KeyboardType.Number, imeAction = ImeAction.Done)
                                }
                                Text(tr("minutes"), style = AppTextStyle.Subheadline.of())
                            }
                        }
                    }
                }
            }
            Section(footer = if (auto) tr("At least 15 minutes") else null, rows = updateRows)
        }
        if (initial["id"] != null) {
            Section(count = 2) { index, position ->
                if (index == 0) {
                    ListItemOpen(tr("Open configuration"), position, leading = AppGlyphs.code) { ProfileEditor(box, initial) }
                } else {
                    ListItem(
                        title = { Text(tr("Last updated")) },
                        subtitle = { Text(if ((initial["updatedAt"].asLong() ?: 0) <= 0) tr("Unknown") else localDate(initial["updatedAt"])) },
                        position = position,
                    )
                }
            }
        }
    }
}

/** pages/profiles.dart's ProfileOrderPage. */
@Composable
fun ProfileOrderPage(box: BoxController) {
    val navigator = LocalBoxNavigator.current
    val scope = rememberCoroutineScope()
    val profiles = remember { mutableStateListOf<Json>().apply { addAll(box.profiles) } }
    var saving by remember { mutableStateOf(false) }
    CommonScaffold(
        title = tr("Reorder"),
        confirmationAction = IconAction(AppGlyphs.check, tr("Save"), if (saving) null else ({
            saving = true
            scope.launch {
                val saved = box.act { reorderProfiles(profiles.map { (it["id"] as Number).toLong() }) }
                if (saved) navigator?.pop() else saving = false
            }
        }), loading = saving),
    ) {
        ReorderableColumn(
            items = profiles.toList(),
            key = { it["id"].toString() },
            onMove = { from, to -> profiles.add(to, profiles.removeAt(from)) },
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = contentTopPadding(), bottom = 32.dp + bottomInset()),
            enabled = !saving,
        ) { index, profile ->
            ListItem(
                title = { Text(profile["name"].toString()) },
                trailing = { SortHandle() },
                position = ItemPosition.of(index, profiles.size),
                startPadding = 16.dp,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// ScanPage: the camera, frames handed to BoxQrScanner through profiles.scan
// ---------------------------------------------------------------------------

@Composable
fun ScanPage(box: BoxController) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val navigator = LocalBoxNavigator.current
    val level = LocalNavLevel.current
    val scope = rememberCoroutineScope()
    val session = remember { (System.nanoTime() / 1000).toString() }
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var decoded by remember { mutableStateOf(0) }
    var total by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var torch by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<androidx.camera.core.Camera?>(null) }
    val state = remember { object { var busy = false; var complete = false } }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) permission.launch(Manifest.permission.CAMERA) }
    DisposableEffect(Unit) {
        onDispose { box.launchAct { closeProfileScan(session) } }
    }
    val scan: (String) -> Unit = scan@{ text ->
        if (state.busy || state.complete) return@scan
        state.busy = true
        scope.launch {
            try {
                val result = json(box.call { scanProfile(session, text) })
                val profile = when {
                    result["profile"] is Map<*, *> -> json(result["profile"])
                    result["text"] is String -> decodeImport(box, result["text"] as String)
                    else -> null
                }
                if (profile != null) {
                    state.complete = true
                    if (level != null) navigator?.pop(profile)
                } else {
                    decoded = result["decoded"].asLong()?.toInt() ?: 0
                    total = result["total"].asLong()?.toInt() ?: 0
                    error = null
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                error = BoxController.describe(failure)
            } finally {
                state.busy = false
            }
        }
    }
    CommonScaffold(
        title = tr("Scan QR code"),
        iconActions = listOf(
            IconAction(if (torch) AppGlyphs.torchOff else AppGlyphs.torch, "Flash", {
                camera?.let {
                    torch = !torch
                    it.cameraControl.enableTorch(torch)
                }
            }),
        ),
    ) {
        Column(Modifier.fillMaxSize().padding(top = io.nekohasekai.sfa.compose.ui.appBarInset())) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    !granted -> NullStatus(
                        label = tr("Camera"),
                        illustration = NullStatusIllustration.Camera,
                        action = { TonalButton(tr("Retry"), { permission.launch(Manifest.permission.CAMERA) }, size = TonalButtonSize.Bar) },
                    )
                    cameraError != null -> ErrorState(cameraError!!) { cameraError = null }
                    else -> AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { viewContext ->
                            val view = PreviewView(viewContext)
                            val future = ProcessCameraProvider.getInstance(viewContext)
                            future.addListener({
                                try {
                                    val provider = future.get()
                                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                                    val analysis = ImageAnalysis.Builder()
                                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                        .build()
                                    val executor = Executors.newSingleThreadExecutor()
                                    analysis.setAnalyzer(executor, ZxingQRCodeAnalyzer(
                                        onSuccess = { text -> if (text.isNotEmpty()) view.post { scan(text) } },
                                        onFailure = { failure -> view.post { error = failure.message } },
                                    ))
                                    provider.unbindAll()
                                    camera = provider.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                                } catch (failure: Exception) {
                                    cameraError = BoxController.describe(failure)
                                }
                            }, ContextCompat.getMainExecutor(viewContext))
                            view
                        },
                    )
                }
            }
            if (total > 0) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    LinearProgressIndicator(progress = { decoded.toFloat() / total }, modifier = Modifier.fillMaxWidth())
                    Text("${tr("Scanning configuration")} $decoded / $total")
                }
            }
            if (error != null) Text(error!!, Modifier.padding(16.dp))
        }
    }
}

/** Whether a profile (as the profile list sends it) runs on sing-box, whose options the structured pages edit. */
fun Json.runsSingBox(): Boolean = (this["resolvedCore"] as? String ?: "sing-box") == "sing-box"
