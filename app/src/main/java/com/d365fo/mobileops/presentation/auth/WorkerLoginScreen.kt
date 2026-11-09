package com.d365fo.mobileops.presentation.auth

import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.ArrowForward

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import com.d365fo.mobileops.presentation.common.components.SquareButton as Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.d365fo.mobileops.presentation.common.components.SquareOutlinedButton as OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.d365fo.mobileops.R
import com.d365fo.mobileops.presentation.common.components.ErrorBanner
import com.d365fo.mobileops.data.security.OfflineSignIn
import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.draw.clip
import com.d365fo.mobileops.presentation.common.focus.BandKind
import com.d365fo.mobileops.presentation.common.focus.BandState
import com.d365fo.mobileops.presentation.common.focus.FocusButton
import com.d365fo.mobileops.presentation.common.focus.FocusDimens
import com.d365fo.mobileops.presentation.common.focus.FocusMenu
import com.d365fo.mobileops.presentation.common.focus.FocusMenuItem
import com.d365fo.mobileops.presentation.common.focus.HamburgerButton
import com.d365fo.mobileops.presentation.common.focus.ResultBand
import com.d365fo.mobileops.presentation.common.theme.FocusColors
import com.d365fo.mobileops.presentation.common.theme.FocusShapes
import com.d365fo.mobileops.presentation.common.theme.FocusType

/**
 * Worker sign-in.
 *
 * This is the *second* identity the app carries. The device has already authenticated to D365FO
 * with a service principal by the time anyone sees this screen — that proved the app may talk to
 * the system, and nothing more. Every device shares it, so without this screen every posted
 * receipt would show the same CreatedBy and a GRN could never say who received the pallet.
 *
 * Kept to two fields and one button. A worker signs in at the start of a shift, often in gloves,
 * and anything else on this screen is something to get wrong at 6am.
 */
@Composable
fun WorkerLoginScreen(
    onOpenSettings: () -> Unit,
    offline: Boolean = false,
    viewModel: WorkerLoginViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val signOutNotice by viewModel.signOutNotice.collectAsStateWithLifecycle()
    val passwordFocus = remember { FocusRequester() }
    val userNameFocus = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    // hide() after requestFocus() - see the identical note and the live testing on the step-flow
    // screens. App-wide consistency: the cursor sits in the field the moment the screen opens,
    // but the keyboard only comes up on an explicit tap, the same as every other auto-focused
    // field in this app now. Simply not calling show() was tried first and was not enough - this
    // device's own IME brings itself up on any focus change regardless of what the app requests.
    // The last worker's name is filled in (feedback, 10 Oct): straight to the PIN then - focused
    // and with its keyboard up, as that is the one thing left to do.
    LaunchedEffect(Unit) {
        if (viewModel.prefill()) {
            runCatching { passwordFocus.requestFocus() }
            keyboardController?.show()
        } else {
            runCatching { userNameFocus.requestFocus() }
            keyboardController?.hide()
        }
    }
    // hide() alone still lost the race on this device (keyboard up on every arrival, seen in
    // release builds), so the field is read-only until tapped, as the counting scan box is.
    var typing by remember { mutableStateOf(false) }
    val userNameInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    LaunchedEffect(userNameInteraction) {
        userNameInteraction.interactions.collect {
            if (it is androidx.compose.foundation.interaction.PressInteraction.Release && !typing) {
                typing = true
                keyboardController?.show()
            }
        }
    }

    // Focus design (brief section 6): a hamburger with Connection settings; the logo, name and
    // "Version x · Online" centred; the two 60dp fields and the orange Sign in at the bottom.
    // Scrollable, so the keyboard opening never clips the logo or the button.
    var menuOpen by remember { mutableStateOf(false) }
    var showOffline by remember { mutableStateOf(false) }
    // Only once "offline" has lasted a few seconds: the network and D365FO checks at start
    // and on resume can report a blip, which flashed this notice for a second (feedback).
    LaunchedEffect(offline) {
        if (offline) {
            kotlinx.coroutines.delay(3_000)
            showOffline = true
        } else {
            showOffline = false
        }
    }
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(FocusColors.page)
            .imePadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, end = 8.dp),
            ) {
                HamburgerButton(open = menuOpen, onClick = { menuOpen = !menuOpen })
            }
            FocusMenu(
                open = menuOpen,
                onDismiss = { menuOpen = false },
                items = listOf(FocusMenuItem(Icons.Filled.Settings, "Connection settings") { menuOpen = false; onOpenSettings() }),
            )

            // Why the last attempt failed comes first, before anything else on the screen.
            val problem = state.error ?: signOutNotice
            problem?.let { ResultBand(BandState(BandKind.Bad, it), modifier = Modifier.padding(bottom = 8.dp)) }
            if (showOffline) {
                ResultBand(
                    BandState(BandKind.Warn, "Offline", "Signed in here in the last ${OfflineSignIn.VALID_DAYS} days? You can still sign in."),
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            // The launcher artwork laid out at full size (res/drawable-nodpi/ic_app_logo.png).
            Image(
                painter = painterResource(id = R.drawable.ic_app_logo),
                contentDescription = null,
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .width(120.dp),
            )
            Text(
                text = "Mobile Operations",
                style = FocusType.panelTitle,
                color = FocusColors.ink,
                modifier = Modifier.padding(top = 12.dp),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Text(com.d365fo.mobileops.util.AppVersion.short + " ·", style = FocusType.rowDetail, color = FocusColors.muted)
                androidx.compose.foundation.layout.Box(
                    Modifier
                        .size(10.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(if (offline) FocusColors.caption else FocusColors.okIcon),
                )
                Text(
                    if (offline) "Offline" else "Online",
                    style = FocusType.rowDetail,
                    color = if (offline) FocusColors.muted else FocusColors.okText,
                )
            }
            // Ownership/trademark notice, on the one screen every worker sees.
            Text(
                text = stringResource(R.string.copyright_notice),
                style = FocusType.holdHint,
                color = FocusColors.caption,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp, start = 24.dp, end = 24.dp),
            )
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))

            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = FocusDimens.side)
                    .padding(top = 24.dp, bottom = 20.dp),
            ) {
                OutlinedTextField(
                    value = state.userName,
                    onValueChange = viewModel::onUserNameChange,
                    label = { Text("User") },
                    singleLine = true,
                    enabled = !state.busy,
                    readOnly = !typing,
                    interactionSource = userNameInteraction,
                    shape = FocusShapes.button,
                    colors = focusFieldColors(),
                    textStyle = FocusType.fieldValue,
                    keyboardOptions = KeyboardOptions(
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Next,
                    ),
                    keyboardActions = KeyboardActions(
                        onNext = { runCatching { passwordFocus.requestFocus() } },
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 60.dp)
                        .focusRequester(userNameFocus),
                )
                OutlinedTextField(
                    value = state.password,
                    // A PIN is digits only (HSMobileOpsPassword::policyFailure), typed on the number pad.
                    onValueChange = { value -> viewModel.onPasswordChange(value.filter { it.isDigit() }) },
                    label = { Text("PIN") },
                    singleLine = true,
                    enabled = !state.busy,
                    visualTransformation = PasswordVisualTransformation(),
                    shape = FocusShapes.button,
                    colors = focusFieldColors(),
                    textStyle = FocusType.fieldValue,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { viewModel.signIn() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 60.dp)
                        .focusRequester(passwordFocus),
                )
                FocusButton(
                    text = if (state.busy) "Signing in" else "Sign in",
                    onClick = viewModel::signIn,
                    primary = true,
                    enabled = !state.busy && state.userName.isNotBlank() && state.password.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                )
            }
        }
    }
}

/** The Focus field: white, a divider-grey border, orange when typing in it. */
@Composable
private fun focusFieldColors() = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
    focusedContainerColor = FocusColors.surface,
    unfocusedContainerColor = FocusColors.surface,
    disabledContainerColor = FocusColors.surface,
    focusedBorderColor = FocusColors.action,
    unfocusedBorderColor = FocusColors.iconMuted,
    focusedLabelColor = FocusColors.action,
    unfocusedLabelColor = FocusColors.caption,
    focusedTextColor = FocusColors.ink,
    unfocusedTextColor = FocusColors.ink,
)
