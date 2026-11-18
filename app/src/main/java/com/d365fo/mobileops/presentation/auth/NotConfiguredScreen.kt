package com.d365fo.mobileops.presentation.auth

import androidx.compose.animation.core.animateFloat
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import com.d365fo.mobileops.presentation.common.components.AppCard as Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.d365fo.mobileops.presentation.common.focus.BandKind
import com.d365fo.mobileops.presentation.common.focus.BandState
import com.d365fo.mobileops.presentation.common.focus.FocusDimens
import com.d365fo.mobileops.presentation.common.focus.FocusMenu
import com.d365fo.mobileops.presentation.common.focus.FocusMenuItem
import com.d365fo.mobileops.presentation.common.focus.HamburgerButton
import com.d365fo.mobileops.presentation.common.focus.ResultBand
import com.d365fo.mobileops.presentation.common.theme.FocusColors
import com.d365fo.mobileops.presentation.common.theme.FocusType
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.d365fo.mobileops.R
import com.d365fo.mobileops.data.security.TenantConfig

/**
 * Shown when the device cannot reach D365FO at all.
 *
 * There is deliberately no worker sign-in button here. The device authenticates as itself,
 * with the app registrations from the connection QR code (see ConnectionQr), so an ordinary
 * worker cannot fix this by signing in as anybody — the only thing that resolves it is scanning
 * the connection QR code, here or in Settings. Offering a worker login control would be offering
 * a button that authenticates
 * nobody, which is worse than offering none: it invites a worker to tap it repeatedly at the
 * start of a shift, conclude the app is broken, and go and find paper.
 */
@Composable
fun NotConfiguredScreen(
    state: SessionState,
    tenant: TenantConfig,
    onOpenSettings: () -> Unit,
    onConnected: () -> Unit = {},
    onTryDemo: () -> Unit = {},
    demoBusy: Boolean = false,
    demoError: String? = null,
    /** Another profile on this device still has its sign-in - point at the menu after a deactivation. */
    otherProfiles: Boolean = false,
) {
    // Dead centre of the page, on its own - not sharing a Column with the title above it. A
    // Column with Arrangement.Center centres the *pair* of title-plus-spinner as one block,
    // which leaves the spinner itself sitting below the true middle of the screen. The
    // standard WMS app puts its loading spinner at the actual centre of the open page;
    // matching that means the spinner has to be the only thing in this state, not one of two
    // things sharing the centred group.
    if (state is SessionState.Checking) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    // Focus design: the sign-in screen's layout - hamburger with Connection settings, the logo
    // and name, a band saying what is wrong, then the QR setup.
    var menuOpen by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FocusColors.page)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val deactivated = state is SessionState.SignedOut &&
            com.d365fo.mobileops.data.security.DeviceDeactivation.isDeactivation(state.reason)
        val pointAtMenu = deactivated && otherProfiles && !menuOpen
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, end = 8.dp),
        ) {
            if (pointAtMenu) TryOtherProfilesHint()
            Box(if (pointAtMenu) Modifier.pulseRing() else Modifier) {
                HamburgerButton(open = menuOpen, onClick = { menuOpen = !menuOpen })
            }
        }
        FocusMenu(
            open = menuOpen,
            onDismiss = { menuOpen = false },
            items = listOf(FocusMenuItem(Icons.Filled.Settings, "Connection settings") { menuOpen = false; onOpenSettings() }),
        )
        // The same logo and name as the sign-in screen.
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(id = R.drawable.ic_app_logo),
            contentDescription = null,
            modifier = Modifier
                .padding(top = 24.dp)
                .width(120.dp),
        )
        Text(
            text = "Inventory hub",
            style = FocusType.panelTitle,
            color = FocusColors.ink,
            modifier = Modifier.padding(top = 12.dp, bottom = 20.dp),
        )
        ResultBand(
            BandState(
                BandKind.Warn,
                when (state) {
                    is SessionState.NotConfigured -> "This device is not configured yet"
                    is SessionState.SignedOut -> state.reason ?: "This device cannot reach D365FO"
                    else -> "Setting up"
                },
                // No address after a deactivation (user, 15 Nov 2026) - the profile is named instead.
                // Not on "not configured yet" either (user, 18 Nov 2026): a half-saved connection
                // (a developer build's compiled-in URL) read as if the device were connected.
                tenant.host.takeIf { state is SessionState.SignedOut && tenant.resourceUrl.isNotBlank() && !deactivated },
            ),
        )
        Text(
            text = "Scan the connection QR code from your administrator.",
            style = FocusType.bandDetail,
            color = FocusColors.muted,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(horizontal = FocusDimens.side, vertical = 16.dp),
        )
        com.d365fo.mobileops.presentation.common.ConnectionQrSetup(
            onConnected = onConnected,
            modifier = Modifier.padding(horizontal = FocusDimens.side).padding(bottom = 20.dp),
        )
        // Demo mode (user, 11 Nov): every screen on sample data, no connection or password.
        Text(
            text = "No connection yet? Look around with sample data.",
            style = FocusType.bandDetail,
            color = FocusColors.muted,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(horizontal = FocusDimens.side),
        )
        com.d365fo.mobileops.presentation.common.focus.FocusButton(
            if (demoBusy) "Starting the demo…" else "Try the demo",
            onClick = onTryDemo,
            enabled = !demoBusy,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = FocusDimens.side, vertical = 12.dp),
        )
        demoError?.let {
            Text(
                text = it,
                style = FocusType.bandDetail,
                color = FocusColors.badIcon,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(horizontal = FocusDimens.side).padding(bottom = 20.dp),
            )
        }
    }
}

/** Whether the system's "remove animations" is on - the hint then stays still. */
@Composable
private fun animationsOff(): Boolean {
    val context = androidx.compose.ui.platform.LocalContext.current
    return androidx.compose.runtime.remember {
        android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/** "Try other profiles" in a light-blue bubble with an arrow, nudging towards the menu. */
@Composable
private fun TryOtherProfilesHint() {
    val still = animationsOff()
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "hint")
    val nudge by transition.animateFloat(
        initialValue = 0f,
        targetValue = 6f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(600),
            androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "nudge",
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .offset(x = if (still) 0.dp else nudge.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
            .background(FocusColors.infoBg)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text("Try other profiles", style = FocusType.rowDetail.copy(fontWeight = FontWeight.ExtraBold), color = FocusColors.infoText)
        androidx.compose.material3.Icon(
            androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = FocusColors.infoText,
            modifier = Modifier.width(18.dp),
        )
    }
}

/** A navy ring that grows and fades around the menu button, over and over. */
@Composable
private fun Modifier.pulseRing(): Modifier {
    val still = animationsOff()
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "pulse")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1600)),
        label = "ring",
    )
    if (still) return this
    val navy = FocusColors.navy
    return this.drawBehind {
        drawCircle(
            color = navy.copy(alpha = 0.45f * (1f - t)),
            radius = size.minDimension / 2 + 12.dp.toPx() * t,
        )
    }
}
