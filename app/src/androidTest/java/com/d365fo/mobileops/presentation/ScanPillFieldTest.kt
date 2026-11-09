package com.d365fo.mobileops.presentation

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.d365fo.mobileops.domain.model.RawScan
import com.d365fo.mobileops.domain.model.ScanSource
import com.d365fo.mobileops.presentation.common.focus.FocusPreviewSurface
import com.d365fo.mobileops.presentation.common.focus.LocalScanBridge
import com.d365fo.mobileops.presentation.common.focus.ScanPillField
import com.d365fo.mobileops.presentation.common.scan.HidScannerBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

/** Brief section 4, rule 4: typing in the scan pill and pressing Enter is exactly a scan. */
@RunWith(AndroidJUnit4::class)
class ScanPillFieldTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun typedCodeAndEnterArriveAsAScan() {
        val bridge = HidScannerBridge()
        val scans = Collections.synchronizedList(mutableListOf<RawScan>())
        val collector = CoroutineScope(Dispatchers.Unconfined)
        collector.launch { bridge.scans.collect { scans += it } }
        try {
            rule.setContent {
                CompositionLocalProvider(LocalScanBridge provides bridge) {
                    FocusPreviewSurface { ScanPillField() }
                }
            }
            rule.onNodeWithText("Scan or type").performClick()
            rule.waitForIdle()
            assertFalse("wedge interception paused while typing", bridge.enabled)

            rule.onNode(hasSetTextAction()).performTextInput(" BSP-PO-000161763 ")
            rule.onNode(hasSetTextAction()).performImeAction()
            rule.waitForIdle()

            assertEquals(1, scans.size)
            assertEquals("BSP-PO-000161763", scans[0].value)
            assertEquals(ScanSource.MANUAL_ENTRY, scans[0].source)
            // Back to the plain pill, interception restored.
            rule.onNodeWithText("Scan or type").assertExists()
            assertTrue(bridge.enabled)
        } finally {
            collector.cancel()
        }
    }
}
