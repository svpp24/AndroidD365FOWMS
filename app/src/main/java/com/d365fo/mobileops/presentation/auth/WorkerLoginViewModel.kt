package com.d365fo.mobileops.presentation.auth

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.d365fo.mobileops.BuildConfig
import com.d365fo.mobileops.data.repository.WorkerSessionRepository
import com.d365fo.mobileops.domain.repository.WorkerContextRepository
import com.d365fo.mobileops.util.NetworkResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Worker sign-in: user name, password, and — where a worker covers more than one — a warehouse.
 *
 * Nothing here retains the password beyond the call that uses it. The fields are held for as long
 * as the operator is typing and cleared the moment the attempt finishes, successfully or not.
 */
@HiltViewModel
class WorkerLoginViewModel @Inject constructor(
    private val sessions: WorkerSessionRepository,
    private val workerContextRepository: WorkerContextRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(WorkerLoginState())
    val state: StateFlow<WorkerLoginState> = _state.asStateFlow()

    fun onUserNameChange(value: String) =
        _state.update { it.copy(userName = value, error = null) }

    fun onPasswordChange(value: String) =
        _state.update { it.copy(password = value, error = null) }

    fun signIn() {
        val current = _state.value
        if (current.busy) return

        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }

            val result = sessions.signIn(
                userName = current.userName,
                password = current.password,
                deviceId = workerContextRepository.current().deviceId,
                // Feeds HSMobileOpsDevice, the backoffice fleet list - see the class doc on
                // WorkerSessionRepository.signIn. Best-effort only: Build.MANUFACTURER/MODEL are
                // plain OS-reported strings, never blank in practice, but there is no reason to
                // let a sign-in fail over them if a device ever did return one blank.
                deviceBrand = Build.MANUFACTURER,
                deviceModel = Build.MODEL,
                platform = "Android",
                platformVersion = Build.VERSION.RELEASE.orEmpty(),
                appVersionName = BuildConfig.VERSION_NAME,
            )

            _state.update { state ->
                when (result) {
                    is NetworkResult.Error -> state.copy(
                        busy = false,
                        // Cleared on failure too. Retyping a password beats leaving it on screen
                        // on a device that gets put down on a bench between attempts.
                        password = "",
                        error = result.failure.message,
                    )

                    NetworkResult.Loading -> state.copy(busy = false)

                    // Nothing carried forward: no name, no password, no error. A warehouse
                    // choice, when one is needed, is PickWarehouseScreen's (see AppRoot).
                    is NetworkResult.Success -> WorkerLoginState()
                }
            }
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }
}

data class WorkerLoginState(
    val userName: String = "",
    val password: String = "",
    val busy: Boolean = false,
    val error: String? = null,
)
