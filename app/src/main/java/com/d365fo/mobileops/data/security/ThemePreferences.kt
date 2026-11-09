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

    /**
     * Always Industrial - the Focus design's navy (docs/ui-redesign-brief.md, 10 Nov). There is no
     * palette picker, so a colour saved by an older build is ignored rather than kept.
     */
    private fun loadPalette(): ColorPalette = ColorPalette.INDUSTRIAL
}
