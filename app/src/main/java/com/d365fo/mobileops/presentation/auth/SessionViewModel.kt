package com.d365fo.mobileops.presentation.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.d365fo.mobileops.data.repository.WorkerContextRepositoryImpl
import com.d365fo.mobileops.data.security.ConnectionSettings
import com.d365fo.mobileops.data.security.TokenProvider
import com.d365fo.mobileops.util.AppFailure
import com.d365fo.mobileops.util.NetworkResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Whether the *device* may talk to D365FO at all.
 *
 * This gate is about the handheld, not the person. Every call to D365FO needs an AAD bearer
 * token, and the device gets one as itself, from the client credentials the connection QR code
 * set (device-code sign-in was removed). Only the *field* completeness of the connection
 * settings used to gate [SessionState.Ready] - confirmed live as a real gap: a device whose
 * fields are filled in but whose secret is wrong or revoked still read as Ready, so the failure
 * only ever surfaced later, confusingly, at a
 * worker's own sign-in attempt. [refresh] now also waits on the token check itself before
 * confirming Ready, precisely so a device that cannot actually get a token lands on
 * [NotConfiguredScreen]'s "Ask a supervisor..." message instead.
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
        // A connection saved on an unconfigured device moves it on at once, as reopening the
        // app did (30 Oct: first install sat on "Setting up…" with the connection already in
        // use). Only from NotConfigured - there is no live data or signed-in worker to disturb.
        viewModelScope.launch {
            connectionSettings.tenant.drop(1).collect {
                if (_state.value is SessionState.NotConfigured) refresh()
            }
        }
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
     * tap Sign in, the token provider usually already holds a cached token, and
     * [AuthInterceptor] finds it instantly instead of blocking the request on a fresh fetch.
     * Launched on its own coroutine, not awaited by the [SessionState.Ready] decision above it -
     * a slow token fetch must not delay the sign-in screen appearing, and the overwhelmingly
     * common case (a cached, still-valid token) resolves instantly with no network call and
     * never touches [_state] at all.
     *
     * A genuine **failure** does though: it corrects [_state] away from the optimistic Ready set
     * above, to [SessionState.SignedOut] with the real reason. Confirmed live as a real gap
     * before this existed - [canGoLive] only checks that the connection *fields* are filled in,
     * which is true for a device that has never completed device-code sign-in at all (or whose
     * refresh token has since been revoked) just as much as for one that has, so Ready used to
     * mean nothing more than "somebody typed something into Settings once". The failure used to
     * surface only much later, confusingly, at a worker's own sign-in attempt, which had nothing
     * to do with the actual problem. Safe to overwrite unconditionally here because [refresh] is
     * only ever called right after launch or right after [signOut] - never mid-shift while a
     * worker is already past this gate - so this can't yank an active worker out from under
     * themselves over an unrelated API call's own, separately-handled failure elsewhere.
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
                Timber.i("device session: not configured")
                SessionState.NotConfigured
            } else {
                Timber.i("device session: ready ('%s')", ready.displayName)
                SessionState.Ready(ready.displayName.ifBlank { "Service account" })
            }
        }

        if (connectionSettings.canGoLive()) {
            viewModelScope.launch(handler) {
                when (val result = tokenProvider.acquireSilent()) {
                    is NetworkResult.Error -> when (result.failure) {
                        // No connection is not a broken setup: stay on the sign-in screen, where
                        // offline sign-in works. The token is fetched again on the next call.
                        is AppFailure.Offline, is AppFailure.Transient ->
                            Timber.w("device session: offline, token not checked - %s", result.failure.message)
                        else -> {
                            Timber.w("device session: token check failed - %s", result.failure.message)
                            _state.value = SessionState.SignedOut(result.failure.message)
                        }
                    }
                    NetworkResult.Loading, is NetworkResult.Success -> Unit
                }
            }
        }
    }

    fun signOut() {
        Timber.i("device session: signing out")
        viewModelScope.launch(handler) {
            // Drops the cached device token too. The next person to pick the handheld up
            // starts from nothing rather than inheriting a live connection - refresh() right
            // after re-proves that connection immediately, the same check a fresh launch does,
            // so a normal sign-out lands the next worker on the sign-in screen instead of
            // parking on NotConfiguredScreen's "cannot reach D365FO" until something else
            // happens to call refresh() again. If the device genuinely cannot reconnect,
            // refresh() surfaces that for real - this only removes the pointless detour for
            // the ordinary case where it can.
            tokenProvider.signOut()
            refresh()
        }
    }
}
