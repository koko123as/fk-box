package io.nekohasekai.sfa.compose.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.compose.theme.AppTextStyle
import io.nekohasekai.sfa.compose.theme.forClip
import io.nekohasekai.sfa.compose.theme.BoxTheme

/**
 * The app's message banner (replaces the stock dark Material snackbar): a
 * rounded card on the surface colour like the dashboard cards, a tinted badge
 * with the glyph, the text, and a close button.
 */
@Composable
fun MessageBanner(text: String, error: Boolean, onDismiss: () -> Unit) {
    val scheme = BoxTheme.scheme
    val accent = if (error) scheme.error else scheme.primary
    val badge = if (error) scheme.errorContainer else scheme.primaryContainer
    val shape = RoundedCornerShape(24.dp)
    Row(
        Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .shadow(12.dp, shape, ambientColor = scheme.shadow, spotColor = scheme.shadow)
            .background(scheme.surfaceContainerHigh, shape)
            .clip(shape.forClip())
            .padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(badge), contentAlignment = Alignment.Center) {
            GlyphIcon(if (error) AppGlyphs.error else AppGlyphs.info, size = 20.dp, color = accent)
        }
        Text(
            text,
            Modifier.weight(1f),
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            style = AppTextStyle.Subheadline.of().copy(color = scheme.onSurface),
        )
        Box(
            Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            GlyphIcon(AppGlyphs.close, size = 18.dp, color = scheme.onSurfaceVariant)
        }
    }
}
