package io.nekohasekai.sfa.compose.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.toPath
import io.nekohasekai.sfa.compose.theme.AppTextStyle
import io.nekohasekai.sfa.compose.theme.BoxScheme
import io.nekohasekai.sfa.compose.theme.BoxTheme
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** null_status.dart's NullStatusIllustration. */
enum class NullStatusIllustration {
    Data, Logs, Proxies, Profile, Scripts, Rules, Connections, Requests, Dns, Ntp, Sniffer, Tun, Wifi, Apps, Icons, Fonts, Permission, Camera, Error, Search,
}

private const val COMPACT_HEIGHT = 560
private const val STAGGER_STEP = 60
private const val ENTER_RISE = 12f
private const val COMMON_DURATION = 300

/** null_status.dart's NullStatus: an illustration, a label, a description and an action, entering in steps. */
@Composable
fun NullStatus(
    label: String? = null,
    description: String? = null,
    illustration: NullStatusIllustration = NullStatusIllustration.Data,
    action: (@Composable () -> Unit)? = null,
) {
    val scheme = BoxTheme.scheme
    val compact = LocalConfiguration.current.screenHeightDp < COMPACT_HEIGHT
    Box(Modifier.fillMaxSize(), contentAlignment = BiasAlignment(0f, -0.2f)) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp, vertical = 24.dp)
                .widthIn(max = 320.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            EnterItem(0) { EmptyIllustration(illustration, if (compact) 120 else 160) }
            if (label != null) {
                Spacer(Modifier.height(if (compact) 12.dp else 16.dp))
                EnterItem(STAGGER_STEP) {
                    Text(label, textAlign = TextAlign.Center, style = AppTextStyle.Headline.of().copy(color = scheme.onSurface))
                }
            }
            if (description != null) {
                Spacer(Modifier.height(8.dp))
                EnterItem(STAGGER_STEP * 2) {
                    Text(description, textAlign = TextAlign.Center, style = AppTextStyle.Subheadline.of().copy(color = scheme.onSurfaceVariant))
                }
            }
            if (action != null) {
                Spacer(Modifier.height(24.dp))
                EnterItem(STAGGER_STEP * if (description != null) 3 else 2) { action() }
            }
        }
    }
}

/** _EnterItem: fades and rises 12 dp over the common duration after its stagger. */
@Composable
private fun EnterItem(delay: Int, content: @Composable () -> Unit) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(COMMON_DURATION + delay, easing = LinearEasing)) }
    val total = (COMMON_DURATION + delay).toFloat()
    val start = delay / total
    Box(
        Modifier.graphicsLayer {
            val t = ((progress.value - start) / (1 - start)).coerceIn(0f, 1f)
            val eased = EaseOutCubicCurve.transform(t)
            alpha = eased
            translationY = ENTER_RISE.dp.toPx() * (1 - eased)
        },
    ) { content() }
}

/** NullStatusSwitcher: the content, the empty status or no search results, cross-faded with a .92 scale. */
@Composable
fun NullStatusSwitcher(
    isEmpty: Boolean,
    isSearching: Boolean = false,
    nullStatus: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val slot = when {
        isEmpty && isSearching -> 1
        isEmpty -> 2
        else -> 3
    }
    AnimatedContent(
        targetState = slot,
        transitionSpec = {
            (fadeIn(tween(COMMON_DURATION, easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f))) + scaleIn(tween(COMMON_DURATION, easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)), initialScale = 0.92f)) togetherWith
                fadeOut(tween(150, easing = CubicBezierEasing(0.42f, 0f, 1f, 1f)))
        },
        contentAlignment = Alignment.Center,
        label = "nullStatus",
    ) { current ->
        when (current) {
            1 -> NullStatus(label = tr("No results"), illustration = NullStatusIllustration.Search)
            2 -> nullStatus()
            else -> content()
        }
    }
}

private enum class Entrance { Spin, Morph, Orbit }

private enum class Accent { Blob, Dot, Ring }

private class ShapePose(val scale: Float, val turn: Float, val morph: Float, val alpha: Float)
private class GlyphPose(val scale: Float, val rise: Float, val fill: Float, val alpha: Float)
private class AccentPose(val scale: Float, val drift: Float, val orbit: Float, val turn: Float)

private val EaseOutBackCurve = CubicBezierEasing(0.175f, 0.885f, 0.32f, 1.275f)
private val EaseInOutCubicCurve = CubicBezierEasing(0.645f, 0.045f, 0.355f, 1f)
private val EaseOutCubicCurve = CubicBezierEasing(0.215f, 0.61f, 0.355f, 1f)

/** Flutter's ElasticOutCurve. */
private class ElasticOut(private val period: Float) : Easing {
    override fun transform(fraction: Float): Float {
        val s = period / 4
        return (2.0.pow(-10.0 * fraction) * sin((fraction - s) * (PI * 2) / period) + 1).toFloat()
    }
}

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

private fun phase(t: Float, begin: Float, end: Float, curve: Easing = LinearEasing): Float =
    curve.transform(((t - begin) / (end - begin)).coerceIn(0f, 1f))

private fun artOf(type: NullStatusIllustration): Pair<RoundedPolygon, Glyph> = when (type) {
    NullStatusIllustration.Data -> M3Shapes.Cookie12Sided to AppGlyphs.resources
    NullStatusIllustration.Logs -> M3Shapes.Cookie9Sided to AppGlyphs.logs
    NullStatusIllustration.Proxies -> M3Shapes.Sunny to AppGlyphs.proxies
    NullStatusIllustration.Profile -> M3Shapes.Cookie7Sided to AppGlyphs.profiles
    NullStatusIllustration.Scripts -> M3Shapes.Cookie6Sided to AppGlyphs.code
    NullStatusIllustration.Rules -> M3Shapes.Clover8Leaf to AppGlyphs.rules
    NullStatusIllustration.Connections -> M3Shapes.Cookie4Sided to AppGlyphs.connections
    NullStatusIllustration.Requests -> M3Shapes.SoftBurst to AppGlyphs.requests
    NullStatusIllustration.Dns -> M3Shapes.Puffy to AppGlyphs.dns
    NullStatusIllustration.Ntp -> M3Shapes.Pentagon to AppGlyphs.clock
    NullStatusIllustration.Sniffer -> M3Shapes.Gem to AppGlyphs.eye
    NullStatusIllustration.Tun -> M3Shapes.ClamShell to AppGlyphs.vpn
    NullStatusIllustration.Wifi -> M3Shapes.Cookie7Sided to AppGlyphs.wifi
    NullStatusIllustration.Apps -> M3Shapes.Clover4Leaf to AppGlyphs.appsList
    NullStatusIllustration.Icons -> M3Shapes.VerySunny to AppGlyphs.iconTile
    NullStatusIllustration.Fonts -> M3Shapes.PuffyDiamond to AppGlyphs.font
    NullStatusIllustration.Permission -> M3Shapes.Sunny to AppGlyphs.lock
    NullStatusIllustration.Camera -> M3Shapes.Cookie6Sided to AppGlyphs.camera
    NullStatusIllustration.Error -> M3Shapes.SoftBurst to AppGlyphs.error
    NullStatusIllustration.Search -> M3Shapes.Flower to AppGlyphs.search
}

private class Palette(val shape: Color, val ink: Color, val highlight: Color, val accent: Color, val accentContainer: Color)

private fun paletteOf(scheme: BoxScheme, error: Boolean) = if (error) {
    Palette(scheme.errorContainer, scheme.onErrorContainer, scheme.error, scheme.error, scheme.errorContainer)
} else {
    Palette(scheme.primaryContainer, scheme.onPrimaryContainer, scheme.primary, scheme.tertiary, scheme.tertiaryContainer)
}

private val polygonPaths = HashMap<RoundedPolygon, Path>()
private val circleMorphs = HashMap<RoundedPolygon, Morph>()

private fun pathOf(shape: RoundedPolygon): Path = polygonPaths.getOrPut(shape) { shape.toPath().asComposePath() }

private const val SHAPE_EXTENT = 0.72f
private const val HALO_EXTENT = 0.84f
private const val GLYPH_EXTENT = 0.4f
private val ACCENTS = listOf(
    Triple(Accent.Blob, Offset(-0.32f, 0.3f), 0.14f),
    Triple(Accent.Dot, Offset(-0.4f, -0.18f), 0.05f),
    Triple(Accent.Ring, Offset(0.42f, 0.15f), 0.075f),
)

/** null_status.dart's EmptyIllustration: a shape, its halo, a glyph and three accents, with one of three entrances. */
@Composable
fun EmptyIllustration(type: NullStatusIllustration, dimension: Int) {
    val scheme = BoxTheme.scheme
    val progress = remember { Animatable(0f) }
    val entrance = remember { Entrance.entries.random() }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(900, easing = LinearEasing)) }
    val (shape, glyph) = artOf(type)
    val palette = paletteOf(scheme, type == NullStatusIllustration.Error)
    Canvas(Modifier.size(dimension.dp)) {
        val t = progress.value
        val side = size.minDimension
        val center = this.center
        val (halo, body, mark) = poseAt(entrance, t)
        paintShape(shape, halo, center, side * HALO_EXTENT, palette.highlight.copy(alpha = palette.highlight.alpha * 0.14f * halo.alpha))
        paintShape(shape, body, center, side * SHAPE_EXTENT, palette.shape)
        if (mark.scale > 0f) {
            val extent = side * GLYPH_EXTENT * mark.scale
            val at = center + Offset(0f, side * mark.rise)
            withTransform({
                translate(at.x - extent / 2, at.y - extent / 2)
                scale(extent / side, extent / side, pivot = Offset.Zero)
            }) {
                drawGlyph(glyph, mark.fill, palette.ink.copy(alpha = palette.ink.alpha * mark.alpha.coerceIn(0f, 1f)))
            }
        }
        ACCENTS.forEachIndexed { index, (accent, offset, extent) ->
            val pose = accentAt(entrance, t, index)
            if (pose.scale <= 0f) return@forEachIndexed
            val direction = atan2(offset.y, offset.x) + pose.orbit
            val distance = hypot(offset.x, offset.y)
            val at = center + Offset(cos(direction), sin(direction)) * distance * side * pose.drift
            val diameter = side * extent * pose.scale
            when (accent) {
                Accent.Blob -> paintPath(pathOf(M3Shapes.Cookie4Sided), at, diameter, (PI / 12).toFloat() + pose.turn, palette.accentContainer)
                Accent.Dot -> drawCircle(palette.accent, diameter / 2, at)
                Accent.Ring -> {
                    val width = diameter * 0.28f
                    drawCircle(palette.highlight, (diameter - width) / 2, at, style = Stroke(width))
                }
            }
        }
    }
}

private data class Poses(val halo: ShapePose, val body: ShapePose, val mark: GlyphPose)

private fun poseAt(entrance: Entrance, t: Float): Poses = when (entrance) {
    Entrance.Spin -> {
        fun spun(settle: Float) = ShapePose(lerp(0.6f, 1f, settle), (-PI / 3 * (1 - settle)).toFloat(), 1f, 1f)
        Poses(
            spun(phase(t, 0.08f, 0.75f, EaseOutBackCurve)),
            spun(phase(t, 0f, 0.65f, EaseOutBackCurve)),
            GlyphPose(
                lerp(0.5f, 1f, phase(t, 0.1f, 0.55f, EaseOutBackCurve)) + sin(PI * phase(t, 0.55f, 0.85f)).toFloat() * 0.08f,
                0f,
                phase(t, 0.2f, 0.6f, EaseInOutCubicCurve),
                phase(t, 0.1f, 0.3f),
            ),
        )
    }
    Entrance.Morph -> {
        fun morphed(begin: Float, end: Float): ShapePose {
            val morph = phase(t, begin, end, EaseInOutCubicCurve)
            return ShapePose(lerp(0.5f, 1f, phase(t, begin, begin + 0.5f, EaseOutBackCurve)), (PI / 2 * (1 - morph)).toFloat(), morph, 1f)
        }
        val rise = phase(t, 0.2f, 0.6f, EaseOutBackCurve)
        Poses(
            morphed(0.15f, 0.8f),
            morphed(0.05f, 0.65f),
            GlyphPose(lerp(0.8f, 1f, rise), 0.14f * (1 - rise), phase(t, 0.3f, 0.7f, EaseInOutCubicCurve), phase(t, 0.2f, 0.35f)),
        )
    }
    Entrance.Orbit -> {
        val ripple = phase(t, 0.2f, 0.75f, EaseOutCubicCurve)
        Poses(
            ShapePose(lerp(SHAPE_EXTENT / HALO_EXTENT, 1f, ripple), 0f, 1f, ripple),
            ShapePose(lerp(0.3f, 1f, phase(t, 0f, 0.6f, ElasticOut(0.6f))), 0f, 1f, 1f),
            GlyphPose(phase(t, 0.15f, 0.7f, ElasticOut(0.5f)), 0f, phase(t, 0.3f, 0.7f, EaseInOutCubicCurve), phase(t, 0.15f, 0.25f)),
        )
    }
}

private fun accentAt(entrance: Entrance, t: Float, index: Int): AccentPose = when (entrance) {
    Entrance.Spin -> {
        val begin = 0.4f + index * 0.08f
        val enter = phase(t, begin, begin + 0.35f, EaseOutBackCurve)
        AccentPose(enter, lerp(0.7f, 1f, enter), 0f, (-PI / 4 * (1 - enter)).toFloat())
    }
    Entrance.Morph -> {
        val begin = 0.45f + (ACCENTS.size - 1 - index) * 0.07f
        val enter = phase(t, begin, begin + 0.3f, EaseOutBackCurve)
        AccentPose(enter, 1f, 0f, (-PI / 2 * (1 - enter)).toFloat())
    }
    Entrance.Orbit -> {
        val begin = 0.3f + index * 0.1f
        val travel = phase(t, begin, begin + 0.4f, EaseOutCubicCurve)
        AccentPose(phase(t, begin, begin + 0.3f, EaseOutBackCurve), lerp(0.55f, 1f, travel), (-PI * 0.7 * (1 - travel)).toFloat(), (-PI / 2 * (1 - travel)).toFloat())
    }
}

private fun DrawScope.paintShape(shape: RoundedPolygon, pose: ShapePose, center: Offset, extent: Float, color: Color) {
    val path = if (pose.morph >= 1f) {
        pathOf(shape)
    } else {
        circleMorphs.getOrPut(shape) { Morph(M3Shapes.Circle, shape) }.toPath(pose.morph, android.graphics.Path()).asComposePath()
    }
    paintPath(path, center, extent * pose.scale, pose.turn, color)
}

private fun DrawScope.paintPath(path: Path, center: Offset, extent: Float, rotation: Float, color: Color) {
    withTransform({
        translate(center.x, center.y)
        rotate(Math.toDegrees(rotation.toDouble()).toFloat(), pivot = Offset.Zero)
        scale(extent, extent, pivot = Offset.Zero)
        translate(-0.5f, -0.5f)
    }) {
        drawPath(path, color)
    }
}

