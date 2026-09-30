package com.d365fo.mobileops.presentation.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.d365fo.mobileops.data.repository.LicenseRepository
import com.d365fo.mobileops.data.repository.LicenseState
import com.d365fo.mobileops.data.security.license.LicenseVerifyResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives both halves of the licensing gate in `AppRoot`: the per-launch [LicenseState] check
 * ([validate]) and, when [LicenseState.NeedsActivation], the QR-scan activation flow
 * ([onCertificateScanned]) - see [LicenseActivationScreen].
 */
@HiltViewModel
class LicenseViewModel @Inject constructor(
    private val repository: LicenseRepository,
) : ViewModel() {

    val state: StateFlow<LicenseState> = repository.state

    private val _activationError = MutableStateFlow<String?>(null)
    val activationError: StateFlow<String?> = _activationError.asStateFlow()

    fun validate() {
        viewModelScope.launch { repository.validate() }
    }

    /**
     * A raw string decoded straight from [com.d365fo.mobileops.presentation.common.scan.CameraScannerSheet] -
     * **never** routed through `HidScannerBridge`/`RawScan.normalized()`, which uppercases
     * everything and would corrupt this case-sensitive base64url certificate string.
     */
    fun onCertificateScanned(rawCertificate: String) {
        when (val result = repository.activate(rawCertificate)) {
            is LicenseVerifyResult.Invalid -> _activationError.value =
                "This QR code did not verify: ${result.reason}. Ask your administrator for a fresh one."
            is LicenseVerifyResult.Valid -> {
                _activationError.value = null
                validate()
            }
        }
    }

    fun dismissActivationError() {
        _activationError.value = null
    }
}
