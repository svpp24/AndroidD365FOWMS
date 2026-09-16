package com.d365fo.mobileops.presentation.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.d365fo.mobileops.data.repository.WorkerContextRepositoryImpl
import com.d365fo.mobileops.data.security.ConnectionSettings
import com.d365fo.mobileops.data.security.TokenProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Whether the *device* may talk to D365FO at all.
 *
 * This gate is about the handheld, not the person. Every call to D365FO needs an AAD bearer
 * token, and the device gets one as itself — a service principal, from a configured client
 * secret, acquired silently. There is nothing here for a human to answer, so there is no
 * sign-in screen: the only question is whether the tenant has been configured.
 *
 * Who is holding the device is a separate question, asked by [WorkerLoginScreen] against
 * `HSMobileOpsUser`. That is the identity a posting is attributed to.
 */
sealed interface SessionState {
    /** Deciding — the configuration check is in flight. */
    data object Checking : SessionState

    /** No tenant configured yet. Nothing can reach D365FO until Settings is filled in. */
    data object NotConfigured : SessionState

    data class SignedOut(val reason: String? = null) : SessionState

    data class Ready(val displayName: String) : SessionState
}

@HiltViewModel
class SessionViewModel @Inject constructor(
    private val tokenProvider: TokenProvider,
    private val connectionSettings: ConnectionSettings,
    private val workerContextRepository: WorkerContextRepositoryImpl,
    private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val handler = CoroutineExceptionHandler { _, t ->
        Timber.e(t, "session check failed")
        _state.value = SessionState.SignedOut(t.message)
    }

    private val _state = MutableStateFlow<SessionState>(SessionState.Checking)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    init {
        refresh()
    }

    /**
     * Decides the device gate, and starts warming the device's AAD token in the background.
     *
     * The token fetch used to be left entirely to the interceptor, on the theory that fetching
     * here would put a round trip in front of the login screen. That reasoning was backwards in
     * practice: deferring it doesn't remove the round trip, it just moves it to land on top of
     * the worker's own tap of "Sign in" instead. Measured on the P2mini, the AAD token request
     * alone took 9.1 s — network/DNS/TLS latency to login.microsoftonline.com that this app has
     * no way to shorten — stacked on top of the ~2 s the sign-in call itself costs for its
     * deliberately slow password hash. Together that read as an 11-second "sign-in is taking
     * time".
     *
     * Firing it here instead overlaps that 9 s with however long the worker spends typing a
     * user name and password — which is normally longer than 9 s on its own. By the time they
     * tap Sign in, [ClientCredentialsTokenProvider] usually already holds a cached token, and
     * [AuthInterceptor] finds it instantly instead of blocking the request on a fresh fetch.
     * Launched on its own coroutine, not awaited: a slow or failed token fetch must not delay
     * the sign-in screen appearing, and a real failure still surfaces normally on whichever
     * call needs the token first.
     */
    fun refresh() {
        viewModelScope.launch(handler) {
            // Off the main thread, all of it.
            //
            // The first call into settings is what forces SecureStore to build itself, and
            // that means Keystore key material and an encrypted file — tens to hundreds of
            // milliseconds on a handheld. Making SecureStore lazy moved that work later;
            // this is what moves it off the thread drawing the first frame.
            val ready = withContext(ioDispatcher) {
                if (!connectionSettings.canGoLive()) return@withContext null
                workerContextRepository.markServiceIdentity()
                workerContextRepository.current()
            }

            _state.value = if (ready == null) {
                SessionState.NotConfigured
            } else {
                SessionState.Ready(ready.displayName.ifBlank { "Service account" })
            }
        }

        if (connectionSettings.canGoLive()) {
            viewModelScope.launch(handler) { tokenProvider.acquireSilent() }
        }
    }

    fun signOut() {
        viewModelScope.launch(handler) {
            // Drops the cached device token too. The next person to pick the handheld up
            // starts from nothing rather than inheriting a live connection.
            tokenProvider.signOut()
            _state.value = SessionState.SignedOut()
        }
    }
}
