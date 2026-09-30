package com.d365fo.mobileops.presentation.auth

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.d365fo.mobileops.data.security.SecureStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * The first-launch walkthrough's own one job: remember it has been seen.
 *
 * One flag per install (see [SecureStore.KEY_HAS_SEEN_ONBOARDING]), not per worker - a handheld
 * is shared across a shift, and the second person to sign into it should not see the same
 * walkthrough the first person just dismissed.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val secureStore: SecureStore,
) : ViewModel() {

    /** Read once at construction - this process only ever transitions false -> true, via
     *  [markSeen], never the other way, so there is nothing external to keep observing. */
    var hasSeenOnboarding by mutableStateOf(
        secureStore.getBoolean(SecureStore.KEY_HAS_SEEN_ONBOARDING),
    )
        private set

    fun markSeen() {
        secureStore.putBoolean(SecureStore.KEY_HAS_SEEN_ONBOARDING, true)
        hasSeenOnboarding = true
    }
}
