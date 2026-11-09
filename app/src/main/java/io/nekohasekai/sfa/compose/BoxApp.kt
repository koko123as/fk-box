package io.nekohasekai.sfa.compose

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.pages.HomePage
import io.nekohasekai.sfa.compose.theme.ColorStyle
import io.nekohasekai.sfa.compose.theme.Theme
import io.nekohasekai.sfa.compose.theme.appFontFamily
import io.nekohasekai.sfa.compose.theme.seededColorScheme
import io.nekohasekai.sfa.compose.ui.ErrorState
import io.nekohasekai.sfa.compose.ui.LocalBoxController
import io.nekohasekai.sfa.compose.ui.NavigatorHost
import io.nekohasekai.sfa.compose.ui.rememberBoxNavigator

/** The custom or system text scale, kept within 0.8–1.4. */
fun appTextScale(prefs: Json, systemScale: Float): Float {
    val custom = prefs["customFontScale"] as? Boolean ?: prefs.containsKey("fontScale")
    val scale = if (custom) (prefs["fontScale"] as? Number)?.toFloat() ?: 1f else systemScale
    return scale.coerceIn(.8f, 1.4f)
}

/** The app: theme from the appearance preferences, splash, error and home, with snackbars for messages. */
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
    Theme(scheme = scheme, fontFamily = appFontFamily(prefs["fontFamily"]), textScale = appTextScale(prefs, systemScale)) {
        val snackbars = remember { SnackbarHostState() }
        val chinese = LocalConfiguration.current.locales.let { !it.isEmpty && it[0].language == "zh" }
        LaunchedEffect(box) {
            box.messages.collect { message ->
                val text = if (chinese) BoxStrings.zh[message.text] ?: message.text else message.text
                snackbars.currentSnackbarData?.dismiss()
                snackbars.showSnackbar(text, duration = if (message.error) SnackbarDuration.Long else SnackbarDuration.Short)
            }
        }
        val navigator = rememberBoxNavigator()
        Box(Modifier.fillMaxSize().background(scheme.surface)) {
            when {
                box.ready -> CompositionLocalProvider(LocalBoxController provides box) { NavigatorHost(navigator) { HomePage(box) } }
                box.startupError != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    ErrorState(box.startupError!!) { box.retryInitialize() }
                }
                else -> SplashScreen()
            }
            SnackbarHost(snackbars, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 80.dp)) { data ->
                Snackbar(data)
            }
        }
    }
}

@Composable
private fun SplashScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(painterResource(R.drawable.box_icon), contentDescription = null, modifier = Modifier.size(96.dp))
            Spacer(Modifier.height(16.dp))
            Text("FK-box", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(32.dp))
            LinearProgressIndicator(Modifier.width(120.dp))
        }
    }
}
