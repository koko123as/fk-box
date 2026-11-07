package io.nekohasekai.sfa.compose

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.pages.HomePage
import io.nekohasekai.sfa.compose.theme.AppearanceStyle
import io.nekohasekai.sfa.compose.theme.AppearanceTokens
import io.nekohasekai.sfa.compose.theme.ColorStyle
import io.nekohasekai.sfa.compose.theme.ProvideAppearance
import io.nekohasekai.sfa.compose.theme.SurfaceLevel
import io.nekohasekai.sfa.compose.theme.Theme
import io.nekohasekai.sfa.compose.theme.appFontFamily
import io.nekohasekai.sfa.compose.theme.seededColorScheme
import io.nekohasekai.sfa.compose.ui.ErrorState
import io.nekohasekai.sfa.compose.ui.NavigatorHost
import io.nekohasekai.sfa.compose.ui.rememberBoxNavigator
import io.nekohasekai.sfa.compose.ui.BannerHost
import io.nekohasekai.sfa.compose.ui.BannerLevel
import io.nekohasekai.sfa.compose.ui.EditMenuHost
import io.nekohasekai.sfa.compose.ui.ViewMode
import io.nekohasekai.sfa.compose.ui.rememberBannerHostState
import io.nekohasekai.sfa.compose.ui.viewModeOf

/** theme_settings.dart's appTextScale: the custom or system scale, kept within 0.8–1.4. */
fun appTextScale(prefs: Json, systemScale: Float): Float {
    val custom = prefs["customFontScale"] as? Boolean ?: prefs.containsKey("fontScale")
    val scale = if (custom) (prefs["fontScale"] as? Number)?.toFloat() ?: 1f else systemScale
    return scale.coerceIn(.8f, 1.4f)
}

/** main.dart's BoxApp: theme from the appearance preferences, splash, error and home. */
@Composable
fun BoxApp(box: BoxController) {
    val prefs = box.preferences
    val dark = when (prefs["theme"]) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val accent = if (prefs["systemColor"] == true) box.systemAccentColor else (prefs["color"] as? Number)?.toLong()?.toInt() ?: 0xff007aff.toInt()
    val scheme = seededColorScheme(accent, dark, ColorStyle.of(prefs["colorStyle"])).toPureBlack(prefs["pureBlack"] == true)
    val systemScale = LocalConfiguration.current.fontScale
    // main.dart's LiquidGlassScope: glass for the dock, popups and alerts; RuntimeShader needs Android 13.
    val appearance = AppearanceTokens.of(AppearanceStyle.of(prefs["appearanceStyle"]))
    val glassMode = prefs["liquidGlass"] as? String ?: "none"
    // The minimal style draws flat surfaces and never runs the glass shaders.
    val glass = io.nekohasekai.sfa.compose.ui.LiquidGlassSettings(
        enabled = appearance.glass && glassMode != "none",
        quality = if (glassMode == "ultra") io.nekohasekai.sfa.compose.ui.LiquidGlassQuality.Ultra else io.nekohasekai.sfa.compose.ui.LiquidGlassQuality.Standard,
    )
    ProvideAppearance(appearance) {
    androidx.compose.runtime.CompositionLocalProvider(io.nekohasekai.sfa.compose.ui.LocalLiquidGlass provides glass) {
    Theme(scheme = scheme, fontFamily = appFontFamily(prefs["fontFamily"]), textScale = appTextScale(prefs, systemScale)) {
        val banners = rememberBannerHostState()
        val chinese = LocalConfiguration.current.locales.let { !it.isEmpty && it[0].language == "zh" }
        LaunchedEffect(box) {
            box.messages.collect { message ->
                banners.show(
                    if (chinese) BoxStrings.zh[message.text] ?: message.text else message.text,
                    if (message.error) BannerLevel.Error else BannerLevel.Info,
                )
            }
        }
        val navigator = rememberBoxNavigator()
        // prism_ui app.dart: banners stack when not mobile; the edit menu covers every text widget.
        val stacked = viewModeOf(LocalConfiguration.current.screenWidthDp.dp) != ViewMode.Mobile
        EditMenuHost {
            Box(Modifier.fillMaxSize().background(SurfaceLevel.Page.colorOf(scheme))) {
                when {
                    box.ready -> androidx.compose.runtime.CompositionLocalProvider(io.nekohasekai.sfa.compose.ui.LocalBoxController provides box) { NavigatorHost(navigator) { HomePage(box) } }
                    box.startupError != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        ErrorState(box.startupError!!) { box.retryInitialize() }
                    }
                    else -> SplashScreen()
                }
                BannerHost(banners, stacked = stacked)
            }
        }
    }
    }
    }
}

@Composable
private fun SplashScreen() {
    val opacity = remember { Animatable(0f) }
    LaunchedEffect(Unit) { opacity.animateTo(1f, tween(600, easing = CubicBezierEasing(0.42f, 0f, 1f, 1f))) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.graphicsLayer { alpha = opacity.value }, horizontalAlignment = Alignment.CenterHorizontally) {
            Image(painterResource(R.drawable.box_icon), contentDescription = null, modifier = Modifier.size(96.dp))
            Spacer(Modifier.height(16.dp))
            Text("FK-box", fontSize = 24.sp, fontWeight = FontWeight.W600)
            Spacer(Modifier.height(32.dp))
            LinearProgressIndicator(Modifier.width(120.dp))
        }
    }
}
