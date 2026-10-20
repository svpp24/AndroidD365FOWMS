package com.d365fo.mobileops.presentation.auth

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.d365fo.mobileops.data.sync.ActiveSessionGuard
import com.d365fo.mobileops.presentation.common.components.CancelOutlinedButton
import com.d365fo.mobileops.presentation.common.components.ConfirmButton
import com.d365fo.mobileops.presentation.common.components.DialogButtonRow
import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class TakeoverPromptViewModel @Inject constructor(private val guard: ActiveSessionGuard) : ViewModel() {
    val prompt = guard.prompt
    fun continueHere() = guard.continueHere()
    fun signOut() = guard.signOutInstead()
}

/**
 * Right after a sign-in, when another device still holds this worker's unfinished jobs or
 * counts (feedback, 11 Oct): continue here - the session moves, the work stays on that device -
 * or sign out and finish it there. Not dismissable by tapping outside: one of the two is chosen.
 */
@Composable
fun TakeoverPromptDialog(viewModel: TakeoverPromptViewModel = hiltViewModel()) {
    val prompt by viewModel.prompt.collectAsStateWithLifecycle()
    val current = prompt ?: return
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Work on another device") },
        text = { Text(ActiveSessionGuard.promptMessage(current)) },
        confirmButton = {
            DialogButtonRow(
                dismiss = {
                    CancelOutlinedButton(
                        onClick = viewModel::signOut,
                        modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget),
                    ) { Text("Sign out") }
                },
                confirm = {
                    ConfirmButton(
                        onClick = viewModel::continueHere,
                        modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget),
                    ) { Text("Continue here") }
                },
            )
        },
    )
}
