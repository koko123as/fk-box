package io.nekohasekai.sfa.compose.pages

import androidx.compose.foundation.text.selection.SelectionContainer

import io.nekohasekai.sfa.bridge.BoxSettings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.BoxController
import io.nekohasekai.sfa.compose.Json
import io.nekohasekai.sfa.compose.asDouble
import io.nekohasekai.sfa.compose.json
import io.nekohasekai.sfa.compose.rows
import io.nekohasekai.sfa.compose.theme.AppShape
import io.nekohasekai.sfa.compose.theme.AppTextStyle
import io.nekohasekai.sfa.compose.theme.BoxTheme
import io.nekohasekai.sfa.compose.ui.AppIcons
import io.nekohasekai.sfa.compose.ui.BoxEditGuard
import io.nekohasekai.sfa.compose.ui.BoxPage
import io.nekohasekai.sfa.compose.ui.CommonCircleLoading
import io.nekohasekai.sfa.compose.ui.CommonDialog
import io.nekohasekai.sfa.compose.ui.CommonScaffold
import io.nekohasekai.sfa.compose.ui.DialogAction
import io.nekohasekai.sfa.compose.ui.ErrorState
import io.nekohasekai.sfa.compose.ui.FormPage
import io.nekohasekai.sfa.compose.ui.FormRow
import io.nekohasekai.sfa.compose.ui.FormRowTextField
import androidx.compose.ui.graphics.vector.ImageVector
import io.nekohasekai.sfa.compose.ui.AppIcon
import io.nekohasekai.sfa.compose.ui.IconAction
import io.nekohasekai.sfa.compose.ui.ItemPosition
import io.nekohasekai.sfa.compose.ui.ListItem
import io.nekohasekai.sfa.compose.ui.ListItemInput
import io.nekohasekai.sfa.compose.ui.ListItemOpen
import io.nekohasekai.sfa.compose.ui.ListItemOptions
import io.nekohasekai.sfa.compose.ui.ListItemToggle
import io.nekohasekai.sfa.compose.ui.LocalBoxNavigator
import io.nekohasekai.sfa.compose.ui.LocalDialogs
import io.nekohasekai.sfa.compose.ui.LocalNavLevel
import io.nekohasekai.sfa.compose.ui.NullStatus
import io.nekohasekai.sfa.compose.ui.NullStatusIllustration
import io.nekohasekai.sfa.compose.ui.NullStatusSwitcher
import io.nekohasekai.sfa.compose.ui.PopupMenuItem
import io.nekohasekai.sfa.compose.ui.PopupMenuSection
import io.nekohasekai.sfa.compose.ui.SearchState
import io.nekohasekai.sfa.compose.ui.Section
import io.nekohasekai.sfa.compose.ui.TonalButton
import io.nekohasekai.sfa.compose.ui.TonalButtonSize
import io.nekohasekai.sfa.compose.ui.TonalChip
import io.nekohasekai.sfa.compose.ui.bottomInset
import io.nekohasekai.sfa.compose.ui.contentTopPadding
import io.nekohasekai.sfa.compose.ui.raisedSurfaceLevel
import io.nekohasekai.sfa.compose.ui.rememberUpdated
import io.nekohasekai.sfa.compose.ui.sectionsTopPadding
import io.nekohasekai.sfa.compose.ui.setClipboard
import io.nekohasekai.sfa.compose.ui.tr
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

// ---------------------------------------------------------------------------
// pages/settings.dart: General, Service and Core
// ---------------------------------------------------------------------------

/** settings.dart's NativeToggle: a native setting, restarting the service as asked. */
@Composable
private fun NativeToggle(box: BoxController, title: String, setting: String, position: ItemPosition, restart: Boolean = true, autoRestart: Boolean = false) {
    val scope = rememberCoroutineScope()
    ListItemToggle(
        tr(title),
        box.settings[setting] == true,
        position,
        onChanged = if (box.serviceSettingsChanging || autoRestart && box.transitioning && !box.remote) {
            null
        } else {
            { value -> scope.launch { box.setSetting(setting, value, restart = restart, autoRestart = autoRestart) } }
        },
    )
}

@Composable
fun GeneralSettingsPage(box: BoxController) {
    BoxPage("General") {
        Section(count = 2) { index, position ->
            if (index == 0) {
                ListItemOpen(tr("Service"), position, leading = AppIcons.vpn) { ServiceSettingsPage(box) }
            } else {
                ListItemOpen(tr("Core"), position, leading = AppIcons.cpu) { CoreSettingsPage(box) }
            }
        }
    }
}

@Composable
private fun ServiceSettingsPage(box: BoxController) {
    BoxPage("Service", loading = box.serviceSettingsChanging) {
        Section(count = 4) { index, position ->
            when (index) {
                0 -> NativeToggle(box, "Allow VPN bypass", "allowBypass", position, autoRestart = true)
                1 -> NativeToggle(box, "Auto redirect", "autoRedirect", position, autoRestart = true)
                2 -> NativeToggle(box, "Dynamic notification", "dynamicNotification", position, autoRestart = true)
                else -> ListItem(
                    title = { Text(tr("Battery optimization")) },
                    subtitle = { Text(tr(if (box.settings["batteryExempt"] == true) "Unrestricted" else "Allow background operation")) },
                    position = position,
                    onClick = { box.launchAct { openBatterySettings() } },
                )
            }
        }
    }
}

@Composable
private fun CoreSettingsPage(box: BoxController) {
    val scope = rememberCoroutineScope()
    BoxPage("Core") {
        // One switch per linked core, with its version; a core that is off is
        // skipped by auto and refuses to start. The last one cannot go off.
        val cores = listOf(
            Triple("sing-box", "singBoxEnabled", box.coreVersion),
            Triple("Xray", "xrayEnabled", box.xrayVersion),
            Triple("mihomo", "mihomoEnabled", box.mihomoVersion),
        )
        Section(title = tr("Cores"), count = cores.size, footer = tr("A core that is off is skipped when choosing automatically, and profiles that need it will not start.")) { index, position ->
            val (name, setting, version) = cores[index]
            val on = box.settings[setting] != false
            val lastOn = on && cores.count { box.settings[it.second] != false } == 1
            ListItemToggle(
                name,
                on,
                position,
                subtitle = version.ifEmpty { null },
                onChanged = if (box.serviceSettingsChanging || lastOn) null else ({ value -> scope.launch { box.setSetting(setting, value, restart = false) } }),
            )
        }
        Section(count = 5, footer = tr("Deprecation warnings and memory protection apply to the sing-box core only.")) { index, position ->
            when (index + 1) {
                1 -> NativeToggle(box, "Disable deprecation warnings", "disableDeprecatedWarnings", position, restart = false)
                2 -> NativeToggle(box, "Enable memory protection", "oomKillerEnabled", position)
                3 -> NativeToggle(box, "Disable core memory killer", "oomKillerDisabled", position)
                4 -> {
                    // The dialog edits the bare number; "MiB" is only shown beside it.
                    val limit = BoxSettings.memoryLimitText(box.settings["oomMemoryLimitMB"])
                    ListItemInput(
                        tr("Memory limit"),
                        limit,
                        position,
                        keyboardType = KeyboardType.Number,
                        display = "$limit MiB",
                        suffixText = "MiB",
                        validator = { value -> if (BoxSettings.parseMemoryLimit(value) != null) null else "10–4096 MiB" },
                    ) { value -> BoxSettings.parseMemoryLimit(value ?: "")?.let { limitMB -> scope.launch { box.setSetting("oomMemoryLimitMB", limitMB) } } }
                }
                else -> NativeToggle(box, "Power reports", "powerReportEnabled", position)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// pages/about.dart (hero and link badges adapted from FlClash v0.9.2 views/about.dart)
// ---------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AboutPage(box: BoxController) {
    val navigator = LocalBoxNavigator.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Json?>(null) }
    val scheme = BoxTheme.scheme
    fun check() {
        if (checking) return
        checking = true
        scope.launch {
            try {
                result = json(box.call { checkUpdates() })
            } catch (error: Throwable) {
                box.report(error)
            } finally {
                checking = false
            }
        }
    }
    CommonScaffold(title = tr("About"), loading = checking) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = contentTopPadding(), bottom = 32.dp + bottomInset())) {
            item(key = "hero") {
                Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.background(raisedSurfaceLevel.colorOf(scheme), AppShape.card).padding(14.dp)) {
                        Image(painterResource(R.drawable.box_icon), contentDescription = null, modifier = Modifier.size(68.dp))
                    }
                    Spacer(Modifier.height(20.dp))
                    Text("FK-box", style = AppTextStyle.Title2.of(emphasized = true).copy(color = scheme.onSurface))
                    Spacer(Modifier.height(10.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("v${box.version}", scheme.primary, scheme.onPrimary)
                        Pill("GPL-3.0", raisedSurfaceLevel.colorOf(scheme), scheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        tr("Kotlin + Jetpack Compose three-core client"),
                        Modifier.widthIn(max = 420.dp),
                        textAlign = TextAlign.Center,
                        style = AppTextStyle.Subheadline.of().copy(color = scheme.onSurfaceVariant, lineHeight = AppTextStyle.Subheadline.of().fontSize * 1.5f),
                    )
                    Spacer(Modifier.height(20.dp))
                    TonalButton(tr("Check for updates"), if (checking) null else ({ check() }), icon = AppIcons.sync, size = TonalButtonSize.Bar)
                }
            }
            item(key = "links") {
                Spacer(Modifier.height(8.dp))
                val links = listOf(
                    Triple(AppIcons.code, "Source code", "github.com/koko123as/fk-box") to "https://github.com/koko123as/fk-box",
                    Triple(AppIcons.code, "Based on sing-box-for-android", "SagerNet/sing-box-for-android · GPL-3.0") to "https://github.com/SagerNet/sing-box-for-android",
                    Triple(AppIcons.code, "Predecessor: B-box", "giturass/B-box · GPL-3.0") to "https://github.com/giturass/B-box",
                    Triple(AppIcons.cpu, "sing-box", listOf(box.coreVersion, "SagerNet/sing-box").filter { it.isNotEmpty() }.joinToString(" · ")) to "https://github.com/SagerNet/sing-box",
                    Triple(AppIcons.cpu, "Xray", listOf(box.xrayVersion, "XTLS/Xray-core").filter { it.isNotEmpty() }.joinToString(" · ")) to "https://github.com/XTLS/Xray-core",
                    Triple(AppIcons.cpu, "mihomo", listOf(box.mihomoVersion, "MetaCubeX/mihomo").filter { it.isNotEmpty() }.joinToString(" · ")) to "https://github.com/MetaCubeX/mihomo",
                    Triple(AppIcons.paintbrush, "FlClash design", "v0.9.2 · rewritten in Kotlin + Compose, no Flutter code · GPL-3.0") to "https://github.com/chen08209/FlClash/tree/7fd417fb9a9a277c6a9339a8ac87f907df7c25f7",
                )
                Section(title = tr("More"), count = links.size + 1) { index, position ->
                    if (index < links.size) {
                        val (info, url) = links[index]
                        ListItem(
                            leading = { LinkBadge(info.first) },
                            title = { Text(tr(info.second)) },
                            subtitle = { Text(info.third, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            trailing = { AppIcon(AppIcons.openExternal) },
                            position = position,
                            onClick = { box.launchAct { openUrl(url) } },
                        )
                    } else {
                        ListItem(
                            leading = { LinkBadge(AppIcons.info) },
                            title = { Text(tr("Open-source licenses")) },
                            position = position,
                            onClick = { navigator?.push { LicensesPage(box) } },
                        )
                    }
                }
            }
        }
    }
    result?.let { update -> UpdateDialog(box, update) { result = null } }
}

@Composable
private fun UpdateDialog(box: BoxController, result: Json, dismiss: () -> Unit) {
    val available = result["available"] == true
    val noMetadata = result["hasMetadata"] == false
    CommonDialog(
        onDismiss = dismiss,
        title = tr(if (available) "Update available" else "Check for updates"),
        actions = buildList {
            add(DialogAction(tr("Close"), dismiss))
            if (available || noMetadata) {
                add(
                    DialogAction(tr(if (result["downloadUrl"] == null) "Releases" else "Download"), {
                        dismiss()
                        box.launchAct { openUrl((result["downloadUrl"] ?: result["url"] ?: "https://github.com/koko123as/fk-box/releases") as? String) }
                    }),
                )
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (available) "FK-box ${result["version"]}" else tr(if (noMetadata) "No release metadata available" else "You are up to date"))
            val notes = result["notes"] as? String ?: ""
            if (available && notes.isNotEmpty()) SelectionContainer { Text(notes) }
        }
    }

}

@Composable
private fun Pill(label: String, color: androidx.compose.ui.graphics.Color, foreground: androidx.compose.ui.graphics.Color) {
    Text(
        label,
        Modifier.background(color, AppShape.full).padding(horizontal = 12.dp, vertical = 4.dp),
        style = AppTextStyle.Caption1.of(emphasized = true).copy(color = foreground),
    )
}

@Composable
private fun LinkBadge(icon: ImageVector) {
    Box(Modifier.size(40.dp).background(raisedSurfaceLevel.colorOf(BoxTheme.scheme), AppShape.card), contentAlignment = Alignment.Center) {
        AppIcon(icon, size = 20.dp, color = BoxTheme.scheme.onSurfaceVariant)
    }
}

/** showLicensePage: the projects FK-box ships or borrows from, with license, what was taken and source. */
private data class Credit(val name: String, val license: String, val taken: String, val url: String)

private val licenses = listOf(
    Credit("FK-box", "GPL-3.0", "This app", "https://github.com/koko123as/fk-box"),
    Credit("sing-box-for-android", "GPL-3.0", "Base Android app and backend", "https://github.com/SagerNet/sing-box-for-android"),
    Credit("B-box", "GPL-3.0", "Direct predecessor: core extensions, MITM, multi-core", "https://github.com/giturass/B-box"),
    Credit("sing-box", "GPL-3.0", "Proxy core (libbox)", "https://github.com/SagerNet/sing-box"),
    Credit("Xray-core", "MPL-2.0", "Second core", "https://github.com/XTLS/Xray-core"),
    Credit("mihomo", "GPL-3.0", "Third core (Clash YAML)", "https://github.com/MetaCubeX/mihomo"),
    Credit("FlClash", "GPL-3.0", "UI design, rewritten in Kotlin + Compose (no Flutter code)", "https://github.com/chen08209/FlClash"),
    Credit("shadowsocks-android", "GPL-3.0", "DefaultNetworkListener", "https://github.com/shadowsocks/shadowsocks-android"),
    Credit("Android Open Source Project", "Apache-2.0", "ParceledListSlice, Material 3 shapes", "https://github.com/androidx/androidx"),
    Credit("Jetpack Compose", "Apache-2.0", "UI framework", "https://developer.android.com/jetpack/compose"),
    Credit("AndroidX Graphics Shapes", "Apache-2.0", "Shape morphing", "https://developer.android.com/jetpack/androidx/releases/graphics"),
    Credit("CameraX", "Apache-2.0", "QR code camera", "https://developer.android.com/jetpack/androidx/releases/camera"),
    Credit("ZXing", "Apache-2.0", "QR code scanning and generation", "https://github.com/zxing/zxing"),
    Credit("libghostty", "MIT", "Terminal", "https://github.com/ghostty-org/ghostty"),
    Credit("Shizuku API", "MIT", "Shizuku access", "https://github.com/RikkaApps/Shizuku-API"),
    Credit("libsu", "Apache-2.0", "Root shell and service", "https://github.com/topjohnwu/libsu"),
    Credit("smali / dexlib2", "BSD-3-Clause", "Per-app proxy scanning", "https://github.com/google/smali"),
    Credit("Goja", "MIT", "JavaScript for HTTP rewrite scripts", "https://github.com/dop251/goja"),
    Credit("JetBrains Mono", "OFL-1.1", "Monospace font", "https://github.com/JetBrains/JetBrainsMono"),
)

@Composable
private fun LicensesPage(box: BoxController) {
    BoxPage("Open-source licenses") {
        Text("FK-box ${box.version}", Modifier.padding(16.dp), style = AppTextStyle.Headline.of().copy(color = BoxTheme.scheme.onSurface))
        Section(count = licenses.size) { index, position ->
            val credit = licenses[index]
            val url = credit.url
            ListItem(
                title = { Text(credit.name) },
                subtitle = { Text(credit.license + " · " + tr(credit.taken)) },
                trailing = { AppIcon(AppIcons.openExternal) },
                position = position,
                onClick = { box.launchAct { openUrl(url) } },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// pages/backup.dart (layout and dialogs adapted from FlClash v0.9.2 backup_and_restore.dart)
// ---------------------------------------------------------------------------

const val DEFAULT_BACKUP_FILE_NAME = "B-box-backup.json"

fun backupFileIssue(value: String?): String? =
    if (value.isNullOrEmpty() || value.length > 128 || value == "." || value == ".." || value.codePoints().anyMatch { it < 32 || it == 47 || it == 92 }) {
        "Invalid backup file name"
    } else {
        null
    }

/** jsonEncode for the backup the bridge returns as maps and lists. */
private fun encodeBackupJson(value: Any?): String = when (value) {
    null -> "null"
    is Map<*, *> -> JSONObject(value.mapKeys { it.key.toString() }.mapValues { toJsonValue(it.value) }).toString()
    is List<*> -> JSONArray(value.map(::toJsonValue)).toString()
    else -> JSONObject.quote(value.toString())
}

private fun toJsonValue(value: Any?): Any? = when (value) {
    null -> JSONObject.NULL
    is Map<*, *> -> JSONObject(value.mapKeys { it.key.toString() }.mapValues { toJsonValue(it.value) })
    is List<*> -> JSONArray(value.map(::toJsonValue))
    else -> value
}

private sealed class BackupDialog {
    class Scope(val result: CompletableDeferred<String?>) : BackupDialog()
    class Approve(val count: Int, val scope: String, val override: Boolean, val result: CompletableDeferred<Boolean>) : BackupDialog()
    class Done(val title: String, val message: String) : BackupDialog()
}

@Composable
fun BackupPage(box: BoxController) {
    val navigator = LocalBoxNavigator.current
    val dialogs = LocalDialogs.current
    val scope = rememberCoroutineScope()
    var account by remember { mutableStateOf<Json?>(null) }
    var accountError by remember { mutableStateOf<Throwable?>(null) }
    var locked by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var connected by remember { mutableStateOf<Boolean?>(null) }
    var connectionError by remember { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf<BackupDialog?>(null) }
    val generation = remember { intArrayOf(0, 0) }

    fun check() {
        val current = ++generation[0]
        if (account == null) {
            checking = false
            connected = null
            return
        }
        checking = true
        connected = null
        connectionError = null
        scope.launch {
            try {
                val value = box.call { checkWebdav() } == true
                if (current == generation[0]) connected = value
            } catch (error: Throwable) {
                if (current == generation[0]) {
                    connected = false
                    connectionError = describe(error)
                }
            } finally {
                if (current == generation[0]) checking = false
            }
        }
    }
    fun updateAccount(value: Json?) {
        generation[1]++
        val previous = account
        account = value
        accountError = null
        if (previous == null || value == null || listOf("uri", "user", "password").any { previous[it] != value[it] }) check()
    }
    suspend fun loadAccount() {
        val revision = generation[1]
        try {
            val value = box.call { webdavAccount() }
            if (revision == generation[1]) updateAccount(value?.let(::json))
        } catch (error: Throwable) {
            if (revision == generation[1]) accountError = error
        }
    }
    LaunchedEffect(Unit) { loadAccount() }
    fun edit() {
        if (locked) return
        locked = true
        navigator?.openForm(onClosed = { result ->
            locked = false
            val value = result as? Map<*, *> ?: return@openForm
            updateAccount(if (value["deleted"] == true) null else json(value))
        }) { WebDAVForm(box, account) }
    }
    fun backup(remote: Boolean) {
        if (locked) return
        locked = true
        loading = true
        scope.launch {
            try {
                val saved = if (remote) {
                    box.call { uploadWebdavBackup() }
                } else {
                    val data = box.call { createBackup() }
                    box.call { saveDocument(DEFAULT_BACKUP_FILE_NAME, encodeBackupJson(data)) }
                }
                loading = false
                if (saved) dialog = BackupDialog.Done("Backup", "Backup complete")
            } catch (error: Throwable) {
                box.report(error)
            } finally {
                loading = false
                locked = false
            }
        }
    }
    fun restore(remote: Boolean) {
        if (locked || box.remote) return
        val strategy = box.preferences["restoreStrategy"] as? String ?: "Compatible"
        locked = true
        scope.launch {
            try {
                val picked = CompletableDeferred<String?>()
                dialog = BackupDialog.Scope(picked)
                val restoreScope = picked.await() ?: return@launch
                loading = true
                val data = box.call { if (remote) readWebdavBackup(restoreScope) else readBackup(restoreScope) }
                loading = false
                if (data == null) return@launch
                val approved = CompletableDeferred<Boolean>()
                dialog = BackupDialog.Approve(rows(json(data)["profiles"]).size, restoreScope, strategy == "Override", approved)
                if (!approved.await()) return@launch
                loading = true
                box.call { @Suppress("UNCHECKED_CAST") restoreBackup(data as? Map<String, Any?>, restoreScope, strategy) }
                loading = false
                if (restoreScope == "all") loadAccount()
                dialog = BackupDialog.Done("Restore", "Restore complete")
            } catch (error: Throwable) {
                box.report(error)
            } finally {
                loading = false
                locked = false
            }
        }
    }
    val scheme = BoxTheme.scheme
    BoxEditGuard(dirty = false, busy = locked) {
        CommonScaffold(title = tr("Backup and restore"), loading = loading) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = sectionsTopPadding(), bottom = 16.dp + bottomInset())) {
                item(key = "remote") {
                    val current = account
                    val remoteRows = buildList<@Composable (ItemPosition) -> Unit> {
                        when {
                            accountError != null -> add { _ -> ErrorState(describe(accountError!!)) { scope.launch { loadAccount() } } }
                            current == null -> add { p ->
                                ListItem(
                                    leading = { AppIcon(AppIcons.account) },
                                    title = { Text(tr("No account")) },
                                    subtitle = { Text(tr("Bind a WebDAV account")) },
                                    trailing = { TonalButton(tr("Bind"), if (locked) null else ({ edit() })) },
                                    position = p,
                                )
                            }
                            else -> {
                                add { p ->
                                    val label = when {
                                        checking -> "Checking connection"
                                        connected == true -> "Connected"
                                        else -> "Connection failed"
                                    }
                                    ListItem(
                                        leading = { AppIcon(AppIcons.account) },
                                        title = {
                                            val user = current["user"] as? String ?: ""
                                            Text(user.ifEmpty { current["uri"] as? String ?: "" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        },
                                        subtitle = {
                                            Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Text(tr("Connectivity"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                if (checking) {
                                                    CommonCircleLoading(12.dp)
                                                } else {
                                                    Box(Modifier.size(12.dp).background(if (connected == true) scheme.success else scheme.error, CircleShape))
                                                }
                                                if (!checking && connected != true) {
                                                    Text(connectionError ?: tr(label), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                }
                                            }
                                        },
                                        trailing = { TonalButton(tr("Edit"), if (locked) null else ({ edit() })) },
                                        position = p,
                                    )
                                }
                                add { p ->
                                    val fileName = current["fileName"] as? String ?: DEFAULT_BACKUP_FILE_NAME
                                    val invalid = tr("Invalid backup file name")
                                    val fileTitle = tr("File")
                                    ListItem(
                                        title = { Text(tr("File")) },
                                        subtitle = { Text(fileName) },
                                        position = p,
                                        onClick = {
                                            scope.launch {
                                                val value = dialogs?.input(fileTitle, fileName, resetValue = DEFAULT_BACKUP_FILE_NAME, validator = { if (backupFileIssue(it) == null) null else invalid })
                                                if (value == null || account == null || locked) return@launch
                                                locked = true
                                                try {
                                                    updateAccount(json(box.call { setWebdavAccount(account!! + ("fileName" to value)) }))
                                                } catch (error: Throwable) {
                                                    box.report(error)
                                                } finally {
                                                    locked = false
                                                }
                                            }
                                        },
                                    )
                                }
                                add { p -> ListItem(title = { Text(tr("Backup")) }, position = p, onClick = { backup(true) }) }
                                add { p -> ListItem(title = { Text(tr("Restore")) }, position = p, onClick = if (box.remote) null else ({ restore(true) })) }
                            }
                        }
                    }
                    Section(title = tr("Remote"), rows = remoteRows)
                }
                item(key = "local") {
                    Section(title = tr("Local"), count = 2) { index, position ->
                        if (index == 0) {
                            ListItem(title = { Text(tr("Backup")) }, position = position, onClick = { backup(false) })
                        } else {
                            ListItem(title = { Text(tr("Restore")) }, position = position, onClick = if (box.remote) null else ({ restore(false) }))
                        }
                    }
                }
                item(key = "options") {
                    val compatible = tr("Compatible")
                    val override = tr("Override")
                    Section(title = tr("Options"), count = 1) { _, position ->
                        ListItemOptions(
                            tr("Restore strategy"),
                            listOf("Compatible", "Override"),
                            box.preferences["restoreStrategy"] as? String ?: "Compatible",
                            position,
                            { if (it == "Override") override else compatible },
                        ) { value -> scope.launch { box.setPreference("restoreStrategy", value) } }
                    }
                }
            }
        }
    }
    when (val shown = dialog) {
        is BackupDialog.Scope -> CommonDialog(title = tr("Restore"), onDismiss = {
            dialog = null
            shown.result.complete(null)
        }, actions = emptyList()) {
            Column {
                for ((label, value) in listOf("Restore profiles only" to "profiles", "Restore all data" to "all")) {
                    ListItem(title = { Text(tr(label)) }, onClick = {
                        dialog = null
                        shown.result.complete(value)
                    })
                }
            }
        }
    
        is BackupDialog.Approve -> CommonDialog(
            title = tr("Restore backup"),
            onDismiss = {
                dialog = null
                shown.result.complete(false)
            },
            destructive = shown.override,
            actions = listOf(
                DialogAction(tr("Cancel"), {
                    dialog = null
                    shown.result.complete(false)
                }),
                DialogAction(tr("Restore"), {
                    dialog = null
                    shown.result.complete(true)
                }),
            ),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(tr("Restore {count} profiles?").replace("{count}", "${shown.count}"))
                Text(tr(if (shown.override) "Profiles missing from this backup will be removed." else "Matching profiles will be replaced. Other profiles will be kept."))
                if (shown.scope == "all") Text(tr("Application settings and remote servers will also be restored."))
            }
        }
    
        is BackupDialog.Done -> CommonDialog(title = tr(shown.title), onDismiss = { dialog = null }, actions = listOf(DialogAction(tr("Close"), { dialog = null }))) { Text(tr(shown.message)) }
    
        null -> Unit
    }
}

private fun webDavIssue(uri: String, user: String, password: String): String? {
    val invalidAddress = "Enter a WebDAV HTTP or HTTPS directory address"
    try {
        val parsed = java.net.URI(uri.trim())
        if (parsed.scheme !in listOf("http", "https") || parsed.host.isNullOrEmpty() || parsed.rawUserInfo != null ||
            parsed.rawQuery != null || parsed.rawFragment != null || uri.length > 4096
        ) {
            return invalidAddress
        }
        if (parsed.port != -1 && (parsed.port <= 0 || parsed.port > 65535)) return invalidAddress
    } catch (_: Exception) {
        return invalidAddress
    }
    if (user.length > 4096 || password.length > 4096) return "Invalid WebDAV credentials"
    return null
}

@Composable
private fun WebDAVForm(box: BoxController, initial: Json?) {
    val navigator = LocalBoxNavigator.current
    val dialogs = LocalDialogs.current
    val scope = rememberCoroutineScope()
    var uri by remember { mutableStateOf(initial?.get("uri") as? String ?: "") }
    var user by remember { mutableStateOf(initial?.get("user") as? String ?: "") }
    var password by remember { mutableStateOf(initial?.get("password") as? String ?: "") }
    var attempted by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val dirty = uri != (initial?.get("uri") ?: "") || user != (initial?.get("user") ?: "") || password != (initial?.get("password") ?: "")
    fun save() {
        if (saving) return
        if (webDavIssue(uri, user, password) != null) {
            attempted = true
            return
        }
        saving = true
        error = null
        scope.launch {
            try {
                val result = box.call {
                    setWebdavAccount(mapOf("uri" to uri.trim(), "user" to user, "password" to password, "fileName" to (initial?.get("fileName") ?: DEFAULT_BACKUP_FILE_NAME)))
                }
                saving = false
                navigator?.pop(json(result))
            } catch (failure: Throwable) {
                error = describe(failure)
                saving = false
            }
        }
    }
    fun delete() {
        if (saving) return
        scope.launch {
            if (dialogs?.confirm("Delete WebDAV account", action = "Delete") != true) return@launch
            saving = true
            error = null
            try {
                box.call { setWebdavAccount(null) }
                saving = false
                navigator?.pop(mapOf("deleted" to true))
            } catch (failure: Throwable) {
                error = describe(failure)
                saving = false
            }
        }
    }
    val issue = if (attempted) webDavIssue(uri, user, password) else null
    BoxEditGuard(dirty = dirty, busy = saving) {
        FormPage("WebDAV configuration", onSave = ::save, saving = saving, error = error ?: issue?.let { tr(it) }) {
            Section(footer = tr("Enter the WebDAV directory URL. Backups are stored in its B-box folder."), count = 3) { index, position ->
                when (index) {
                    0 -> FormRow(tr("Address"), position, invalid = issue == "Enter a WebDAV HTTP or HTTPS directory address") {
                        FormRowTextField(uri, { uri = it.take(4096); error = null }, hint = tr("Required"), keyboardType = KeyboardType.Uri, autocorrect = false)
                    }
                    1 -> FormRow(tr("Account"), position) {
                        FormRowTextField(user, { user = it.take(4096); error = null }, hint = tr("Optional"), autocorrect = false)
                    }
                    else -> FormRow(tr("Password"), position) {
                        FormRowTextField(password, { password = it.take(4096); error = null }, hint = tr("Optional"), keyboardType = KeyboardType.Password, autocorrect = false, secret = true)
                    }
                }
            }
            if (initial != null) {
                Section(count = 1) { _, position ->
                    ListItem(title = { Text(tr("Delete"), color = BoxTheme.scheme.error) }, position = position, onClick = { delete() })
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// pages/access.dart
// ---------------------------------------------------------------------------

private fun stringSet(value: Any?): Set<String> = (value as? List<*>)?.map { it.toString() }?.toSet() ?: emptySet()

@Composable
fun AccessPage(box: BoxController) {
    val navigator = LocalBoxNavigator.current
    val context = LocalContext.current
    val dialogs = LocalDialogs.current
    val level = LocalNavLevel.current
    val scope = rememberCoroutineScope()
    val initialDraft: Map<String, Any?> = remember {
        val settings = box.settings
        mapOf<String, Any?>(
            "perAppEnabled" to (settings["perAppEnabled"] == true),
            "perAppManaged" to (settings["perAppManaged"] == true),
            "perAppMode" to (if ((settings["perAppMode"].asDouble() ?: 1.0).toInt() == 2) 2 else 1),
            "perAppList" to stringSet(settings["perAppList"]).toList(),
        )
    }
    var draft by remember { mutableStateOf(initialDraft) }
    var saved by remember { mutableStateOf(initialDraft) }
    val enabled = draft["perAppEnabled"] == true
    val managed = draft["perAppManaged"] == true
    val selected = if (managed) stringSet(box.settings["perAppManagedList"]) else stringSet(draft["perAppList"])
    var pinned by remember { mutableStateOf(selected) }
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf("Default") }
    var system by remember { mutableStateOf(false) }
    var nonInternet by remember { mutableStateOf(false) }
    var selectedOnly by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var apps by remember { mutableStateOf<List<Json>?>(null) }
    var appsError by remember { mutableStateOf<Throwable?>(null) }
    var load by remember { mutableIntStateOf(0) }
    var refresh by remember { mutableStateOf(true) }
    val hasChanges = draft != saved
    LaunchedEffect(load, system, nonInternet) {
        appsError = null
        apps = null
        try {
            apps = rows(box.call { listApps(refresh, system, nonInternet) })
        } catch (error: Throwable) {
            appsError = error
        }
        refresh = false
    }
    fun reload() {
        box.clearAppIcons()
        refresh = true
        load++
    }
    fun set(key: String, value: Any?) {
        if (!saving) draft = draft + (key to value)
    }
    fun toggle(pkg: String) {
        if (!enabled || managed || saving) return
        val next = selected.toMutableSet()
        if (!next.add(pkg)) next.remove(pkg)
        set("perAppList", next.sorted())
    }
    suspend fun save(): Boolean {
        if (saving) return false
        saving = true
        val values = draft
        val ok = box.setSettings(values)
        saving = false
        if (ok) saved = values
        return ok
    }
    val hasChangesNow = rememberUpdated(hasChanges || saving)
    androidx.compose.runtime.DisposableEffect(level) {
        val guard: suspend () -> Boolean = guard@{
            if (!hasChangesNow.value) return@guard true
            if (saving) return@guard false
            when (dialogs?.choose("Unsaved changes", null, listOf("Cancel" to null, "Discard" to "discard", "Save" to "save"))) {
                "discard" -> true
                "save" -> save()
                else -> false
            }
        }
        level?.guard = guard
        onDispose { if (level?.guard === guard) level.guard = null }
    }
    fun querySettings(retry: Boolean = false) {
        val previousMode = box.settings["queryMode"]
        var permissionRequested = false
        navigator?.openForm(onClosed = {
            if (retry || permissionRequested || box.settings["queryMode"] != previousMode) reload()
        }) {
            BoxPage("Application query") {
                Section(count = 2) { index, position ->
                    if (index == 0) {
                        ListItemOptions(
                            tr("Application query"),
                            listOf("SHIZUKU", "ROOT"),
                            box.settings["queryMode"] as? String ?: "SHIZUKU",
                            position,
                            { if (it == "ROOT") "Root" else "Shizuku" },
                        ) { value -> scope.launch { box.setSetting("queryMode", value, restart = false) } }
                    } else {
                        ListItem(title = { Text(tr("Grant Shizuku permission")) }, position = position, onClick = {
                            permissionRequested = true
                            box.launchAct { requestAppsPermission() }
                        })
                    }
                }
            }
        }
    }
    val words = query.lowercase().trim().split(Regex("\\s+"))
    val visible = (apps ?: emptyList()).filter { app ->
        (system || app["system"] != true) && (nonInternet || app["internet"] != false) &&
            (!selectedOnly || app["package"] in selected) &&
            words.all { "${app["name"]} ${app["package"]}".lowercase().contains(it) }
    }.sortedWith { a, b ->
        val pin = (if (b["package"] in pinned) 1 else 0) - (if (a["package"] in pinned) 1 else 0)
        if (pin != 0) return@sortedWith pin
        if (sort == "Time") {
            val time = (b["updatedAt"].asDouble() ?: 0.0).compareTo(a["updatedAt"].asDouble() ?: 0.0)
            if (time != 0) return@sortedWith time
        }
        val aName = a["name"].toString()
        val bName = b["name"].toString()
        val name = if (sort == "Name") aName.compareTo(bName) else aName.lowercase().compareTo(bName.lowercase())
        if (name != 0) name else a["package"].toString().compareTo(b["package"].toString())
    }
    val allSelected = visible.isNotEmpty() && visible.all { it["package"] in selected }
    val selectAll = if (enabled && !managed && visible.isNotEmpty()) {
        IconAction(if (allSelected) AppIcons.deselect else AppIcons.selectAll, tr(if (allSelected) "Deselect all" else "Select all"), if (saving) null else ({
            val next = selected.toMutableSet()
            val packages = visible.map { it["package"].toString() }
            if (allSelected) next.removeAll(packages.toSet()) else next.addAll(packages)
            set("perAppList", next.sorted())
        }))
    } else {
        null
    }
    val mode = if (managed) 1 else (draft["perAppMode"] as? Int ?: 1)
    fun option(label: String, checked: Boolean, section: PopupMenuSection? = null, change: () -> Unit) =
        PopupMenuItem(label, checked = checked, section = section, onPressed = change)
    val trProxy = tr("Proxy selected apps")
    val trBypass = tr("Bypass selected apps")
    val menu = listOf(
        PopupMenuItem(tr("Turn on"), AppIcons.toggleOn, checked = enabled, section = PopupMenuSection(tr("Access control")), onPressed = { set("perAppEnabled", true) }),
        PopupMenuItem(tr("Turn off"), AppIcons.toggle, checked = !enabled, onPressed = { set("perAppEnabled", false) }),
        PopupMenuItem(
            tr("Settings"),
            AppIcons.sliders,
            subtitle = if (mode == 2) trProxy else trBypass,
            section = PopupMenuSection(),
            subItems = listOf(
                option(trProxy, mode == 2, PopupMenuSection(tr("Mode"))) { set("perAppMode", 2) },
                option(trBypass, mode == 1) { set("perAppMode", 1) },
                option(tr("Default"), sort == "Default", PopupMenuSection(tr("Sort"))) { sort = "Default" },
                option(tr("Name"), sort == "Name") { sort = "Name" },
                option(tr("Time"), sort == "Time") { sort = "Time" },
                option(tr("Show system apps"), system, PopupMenuSection(tr("Source"))) { system = !system },
                option(tr("Show apps without Internet permission"), nonInternet) { nonInternet = !nonInternet },
                option(tr("Selected apps"), selectedOnly) { selectedOnly = !selectedOnly },
                option(tr("Managed app list"), managed) { set("perAppManaged", !managed) },
            ),
        ),
        PopupMenuItem(
            tr("Action"),
            AppIcons.moreCircle,
            subItems = buildList {
                add(PopupMenuItem(tr("Clipboard export"), AppIcons.copy, onPressed = { setClipboard(context, selected.joinToString("\n")) }))
                if (!managed) {
                    add(
                        PopupMenuItem(tr("Clipboard import"), AppIcons.paste, onPressed = {
                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                            if (text != null) set("perAppList", text.split(Regex("\\s+")).filter { it.isNotEmpty() }.distinct().sorted())
                        }),
                    )
                }
                add(PopupMenuItem(tr("Refresh"), AppIcons.refresh, onPressed = { reload() }))
                if (managed) {
                    add(
                        PopupMenuItem(tr("Rescan managed apps"), AppIcons.appsList, onPressed = if (scanning || saving || hasChanges) null else ({
                            scanning = true
                            scope.launch {
                                try {
                                    box.settings = json(box.call { rescanApps() })
                                } catch (error: Throwable) {
                                    box.report(error)
                                } finally {
                                    scanning = false
                                }
                            }
                        })),
                    )
                }
                add(PopupMenuItem(tr("Application query"), AppIcons.lockOpen, onPressed = { querySettings() }))
            },
        ),
    )
    val scheme = BoxTheme.scheme
    CommonScaffold(
        title = tr("Access control"),
        loading = saving || scanning,
        searchInBar = true,
        searchState = SearchState {
            query = it
            pinned = selected
        },
        iconActions = listOfNotNull(selectAll),
        confirmationAction = if (hasChanges) IconAction(AppIcons.check, tr("Save"), if (saving) null else ({ scope.launch { save() } })) else null,
        menuItems = menu,
    ) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                when {
                    appsError != null -> NullStatus(
                        label = tr("Application query failed"),
                        description = describe(appsError!!),
                        illustration = NullStatusIllustration.Permission,
                        action = { TonalButton(tr("Application query"), { querySettings(retry = true) }, icon = AppIcons.lockOpen, size = TonalButtonSize.Bar) },
                    )
                    apps == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CommonCircleLoading(40.dp) }
                    else -> NullStatusSwitcher(
                        isEmpty = visible.isEmpty(),
                        isSearching = query.isNotBlank(),
                        nullStatus = { NullStatus(label = tr("No results"), illustration = NullStatusIllustration.Apps) },
                    ) {
                        LazyColumn(
                            Modifier.fillMaxSize().alpha(if (enabled) 1f else 0.5f),
                            contentPadding = PaddingValues(top = contentTopPadding(), bottom = 72.dp + bottomInset()),
                        ) {
                            items(visible, key = { it["package"].toString() }) { app ->
                                val pkg = app["package"].toString()
                                Box {
                                    ListItem(
                                        leading = { PackageIcon(box, pkg) },
                                        title = { Text(app["name"].toString(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        subtitle = { Text(pkg, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        trailing = {
                                            androidx.compose.material3.Checkbox(
                                                checked = pkg in selected,
                                                onCheckedChange = if (enabled && !managed && !saving) ({ toggle(pkg) }) else null,
                                            )
                                        },
                                        onClick = if (enabled && !managed && !saving) ({ toggle(pkg) }) else null,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        // FloatingFootBar: the access state over the list's foot.
        Box(Modifier.fillMaxSize().padding(bottom = bottomInset() + 12.dp, start = 16.dp, end = 16.dp), contentAlignment = Alignment.BottomCenter) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(raisedSurfaceLevel.colorOf(scheme), AppShape.full)
                    .padding(start = 16.dp, top = 6.dp, bottom = 6.dp, end = if (enabled) 16.dp else 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppIcon(if (enabled) AppIcons.appsList else AppIcons.info, size = 20.dp, color = scheme.onSurfaceVariant)
                Text(
                    tr(
                        when {
                            !enabled -> "Access control is disabled"
                            managed -> "Disable managed app list to edit selection"
                            mode == 2 -> "Only selected apps use the VPN"
                            else -> "Selected apps bypass the VPN"
                        },
                    ),
                    Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = AppTextStyle.Footnote.of().copy(color = scheme.onSurface),
                )
                if (enabled) {
                    TonalChip("${tr("Selected")} ${selected.size}", scheme.primary.copy(alpha = 0.15f), scheme.primary)
                } else {
                    TonalButton(tr("Turn on"), { set("perAppEnabled", true) }, size = TonalButtonSize.Compact)
                }
            }
        }
    }
}

@Composable
private fun PackageIcon(box: BoxController, pkg: String) {
    val icon by produceState(box.cachedAppIcon(pkg), pkg) {
        value = box.appIcon(pkg)
    }
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        val bitmap = icon
        if (bitmap == null) {
            AppIcon(AppIcons.appsList, size = 32.dp, color = BoxTheme.scheme.onSurfaceVariant)
        } else {
            Image(bitmap, contentDescription = null, modifier = Modifier.size(48.dp))
        }
    }
}
