package com.d365fo.mobileops.presentation.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.d365fo.mobileops.data.repository.WorkerSession
import com.d365fo.mobileops.data.repository.WorkerSessionRepository
import com.d365fo.mobileops.data.security.IdlePreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Exposes who is signed in, for the gate above the navigation graph.
 *
 * Separate from WorkerLoginViewModel on purpose: that one owns the transient typing state of one
 * sign-in attempt and dies with the screen, while this one follows the session for as long as the
 * app is running.
 */
@HiltViewModel
class WorkerSessionViewModel @Inject constructor(
    private val sessions: WorkerSessionRepository,
    private val idlePreferences: IdlePreferences,
) : ViewModel() {

    val session: StateFlow<WorkerSession?> = sessions.session

    /** Non-null for a while after a sign-in whose license is nearing expiry - see the repository. */
    val licenseWarning: StateFlow<String?> = sessions.licenseWarning

    fun dismissLicenseWarning() = sessions.dismissLicenseWarning()

    fun signOut() = sessions.signOut()

    // Same "shared handheld, no remember me" reasoning as WorkerSessionRepository's own doc - a
    // device set down mid-shift and picked up later by someone else should not still be sitting
    // on the previous worker's identity. Owned here (viewModelScope, not a Composable's own
    // LaunchedEffect) so the countdown survives configuration changes and isn't tied to any one
    // screen's composition.
    private var idleJob: Job? = null

    init {
        viewModelScope.launch {
            sessions.session.collect { current ->
                if (current != null) armIdleTimer() else idleJob?.cancel()
            }
        }
    }

    /**
     * Call on every touch anywhere in the signed-in app (see AppRoot) - restarts the idle clock.
     * A no-op while signed out; nothing to time out and armIdleTimer would otherwise sign nobody
     * out 30 minutes after a stray touch on the login screen.
     */
    fun notifyActivity() {
        if (sessions.session.value != null) armIdleTimer()
    }

    private fun armIdleTimer() {
        idleJob?.cancel()
        // Read fresh every arm, not cached at construction - a change made in Settings mid-shift
        // takes effect from the next touch/restart rather than needing a sign-out first.
        val timeoutMillis = idlePreferences.timeout.value.minutes * 60 * 1000L
        idleJob = viewModelScope.launch {
            delay(timeoutMillis)
            sessions.signOut()
        }
    }
}
