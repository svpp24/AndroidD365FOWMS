package com.d365fo.mobileops.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import java.util.regex.Pattern

/**
 * The steps the benchmarks share, written against what the app shows on screen so they work
 * against an unmodified build. Each wait fails the run loudly rather than measuring a screen
 * that never arrived - a benchmark that quietly times a spinner is worse than none.
 */
internal object Journeys {
    /**
     * The macrobench build: unconfigured, demo-ready (see app/build.gradle.kts). Overridable with
     * the instrumentation argument `targetPackage`.
     */
    val PACKAGE: String = InstrumentationRegistry.getArguments().getString("targetPackage") ?: "com.d365fo.mobileops.bench"

    const val FIRST_SCREEN_TEXT = "Try the demo"
    // Only Home shows this (On-hand's line). Not "Receive": the Receive area's own page is
    // titled that too, so a check for it passes one level down from Home.
    private const val HOME_TEXT = "How many, and where?"
    private const val SCAN_ACTION = "com.sunmi.scanner.ACTION_DATA_CODE_RECEIVED"

    /** Items DemoBackend always carries (data/demo/DemoBackend.kt). */
    val DEMO_ITEMS = listOf("DM-1001", "DM-1002", "DM-1003", "DM-1004", "DM-1005")

    const val STARTUP_TIMEOUT_MS = 30_000L
    // Starting the demo signs in and downloads its item list before Home appears.
    private const val DEMO_START_TIMEOUT_MS = 120_000L
    private const val STEP_TIMEOUT_MS = 10_000L

    /**
     * Back to a device straight out of the box: no connection, no worker, no tour seen.
     *
     * Runtime permissions are granted again straight after: on API 33+ MainActivity asks for
     * notifications on launch, and the system dialog would take the foreground before the app's
     * first frame, so the launch could never be timed. The P2mini (API 28) never shows that
     * dialog, so granting it measures the same launch a worker gets. On older APIs the grant
     * fails harmlessly.
     */
    fun MacrobenchmarkScope.clearAppData() {
        device.executeShellCommand("pm clear $packageName")
        grantPermissions()
        // Something (a grant, or the app's own scheduled work) can wake the process again after
        // the clear, and a cold start refuses to begin with it running.
        stopAppAndWait()
    }

    /** Force-stops the app until its process is really gone (it can be woken again meanwhile). */
    fun MacrobenchmarkScope.stopAppAndWait() {
        repeat(10) {
            device.executeShellCommand("am force-stop $packageName")
            Thread.sleep(300)
            if (device.executeShellCommand("pidof $packageName").isBlank()) return
        }
        error("$packageName kept running after force-stop - something keeps restarting it")
    }

    fun MacrobenchmarkScope.grantPermissions() {
        listOf("android.permission.POST_NOTIFICATIONS", "android.permission.CAMERA").forEach {
            device.executeShellCommand("pm grant $packageName $it")
        }
    }

    fun MacrobenchmarkScope.waitForFirstScreen() {
        check(device.wait(Until.hasObject(By.text(FIRST_SCREEN_TEXT)), STARTUP_TIMEOUT_MS)) {
            "\"$FIRST_SCREEN_TEXT\" never appeared - is the macrobench build installed and unconfigured?"
        }
    }

    /** From the first screen to Home in demo mode, tour dismissed. No-op when already on Home. */
    fun MacrobenchmarkScope.ensureDemoHome() {
        // Whichever comes up first: Home (the demo is already on - a warm restart) or the first
        // screen. Checking Home without waiting misses it while it is still drawing.
        val either = By.text(Pattern.compile("$HOME_TEXT|$FIRST_SCREEN_TEXT"))
        check(device.wait(Until.hasObject(either), STARTUP_TIMEOUT_MS)) { "Neither Home nor \"$FIRST_SCREEN_TEXT\" appeared" }
        if (device.hasObject(By.text(HOME_TEXT))) {
            dismissTour()
            return
        }
        device.findObject(By.text(FIRST_SCREEN_TEXT)).click()
        check(device.wait(Until.hasObject(By.text(HOME_TEXT)), DEMO_START_TIMEOUT_MS)) {
            "Home never appeared after starting the demo"
        }
        dismissTour()
    }

    /** The first-visit tour (presentation/common/tour/AppTour.kt) covers Home until skipped. */
    private fun MacrobenchmarkScope.dismissTour() {
        device.wait(Until.findObject(By.text("Skip")), 3_000)?.click()
        device.waitForIdle()
    }

    /** A scan the way the handheld delivers it: the Sunmi imager broadcast (OemScannerReceiver). */
    fun MacrobenchmarkScope.scan(barcode: String) {
        device.executeShellCommand("am broadcast -a $SCAN_ACTION -p $packageName --es data $barcode")
    }

    /** Opens a Home row by its label and comes back, waiting for each screen to settle. */
    fun MacrobenchmarkScope.openAndBack(label: String) {
        val row = device.wait(Until.findObject(By.text(label)), STEP_TIMEOUT_MS)
            ?: error("Home row \"$label\" not found")
        row.click()
        device.waitForIdle()
        device.pressBack()
        check(device.wait(Until.hasObject(By.text(HOME_TEXT)), STEP_TIMEOUT_MS)) { "Back from \"$label\" did not reach Home" }
    }

    fun MacrobenchmarkScope.backToHome() {
        repeat(3) {
            if (device.hasObject(By.text(HOME_TEXT))) return
            device.pressBack()
            device.waitForIdle()
        }
    }
}
