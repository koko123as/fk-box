package io.nekohasekai.sfa.compose.ui

import android.graphics.BlurMaskFilter
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import io.nekohasekai.sfa.compose.theme.LocalSurfaceLevel
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.compose.BoxStrings
import io.nekohasekai.sfa.compose.theme.AppCorner
import io.nekohasekai.sfa.compose.theme.AppShape
import io.nekohasekai.sfa.compose.theme.AppTextStyle
import io.nekohasekai.sfa.compose.theme.BoxScheme
import io.nekohasekai.sfa.compose.theme.BoxTheme
import io.nekohasekai.sfa.compose.theme.SurfaceLevel
import io.nekohasekai.sfa.compose.theme.tabular
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// ---------------------------------------------------------------------------
// Localization (l10n.dart's context.tr)
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
// Text styles from ui/context.dart
// ---------------------------------------------------------------------------

object ListStyles {
    val title: TextStyle @Composable get() = AppTextStyle.Body.of().copy(color = BoxTheme.scheme.onSurface)
    val subtitle: TextStyle @Composable get() = AppTextStyle.Subheadline.of().copy(color = BoxTheme.scheme.onSurfaceVariant)
    val caption: TextStyle @Composable get() = AppTextStyle.Footnote.of().copy(color = BoxTheme.scheme.onSurfaceVariant)
}

// ---------------------------------------------------------------------------
// Shadows (Flutter BoxShadow lists)
// ---------------------------------------------------------------------------

/** One Flutter BoxShadow; [blur] is the CSS-style blur the Dart sources pass. */
data class BoxShadowSpec(val color: Color, val dy: Float, val blur: Float, val spread: Float = 0f, val flutterRadius: Boolean = false)

/**
 * Paints blurred shadows under [shape]. Blur mask filters need hardware
 * canvas support from API 28; below that the shadows are left out.
 */
fun Modifier.boxShadows(shape: Shape, shadows: List<BoxShadowSpec>): Modifier =
    if (Build.VERSION.SDK_INT < 28 || shadows.isEmpty()) {
        this
    } else {
        drawBehind {
            for (shadow in shadows) {
                if (shadow.color.alpha <= 0f) continue
                val spread = shadow.spread.dp.toPx()
                val outline = shape.createOutline(Size(size.width + 2 * spread, size.height + 2 * spread), layoutDirection, this)
                val path = Path().apply { addOutline(outline) }
                val flutterBlur = if (shadow.flutterRadius) shadow.blur else if (shadow.blur <= 1f) 0f else (shadow.blur / 2 - .5f) / .57735f
                val radius = flutterBlur.dp.toPx()
                drawIntoCanvas { canvas ->
                    val paint = Paint().apply { color = shadow.color }
                    if (radius > 0f) paint.asFrameworkPaint().maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL)
                    canvas.save()
                    canvas.translate(-spread, -spread + shadow.dy.dp.toPx())
                    canvas.drawPath(path, paint)
                    canvas.restore()
                }
            }
        }
    }

/** navigation_dock.dart's dockShadows: a soft, wide drop in the manner of iOS. */
fun dockShadows(scheme: BoxScheme): List<BoxShadowSpec> {
    val strength = if (scheme.dark) 3f else 1f
    return listOf(
        BoxShadowSpec(scheme.shadow.copy(alpha = min(1f, 0.08f * strength)), 8f, 24f, flutterRadius = true),
        BoxShadowSpec(scheme.shadow.copy(alpha = min(1f, 0.04f * strength)), 1f, 3f, flutterRadius = true),
    )
}

// ---------------------------------------------------------------------------
// ElasticPress (navigation_dock.dart): a press swells the panel
// ---------------------------------------------------------------------------

private const val PRESS_GROWTH = 1f / 8
private const val MAX_PRESS_GROWTH = 16f
private val pressSpring = spring<Float>(dampingRatio = 0.593f, stiffness = 311f)

fun Modifier.elasticPress(enabled: Boolean = true, maxGrowth: Float = MAX_PRESS_GROWTH): Modifier = composed {
    val lift = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var size by remember { mutableStateOf(IntSize.Zero) }
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    this
        .onSizeChanged { size = it }
        .pointerInput(enabled) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                scope.launch { lift.animateTo(1f, pressSpring) }
                waitForUpOrCancellation()
                scope.launch {
                    // A tap lets go before the swell has risen; it rises all the way first.
                    while (lift.value < 0.975f && lift.isRunning) kotlinx.coroutines.delay(8)
                    lift.animateTo(0f, pressSpring)
                }
            }
        }
        .graphicsLayer {
            val longest = max(size.width, size.height) / density
            val growth = if (longest <= 0f) 0f else min(PRESS_GROWTH * 2, maxGrowth / longest)
            val swell = 1 + lift.value * growth
            scaleX = swell
            scaleY = swell
        }
}

// ---------------------------------------------------------------------------
// BoxPanel and PanelTitle (widgets.dart)
// ---------------------------------------------------------------------------

private fun panelShadows(dark: Boolean): List<BoxShadowSpec> = if (dark) {
    listOf(BoxShadowSpec(Color(0x80000000), 1f, 2f), BoxShadowSpec(Color(0x8c000000), 10f, 28f, -6f))
} else {
    listOf(BoxShadowSpec(Color(0x0d000000), .5f, 1f), BoxShadowSpec(Color(0x0a000000), 2f, 6f), BoxShadowSpec(Color(0x17000000), 12f, 32f, -6f))
}

val PanelShape = AppShape.all(34f)

@Composable
fun BoxPanel(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
    clip: Boolean = true,
    content: @Composable () -> Unit,
) {
    val scheme = BoxTheme.scheme
    Box(
        modifier
            .fillMaxWidth()
            .boxShadows(PanelShape, panelShadows(scheme.dark))
            .background(SurfaceLevel.Card.colorOf(scheme), PanelShape)
            .then(if (clip || onClick != null) Modifier.clip(PanelShape) else Modifier)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
    ) {
        CompositionLocalProvider(LocalSurfaceLevelForContent provides SurfaceLevel.Card) { content() }
    }
}

/** The surface a panel's content sits on, for raised children. */
val LocalSurfaceLevelForContent = staticCompositionLocalOf { SurfaceLevel.Page }

@Composable
fun PanelTitle(title: String, glyph: Glyph, trailing: (@Composable () -> Unit)? = null) {
    val scale = androidx.compose.ui.platform.LocalDensity.current.fontScale
    Row(Modifier.fillMaxWidth().height((26 * max(1f, scale)).dp), verticalAlignment = Alignment.CenterVertically) {
        GlyphIcon(glyph, size = 18.dp, color = BoxTheme.scheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(tr(title), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = AppTextStyle.Subheadline.of(true).copy(color = BoxTheme.scheme.onSurface))
        if (trailing != null) trailing()
    }
}

// ---------------------------------------------------------------------------
// DashboardChip (components/dashboard_chip.dart)
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
        if (dot) Box(Modifier.size(6.dp).background(foreground, androidx.compose.foundation.shape.CircleShape))
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = AppTextStyle.Caption1.of(true).tabular.copy(color = foreground))
    }
}

// ---------------------------------------------------------------------------
// CommonSwitch (ui/switch.dart): M3 track and thumb, white when pressed
// ---------------------------------------------------------------------------

const val SWITCH_TRACK_WIDTH = 52f
const val SWITCH_TRACK_HEIGHT = 32f

private val SwitchEaseOutBack = androidx.compose.animation.core.CubicBezierEasing(.175f, .885f, .32f, 1.275f)
private val SwitchEaseOutCubic = androidx.compose.animation.core.CubicBezierEasing(.215f, .61f, .355f, 1f)

@Composable
fun CommonSwitch(value: Boolean, onChanged: ((Boolean) -> Unit)?, modifier: Modifier = Modifier) {
    val scheme = BoxTheme.scheme
    val enabled = onChanged != null
    // ui/switch.dart: the thumb follows a horizontal drag and flips past
    // halfway; a tap flips it and lifts the thumb for 200 ms.
    val toggle = remember { Animatable(if (value) 1f else 0f) }
    val pressA = remember { Animatable(0f) }
    var dragging by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val currentValue by rememberUpdatedState(value)
    val currentOnChanged by rememberUpdatedState(onChanged)
    val switchRtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    val rtlNow by rememberUpdatedState(switchRtl)
    var liftJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun pressIn() {
        liftJob?.cancel()
        scope.launch { pressA.animateTo(1f, tween(150, easing = SwitchEaseOutCubic)) }
    }
    fun pressOut() { scope.launch { pressA.animateTo(0f, tween(150, easing = SwitchEaseOutCubic)) } }
    fun liftAcross() {
        pressIn()
        liftJob = scope.launch { kotlinx.coroutines.delay(200); pressOut() }
    }
    LaunchedEffect(value) {
        if (dragging) return@LaunchedEffect
        val target = if (value) 1f else 0f
        if (toggle.value == target) return@LaunchedEffect
        // A drag hands over its flip with the thumb already past halfway.
        if (kotlin.math.abs(toggle.value - target) > .5f && currentOnChanged != null) liftAcross()
        toggle.animateTo(target, tween(300, easing = SwitchEaseOutBack))
    }
    val on = toggle.value.coerceIn(0f, 1f)
    val press = pressA.value
    val trackColor = if (enabled) {
        androidx.compose.ui.graphics.lerp(scheme.surfaceContainerHighest, scheme.primary, on)
    } else {
        androidx.compose.ui.graphics.lerp(scheme.surfaceContainerHighest.copy(alpha = .12f), scheme.onSurface.copy(alpha = .12f), on)
    }
    val thumbColor = if (enabled) {
        androidx.compose.ui.graphics.lerp(scheme.outline, scheme.onPrimary, on)
    } else {
        androidx.compose.ui.graphics.lerp(scheme.onSurface.copy(alpha = .38f), scheme.surface, on)
    }
    val thumbFill = androidx.compose.ui.graphics.lerp(thumbColor, Color.White, press)
    Box(
        modifier
            .size(SWITCH_TRACK_WIDTH.dp, SWITCH_TRACK_HEIGHT.dp)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                val travel = (SWITCH_TRACK_WIDTH - SWITCH_TRACK_HEIGHT).dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    pressIn()
                    var moved = 0f
                    var cancelled = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null) { cancelled = true; break }
                        if (!change.pressed) break
                        val dx = change.positionChange().x
                        if (dx == 0f) continue
                        moved += dx
                        if (!dragging && kotlin.math.abs(moved) > viewConfiguration.touchSlop) {
                            dragging = true
                        }
                        if (dragging) {
                            change.consume()
                            val next = (toggle.value + (if (rtlNow) -dx else dx) / travel).coerceIn(0f, 1f)
                            scope.launch { toggle.snapTo(next) }
                        }
                    }
                    if (cancelled) {
                        pressOut()
                        if (dragging) {
                            dragging = false
                            scope.launch { toggle.animateTo(if (currentValue) 1f else 0f, tween(300, easing = SwitchEaseOutBack)) }
                        }
                        return@awaitEachGesture
                    }
                    if (!dragging) {
                        currentOnChanged?.invoke(!currentValue)
                        liftAcross()
                        return@awaitEachGesture
                    }
                    pressOut()
                    dragging = false
                    if ((toggle.value >= .5f) == currentValue) {
                        scope.launch { toggle.animateTo(if (currentValue) 1f else 0f, tween(300, easing = SwitchEaseOutBack)) }
                    } else {
                        currentOnChanged?.invoke(!currentValue)
                        // The flipped value arrives with the next composition, if the owner takes it.
                        scope.launch {
                            kotlinx.coroutines.yield()
                            androidx.compose.runtime.withFrameNanos { }
                            toggle.animateTo(if (currentValue) 1f else 0f, tween(300, easing = SwitchEaseOutBack))
                        }
                    }
                }
            },
    ) {
        Box(Modifier.fillMaxSize().background(trackColor, AppShape.full))
        val stretch = sin(Math.PI * on).toFloat()
        val position = toggle.value
        val baseW = 24f + (34f - 24f) * stretch
        val baseH = 24f + (22f - 24f) * stretch
        val w = baseW + (40f + 10f * stretch - baseW) * press
        val h = baseH + (40f - 2f * stretch - baseH) * press
        val along = SWITCH_TRACK_HEIGHT / 2 + (SWITCH_TRACK_WIDTH - SWITCH_TRACK_HEIGHT) * position
        Box(
            Modifier
                .offset((along - w / 2).dp, ((SWITCH_TRACK_HEIGHT - h) / 2).dp)
                .size(w.dp, h.dp)
                .boxShadows(AppShape.full, if (press > 0f) listOf(BoxShadowSpec(scheme.shadow.copy(alpha = .2f * press), 2f, 6f, flutterRadius = true)) else emptyList())
                .background(thumbFill, AppShape.full),
        )
    }
}


// ---------------------------------------------------------------------------
// Skeleton (ui/skeleton.dart): a pulsing pill
// ---------------------------------------------------------------------------

@Composable
fun SkeletonPill(width: Dp?, height: Dp, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val t by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = androidx.compose.animation.core.LinearEasing), RepeatMode.Restart), label = "pulse")
    // _Pulse: eases between 1 and .4 and back over the period.
    val alpha = 0.7f + 0.3f * kotlin.math.cos(2 * Math.PI * t).toFloat()
    Box(
        modifier
            .then(if (width == null) Modifier.fillMaxWidth() else Modifier.width(width))
            .height(height)
            .graphicsLayer { this.alpha = alpha }
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
// Lists (ui/list.dart, list_selected.dart): grouped rows on a card
// ---------------------------------------------------------------------------

enum class ItemPosition { Start, Middle, End, StartAndEnd;
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
    val shape = AppShape.vertical(if (start) AppCorner.XL else 0f, if (end) AppCorner.XL else 0f)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val sorting = LocalSorting.current
    val level = LocalSurfaceLevel.current.next
    val background = if (invalid) scheme.errorContainer else level.colorOf(scheme)
    val foreground = if (invalid) scheme.onErrorContainer else scheme.onSurfaceVariant
    Column(
        modifier
            .fillMaxWidth()
            .then(if (position != null) Modifier.background(background, shape).clip(shape) else Modifier)
            .then(if (onClick != null && !sorting) Modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick) else Modifier)
            .pressOverlay(pressed && onClick != null && position != null, foreground, shape),
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
                    androidx.compose.material3.ProvideTextStyle(ListStyles.title) { title() }
                    if (subtitle != null) androidx.compose.material3.ProvideTextStyle(ListStyles.subtitle) { subtitle() }
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(16.dp))
                CompositionLocalProvider(LocalContentColor provides scheme.onSurfaceVariant, LocalSurfaceLevel provides level) {
                    androidx.compose.material3.ProvideTextStyle(ListStyles.subtitle) { trailing() }
                }
            }
        }
        if (position != null && !end) {
            HorizontalDivider(
                Modifier.padding(start = if (leading != null) 56.dp else 16.dp, end = 14.dp),
                thickness = 0.5.dp,
                color = if (invalid) scheme.error else scheme.onSurface.copy(alpha = 0.14f),
            )
        }
    }
}

/** list.dart's generateSection: an optional header, the rows grouped on one card, an optional footer. */
@Composable
fun Section(title: String? = null, count: Int, footer: String? = null, item: @Composable (index: Int, position: ItemPosition) -> Unit) {
    if (count <= 0) return
    if (title != null) {
        Text(
            title,
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
            style = AppTextStyle.Headline.of().copy(color = BoxTheme.scheme.onSurfaceVariant),
        )
    } else {
        Spacer(Modifier.height(24.dp))
    }
    Column(Modifier.fillMaxWidth()) {
        for (index in 0 until count) item(index, ItemPosition.of(index, count))
    }
    if (footer != null) ListFooter(footer)
}

/** list.dart's generateSection over a list of rows, each told its position. */
@Composable
fun Section(title: String? = null, footer: String? = null, rows: List<@Composable (ItemPosition) -> Unit>) {
    Section(title, rows.size, footer) { index, position -> rows[index](position) }
}

/** list.dart's ListFooter. */
@Composable
fun ListFooter(text: String) {
    Text(text, Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp), style = ListStyles.caption)
}

// ---------------------------------------------------------------------------
// Empty and error states (widgets.dart)
// ---------------------------------------------------------------------------

@Composable
fun EmptyState(title: String, message: String? = null, glyph: Glyph = AppGlyphs.info, action: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 64.dp, horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        GlyphIcon(glyph, size = 56.dp, color = BoxTheme.scheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        Text(tr(title), style = AppTextStyle.Title3.of().copy(color = BoxTheme.scheme.onSurface), textAlign = TextAlign.Center)
        if (message != null) {
            Text(tr(message), Modifier.padding(vertical = 12.dp), textAlign = TextAlign.Center, style = ListStyles.subtitle)
        }
        if (action != null) Box(Modifier.padding(top = 16.dp)) { action() }
    }
}

@Composable
fun FilledPillButton(text: String, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    androidx.compose.material3.Button(onClick = { onClick?.invoke() }, enabled = onClick != null, modifier = modifier, shape = AppShape.full) {
        Text(text, style = AppTextStyle.Subheadline.of(true))
    }
}

@Composable
fun TextPillButton(text: String, onClick: (() -> Unit)?, modifier: Modifier = Modifier, color: Color? = null) {
    androidx.compose.material3.TextButton(onClick = { onClick?.invoke() }, enabled = onClick != null, modifier = modifier, shape = AppShape.full) {
        Text(text, style = AppTextStyle.Subheadline.of(true).let { if (color != null) it.copy(color = color) else it })
    }
}

@Composable
fun ErrorState(error: String, retry: (() -> Unit)? = null) {
    if (retry == null) {
        EmptyState("Error", message = error)
    } else {
        EmptyState("Error", message = error, action = { FilledPillButton(tr("Retry"), retry) })
    }
}

/** A small round icon button (Material IconButton with the pill shape). */
@Composable
fun GlyphButton(
    glyph: Glyph,
    contentDescription: String?,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    iconSize: Dp = 24.dp,
    color: Color = LocalContentColor.current,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .size(size)
            .clip(AppShape.full)
            .clickable(interactionSource = interaction, indication = androidx.compose.material3.ripple(), enabled = onClick != null) { onClick?.invoke() }
            .then(if (contentDescription != null) Modifier.semanticsLabel(contentDescription) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(glyph, size = iconSize, color = if (onClick == null) color.copy(alpha = .38f) else color)
    }
}

fun Modifier.semanticsLabel(label: String): Modifier = this.then(
    Modifier.semantics(mergeDescendants = true) { contentDescription = label },
)


@Composable
fun animatedColor(target: Color, millis: Int = 240): Color = animateColorAsState(target, tween(millis), label = "color").value
