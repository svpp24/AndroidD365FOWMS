package com.d365fo.mobileops.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test

/**
 * Records which classes and methods the app touches while starting, so ART can compile them
 * ahead of time instead of interpreting them on first launch.
 *
 * **Why this exists.** A freshly installed APK runs interpreted until ART gets round to
 * compiling it in the background. Measured on the P2mini: 10.17 s cold start on a fresh
 * install, 2.50 s for the identical APK after `cmd package compile -m speed`. The 7.7 s
 * difference is not the app doing anything — it is the same work, executed slowly. A baseline
 * profile closes most of that gap on the *first* launch, which is the launch a warehouse
 * worker judges the app on.
 *
 * **What to put in it.** Only the startup path, deliberately. A profile is a hint with a size
 * budget, not a list of everything the app can do: pad it with screens nobody opens in the
 * first ten seconds and the parts that matter get less benefit. Cold start to the first
 * interactive screen is what hurts here, so that is what this records.
 *
 * **Running it.** Needs a rooted device or an AOSP/google_apis emulator image at API 28+:
 *
 * ```
 * ./gradlew :app:generateReleaseBaselineProfile
 * ```
 *
 * A Play Store emulator image will not do — it cannot be rooted, so the profile cannot be read
 * back off the device. Nor will the handhelds themselves: they are `user` builds.
 */
class StartupBaselineProfile {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startup() = rule.collect(
        packageName = PACKAGE,
        // The default is a single iteration; several passes and the profile stops depending on
        // which classes happened to be resolved first on one particular run.
        maxIterations = 5,
        stableIterations = 3,
    ) {
        pressHome()
        startActivityAndWait()

        // Wait for something the sign-in screen actually renders rather than a fixed sleep.
        // A sleep either ends before the first frame — recording a profile that stops halfway
        // through startup — or wastes time on every iteration.
        device.wait(Until.hasObject(By.textContains("Sign in")), STARTUP_TIMEOUT_MS)
    }

    private companion object {
        // The release variant, which is the one that ships and the only one a profile helps.
        const val PACKAGE = "com.d365fo.mobileops"
        const val STARTUP_TIMEOUT_MS = 20_000L
    }
}
