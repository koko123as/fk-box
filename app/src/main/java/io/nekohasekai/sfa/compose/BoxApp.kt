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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import io.nekohasekai.sfa.compose.theme.ColorStyle
import io.nekohasekai.sfa.compose.theme.SurfaceLevel
import io.nekohasekai.sfa.compose.theme.Theme
import io.nekohasekai.sfa.compose.theme.appFontFamily
import io.nekohasekai.sfa.compose.theme.seededColorScheme
import io.nekohasekai.sfa.compose.ui.ErrorState
import io.nekohasekai.sfa.compose.ui.NavigationDock
import io.nekohasekai.sfa.compose.ui.NavigatorHost
import io.nekohasekai.sfa.compose.ui.rememberBoxNavigator
import io.nekohasekai.sfa.compose.ui.MessageBanner
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf

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
    val glassMode = prefs["liquidGlass"] as? String ?: "none"
    val glass = io.nekohasekai.sfa.compose.ui.LiquidGlassSettings(
        enabled = glassMode != "none",
        quality = if (glassMode == "ultra") io.nekohasekai.sfa.compose.ui.LiquidGlassQuality.Ultra else io.nekohasekai.sfa.compose.ui.LiquidGlassQuality.Standard,
    )
    androidx.compose.runtime.CompositionLocalProvider(io.nekohasekai.sfa.compose.ui.LocalLiquidGlass provides glass) {
    Theme(scheme = scheme, fontFamily = appFontFamily(prefs["fontFamily"]), textScale = appTextScale(prefs, systemScale)) {
        val snackbar = remember { SnackbarHostState() }
        var lastMessage by remember { mutableStateOf(BoxMessage("")) }
        val chinese = LocalConfiguration.current.locales.let { !it.isEmpty && it[0].language == "zh" }
        LaunchedEffect(box) {
            box.messages.collect { message ->
                lastMessage = message
                snackbar.showSnackbar(if (chinese) BoxStrings.zh[message.text] ?: message.text else message.text)
            }
        }
        val navigator = rememberBoxNavigator()
        Box(Modifier.fillMaxSize().background(SurfaceLevel.Page.colorOf(scheme))) {
            when {
                box.ready -> androidx.compose.runtime.CompositionLocalProvider(io.nekohasekai.sfa.compose.ui.LocalBoxController provides box) { NavigatorHost(navigator) { HomePage(box) } }
                box.startupError != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    ErrorState(box.startupError!!) { box.retryInitialize() }
                }
                else -> SplashScreen()
            }
            val bottom = if (navigator.pages.isEmpty()) NavigationDock.inset() else WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = bottom)) { data ->
                MessageBanner(data.visuals.message, lastMessage.error) { data.dismiss() }
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
