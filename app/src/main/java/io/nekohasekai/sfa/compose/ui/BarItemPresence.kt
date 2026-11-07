package io.nekohasekai.sfa.compose.ui

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer

/*
 * prism_ui 63450c0 lib/bar_item_presence.dart, as used by FlClash v0.9.3
 * be84736, GPL-3.0: SwiftUI's toolbar springs an item in from 1.2 and a blur
 * of 10 a frame after its page first moves (0.416 s, bounce 0.25), and swells
 * a button whose glyph changes (up on 0.292 s, down on 0.416 s, 20%).
 */

private val PRESENCE_SPRING = durationSpring(416, 0.25f)
private const val ABSENT_SCALE = 1.2f
private const val ABSENT_BLUR = 10f
private const val PULSE_SWELL = 0.2f
private const val PULSE_LENGTH_MS = 900
private const val PULSE_ACROSS_LAG = 0.142f
private const val PULSE_DOWN_LAG = 0.113f

/** _pulseAt: the swell across and down at [progress] of the pulse. */
private fun pulseAt(progress: Float): Pair<Float, Float> {
    val time = progress * PULSE_LENGTH_MS / 1000f
    val up = springStepAt(time, 292, 0.5f)
    val across = 1 + PULSE_SWELL * (up - springStepAt(time - PULSE_ACROSS_LAG, 416, 0.5f))
    val down = 1 + PULSE_SWELL * (up - springStepAt(time - PULSE_DOWN_LAG, 416, 0.416f))
    return across to down
}

/** BarItemPresence: the item springs in when it first shows, and swells when [glyph] changes. */
@Composable
fun Modifier.barItemPresence(glyph: Any? = null): Modifier {
    val tokens = io.nekohasekai.sfa.compose.theme.LocalAppearance.current
    // The minimal style fades a toolbar item in briefly; no scale, blur or swell.
    val still = !tokens.expressiveMotion
    val presence = remember { Animatable(0f) }
    val pulse = remember { Animatable(1f) }
    val last = remember { arrayOf(glyph) }
    LaunchedEffect(Unit) { presence.animateTo(1f, if (still) tokens.fade<Float>() else PRESENCE_SPRING) }
    LaunchedEffect(glyph) {
        if (last[0] != glyph) {
            last[0] = glyph
            if (still) return@LaunchedEffect
            pulse.snapTo(0f)
            pulse.animateTo(1f, tween(PULSE_LENGTH_MS, easing = LinearEasing))
        }
    }
    if (still) return graphicsLayer { alpha = presence.value.coerceIn(0f, 1f) }
    return graphicsLayer {
        val shown = presence.value
        val (across, down) = if (pulse.value < 1f) pulseAt(pulse.value) else 1f to 1f
        val scale = ABSENT_SCALE - (ABSENT_SCALE - 1) * shown
        scaleX = scale * across
        scaleY = scale * down
        alpha = shown.coerceIn(0f, 1f)
        val blur = ABSENT_BLUR * (1 - shown).coerceIn(0f, 1f) * density
        renderEffect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && blur > 0.5f) BlurEffect(blur, blur, TileMode.Decal) else null
    }
}
