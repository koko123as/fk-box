package io.nekohasekai.sfa.compose.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.compose.BoxStrings
import io.nekohasekai.sfa.compose.theme.AppCorner
import io.nekohasekai.sfa.compose.theme.AppShape
import io.nekohasekai.sfa.compose.theme.AppTextStyle
import io.nekohasekai.sfa.compose.theme.BoxTheme
import io.nekohasekai.sfa.compose.theme.SurfaceLevel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch

/*
 * widgets.dart's confirm (a stock Material AlertDialog) and dialog.dart's
 * CommonDialog / showFloatingDialog: an iOS-style alert 348 dp wide that
 * enters from 1.2× on a 275 ms spring over 404 ms, behind the alert scrim,
 * with 48 dp pill actions whose last one is the filled default.
 */

private const val ACTION_GAP = 8f
private const val ACTION_INSET = 16f
private const val ACTION_HEIGHT = 48f
private const val TEXT_INSET = 30f
private const val ALERT_WIDTH = 348f
private const val ALERT_ENTRY_SCALE = 1.2f

@Stable
class DialogHost {
    internal val requests = mutableStateListOf<DialogRequest>()
    internal var chinese = false

    private fun t(value: String) = if (chinese) BoxStrings.zh[value] ?: value else value

    /** widgets.dart's confirm: Cancel and [action]; true only for [action]. */
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

    /** An alert with its own actions: the picked action's value, or null when dismissed. */
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

    /** input.dart's InputDialog in showFloatingDialog: the text, or null when cancelled. */
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
            is DialogRequest.Confirm -> AlertDialog(
                onDismissRequest = { request.result.complete(false) },
                title = { Text(request.title) },
                text = request.message?.let { message -> { Text(message) } },
                dismissButton = { TextButton(onClick = { request.result.complete(false) }) { Text(tr("Cancel")) } },
                confirmButton = { TextButton(onClick = { request.result.complete(true) }) { Text(request.action) } },
            )
            is DialogRequest.Choice -> FloatingDialog(onDismiss = { request.result.complete(null) }) {
                CommonDialog(
                    title = request.title,
                    actions = request.actions.map { (label, value) -> DialogAction(label, { request.result.complete(value); Unit }) },
                    content = request.message?.let { message -> { androidx.compose.foundation.text.selection.SelectionContainer { Text(message) } } },
                )
            }
            is DialogRequest.Input -> FloatingDialog(onDismiss = { request.result.complete(null) }) { InputDialog(request) }
        }
    }
}

/** showFloatingDialog's route: the alert scrim and the alert's spring entrance. */
@Composable
fun FloatingDialog(onDismiss: () -> Unit, dismissible: Boolean = true, content: @Composable () -> Unit) {
    val scheme = BoxTheme.scheme
    val shown = remember { Animatable(0f) }
    LaunchedEffect(Unit) { shown.animateTo(1f, spring(dampingRatio = 1f, stiffness = 522f)) }
    androidx.activity.compose.BackHandler(enabled = dismissible) { onDismiss() }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = shown.value.coerceIn(0f, 1f) }
            .background(scheme.scrim.copy(alpha = if (scheme.dark) 0.48f else 0.2f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { if (dismissible) onDismiss() }
            .imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .graphicsLayer {
                    val scale = ALERT_ENTRY_SCALE + (1 - ALERT_ENTRY_SCALE) * shown.value
                    scaleX = scale
                    scaleY = scale
                }
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        ) { content() }
    }
}

/** dialog.dart's CommonDialog. */
@Composable
fun CommonDialog(
    title: String,
    actions: List<DialogAction>,
    hasFields: Boolean = false,
    destructive: Boolean = false,
    content: (@Composable () -> Unit)? = null,
) {
    val scheme = BoxTheme.scheme
    val contentInset = if (hasFields) ACTION_INSET else TEXT_INSET
    // On glass an alert stands on LiquidGlassStyle.alert, casting menuShadows.
    val glass = LocalLiquidGlass.current.shaded && LocalGlassBackdrop.current != null
    val alertGlass = remember(scheme) { LiquidGlassStyle.alert(scheme) }
    Box(
        Modifier
            .width(ALERT_WIDTH.dp)
            .padding(horizontal = 24.dp)
            .elasticPress(maxGrowth = 4f),
    ) {
    if (glass) {
        LiquidGlassPane(
            alertGlass,
            Modifier.matchParentSize().boxShadows(AppShape.all(AppCorner.ALERT), menuShadows(scheme)),
            cornerRadius = AppCorner.ALERT,
        )
    }
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (glass) Modifier else Modifier.background(SurfaceLevel.floatingOf(scheme).colorOf(scheme), AppShape.all(AppCorner.ALERT))),
    ) {
        Text(
            title,
            Modifier.fillMaxWidth().padding(start = TEXT_INSET.dp, end = TEXT_INSET.dp, top = 21.dp),
            textAlign = if (hasFields) TextAlign.Center else TextAlign.Start,
            style = (if (hasFields) AppTextStyle.Body.of() else AppTextStyle.Headline.of()).copy(color = scheme.onSurface),
        )
        if (content != null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(start = contentInset.dp, end = contentInset.dp, top = if (hasFields) 16.dp else 6.dp, bottom = 20.dp),
            ) {
                androidx.compose.material3.ProvideTextStyle(
                    AppTextStyle.Subheadline.of().copy(color = scheme.onSurfaceVariant.copy(alpha = if (scheme.dark) 1f else 0.85f)),
                ) { content() }
            }
        } else {
            Spacer(Modifier.height(20.dp))
        }
        if (actions.isNotEmpty()) AlertActions(actions, destructive, Modifier.padding(start = ACTION_INSET.dp, end = ACTION_INSET.dp, bottom = ACTION_INSET.dp))
    }
    }
}

class DialogAction(val label: String, val onClick: (() -> Unit)?, val loading: Boolean = false)

/**
 * Two actions side by side when both labels fit half the width; any other
 * count stacked in order with the first one at the bottom.
 */
@Composable
fun AlertActions(actions: List<DialogAction>, destructive: Boolean = false, modifier: Modifier = Modifier) {
    val sideBySide = actions.size == 2 && actions.all { it.label.length <= 14 }
    if (sideBySide) {
        Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ACTION_GAP.dp)) {
            actions.forEachIndexed { index, action ->
                AlertAction(action, isDefault = !destructive && index == actions.lastIndex, destructive = destructive && index == actions.lastIndex, Modifier.weight(1f))
            }
        }
    } else {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ACTION_GAP.dp)) {
            for (index in actions.indices.reversed()) {
                val action = actions[index]
                AlertAction(action, isDefault = !destructive && index == actions.lastIndex, destructive = destructive && index == actions.lastIndex, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun AlertAction(action: DialogAction, isDefault: Boolean, destructive: Boolean, modifier: Modifier) {
    val scheme = BoxTheme.scheme
    val fill = SurfaceLevel.floatingOf(scheme).next.colorOf(scheme)
    val foreground = when {
        isDefault -> scheme.onPrimary
        destructive -> scheme.destructive
        else -> scheme.onSurface
    }
    val background = if (isDefault) scheme.primary else fill
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val share = when {
        isDefault && scheme.dark -> 0.5f
        !isDefault && scheme.dark -> 1f
        else -> 0.4f
    }
    val enabled = action.onClick != null && !action.loading
    Box(
        modifier
            .height(ACTION_HEIGHT.dp)
            .background(if (!enabled) fill else if (pressed) background.copy(alpha = background.alpha * share) else background, AppShape.full)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled) { action.onClick?.invoke() }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (action.loading) {
            CommonCircleLoading(size = 24.dp, color = foreground)
        } else {
            Text(
                action.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = (if (isDefault) AppTextStyle.Body.of(true) else AppTextStyle.Body.of().copy(fontWeight = FontWeight.W500))
                    .copy(color = if (enabled) foreground else scheme.onSurface.copy(alpha = .38f)),
            )
        }
    }
}

@Composable
private fun InputDialog(request: DialogRequest.Input) {
    val scheme = BoxTheme.scheme
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
        hasFields = true,
        actions = listOf(
            if (reset != null && value.text != reset) {
                DialogAction(tr("Reset"), { request.result.complete(reset); Unit })
            } else {
                DialogAction(tr("Cancel"), { request.result.complete(null); Unit })
            },
            DialogAction(tr("Confirm"), submit),
        ),
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = ACTION_HEIGHT.dp)
                    .background(SurfaceLevel.floatingOf(scheme).next.colorOf(scheme), AppShape.all(ACTION_HEIGHT / 2))
                    .padding(horizontal = (TEXT_INSET - ACTION_INSET - 4).dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = {
                        value = it
                        touched = true
                    },
                    modifier = Modifier.weight(1f).focusRequester(focus),
                    singleLine = true,
                    textStyle = AppTextStyle.Body.of().copy(color = scheme.onSurface),
                    cursorBrush = SolidColor(scheme.primary),
                    keyboardOptions = KeyboardOptions(keyboardType = request.keyboardType),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { submit() }),
                )
                if (request.suffixText != null) {
                    Text(request.suffixText, style = AppTextStyle.Body.of().copy(color = scheme.onSurfaceVariant))
                }
            }
            if (error != null) {
                Text(error, Modifier.padding(start = 12.dp, top = 4.dp), style = AppTextStyle.Caption1.of().copy(color = scheme.error))
            }
        }
    }
}

/** Launches [block] from a click handler with the dialog host at hand. */
@Composable
fun rememberDialogScope(): Pair<kotlinx.coroutines.CoroutineScope, DialogHost?> = rememberCoroutineScope() to LocalDialogs.current

