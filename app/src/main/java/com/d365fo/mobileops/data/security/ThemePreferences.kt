package com.d365fo.mobileops.data.security

import com.d365fo.mobileops.presentation.common.theme.ColorPalette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which [ColorPalette] this device is set to. Per-device, not per-worker: it lives in
 * [SecureStore] the same way the tenant/auth settings do, so it survives a sign-out and applies
 * before anyone has signed in, rather than resetting every shift.
 */
@Singleton
class ThemePreferences @Inject constructor(
    private val secureStore: SecureStore,
) {
    private val _palette = MutableStateFlow(loadPalette())
    val palette: StateFlow<ColorPalette> = _palette.asStateFlow()

    fun setPalette(value: ColorPalette) {
        secureStore.putString(KEY_PALETTE, value.name)
        _palette.value = value
    }

    private fun loadPalette(): ColorPalette =
        secureStore.getString(KEY_PALETTE)
            ?.let { runCatching { ColorPalette.valueOf(it) }.getOrNull() }
            ?: ColorPalette.BLUE

    private companion object {
        const val KEY_PALETTE = "color_palette"
    }
}
