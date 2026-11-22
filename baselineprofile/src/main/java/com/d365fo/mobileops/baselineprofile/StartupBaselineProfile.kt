package com.d365fo.mobileops.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import com.d365fo.mobileops.baselineprofile.Journeys.backToHome
import com.d365fo.mobileops.baselineprofile.Journeys.resetToOutOfBox
import com.d365fo.mobileops.baselineprofile.Journeys.ensureDemoHome
import com.d365fo.mobileops.baselineprofile.Journeys.openAndBack
import com.d365fo.mobileops.baselineprofile.Journeys.scanAndWait
import com.d365fo.mobileops.baselineprofile.Journeys.waitForFirstScreen
import org.junit.Rule
import org.junit.Test

/**
 * Records which classes and methods the app touches while starting and in its first minutes of
 * use, so ART can compile them ahead of time instead of interpreting them on first launch.
 *
 * **Why this exists.** A freshly installed APK runs interpreted until ART gets round to
 * compiling it in the background. Measured on the P2mini: 10.17 s cold start on a fresh
 * install, 2.50 s for the identical APK after `cmd package compile -m speed`. The 7.7 s
 * difference is not the app doing anything — it is the same work, executed slowly. A baseline
 * profile closes most of that gap on the *first* launch, which is the launch a warehouse
 * worker judges the app on.
 *
 * **What to put in it.** Startup, and what a worker does in the first minutes after it: Home,
 * opening each area, and scanning. A profile is a hint with a size budget, not a list of
 * everything the app can do - screens nobody opens early stay out.
 *
 * **Running it.** Against the macrobench build (unconfigured, demo mode - no tenant, no release
 * key), on any device at API 33+ (no root needed there) or a rooted older one:
 *
 * ```
 * ./gradlew :app:generateBaselineProfile
 * ```
 *
 * The result is merged into app/src/main/generated/baselineProfiles, so every build - release
 * included - ships it. Commit it, and regenerate when the startup path or these flows change.
 */
class StartupBaselineProfile {

    @get:Rule
    val rule = BaselineProfileRule()

    /** Cold start of a device straight out of the box, to its first screen. */
    @Test
    fun startup() = rule.collect(
        packageName = Journeys.PACKAGE,
        // The default is a single iteration; several passes and the profile stops depending on
        // which classes happened to be resolved first on one particular run.
        maxIterations = 5,
        stableIterations = 3,
        includeInStartupProfile = true,
    ) {
        pressHome()
        resetToOutOfBox()
        startActivityAndWait()
        // Wait for something the first screen actually renders rather than a fixed sleep.
        waitForFirstScreen()
    }

    /** Into the demo, then each Home area and a run of scans - the first minutes of a shift. */
    @Test
    fun firstMinutes() = rule.collect(
        packageName = Journeys.PACKAGE,
        maxIterations = 5,
        stableIterations = 3,
    ) {
        pressHome()
        startActivityAndWait()
        ensureDemoHome()
        listOf("Receive", "Ship", "Count", "Activity").forEach { openAndBack(it) }
        Journeys.DEMO_ITEMS.forEach { item ->
            scanAndWait(item)
            backToHome()
        }
    }
}
