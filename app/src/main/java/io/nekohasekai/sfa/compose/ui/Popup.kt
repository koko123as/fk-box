package io.nekohasekai.sfa.compose.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.compose.theme.AppTextStyle
import io.nekohasekai.sfa.compose.theme.BoxTheme

/** Starts a new group of menu items, with an optional title. */
class PopupMenuSection(val title: String? = null)

class PopupMenuItem(
    val label: String,
    val icon: ImageVector? = null,
    val subtitle: String? = null,
    val onPressed: (() -> Unit)? = null,
    val danger: Boolean = false,
    val checked: Boolean? = null,
    val section: PopupMenuSection? = null,
    val subItems: List<PopupMenuItem> = emptyList(),
)

/** A Material 3 dropdown menu anchored to [target], which receives the opener. */
@Composable
fun CommonPopupBox(
    items: List<PopupMenuItem>,
    modifier: Modifier = Modifier,
    target: @Composable (open: () -> Unit) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        target { if (items.isNotEmpty()) expanded = true }
        MenuItems(items, expanded, onDismiss = { expanded = false })
    }
}

/** A long press (or a secondary click) opens [items] at the point that was pressed. */
@Composable
fun ContextMenuRegion(items: List<PopupMenuItem>?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    var height by remember { mutableStateOf(0) }
    var lastDown by remember { mutableStateOf(Offset.Zero) }
    var menuAt by remember { mutableStateOf<Offset?>(null) }
    val enabled = !items.isNullOrEmpty() && !LocalSorting.current
    val open: (() -> Unit)? = if (!enabled) {
        null
    } else {
        {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            menuAt = lastDown
        }
    }
    Box(
        modifier
            .onSizeChanged { height = it.height }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.firstOrNull()?.let { if (it.pressed && !it.previousPressed) lastDown = it.position }
                    }
                }
            },
    ) {
        CompositionLocalProvider(LocalContextMenu provides open) { content() }
        val at = menuAt
        if (items != null) {
            val offset = with(density) { DpOffset((at?.x ?: 0f).toDp(), ((at?.y ?: 0f) - height).toDp()) }
            MenuItems(items, at != null, onDismiss = { menuAt = null }, offset = offset)
        }
    }
}

/** The opener of the enclosing ContextMenuRegion, which a CommonCard takes as its long press. */
val LocalContextMenu = staticCompositionLocalOf<(() -> Unit)?> { null }

/** A menu stays shut while the list sorts. */
val LocalSorting = staticCompositionLocalOf { false }

/** The trailing more button of a row, opening [items]. */
@Composable
fun BoxMenu(items: List<PopupMenuItem>) {
    CommonPopupBox(items) { open -> AppIconButton(AppIcons.more, tr("More"), open) }
}

/** [items] in a DropdownMenu; an item with sub items shows them in the same menu. */
@Composable
private fun MenuItems(items: List<PopupMenuItem>, expanded: Boolean, onDismiss: () -> Unit, offset: DpOffset = DpOffset(0.dp, 0.dp)) {
    var opened by remember { mutableStateOf<PopupMenuItem?>(null) }
    val close = {
        opened = null
        onDismiss()
    }
    DropdownMenu(expanded = expanded, onDismissRequest = close, offset = offset) {
        val parent = opened
        if (parent != null) {
            DropdownMenuItem(
                text = { Text(parent.label) },
                leadingIcon = { AppIcon(AppIcons.back, size = 20.dp) },
                onClick = { opened = null },
            )
            HorizontalDivider()
        }
        val shown = parent?.subItems ?: items
        shown.forEachIndexed { index, item ->
            val section = item.section
            if (section != null && index > 0) HorizontalDivider()
            if (section?.title != null) {
                Text(
                    section.title,
                    Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
                    style = AppTextStyle.Caption1.of(true).copy(color = BoxTheme.scheme.primary),
                )
            }
            val color = if (item.danger) BoxTheme.scheme.error else BoxTheme.scheme.onSurface
            DropdownMenuItem(
                text = {
                    if (item.subtitle == null) {
                        Text(item.label, color = color)
                    } else {
                        androidx.compose.foundation.layout.Column {
                            Text(item.label, color = color)
                            Text(item.subtitle, style = AppTextStyle.Footnote.of().copy(color = BoxTheme.scheme.onSurfaceVariant))
                        }
                    }
                },
                leadingIcon = item.icon?.let { icon -> { AppIcon(icon, size = 20.dp, color = color) } },
                trailingIcon = when {
                    item.subItems.isNotEmpty() -> ({ AppIcon(AppIcons.chevronForward, size = 20.dp) })
                    item.checked == true -> ({ AppIcon(AppIcons.check, size = 20.dp, color = BoxTheme.scheme.primary) })
                    else -> null
                },
                enabled = item.onPressed != null || item.subItems.isNotEmpty(),
                colors = MenuDefaults.itemColors(),
                onClick = {
                    if (item.subItems.isNotEmpty()) {
                        opened = item
                    } else {
                        close()
                        item.onPressed?.invoke()
                    }
                },
            )
        }
    }
}
