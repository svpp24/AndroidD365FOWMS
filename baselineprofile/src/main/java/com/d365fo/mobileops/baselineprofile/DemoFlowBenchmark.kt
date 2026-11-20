package com.d365fo.mobileops.baselineprofile

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import com.d365fo.mobileops.baselineprofile.Journeys.backToHome
import com.d365fo.mobileops.baselineprofile.Journeys.ensureDemoHome
import com.d365fo.mobileops.baselineprofile.Journeys.grantPermissions
import com.d365fo.mobileops.baselineprofile.Journeys.openAndBack
import com.d365fo.mobileops.baselineprofile.Journeys.scanAndWait
import org.junit.Rule
import org.junit.Test

/**
 * Frame times on the paths a worker repeats all shift, in demo mode so every run sees the same
 * data. Compiled with the baseline profile when one ships (CompilationMode.Partial), as a Play
 * install would be once ART has applied it.
 *
 * The demo is started once, in the first iteration's setup. Later iterations find Home already
 * there, so the numbers are the screens, not the sign-in.
 */
class DemoFlowBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    /** Home's work rows: open each and come back. Navigation and first composition of each area. */
    @Test
    fun homeNavigation() = rule.measureRepeated(
        packageName = Journeys.PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.WARM,
        iterations = 5,
        setupBlock = {
            grantPermissions()
            startActivityAndWait()
            ensureDemoHome()
        },
    ) {
        listOf("Receive", "Ship", "Count", "Activity").forEach { openAndBack(it) }
    }

    /**
     * Scans on Home, delivered as imager broadcasts the way the handheld sends them: the lookup,
     * the result band and its countdown bar (FocusParts.kt), once per demo item, four times over.
     */
    @Test
    fun scanOnHome() = rule.measureRepeated(
        packageName = Journeys.PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.WARM,
        iterations = 5,
        setupBlock = {
            grantPermissions()
            startActivityAndWait()
            ensureDemoHome()
        },
    ) {
        repeat(4) {
            Journeys.DEMO_ITEMS.forEach { item ->
                scanAndWait(item)
                backToHome()
            }
        }
    }
}
