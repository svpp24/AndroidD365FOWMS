package com.d365fo.mobileops.data.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * How long a signed-in worker may leave the app untouched before WorkerSessionViewModel's idle
 * timer locks them out automatically. Five-minute steps from 5 to 30 - 30 is the ceiling this app
 * shipped with originally, kept as the upper bound rather than removed so a device can be set to
 * something more forgiving than the 15-minute default without being left unbounded.
 */
enum class IdleTimeout(val label: String, val minutes: Long) {
    MIN_5("5 min", 5),
    MIN_10("10 min", 10),
    MIN_15("15 min", 15),
    MIN_20("20 min", 20),
    MIN_25("25 min", 25),
    MIN_30("30 min", 30),
}

/**
 * Which [IdleTimeout] this device is set to. Per-device, not per-worker - stored the same way as
 * [ThemePreferences]/[NetworkPreferences], so a supervisor sets it once per handheld rather than
 * it resetting every shift.
 */
@Singleton
class IdlePreferences @Inject constructor(
    private val secureStore: SecureStore,
) {
    private val _timeout = MutableStateFlow(loadTimeout())
    val timeout: StateFlow<IdleTimeout> = _timeout.asStateFlow()

    fun setTimeout(value: IdleTimeout) {
        secureStore.putString(KEY_IDLE_TIMEOUT, value.name)
        _timeout.value = value
    }

    private fun loadTimeout(): IdleTimeout =
        secureStore.getString(KEY_IDLE_TIMEOUT)
            ?.let { runCatching { IdleTimeout.valueOf(it) }.getOrNull() }
            ?: IdleTimeout.MIN_15

    private companion object {
        const val KEY_IDLE_TIMEOUT = "idle_timeout_minutes"
    }
}
