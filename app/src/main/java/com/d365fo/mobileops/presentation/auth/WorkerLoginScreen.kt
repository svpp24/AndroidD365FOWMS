package com.d365fo.mobileops.presentation.auth

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
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors

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
    viewModel: WorkerLoginViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val passwordFocus = remember { FocusRequester() }
    val userNameFocus = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    // hide() after requestFocus() - see the identical note and the live testing on the step-flow
    // screens. App-wide consistency: the cursor sits in the field the moment the screen opens,
    // but the keyboard only comes up on an explicit tap, the same as every other auto-focused
    // field in this app now. Simply not calling show() was tried first and was not enough - this
    // device's own IME brings itself up on any focus change regardless of what the app requests.
    LaunchedEffect(Unit) {
        runCatching { userNameFocus.requestFocus() }
        keyboardController?.hide()
    }

    // Scrollable, not just centered - when the keyboard opens (an explicit tap on a field;
    // see the hide() above for why it is not open by default) it shrinks the space this
    // Column has to work with. Centered-but-fixed content that no longer fits its viewport
    // overflows equally off both ends rather than scrolling, which clipped the title off the
    // top and the "Connection settings" button down to a sliver of its own border - caught
    // live on device. verticalScroll is what SettingsScreen already does for the same reason.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // At the top, not tucked between the button and "Connection settings" - a worker
        // scanning down the screen after a failed attempt should see why before they see
        // anything else, not after scrolling past fields they already filled in correctly.
        state.error?.let { message ->
            ErrorBanner(message = message)
        }

        // Same artwork as the launcher icon, but its own drawable (res/drawable-nodpi/ic_app_logo.png)
        // rather than reusing ic_launcher_foreground directly - this one is laid out at full
        // rectangular size on a plain background, not recentred/cropped into an adaptive icon's
        // safe zone. Sits after the error card, not before it - the error staying the first thing
        // a worker sees on a failed attempt is the whole reason it's placed above everything else,
        // see this screen's own class doc.
        Image(
            painter = painterResource(id = R.drawable.ic_app_logo),
            contentDescription = null,
            modifier = Modifier.heightIn(max = 104.dp),
        )

        // Ownership/trademark notice, right under the mark it names - the one screen every
        // worker sees on every device, signed in or not, so it doesn't depend on anyone
        // finding it under Settings.
        Text(
            text = stringResource(R.string.copyright_notice),
            style = MaterialTheme.typography.labelSmall,
            color = WarehouseStatusColors.text3,
        )

        Text(
            text = "Sign in",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )

        OutlinedTextField(
            value = state.userName,
            onValueChange = viewModel::onUserNameChange,
            label = { Text("User name") },
            singleLine = true,
            enabled = !state.busy,
            keyboardOptions = KeyboardOptions(
                autoCorrectEnabled = false,
                imeAction = ImeAction.Next,
            ),
            keyboardActions = KeyboardActions(
                onNext = { runCatching { passwordFocus.requestFocus() } },
            ),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .focusRequester(userNameFocus),
        )

        OutlinedTextField(
            value = state.password,
            onValueChange = viewModel::onPasswordChange,
            label = { Text("Password") },
            singleLine = true,
            enabled = !state.busy,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { viewModel.signIn() }),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .focusRequester(passwordFocus),
        )

        Button(
            onClick = viewModel::signIn,
            enabled = !state.busy && state.userName.isNotBlank() && state.password.isNotBlank(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) {
            if (state.busy) {
                CircularProgressIndicator(modifier = Modifier.heightIn(max = 24.dp))
            } else {
                Text("Sign in")
            }
        }

        OutlinedButton(
            onClick = onOpenSettings,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) {
            Text("Connection settings")
        }
    }
}
