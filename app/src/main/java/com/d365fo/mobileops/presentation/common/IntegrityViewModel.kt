package com.d365fo.mobileops.presentation.common

import androidx.lifecycle.ViewModel
import com.d365fo.mobileops.data.security.SelfIntegrityCheck
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * A one-shot read of [SelfIntegrityCheck.tampered] - computed once, at process start, and never
 * expected to change for the life of the process, so a plain property is enough; no StateFlow
 * needed for a value that cannot itself go stale within one run of the app.
 */
@HiltViewModel
class IntegrityViewModel @Inject constructor(check: SelfIntegrityCheck) : ViewModel() {
    val tampered: Boolean = check.tampered
}
