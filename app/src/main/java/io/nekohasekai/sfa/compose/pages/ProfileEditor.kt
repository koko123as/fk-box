package io.nekohasekai.sfa.compose.pages

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import io.nekohasekai.sfa.compose.ui.SelectionArea
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nekohasekai.sfa.compose.BoxController
import io.nekohasekai.sfa.compose.Json
import io.nekohasekai.sfa.compose.asLong
import io.nekohasekai.sfa.compose.json
import io.nekohasekai.sfa.compose.theme.AppShape
import io.nekohasekai.sfa.compose.theme.AppTextStyle
import io.nekohasekai.sfa.compose.theme.BoxScheme
import io.nekohasekai.sfa.compose.theme.BoxTheme
import io.nekohasekai.sfa.compose.theme.JetBrainsMono
import io.nekohasekai.sfa.compose.theme.SurfaceLevel
import io.nekohasekai.sfa.compose.ui.AppGlyphs
import io.nekohasekai.sfa.compose.ui.BoxEditGuard
import io.nekohasekai.sfa.compose.ui.BoxPage
import io.nekohasekai.sfa.compose.ui.CommonScaffold
import io.nekohasekai.sfa.compose.ui.DetailRows
import io.nekohasekai.sfa.compose.ui.EmojiText
import io.nekohasekai.sfa.compose.ui.EmptyState
import io.nekohasekai.sfa.compose.ui.ErrorState
import io.nekohasekai.sfa.compose.ui.GlyphButton
import io.nekohasekai.sfa.compose.ui.IconAction
import io.nekohasekai.sfa.compose.ui.ListItem
import androidx.compose.ui.text.style.TextOverflow
import io.nekohasekai.sfa.compose.ui.ListItemOpen
import io.nekohasekai.sfa.compose.ui.ListStyles
import io.nekohasekai.sfa.compose.ui.LocalBoxNavigator
import io.nekohasekai.sfa.compose.ui.LocalDialogs
import io.nekohasekai.sfa.compose.ui.SearchField
import io.nekohasekai.sfa.compose.ui.Section
import io.nekohasekai.sfa.compose.ui.appBarInset
import io.nekohasekai.sfa.compose.ui.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.max

// ---------------------------------------------------------------------------
// pages/profile_details.dart
// ---------------------------------------------------------------------------

@Composable
fun ProfileDetailsPage(box: BoxController, profile: Json?) {
    val navigator = LocalBoxNavigator.current
    val context = LocalContext.current
    val current = if (box.remote) null else if (profile == null) box.selectedProfile else box.profiles.firstOrNull { it["id"] == profile["id"] }
    if (!box.remote && profile != null && current == null) {
        BoxPage("Edit") { EmptyState("Profile no longer exists") }
        return
    }
    if (current == null) {
        BoxPage("Edit") {
            DetailRows(listOf("Name" to "—", "Remote control" to (box.dashboard["remote"] ?: "—"), "Proxy groups" to box.groups.size))
        }
        return
    }
    val p: Json = current
    BoxPage(
        "Edit",
        actions = listOfNotNull(
            IconAction(AppGlyphs.edit, tr("Edit"), { navigator?.openForm { ProfileForm(box, p) } }),
            if (p["remote"] == true) IconAction(AppGlyphs.sync, tr("Update"), { box.launchAct("profiles.update", mapOf("id" to p["id"])) }) else null,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp)) {
            EmojiText(p["name"].toString(), AppTextStyle.Title2.of(true).copy(color = BoxTheme.scheme.onSurface))
            Spacer(Modifier.height(14.dp))
            ProfileStats(box, p)
        }
        // profile_detail.dart's subscription section, shown only when the provider sent subscription-userinfo.
        val subscription = p.subscriptionInfo()
        val webPage = p.subscriptionWebPage()
        if (subscription != null) {
            SubscriptionInfoSection(subscription, webPage) { url -> box.launchAct("url.open", mapOf("url" to url)) }
        } else if (webPage != null) {
            Section(title = tr("Subscription info"), count = 1) { _, position ->
                ListItem(title = { Text(tr("Open website")) }, subtitle = { Text(webPage, maxLines = 1, overflow = TextOverflow.Ellipsis) }, position = position, onClick = { box.launchAct("url.open", mapOf("url" to webPage)) })
            }
        }
        val remote = p["remote"] == true
        val rows = buildList<@Composable (io.nekohasekai.sfa.compose.ui.ItemPosition) -> Unit> {
            if (remote) {
                add { position ->
                    ListItem(
                        title = { Text(tr("URL")) },
                        subtitle = { SelectionArea { Text(p["url"].toString()) } },
                        trailing = {
                            GlyphButton(AppGlyphs.copy, tr("Copy"), {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText(null, p["url"].toString()))
                            })
                        },
                        position = position,
                    )
                }
            }
            add { position ->
                ListItem(
                    title = { Text(tr("Last updated")) },
                    subtitle = { Text(if ((p["updatedAt"].asLong() ?: 0) <= 0) tr("Unknown") else localDate(p["updatedAt"])) },
                    position = position,
                )
            }
            if (remote) {
                add { position ->
                    ListItem(title = { Text(tr("Auto update")) }, trailing = { Text(tr(if (p["autoUpdate"] == true) "Turn on" else "Turn off")) }, position = position)
                }
                add { position ->
                    ListItem(title = { Text(tr("Update interval (minutes)")) }, trailing = { Text(p["interval"].asLong()?.toString() ?: p["interval"].toString()) }, position = position)
                }
            }
        }
        Section(rows = rows)
        // The structured editor understands sing-box options only.
        val structured = p.runsSingBox()
        Section(count = if (structured) 2 else 1) { index, position ->
            if (structured && index == 0) {
                ListItemOpen(tr("Advanced configuration"), position, leading = AppGlyphs.customize) { ProfileConfigurationPage(box, p) }
            } else {
                ListItemOpen(tr("Open configuration"), position, leading = AppGlyphs.code) { ProfileEditor(box, p) }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// pages/profile_editor.dart and components/code_editor.dart
// ---------------------------------------------------------------------------

/** JsonEditorController: keys in primary, strings in tertiary, literals and numbers in secondary. */
private val jsonTokens = Regex("(\"(?:\\\\.|[^\"\\\\])*\")(\\s*:)?|\\b(true|false|null)\\b|-?\\d+(?:\\.\\d+)?")

fun highlightJson(text: String, scheme: BoxScheme): AnnotatedString {
    if (text.length > 150000) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        for (token in jsonTokens.findAll(text)) {
            val color = when {
                token.groups[2] != null -> scheme.primary
                token.groups[1] != null -> scheme.tertiary
                else -> scheme.secondary
            }
            addStyle(SpanStyle(color = color), token.range.first, token.range.last + 1)
        }
    }
}

private class JsonHighlight(private val scheme: BoxScheme) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText = TransformedText(highlightJson(text.text, scheme), OffsetMapping.Identity)
}

/** Clash YAML for mihomo profiles: keys in primary, strings in tertiary, comments dimmed, literals and numbers in secondary. */
private val yamlTokens = Regex("(?m)(#.*$)|^(\\s*-?\\s*[^\\s#:'\"\\[\\]{},][^#:]*?)(:)(?=\\s|$)|(\"(?:\\\\.|[^\"\\\\])*\"|'[^']*')|\\b(true|false|null|~)\\b|(?<![\\w.])-?\\d+(?:\\.\\d+)?(?![\\w.])")

fun highlightYaml(text: String, scheme: BoxScheme): AnnotatedString {
    if (text.length > 150000) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        for (token in yamlTokens.findAll(text)) {
            val comment = token.groups[1]
            val key = token.groups[2]
            when {
                comment != null -> addStyle(SpanStyle(color = scheme.onSurface.copy(alpha = .5f)), comment.range.first, comment.range.last + 1)
                key != null -> addStyle(SpanStyle(color = scheme.primary), key.range.first, key.range.last + 1)
                token.groups[4] != null -> addStyle(SpanStyle(color = scheme.tertiary), token.range.first, token.range.last + 1)
                else -> addStyle(SpanStyle(color = scheme.secondary), token.range.first, token.range.last + 1)
            }
        }
    }
}

private class YamlHighlight(private val scheme: BoxScheme) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText = TransformedText(highlightYaml(text.text, scheme), OffsetMapping.Identity)
}

/** UndoHistoryController for the editor: edits coalesce while typing within a second. */
class EditorHistory {
    private val undo = ArrayDeque<TextFieldValue>()
    private val redo = ArrayDeque<TextFieldValue>()
    private var lastPush = 0L

    fun record(previous: TextFieldValue, next: TextFieldValue) {
        if (previous.text == next.text) return
        val now = System.currentTimeMillis()
        if (undo.isEmpty() || now - lastPush > 1000) {
            undo.addLast(previous)
            if (undo.size > 100) undo.removeFirst()
        }
        lastPush = now
        redo.clear()
    }

    fun reset() {
        undo.clear()
        redo.clear()
    }

    fun undo(current: TextFieldValue): TextFieldValue? = undo.removeLastOrNull()?.also { redo.addLast(current) }
    fun redo(current: TextFieldValue): TextFieldValue? = redo.removeLastOrNull()?.also { undo.addLast(current) }
}

private fun codeStyle(scheme: BoxScheme) = TextStyle(
    fontFamily = JetBrainsMono,
    fontSize = 13.sp,
    lineHeight = (13 * 1.6).sp,
    fontWeight = FontWeight.Normal,
    color = scheme.onSurface,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
)

/** code_editor.dart's CodeEditor: a JSON field with a gutter of logical line numbers, wrapped lines included. */
@Composable
fun CodeEditor(value: TextFieldValue, onValueChange: (TextFieldValue) -> Unit, readOnly: Boolean, modifier: Modifier = Modifier, scroll: ScrollState = rememberScrollState(), language: String = "json") {
    val scheme = BoxTheme.scheme
    val style = codeStyle(scheme)
    val density = LocalDensity.current
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val lineCount = value.text.count { it == '\n' } + 1
    val gutterWidth = with(density) { (lineCount.toString().length * 13 * 0.6f).sp.toDp() } + 24.dp
    val transformation = remember(scheme, language) { if (language == "yaml") YamlHighlight(scheme) else JsonHighlight(scheme) }
    var viewport by remember { mutableStateOf(0) }
    Row(modifier.fillMaxSize().onSizeChanged { viewport = it.height }) {
        Box(Modifier.width(gutterWidth).fillMaxHeight().background(scheme.surfaceContainerLow).clipToBounds()) {
            val result = layout
            if (result != null) {
                val offset = scroll.value
                var logical = 0
                val content = value.text
                for (line in 0 until result.lineCount) {
                    val lineStart = result.getLineStart(line)
                    val start = line == 0 || (lineStart > 0 && lineStart <= content.length && content[lineStart - 1] == '\n')
                    if (!start) continue
                    logical++
                    val top = result.getLineTop(line)
                    val inset = with(density) { 16.dp.toPx() }
                    if (top + inset - offset > -50 && top - offset < viewport + 50) {
                        Text(
                            "$logical",
                            Modifier.offset { IntOffset(0, (inset + top - offset).toInt()) }.fillMaxWidth().padding(end = 12.dp),
                            textAlign = TextAlign.End,
                            style = style.copy(color = scheme.onSurfaceVariant),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight().verticalScroll(scroll)) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                readOnly = readOnly,
                textStyle = style,
                cursorBrush = SolidColor(scheme.primary),
                visualTransformation = transformation,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
                onTextLayout = { layout = it },
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 16.dp, end = 16.dp, bottom = 16.dp),
            )
        }
    }
}

@Composable
fun ProfileEditor(box: BoxController, profile: Json) {
    val scope = rememberCoroutineScope()
    val dialogs = LocalDialogs.current
    val scheme = BoxTheme.scheme
    var text by remember { mutableStateOf(TextFieldValue("")) }
    val history = remember { EditorHistory() }
    var find by remember { mutableStateOf("") }
    var original by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableStateOf<String?>(null) }
    var language by remember { mutableStateOf("json") }
    var saving by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf(false) }
    val dirty = original != null && text.text != original
    val busy = saving || checking || loading
    val done = tr("Done")
    val valid = tr("Configuration is valid")
    val setText: (TextFieldValue) -> Unit = { next ->
        history.record(text, next)
        text = next
    }
    fun load(confirmDiscard: Boolean = false) {
        if (saving || checking || loading) return
        scope.launch {
            if (confirmDiscard && dirty && dialogs?.confirm("Discard changes?", action = "Discard") != true) return@launch
            error = null
            loading = true
            try {
                val data = json(box.call("profiles.config", mapOf("id" to profile["id"])))
                val content = data["content"] as String
                text = TextFieldValue(content)
                history.reset()
                original = content
                revision = data["revision"] as String
                language = data["language"] as? String ?: "json"
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                error = BoxController.describe(failure)
            } finally {
                loading = false
            }
        }
    }
    fun save() {
        val currentRevision = revision ?: return
        if (busy) return
        saving = true
        error = null
        scope.launch {
            try {
                val saved = json(box.call("profiles.write", mapOf("id" to profile["id"], "content" to text.text, "revision" to currentRevision)))
                original = text.text
                revision = saved["revision"] as String
                box.message(done)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                error = BoxController.describe(failure)
            }
            saving = false
        }
    }
    fun check(format: Boolean = false) {
        if (busy) return
        checking = true
        error = null
        scope.launch {
            try {
                val result = box.call(if (format) "config.format" else "config.check", mapOf("content" to text.text, "core" to (profile["core"] ?: "auto")))
                if (format) setText(TextFieldValue(result as String)) else box.message(valid)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                error = BoxController.describe(failure)
            }
            checking = false
        }
    }
    fun nextMatch() {
        if (find.isEmpty()) return
        val start = text.selection.end
        var index = text.text.indexOf(find, start)
        if (index < 0) index = text.text.indexOf(find)
        if (index < 0) return
        text = text.copy(selection = TextRange(index, index + find.length))
    }
    LaunchedEffect(Unit) { load() }
    BoxEditGuard(dirty = dirty, busy = busy) {
        CommonScaffold(
            title = profile["name"].toString() + if (dirty) " •" else "",
            loading = busy || (original == null && error == null),
            confirmationAction = IconAction(AppGlyphs.check, tr("Save"), if (busy || original == null) null else ({ save() })),
            iconActions = listOf(
                IconAction(AppGlyphs.refresh, tr("Reload"), if (busy) null else ({ load(confirmDiscard = true) })),
                IconAction(AppGlyphs.code, tr("Format"), if (busy || original == null) null else ({ check(format = true) })),
                IconAction(AppGlyphs.verified, tr("Validate"), if (busy || original == null) null else ({ check() })),
                IconAction(AppGlyphs.search, tr("Search"), { search = !search }),
                IconAction(AppGlyphs.undo, tr("Undo"), if (busy) null else ({ history.undo(text)?.let { text = it } })),
                IconAction(AppGlyphs.redo, tr("Redo"), if (busy) null else ({ history.redo(text)?.let { text = it } })),
            ),
        ) {
            Column(Modifier.fillMaxSize().padding(top = appBarInset()).navigationBarsPadding()) {
                if (search) {
                    Box(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) {
                                SearchField(find, { find = it }, fill = SurfaceLevel.Card.colorOf(scheme), onSubmitted = { nextMatch() })
                            }
                            GlyphButton(AppGlyphs.arrowDown, tr("Search"), { nextMatch() })
                        }
                    }
                }
                if (error != null) {
                    Box(Modifier.fillMaxWidth().background(scheme.errorContainer).padding(12.dp)) {
                        SelectionArea { Text(error!!, style = androidx.compose.material3.LocalTextStyle.current.copy(color = scheme.onErrorContainer)) }
                    }
                }
                if (profile["remote"] == true) {
                    Text(tr("Configuration changes will replace the remote content until its next update."), Modifier.padding(12.dp), style = ListStyles.caption)
                }
                Box(Modifier.weight(1f)) {
                    when {
                        original == null && error == null -> Spacer(Modifier)
                        original == null -> ErrorState(error!!) { load() }
                        else -> CodeEditor(text, setText, readOnly = busy, language = language)
                    }
                }
            }
        }
    }
}

