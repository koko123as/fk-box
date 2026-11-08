package io.nekohasekai.sfa.compose.ui

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import io.nekohasekai.sfa.compose.theme.BoxScheme
import io.nekohasekai.sfa.compose.theme.shiftTone

/*
 * flutter/lib/ui/liquid_glass.dart (FlClash UI kit, GPL-3.0): panes of glass
 * after iOS's Liquid Glass. Flutter captures what lies behind every pane into
 * an atlas; here a [GlassBackdrop] records the content it stands over into a
 * graphics layer, and each pane draws that layer under a RuntimeShader
 * (LIQUID_GLASS_AGSL) as a RenderEffect. RuntimeShader needs Android 13; below
 * it a pane falls back to its tint over the content, as the Flutter app does
 * without Impeller's shader filters.
 */

enum class LiquidGlassQuality { Standard, Ultra }

/** LiquidGlassScope: whether the dock and the popups are glass, and how fine. */
@Immutable
data class LiquidGlassSettings(val enabled: Boolean, val quality: LiquidGlassQuality = LiquidGlassQuality.Ultra) {
    val shaded: Boolean get() = enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
}

val LocalLiquidGlass = compositionLocalOf { LiquidGlassSettings(false) }

/** Lengths are dp, as LiquidGlassStyle's. */
@Immutable
data class LiquidGlassStyle(
    val tint: Color,
    val contrast: Float = 0f,
    val blur: Float = 2.5f,
    val haze: Float = 0f,
    val hazeBlur: Float = 0f,
    val refraction: Float = 60f,
    val bevel: Float = 20f,
    val saturation: Float = 1.5f,
    val brightness: Float = 1f,
    val rim: Float = 0.6f,
    val rimWidth: Float = 1.3f,
    val rimTail: Float = 0f,
    val rimFocus: Float = 0f,
    val rimAdded: Float = 0f,
    val endShade: Float = 0f,
    val dispersion: Float = 0f,
    val rimBlur: Float = 0f,
    val hairline: Float = 0f,
    val hairlineSide: Float? = null,
) {
    /** Eases in from bare air, as glass materializes by bending, not fading. */
    fun scale(t: Float): LiquidGlassStyle {
        val amount = t.coerceIn(0f, 1f)
        return copy(
            tint = tint.copy(alpha = tint.alpha * amount),
            contrast = contrast * amount,
            blur = blur * amount,
            haze = haze * amount,
            refraction = refraction * amount,
            saturation = 1 + (saturation - 1) * amount,
            brightness = 1 + (brightness - 1) * amount,
            rim = rim * amount,
            endShade = endShade * amount,
            dispersion = dispersion * amount,
            rimBlur = rimBlur * amount,
            hairline = hairline * amount,
            hairlineSide = hairlineSide?.let { it * amount },
        )
    }

    companion object {
        private fun darkTint(scheme: BoxScheme) = scheme.surfaceContainer.shiftTone(7.0)

        fun pill(scheme: BoxScheme) = if (scheme.dark) {
            LiquidGlassStyle(
                tint = darkTint(scheme).copy(alpha = .48f), contrast = -.4f, blur = 0f, haze = .72f, hazeBlur = 15.6f,
                saturation = 1.925f, rim = .35f,
            )
        } else {
            LiquidGlassStyle(
                tint = Color(0x93F4F4F4), contrast = .4f, blur = 0f, haze = .72f, hazeBlur = 15.6f,
                saturation = 2.1f, hairline = .35f,
            )
        }

        fun menu(scheme: BoxScheme) = if (scheme.dark) {
            LiquidGlassStyle(tint = darkTint(scheme).copy(alpha = .63f), contrast = -.53f, blur = 12f, saturation = 1.75f, brightness = 1.8f, rim = 0f)
        } else {
            LiquidGlassStyle(tint = scheme.surfaceBright.copy(alpha = .55f), blur = 12f, saturation = 2f, brightness = 1.1f, rim = 0f)
        }

        fun alert(scheme: BoxScheme) = menu(scheme).copy(blur = 16f)

        fun lens(scheme: BoxScheme) = LiquidGlassStyle(
            tint = Color.Transparent, blur = 0f, refraction = -4.3f, bevel = 5.5f, saturation = 1f,
            brightness = if (scheme.dark) 1.33f else 1f, rim = if (scheme.dark) .3f else .6f, rimWidth = 1.1f,
            endShade = .045f, dispersion = 6.5f, rimBlur = 1.5f, hairline = .42f,
        )

    }
}

/** Light a press casts on a pane, in dp from its top left. */
@Immutable
data class LiquidGlassGlow(val center: Offset, val reach: Float, val strength: Float, val wash: Float = 0f) {
    companion object {
        val None = LiquidGlassGlow(Offset.Zero, 1f, 0f)
    }
}

/** What panes over it bend: the content of the box [glassBackdropSource] marks. */
class GlassBackdrop internal constructor(internal val layer: GraphicsLayer) {
    internal var origin by mutableStateOf(Offset.Zero)
    internal var recorded by mutableStateOf(false)
}

@Composable
fun rememberGlassBackdrop(): GlassBackdrop {
    val layer = rememberGraphicsLayer()
    return remember(layer) { GlassBackdrop(layer) }
}

/** The backdrop panes read where none is passed: the page under the dock and popups. */
val LocalGlassBackdrop = staticCompositionLocalOf<GlassBackdrop?> { null }

/** Records this box's content as [backdrop], for panes outside it to bend. */
fun Modifier.glassBackdropSource(backdrop: GlassBackdrop?, enabled: Boolean = true): Modifier =
    if (backdrop == null || !enabled) {
        this
    } else {
        onGloballyPositioned { backdrop.origin = it.positionInRoot() }
            .drawWithContent {
                backdrop.layer.record(size = IntSize(size.width.toInt(), size.height.toInt())) { this@drawWithContent.drawContent() }
                backdrop.recorded = true
                drawLayer(backdrop.layer)
            }
    }

/**
 * A pane of glass behind [content], with corners of [cornerRadius] dp or a
 * pill when null. Off, or below Android 13, it paints [fallback] instead.
 */
@Composable
fun LiquidGlassPane(
    style: LiquidGlassStyle,
    modifier: Modifier = Modifier,
    cornerRadius: Float? = null,
    backdrop: GlassBackdrop? = LocalGlassBackdrop.current,
    glow: () -> LiquidGlassGlow = { LiquidGlassGlow.None },
    fallback: Color = Color.Transparent,
    content: @Composable BoxScope.() -> Unit = {},
) = LiquidGlassPane({ style }, modifier, cornerRadius, backdrop, glow, fallback, content)

/** As above, with a style that may change every frame without recomposing. */
@Composable
fun LiquidGlassPane(
    style: () -> LiquidGlassStyle,
    modifier: Modifier = Modifier,
    cornerRadius: Float? = null,
    backdrop: GlassBackdrop? = LocalGlassBackdrop.current,
    glow: () -> LiquidGlassGlow = { LiquidGlassGlow.None },
    fallback: Color = Color.Transparent,
    content: @Composable BoxScope.() -> Unit = {},
) {
    Box(modifier) {
        // The minimal style never runs the glass shader, whoever asks for a pane.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && backdrop != null && io.nekohasekai.sfa.compose.theme.LocalAppearance.current.glass) {
            GlassLayer(style, cornerRadius, backdrop, glow, Modifier.matchParentSize())
        } else {
            val shape = if (cornerRadius == null) io.nekohasekai.sfa.compose.theme.AppShape.full else androidx.compose.foundation.shape.RoundedCornerShape(androidx.compose.ui.unit.Dp(cornerRadius))
            Box(Modifier.matchParentSize().drawBehind {
                val outline = shape.createOutline(size, layoutDirection, this)
                val tint = style().tint
                drawOutline(outline, if (fallback.alpha > 0f) fallback else tint.copy(alpha = kotlin.math.max(tint.alpha, .9f)))
            })
        }
        content()
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun GlassLayer(
    styleOf: () -> LiquidGlassStyle,
    cornerRadius: Float?,
    backdrop: GlassBackdrop,
    glow: () -> LiquidGlassGlow,
    modifier: Modifier,
) {
    val shader = remember { RuntimeShader(LIQUID_GLASS_AGSL) }
    val effect = remember { GlassEffectCache() }
    val haze = rememberGraphicsLayer()
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier
            .onGloballyPositioned { origin = it.positionInRoot() }
            // Twice as tall: the backdrop on top, its haze below, as the atlas holds both.
            .layout { measurable, constraints ->
                val placeable = measurable.measure(Constraints.fixed(constraints.maxWidth, constraints.maxHeight * 2))
                layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(0, 0) }
            }
            .graphicsLayer {
                val d = density
                val w = size.width
                val h = size.height / 2
                if (w <= 0f || h <= 0f) return@graphicsLayer
                val g = glow()
                val style = styleOf()
                val radius = cornerRadius ?: 1e4f
                val hazeAmount = if (style.hazeBlur > 0f) style.haze else 0f
                // Every value the uniforms below are derived from. While they
                // are unchanged the effect built last time is reused: same
                // shader, same uniforms, so the same pixels, without a new
                // RenderEffect on every layer pass.
                val inputs = floatArrayOf(
                    d, w, h, radius, hazeAmount,
                    style.bevel, style.blur, style.refraction, style.saturation, style.brightness,
                    style.tint.red, style.tint.green, style.tint.blue, style.tint.alpha,
                    style.rim, style.rimWidth, style.endShade,
                    g.center.x, g.center.y, g.strength, style.contrast, g.reach, g.wash,
                    style.hairline, style.dispersion, style.rimBlur,
                    style.rimTail, style.rimFocus, style.rimAdded, style.hairlineSide ?: style.hairline,
                )
                val cached = effect.effect
                if (cached != null && inputs.contentEquals(effect.inputs)) {
                    renderEffect = cached
                    return@graphicsLayer
                }
                shader.setFloatUniform("uSize", w, h * 2)
                shader.setFloatUniform("uFrame", 0f, 0f, 1 / d, 1 / d)
                shader.setFloatUniform("uSample", 0f, 0f, 1 / d, 1 / d)
                shader.setFloatUniform("uClamp", .5f, .5f, w - .5f, h - .5f)
                shader.setFloatUniform("uTexels", 1 / d, 0f)
                shader.setFloatUniform("uHazeSample", 0f, -h / d, 1 / d, 1 / d)
                shader.setFloatUniform("uHazeClamp", .5f, h + .5f, w - .5f, h * 2 - .5f)
                shader.setFloatUniform("uHaze", hazeAmount)
                shader.setFloatUniform("uShape", w / d, h / d, style.bevel, style.blur)
                shader.setFloatUniform("uCorners", radius, radius, radius, radius)
                shader.setFloatUniform("uOptics", style.refraction, style.saturation, style.brightness)
                shader.setFloatUniform("uTint", style.tint.red, style.tint.green, style.tint.blue, style.tint.alpha)
                shader.setFloatUniform("uLight", style.rim, style.rimWidth, style.endShade)
                shader.setFloatUniform("uGlow", g.center.x, g.center.y, g.strength, style.contrast)
                shader.setFloatUniform("uGlowSpread", g.reach, g.wash)
                shader.setFloatUniform("uEdge", style.hairline, style.dispersion, style.rimBlur)
                shader.setFloatUniform("uRim", style.rimTail, style.rimFocus, style.rimAdded, style.hairlineSide ?: style.hairline)
                val created = RenderEffect.createRuntimeShaderEffect(shader, "uTexture").asComposeRenderEffect()
                effect.inputs = inputs
                effect.effect = created
                renderEffect = created
            }
            .drawBehind {
                if (!backdrop.recorded) return@drawBehind
                val style = styleOf()
                val h = size.height / 2
                val shift = origin - backdrop.origin
                clipRect(0f, 0f, size.width, h) {
                    translate(-shift.x, -shift.y) { drawLayer(backdrop.layer) }
                }
                if (style.haze > 0f && style.hazeBlur > 0f && GlassGpu.reducedHaze) {
                    // MediaTek (Mali/Immortalis/PowerVR): the heavily blurred
                    // haze is rendered at half resolution and scaled back up,
                    // a quarter of the offscreen target and blur work.
                    val sigma = style.hazeBlur * density * HAZE_SCALE
                    haze.renderEffect = BlurEffect(sigma, sigma, TileMode.Clamp)
                    haze.clip = true
                    val width = kotlin.math.ceil(size.width * HAZE_SCALE).toInt().coerceAtLeast(1)
                    val height = kotlin.math.ceil(h * HAZE_SCALE).toInt().coerceAtLeast(1)
                    haze.record(size = IntSize(width, height)) {
                        scale(HAZE_SCALE, HAZE_SCALE, pivot = Offset.Zero) {
                            translate(-shift.x, -shift.y) { drawLayer(backdrop.layer) }
                        }
                    }
                    translate(0f, h) { scale(1 / HAZE_SCALE, 1 / HAZE_SCALE, pivot = Offset.Zero) { drawLayer(haze) } }
                } else if (style.haze > 0f && style.hazeBlur > 0f) {
                    val sigma = style.hazeBlur * density
                    haze.renderEffect = BlurEffect(sigma, sigma, TileMode.Clamp)
                    haze.clip = true
                    haze.record(size = IntSize(size.width.toInt(), h.toInt())) {
                        translate(-shift.x, -shift.y) { drawLayer(backdrop.layer) }
                    }
                    translate(0f, h) { drawLayer(haze) }
                }
            },
    )
}

private const val HAZE_SCALE = .5f

/** The last glass RenderEffect of one pane and the uniform inputs it was built from. */
private class GlassEffectCache {
    var inputs: FloatArray? = null
    var effect: androidx.compose.ui.graphics.RenderEffect? = null
}

/**
 * GPU-specific Liquid Glass tuning. Only MediaTek SoCs (Dimensity/Helio:
 * Arm Mali/Immortalis or Imagination PowerVR GPUs, whose drivers allocate
 * offscreen blur targets in full and report them as app graphics memory) take
 * the cheaper paths; every other GPU renders exactly as before.
 */
internal object GlassGpu {
    val reducedHaze: Boolean by lazy {
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MANUFACTURER else ""
        soc.equals("Mediatek", ignoreCase = true) || soc.equals("MTK", ignoreCase = true) ||
            Regex("^mt\\d", RegexOption.IGNORE_CASE).containsMatchIn(Build.HARDWARE.orEmpty())
    }
}
