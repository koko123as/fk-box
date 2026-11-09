// Translated from FlClash v0.9.3 (be84736) lib/features/form/quick_edit.dart
// and lib/common/input_entries.dart (parseListInput / parseLineInput).
package io.nekohasekai.sfa.compose.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.compose.BoxController
import io.nekohasekai.sfa.compose.theme.BoxTheme
import io.nekohasekai.sfa.compose.theme.JetBrainsMono
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Thrown by an `apply` the user called off, which keeps the editor open. */
class QuickEditCancelled : Exception()

/** An input the editor rejects; its message is already shown to the user as is. */
class QuickEditInvalid(message: String) : IllegalArgumentException(message)

/** One rejected value: its 1-based line, the value and why. */
data class InputIssue(val line: Int, val raw: String, val kind: Kind) {
    enum class Kind { ValueTooLong, InvalidValue }
}

class ParsedInput(val entries: List<String>, val issues: List<InputIssue>) {
    val isValid: Boolean get() = issues.isEmpty()
}

private val lineSeparator = Regex("\\r?\\n")
private val listSeparator = Regex("[,，]")

/** BatchInput.maxShownIssues. */
const val QUICK_EDIT_MAX_SHOWN_ISSUES = 3

/** Lines copied out of a YAML or JSON sequence keep their `- ` marker, trailing comma and quotes. */
private fun stripLineMarkup(line: String): String {
    var value = line.trim()
    if (value.startsWith("- ")) value = value.substring(2).trim()
    if (value.endsWith(",")) value = value.dropLast(1).trim()
    return value
}

private fun unquote(value: String): String {
    if (value.length < 2) return value
    val first = value[0]
    return if ((first == '"' || first == '\'') && value.endsWith(first)) value.substring(1, value.length - 1).trim() else value
}

/** input_entries.dart's parseListInput: one item a line (commas also split), blanks and repeats dropped. */
fun parseListInput(text: String, maxLength: Int? = null, isValid: ((String) -> Boolean)? = null): ParsedInput {
    val entries = mutableListOf<String>()
    val issues = mutableListOf<InputIssue>()
    val seen = HashSet<String>()
    text.split(lineSeparator).forEachIndexed { index, rawLine ->
        for (raw in stripLineMarkup(rawLine).split(listSeparator)) {
            val value = unquote(raw.trim())
            if (value.isEmpty()) continue
            if (maxLength != null && value.length > maxLength) {
                issues.add(InputIssue(index + 1, value, InputIssue.Kind.ValueTooLong))
                continue
            }
            if (isValid != null && !isValid(value)) {
                issues.add(InputIssue(index + 1, value, InputIssue.Kind.InvalidValue))
                continue
            }
            if (seen.add(value)) entries.add(value)
        }
    }
    return ParsedInput(entries, issues)
}

/** input_entries.dart's parseLineInput: keeps each non-blank line whole, so its commas and quotes stay its own. */
fun parseLineInput(text: String): List<Pair<Int, String>> =
    text.split(lineSeparator).mapIndexedNotNull { index, line -> line.trim().takeIf { it.isNotEmpty() }?.let { index + 1 to it } }

/** quick_edit.dart's _throwIssues: the first three issues as "Line n: message". */
fun throwQuickEditIssues(issues: List<InputIssue>, t: (String) -> String, messageOf: (InputIssue) -> String) {
    if (issues.isEmpty()) return
    throw QuickEditInvalid(issues.take(QUICK_EDIT_MAX_SHOWN_ISSUES).joinToString("\n") { lineIssue(t, it.line, messageOf(it)) })
}

fun lineIssue(t: (String) -> String, line: Int, message: String): String = t("Line %1\$d: %2\$s").replace("%1\$d", "$line").replace("%2\$s", message)

/**
 * quick_edit.dart's showQuickEdit: edits a list as text. Saving an unchanged
 * text closes the page; otherwise [apply] runs, and a failure shows its
 * message (or [invalidMessage] for an invalid number) with "Discard changes?",
 * leaving the editor open unless the user discards. [QuickEditCancelled]
 * keeps it open silently.
 */
@Composable
fun QuickEditPage(title: String, content: String, invalidMessage: String? = null, hint: String? = null, apply: suspend (String) -> Unit) {
    val navigator = LocalBoxNavigator.current
    val dialogs = LocalDialogs.current
    val scope = rememberCoroutineScope()
    val scheme = BoxTheme.scheme
    val discardText = tr("Discard changes?")
    var text by remember { mutableStateOf(TextFieldValue(content)) }
    var saving by remember { mutableStateOf(false) }
    BoxEditGuard(dirty = text.text != content, busy = saving) {
        BoxPage(
            title,
            loading = saving,
            confirmationAction = IconAction(AppIcons.check, tr("Save"), if (saving) null else ({
                if (text.text == content) {
                    navigator?.pop()
                } else {
                    saving = true
                    scope.launch {
                        val failure = try {
                            apply(text.text)
                            null
                        } catch (failure: CancellationException) {
                            throw failure
                        } catch (failure: QuickEditCancelled) {
                            saving = false
                            return@launch
                        } catch (failure: Exception) {
                            failure
                        }
                        saving = false
                        if (failure == null) {
                            navigator?.pop()
                        } else {
                            val message = if (failure is NumberFormatException && invalidMessage != null) invalidMessage else BoxController.describe(failure)
                            if (dialogs?.confirm("Quick edit", message = "$message\n\n$discardText", action = "Discard") == true) navigator?.pop()
                        }
                    }
                }
            })),
        ) {
            ListFooter(tr("One item per line."))
            Box(Modifier.padding(top = 8.dp)) {
                androidx.compose.material3.OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    readOnly = saving,
                    minLines = 10,
                    maxLines = 24,
                    placeholder = hint?.let { { androidx.compose.material3.Text(it) } },
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = JetBrainsMono, fontSize = TextUnit(13f, TextUnitType.Sp), lineHeight = TextUnit(13f * 1.6f, TextUnitType.Sp), color = scheme.onSurface),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
                )
            }
        }
    }
}

/** quick_edit.dart's showListQuickEdit: one item a line; [validator] names why an item is rejected. */
@Composable
fun ListQuickEditPage(
    title: String,
    items: List<String>,
    hint: String? = null,
    itemMaxLength: Int? = null,
    validator: ((String) -> String?)? = null,
    onChanged: suspend (List<String>) -> Unit,
) {
    val t = trFunction()
    QuickEditPage(title, items.joinToString("\n"), hint = hint) { content ->
        val parsed = parseListInput(content, itemMaxLength, validator?.let { check -> { item: String -> check(item) == null } })
        throwQuickEditIssues(parsed.issues, t) { issue ->
            when (issue.kind) {
                InputIssue.Kind.InvalidValue -> validator?.invoke(issue.raw) ?: t("Invalid value")
                InputIssue.Kind.ValueTooLong -> t("Value must be at most %d characters").replace("%d", "${itemMaxLength ?: 0}")
            }
        }
        onChanged(parsed.entries)
    }
}

/** A translator for strings built outside composition, as tr(). */
@Composable
fun trFunction(): (String) -> String {
    val locales = androidx.compose.ui.platform.LocalConfiguration.current.locales
    val zh = !locales.isEmpty && locales[0].language == "zh"
    return { value -> if (zh) io.nekohasekai.sfa.compose.BoxStrings.zh[value] ?: value else value }
}
