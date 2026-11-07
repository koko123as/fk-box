package io.nekohasekai.sfa.compose.ui

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import io.nekohasekai.sfa.compose.theme.LocalAppearance
import io.nekohasekai.sfa.compose.theme.LocalBoxScheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

// ---------------------------------------------------------------------------
// Press feedback: prism_ui 63450c0 press_surface.dart, card_surface.dart,
// press_region.dart and press_sparkle.dart (FlClash be84736 replaced every
// InkWell with these). One Indication replaces the Material ripple app-wide.
// ---------------------------------------------------------------------------

/** card_surface.dart PressLook: how a surface shows that it is held down. */
enum class PressLook {
    /** Ink over the fill while held and, on Android 13+, a sparkle from the press. */
    Ink,

    /** The whole surface tints, as iOS 27 tints a held button. */
    Tint,

    /** No press look; hover and the focus ring remain. */
    None,
}

// press_surface.dart: UIScrollView holds a touch this long before a cell highlights.
private const val REVEAL_DELAY_MS = 150L
private const val INK_FADE_MS = 200
private const val TINT_RELEASE_MS = 250
private const val INK_ALPHA = 0.1f

// As faint as the sidebar's hover: iOS shows none, a desktop pointer expects one.
private const val HOVER_ALPHA = 0.04f

// card_surface.dart: a held tint fades light surfaces and lifts dark ones.
private const val TINT_FADE = 0.25f
private const val TINT_LIFT = 0.2f

// surface.dart focusRingSide: 2 dp of primary, inside the shape.
private val FOCUS_RING_WIDTH = 2.dp

/**
 * press_surface.dart's PressSurface as an [IndicationNodeFactory]: ink revealed
 * [REVEAL_DELAY_MS] after the press and faded over 200 ms (alpha 0.10), a held
 * tint released over 250 ms, a 0.04 hover and a focus ring while a key moved
 * focus. Like CardSurface it paints the ink and sparkle under the content.
 */
@Stable
class PressIndication(
    val look: PressLook = PressLook.Ink,
    val shape: Shape = RectangleShape,
    val inkColor: Color = Color.Unspecified,
    val showsHover: Boolean = true,
) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = PressNode(interactionSource, this)

    override fun equals(other: Any?): Boolean = other is PressIndication && other.look == look && other.shape == shape &&
        other.inkColor == inkColor && other.showsHover == showsHover

    override fun hashCode(): Int = ((look.hashCode() * 31 + shape.hashCode()) * 31 + inkColor.hashCode()) * 31 + showsHover.hashCode()

    companion object {
        val Ink = PressIndication()
        val Tint = PressIndication(PressLook.Tint)
    }
}

/** The shared app indication: ink for anything clickable that names none. */
val DefaultPressIndication: PressIndication get() = PressIndication.Ink

private class PressNode(
    private val source: InteractionSource,
    private val spec: PressIndication,
) : Modifier.Node(), DrawModifierNode, CompositionLocalConsumerModifierNode {
    private val press = Animatable(0f)
    private val hover = Animatable(0f)
    private var focused = false
    private var held = 0
    private var reveal: Job? = null
    private val sparkles = mutableStateListOf<Sparkle>()

    override fun onAttach() {
        coroutineScope.launch { source.interactions.collect { onInteraction(it) } }
    }

    override fun onDetach() {
        sparkles.clear()
    }

    private fun onInteraction(interaction: Interaction) {
        when (interaction) {
            is PressInteraction.Press -> pressed(interaction.pressPosition)
            is PressInteraction.Release, is PressInteraction.Cancel -> released()
            is HoverInteraction.Enter -> coroutineScope.launch { hover.snapTo(1f) }
            is HoverInteraction.Exit -> coroutineScope.launch { hover.snapTo(0f) }
            is FocusInteraction.Focus -> { focused = true; invalidateDraw() }
            is FocusInteraction.Unfocus -> { focused = false; invalidateDraw() }
        }
    }

    /** The minimal style's press is a static dim: no spreading ink, no sparkle. */
    private val tokens get() = currentValueOf(LocalAppearance)

    private fun pressed(at: Offset) {
        held++
        val tokens = tokens
        when (spec.look) {
            PressLook.Ink -> {
                if (SPARKLES && tokens.sparkle) startSparkle(at)
                reveal?.cancel()
                reveal = coroutineScope.launch {
                    delay(REVEAL_DELAY_MS)
                    if (tokens.inkSpread) press.animateTo(1f, tween(INK_FADE_MS, easing = LinearEasing)) else press.snapTo(1f)
                }
            }
            // clickable already holds a press back inside a scrolling container.
            PressLook.Tint -> coroutineScope.launch { press.snapTo(1f) }
            PressLook.None -> Unit
        }
    }

    private fun released() {
        held = (held - 1).coerceAtLeast(0)
        if (held > 0) return
        reveal?.cancel()
        reveal = null
        if (press.value == 0f && !press.isRunning) return
        val spread = tokens.inkSpread
        coroutineScope.launch {
            if (!spread) {
                press.snapTo(0f)
            } else if (spec.look == PressLook.Ink) {
                press.animateTo(0f, tween(INK_FADE_MS, easing = LinearEasing))
            } else {
                press.animateTo(0f, tween(TINT_RELEASE_MS, easing = EaseInOut))
            }
        }
    }

    private fun startSparkle(at: Offset) {
        val sparkle = Sparkle(at, Random.nextFloat() * 1000f)
        sparkles.add(sparkle)
        coroutineScope.launch {
            sparkle.progress.animateTo(1f, tween(Sparkle.DURATION_MS, easing = LinearEasing))
            sparkles.remove(sparkle)
        }
    }

    override fun ContentDrawScope.draw() {
        val scheme = currentValueOf(LocalBoxScheme)
        val ink = if (spec.inkColor.isSpecified) spec.inkColor else scheme.onSurface
        val outline = spec.shape.createOutline(size, layoutDirection, this)
        val hovered = spec.showsHover && spec.look != PressLook.None && hover.value > 0f
        val pressed = press.value
        var overlay = Color.Transparent
        if (hovered) overlay = scheme.onSurface.copy(alpha = HOVER_ALPHA)
        if (spec.look == PressLook.Ink && pressed > 0f) overlay = compose(ink.copy(alpha = INK_ALPHA * pressed), overlay)
        if (overlay.alpha > 0f) drawOutline(outline, overlay)
        if (SPARKLES && sparkles.isNotEmpty()) {
            val path = Path().apply { addOutline(outline) }
            clipPath(path) { for (sparkle in sparkles) drawSparkle(sparkle, ink.copy(alpha = INK_ALPHA)) }
        }
        if (spec.look == PressLook.Tint && pressed > 0f) {
            val paint = Paint()
            if (scheme.dark) {
                val lift = TINT_LIFT * pressed
                val keep = 1 - lift
                val white = 255 * lift
                paint.colorFilter = ColorFilter.colorMatrix(
                    ColorMatrix(floatArrayOf(keep, 0f, 0f, 0f, white, 0f, keep, 0f, 0f, white, 0f, 0f, keep, 0f, white, 0f, 0f, 0f, 1f, 0f)),
                )
            } else {
                paint.alpha = 1 - TINT_FADE * pressed
            }
            drawIntoCanvas { canvas ->
                canvas.saveLayer(Rect(Offset.Zero, size), paint)
                drawContent()
                canvas.restore()
            }
        } else {
            drawContent()
        }
        if (focused && currentValueOf(LocalInputModeManager).inputMode == InputMode.Keyboard) {
            val half = FOCUS_RING_WIDTH.toPx() / 2
            inset(half) {
                drawOutline(spec.shape.createOutline(size, layoutDirection, this), scheme.primary, style = Stroke(FOCUS_RING_WIDTH.toPx()))
            }
        }
    }

    private fun DrawScope.drawSparkle(sparkle: Sparkle, color: Color) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val shader = sparkle.shader(size.width, size.height, color) ?: return
        drawRect(ShaderBrush(shader))
    }
}

private val SPARKLES = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

private fun compose(top: Color, bottom: Color): Color {
    if (bottom.alpha == 0f) return top
    val a = top.alpha + bottom.alpha * (1 - top.alpha)
    if (a == 0f) return Color.Transparent
    fun ch(t: Float, b: Float) = (t * top.alpha + b * bottom.alpha * (1 - top.alpha)) / a
    return Color(ch(top.red, bottom.red), ch(top.green, bottom.green), ch(top.blue, bottom.blue), a)
}

/**
 * press_sparkle.dart's _Sparkle: Flutter's InkSparkle over 617 ms, drawn by an
 * AGSL port of ink_sparkle.frag (Flutter, BSD-3-Clause) on Android 13+.
 */
private class Sparkle(val origin: Offset, val seed: Float) {
    val progress = Animatable(0f)
    private var runtime: Any? = null

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun shader(width: Float, height: Float, color: Color): RuntimeShader? {
        if (width <= 0f || height <= 0f) return null
        val shader = (runtime as? RuntimeShader) ?: RuntimeShader(INK_SPARKLE_AGSL).also { runtime = it }
        val t = progress.value
        val radiusScale = if (t < 0.75f) FastOutSlowInEasing.transform(t / 0.75f) else 1f
        val alpha = when {
            t < 0.13f -> t / 0.13f
            t < 0.40f -> 1f
            else -> 1 - (t - 0.40f) / 0.60f
        }
        val sparkleAlpha = when {
            t < 13f / 90 -> t / (13f / 90)
            t < 40f / 90 -> 1f
            else -> 1 - (t - 40f / 90) / (50f / 90)
        }
        val middle = Offset(width / 2, height / 2)
        val center = androidx.compose.ui.geometry.lerp(origin, middle, min(radiusScale * 2, 1f))
        val phase = seed + radiusScale
        val pi = Math.PI.toFloat()
        val rotations = floatArrayOf(phase * ROTATE_RIGHT + 1.7f * pi, phase * -ROTATE_RIGHT + 2 * pi, phase * ROTATE_RIGHT + 2.75f * pi)
        val scale = TURBULENCE_SCALE
        shader.setFloatUniform("u_color", color.red, color.green, color.blue, color.alpha)
        shader.setFloatUniform("u_composite_1", alpha.coerceIn(0f, 1f), sparkleAlpha.coerceIn(0f, 1f), 1f, radiusScale)
        shader.setFloatUniform("u_center", center.x, center.y)
        shader.setFloatUniform("u_max_radius", hypot(width, height) / 2 * RADIUS_MULTIPLIER)
        shader.setFloatUniform("u_resolution_scale", 1 / width, 1 / height)
        shader.setFloatUniform("u_noise_scale", NOISE_DENSITY / width, NOISE_DENSITY / height)
        shader.setFloatUniform("u_noise_phase", phase / 1000)
        shader.setFloatUniform("u_circle1", scale * 0.5f + phase * 0.01f * cos(scale * 0.55f), scale * 0.5f + phase * 0.01f * sin(scale * 0.55f))
        shader.setFloatUniform("u_circle2", scale * 0.2f + phase * -0.0066f * cos(scale * 0.45f), scale * 0.2f + phase * -0.0066f * sin(scale * 0.45f))
        shader.setFloatUniform("u_circle3", scale + phase * -0.0066f * cos(scale * 0.35f), scale + phase * -0.0066f * sin(scale * 0.35f))
        shader.setFloatUniform("u_rotation1", cos(rotations[0]), sin(rotations[0]))
        shader.setFloatUniform("u_rotation2", cos(rotations[1]), sin(rotations[1]))
        shader.setFloatUniform("u_rotation3", cos(rotations[2]), sin(rotations[2]))
        return shader
    }

    companion object {
        const val DURATION_MS = 617
        private const val RADIUS_MULTIPLIER = 2.3f
        private val ROTATE_RIGHT = (Math.PI * 0.0078125).toFloat()
        private const val NOISE_DENSITY = 2.1f
        private const val TURBULENCE_SCALE = 1.5f
    }
}

/**
 * press_region.dart's PressRegion state layer: [pressed], [focused] and
 * [hovered] fills under the content inside [shape], each faded in 50 ms.
 */
@Stable
class PressRegionIndication(
    val shape: Shape,
    val pressed: Color = Color.Transparent,
    val focused: Color = Color.Transparent,
    val hovered: Color = Color.Transparent,
) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = RegionNode(interactionSource, this)

    override fun equals(other: Any?): Boolean = other is PressRegionIndication && other.shape == shape && other.pressed == pressed &&
        other.focused == focused && other.hovered == hovered

    override fun hashCode(): Int = ((shape.hashCode() * 31 + pressed.hashCode()) * 31 + focused.hashCode()) * 31 + hovered.hashCode()
}

private const val OVERLAY_FADE_MS = 50

private class RegionNode(
    private val source: InteractionSource,
    private val spec: PressRegionIndication,
) : Modifier.Node(), DrawModifierNode, CompositionLocalConsumerModifierNode {
    private val presses = mutableIntStateOf(0)
    private val hovers = mutableIntStateOf(0)
    private val focuses = mutableIntStateOf(0)
    private var shown = Color.Transparent
    private val fade = Animatable(1f)
    private var from = Color.Transparent

    override fun onAttach() {
        coroutineScope.launch {
            source.interactions.collect {
                when (it) {
                    is PressInteraction.Press -> presses.intValue++
                    is PressInteraction.Release, is PressInteraction.Cancel -> presses.intValue = (presses.intValue - 1).coerceAtLeast(0)
                    is HoverInteraction.Enter -> hovers.intValue++
                    is HoverInteraction.Exit -> hovers.intValue = (hovers.intValue - 1).coerceAtLeast(0)
                    is FocusInteraction.Focus -> focuses.intValue++
                    is FocusInteraction.Unfocus -> focuses.intValue = (focuses.intValue - 1).coerceAtLeast(0)
                }
                retarget()
            }
        }
    }

    private fun target(): Color = when {
        presses.intValue > 0 && spec.pressed.alpha > 0f -> spec.pressed
        focuses.intValue > 0 && spec.focused.alpha > 0f && currentValueOf(LocalInputModeManager).inputMode == InputMode.Keyboard -> spec.focused
        hovers.intValue > 0 -> spec.hovered
        else -> Color.Transparent
    }

    private fun retarget() {
        val next = target()
        if (next == shown) return
        from = lerp(from, shown, fade.value)
        shown = next
        coroutineScope.launch {
            fade.snapTo(0f)
            fade.animateTo(1f, tween(OVERLAY_FADE_MS, easing = LinearEasing))
        }
    }

    override fun ContentDrawScope.draw() {
        val color = lerp(from, shown, fade.value)
        if (color.alpha > 0f) drawOutline(spec.shape.createOutline(size, layoutDirection, this), color)
        drawContent()
    }
}

/** sidebar.dart's _SidebarColors overlay: press 0.06, focus 0.10, hover 0.04 of onSurface. */
@Composable
fun sidebarPressIndication(shape: Shape): PressRegionIndication {
    val onSurface = LocalBoxScheme.current.onSurface
    return PressRegionIndication(shape, onSurface.copy(alpha = 0.06f), onSurface.copy(alpha = 0.1f), onSurface.copy(alpha = 0.04f))
}

/** press_region.dart PressRegion.focusOverlay: keyboard or remote focus only. */
@Composable
fun focusPressIndication(shape: Shape): PressRegionIndication {
    val dark = LocalBoxScheme.current.dark
    return PressRegionIndication(shape, focused = if (dark) Color(0x1FFFFFFF) else Color(0x1F000000))
}

// ink_sparkle.frag (Flutter Authors, BSD-3-Clause; via prism_ui shaders/) in AGSL.
private const val INK_SPARKLE_AGSL = """
uniform float4 u_color;
uniform float4 u_composite_1;
uniform float2 u_center;
uniform float u_max_radius;
uniform float2 u_resolution_scale;
uniform float2 u_noise_scale;
uniform float u_noise_phase;
uniform float2 u_circle1;
uniform float2 u_circle2;
uniform float2 u_circle3;
uniform float2 u_rotation1;
uniform float2 u_rotation2;
uniform float2 u_rotation3;

const float PI = 3.1415926535897932384626;
const float2 TURBULENCE_SCALE = float2(0.8);

float triangle_noise(float2 n) {
  n = fract(n * float2(5.3987, 5.4421));
  n += dot(n.yx, n.xy + float2(21.5351, 14.3137));
  float xy = n.x * n.y;
  return fract(xy * 95.4307) + fract(xy * 75.04961) - 1.0;
}

float threshold(float v, float l, float h) {
  return step(l, v) * (1.0 - step(h, v));
}

float2x2 rotate2d(float2 rad) {
  return float2x2(rad.x, -rad.y, rad.y, rad.x);
}

float soft_circle(float2 uv, float2 xy, float radius, float blur) {
  float blur_half = blur * 0.5;
  float d = distance(uv, xy);
  return 1.0 - smoothstep(1.0 - blur_half, 1.0 + blur_half, d / radius);
}

float soft_ring(float2 uv, float2 xy, float radius, float thickness, float blur) {
  float circle_outer = soft_circle(uv, xy, radius + thickness, blur);
  float circle_inner = soft_circle(uv, xy, max(radius - thickness, 0.0), blur);
  return clamp(circle_outer - circle_inner, 0.0, 1.0);
}

float circle_grid(float2 resolution, float2 p, float2 xy, float2 rotation, float cell_diameter) {
  p = rotate2d(rotation) * (xy - p) + xy;
  p = mod(p, cell_diameter) / resolution;
  float cell_uv = cell_diameter / resolution.y * 0.5;
  float r = 0.65 * cell_uv;
  return soft_circle(p, float2(cell_uv), r, r * 50.0);
}

float sparkle(float2 uv, float t) {
  float n = triangle_noise(uv);
  float s = threshold(n, 0.0, 0.05);
  s += threshold(n + sin(PI * (t + 0.35)), 0.1, 0.15);
  s += threshold(n + sin(PI * (t + 0.7)), 0.2, 0.25);
  s += threshold(n + sin(PI * (t + 1.05)), 0.3, 0.35);
  return clamp(s, 0.0, 1.0) * 0.55;
}

float turbulence(float2 uv) {
  float2 uv_scale = uv * TURBULENCE_SCALE;
  float g1 = circle_grid(TURBULENCE_SCALE, uv_scale, u_circle1, u_rotation1, 0.17);
  float g2 = circle_grid(TURBULENCE_SCALE, uv_scale, u_circle2, u_rotation2, 0.2);
  float g3 = circle_grid(TURBULENCE_SCALE, uv_scale, u_circle3, u_rotation3, 0.275);
  float v = (g1 * g1 + g2 - g3) * 0.5;
  return clamp(0.45 + 0.8 * v, 0.0, 1.0);
}

half4 main(float2 p) {
  float u_alpha = u_composite_1.x;
  float u_sparkle_alpha = u_composite_1.y;
  float u_blur = u_composite_1.z;
  float u_radius_scale = u_composite_1.w;
  float2 uv = p * u_resolution_scale;
  float2 density_uv = uv - mod(p, u_noise_scale);
  float radius = u_max_radius * u_radius_scale;
  float turb = turbulence(uv);
  float ring = soft_ring(p, u_center, radius, 0.05 * u_max_radius, u_blur);
  float spark = sparkle(density_uv, u_noise_phase) * ring * turb * u_sparkle_alpha;
  float wave_alpha = soft_circle(p, u_center, radius, u_blur) * u_alpha * u_color.a;
  float4 wave_color = float4(u_color.rgb * wave_alpha, wave_alpha);
  return half4(mix(wave_color, float4(1.0), spark));
}
"""

/** A Material button's press as a tint (common_button.dart PressLook.tint); its ripple is off app-wide. */
fun Modifier.tintPress(interaction: InteractionSource, shape: Shape = androidx.compose.foundation.shape.CircleShape): Modifier =
    indication(interaction, PressIndication(PressLook.Tint, shape))

/** press_region.dart PressRegion.hoverOverlay: hover ([hover] replaces it) and keyboard focus; the press is the owner's. */
@Composable
fun hoverPressIndication(shape: Shape, hover: Color? = null): PressRegionIndication {
    val dark = LocalBoxScheme.current.dark
    return PressRegionIndication(
        shape,
        focused = if (dark) Color(0x1FFFFFFF) else Color(0x1F000000),
        hovered = hover ?: if (dark) Color(0x0AFFFFFF) else Color(0x0A000000),
    )
}
