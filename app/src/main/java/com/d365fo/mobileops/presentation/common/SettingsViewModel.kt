package com.d365fo.mobileops.presentation.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.d365fo.mobileops.data.local.D365Database
import com.d365fo.mobileops.data.repository.WorkerContextRepositoryImpl
import com.d365fo.mobileops.data.repository.WorkerSessionRepository
import com.d365fo.mobileops.data.security.ClientCredentialsTokenProvider
import com.d365fo.mobileops.data.security.ConnectionSettings
import com.d365fo.mobileops.data.security.DeviceCodePollState
import com.d365fo.mobileops.data.security.DeviceCodeTokenProvider
import com.d365fo.mobileops.data.security.DisplayPreferences
import com.d365fo.mobileops.data.security.DisplayScale
import com.d365fo.mobileops.data.security.IdlePreferences
import com.d365fo.mobileops.data.security.IdleTimeout
import com.d365fo.mobileops.data.security.NetworkPreferences
import com.d365fo.mobileops.data.security.RequestTimeout
import com.d365fo.mobileops.data.security.TenantConfig
import com.d365fo.mobileops.data.security.ThemePreferences
import com.d365fo.mobileops.domain.model.WorkerContext
import com.d365fo.mobileops.domain.model.WorkerRole
import com.d365fo.mobileops.domain.model.role
import com.d365fo.mobileops.presentation.common.theme.ColorPalette
import com.d365fo.mobileops.util.NetworkResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private val sessionRepository: WorkerSessionRepository,
    private val deviceCodeTokenProvider: DeviceCodeTokenProvider,
    private val clientCredentialsTokenProvider: ClientCredentialsTokenProvider,
    private val themePreferences: ThemePreferences,
    private val networkPreferences: NetworkPreferences,
    private val displayPreferences: DisplayPreferences,
    private val idlePreferences: IdlePreferences,
    private val database: D365Database,
) : ViewModel() {

    val palette: StateFlow<ColorPalette> = themePreferences.palette

    fun setPalette(value: ColorPalette) = themePreferences.setPalette(value)

    val displayScale: StateFlow<DisplayScale> = displayPreferences.scale

    fun setDisplayScale(value: DisplayScale) = displayPreferences.setScale(value)

    val idleTimeout: StateFlow<IdleTimeout> = idlePreferences.timeout

    fun setIdleTimeout(value: IdleTimeout) = idlePreferences.setTimeout(value)

    val requestTimeout: StateFlow<RequestTimeout> = networkPreferences.timeout

    fun setRequestTimeout(value: RequestTimeout) = networkPreferences.setTimeout(value)

    private val handler = CoroutineExceptionHandler { _, t ->
        Timber.e(t, "settings action failed")
        _test.value = ConnectionTest.Failed(t.message ?: "Unexpected error")
    }

    val tenant: StateFlow<TenantConfig> = connectionSettings.tenant
    val profiles: StateFlow<List<TenantConfig>> = connectionSettings.profiles
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

    /**
     * Whether the Admin Center menu entry should show at all - see [WorkerRole]. Purely a UX
     * gate: the AOS service operations behind the Admin Center re-verify this worker is a
     * current supervisor themselves before writing anything, so a stale or tampered reading
     * here can hide or show a menu entry, never grant a permission.
     */
    val isAdmin: StateFlow<Boolean> = sessionRepository.session
        .map { it?.role == WorkerRole.ADMIN }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun canGoLive(): Boolean = connectionSettings.canGoLive()

    fun setPostingEnabled(enabled: Boolean) = connectionSettings.setPostingEnabled(enabled)

    /**
     * Switches to an already-saved profile - see [ConnectionSettings.activate]. Wipes local
     * data when this actually points the device somewhere new, same as [addProfile] and
     * [startDeviceCodeSignIn] - order and item data from two tenants must never mix.
     */
    fun switchProfile(label: String) {
        viewModelScope.launch(handler) {
            if (connectionSettings.activate(label)) wipeLocalData()
            _test.value = ConnectionTest.Idle
        }
    }

    /**
     * Registers a brand new, blank profile under [label] and makes it active, so it appears in
     * the profile list immediately and the fields below are ready to fill in. Blank fields mean
     * [TenantConfig.isComplete] is false, so nothing can go live on it until someone fills them
     * in and saves for real.
     */
    fun addProfile(label: String) {
        val trimmed = label.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch(handler) {
            if (connectionSettings.saveTenant(TenantConfig(label = trimmed))) wipeLocalData()
            _test.value = ConnectionTest.Idle
        }
    }

    /** Removes a saved, non-active profile. See [ConnectionSettings.deleteProfile]. */
    fun deleteProfile(label: String) {
        connectionSettings.deleteProfile(label)
    }

    /**
     * Wipes local order/item data AND the in-memory cached access token, together - every call
     * site already means "the active target just changed", and a cached token surviving that is
     * exactly as wrong as order data from the old target surviving it. Confirmed live: this gap
     * was the actual cause of a persistent 401 from AOS after switching DEV/UAT profiles - every
     * credential involved was correct, but a token cached while the previous profile was active
     * doesn't expire just because the active profile changed, so it kept getting reused against
     * a resource it was never issued for. See TokenProvider.invalidateCache's own doc comment.
     */
    private suspend fun wipeLocalData() = withContext(Dispatchers.IO) {
        database.clearAllTables()
        deviceCodeTokenProvider.invalidateCache()
        clientCredentialsTokenProvider.invalidateCache()
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
     * Saves [config] and starts an interactive device-code sign-in in one tap - there is no
     * separate "save" step on screen any more. The old two-step "save and test" wording made
     * sense back when device-code was one of two auth modes and testing the D365FO connection
     * was a reasonable thing to check before touching Azure AD; now that it's the only device
     * credential, a person filling in the form wants exactly one thing to happen: get signed in.
     *
     * Wipes local data first when this actually changes the active target, same as
     * [addProfile]/[switchProfile] - order and item data from two tenants must never mix.
     */
    fun startDeviceCodeSignIn(config: TenantConfig) {
        devicePollJob?.cancel()
        _deviceCode.value = DeviceCodeUiState.Starting

        devicePollJob = viewModelScope.launch(handler) {
            if (connectionSettings.saveTenant(config)) wipeLocalData()

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

    /**
     * Saves [config] and immediately tries to acquire a token with its client secret - the
     * client-credentials grant has no interactive step, so "save" and "test" are the same
     * click. Wipes local data first when this actually changes the active target, same as
     * [addProfile]/[switchProfile]/[startDeviceCodeSignIn].
     */
    fun saveAndSignInWithSecret(config: TenantConfig) {
        _test.value = ConnectionTest.Running
        viewModelScope.launch(handler) {
            if (connectionSettings.saveTenant(config)) wipeLocalData()

            _test.value = when (val result = clientCredentialsTokenProvider.acquireSilent()) {
                is NetworkResult.Error -> ConnectionTest.Failed(result.failure.message)
                NetworkResult.Loading -> ConnectionTest.Running
                is NetworkResult.Success -> ConnectionTest.Ok("Signed in with client secret.")
            }
        }
    }

    /**
     * Persists [config] and [dataAreaId] with no side effect beyond that - no sign-in attempt,
     * no connection test. Added back after the two folded-in "save and act" buttons
     * ([startDeviceCodeSignIn], [saveAndSignInWithSecret]) turned out to be the *only* way typed
     * fields ever reached storage: confirmed live that leaving this screen (or the process dying)
     * straight after typing, without tapping one of those, silently discarded the edit. A worker
     * setting up a device with no browser handy yet - or wanting to fix a typo without kicking off
     * a new sign-in - needs a way to just save.
     */
    fun saveConnection(config: TenantConfig, dataAreaId: String) {
        viewModelScope.launch(handler) {
            if (connectionSettings.saveTenant(config)) wipeLocalData()
            val company = dataAreaId.trim().uppercase()
            workerContextRepository.setRegistration(personnelNumber = null, dataAreaId = company)
            _test.value = ConnectionTest.Ok("Saved.")
        }
    }

    fun cancelDeviceCodeSignIn() {
        devicePollJob?.cancel()
        devicePollJob = null
        _deviceCode.value = deviceCodeTokenProvider.signedInUpn()?.let { DeviceCodeUiState.SignedIn(it) }
            ?: DeviceCodeUiState.Idle
    }
}
