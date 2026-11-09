package io.nekohasekai.sfa.compose.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.compose.BoxStrings
import io.nekohasekai.sfa.compose.theme.BoxTheme
import kotlinx.coroutines.CompletableDeferred

/** Confirmations, choices and text input, each a Material 3 AlertDialog. */
@Stable
class DialogHost {
    internal val requests = mutableStateListOf<DialogRequest>()
    internal var chinese = false

    private fun t(value: String) = if (chinese) BoxStrings.zh[value] ?: value else value

    /** Cancel and [action]; true only for [action]. */
    suspend fun confirm(title: String, message: String? = null, action: String = "Delete"): Boolean {
        val result = CompletableDeferred<Any?>()
        val request = DialogRequest.Confirm(t(title), message?.let(::t), t(action), result)
        requests.add(request)
        return try {
            result.await() == true
        } finally {
            requests.remove(request)
        }
    }

    /** A dialog with its own actions: the picked action's value, or null when dismissed. */
    suspend fun choose(title: String, message: String?, actions: List<Pair<String, String?>>): String? {
        val result = CompletableDeferred<Any?>()
        val request = DialogRequest.Choice(t(title), message, actions.map { t(it.first) to it.second }, result)
        requests.add(request)
        return try {
            result.await() as? String
        } finally {
            requests.remove(request)
        }
    }

    /** A text field: the text, or null when cancelled. */
    suspend fun input(
        title: String,
        value: String,
        keyboardType: KeyboardType = KeyboardType.Text,
        suffixText: String? = null,
        resetValue: String? = null,
        validator: ((String) -> String?)? = null,
    ): String? {
        val result = CompletableDeferred<Any?>()
        val request = DialogRequest.Input(title, value, keyboardType, suffixText, resetValue, validator, result)
        requests.add(request)
        return try {
            result.await() as? String
        } finally {
            requests.remove(request)
        }
    }
}

internal sealed class DialogRequest(val result: CompletableDeferred<Any?>) {
    class Choice(val title: String, val message: String?, val actions: List<Pair<String, String?>>, result: CompletableDeferred<Any?>) : DialogRequest(result)
    class Confirm(val title: String, val message: String?, val action: String, result: CompletableDeferred<Any?>) : DialogRequest(result)
    class Input(
        val title: String,
        val value: String,
        val keyboardType: KeyboardType,
        val suffixText: String?,
        val resetValue: String?,
        val validator: ((String) -> String?)?,
        result: CompletableDeferred<Any?>,
    ) : DialogRequest(result)
}

val LocalDialogs = staticCompositionLocalOf<DialogHost?> { null }

@Composable
fun rememberDialogHost(): DialogHost = remember { DialogHost() }

@Composable
internal fun DialogLayer(host: DialogHost) {
    host.chinese = isChinese()
    val request = host.requests.lastOrNull() ?: return
    androidx.compose.runtime.key(request) {
        when (request) {
            is DialogRequest.Confirm -> CommonDialog(
                title = request.title,
                onDismiss = { request.result.complete(false) },
                actions = listOf(DialogAction(tr("Cancel"), { request.result.complete(false) }), DialogAction(request.action, { request.result.complete(true) })),
                content = request.message?.let { message -> { Text(message) } },
            )
            is DialogRequest.Choice -> CommonDialog(
                title = request.title,
                onDismiss = { request.result.complete(null) },
                actions = request.actions.map { (label, value) -> DialogAction(label, { request.result.complete(value) }) },
                content = request.message?.let { message -> { SelectionContainer { Text(message) } } },
            )
            is DialogRequest.Input -> InputDialog(request)
        }
    }
}

class DialogAction(val label: String, val onClick: (() -> Unit)?)

/**
 * A Material 3 AlertDialog: the last action is the confirm button (in the
 * error colour when [destructive]), the others sit before it as text buttons.
 */
@Composable
fun CommonDialog(
    title: String,
    onDismiss: () -> Unit,
    actions: List<DialogAction>,
    destructive: Boolean = false,
    content: (@Composable () -> Unit)? = null,
) {
    val confirm = actions.lastOrNull()
    val others = actions.dropLast(1)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = content?.let { body -> { androidx.compose.foundation.layout.Box(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) { body() } } },
        confirmButton = {
            if (confirm != null) {
                TextButton(onClick = { confirm.onClick?.invoke() }, enabled = confirm.onClick != null) {
                    Text(confirm.label, color = if (destructive) BoxTheme.scheme.error else androidx.compose.ui.graphics.Color.Unspecified)
                }
            }
        },
        dismissButton = if (others.isEmpty()) {
            null
        } else {
            {
                Row {
                    for (action in others) {
                        TextButton(onClick = { action.onClick?.invoke() }, enabled = action.onClick != null) { Text(action.label) }
                    }
                }
            }
        },
    )
}

@Composable
private fun InputDialog(request: DialogRequest.Input) {
    var value by remember { mutableStateOf(TextFieldValue(request.value, TextRange(request.value.length))) }
    var touched by remember { mutableStateOf(false) }
    val error = if (touched) request.validator?.invoke(value.text) else null
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val submit = {
        touched = true
        if (request.validator?.invoke(value.text) == null) request.result.complete(value.text)
    }
    val reset = request.resetValue
    CommonDialog(
        title = request.title,
        onDismiss = { request.result.complete(null) },
        actions = listOf(
            if (reset != null && value.text != reset) {
                DialogAction(tr("Reset")) { request.result.complete(reset) }
            } else {
                DialogAction(tr("Cancel")) { request.result.complete(null) }
            },
            DialogAction(tr("Confirm")) { submit() },
        ),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {
                value = it
                touched = true
            },
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
            singleLine = true,
            isError = error != null,
            supportingText = error?.let { message -> { Text(message) } },
            suffix = request.suffixText?.let { suffix -> { Text(suffix) } },
            keyboardOptions = KeyboardOptions(keyboardType = request.keyboardType),
            keyboardActions = KeyboardActions(onDone = { submit() }),
        )
    }
}
