package com.d365fo.mobileops.presentation

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.d365fo.mobileops.presentation.common.focus.FocusPreviewSurface
import com.d365fo.mobileops.presentation.common.theme.FocusColors
import com.d365fo.mobileops.presentation.modules.countcontrol.Tile
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Count control's tiles with the mock's coloured top edge; a PNG goes to files/count-control-shots. */
@RunWith(AndroidJUnit4::class)
class CountControlTileShotTest {
    @get:Rule
    val rule = createComposeRule()

    @Test fun tiles() {
        rule.setContent {
            FocusPreviewSurface {
                Column(Modifier.width(360.dp).background(FocusColors.page).padding(horizontal = 14.dp, vertical = 6.dp)) {
                    Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Tile(2, "Bins counted twice", FocusColors.badIcon, Modifier.weight(1f))
                        Tile(1, "Counter still counting", FocusColors.warnIcon, Modifier.weight(1f))
                    }
                    Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Tile(3, "Blind counts today", FocusColors.divider, Modifier.weight(1f))
                        Tile(38, "Lines with variance", FocusColors.divider, Modifier.weight(1f))
                    }
                }
            }
        }
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "count-control-shots").apply { mkdirs() }
        File(dir, "tiles.png").outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
