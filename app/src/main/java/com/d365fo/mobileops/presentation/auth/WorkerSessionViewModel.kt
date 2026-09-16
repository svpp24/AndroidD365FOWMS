package com.d365fo.mobileops.presentation.auth

import androidx.lifecycle.ViewModel
import com.d365fo.mobileops.data.repository.WorkerSession
import com.d365fo.mobileops.data.repository.WorkerSessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
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
) : ViewModel() {

    val session: StateFlow<WorkerSession?> = sessions.session

    fun signOut() = sessions.signOut()
}
