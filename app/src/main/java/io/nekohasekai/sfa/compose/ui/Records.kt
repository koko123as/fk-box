package io.nekohasekai.sfa.compose.ui

import androidx.compose.foundation.text.selection.SelectionContainer

import androidx.compose.ui.graphics.vector.ImageVector

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.nekohasekai.sfa.compose.BoxController
import io.nekohasekai.sfa.compose.Json
import io.nekohasekai.sfa.compose.asLong
import io.nekohasekai.sfa.compose.bytes
import io.nekohasekai.sfa.compose.json
import io.nekohasekai.sfa.compose.rows
import io.nekohasekai.sfa.compose.theme.AppShape
import io.nekohasekai.sfa.compose.theme.AppTextStyle
import io.nekohasekai.sfa.compose.theme.BoxTheme
import io.nekohasekai.sfa.compose.theme.LocalSurfaceLevel
import io.nekohasekai.sfa.compose.theme.jetBrainsMono
import io.nekohasekai.sfa.compose.theme.tabular
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

// ---------------------------------------------------------------------------
// components/record.dart: the cards of the logs, connections and requests lists
// ---------------------------------------------------------------------------

/** record.dart's recordTime: HH:mm:ss in local time, or a dash. */
fun recordTime(value: Any?): String {
    val ms = value.asLong() ?: 0L
    if (ms <= 0) return "—"
    return java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date(ms))
}

const val RECORD_LIST_GAP = 8

class RecordTextStyles(val title: TextStyle, val primary: TextStyle, val secondary: TextStyle)

@Composable
fun recordTextStyles(): RecordTextStyles {
    val scheme = BoxTheme.scheme
    return RecordTextStyles(
        title = AppTextStyle.Subheadline.of(emphasized = true).copy(color = scheme.onSurface),
        primary = AppTextStyle.Subheadline.of().copy(color = scheme.onSurface),
        secondary = AppTextStyle.Footnote.of().copy(color = scheme.onSurfaceVariant),
    )
}

enum class RecordTone { Neutral, Warning, Error }

@Composable
private fun RecordTone.labelColor(): Color {
    val scheme = BoxTheme.scheme
    return when (this) {
        RecordTone.Neutral -> scheme.onSurfaceVariant
        RecordTone.Warning -> scheme.onWarningContainer
        RecordTone.Error -> scheme.onErrorContainer
    }
}

@Composable
private fun RecordTone.labelContainerColor(): Color {
    val scheme = BoxTheme.scheme
    return when (this) {
        RecordTone.Neutral -> raisedSurfaceLevel.colorOf(scheme)
        RecordTone.Warning -> scheme.warningContainer
        RecordTone.Error -> scheme.errorContainer
    }
}

/** record.dart's RecordListItem: a 16 dp inset card, its header over its body. */
@Composable
fun RecordListItem(
    header: @Composable () -> Unit,
    body: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    onTap: (() -> Unit)? = null,
) {
    val scheme = BoxTheme.scheme
    val level = raisedSurfaceLevel
    val shape = AppShape.card
    val interaction = remember { MutableInteractionSource() }
    Box(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(level.colorOf(scheme), shape)
                // record.dart: a PressSurface ink on the raised row.
                .then(if (onTap != null) Modifier.clip(shape).clickable(onClick = onTap) else Modifier)
                .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 12.dp),
        ) {
            CompositionLocalProvider(LocalSurfaceLevel provides level) {
                header()
                Spacer(Modifier.padding(top = 6.dp))
                body()
            }
        }
    }
}

/** record.dart's RecordHeader: chips on the start, an optional trailing control at the end. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecordHeader(trailing: (@Composable () -> Unit)? = null, children: @Composable () -> Unit) {
    val style = AppTextStyle.Caption1.of().tabular.copy(color = BoxTheme.scheme.onSurfaceVariant)
    ProvideTextStyle(style) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FlowRow(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) { children() }
            if (trailing != null) {
                Spacer(Modifier.width(12.dp))
                trailing()
            }
        }
    }
}

/** chip.dart's TonalChip: a small superellipse with a caption label. */
@Composable
fun TonalChip(label: String, color: Color, foregroundColor: Color, onPressed: (() -> Unit)? = null) {
    Box(
        Modifier
            .background(color, AppShape.card)
            .then(if (onPressed != null) Modifier.clip(AppShape.card).clickable(onClick = onPressed) else Modifier)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = AppTextStyle.Caption1.of(emphasized = true).copy(color = foregroundColor))
    }
}

@Composable
fun RecordLabel(label: String, tone: RecordTone = RecordTone.Neutral, onPressed: (() -> Unit)? = null) {
    TonalChip(label, tone.labelContainerColor(), tone.labelColor(), onPressed)
}

@Composable
fun RecordTimestamp(dateTime: String) {
    Text(dateTime, maxLines = 1)
}

@Composable
fun RecordArrow() {
    Text("→", style = recordTextStyles().secondary.jetBrainsMono)
}

/** record.dart's DetailRow: a tap copies the value; [stacked] puts it under the title. */
@Composable
fun RecordDetailRow(
    title: String,
    value: String,
    position: ItemPosition?,
    stacked: Boolean = false,
    copyText: String? = value,
    content: (@Composable () -> Unit)? = null,
) {
    val context = LocalContext.current
    val box = LocalBoxController.current
    val copied = tr("Copied")
    val shown: @Composable () -> Unit = content ?: { SelectionContainer { Text(value) } }
    ListItem(
        title = {
            if (stacked) {
                Text(title)
            } else {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val width = maxWidth
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(title, Modifier.widthIn(max = (width - 20.dp) * 0.45f))
                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                            ProvideTextStyle(AppTextStyle.Subheadline.of().copy(color = BoxTheme.scheme.onSurfaceVariant, textAlign = TextAlign.End)) { shown() }
                        }
                    }
                }
            }
        },
        subtitle = if (stacked) shown else null,
        position = position,
        onClick = copyText?.let { text ->
            {
                setClipboard(context, text)
                box?.message(copied)
            }
        },
    )
}

/** list.dart's ListItem.checkbox: the whole row flips the box. */
@Composable
fun ListItemCheckbox(
    title: String,
    value: Boolean,
    position: ItemPosition?,
    subtitle: String? = null,
    onChanged: ((Boolean) -> Unit)?,
) {
    ListItem(
        title = { Text(title) },
        subtitle = subtitle?.let { { Text(it) } },
        trailing = {
            Checkbox(
                checked = value,
                onCheckedChange = onChanged,
                colors = CheckboxDefaults.colors(checkedColor = BoxTheme.scheme.primary),
            )
        },
        position = position,
        onClick = onChanged?.let { { it(!value) } },
    )
}

/** list.dart's ListHeader: a section title with its icon buttons. */
@Composable
fun ListHeader(title: String, actions: List<IconAction> = emptyList()) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 24.dp, bottom = 4.dp).heightIn(min = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), style = AppTextStyle.Headline.of().copy(color = BoxTheme.scheme.onSurfaceVariant))
        for (action in actions) {
            AppIconButton(action.icon, action.tooltip, action.onClick, color = if (action.selected) BoxTheme.scheme.primary else BoxTheme.scheme.onSurfaceVariant)
        }
    }
}

/** generateSection with header actions: [ListHeader], then the rows on one card. */
@Composable
fun ActionSection(title: String, actions: List<IconAction>, footer: String? = null, rows: List<@Composable (ItemPosition) -> Unit>) {
    ListHeader(title, actions)
    Column(Modifier.fillMaxWidth()) {
        rows.forEachIndexed { index, row -> row(ItemPosition.of(index, rows.size)) }
    }
    if (footer != null) ListFooter(footer)
}

fun setClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(null, text))
}

/** The controller pages reach without threading it through every row. */
val LocalBoxController = androidx.compose.runtime.staticCompositionLocalOf<BoxController?> { null }

// ---------------------------------------------------------------------------
// Page activity: PageActivityScope and the app lifecycle together
// ---------------------------------------------------------------------------

/** True while this route is the innermost visible one and the app is resumed. */
@Composable
fun rememberPageActive(): Boolean {
    val navigator = LocalBoxNavigator.current
    val level = LocalNavLevel.current
    val top = navigator?.entries?.lastOrNull { !it.closing }
    val inner = top?.children?.lastOrNull { !it.closing } ?: top
    val onTop = navigator == null || level === inner
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return onTop && resumed
}

// ---------------------------------------------------------------------------
// components/capture_poll.dart
// ---------------------------------------------------------------------------

class PollState(val value: Json?, val error: Throwable?, val refresh: () -> Unit)

/**
 * Capture lists, details and logs poll every two seconds only while their page
 * is visible in the foreground; a change of [key] (the request's arguments) or
 * [revision] restarts the cycle, so a slow reply never replaces a newer filter.
 */
@Composable
fun rememberCapturePoll(
    box: BoxController,
    key: Any?,
    revision: Int = 0,
    fetch: suspend io.nekohasekai.sfa.bridge.BoxBridge.() -> Any?,
): PollState {
    val active = rememberPageActive() && !box.remote
    var value by remember { mutableStateOf<Json?>(null) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var manual by remember { mutableIntStateOf(0) }
    val currentFetch by rememberUpdatedState(fetch)
    LaunchedEffect(active, key, revision, manual) {
        if (!active) return@LaunchedEffect
        while (true) {
            try {
                value = json(box.call(currentFetch))
                error = null
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (failure: Throwable) {
                error = failure
            }
            delay(2000)
        }
    }
    return PollState(value, error) { manual++ }
}

// ---------------------------------------------------------------------------
// common/http_message.dart
// ---------------------------------------------------------------------------

fun capturedHeader(headers: List<Json>, name: String): String =
    headers.firstOrNull { it["name"]?.toString()?.lowercase() == name.lowercase() }?.get("value")?.toString() ?: ""

class CapturedCookie(val name: String, val value: String, val raw: String, val attributes: List<String> = emptyList())

fun capturedCookies(headers: List<Json>, response: Boolean): List<CapturedCookie> {
    val cookies = mutableListOf<CapturedCookie>()
    for (header in headers) {
        if (header["name"]?.toString()?.lowercase() != (if (response) "set-cookie" else "cookie")) continue
        val raw = header["value"]?.toString() ?: ""
        // Set-Cookie headers are separate records. A comma can belong to Expires;
        // neither it nor an equals sign inside a value separates cookies.
        for (entry in if (response) listOf(raw) else raw.split(';')) {
            val parts = entry.split(';')
            val pair = parts.first().trim()
            val separator = pair.indexOf('=')
            if (separator <= 0) continue
            val name = pair.substring(0, separator).trim()
            if (name.isEmpty()) continue
            cookies.add(
                CapturedCookie(
                    name = name,
                    value = pair.substring(separator + 1).trim(),
                    raw = if (response) raw else pair,
                    attributes = parts.drop(1).map { it.trim() }.filter { it.isNotEmpty() },
                ),
            )
        }
    }
    return cookies
}

/** Adds whitespace without rewriting numbers, duplicate keys or string escapes. */
fun formatCapturedJson(text: String): String? {
    val source = text.trim()
    if (source.length > 65536 || !(source.startsWith("{") || source.startsWith("["))) return null
    val output = StringBuilder()
    var depth = 0
    var quoted = false
    var escaped = false
    var previous = ' '
    fun whitespace(c: Char) = c == ' ' || c == '\t' || c == '\n' || c == '\r'
    fun newLine() {
        output.append('\n')
        repeat(depth) { output.append("  ") }
    }
    var index = 0
    while (index < source.length) {
        val char = source[index]
        if (quoted) {
            output.append(char)
            if (escaped) {
                escaped = false
            } else if (char == '\\') {
                escaped = true
            } else if (char == '"') {
                quoted = false
                previous = char
            }
        } else if (!whitespace(char)) {
            when (char) {
                '"' -> {
                    quoted = true
                    output.append(char)
                }
                '{', '[' -> {
                    depth++
                    if (depth > 64) return null
                    output.append(char)
                    var next = index + 1
                    while (next < source.length && whitespace(source[next])) next++
                    if (next < source.length && source[next] != (if (char == '{') '}' else ']')) newLine()
                }
                '}', ']' -> {
                    depth--
                    if (depth < 0) return null
                    if (previous != '{' && previous != '[') newLine()
                    output.append(char)
                }
                ',' -> {
                    output.append(char)
                    newLine()
                }
                ':' -> output.append(": ")
                else -> output.append(char)
            }
            previous = char
        }
        if (output.length > 262144) return null
        index++
    }
    if (quoted || depth != 0) return null
    return try {
        val parsed = JSONTokener(source).nextValue()
        if (parsed is JSONObject || parsed is JSONArray) output.toString() else null
    } catch (_: Exception) {
        null
    }
}

fun capturedTextPrefix(text: String, limit: Int): String {
    if (text.length <= limit) return text
    var end = limit
    if (end > 0 && Character.isHighSurrogate(text[end - 1]) && Character.isLowSurrogate(text[end])) end--
    return text.substring(0, end)
}

// ---------------------------------------------------------------------------
// components/captured_body.dart
// ---------------------------------------------------------------------------

private const val BODY_PAGE_SIZE = 16384

@Composable
fun CapturedBody(message: Json, preview: Json) {
    val context = LocalContext.current
    val box = LocalBoxController.current
    val scheme = BoxTheme.scheme
    val copied = tr("Copied")
    val text0 = preview["text"]?.toString() ?: ""
    val pretty = remember(preview["text"], preview["encoding"], preview["truncated"], message["bodyComplete"], message["bodyTruncated"]) {
        if (preview["encoding"] == "text" && preview["truncated"] != true && message["bodyComplete"] == true && message["bodyTruncated"] != true) {
            formatCapturedJson(text0)
        } else {
            null
        }
    }
    var limit by remember { mutableIntStateOf(BODY_PAGE_SIZE) }
    var formatted by remember { mutableStateOf(true) }
    val scroll = rememberScrollState()
    val contentType = capturedHeader(rows(message["headers"]), "content-type")
    val text = if (formatted) pretty ?: text0 else text0
    val visible = capturedTextPrefix(text, limit)
    val caption = ListStyles.caption
    val subtitle = ListStyles.subtitle
    LaunchedEffect(formatted, limit == BODY_PAGE_SIZE) { if (limit == BODY_PAGE_SIZE) scroll.scrollTo(0) }
    Column(Modifier.fillMaxWidth()) {
        ListHeader(
            tr("Body"),
            buildList {
                if (pretty != null) {
                    add(
                        IconAction(AppIcons.code, tr(if (formatted) "Show original" else "Format JSON"), {
                            formatted = !formatted
                            limit = BODY_PAGE_SIZE
                        }, selected = formatted),
                    )
                }
                add(
                    IconAction(AppIcons.copy, tr("Copy body preview"), if (text0.isEmpty()) null else ({
                        setClipboard(context, text0)
                        box?.message(copied)
                    })),
                )
            },
        )
        CommonCard(shape = AppShape.card, padding = PaddingValues(16.dp)) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    listOfNotNull(
                        contentType.ifEmpty { null },
                        "${bytes(message["capturedBytes"] as? Number ?: 0)} / ${bytes(message["bodyBytes"] as? Number ?: 0)}",
                        if (preview["encoding"] == "base64") "Base64" else null,
                        if (formatted && pretty != null) "JSON" else null,
                    ).joinToString(" · "),
                    style = caption,
                )
                if (message["bodyTruncated"] == true || preview["truncated"] == true) {
                    Text(tr("Only a limited body preview was saved or decoded."), style = subtitle)
                }
                if (message["bodyComplete"] != true) Text(tr("Body stream is incomplete"), style = subtitle)
                if (text.isEmpty()) {
                    Text(tr("No body captured"), style = subtitle)
                } else {
                    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.5f).dp
                    Box(Modifier.fillMaxWidth().heightIn(max = maxHeight).verticalScroll(scroll)) {
                        SelectionContainer {
                            Text(
                                visible,
                                Modifier.fillMaxWidth(),
                                style = AppTextStyle.Footnote.of().jetBrainsMono.copy(lineHeight = (AppTextStyle.Footnote.size * 1.5f).sp, color = scheme.onSurface),
                            )
                        }
                    }
                }
                if (text.length > visible.length || limit > BODY_PAGE_SIZE) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (text.length > visible.length) TextPillButton(tr("Show more"), { limit += BODY_PAGE_SIZE })
                        if (limit > BODY_PAGE_SIZE) TextPillButton(tr("Show less"), { limit = BODY_PAGE_SIZE })
                    }
                }
            }
        }
    }
}

/** A determinate or running bar, as LinearProgressIndicator. */
@Composable
fun BoxProgress(value: Float? = null, color: Color = BoxTheme.scheme.primary, modifier: Modifier = Modifier) {
    if (value == null) {
        androidx.compose.material3.LinearProgressIndicator(modifier.fillMaxWidth(), color = color)
    } else {
        androidx.compose.material3.LinearProgressIndicator(progress = { value }, modifier = modifier.fillMaxWidth(), color = color)
    }
}

/** Center(child: CircularProgressIndicator()). */
@Composable
fun CenteredLoading() {
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CommonCircleLoading(32.dp) }
}

/** A labelled text field as Material's TextField with labelText. */
@Composable
fun LabeledField(
    value: String,
    onChanged: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    secret: Boolean = false,
    readOnly: Boolean = false,
    keyboardType: androidx.compose.ui.text.input.KeyboardType = androidx.compose.ui.text.input.KeyboardType.Text,
    onSubmitted: (() -> Unit)? = null,
) {
    androidx.compose.material3.OutlinedTextField(
        value = value,
        onValueChange = onChanged,
        label = { Text(label) },
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        readOnly = readOnly,
        shape = AppShape.card,
        visualTransformation = if (secret) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = keyboardType, autoCorrectEnabled = false),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onSubmitted?.invoke() }),
    )
}

/** A trailing round icon button, in a row's trailing slot. */
@Composable
fun TrailingIcon(icon: ImageVector, tooltip: String, onClick: (() -> Unit)?) {
    AppIconButton(icon, tooltip, onClick)
}
