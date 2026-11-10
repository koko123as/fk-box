package io.nekohasekai.sfa.compose.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.compose.theme.AppShape
import io.nekohasekai.sfa.compose.theme.BoxTheme
import io.nekohasekai.sfa.compose.ui.AppIcon
import io.nekohasekai.sfa.compose.ui.AppIcons
import io.nekohasekai.sfa.compose.ui.semanticsLabel
import io.nekohasekai.sfa.compose.ui.tr

/** The service button: a round filled button showing start, stop, or that a remote session is in use. */
@Composable
fun StartToggle(size: Dp, enabled: Boolean, running: Boolean, suspended: Boolean, onTap: () -> Unit) {
    val tooltip = tr(if (suspended) "Remote" else if (running) "Stop" else "Start")
    FilledIconButton(
        onClick = onTap,
        enabled = enabled,
        modifier = Modifier.size(size).semanticsLabel(tooltip),
        shape = CircleShape,
        colors = IconButtonDefaults.filledIconButtonColors(),
    ) {
        AppIcon(
            when {
                suspended -> AppIcons.wifiOff
                running -> AppIcons.pause
                else -> AppIcons.start
            },
            size = size * 0.4f,
        )
    }
}

/** A row of connected buttons 2 dp apart. */
@Composable
fun ConnectedGroup(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically, content = content)
}

/** One of a row of connected buttons; the selected one takes the primary colour. */
@Composable
fun ConnectedButton(
    selected: Boolean,
    background: Color,
    label: String,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (Color) -> Unit,
) {
    val scheme = BoxTheme.scheme
    Box(
        modifier
            .clip(AppShape.card)
            .background(if (selected) scheme.primary else background)
            .clickable(onClick = onTap)
            .semanticsLabel(label),
        contentAlignment = Alignment.Center,
    ) { content(if (selected) scheme.onPrimary else scheme.onSurfaceVariant) }
}
