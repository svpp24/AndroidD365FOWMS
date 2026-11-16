package com.d365fo.mobileops.presentation.auth

import androidx.lifecycle.ViewModel
import com.d365fo.mobileops.data.repository.LicenseExpiryNotice
import com.d365fo.mobileops.presentation.common.focus.BandState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/** Home's license-expiry band - see [LicenseExpiryNotice]. */
@HiltViewModel
class LicenseNoticeViewModel @Inject constructor(private val notice: LicenseExpiryNotice) : ViewModel() {

    private val _band = MutableStateFlow<BandState?>(null)
    val band: StateFlow<BandState?> = _band.asStateFlow()

    /** Home came to the front: today's band, if any. */
    fun refresh() {
        _band.value = notice.band()
    }

    /** The x: gone until tomorrow. */
    fun hideToday() {
        notice.hideToday()
        _band.value = null
    }

    /** D365FO's sign-in warning names the expiry date - kept for the daily band. */
    fun recordFromWarning(warning: String) {
        notice.recordFromWarning(warning)
        refresh()
    }
}
