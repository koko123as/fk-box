package io.nekohasekai.sfa.compose.pages

import androidx.compose.foundation.text.selection.SelectionContainer

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.nekohasekai.sfa.compose.BoxController
import io.nekohasekai.sfa.compose.Json
import io.nekohasekai.sfa.compose.asDouble
import io.nekohasekai.sfa.compose.asLong
import io.nekohasekai.sfa.compose.json
import io.nekohasekai.sfa.compose.rows
import io.nekohasekai.sfa.compose.theme.AppShape
import io.nekohasekai.sfa.compose.theme.AppTextStyle
import io.nekohasekai.sfa.compose.theme.BoxTheme
import io.nekohasekai.sfa.compose.theme.JetBrainsMono
import io.nekohasekai.sfa.compose.theme.tabular
import io.nekohasekai.sfa.compose.ui.AppIcons
import io.nekohasekai.sfa.compose.ui.BoxPage
import io.nekohasekai.sfa.compose.ui.BoxProgress
import io.nekohasekai.sfa.compose.ui.CommonScaffold
import io.nekohasekai.sfa.compose.ui.EmptyState
import io.nekohasekai.sfa.compose.ui.ErrorState
import io.nekohasekai.sfa.compose.ui.FilledPillButton
import io.nekohasekai.sfa.compose.ui.AppIcon
import io.nekohasekai.sfa.compose.ui.IconAction
import io.nekohasekai.sfa.compose.ui.ItemPosition
import io.nekohasekai.sfa.compose.ui.LabeledField
import io.nekohasekai.sfa.compose.ui.ListItem
import io.nekohasekai.sfa.compose.ui.ListItemOpen
import io.nekohasekai.sfa.compose.ui.ListItemToggle
import io.nekohasekai.sfa.compose.ui.ListStyles
import io.nekohasekai.sfa.compose.ui.LocalBoxNavigator
import io.nekohasekai.sfa.compose.ui.LocalDialogs
import io.nekohasekai.sfa.compose.ui.SearchState
import io.nekohasekai.sfa.compose.ui.Section
import io.nekohasekai.sfa.compose.ui.SelectionCheck
import io.nekohasekai.sfa.compose.ui.TextPillButton
import io.nekohasekai.sfa.compose.ui.TrailingIcon
import io.nekohasekai.sfa.compose.ui.appBarInset
import io.nekohasekai.sfa.compose.ui.bottomInset
import io.nekohasekai.sfa.compose.ui.contentTopPadding
import io.nekohasekai.sfa.compose.ui.rememberPageActive
import io.nekohasekai.sfa.compose.ui.tr
import io.nekohasekai.sfa.bridge.TerminalChannel
import kotlinx.coroutines.launch
import java.io.File

// ---------------------------------------------------------------------------
// Terminal
// ---------------------------------------------------------------------------

@Composable
fun TerminalPage(box: BoxController, endpoint: String? = null, peer: String? = null, name: String? = null) {
    val navigator = LocalBoxNavigator.current
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf("root") }
    var terminalType by remember { mutableStateOf("xterm-256color") }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var quickConnect by remember { mutableStateOf(false) }
    var prompt by remember { mutableStateOf(peer != null) }
    var error by remember { mutableStateOf<String?>(null) }
    var settingsRevision by remember { mutableIntStateOf(0) }
    // The native surface sits above Compose; it is dropped while another route covers it.
    val active = rememberPageActive()

    suspend fun start() {
        if (busy || username.trim().isEmpty()) return
        busy = true
        try {
            val snapshot = json(
                box.call { toolBridge.ssh.start(endpoint, peer, username.trim(), terminalType.trim(), quickConnect) },
            )
            box.terminal = snapshot
            prompt = false
            error = null
        } catch (failure: Throwable) {
            error = describe(failure)
        }
        busy = false
    }
    LaunchedEffect(Unit) {
        try {
            box.terminal = json(box.call { toolBridge.ssh.snapshot() })
            if (prompt) {
                val options = json(box.call { toolBridge.ssh.options(endpoint, peer) })
                username = options["username"] as? String ?: "root"
                terminalType = options["terminalType"] as? String ?: "xterm-256color"
                quickConnect = options["remember"] == true
            }
            loading = false
            error = null
            if (prompt && quickConnect) start()
        } catch (failure: Throwable) {
            loading = false
            error = describe(failure)
        }
    }
    fun newSession() {
        navigator?.openForm(onClosed = { result ->
            val picked = result as? Map<*, *> ?: return@openForm
            navigator?.push { TerminalPage(box, picked["endpoint"] as String, picked["id"] as String, picked["name"] as String) }
        }) { TerminalPeerPicker(box) }
    }
    val snapshot = box.terminal
    val sessions = rows(snapshot["sessions"])
    val activeSession = sessions.firstOrNull { it["id"] == snapshot["activeId"] }
    val progress = json(activeSession?.get("progress"))
    val scheme = BoxTheme.scheme
    CommonScaffold(
        title = if (prompt) "SSH · ${name ?: ""}" else activeSession?.get("name") as? String ?: tr("Terminal"),
        loading = loading || busy,
        iconActions = listOf(
            IconAction(AppIcons.add, tr("New session"), { newSession() }),
            IconAction(AppIcons.layers, tr("Sessions"), { navigator?.openForm { TerminalSessionsPage(box) } }),
            IconAction(AppIcons.settings, tr("Ghostty settings"), { navigator?.pushPage({ TerminalSettingsPage(box) }) { settingsRevision++ } }),
        ),
    ) {
        Box(Modifier.fillMaxSize().padding(top = appBarInset()).navigationBarsPadding()) {
            when {
                loading -> Unit
                prompt -> Column(Modifier.fillMaxSize().padding(20.dp)) {
                    LabeledField(username, { username = it }, tr("Username"))
                    Spacer(Modifier.height(16.dp))
                    LabeledField(terminalType, { terminalType = it }, tr("Terminal type"), onSubmitted = { scope.launch { start() } })
                    Spacer(Modifier.height(16.dp))
                    ListItemToggle(tr("Quick connect"), quickConnect, ItemPosition.StartAndEnd, subtitle = tr("Remember options and connect directly next time")) { quickConnect = it }
                    error?.let { Text(it, Modifier.padding(vertical = 16.dp), style = ListStyles.subtitle.copy(color = scheme.error)) }
                    Spacer(Modifier.height(20.dp))
                    FilledPillButton(tr("Connect"), if (busy) null else ({ scope.launch { start() } }), Modifier.fillMaxWidth())
                }
                activeSession == null -> EmptyState(
                    "No terminal sessions",
                    message = error,
                    action = { FilledPillButton(tr("New session"), { newSession() }) },
                )
                else -> Column(Modifier.fillMaxSize()) {
                    if (activeSession["phase"] == "CONNECTING" || progress.isNotEmpty()) {
                        val percent = progress["percent"].asDouble()
                        BoxProgress(
                            if (activeSession["phase"] == "CONNECTING" || percent == null) null else (percent / 100).toFloat(),
                            color = if (progress["state"] == "ERROR") scheme.error else scheme.primary,
                        )
                    }
                    activeSession["banner"]?.let { banner -> SelectionContainer { Text(banner.toString(), Modifier.padding(12.dp)) } }
                    Box(Modifier.weight(1f)) {
                        if (active) GhosttySurface(box, activeSession["id"] as String, settingsRevision)
                    }
                    if (activeSession["phase"] == "FINISHED") {
                        TextPillButton(tr("Close session"), { box.launchAct { toolBridge.ssh.closeSession(activeSession["id"] as? String) } })
                    }
                }
            }
        }
    }
}

private val terminalKeys = listOf("Esc", "Tab", "Ctrl", "Alt", "↑", "↓", "←", "→", "Home", "End", "PgUp", "PgDn", "Paste", "Keyboard")

/** The native Ghostty view and, unless a preview, its extra keys. */
@Composable
fun GhosttySurface(box: BoxController, sessionId: String, settingsRevision: Int, preview: Boolean = false) {
    val context = LocalContext.current
    val dialogs = LocalDialogs.current
    val scope = rememberCoroutineScope()
    val dark = BoxTheme.scheme.dark
    var modifiers by remember { mutableStateOf<Json>(emptyMap()) }
    val parameters = mapOf("id" to sessionId, "dark" to dark, "preview" to preview)
    val surface = remember {
        box.terminalView(
            context,
            parameters,
            object : TerminalChannel {
                override fun request(method: String, value: Any?, respond: (Any?) -> Unit) {
                    scope.launch {
                        val answer: Any? = when (method) {
                            "unsafePaste" -> dialogs?.confirm("Paste multiple lines?", message = "Pasted text may execute commands in this terminal.", action = "Paste") == true
                            "clipboardRead" -> dialogs?.confirm("Allow terminal to read clipboard?", action = "Allow") == true
                            "link" -> dialogs?.choose("Link", value?.toString(), listOf("Cancel" to null, "Copy" to "copy", "Open" to "open"))
                            else -> null
                        }
                        respond(answer)
                    }
                }

                override fun notify(method: String, value: Any?) {
                    if (method == "modifiers") modifiers = json(value)
                }
            },
        )
    }
    DisposableEffect(surface) { onDispose { surface.dispose() } }
    LaunchedEffect(sessionId, settingsRevision, dark) {
        try {
            surface.invoke("update", parameters)
        } catch (failure: Throwable) {
            box.report(failure)
        }
    }
    val scheme = BoxTheme.scheme
    Column(Modifier.fillMaxSize()) {
        AndroidView(factory = { surface.getView() }, modifier = Modifier.weight(1f).fillMaxWidth())
        if (!preview) {
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                for (key in terminalKeys) {
                    val state = modifiers[key.lowercase()]
                    val armed = state == "ARMED" || state == "LOCKED"
                    Box(Modifier.then(if (armed) Modifier.background(scheme.primaryContainer, AppShape.full) else Modifier)) {
                        TextPillButton(tr(key) + if (state == "LOCKED") " •" else "", {
                            try {
                                surface.invoke("key", mapOf("key" to key))
                            } catch (failure: Throwable) {
                                box.report(failure)
                            }
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun TerminalSessionsPage(box: BoxController) {
    val navigator = LocalBoxNavigator.current
    val dialogs = LocalDialogs.current
    val scope = rememberCoroutineScope()
    val sessions = rows(box.terminal["sessions"])
    BoxPage(
        "Sessions",
        actions = if (sessions.isEmpty()) {
            emptyList()
        } else {
            listOf(
                IconAction(AppIcons.copy, tr("Duplicate session"), {
                    scope.launch { if (box.act { toolBridge.ssh.duplicate() }) navigator?.pop() }
                }),
            )
        },
    ) {
        if (sessions.isEmpty()) EmptyState("No terminal sessions")
        Column {
            sessions.forEachIndexed { index, session ->
                val phase = when (session["phase"]) {
                    "RUNNING" -> "Running"
                    "CONNECTING" -> "Connecting"
                    else -> "Disconnected"
                }
                ListItem(
                    leading = { if (session["id"] == box.terminal["activeId"]) SelectionCheck() else AppIcon(AppIcons.code) },
                    title = { Text(session["name"].toString()) },
                    subtitle = { Text("${session["username"]}@${session["host"]} · ${tr(phase)}") },
                    trailing = {
                        TrailingIcon(AppIcons.close, tr("Close session")) {
                            scope.launch {
                                if (dialogs?.confirm("Close this SSH session?", action = "Close") == true) box.act { toolBridge.ssh.closeSession(session["id"] as? String) }
                            }
                        }
                    },
                    position = ItemPosition.of(index, sessions.size),
                    onClick = { scope.launch { if (box.act { toolBridge.ssh.select(session["id"] as? String) }) navigator?.pop() } },
                )
            }
        }
    }
}

@Composable
private fun TerminalPeerPicker(box: BoxController) {
    val navigator = LocalBoxNavigator.current
    val peers = buildList {
        for (endpoint in rows(box.toolState["tailscale"])) {
            for (group in rows(endpoint["userGroups"])) {
                for (peer in rows(group["peers"])) {
                    if ((peer["sshHostKeys"] as? List<*>)?.isNotEmpty() == true) add(peer + ("endpoint" to endpoint["endpointTag"]))
                }
            }
        }
    }
    BoxPage("New session") {
        if (peers.isEmpty()) EmptyState("No SSH peers", message = "Start a Tailscale endpoint with SSH-enabled peers.")
        Column {
            peers.forEachIndexed { index, peer ->
                ListItem(
                    title = { Text(peer["hostName"].toString()) },
                    subtitle = { Text(((peer["tailscaleIPs"] as? List<*>) ?: emptyList<Any>()).joinToString(" · ")) },
                    position = ItemPosition.of(index, peers.size),
                    onClick = { navigator?.pop(mapOf("id" to peer["id"], "endpoint" to peer["endpoint"], "name" to peer["hostName"])) },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Terminal settings
// ---------------------------------------------------------------------------

@Composable
private fun TerminalSettingsPage(box: BoxController) {
    val navigator = LocalBoxNavigator.current
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf<Json?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    LaunchedEffect(revision) {
        try {
            settings = json(box.call { terminalSettings() })
            error = null
        } catch (failure: Throwable) {
            error = describe(failure)
        }
    }
    fun set(key: String, value: Any?) {
        if (saving) return
        saving = true
        scope.launch {
            try {
                settings = json(box.call { setTerminalSetting(key, value) })
            } catch (failure: Throwable) {
                box.report(failure)
            }
            saving = false
        }
    }
    fun open(page: @Composable () -> Unit) {
        navigator?.pushPage(page) { revision++ }
    }
    val current = settings
    BoxPage("Ghostty settings", loading = saving || current == null && error == null) {
        error?.let { ErrorState(it) { revision++ } }
        if (current != null) {
            Section(count = 1) { _, position ->
                ListItemOpen(tr("Terminal preview"), position, leading = AppIcons.code) { TerminalPreviewPage(box) }
            }
            val customLabel = tr("Custom configuration")
            for (dark in listOf(false, true)) {
                val themeKey = if (dark) "darkTheme" else "lightTheme"
                val custom = current[themeKey] == ""
                Section(title = tr(if (dark) "Dark configuration" else "Light configuration"), count = if (custom) 3 else 2) { index, position ->
                    when (index) {
                        0 -> ListItem(
                            title = { Text(tr("Theme")) },
                            subtitle = { Text(if (custom) customLabel else current[themeKey] as? String ?: "") },
                            trailing = { AppIcon(AppIcons.chevronForward) },
                            position = position,
                            onClick = { open { TerminalThemePage(box, dark) } },
                        )
                        1 -> ListItemToggle(customLabel, custom, position, onChanged = if (saving) null else ({ enabled ->
                            set(themeKey, if (enabled) "" else current[if (dark) "defaultDarkTheme" else "defaultLightTheme"])
                        }))
                        else -> ListItem(
                            title = { Text(tr("Edit Ghostty configuration")) },
                            trailing = { AppIcon(AppIcons.chevronForward) },
                            position = position,
                            onClick = { open { TerminalConfigEditor(box, dark, current[if (dark) "darkConfig" else "lightConfig"] as? String ?: "") } },
                        )
                    }
                }
            }
            val followTheme = current["fontFollowTheme"] != false
            val fontSize = current["fontSize"].asLong()?.toInt() ?: 14
            Section(title = tr("Font"), count = if (followTheme) 2 else 3) { index, position ->
                val row = if (followTheme && index >= 1) index + 1 else index
                when (row) {
                    0 -> ListItemToggle(tr("Font follows theme"), followTheme, position, onChanged = if (saving) null else ({ set("fontFollowTheme", it) }))
                    1 -> {
                        val path = current["fontPath"] as? String ?: ""
                        val family = current["fontFamily"] as? String ?: ""
                        ListItem(
                            title = { Text(tr("Font family")) },
                            subtitle = { Text(if (path.isNotEmpty()) File(path).name else family.ifEmpty { "monospace" }) },
                            trailing = { AppIcon(AppIcons.chevronForward) },
                            position = position,
                            onClick = { open { TerminalFontPage(box) } },
                        )
                    }
                    else -> ListItem(
                        title = { Text(tr("Font size")) },
                        trailing = {
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                TrailingIcon(AppIcons.remove, tr("Decrease"), if (saving || fontSize <= 8) null else ({ set("fontSize", fontSize - 1) }))
                                Text("$fontSize", style = AppTextStyle.Body.of().tabular)
                                TrailingIcon(AppIcons.add, tr("Increase"), if (saving || fontSize >= 48) null else ({ set("fontSize", fontSize + 1) }))
                            }
                        },
                        position = position,
                    )
                }
            }
        }
    }
}

private fun argb(value: Any?, fallback: Long): Color = Color((value.asLong() ?: fallback).toInt())

@Composable
private fun TerminalThemePage(box: BoxController, dark: Boolean) {
    val navigator = LocalBoxNavigator.current
    val scope = rememberCoroutineScope()
    var themes by remember { mutableStateOf<List<Json>?>(null) }
    var selected by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) {
        try {
            val settings = json(box.call { terminalSettings() })
            themes = rows(box.call { terminalThemes(dark) })
            selected = settings[if (dark) "darkTheme" else "lightTheme"] as? String
            error = null
        } catch (failure: Throwable) {
            error = describe(failure)
        }
    }
    val visible = (themes ?: emptyList()).filter { it["name"].toString().lowercase().contains(query.lowercase()) }
    CommonScaffold(
        title = tr(if (dark) "Dark theme" else "Light theme"),
        loading = themes == null && error == null,
        searchState = SearchState { query = it },
    ) {
        if (error != null) {
            Box(Modifier.padding(top = contentTopPadding())) { ErrorState(error!!) { attempt++ } }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = contentTopPadding(), bottom = 24.dp + bottomInset())) {
                items(visible, key = { it["name"].toString() }) { theme ->
                    val background = argb(theme["background"], if (dark) 0xff000000 else 0xffffffff)
                    val foreground = argb(theme["foreground"], if (dark) 0xffffffff else 0xff000000)
                    val isSelected = selected == theme["name"]
                    Box(Modifier.padding(bottom = 8.dp)) {
                        ListItem(
                            title = { Text(theme["name"].toString()) },
                            subtitle = {
                                Column(
                                    Modifier.padding(top = 8.dp).fillMaxWidth().background(background, AppShape.card).padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text("user@b-box ~\n❯ echo Ghostty", style = TextStyle(fontFamily = JetBrainsMono, color = foreground, fontSize = 12.sp))
                                    Row(Modifier.fillMaxWidth()) {
                                        for (color in ((theme["palette"] as? List<*>) ?: emptyList<Any>()).take(8)) {
                                            Box(Modifier.weight(1f).height(8.dp).background(if (color == null) foreground else argb(color, 0)))
                                        }
                                    }
                                }
                            },
                            trailing = if (isSelected) ({ SelectionCheck() }) else null,
                            position = ItemPosition.StartAndEnd,
                            onClick = {
                                scope.launch {
                                    if (box.act { setTerminalSetting(if (dark) "darkTheme" else "lightTheme", theme["name"]) }) navigator?.pop()
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TerminalFontPage(box: BoxController) {
    val dialogs = LocalDialogs.current
    val scope = rememberCoroutineScope()
    var fonts by remember { mutableStateOf<Json?>(null) }
    var settings by remember { mutableStateOf<Json?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    LaunchedEffect(revision) {
        try {
            fonts = json(box.call { terminalFonts() })
            settings = json(box.call { terminalSettings() })
            error = null
        } catch (failure: Throwable) {
            error = describe(failure)
        }
    }
    fun select(key: String, value: String) {
        scope.launch { if (box.act { setTerminalSetting(key, value) }) revision++ }
    }
    val system = (fonts?.get("system") as? List<*>)?.map { it.toString() } ?: emptyList()
    val imported = rows(fonts?.get("imported"))
    BoxPage(
        "Font",
        loading = fonts == null && error == null,
        actions = listOf(
            IconAction(AppIcons.add, tr("Import font"), {
                scope.launch {
                    box.act { importTerminalFont() }
                    revision++
                }
            }),
        ),
    ) {
        error?.let { ErrorState(it) { revision++ } }
        Section(title = tr("System fonts"), count = system.size) { index, position ->
            val font = system[index]
            val family = remember(font) { FontFamily(android.graphics.Typeface.create(font, android.graphics.Typeface.NORMAL)) }
            val isSelected = settings?.get("fontFamily") == font && settings?.get("fontPath") == ""
            ListItem(
                title = { Text(font, style = TextStyle(fontFamily = family)) },
                trailing = if (isSelected) ({ SelectionCheck() }) else null,
                position = position,
                onClick = { select("fontFamily", font) },
            )
        }
        Section(title = tr("Imported fonts"), count = imported.size) { index, position ->
            val font = imported[index]
            val path = font["path"] as? String ?: ""
            val family = remember(path) { runCatching { FontFamily(Font(File(path))) }.getOrNull() }
            ListItem(
                title = { Text(font["name"].toString(), style = TextStyle(fontFamily = family)) },
                position = position,
                onClick = { select("fontPath", path) },
                trailing = {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        if (settings?.get("fontPath") == path) SelectionCheck()
                        TrailingIcon(AppIcons.delete, tr("Delete")) {
                            scope.launch {
                                if (dialogs?.confirm("Delete this font?") == true) {
                                    box.act { deleteTerminalFont(path as? String) }
                                    revision++
                                }
                            }
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun TerminalConfigEditor(box: BoxController, dark: Boolean, initial: String) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(TextFieldValue(initial, TextRange(0))) }
    val history = remember { EditorHistory() }
    var saving by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf<String?>(null) }
    val dirty = text.text != (saved ?: initial)
    val done = tr("Done")
    io.nekohasekai.sfa.compose.ui.BoxEditGuard(dirty = dirty, busy = saving) {
        CommonScaffold(
            title = tr(if (dark) "Dark configuration" else "Light configuration"),
            loading = saving,
            confirmationAction = IconAction(AppIcons.check, tr("Save"), if (saving) null else ({
                saving = true
                scope.launch {
                    if (box.act { setTerminalSetting(if (dark) "darkConfig" else "lightConfig", text.text) }) {
                        saved = text.text
                        box.message(done)
                    }
                    saving = false
                }
            })),
            iconActions = listOf(
                IconAction(AppIcons.undo, tr("Undo"), { history.undo(text)?.let { text = it } }),
                IconAction(AppIcons.redo, tr("Redo"), { history.redo(text)?.let { text = it } }),
            ),
        ) {
            val scheme = BoxTheme.scheme
            Column(Modifier.fillMaxSize().padding(top = appBarInset()).navigationBarsPadding()) {
                Text(
                    tr("Supported Ghostty options: foreground, background, cursor-color, selection-background, selection-foreground, palette, font-family."),
                    Modifier.padding(16.dp),
                    style = ListStyles.caption,
                )
                BasicTextField(
                    value = text,
                    onValueChange = { next ->
                        history.record(text, next)
                        text = next
                    },
                    readOnly = saving,
                    textStyle = TextStyle(fontFamily = JetBrainsMono, fontSize = 13.sp, lineHeight = (13 * 1.6).sp, color = scheme.onSurface),
                    cursorBrush = SolidColor(scheme.primary),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun TerminalPreviewPage(box: BoxController) {
    CommonScaffold(title = tr("Terminal preview")) {
        Box(Modifier.fillMaxSize().padding(top = appBarInset()).navigationBarsPadding()) {
            GhosttySurface(box, "", 0, preview = true)
        }
    }
}
