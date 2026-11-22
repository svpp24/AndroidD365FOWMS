package com.d365fo.mobileops.baselineprofile

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import com.d365fo.mobileops.baselineprofile.Journeys.resetToOutOfBox
import com.d365fo.mobileops.baselineprofile.Journeys.waitForFirstScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Cold start to the first screen a new device shows, under three compilation states:
 *
 * - **None** - a fresh install before ART has compiled anything: the launch a worker judges the
 *   app on (10.17 s measured on the P2mini, see [StartupBaselineProfile]).
 * - **BaselineProfile** - what a Play install gets with the shipped profile (Phase 1: it now
 *   matches Full on the emulator).
 * - **Full** - everything compiled ahead of time: the ceiling (2.50 s on the P2mini).
 *
 * App data is cleared before every launch so each run starts at "Try the demo", whatever ran
 * before it.
 */
@RunWith(Parameterized::class)
class StartupBenchmark(@Suppress("unused") private val label: String, private val mode: CompilationMode) {

    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun coldStart() = rule.measureRepeated(
        packageName = Journeys.PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = mode,
        startupMode = StartupMode.COLD,
        iterations = 10,
        setupBlock = {
            pressHome()
            resetToOutOfBox()
        },
    ) {
        startActivityAndWait()
        waitForFirstScreen()
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun modes(): List<Array<Any>> = listOf(
            arrayOf("None", CompilationMode.None()),
            arrayOf("BaselineProfile", CompilationMode.Partial()),
            arrayOf("Full", CompilationMode.Full()),
        )
    }
}
