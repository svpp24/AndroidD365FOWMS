package com.d365fo.mobileops.presentation

import android.graphics.Bitmap
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.d365fo.mobileops.presentation.common.focus.FocusBandSamples
import com.d365fo.mobileops.presentation.common.focus.FocusChromeSamples
import com.d365fo.mobileops.presentation.common.focus.FocusEndCountSample
import com.d365fo.mobileops.presentation.common.focus.FocusPreviewSurface
import com.d365fo.mobileops.presentation.common.focus.FocusQuantityPadSample
import com.d365fo.mobileops.presentation.common.focus.FocusRowSamples
import com.d365fo.mobileops.presentation.common.focus.FocusScannedSample
import com.d365fo.mobileops.presentation.common.focus.HOLD_TO_CONFIRM_MS
import com.d365fo.mobileops.presentation.common.focus.HoldToConfirmButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The Focus brief's checks for the components (sections 3 and 14), run on a device rather than by
 * eye: every sample at 100% and at the largest text size (130%) on a 360dp phone, with no text cut
 * or broken in the middle of a word or code, and every tappable element at least 56dp tall (the
 * back arrow and hamburger: 48dp, as the brief draws them). Saves a PNG of each to
 * files/focus-shots on the app's external storage.
 */
@RunWith(AndroidJUnit4::class)
class FocusComponentLayoutTest {

    @get:Rule
    val rule = createComposeRule()

    /** Name, whether the sample is a full 640dp screen, and the sample. */
    private val samples: List<Triple<String, Boolean, @Composable () -> Unit>> = listOf(
        Triple("Bands", false) { FocusBandSamples() },
        Triple("Chrome", false) { FocusChromeSamples() },
        Triple("Rows", false) { FocusRowSamples() },
        Triple("Scanned", true) { FocusScannedSample() },
        Triple("QuantityPad", true) { FocusQuantityPadSample() },
        Triple("EndCount", true) { FocusEndCountSample() },
    )

    @Test
    fun noTextIsCutAndEveryTargetIsBigEnough() {
        var current by mutableStateOf(0 to false)
        val scroll = ScrollState(0)
        rule.setContent {
            val (index, large) = current
            val (_, screen, sample) = samples[index]
            FocusPreviewSurface(large = large) {
                if (screen) {
                    Box(Modifier.width(360.dp).height(640.dp)) { sample() }
                } else {
                    // Scrollable, so a sample taller than the screen is measured whole.
                    Box(Modifier.verticalScroll(scroll)) { Box(Modifier.width(360.dp)) { sample() } }
                }
            }
        }
        val problems = mutableListOf<String>()
        val shots = File(
            InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
            "focus-shots",
        ).apply { mkdirs() }
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density

        samples.forEachIndexed { index, (name, _, _) ->
            listOf(false, true).forEach { large ->
                current = index to large
                rule.waitForIdle()
                val label = "$name ${if (large) "130%" else "100%"}"
                rule.onAllTextNodes().forEach { (text, layout) ->
                    cutReason(layout)?.let { problems += "$label: \"$text\" $it" }
                }
                // Merged tree: a button's label comes from its icon or text child.
                rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
                    .fetchSemanticsNodes()
                    // Nodes clipped out of the sample (zero height) aren't on screen.
                    .filter { it.boundsInRoot.height > 0f }
                    .forEach { node ->
                        // Laid-out height, not the visible part (a row at the screen edge is clipped).
                        val heightDp = node.size.height / density
                        val what = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
                            ?: node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
                            ?: node.config.getOrNull(SemanticsActions.OnClick)?.label
                            ?: "?"
                        val minimum = if (what in setOf("Back", "Menu", "Close menu", "Open menu")) 48f else 56f
                        if (heightDp + 0.5f < minimum) problems += "$label: \"$what\" is ${"%.1f".format(heightDp)}dp tall (min ${minimum.toInt()}dp)"
                    }
                capture(File(shots, "${name}_${if (large) "130" else "100"}.png"))
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun holdToConfirmFiresOnlyAfterAFullSecond() {
        var confirmed = 0
        rule.setContent {
            FocusPreviewSurface { HoldToConfirmButton(label = "Hold to post return", onConfirm = { confirmed++ }) }
        }
        rule.mainClock.autoAdvance = false

        // A plain tap: nothing happens.
        rule.onNodeWithText("Hold to post return").performTouchInput { down(center); up() }
        rule.mainClock.advanceTimeBy(300)
        assertEquals(0, confirmed)

        // Let go halfway: still nothing.
        rule.onNodeWithText("Hold to post return").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(HOLD_TO_CONFIRM_MS / 2L)
        rule.onNodeWithText("Hold to post return").performTouchInput { up() }
        rule.mainClock.advanceTimeBy(500)
        assertEquals(0, confirmed)

        // Held for the full second: fires once, before the finger lifts.
        rule.onNodeWithText("Hold to post return").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(HOLD_TO_CONFIRM_MS + 200L)
        assertEquals(1, confirmed)
        rule.onNodeWithText("Hold to post return").performTouchInput { up() }
        rule.mainClock.advanceTimeBy(500)
        assertEquals(1, confirmed)
    }

    private fun capture(file: File) {
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /**
     * Why [layout] is visibly cut, or null: lines that don't fit the height, lines dropped by
     * maxLines, a line wider than its box, or a line ending in the middle of a word or code.
     */
    private fun cutReason(layout: TextLayoutResult): String? {
        val box = layout.size
        return when {
            layout.didOverflowHeight -> "is cut at the bottom (needs ${layout.multiParagraph.height}px, has ${box.height}px)"
            layout.multiParagraph.didExceedMaxLines -> "has lines dropped by maxLines"
            else -> (0 until layout.lineCount)
                .map { it to layout.getLineRight(it) - layout.getLineLeft(it) }
                .firstOrNull { (_, width) -> width > box.width + 1f }
                ?.let { (line, width) -> "is cut at the right (line $line is ${width}px, box is ${box.width}px)" }
                ?: midWordBreak(layout)
        }
    }

    private fun midWordBreak(layout: TextLayoutResult): String? {
        val text = layout.layoutInput.text.text
        return (0 until layout.lineCount - 1).firstNotNullOfOrNull { line ->
            val end = layout.getLineEnd(line)
            val before = text.getOrNull(end - 1) ?: return@firstNotNullOfOrNull null
            if (before.isWhitespace() || before == '-' || before == '​') {
                null
            } else {
                "breaks mid-word: \"${text.substring(0, end).replace("​", "")}\" / \"${text.substring(end).replace("​", "")}\""
            }
        }
    }

    private fun ComposeContentTestRule.onAllTextNodes(): List<Pair<String, TextLayoutResult>> {
        val nodes = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
            .fetchSemanticsNodes()
        return nodes.flatMap { node ->
            val results = mutableListOf<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
            val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } ?: "?"
            results.map { text to it }
        }
    }
}
