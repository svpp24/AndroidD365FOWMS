package com.d365fo.mobileops.data.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * How long the main OkHttpClient waits on a D365FO OData/service call before giving up. See
 * [NetworkModule.provideOkHttpClient][com.d365fo.mobileops.di.NetworkModule] for where this is
 * applied - read once when that client is built, so a changed value takes effect on the next
 * app start, not immediately.
 */
enum class RequestTimeout(val label: String, val seconds: Long) {
    SHORT("30s", 30),
    STANDARD("60s", 60),
    LONG("2 min", 120),
    EXTENDED("5 min", 300),
}

/**
 * Which [RequestTimeout] this device is set to. Per-device, not per-worker - stored the same way
 * as [ThemePreferences], but unlike the accent colour this only takes effect on the next app
 * start (see [RequestTimeout]'s own doc comment), so it lives alongside the other
 * commissioning-time settings in Settings rather than the day-to-day worker ones.
 */
@Singleton
class NetworkPreferences @Inject constructor(
    private val secureStore: SecureStore,
) {
    private val _timeout = MutableStateFlow(loadTimeout())
    val timeout: StateFlow<RequestTimeout> = _timeout.asStateFlow()

    fun setTimeout(value: RequestTimeout) {
        secureStore.putString(KEY_TIMEOUT, value.name)
        _timeout.value = value
    }

    private fun loadTimeout(): RequestTimeout =
        secureStore.getString(KEY_TIMEOUT)
            ?.let { runCatching { RequestTimeout.valueOf(it) }.getOrNull() }
            ?: RequestTimeout.STANDARD

    private companion object {
        const val KEY_TIMEOUT = "network_request_timeout"
    }
}
