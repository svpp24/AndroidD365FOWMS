package com.d365fo.mobileops.data.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * How far text and button sizing are scaled up from the app's own default. Never below 100% -
 * see [com.d365fo.mobileops.presentation.common.theme.WarehouseDimens.MinTouchTarget]'s own doc
 * comment for why that floor exists; a scale that could go below 1.0x could shrink a button under
 * it.
 */
enum class DisplayScale(val label: String, val factor: Float) {
    STANDARD("100%", 1.0f),
    LARGE("115%", 1.15f),
    EXTRA_LARGE("130%", 1.3f),
}

/**
 * Which [DisplayScale] this worker has chosen. Per-worker, not per-device - stored the same way
 * as [ThemePreferences], and applies live: see
 * [com.d365fo.mobileops.presentation.common.theme.D365MobileTheme].
 */
@Singleton
class DisplayPreferences @Inject constructor(
    private val secureStore: SecureStore,
) {
    private val _scale = MutableStateFlow(loadScale())
    val scale: StateFlow<DisplayScale> = _scale.asStateFlow()

    fun setScale(value: DisplayScale) {
        secureStore.putString(KEY_SCALE, value.name)
        _scale.value = value
    }

    private fun loadScale(): DisplayScale =
        secureStore.getString(KEY_SCALE)
            ?.let { runCatching { DisplayScale.valueOf(it) }.getOrNull() }
            ?: DisplayScale.STANDARD

    private companion object {
        const val KEY_SCALE = "display_scale"
    }
}
