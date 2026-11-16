package com.d365fo.mobileops.presentation

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.d365fo.mobileops.data.repository.LicenseState
import com.d365fo.mobileops.data.repository.expiryBand
import com.d365fo.mobileops.presentation.auth.LicenseInvalidScreen
import com.d365fo.mobileops.presentation.common.focus.FocusPreviewSurface
import com.d365fo.mobileops.presentation.common.focus.ResultBand
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate

/**
 * The license screens and Home's expiry band (user, 16 Nov 2026) on a 360dp phone: each shows its
 * band and steps, and a PNG of each goes to files/license-shots for a look.
 */
@RunWith(AndroidJUnit4::class)
class LicenseScreensShotTest {

    @get:Rule
    val rule = createComposeRule()

    private fun shot(name: String) {
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "license-shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun screen(state: LicenseState.Blocked, name: String, title: String) {
        rule.setContent {
            FocusPreviewSurface {
                Box(Modifier.width(360.dp).height(640.dp)) {
                    LicenseInvalidScreen(state = state, connectedTo = "DEV · BSP", onRetry = {}, onOpenSettings = {})
                }
            }
        }
        rule.onNodeWithText(title).assertExists()
        rule.onNodeWithText("Try again").assertExists()
        shot(name)
    }

    @Test fun noLicense() = screen(
        LicenseState.Blocked("No license has been configured for this app. Contact your administrator.", "NO_LICENSE"),
        "no-license", "No license",
    )

    @Test fun expired() = screen(
        LicenseState.Blocked("This app's license expired on 2026-12-28T00:00:00Z. Contact your administrator.", "LICENSE_EXPIRED"),
        "expired", "License expired",
    )

    @Test fun deviceLimit() = screen(
        LicenseState.Blocked("This app is licensed for 5 device(s), and that many are already active. Contact your administrator.", "DEVICE_LIMIT_REACHED"),
        "device-limit", "Device limit reached",
    )

    @Test fun expiryBands() {
        rule.setContent {
            FocusPreviewSurface {
                androidx.compose.foundation.layout.Column(Modifier.width(360.dp)) {
                    ResultBand(expiryBand(LocalDate.of(2026, 12, 28), null, LocalDate.of(2026, 12, 16))!!, onDismiss = {})
                    androidx.compose.foundation.layout.Spacer(Modifier.height(12.dp))
                    ResultBand(expiryBand(LocalDate.of(2026, 12, 28), null, LocalDate.of(2026, 12, 27))!!, onDismiss = {})
                }
            }
        }
        rule.onNodeWithText("License expires in 12 days").assertExists()
        rule.onNodeWithText("License expires tomorrow").assertExists()
        shot("expiry-bands")
    }
}
