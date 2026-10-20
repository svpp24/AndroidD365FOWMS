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

    /**
     * Settings > Data posting > Post immediately (feedback 301e5d0e). On: each posting asks
     * D365FO to process it in the same call (ProcessNow) and the worker sees it posted - or
     * refused, with the reason - before moving on. Off (the default): D365FO stages it as
     * Pending and its batch job or "Process pending" posts it within minutes, so the worker
     * never waits on a slow posting.
     */
    private val _postImmediately = MutableStateFlow(secureStore.getString(KEY_POST_IMMEDIATELY) == "1")
    val postImmediately: StateFlow<Boolean> = _postImmediately.asStateFlow()

    fun setPostImmediately(value: Boolean) {
        secureStore.putString(KEY_POST_IMMEDIATELY, if (value) "1" else "0")
        _postImmediately.value = value
    }

    private fun loadTimeout(): RequestTimeout =
        secureStore.getString(KEY_TIMEOUT)
            ?.let { runCatching { RequestTimeout.valueOf(it) }.getOrNull() }
            ?: RequestTimeout.STANDARD

    private companion object {
        const val KEY_TIMEOUT = "network_request_timeout"
        const val KEY_POST_IMMEDIATELY = "post_immediately"
    }
}
