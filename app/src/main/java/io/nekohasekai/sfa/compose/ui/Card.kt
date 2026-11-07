package io.nekohasekai.sfa.compose.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.compose.theme.AppCorner
import io.nekohasekai.sfa.compose.theme.AppShape
import io.nekohasekai.sfa.compose.theme.AppTextStyle
import io.nekohasekai.sfa.compose.theme.BoxTheme
import io.nekohasekai.sfa.compose.theme.LocalSurfaceLevel
import io.nekohasekai.sfa.compose.theme.SurfaceLevel
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** context.raisedSurfaceLevel: the level a card on the current surface takes. */
val raisedSurfaceLevel: SurfaceLevel
    @Composable get() = LocalSurfaceLevel.current.next

/**
 * card.dart's CommonCard: a FilledButton on the raised surface, its content
 * one level up. A selected card keeps its fill unless [fillsSelection].
 */
@Composable
fun CommonCard(
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    fillsSelection: Boolean = false,
    isError: Boolean = false,
    inert: Boolean = false,
    radius: Float = AppCorner.MD,
    shape: Shape = AppShape.all(radius),
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
        filled -> scheme.primary
        else -> level.colorOf(scheme)
    }
    val foreground = when {
        isError -> scheme.onErrorContainer
        filled -> scheme.onPrimary
        else -> scheme.onSurfaceVariant
    }
    val interaction = remember { MutableInteractionSource() }
    val enabled = !inert && onPressed != null
    Box(
        modifier
            .clip(shape)
            .background(animatedColor(background, 200), shape)
            .then(
                if (enabled || (!inert && onLongPress != null)) {
                    Modifier.combinedClickable(
                        interactionSource = interaction,
                        // card.dart: PressSurface ink, in the content colour on a filled card.
                        indication = PressIndication(PressLook.Ink, shape, if (filled) foreground else Color.Unspecified),
                        enabled = true,
                        role = Role.Button,
                        onLongClick = onLongPress,
                        onClick = { onPressed?.invoke() },
                    )
                } else {
                    Modifier
                },
            )
            .padding(padding),
        contentAlignment = contentAlignment,
    ) {
        CompositionLocalProvider(LocalSurfaceLevel provides level, LocalContentColor provides foreground) { content() }
    }
}

/** selection_mark.dart's SelectionBadge: a check on a primary circle. */
@Composable
fun SelectionBadge(size: Dp = 22.dp, preferred: Boolean = false, inUse: Boolean = false) {
    val scheme = BoxTheme.scheme
    val tonal = preferred && !inUse
    Box(
        Modifier
            .size(size)
            .background(if (tonal) scheme.primary.copy(alpha = 0.14f) else scheme.primary, androidx.compose.foundation.shape.CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(if (preferred) AppGlyphs.pin else AppGlyphs.check, size = size - 6.dp, fill = 1f, color = if (tonal) scheme.primary else scheme.onPrimary)
    }
}

@Composable
fun SelectionCheck() {
    GlyphIcon(AppGlyphs.check, size = 24.dp, fill = 1f, color = BoxTheme.scheme.primary)
}

/** loading.dart's CommonCircleLoading: a comet ring turning once a second. */
@Composable
fun CommonCircleLoading(size: Dp = 24.dp, color: Color = BoxTheme.scheme.primary, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "loading")
    val turns by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart), label = "turns")
    Canvas(modifier.size(size)) {
        val side = this.size.minDimension
        val strokeWidth = max(side / 12, 1.5.dp.toPx())
        val radius = (side * 20f / 24f - strokeWidth) / 2
        if (radius <= 0f) return@Canvas
        val tail = 0.72f
        drawCircle(color.copy(alpha = color.alpha * 0.12f), radius, style = Stroke(strokeWidth))
        rotate(turns * 360f) {
            val ring = Rect(center.x - radius, center.y - radius, center.x + radius, center.y + radius)
            drawArc(
                brush = Brush.sweepGradient(
                    0f to color.copy(alpha = 0f),
                    tail / 2 to color.copy(alpha = color.alpha * 0.3f),
                    tail to color,
                    tail to color.copy(alpha = 0f),
                    center = center,
                ),
                startAngle = 0f,
                sweepAngle = tail * 360f,
                useCenter = false,
                topLeft = ring.topLeft,
                size = ring.size,
                style = Stroke(strokeWidth),
            )
            val sweep = tail * 2 * PI
            drawCircle(color, strokeWidth / 2, Offset(center.x + cos(sweep).toFloat() * radius, center.y + sin(sweep).toFloat() * radius))
        }
    }
}

/** button.dart's TonalButtonSize. */
enum class TonalButtonSize(val button: Float, val icon: Float) {
    Bar(44f, 24f),
    Docked(48f, 24f),
    Section(36f, 20f),
    Compact(32f, 20f),
}

/** button.dart's TonalButton: a filled pill with a glyph and a label. */
@Composable
fun TonalButton(label: String, onPressed: (() -> Unit)?, glyph: Glyph? = null, size: TonalButtonSize = TonalButtonSize.Section, modifier: Modifier = Modifier) {
    val scheme = BoxTheme.scheme
    val foreground = if (onPressed == null) scheme.onSurface.copy(alpha = .38f) else scheme.onSurface
    Row(
        modifier
            .elasticPress(onPressed != null)
            .heightIn(min = size.button.dp)
            .clip(AppShape.full)
            .background(raisedSurfaceLevel.colorOf(scheme))
            .clickable(enabled = onPressed != null, interactionSource = remember { MutableInteractionSource() }, indication = PressIndication(PressLook.None, AppShape.full)) { onPressed?.invoke() }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (glyph != null) {
            GlyphIcon(glyph, size = size.icon.dp, fill = 1f, color = foreground)
            Spacer(Modifier.width(8.dp))
        }
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = AppTextStyle.Subheadline.of(true).copy(color = foreground))
    }
}

/** An IconButton under TonalButtonTheme: a filled square of [size], no ink, swelling on a press. */
@Composable
fun TonalIconButton(
    action: IconAction,
    size: TonalButtonSize = TonalButtonSize.Bar,
    grouped: Boolean = false,
    icon: (@Composable () -> Unit)? = null,
) {
    val scheme = BoxTheme.scheme
    val enabled = action.onClick != null && !action.loading
    val background = when {
        action.selected -> scheme.primary
        grouped -> Color.Transparent
        else -> raisedSurfaceLevel.colorOf(scheme)
    }
    val foreground = if (action.selected) scheme.onPrimary else scheme.onSurface
    Box(
        Modifier
            .then(if (grouped) Modifier else Modifier.elasticPress(enabled))
            .size(size.button.dp)
            .clip(AppShape.full)
            .background(background)
            .clickable(enabled = enabled, interactionSource = remember { MutableInteractionSource() }, indication = PressIndication(PressLook.None, AppShape.full)) { action.onClick?.invoke() }
            .semanticsLabel(action.tooltip),
        contentAlignment = Alignment.Center,
    ) {
        when {
            action.loading -> CommonCircleLoading(size = size.icon.dp)
            icon != null -> CompositionLocalProvider(LocalContentColor provides if (enabled) foreground else foreground.copy(alpha = .38f)) { icon() }
            else -> GlyphIcon(action.glyph, size = size.icon.dp, fill = 0f, color = if (enabled) foreground else foreground.copy(alpha = .38f))
        }
    }
}

/** button.dart's TonalButtonGroup: buttons sharing one filled pill that swells as one. */
@Composable
fun TonalButtonGroup(size: TonalButtonSize = TonalButtonSize.Bar, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier
            .elasticPress()
            .height(size.button.dp)
            .clip(AppShape.full)
            .background(raisedSurfaceLevel.colorOf(BoxTheme.scheme)),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** effect.dart's CommonExpandIcon: the caret turns half a turn over 200 ms. */
@Composable
fun CommonExpandIcon(expand: Boolean, size: Dp = 24.dp, color: Color = LocalContentColor.current) {
    val turns by animateFloatAsState(if (expand) 0.5f else 0f, tween(200, easing = FastOutSlowInEasing), label = "expand")
    GlyphIcon(AppGlyphs.caretDown, Modifier.graphicsLayer { rotationZ = turns * 360f }, size = size, fill = 1f, color = color)
}

/** fade_box.dart's FadeBox: children cross-fade in place over 300 ms. */
@Composable
fun <T> FadeBox(target: T, alignment: Alignment = Alignment.Center, modifier: Modifier = Modifier, content: @Composable (T) -> Unit) {
    AnimatedContent(
        targetState = target,
        modifier = modifier,
        contentAlignment = alignment,
        transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) },
        label = "fade",
    ) { content(it) }
}

