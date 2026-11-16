package com.d365fo.mobileops.usecase

import com.d365fo.mobileops.data.repository.LicenseState
import com.d365fo.mobileops.data.repository.expiryBand
import com.d365fo.mobileops.data.repository.expiryDateOf
import com.d365fo.mobileops.presentation.auth.licenseBandAndSteps
import com.d365fo.mobileops.presentation.common.focus.BandKind
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

/** The daily license-expiry band and the license screen's wording (user, 16 Nov 2026). */
class LicenseNoticeTest {

    private val expiry = LocalDate.of(2027, 1, 1)

    @Test
    fun `band shows in the last 30 days only`() {
        assertThat(expiryBand(expiry, null, LocalDate.of(2026, 12, 1))).isNull() // 31 days
        val day30 = expiryBand(expiry, null, LocalDate.of(2026, 12, 2))!!
        assertThat(day30.kind).isEqualTo(BandKind.Warn)
        assertThat(day30.title).isEqualTo("License expires in 30 days")
        assertThat(day30.detail).isEqualTo("Renew by 1 Jan 2027.")
        val last = expiryBand(expiry, null, LocalDate.of(2026, 12, 31))!!
        assertThat(last.kind).isEqualTo(BandKind.Bad)
        assertThat(last.title).isEqualTo("License expires tomorrow")
        assertThat(expiryBand(expiry, null, expiry)).isNull() // expired: the license screen says it
    }

    @Test
    fun `hidden today comes back tomorrow`() {
        assertThat(expiryBand(expiry, "2026-12-20", LocalDate.of(2026, 12, 20))).isNull()
        assertThat(expiryBand(expiry, "2026-12-20", LocalDate.of(2026, 12, 21))).isNotNull()
    }

    @Test
    fun `expiry date from a license or the sign-in warning`() {
        assertThat(expiryDateOf("2027-01-01T00:00:00Z")).isEqualTo(expiry)
        assertThat(expiryDateOf("This app's license expires in 12 day(s) (on 2027-01-01T00:00:00Z). Contact your administrator to renew."))
            .isEqualTo(expiry)
        assertThat(expiryDateOf("no date")).isNull()
    }

    @Test
    fun `license screen bands never say contact your administrator`() {
        val noLicense = licenseBandAndSteps(LicenseState.Blocked("No license has been configured for this app. Contact your administrator.", "NO_LICENSE"))
        assertThat(noLicense.first.title).isEqualTo("No license")
        val expired = licenseBandAndSteps(LicenseState.Blocked("This app's license expired on 2027-01-01T00:00:00Z. Contact your administrator.", "LICENSE_EXPIRED"))
        assertThat(expired.first.title).isEqualTo("License expired")
        assertThat(expired.first.detail).isEqualTo("Expired on 1 Jan 2027.")
        assertThat(expired.second.first()).isEqualTo("Get the renewed license file from the vendor.")
        val limit = licenseBandAndSteps(
            LicenseState.Blocked("This app is licensed for 5 device(s), and that many are already active. Contact your administrator.", "DEVICE_LIMIT_REACHED"),
        )
        assertThat(limit.first.title).isEqualTo("Device limit reached")
        assertThat(limit.first.detail).doesNotContain("administrator")
    }
}
