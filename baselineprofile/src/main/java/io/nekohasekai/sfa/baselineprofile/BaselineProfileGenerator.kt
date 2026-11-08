package io.nekohasekai.sfa.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Collects the app's Baseline Profile: cold start, then Dashboard → Proxies →
 * Tools → Logs, the path most sessions take. Run on an ARM64 device or
 * emulator image (libbox is arm64-only) with API 33+, then copy the result
 * into app/src/main/baseline-prof.txt:
 *
 *   ./gradlew -PbaselineProfile=true -Parm64Only=true \
 *     :baselineprofile:connectedOtherBenchmarkAndroidTest
 *   # baselineprofile/build/outputs/connected_android_test_additional_output/
 *   #   …/BaselineProfileGenerator_generate-baseline-prof.txt
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
        device.wait(Until.hasObject(By.text("Dashboard")), TIMEOUT)
        for (destination in listOf("Proxies", "Profiles", "Tools", "Dashboard")) {
            device.findObject(By.text(destination))?.click() ?: continue
            device.waitForIdle()
        }
        device.findObject(By.text("Tools"))?.click()
        device.waitForIdle()
        device.findObject(By.text("Logs"))?.let {
            it.click()
            device.waitForIdle()
            device.pressBack()
        }
    }

    private companion object {
        const val PACKAGE = "io.fkbox.app"
        const val TIMEOUT = 10_000L
    }
}
