package io.nekohasekai.sfa.baselineprofile

import android.content.res.Resources
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.UiDevice
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Collects the app's Baseline Profile: cold start, then Dashboard → Proxies →
 * Profiles → Tools → Dashboard, the path most sessions take. Run on an ARM64
 * device or emulator image (libbox is arm64-only) with API 33+, then copy the
 * result into app/src/main/baseline-prof.txt:
 *
 *   ./gradlew -PbaselineProfile=true -Parm64Only=true \
 *     :baselineprofile:connectedOtherBenchmarkAndroidTest
 *   # baselineprofile/build/outputs/connected_android_test_additional_output/
 *   #   …/BaselineProfileGenerator_generate-baseline-prof.txt
 *
 * The Rust + Slint UI draws its own widgets and has no accessibility tree, so
 * UIAutomator cannot find "Dashboard" or "Tools" by text. The destinations are
 * tapped by position instead: on a phone (narrower than 600 dp) the floating
 * navigation bar (ui-slint/ui/globals.slint: 64 dp high, 12 dp margin, above the
 * system navigation bar) holds four evenly spaced destinations; on wider
 * windows the rail (88 dp wide) lists them from the top.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = PACKAGE, includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()
        device.waitForIdle()
        Thread.sleep(SETTLE_MS)
        // Proxies, Profiles, Tools, then back to Dashboard.
        for (index in listOf(1, 2, 3, 0)) {
            tapDestination(device, index)
            device.waitForIdle()
            Thread.sleep(SETTLE_MS)
            // Scroll the page once, the other path a session takes.
            val width = device.displayWidth
            val height = device.displayHeight
            device.swipe(width / 2, height * 2 / 3, width / 2, height / 3, 20)
            device.waitForIdle()
        }
    }

    private fun tapDestination(device: UiDevice, index: Int) {
        val density = Resources.getSystem().displayMetrics.density
        val widthDp = device.displayWidth / density
        if (widthDp < PHONE_MAX_WIDTH_DP) {
            val navigationBar = systemDimension("navigation_bar_height")
            val barCenter = device.displayHeight - navigationBar - ((BAR_MARGIN_DP + BAR_HEIGHT_DP / 2) * density).toInt()
            val margin = (BAR_MARGIN_DP * density).toInt()
            val slot = (device.displayWidth - 2 * margin) / PHONE_DESTINATIONS
            device.click(margin + slot * index + slot / 2, barCenter)
        } else {
            val statusBar = systemDimension("status_bar_height")
            // The rail: app icon row (56 dp + 12 dp padding), then 56 dp per destination.
            val y = statusBar + ((12 + 56 + 8 + 56 * index + 28) * density).toInt()
            device.click((RAIL_WIDTH_DP / 2 * density).toInt(), y)
        }
    }

    private fun systemDimension(name: String): Int {
        val resources = Resources.getSystem()
        val id = resources.getIdentifier(name, "dimen", "android")
        return if (id != 0) resources.getDimensionPixelSize(id) else 0
    }

    private companion object {
        const val PACKAGE = "io.fkbox.app"
        const val SETTLE_MS = 1_500L
        const val PHONE_MAX_WIDTH_DP = 600f
        const val PHONE_DESTINATIONS = 4
        const val BAR_HEIGHT_DP = 64f
        const val BAR_MARGIN_DP = 12f
        const val RAIL_WIDTH_DP = 88f
    }
}
