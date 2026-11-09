package io.nekohasekai.sfa.compose.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.compose.theme.AppTextStyle
import io.nekohasekai.sfa.compose.theme.BoxTheme

/** What an empty status is about, which picks its icon. */
enum class NullStatusIllustration(val icon: ImageVector) {
    Data(AppIcons.info),
    Logs(AppIcons.logs),
    Proxies(AppIcons.proxies),
    Profile(AppIcons.profiles),
    Connections(AppIcons.connections),
    Apps(AppIcons.appsList),
    Permission(AppIcons.lock),
    Camera(AppIcons.camera),
    Search(AppIcons.search),
}

/** An empty status: an icon, a label, a description and an action. */
@Composable
fun NullStatus(
    label: String? = null,
    description: String? = null,
    illustration: NullStatusIllustration = NullStatusIllustration.Data,
    action: (@Composable () -> Unit)? = null,
) {
    val scheme = BoxTheme.scheme
    Box(Modifier.fillMaxSize(), contentAlignment = BiasAlignment(0f, -0.2f)) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp, vertical = 24.dp)
                .widthIn(max = 320.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AppIcon(illustration.icon, size = 48.dp, color = scheme.onSurfaceVariant)
            if (label != null) {
                Spacer(Modifier.height(16.dp))
                Text(label, textAlign = TextAlign.Center, style = AppTextStyle.Headline.of().copy(color = scheme.onSurface))
            }
            if (description != null) {
                Spacer(Modifier.height(8.dp))
                Text(description, textAlign = TextAlign.Center, style = AppTextStyle.Subheadline.of().copy(color = scheme.onSurfaceVariant))
            }
            if (action != null) {
                Spacer(Modifier.height(24.dp))
                action()
            }
        }
    }
}

/** The content, the empty status, or no search results. */
@Composable
fun NullStatusSwitcher(
    isEmpty: Boolean,
    isSearching: Boolean = false,
    nullStatus: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    when {
        isEmpty && isSearching -> NullStatus(label = tr("No results"), illustration = NullStatusIllustration.Search)
        isEmpty -> nullStatus()
        else -> content()
    }
}
