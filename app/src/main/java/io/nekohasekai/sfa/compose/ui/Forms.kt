package io.nekohasekai.sfa.compose.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.compose.theme.AppShape
import io.nekohasekai.sfa.compose.theme.AppTextStyle
import io.nekohasekai.sfa.compose.theme.BoxTheme
import kotlinx.coroutines.launch

/** search_field.dart's SearchField: the glyph's center 22 dp in, the clear button's 24.5. */
@Composable
fun SearchField(
    text: String,
    onChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = TonalButtonSize.Bar.button.dp,
    fill: Color? = null,
    hint: String = tr("Search"),
    focusRequester: FocusRequester? = null,
    onFocusChanged: ((Boolean) -> Unit)? = null,
    onSubmitted: (() -> Unit)? = null,
) {
    val scheme = BoxTheme.scheme
    Row(
        modifier
            .fillMaxWidth()
            .height(height)
            .then(if (fill != null) Modifier.background(fill, AppShape.full) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(41.dp).padding(start = 12.dp), contentAlignment = Alignment.CenterStart) {
            GlyphIcon(AppGlyphs.search, size = 20.dp, color = scheme.onSurfaceVariant)
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (text.isEmpty()) {
                Text(hint, maxLines = 1, overflow = TextOverflow.Ellipsis, style = AppTextStyle.Body.of().copy(color = scheme.onSurfaceVariant))
            }
            BasicTextField(
                value = text,
                onValueChange = { onChanged(it.take(256)) },
                singleLine = true,
                textStyle = AppTextStyle.Body.of().copy(color = scheme.onSurface),
                cursorBrush = SolidColor(scheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmitted?.invoke() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                    .onFocusChanged { onFocusChanged?.invoke(it.isFocused) },
            )
        }
        if (text.isNotEmpty()) {
            Box(
                Modifier
                    .width(49.dp)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onChanged("") },
                contentAlignment = Alignment.Center,
            ) {
                GlyphIcon(AppGlyphs.clearField, size = 24.dp, fill = 1f, color = scheme.onSurface)
            }
        } else {
            Spacer(Modifier.width(12.dp))
        }
    }
}

/** ListItem.open: a row opening [page] in the current route stack (OpenContainer on a phone). */
@Composable
fun ListItemOpen(
    title: String,
    position: ItemPosition?,
    leading: Glyph? = null,
    subtitle: String? = null,
    onClosed: ((Any?) -> Unit)? = null,
    page: @Composable () -> Unit,
) {
    val navigator = LocalBoxNavigator.current
    ListItem(
        title = { Text(title) },
        subtitle = subtitle?.let { { Text(it) } },
        leading = leading?.let { { GlyphIcon(it) } },
        trailing = { GlyphIcon(AppGlyphs.chevronForward) },
        position = position,
        onClick = { navigator?.pushPage(page, onClosed) },
    )
}

/** A row for a feature the running core does not have: greyed out, with the reason, not tappable. */
@Composable
fun ListItemUnavailable(title: String, position: ItemPosition?, leading: Glyph? = null, reason: String) {
    ListItem(
        title = { Text(title) },
        modifier = Modifier.alpha(0.45f),
        subtitle = { Text(reason) },
        leading = leading?.let { { GlyphIcon(it) } },
        position = position,
    )
}

/** ListItem.toggle: the whole row flips the switch. */
@Composable
fun ListItemToggle(title: String, value: Boolean, position: ItemPosition?, subtitle: String? = null, onChanged: ((Boolean) -> Unit)?) {
    ListItem(
        title = { Text(title) },
        subtitle = subtitle?.let { { Text(it) } },
        trailing = { CommonSwitch(value, onChanged) },
        position = position,
        onClick = onChanged?.let { { it(!value) } },
    )
}

/** ListItem.options: an iOS pop-up row; a tap anywhere opens the menu out of its value. */
@Composable
fun <T> ListItemOptions(
    title: String,
    options: List<T>,
    value: T,
    position: ItemPosition?,
    textOf: (T) -> String,
    onChanged: (T) -> Unit,
) {
    var opener by remember { mutableStateOf<(() -> Unit)?>(null) }
    val scheme = BoxTheme.scheme
    ListItem(
        title = { Text(title) },
        position = position,
        onClick = { opener?.invoke() },
        trailing = {
            CommonPopupBox(options.map { option -> PopupMenuItem(textOf(option), checked = option == value, onPressed = { onChanged(option) }) }) { open ->
                opener = open
                Row(
                    Modifier
                        .clip(AppShape.full)
                        .clickable(onClick = open)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        textOf(value),
                        Modifier.widthIn(max = 160.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.End,
                        style = ListStyles.title.copy(color = scheme.onSurfaceVariant),
                    )
                    GlyphIcon(AppGlyphs.picker, color = scheme.onSurfaceVariant)
                }
            }
        },
    )
}

/** ListItem.input: a tap opens an InputDialog, whose text (or null) reaches [onChanged]. */
@Composable
fun ListItemInput(
    title: String,
    value: String,
    position: ItemPosition?,
    dialogTitle: String = title,
    keyboardType: KeyboardType = KeyboardType.Text,
    validator: ((String) -> String?)? = null,
    onChanged: (String?) -> Unit,
) {
    val dialogs = LocalDialogs.current
    val scope = rememberCoroutineScope()
    ListItem(
        title = { Text(title) },
        trailing = { Text(value, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        position = position,
        onClick = {
            scope.launch { onChanged(dialogs?.input(dialogTitle, value, keyboardType, validator = validator)) }
        },
    )
}

/** widgets.dart's DetailRows: titled, selectable values on one card. */
@Composable
fun DetailRows(values: List<Pair<String, Any?>>) {
    Column {
        values.forEachIndexed { index, (key, value) ->
            ListItem(
                title = { Text(tr(key)) },
                subtitle = { SelectionContainer { Text(value?.toString() ?: "—") } },
                position = ItemPosition.of(index, values.size),
            )
        }
    }
}

/** components/form.dart's FormPage: a confirmation in the bar and grouped rows. */
@Composable
fun FormPage(
    title: String,
    onSave: () -> Unit,
    error: String? = null,
    saving: Boolean = false,
    actions: List<IconAction> = emptyList(),
    content: @Composable () -> Unit,
) {
    CommonScaffold(
        title = tr(title),
        iconActions = actions,
        confirmationAction = IconAction(AppGlyphs.check, tr("Save"), if (saving) null else onSave, loading = saving),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(PaddingValues(start = 16.dp, end = 16.dp, top = sectionsTopPadding(), bottom = 20.dp + bottomInset())),
        ) {
            if (error != null) {
                Text(error, Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp), style = ListStyles.caption.copy(color = BoxTheme.scheme.error))
            }
            content()
        }
    }
}

/** components/form.dart's FormRow: the title takes at most half the row beside a trailing control. */
@Composable
fun FormRow(
    title: String,
    position: ItemPosition?,
    invalid: Boolean = false,
    onPressed: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    ListItem(
        title = {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val rowWidth = maxWidth
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        title,
                        Modifier.then(if (trailing != null) Modifier.widthIn(max = (rowWidth - 16.dp) * 0.5f) else Modifier),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (trailing != null) {
                        Box(Modifier.weight(1f).height(56.dp - 20.dp + 22.dp), contentAlignment = Alignment.CenterEnd) {
                            androidx.compose.runtime.CompositionLocalProvider(LocalContentColor provides BoxTheme.scheme.onSurface.copy(alpha = .6f)) { trailing() }
                        }
                    }
                }
            }
        },
        minVerticalPadding = if (trailing == null) 10.dp else 0.dp,
        position = position,
        invalid = invalid,
        onClick = onPressed,
    )
}

/** components/form.dart's FormRowTextField: a bare field set against the row's end. */
@Composable
fun FormRowTextField(
    value: String,
    onChanged: (String) -> Unit,
    hint: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Default,
    autocorrect: Boolean = true,
    secret: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val scheme = BoxTheme.scheme
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        if (value.isEmpty() && hint != null) {
            Text(hint, Modifier.padding(end = 3.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End, style = AppTextStyle.Body.of().copy(color = scheme.onSurfaceVariant))
        }
        BasicTextField(
            value = value,
            onValueChange = onChanged,
            singleLine = true,
            textStyle = AppTextStyle.Body.of().copy(color = scheme.onSurface, textAlign = TextAlign.End),
            cursorBrush = SolidColor(scheme.primary),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction, autoCorrectEnabled = autocorrect),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** EmojiText: plain text; the platform draws emoji in place. */
@Composable
fun EmojiText(text: String, style: androidx.compose.ui.text.TextStyle, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    Text(text, modifier, maxLines = maxLines, overflow = TextOverflow.Ellipsis, style = style)
}

