package com.d365fo.mobileops.usecase

import com.d365fo.mobileops.presentation.common.signedInLabel
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** Settings > Session: who is signed in on the handheld. */
class SignedInLabelTest {

    @Test
    fun `name and user ID both show`() {
        assertThat(signedInLabel("Pradeep", "001")).isEqualTo("Pradeep · 001")
    }

    @Test
    fun `display name that is the user ID shows once`() {
        assertThat(signedInLabel("001", "001")).isEqualTo("001")
    }

    @Test
    fun `either one alone shows`() {
        assertThat(signedInLabel("", "001")).isEqualTo("001")
        assertThat(signedInLabel("Pradeep", null)).isEqualTo("Pradeep")
    }

    @Test
    fun `no session falls back to Signed in`() {
        assertThat(signedInLabel(null, null)).isEqualTo("Signed in")
        assertThat(signedInLabel("  ", "")).isEqualTo("Signed in")
    }
}
