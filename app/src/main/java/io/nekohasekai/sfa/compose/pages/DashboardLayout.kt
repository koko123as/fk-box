package io.nekohasekai.sfa.compose.pages

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nekohasekai.sfa.compose.theme.AppShape
import io.nekohasekai.sfa.compose.theme.forClip
import io.nekohasekai.sfa.compose.theme.AppTextStyle
import io.nekohasekai.sfa.compose.theme.BoxTheme
import io.nekohasekai.sfa.compose.theme.SurfaceLevel
import io.nekohasekai.sfa.compose.theme.alphaBlend
import io.nekohasekai.sfa.compose.theme.tabular
import io.nekohasekai.sfa.compose.ui.AppGlyphs
import io.nekohasekai.sfa.compose.ui.BoxShadowSpec
import io.nekohasekai.sfa.compose.ui.CommonSwitch
import io.nekohasekai.sfa.compose.ui.Glyph
import io.nekohasekai.sfa.compose.ui.GlyphButton
import io.nekohasekai.sfa.compose.ui.GlyphIcon
import io.nekohasekai.sfa.compose.ui.LocalSurfaceLevelForContent
import io.nekohasekai.sfa.compose.ui.MaterialShapes
import io.nekohasekai.sfa.compose.ui.SWITCH_TRACK_HEIGHT
import io.nekohasekai.sfa.compose.ui.SWITCH_TRACK_WIDTH
import io.nekohasekai.sfa.compose.ui.ShapeBadge
import io.nekohasekai.sfa.compose.ui.boxShadows
import io.nekohasekai.sfa.compose.ui.semanticsLabel
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/*
 * views/dashboard/layout.dart, style.dart and widgets/card.dart (FlClash
 * be84736605527034689c95bf27828ff27d912c5e, GPL-3.0): the window classes,
 * density scope and the card building blocks the desktop grid shares with
 * the phone's card list.
 */

private const val MIN_DESKTOP_WIDTH = 640f
private const val MIN_FOUR_COLUMN_WIDTH = 860f
private const val MIN_STANDARD_FOUR_COLUMN_WIDTH = 1040f
private const val MIN_STANDARD_HEIGHT = 824f
private const val MOBILE_MAX_WIDTH = 560f

fun dashboardColumnsOf(width: Float): Int = if (width < MIN_FOUR_COLUMN_WIDTH) 2 else 4

/** The mobile cards and their editor share one centered column. */
fun dashboardMobileSideOf(width: Float): Float = max(16f, (width - MOBILE_MAX_WIDTH) / 2)

/** [Compact] shrinks type, the start button and tiles, and drops subtitles. */
enum class DashboardDensity {
    Standard,
    Compact,
    Mobile,
    ;

    val isDesktop: Boolean get() = this != Mobile
    val isCompact: Boolean get() = this == Compact

    companion object {
        /** [width] and [height] in dp, already divided by the text scale. */
        fun of(width: Float, height: Float): DashboardDensity {
            if (width < MIN_DESKTOP_WIDTH) return Mobile
            val narrowColumns = dashboardColumnsOf(width) == 4 && width < MIN_STANDARD_FOUR_COLUMN_WIDTH
            return if (narrowColumns || height < MIN_STANDARD_HEIGHT) Compact else Standard
        }
    }
}

/** DashboardDensityScope: every fixed height on the dashboard grows by [scale] so its text fits. */
@Immutable
data class DashboardScope(val density: DashboardDensity, val scale: Float)

val LocalDashboardScope = compositionLocalOf { DashboardScope(DashboardDensity.Mobile, 1f) }

val dashboardDensity: DashboardDensity
    @Composable @ReadOnlyComposable get() = LocalDashboardScope.current.density

val dashboardScale: Float
    @Composable @ReadOnlyComposable get() = LocalDashboardScope.current.scale

/** tr() with FlClash's dashboard wording where it differs from FK-box's for the same English key. */
@Composable
@ReadOnlyComposable
fun trDashboard(value: String): String =
    if (io.nekohasekai.sfa.compose.ui.isChinese()) io.nekohasekai.sfa.compose.BoxStrings.dashboardZh[value] ?: io.nekohasekai.sfa.compose.ui.tr(value) else value

// ---------------------------------------------------------------------------
// style.dart
// ---------------------------------------------------------------------------

object DashboardColors {
    val page: Color @Composable @ReadOnlyComposable get() = SurfaceLevel.Page.colorOf(BoxTheme.scheme)
    val card: Color @Composable @ReadOnlyComposable get() = SurfaceLevel.Card.colorOf(BoxTheme.scheme)
    val tone: Color @Composable @ReadOnlyComposable get() = SurfaceLevel.Block.colorOf(BoxTheme.scheme)
    val tone2: Color @Composable @ReadOnlyComposable get() = SurfaceLevel.Inset.colorOf(BoxTheme.scheme)
    val series2: Color @Composable @ReadOnlyComposable get() = alphaBlend(BoxTheme.scheme.primary.copy(alpha = .45f), card)
    val warn: Color @Composable @ReadOnlyComposable get() = BoxTheme.scheme.warning
}

object DashboardCorner {
    const val DESKTOP_CARD = 32f
    const val MOBILE_CARD = 34f
    const val BLOCK = 20f
    const val MOBILE_BLOCK = 22f
    const val TAB = 16f
    const val TABLE_HEADER = 12f
    const val BADGE = 14f
}

fun dashboardShadowsE1(dark: Boolean): List<BoxShadowSpec> = if (dark) {
    listOf(BoxShadowSpec(Color(0x80000000), 1f, 2f), BoxShadowSpec(Color(0x8c000000), 10f, 28f, -6f))
} else {
    listOf(BoxShadowSpec(Color(0x0d000000), .5f, 1f), BoxShadowSpec(Color(0x0a000000), 2f, 6f), BoxShadowSpec(Color(0x17000000), 12f, 32f, -6f))
}

/** Grouped digits until they outgrow a third of a phone's width. */
fun dashboardCount(count: Long): String = if (count < 100_000) {
    String.format(java.util.Locale.US, "%,d", count)
} else {
    val units = listOf("K" to 1e3, "M" to 1e6, "B" to 1e9)
    val (unit, size) = units.last { count >= it.second }
    val value = count / size
    (if (value >= 100) value.toLong().toString() else String.format(java.util.Locale.ROOT, "%.1f", value).trimEnd('0').trimEnd('.')) + unit
}

fun dashboardTraffic(bytes: Number): String = io.nekohasekai.sfa.compose.bytes(bytes)

object DashboardText {
    @Composable
    fun cardTitle(): TextStyle = AppTextStyle.Footnote.of(true).copy(color = BoxTheme.scheme.onSurfaceVariant)

    @Composable
    fun mobileCardTitle(): TextStyle = AppTextStyle.Subheadline.of(true).copy(color = BoxTheme.scheme.onSurface)

    @Composable
    fun label(color: Color? = null): TextStyle = AppTextStyle.Caption1.of().tabular.copy(color = color ?: BoxTheme.scheme.onSurfaceVariant)

    @Composable
    fun figure(style: AppTextStyle, color: Color? = null): TextStyle =
        style.of(true).tabular.copy(color = color ?: BoxTheme.scheme.onSurface, letterSpacing = (-.02f * style.size).sp)

    @Composable
    fun unit(style: AppTextStyle = AppTextStyle.Subheadline): TextStyle = style.of(true).copy(color = BoxTheme.scheme.onSurfaceVariant)

    @Composable
    fun rowTitle(): TextStyle = AppTextStyle.Subheadline.of(true).copy(color = BoxTheme.scheme.onSurface)
}

// ---------------------------------------------------------------------------
// card.dart
// ---------------------------------------------------------------------------

fun dashboardCardPadding(density: DashboardDensity): PaddingValues = when (density) {
    DashboardDensity.Standard -> PaddingValues(horizontal = 22.dp, vertical = 20.dp)
    DashboardDensity.Compact -> PaddingValues(horizontal = 20.dp, vertical = 16.dp)
    DashboardDensity.Mobile -> PaddingValues(horizontal = 20.dp, vertical = 18.dp)
}

fun dashboardCardPaddingStart(density: DashboardDensity): Float = when (density) {
    DashboardDensity.Standard -> 22f
    DashboardDensity.Compact -> 20f
    DashboardDensity.Mobile -> 20f
}

fun dashboardCardPaddingTop(density: DashboardDensity): Float = when (density) {
    DashboardDensity.Standard -> 20f
    DashboardDensity.Compact -> 16f
    DashboardDensity.Mobile -> 18f
}

fun dashboardCardRadius(density: DashboardDensity): Float = if (density.isDesktop) DashboardCorner.DESKTOP_CARD else DashboardCorner.MOBILE_CARD

private const val INK_ALPHA = .1f
private const val INK_FADE_MS = 200

/**
 * prism_ui's PressSurface with the ink look, as DashboardCard and
 * DashboardBlock use it: a press fades an onSurface ink at 10% in over 200 ms
 * under the content and lets it out over 200 ms. A quick tap, which Flutter
 * reports down and up together, shows no ink.
 */
fun Modifier.pressInk(shape: Shape, enabled: Boolean, label: String? = null, onClick: () -> Unit): Modifier =
    this.composedPressInk(shape, enabled, label, onClick)

@Composable
private fun inkModifier(shape: Shape, enabled: Boolean, label: String?, onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val ink = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val color = BoxTheme.scheme.onSurface
    LaunchedEffect(interaction) {
        interaction.interactions.collect { event ->
            when (event) {
                is PressInteraction.Press -> scope.launch { ink.animateTo(1f, tween(INK_FADE_MS, easing = LinearEasing)) }
                is PressInteraction.Release, is PressInteraction.Cancel ->
                    scope.launch { ink.animateTo(0f, tween(INK_FADE_MS, easing = LinearEasing)) }
            }
        }
    }
    return Modifier
        .drawBehind {
            val value = ink.value
            if (value > 0f) {
                drawOutline(shape.createOutline(size, layoutDirection, this), color.copy(alpha = INK_ALPHA * value))
            }
        }
        .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
        .then(if (label != null) Modifier.semanticsLabel(label) else Modifier)
}

private fun Modifier.composedPressInk(shape: Shape, enabled: Boolean, label: String?, onClick: () -> Unit): Modifier =
    this.composed { inkModifier(shape, enabled, label, onClick) }

/**
 * DashboardCard: the card surface with the e1 shadows. Unclipped by default
 * so a control swelling under an elastic press can paint past the card's edge.
 */
@Composable
fun DashboardCard(
    modifier: Modifier = Modifier,
    padding: PaddingValues? = null,
    onTap: (() -> Unit)? = null,
    semanticLabel: String? = null,
    clip: Boolean = false,
    side: Pair<Dp, Color>? = null,
    content: @Composable () -> Unit,
) {
    val scheme = BoxTheme.scheme
    val density = dashboardDensity
    val shape = AppShape.all(dashboardCardRadius(density))
    Box(
        Modifier
            .fillMaxWidth()
            .then(modifier)
            .boxShadows(shape, dashboardShadowsE1(scheme.dark))
            .background(SurfaceLevel.Card.colorOf(scheme), shape)
            // The border sits outside the clip, which is a native rounded rectangle just
            // inside the squircle (Shape.forClip), so the squircle border stays whole.
            .then(if (side != null) Modifier.border(side.first, side.second, shape) else Modifier)
            .then(if (clip || onTap != null) Modifier.clip(shape.forClip()) else Modifier)
            .then(if (onTap != null) Modifier.pressInk(shape, true, semanticLabel, onTap) else if (semanticLabel != null) Modifier.semanticsLabel(semanticLabel) else Modifier)
            .padding(padding ?: dashboardCardPadding(density)),
    ) {
        androidx.compose.runtime.CompositionLocalProvider(LocalSurfaceLevelForContent provides SurfaceLevel.Card) { content() }
    }
}

const val DASHBOARD_HEADER_HEIGHT = 24f

/** DashboardCardHeader: glyph, title, [leading] right after it and [trailing] at the end. */
@Composable
fun DashboardCardHeader(
    title: String,
    glyph: Glyph? = null,
    leading: (@Composable RowScope.() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    chevron: Boolean = false,
) {
    val mobile = !dashboardDensity.isDesktop
    val scale = dashboardScale
    val scheme = BoxTheme.scheme
    val style = if (mobile) DashboardText.mobileCardTitle() else DashboardText.cardTitle()
    val hasEnd = trailing != null || chevron
    Row(
        Modifier.fillMaxWidth().height(((if (mobile) 26f else DASHBOARD_HEADER_HEIGHT) * scale).dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (glyph != null) GlyphIcon(glyph, size = 18.dp, color = scheme.onSurfaceVariant)
        val start: @Composable () -> Unit = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis, style = style)
                leading?.invoke(this)
            }
        }
        if (!hasEnd) {
            Box(Modifier.weight(1f)) { start() }
        } else {
            DashboardCappedRow(
                modifier = Modifier.weight(1f),
                start = start,
                end = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        trailing?.invoke(this)
                        if (chevron) GlyphIcon(AppGlyphs.chevronForward, size = 18.dp, color = scheme.onSurfaceVariant)
                    }
                },
            )
        }
    }
}

/**
 * DashboardCappedRow: [end] is laid out first at its own width, up to [share]
 * of the row and scaled down past that; [start] takes what is left.
 */
@Composable
fun DashboardCappedRow(
    start: @Composable () -> Unit,
    end: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    share: Float = .5f,
    spacing: Dp = 8.dp,
) {
    val rtl = LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    Layout(
        contents = listOf(start, end),
        modifier = modifier,
    ) { (startMeasurables, endMeasurables), constraints ->
        val gap = spacing.roundToPx()
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
        val cap = (max(0, width - gap) * share).toInt()
        val endPlaceables = endMeasurables.map { it.measure(Constraints(maxHeight = constraints.maxHeight)) }
        val natural = endPlaceables.maxOfOrNull { it.width } ?: 0
        val endScale = if (natural > cap && natural > 0) cap.toFloat() / natural else 1f
        val endWidth = min(natural, cap)
        val startWidth = max(0, width - endWidth - if (endWidth > 0) gap else 0)
        val startPlaceables = startMeasurables.map { it.measure(Constraints(maxWidth = startWidth, maxHeight = constraints.maxHeight)) }
        val height = if (constraints.hasBoundedHeight) {
            max(constraints.minHeight, max(startPlaceables.maxOfOrNull { it.height } ?: 0, endPlaceables.maxOfOrNull { (it.height * endScale).toInt() } ?: 0)).coerceAtMost(constraints.maxHeight)
        } else {
            max(startPlaceables.maxOfOrNull { it.height } ?: 0, endPlaceables.maxOfOrNull { (it.height * endScale).toInt() } ?: 0)
        }
        layout(width, height) {
            for (placeable in startPlaceables) {
                val x = if (rtl) width - placeable.width else 0
                placeable.place(x, (height - placeable.height) / 2)
            }
            for (placeable in endPlaceables) {
                val x = if (rtl) 0 else width - placeable.width
                placeable.placeWithLayer(x, (height - placeable.height) / 2) {
                    scaleX = endScale
                    scaleY = endScale
                    transformOrigin = TransformOrigin(if (rtl) 0f else 1f, .5f)
                }
            }
        }
    }
}

/** DashboardFitText: shrinks to 75% before cutting short, so an IPv6 address stays readable. */
@Composable
fun DashboardFitText(text: String, style: TextStyle, modifier: Modifier = Modifier) {
    val size = style.fontSize.value
    BasicText(
        text,
        modifier,
        style = style,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        autoSize = TextAutoSize.StepBased(minFontSize = (size * .75f).sp, maxFontSize = size.sp, stepSize = .5.sp),
    )
}

/** DashboardBlock: a block inside a card, one tone off the card itself. */
@Composable
fun DashboardBlock(
    radius: Float,
    modifier: Modifier = Modifier,
    color: Color? = null,
    padding: PaddingValues = PaddingValues(0.dp),
    onTap: (() -> Unit)? = null,
    semanticLabel: String? = null,
    content: @Composable () -> Unit,
) {
    val shape = AppShape.all(radius)
    val fill = color ?: DashboardColors.tone
    Box(
        modifier
            .background(fill, shape)
            .clip(shape.forClip())
            .then(if (onTap != null) Modifier.pressInk(shape, true, semanticLabel, onTap) else Modifier)
            .padding(padding),
    ) { content() }
}

/** DashboardIconButton: a 32 dp round button whose height a 24 dp header cannot squeeze. */
@Composable
fun DashboardIconButton(glyph: Glyph, tooltip: String, onPressed: (() -> Unit)?, modifier: Modifier = Modifier) {
    Box(modifier.width(32.dp).height(24.dp), contentAlignment = Alignment.Center) {
        GlyphButton(
            glyph,
            tooltip,
            onPressed,
            modifier = Modifier.wrapContentSize(Alignment.Center, unbounded = true),
            size = 32.dp,
            iconSize = 18.dp,
            color = BoxTheme.scheme.onSurfaceVariant,
        )
    }
}

/** DashboardSwitch: lays out at its track so the switch never sets a row's height. */
@Composable
fun DashboardSwitch(value: Boolean, onChanged: ((Boolean) -> Unit)?) {
    Box(Modifier.width(SWITCH_TRACK_WIDTH.dp).height(SWITCH_TRACK_HEIGHT.dp)) { CommonSwitch(value, onChanged) }
}

private const val SWITCH_ROW_GAP = 12f

/** DashboardSwitchRow.iconFits: whether each title fits beside the icon in rows [width] dp wide. */
@Composable
fun dashboardSwitchIconFits(width: Float, iconSize: Float, paddingHorizontal: Float, titles: List<String>): Boolean {
    val measurer = rememberTextMeasurer()
    val style = DashboardText.rowTitle()
    val density = LocalDensity.current
    val room = width - paddingHorizontal - iconSize - SWITCH_ROW_GAP * 2 - SWITCH_TRACK_WIDTH
    return titles.all { title ->
        val measured = measurer.measure(title, style, maxLines = 1, softWrap = false)
        with(density) { measured.size.width.toDp().value } <= room
    }
}

/**
 * DashboardSwitchRow: one setting behind a switch, a cookie-6 icon container
 * while it is on and a plain circle while it is off.
 */
@Composable
fun DashboardSwitchRow(
    glyph: Glyph,
    title: String,
    subtitle: String?,
    value: Boolean,
    onChanged: ((Boolean) -> Unit)?,
    iconSize: Float,
    padding: PaddingValues,
    radius: Float,
    modifier: Modifier = Modifier,
    color: Color? = null,
    onTap: (() -> Unit)? = null,
    showIcon: Boolean = true,
) {
    val scheme = BoxTheme.scheme
    val cardColor = DashboardColors.card
    DashboardBlock(radius = radius, modifier = modifier, color = color, padding = padding, onTap = if (onChanged != null) onTap else null, semanticLabel = title) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(SWITCH_ROW_GAP.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showIcon) {
                AnimatedContent(value, transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) }, label = "switchRow") { on ->
                    ShapeBadge(if (on) MaterialShapes.Cookie6Sided else MaterialShapes.Circle, if (on) scheme.primary else cardColor, iconSize.dp) {
                        GlyphIcon(glyph, size = (iconSize * .5f).dp, color = if (on) scheme.onPrimary else scheme.onSurfaceVariant)
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = DashboardText.rowTitle())
                if (subtitle != null) Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = DashboardText.label())
            }
            DashboardSwitch(value, onChanged)
        }
    }
}

const val DASHBOARD_FADE_HEIGHT = 44f

/**
 * DashboardFadeOut: the last 44 dp of a list that runs past its card fade out
 * instead of showing a scroll bar. The card under the list is one solid color
 * ([background], the card's own), so the fade is painted as a gradient of that
 * color over the list: the same pixels as fading the list out with DstIn, but
 * with no offscreen layer to allocate and composite on every frame.
 */
fun Modifier.dashboardFadeOut(enabled: Boolean, background: Color): Modifier = if (!enabled) {
    this
} else {
    this.drawWithContent {
        drawContent()
        val fade = DASHBOARD_FADE_HEIGHT.dp.toPx()
        val stop = if (size.height <= 0f) 1f else ((size.height - fade) / size.height).coerceIn(0f, 1f)
        drawRect(Brush.verticalGradient(0f to background.copy(alpha = 0f), stop to background.copy(alpha = 0f), 1f to background))
    }
}

/** The 8 dp nudge FlClash gives trailing header buttons (Transform.translate(8, 0)). */
fun Modifier.headerNudge(): Modifier = this.offset(x = 8.dp)

/** Wraps a card in its own layer, as FlClash's RepaintBoundary does, so its per-second redraws stay its own. */
@Composable
fun DashboardRepaintBoundary(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.graphicsLayer {}) { content() }
}
