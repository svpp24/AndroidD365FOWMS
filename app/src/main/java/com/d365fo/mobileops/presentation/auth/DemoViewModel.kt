package com.d365fo.mobileops.presentation.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.d365fo.mobileops.data.demo.DemoLauncher
import com.d365fo.mobileops.data.demo.DemoMode
import com.d365fo.mobileops.util.NetworkResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** "Try the demo" on the first screen, and leaving the demo again. See [DemoMode]. */
@HiltViewModel
class DemoViewModel @Inject constructor(
    private val launcher: DemoLauncher,
) : ViewModel() {
    val active: StateFlow<Boolean> = DemoMode.active

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** True while signing in to the demo - the sign-out watcher in AppRoot leaves it alone. */
    val starting: Boolean get() = launcher.starting

    fun start(onStarted: () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        _error.value = null
        viewModelScope.launch {
            when (val r = launcher.start()) {
                is NetworkResult.Error -> _error.value = r.failure.message
                else -> Unit
            }
            _busy.value = false
            onStarted()
        }
    }

    fun end(onEnded: () -> Unit) {
        viewModelScope.launch {
            launcher.end()
            onEnded()
        }
    }
}
