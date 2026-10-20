package com.d365fo.mobileops.presentation.common.tour

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.d365fo.mobileops.data.repository.WorkerSessionRepository
import com.d365fo.mobileops.data.security.SecureStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.util.Locale
import javax.inject.Inject

/**
 * Whether the navigation tour shows: once per worker, the first time they reach Home on this
 * device - per worker, not per device like the first-run walkthrough, since a shared handheld's
 * second worker has never seen the app either. [replay] (Settings) shows it again.
 */
@HiltViewModel
class TourViewModel @Inject constructor(
    private val secureStore: SecureStore,
    private val sessions: WorkerSessionRepository,
) : ViewModel() {

    val show: StateFlow<Boolean> = combine(sessions.session, changes) { session, _ ->
        val user = session?.userName ?: return@combine false
        !secureStore.getBoolean(key(user))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun markSeen() {
        val user = sessions.session.value?.userName ?: return
        secureStore.putBoolean(key(user), true)
        changes.value++
    }

    fun replay() {
        val user = sessions.session.value?.userName ?: return
        secureStore.putBoolean(key(user), false)
        changes.value++
    }

    private fun key(user: String) = "navigation_tour_seen::" + user.trim().lowercase(Locale.ROOT)

    private companion object {
        /** Bumped whenever a flag changes, so every instance's [show] re-reads it - Settings'
         *  replay and Home's overlay hold different instances. */
        val changes = MutableStateFlow(0)
    }
}
