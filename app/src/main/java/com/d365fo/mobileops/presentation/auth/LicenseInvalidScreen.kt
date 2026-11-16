package com.d365fo.mobileops.presentation.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.d365fo.mobileops.data.repository.LicenseState
import com.d365fo.mobileops.presentation.common.focus.BandKind
import com.d365fo.mobileops.presentation.common.focus.BandState
import com.d365fo.mobileops.presentation.common.focus.FocusButton
import com.d365fo.mobileops.presentation.common.focus.FocusCaption
import com.d365fo.mobileops.presentation.common.focus.ResultBand
import com.d365fo.mobileops.presentation.common.theme.FocusColors
import com.d365fo.mobileops.presentation.common.theme.FocusType

/**
 * A licensing refusal (user, 16 Nov 2026): what is wrong in a band - No license, License expired,
 * Device limit reached, License not valid - which environment, and the steps To fix. A worker
 * cannot fix it by signing in, so no sign-in is offered; the menu reaches Connection settings
 * (a wrong environment), and [onRetry] re-runs the check once the fix is in.
 */
@Composable
fun LicenseInvalidScreen(
    state: LicenseState.Blocked,
    /** "DEV · BSP" - the profile and legal entity the check ran against. */
    connectedTo: String?,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val (band, steps) = licenseBandAndSteps(state)
    Column(Modifier.fillMaxSize().background(FocusColors.page)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 4.dp),
        ) {
            Text("Inventory hub", style = FocusType.rowTitle, color = FocusColors.ink, modifier = Modifier.weight(1f))
            com.d365fo.mobileops.presentation.common.focus.HamburgerButton(open = menuOpen, onClick = { menuOpen = !menuOpen })
        }
        com.d365fo.mobileops.presentation.common.focus.FocusMenu(
            open = menuOpen,
            onDismiss = { menuOpen = false },
            items = listOf(
                com.d365fo.mobileops.presentation.common.focus.FocusMenuItem(Icons.Filled.Settings, "Connection settings") {
                    menuOpen = false
                    onOpenSettings()
                },
            ),
        )
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            ResultBand(band, modifier = Modifier.padding(top = 8.dp))
            connectedTo?.takeIf { it.isNotBlank() }?.let {
                Text(
                    "Connected to $it",
                    style = FocusType.rowDetail,
                    color = FocusColors.muted,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                )
            }
            FocusCaption("To fix", modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 6.dp))
            steps.forEachIndexed { i, step -> FixStep(i + 1, step) }
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
            FocusButton("Try again", onClick = onRetry, primary = true, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun FixStep(number: Int, text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(26.dp).background(FocusColors.navy, CircleShape)) {
            Text(number.toString(), style = FocusType.caption, color = FocusColors.surface)
        }
        Text(text, style = FocusType.rowDetail, color = FocusColors.ink, modifier = Modifier.weight(1f))
    }
    Spacer(Modifier.size(0.dp))
}

/** The band and the To fix steps for each refusal - no "contact your administrator". */
internal fun licenseBandAndSteps(state: LicenseState.Blocked): Pair<BandState, List<String>> {
    val detail = state.message
        .replace(Regex("""\s*(Contact|contact) your administrator( to renew)?\.?"""), "")
        .trim()
    val upload = "In D365FO open License, Upload license file."
    val retry = "Tap Try again here."
    return when (state.code) {
        "NO_LICENSE" -> BandState(BandKind.Bad, "No license", "D365FO has no license for this app yet.") to
            listOf("Get the license file from the vendor.", upload, retry)
        "LICENSE_EXPIRED" -> BandState(
            BandKind.Bad,
            "License expired",
            // D365FO's own refusal names the date in its message (2027-01-01T00:00:00Z).
            (state.expiry ?: Regex("""\d{4}-\d{2}-\d{2}""").find(state.message)?.value)?.let { e -> expiredOn(e) } ?: detail,
        ) to listOf("Get the renewed license file from the vendor.", upload, retry)
        "DEVICE_LIMIT_REACHED" -> BandState(BandKind.Bad, "Device limit reached", detail) to
            listOf("In D365FO open Hub devices.", "Deactivate a device that is no longer used.", retry)
        "OFFLINE" -> BandState(BandKind.Warn, "Can't check the license", detail) to
            listOf("Check the device's network connection.", retry)
        "CLOCK" -> BandState(BandKind.Bad, "Clock moved backward", detail) to
            listOf("Set the device's date and time to automatic.", retry)
        else -> BandState(BandKind.Bad, "License not valid", detail) to
            listOf("Get a new license file from the vendor.", upload, retry)
    }
}

private fun expiredOn(iso: String): String = runCatching {
    "Expired on " + java.time.LocalDate.parse(iso.trim().substring(0, 10))
        .format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.ENGLISH)) + "."
}.getOrDefault("Expired on $iso.")
