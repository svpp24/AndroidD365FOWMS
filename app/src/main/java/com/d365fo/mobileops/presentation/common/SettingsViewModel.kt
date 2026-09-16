package com.d365fo.mobileops.presentation.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.d365fo.mobileops.data.remote.api.LiveD365ODataApi
import com.d365fo.mobileops.data.remote.error.D365ErrorMapper
import com.d365fo.mobileops.data.repository.WorkerContextRepositoryImpl
import com.d365fo.mobileops.data.security.ConnectionSettings
import com.d365fo.mobileops.data.security.DeviceCodePollState
import com.d365fo.mobileops.data.security.DeviceCodeTokenProvider
import com.d365fo.mobileops.data.security.NetworkPreferences
import com.d365fo.mobileops.data.security.RequestTimeout
import com.d365fo.mobileops.data.security.TenantConfig
import com.d365fo.mobileops.data.security.ThemePreferences
import com.d365fo.mobileops.domain.model.WorkerContext
import com.d365fo.mobileops.presentation.common.theme.ColorPalette
import com.d365fo.mobileops.util.AppFailure
import com.d365fo.mobileops.util.NetworkResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/** Result of the "test connection" action, shown inline under the form. */
sealed interface ConnectionTest {
    data object Idle : ConnectionTest
    data object Running : ConnectionTest
    data class Ok(val detail: String) : ConnectionTest
    data class Failed(val reason: String) : ConnectionTest
}

/** UI state of an in-progress or completed device-code sign-in. Mirrors [DeviceCodePollState]. */
sealed interface DeviceCodeUiState {
    data object Idle : DeviceCodeUiState
    data object Starting : DeviceCodeUiState
    data class WaitingForUser(val userCode: String, val verificationUri: String) : DeviceCodeUiState
    data class SignedIn(val userPrincipalName: String?) : DeviceCodeUiState
    data class Failed(val reason: String) : DeviceCodeUiState
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val connectionSettings: ConnectionSettings,
    private val workerContextRepository: WorkerContextRepositoryImpl,
    private val liveApi: LiveD365ODataApi,
    private val errorMapper: D365ErrorMapper,
    private val deviceCodeTokenProvider: DeviceCodeTokenProvider,
    private val themePreferences: ThemePreferences,
    private val networkPreferences: NetworkPreferences,
) : ViewModel() {

    val palette: StateFlow<ColorPalette> = themePreferences.palette

    fun setPalette(value: ColorPalette) = themePreferences.setPalette(value)

    val requestTimeout: StateFlow<RequestTimeout> = networkPreferences.timeout

    fun setRequestTimeout(value: RequestTimeout) = networkPreferences.setTimeout(value)

    private val handler = CoroutineExceptionHandler { _, t ->
        Timber.e(t, "settings action failed")
        _test.value = ConnectionTest.Failed(t.message ?: "Unexpected error")
    }

    val tenant: StateFlow<TenantConfig> = connectionSettings.tenant
    val postingEnabled: StateFlow<Boolean> = connectionSettings.postingEnabled

    private val _test = MutableStateFlow<ConnectionTest>(ConnectionTest.Idle)
    val test: StateFlow<ConnectionTest> = _test.asStateFlow()

    private val _deviceCode = MutableStateFlow<DeviceCodeUiState>(
        deviceCodeTokenProvider.signedInUpn()?.let { DeviceCodeUiState.SignedIn(it) }
            ?: DeviceCodeUiState.Idle,
    )
    val deviceCode: StateFlow<DeviceCodeUiState> = _deviceCode.asStateFlow()
    private var devicePollJob: Job? = null

    val worker: StateFlow<WorkerContext> = workerContextRepository.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WorkerContext.UNAUTHENTICATED)

    fun canGoLive(): Boolean = connectionSettings.canGoLive()

    fun setPostingEnabled(enabled: Boolean) = connectionSettings.setPostingEnabled(enabled)

    fun saveTenant(config: TenantConfig) {
        connectionSettings.saveTenant(config)
        _test.value = ConnectionTest.Idle
    }

    /**
     * Saves the connection and proves it against the *real* tenant.
     *
     * Calls [liveApi] directly rather than going through the injected D365ODataApi. In demo
     * mode that injected instance is the fake, so the test previously answered "Connected"
     * with the demo dataset's own row counts without a single packet leaving the device — a
     * green result that meant nothing, which is worse than no test at all.
     *
     * The probe is one row from a small entity, and **any HTTP reply counts as reachable** —
     * including a 404.
     *
     * This has been wrong twice in opposite directions. First it probed a named entity and a
     * 404 for an entity that deployment did not publish was reported as a broken connection.
     * Then it probed the `/data/` service document, which is guaranteed to exist but enumerates
     * every entity in the system: 11.9 seconds on a warm sandbox, and past a sixty-second
     * timeout on a cold AOS, so a healthy environment reported "request timed out".
     *
     * Treating a 404 as success resolves both. A 404 came *from D365FO* — DNS, TLS, the token
     * and the OData stack all worked to produce it. Only a timeout, a TLS failure or a 401 mean
     * what this test is actually for.
     */
    fun saveAndTest(config: TenantConfig) {
        val problem = config.validationError()
        if (problem != null) {
            _test.value = ConnectionTest.Failed(problem)
            return
        }

        connectionSettings.saveTenant(config)
        _test.value = ConnectionTest.Running

        viewModelScope.launch(handler) {
            val startedAt = System.currentTimeMillis()
            val result = errorMapper.safeCall { liveApi.getConnectivityProbe() }
            val elapsed = System.currentTimeMillis() - startedAt

            _test.value = when (result) {
                is NetworkResult.Success -> ConnectionTest.Ok(
                    "Connected to ${config.host} in $elapsed ms. OData is reachable.",
                )

                is NetworkResult.Error -> when {
                    // A 404 is the probe entity missing, not the server missing. Everything
                    // this test exists to check already worked to produce it.
                    (result.failure as? AppFailure.BusinessRule)?.httpCode == 404 ->
                        ConnectionTest.Ok(
                            "Connected to ${config.host} in $elapsed ms. OData is reachable " +
                                "(the probe entity is not published here, which is harmless).",
                        )

                    // The most common outcome on a first attempt, and the one whose cause is
                    // least obvious — so it gets told plainly instead of "401".
                    result.failure is AppFailure.Authentication -> ConnectionTest.Failed(
                        "Reached the server, but it refused the request. Sign in first, " +
                            "and check the client ID is registered in D365FO under " +
                            "System administration > Setup > Microsoft Entra ID applications.",
                    )
                    result.failure is AppFailure.Offline -> ConnectionTest.Failed(
                        "Could not reach ${config.host}. Check the URL and the device's network.",
                    )
                    else -> ConnectionTest.Failed(result.failure.message)
                }

                NetworkResult.Loading -> ConnectionTest.Idle
            }
        }
    }

    /**
     * Stores the device's legal entity.
     *
     * Warehouse is deliberately not set here any more — it comes from the signed-in worker's
     * own backoffice assignment (see WorkerSessionRepository), never from a device-level field.
     * Legal entity stays device-level: the device connects to one company, chosen before anyone
     * signs in, independent of which warehouses that company's directory assigns per worker.
     */
    fun saveLegalEntity(dataAreaId: String) {
        viewModelScope.launch(handler) {
            val company = dataAreaId.trim().uppercase()
            workerContextRepository.setRegistration(
                personnelNumber = null,
                dataAreaId = company,
            )
            _test.value = ConnectionTest.Ok("Legal entity $company saved.")
        }
    }

    /**
     * Starts an interactive device-code sign-in and polls it to completion.
     *
     * The tenant/client fields must already be saved (see [saveTenant]) before this can start -
     * the device-code request needs a tenant and client ID the same way the client-credentials
     * test does.
     */
    fun startDeviceCodeSignIn() {
        devicePollJob?.cancel()
        _deviceCode.value = DeviceCodeUiState.Starting

        devicePollJob = viewModelScope.launch(handler) {
            when (val challenge = deviceCodeTokenProvider.startDeviceCodeFlow()) {
                is NetworkResult.Error -> {
                    _deviceCode.value = DeviceCodeUiState.Failed(challenge.failure.message)
                }
                NetworkResult.Loading -> Unit
                is NetworkResult.Success -> {
                    _deviceCode.value = DeviceCodeUiState.WaitingForUser(
                        userCode = challenge.data.userCode,
                        verificationUri = challenge.data.verificationUri,
                    )
                    deviceCodeTokenProvider.pollForToken(challenge.data).collect { state ->
                        _deviceCode.value = when (state) {
                            DeviceCodePollState.Pending -> return@collect
                            is DeviceCodePollState.Success ->
                                DeviceCodeUiState.SignedIn(state.userPrincipalName)
                            DeviceCodePollState.Expired ->
                                DeviceCodeUiState.Failed("The code expired before sign-in finished. Try again.")
                            DeviceCodePollState.Declined ->
                                DeviceCodeUiState.Failed("Sign-in was declined.")
                            is DeviceCodePollState.Error -> DeviceCodeUiState.Failed(state.message)
                        }
                    }
                }
            }
        }
    }

    fun cancelDeviceCodeSignIn() {
        devicePollJob?.cancel()
        devicePollJob = null
        _deviceCode.value = deviceCodeTokenProvider.signedInUpn()?.let { DeviceCodeUiState.SignedIn(it) }
            ?: DeviceCodeUiState.Idle
    }
}
