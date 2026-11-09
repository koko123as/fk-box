package io.nekohasekai.sfa.compose.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.compose.theme.AppShape
import io.nekohasekai.sfa.compose.theme.BoxTheme
import io.nekohasekai.sfa.compose.theme.LocalSurfaceLevel
import io.nekohasekai.sfa.compose.theme.SurfaceLevel

/** The level a card on the current surface takes. */
val raisedSurfaceLevel: SurfaceLevel
    @Composable get() = LocalSurfaceLevel.current.next

/**
 * A flat Material 3 card on the raised surface, its content one level up.
 * A selected card takes the primary container when [fillsSelection].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CommonCard(
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    fillsSelection: Boolean = false,
    isError: Boolean = false,
    inert: Boolean = false,
    shape: Shape = AppShape.card,
    padding: PaddingValues = PaddingValues(0.dp),
    onPressed: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = LocalContextMenu.current,
    contentAlignment: Alignment = Alignment.CenterStart,
    content: @Composable BoxScope.() -> Unit,
) {
    val scheme = BoxTheme.scheme
    val level = raisedSurfaceLevel
    val filled = isSelected && fillsSelection
    val background = when {
        isError -> scheme.errorContainer
        filled -> scheme.primaryContainer
        else -> level.colorOf(scheme)
    }
    val foreground = when {
        isError -> scheme.onErrorContainer
        filled -> scheme.onPrimaryContainer
        else -> scheme.onSurfaceVariant
    }
    val clickable = !inert && (onPressed != null || onLongPress != null)
    Card(
        modifier
            .clip(shape)
            .then(
                if (clickable) {
                    Modifier.combinedClickable(role = Role.Button, onLongClick = onLongPress, onClick = { onPressed?.invoke() })
                } else {
                    Modifier
                },
            ),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = background, contentColor = foreground),
    ) {
        Box(Modifier.padding(padding), contentAlignment = contentAlignment) {
            CompositionLocalProvider(LocalSurfaceLevel provides level, LocalContentColor provides foreground) { content() }
        }
    }
}

/** A check on a primary circle, marking the selected item; a pin for a preferred one. */
@Composable
fun SelectionBadge(size: Dp = 22.dp, preferred: Boolean = false, inUse: Boolean = false) {
    val scheme = BoxTheme.scheme
    val tonal = preferred && !inUse
    Surface(Modifier.size(size), shape = CircleShape, color = if (tonal) scheme.secondaryContainer else scheme.primary) {
        Box(contentAlignment = Alignment.Center) {
            AppIcon(if (preferred) AppIcons.pin else AppIcons.check, size = size - 6.dp, color = if (tonal) scheme.onSecondaryContainer else scheme.onPrimary)
        }
    }
}

@Composable
fun SelectionCheck() {
    AppIcon(AppIcons.check, size = 24.dp, color = BoxTheme.scheme.primary)
}

/** The Material 3 indeterminate progress ring. */
@Composable
fun CommonCircleLoading(size: Dp = 24.dp, color: Color = BoxTheme.scheme.primary, modifier: Modifier = Modifier) {
    CircularProgressIndicator(modifier.size(size), color = color, strokeWidth = if (size < 20.dp) 2.dp else 3.dp)
}

/** Button heights and icon sizes. */
enum class TonalButtonSize(val button: Float, val icon: Float) {
    Bar(40f, 24f),
    Section(36f, 20f),
    Compact(32f, 20f),
}

/** A Material 3 filled tonal button with an optional icon. */
@Composable
fun TonalButton(label: String, onPressed: (() -> Unit)?, icon: ImageVector? = null, size: TonalButtonSize = TonalButtonSize.Section, modifier: Modifier = Modifier) {
    FilledTonalButton(
        onClick = { onPressed?.invoke() },
        enabled = onPressed != null,
        modifier = modifier.heightIn(min = size.button.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) {
        if (icon != null) {
            AppIcon(icon, size = 18.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * An icon button for [action]: filled when selected, tonal on its own, plain
 * when [grouped] with others; a progress ring while loading.
 */
@Composable
fun TonalIconButton(
    action: IconAction,
    size: TonalButtonSize = TonalButtonSize.Bar,
    grouped: Boolean = false,
    icon: (@Composable () -> Unit)? = null,
) {
    val enabled = action.onClick != null && !action.loading
    val onClick = { action.onClick?.invoke(); Unit }
    val modifier = Modifier.size(size.button.dp).semanticsLabel(action.tooltip)
    val content: @Composable () -> Unit = {
        when {
            action.loading -> CommonCircleLoading(size = (size.icon - 4).dp)
            icon != null -> icon()
            else -> AppIcon(action.icon, size = size.icon.dp)
        }
    }
    when {
        action.selected -> FilledIconButton(onClick, modifier, enabled) { content() }
        grouped -> IconButton(onClick, modifier, enabled) { content() }
        else -> FilledTonalIconButton(onClick, modifier, enabled) { content() }
    }
}

/** A row of grouped icon buttons. */
@Composable
fun TonalButtonGroup(content: @Composable RowScope.() -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(0.dp), content = content)
}

/** Expand more / expand less. */
@Composable
fun CommonExpandIcon(expand: Boolean, size: Dp = 24.dp, color: Color = LocalContentColor.current) {
    AppIcon(if (expand) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, size = size, color = color)
}
