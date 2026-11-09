package com.d365fo.mobileops.presentation

import android.graphics.Bitmap
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.verticalScroll
import kotlinx.coroutines.runBlocking
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.d365fo.mobileops.presentation.common.AppBottomBarSamples
import com.d365fo.mobileops.presentation.common.components.FlowActionBarSamples
import com.d365fo.mobileops.presentation.common.components.FlowHeaderSamples
import com.d365fo.mobileops.presentation.common.components.HOLD_TO_CONFIRM_MS
import com.d365fo.mobileops.presentation.common.components.HoldToConfirmButton
import com.d365fo.mobileops.presentation.common.components.HoldToConfirmSamples
import com.d365fo.mobileops.presentation.common.components.ListRowSamples
import com.d365fo.mobileops.presentation.common.components.NextCardSamples
import com.d365fo.mobileops.presentation.common.components.PreviewSurface
import com.d365fo.mobileops.presentation.common.components.SendTraySamples
import com.d365fo.mobileops.presentation.common.components.StatusBandSamples
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The redesign brief's Phase 2 check, run for real rather than by eye: every component preview's
 * samples, at 100% and at the Settings 130% text size, on a 360dp-wide phone, with no text
 * truncated or clipped anywhere. A [TextLayoutResult] with visual overflow is exactly a text that
 * was cut - by maxLines, by a fixed-height parent, or by running out of width.
 *
 * Also saves a PNG of each (files/component-shots on the app's external storage) for a visual
 * check.
 */
@RunWith(AndroidJUnit4::class)
class ComponentLayoutTest {

    @get:Rule
    val rule = createComposeRule()

    /** Name, drawn inside the page's 16dp margin (false for full-width chrome), samples. */
    private val samples: List<Triple<String, Boolean, @Composable () -> Unit>> = listOf(
        Triple("FlowHeader", true) { FlowHeaderSamples() },
        Triple("StatusBand", true) { StatusBandSamples() },
        Triple("NextCard", true) { NextCardSamples() },
        Triple("SendTray", true) { SendTraySamples() },
        Triple("FlowActionBar", true) { FlowActionBarSamples() },
        Triple("HoldToConfirmButton", true) { HoldToConfirmSamples() },
        Triple("ListRow", true) { ListRowSamples() },
        Triple("AppBottomBar", false) { AppBottomBarSamples() },
    )

    @Test
    fun noTextIsTruncatedAt100Or130Percent() {
        var current by mutableStateOf(0 to false)
        val scroll = ScrollState(0)
        rule.setContent {
            val (index, large) = current
            // Scrollable, so a sample taller than the screen is measured at its full height - left
            // unscrolled, whatever falls below the screen's edge is squeezed to 0px and reads as cut.
            Box(Modifier.verticalScroll(scroll)) {
                PreviewSurface(large = large, padded = samples[index].second) {
                    Box(Modifier.width(360.dp)) { samples[index].third() }
                }
            }
        }
        val problems = mutableListOf<String>()
        val shots = File(
            InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
            "component-shots",
        ).apply { mkdirs() }

        samples.forEachIndexed { index, (name, _, _) ->
            listOf(false, true).forEach { large ->
                current = index to large
                rule.runOnIdle { runBlocking { scroll.scrollTo(0) } }
                rule.waitForIdle()
                val label = "$name ${if (large) "130%" else "100%"}"
                rule.onAllTextNodes().forEach { (text, layout) ->
                    cutReason(layout)?.let { problems += "$label: \"$text\" $it" }
                }
                val file = "${name}_${if (large) "130" else "100"}"
                capture(File(shots, "$file.png"))
                // Taller than the screen: the bottom too, so the whole sample can be looked at.
                if (scroll.maxValue > 0) {
                    rule.runOnIdle { runBlocking { scroll.scrollTo(scroll.maxValue) } }
                    rule.waitForIdle()
                    capture(File(shots, "${file}_end.png"))
                }
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun holdToConfirmFiresOnlyWhenTheBarIsFull() {
        var confirmed = 0
        rule.setContent {
            PreviewSurface { HoldToConfirmButton(label = "Hold to post return", onConfirm = { confirmed++ }) }
        }
        rule.mainClock.autoAdvance = false

        // A plain tap: nothing posts, the hint explains.
        rule.onNodeWithText("Hold to post return").performTouchInput { down(center); up() }
        rule.mainClock.advanceTimeBy(300)
        assertEquals(0, confirmed)
        rule.onNodeWithText("Keep holding until the bar is full").assertExists()

        // Let go halfway: still nothing.
        rule.onNodeWithText("Hold to post return").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(HOLD_TO_CONFIRM_MS / 2L)
        rule.onNodeWithText("Hold to post return").performTouchInput { up() }
        rule.mainClock.advanceTimeBy(500)
        assertEquals(0, confirmed)

        // Held to the end: fires once, before the finger lifts.
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
     * Why [layout] is visibly cut, or null if it isn't. Not [TextLayoutResult.hasVisualOverflow]:
     * its width half compares the box with the *layout* width, and this Compose version shrinks a
     * text's box to its content - so every short label "overflowed" while drawn in full (seen in
     * the first run's screenshots). What actually cuts text: lines that don't fit the height, lines
     * dropped by maxLines, or a line (an unwrapped one) wider than the box. Line *width*, not its
     * right edge: a centred line's position is measured against the layout width, not the box.
     */
    private fun cutReason(layout: TextLayoutResult): String? {
        val box = layout.size
        return when {
            layout.didOverflowHeight ->
                "is cut at the bottom (needs ${layout.multiParagraph.height}px, has ${box.height}px)"
            layout.multiParagraph.didExceedMaxLines -> "has lines dropped by maxLines"
            else -> (0 until layout.lineCount)
                .map { it to layout.getLineRight(it) - layout.getLineLeft(it) }
                .firstOrNull { (_, width) -> width > box.width + 1f }
                ?.let { (line, width) -> "is cut at the right (line $line is ${width}px, box is ${box.width}px)" }
                ?: midWordBreak(layout)
        }
    }

    /**
     * Not cut, but as bad to read: a line that ends inside a word or a code ("Settin / gs",
     * "BSP-PO-0001617 / 63"). Every line but the last has to end at a space, a hyphen or the
     * zero-width break [com.d365fo.mobileops.presentation.common.theme.breakableCode] adds.
     */
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

    /** Every text on screen with its layout - each Text exposes it through GetTextLayoutResult. */
    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllTextNodes(): List<Pair<String, TextLayoutResult>> {
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
