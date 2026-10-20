package com.d365fo.mobileops.presentation.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.d365fo.mobileops.data.local.D365Database
import com.d365fo.mobileops.data.repository.WorkerContextRepositoryImpl
import com.d365fo.mobileops.data.repository.WorkerSessionRepository
import com.d365fo.mobileops.data.security.ClientCredentialsTokenProvider
import com.d365fo.mobileops.data.security.ConnectionSettings
import com.d365fo.mobileops.data.security.CredentialRouter
import com.d365fo.mobileops.data.security.CredentialStatus
import com.d365fo.mobileops.data.security.DisplayPreferences
import com.d365fo.mobileops.data.security.DisplayScale
import com.d365fo.mobileops.data.security.IdlePreferences
import com.d365fo.mobileops.data.security.IdleTimeout
import com.d365fo.mobileops.data.security.NetworkPreferences
import com.d365fo.mobileops.data.security.RequestTimeout
import com.d365fo.mobileops.data.security.TenantConfig
import com.d365fo.mobileops.domain.model.WorkerContext
import com.d365fo.mobileops.domain.model.role
import com.d365fo.mobileops.util.NetworkResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
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

/**
 * The signed-in worker as Settings shows it: display name and user ID ("Pradeep · 001"), or
 * whichever of the two is set. D365FO falls back to the user ID for a blank display name, so
 * the two can be the same - shown once then.
 */
internal fun signedInLabel(displayName: String?, userName: String?): String {
    val name = displayName?.trim().orEmpty()
    val id = userName?.trim().orEmpty()
    return when {
        name.isNotEmpty() && id.isNotEmpty() && !name.equals(id, ignoreCase = true) -> "$name · $id"
        name.isNotEmpty() -> name
        id.isNotEmpty() -> id
        else -> "Signed in"
    }
}

/** Result of the "test connection" action, shown inline under the form. */
sealed interface ConnectionTest {
    data object Idle : ConnectionTest
    data object Running : ConnectionTest
    data class Ok(val detail: String) : ConnectionTest
    data class Failed(val reason: String) : ConnectionTest
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val connectionSettings: ConnectionSettings,
    private val workerContextRepository: WorkerContextRepositoryImpl,
    private val clientCredentialsTokenProvider: ClientCredentialsTokenProvider,
    credentialRouter: CredentialRouter,
    private val networkPreferences: NetworkPreferences,
    private val displayPreferences: DisplayPreferences,
    private val idlePreferences: IdlePreferences,
    private val database: D365Database,
    private val unsentWork: com.d365fo.mobileops.data.sync.UnsentWork,
    private val offlineSignIn: com.d365fo.mobileops.data.security.OfflineSignIn,
    workerSessions: WorkerSessionRepository,
) : ViewModel() {

    val displayScale: StateFlow<DisplayScale> = displayPreferences.scale

    fun setDisplayScale(value: DisplayScale) = displayPreferences.setScale(value)

    val idleTimeout: StateFlow<IdleTimeout> = idlePreferences.timeout

    fun setIdleTimeout(value: IdleTimeout) = idlePreferences.setTimeout(value)

    private val handler = CoroutineExceptionHandler { _, t ->
        Timber.e(t, "settings action failed")
        _test.value = ConnectionTest.Failed(t.message ?: "Unexpected error")
    }

    val tenant: StateFlow<TenantConfig> = connectionSettings.tenant
    val profiles: StateFlow<List<TenantConfig>> = connectionSettings.profiles
    val postingEnabled: StateFlow<Boolean> = connectionSettings.postingEnabled

    private val _test = MutableStateFlow<ConnectionTest>(ConnectionTest.Idle)
    val test: StateFlow<ConnectionTest> = _test.asStateFlow()

    /** This device's app registration and how often D365FO has throttled it - see [CredentialRouter]. */
    val credentialStatus: StateFlow<CredentialStatus> = credentialRouter.status

    val worker: StateFlow<WorkerContext> = workerContextRepository.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WorkerContext.UNAUTHENTICATED)

    /**
     * Who is signed in on this handheld, as Session shows it - see [signedInLabel]. From the
     * handheld session, not [worker]: that one's display name is the service token's user
     * principal name, blank under client credentials, so Session only ever said "Signed in".
     */
    val signedInAs: StateFlow<String> = workerSessions.session
        .map { signedInLabel(it?.displayName, it?.userName) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            workerSessions.session.value.let { signedInLabel(it?.displayName, it?.userName) },
        )

    init {
        // A profile saved before profiles carried a company takes the device's one, once.
        viewModelScope.launch(handler) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                connectionSettings.backfillCompany(workerContextRepository.current().dataAreaId)
            }
        }
    }

    fun canGoLive(): Boolean = connectionSettings.canGoLive()

    fun setPostingEnabled(enabled: Boolean) = connectionSettings.setPostingEnabled(enabled)

    /** Settings > Data posting > Post immediately - see NetworkPreferences.postImmediately. */
    val postImmediately: StateFlow<Boolean> = networkPreferences.postImmediately

    fun setPostImmediately(value: Boolean) = networkPreferences.setPostImmediately(value)

    /**
     * Switches to an already-saved profile - see [ConnectionSettings.activate]. Wipes local
     * data when this actually points the device somewhere new, same as [addProfile] - order and
     * item data from two tenants must never mix.
     */
    fun switchProfile(label: String) = guardUnsent(connectionSettings.wouldChangeTarget(label)) {
        viewModelScope.launch(handler) {
            if (connectionSettings.activate(label)) wipeLocalData()
            // Each profile carries its own company (from its QR code) - the device follows it.
            connectionSettings.tenant.value.company.takeIf { it.isNotBlank() }?.let {
                workerContextRepository.setRegistration(personnelNumber = null, dataAreaId = it)
            }
            _test.value = ConnectionTest.Idle
        }
    }

    /** Work that would be wiped by the pending connection change, and that change - see [guardUnsent]. */
    data class UnsentBlock(val description: String, val proceed: () -> Unit)

    private val _unsentBlock = kotlinx.coroutines.flow.MutableStateFlow<UnsentBlock?>(null)
    val unsentBlock: StateFlow<UnsentBlock?> = _unsentBlock

    fun keepUnsent() {
        _unsentBlock.value = null
    }

    fun deleteUnsentAndProceed() {
        val block = _unsentBlock.value ?: return
        _unsentBlock.value = null
        block.proceed()
    }

    /**
     * Runs [action] - unless it would point the device somewhere new while work on it hasn't
     * reached D365FO, in which case it asks first (see UnsentWorkDialog). The wipe that follows
     * a target change would otherwise take that work with it.
     */
    private fun guardUnsent(changesTarget: Boolean, action: () -> Unit) {
        if (!changesTarget) return action()
        viewModelScope.launch(handler) {
            val unsent = unsentWork.summary()
            if (unsent.isEmpty) action() else _unsentBlock.value = UnsentBlock(unsent.describe(), action)
        }
    }


    /** Removes a saved, non-active profile. See [ConnectionSettings.deleteProfile]. */
    /**
     * Deletes a saved profile - the active one too (see [ConnectionSettings.deleteProfile]).
     * Deleting the active one points the device elsewhere, so it wipes local data, and asks
     * first when work on the device hasn't reached D365FO (see [guardUnsent]).
     */
    fun deleteProfile(label: String) = guardUnsent(label == connectionSettings.tenant.value.label) {
        viewModelScope.launch(handler) {
            if (connectionSettings.deleteProfile(label)) {
                wipeLocalData()
                connectionSettings.tenant.value.company.takeIf { it.isNotBlank() }?.let {
                    workerContextRepository.setRegistration(personnelNumber = null, dataAreaId = it)
                }
            }
            _test.value = ConnectionTest.Idle
        }
    }

    /** How much work would wait (or go) - shown in the remove/reset confirmations. */
    suspend fun unsentDescription(): String? = unsentWork.summary().takeIf { !it.isEmpty }?.describe()

    /**
     * Clears the credentials of every profile and the offline sign-ins. Local data - including
     * work not yet in D365FO - is kept: it is sent once a connection QR code is scanned again.
     */
    fun resetConnection() {
        connectionSettings.clearAllCredentials()
        offlineSignIn.forgetAll()
        clientCredentialsTokenProvider.invalidateCache()
        _test.value = ConnectionTest.Idle
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
        clientCredentialsTokenProvider.invalidateCache()
    }

    /**
     * Saves [config] and immediately tries to acquire a token with its client secret - the
     * client-credentials grant has no interactive step, so "save" and "test" are the same
     * click. Wipes local data first when this actually changes the active target, same as
     * [addProfile]/[switchProfile].
     */
    fun saveAndSignInWithSecret(config: TenantConfig) = guardUnsent(connectionSettings.wouldChangeTarget(config)) {
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
}
