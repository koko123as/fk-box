package io.nekohasekai.sfa.compose.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.compose.BoxStrings
import io.nekohasekai.sfa.compose.theme.AppShape
import io.nekohasekai.sfa.compose.theme.AppTextStyle
import io.nekohasekai.sfa.compose.theme.BoxTheme
import io.nekohasekai.sfa.compose.theme.LocalSurfaceLevel
import io.nekohasekai.sfa.compose.theme.SurfaceLevel
import io.nekohasekai.sfa.compose.theme.tabular
import kotlin.math.max

// ---------------------------------------------------------------------------
// Localization
// ---------------------------------------------------------------------------

@Composable
@ReadOnlyComposable
fun tr(value: String): String {
    val locales = LocalConfiguration.current.locales
    val zh = !locales.isEmpty && locales[0].language == "zh"
    return if (zh) BoxStrings.zh[value] ?: value else value
}

@Composable
@ReadOnlyComposable
fun isChinese(): Boolean {
    val locales = LocalConfiguration.current.locales
    return !locales.isEmpty && locales[0].language == "zh"
}

// ---------------------------------------------------------------------------
// List text styles
// ---------------------------------------------------------------------------

object ListStyles {
    val title: TextStyle @Composable get() = AppTextStyle.Body.of().copy(color = BoxTheme.scheme.onSurface)
    val subtitle: TextStyle @Composable get() = AppTextStyle.Subheadline.of().copy(color = BoxTheme.scheme.onSurfaceVariant)
    val caption: TextStyle @Composable get() = AppTextStyle.Footnote.of().copy(color = BoxTheme.scheme.onSurfaceVariant)
}

// ---------------------------------------------------------------------------
// BoxPanel and PanelTitle: a dashboard card
// ---------------------------------------------------------------------------

/** A flat Material 3 card for a dashboard panel; fills its grid cell outside the phone layout. */
@Composable
fun BoxPanel(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: PaddingValues = PaddingValues(16.dp),
    content: @Composable () -> Unit,
) {
    val scheme = BoxTheme.scheme
    val density = LocalDashboardDensity.current
    val colors = CardDefaults.cardColors(containerColor = SurfaceLevel.Card.colorOf(scheme), contentColor = scheme.onSurface)
    val cardModifier = modifier
        .fillMaxWidth()
        // The desktop grid gives every card a fixed row height.
        .then(if (density.isDesktop) Modifier.fillMaxHeight() else Modifier)
    val body: @Composable () -> Unit = {
        Box(Modifier.fillMaxWidth().padding(padding)) {
            CompositionLocalProvider(LocalSurfaceLevel provides SurfaceLevel.Card) { content() }
        }
    }
    if (onClick != null) {
        Card(onClick = onClick, modifier = cardModifier, shape = AppShape.card, colors = colors) { body() }
    } else {
        Card(modifier = cardModifier, shape = AppShape.card, colors = colors) { body() }
    }
}

@Composable
fun PanelTitle(title: String, icon: ImageVector, trailing: (@Composable () -> Unit)? = null) {
    val scale = androidx.compose.ui.platform.LocalDensity.current.fontScale
    Row(Modifier.fillMaxWidth().height((26 * max(1f, scale)).dp), verticalAlignment = Alignment.CenterVertically) {
        AppIcon(icon, size = 18.dp, color = BoxTheme.scheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(tr(title), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = AppTextStyle.Subheadline.of(true).copy(color = BoxTheme.scheme.onSurface))
        if (trailing != null) trailing()
    }
}

// ---------------------------------------------------------------------------
// DashboardChip: a small status label
// ---------------------------------------------------------------------------

enum class ChipTone { Idle, Ok, Warn }

@Composable
fun DashboardChip(label: String, modifier: Modifier = Modifier, tone: ChipTone = ChipTone.Idle, dot: Boolean = false, large: Boolean = false) {
    val scheme = BoxTheme.scheme
    val (background, foreground) = when (tone) {
        ChipTone.Idle -> SurfaceLevel.Block.colorOf(scheme) to scheme.onSurfaceVariant
        ChipTone.Ok -> scheme.successContainer to scheme.onSuccessContainer
        ChipTone.Warn -> scheme.warningContainer to scheme.onWarningContainer
    }
    Row(
        modifier.height(if (large) 28.dp else 24.dp).background(background, AppShape.full).padding(horizontal = if (large) 12.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (dot) Box(Modifier.size(6.dp).background(foreground, CircleShape))
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = AppTextStyle.Caption1.of(true).tabular.copy(color = foreground))
    }
}

// ---------------------------------------------------------------------------
// Switch
// ---------------------------------------------------------------------------

/** The Material 3 switch's track size, for layouts that reserve its room. */
const val SWITCH_TRACK_WIDTH = 52f
const val SWITCH_TRACK_HEIGHT = 32f

/** The stock Material 3 switch; disabled when [onChanged] is null. */
@Composable
fun CommonSwitch(value: Boolean, onChanged: ((Boolean) -> Unit)?, modifier: Modifier = Modifier) {
    Switch(checked = value, onCheckedChange = onChanged, modifier = modifier, enabled = onChanged != null)
}

// ---------------------------------------------------------------------------
// Placeholder while a value loads
// ---------------------------------------------------------------------------

@Composable
fun SkeletonPill(width: Dp?, height: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .then(if (width == null) Modifier.fillMaxWidth() else Modifier.width(width))
            .height(height)
            .background(BoxTheme.scheme.surfaceContainerHighest, AppShape.full),
    )
}

@Composable
fun SkeletonText(width: Dp, style: TextStyle) {
    Box(Modifier.width(width), contentAlignment = Alignment.CenterStart) {
        Text(" ", style = style)
        SkeletonPill(width, style.fontSize.value.dp)
    }
}

// ---------------------------------------------------------------------------
// Lists: grouped rows on a card
// ---------------------------------------------------------------------------

enum class ItemPosition {
    Start,
    Middle,
    End,
    StartAndEnd,
    ;

    companion object {
        fun of(index: Int, count: Int) = when {
            count == 1 -> StartAndEnd
            index == 0 -> Start
            index == count - 1 -> End
            else -> Middle
        }
    }
}

@Composable
fun ListItem(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: (@Composable () -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    position: ItemPosition? = null,
    invalid: Boolean = false,
    minVerticalPadding: Dp = 10.dp,
    startPadding: Dp = 16.dp,
    onClick: (() -> Unit)? = null,
) {
    val scheme = BoxTheme.scheme
    val start = position == ItemPosition.Start || position == ItemPosition.StartAndEnd
    val end = position == ItemPosition.End || position == ItemPosition.StartAndEnd
    val shape = AppShape.vertical(start, end)
    val sorting = LocalSorting.current
    val level = LocalSurfaceLevel.current.next
    val background = if (invalid) scheme.errorContainer else level.colorOf(scheme)
    Column(
        modifier
            .fillMaxWidth()
            .then(if (position != null) Modifier.clip(shape).background(background, shape) else Modifier)
            .then(if (onClick != null && !sorting) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = startPadding, end = 16.dp, top = minVerticalPadding, bottom = minVerticalPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                CompositionLocalProvider(LocalContentColor provides scheme.onSurfaceVariant) { leading() }
                Spacer(Modifier.width(16.dp))
            }
            Column(Modifier.weight(1f)) {
                CompositionLocalProvider(LocalSurfaceLevel provides level) {
                    ProvideTextStyle(ListStyles.title) { title() }
                    if (subtitle != null) ProvideTextStyle(ListStyles.subtitle) { subtitle() }
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(16.dp))
                CompositionLocalProvider(LocalContentColor provides scheme.onSurfaceVariant, LocalSurfaceLevel provides level) {
                    ProvideTextStyle(ListStyles.subtitle) { trailing() }
                }
            }
        }
        if (position != null && !end) {
            HorizontalDivider(
                Modifier.padding(start = if (leading != null) 56.dp else 16.dp, end = 16.dp),
                color = if (invalid) scheme.error else scheme.outlineVariant,
            )
        }
    }
}

/** A section: an optional header, the rows grouped on one card, an optional footer. */
@Composable
fun Section(title: String? = null, count: Int, footer: String? = null, item: @Composable (index: Int, position: ItemPosition) -> Unit) {
    if (count <= 0) return
    if (title != null) {
        Text(
            title,
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
            style = AppTextStyle.Subheadline.of(true).copy(color = BoxTheme.scheme.primary),
        )
    } else {
        Spacer(Modifier.height(24.dp))
    }
    Column(Modifier.fillMaxWidth()) {
        for (index in 0 until count) item(index, ItemPosition.of(index, count))
    }
    if (footer != null) ListFooter(footer)
}

/** [Section] over a list of rows, each told its position. */
@Composable
fun Section(title: String? = null, footer: String? = null, rows: List<@Composable (ItemPosition) -> Unit>) {
    Section(title, rows.size, footer) { index, position -> rows[index](position) }
}

@Composable
fun ListFooter(text: String) {
    Text(text, Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp), style = ListStyles.caption)
}

// ---------------------------------------------------------------------------
// Empty and error states
// ---------------------------------------------------------------------------

@Composable
fun EmptyState(title: String, message: String? = null, icon: ImageVector = AppIcons.info, selectable: Boolean = false, action: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 64.dp, horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        AppIcon(icon, size = 48.dp, color = BoxTheme.scheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Text(tr(title), style = AppTextStyle.Title3.of().copy(color = BoxTheme.scheme.onSurface), textAlign = TextAlign.Center)
        if (message != null) {
            val text = @Composable { Text(tr(message), Modifier.padding(vertical = 12.dp), textAlign = TextAlign.Center, style = ListStyles.subtitle) }
            if (selectable) SelectionContainer { text() } else text()
        }
        if (action != null) Box(Modifier.padding(top = 16.dp)) { action() }
    }
}

@Composable
fun FilledPillButton(text: String, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    Button(onClick = { onClick?.invoke() }, enabled = onClick != null, modifier = modifier) { Text(text) }
}

@Composable
fun TextPillButton(text: String, onClick: (() -> Unit)?, modifier: Modifier = Modifier, color: Color? = null) {
    TextButton(onClick = { onClick?.invoke() }, enabled = onClick != null, modifier = modifier) {
        Text(text, color = color ?: Color.Unspecified)
    }
}

@Composable
fun ErrorState(error: String, retry: (() -> Unit)? = null) {
    if (retry == null) {
        EmptyState("Error", message = error, selectable = true)
    } else {
        EmptyState("Error", message = error, selectable = true, action = { FilledPillButton(tr("Retry"), retry) })
    }
}

fun Modifier.semanticsLabel(label: String): Modifier = this.then(
    Modifier.semantics(mergeDescendants = true) { contentDescription = label },
)

/** An icon on a filled circle, marking a card or row. */
@Composable
fun IconBadge(icon: ImageVector, size: Dp, container: Color, color: Color) {
    Box(Modifier.size(size).background(container, CircleShape), contentAlignment = Alignment.Center) {
        AppIcon(icon, size = size * 0.5f, color = color)
    }
}
