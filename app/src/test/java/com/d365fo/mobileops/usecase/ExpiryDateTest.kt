package com.d365fo.mobileops.usecase

import com.d365fo.mobileops.presentation.modules.stepflow.displayDate
import com.d365fo.mobileops.presentation.modules.stepflow.parseExpiry
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** Expiry date entry as DD-MM-YYYY, sent to D365FO as YYYY-MM-DD. */
class ExpiryDateTest {

    @Test
    fun `day-month-year in the common shapes`() {
        assertThat(parseExpiry("31-12-2027")).isEqualTo("2027-12-31")
        assertThat(parseExpiry("31/12/2027")).isEqualTo("2027-12-31")
        assertThat(parseExpiry("5.3.2028")).isEqualTo("2028-03-05")
        assertThat(parseExpiry("31122027")).isEqualTo("2027-12-31")
        // Six digits, day first, and two-digit years with separators.
        assertThat(parseExpiry("311228")).isEqualTo("2028-12-31")
        assertThat(parseExpiry("050328")).isEqualTo("2028-03-05")
        assertThat(parseExpiry("31-12-28")).isEqualTo("2028-12-31")
        assertThat(com.d365fo.mobileops.presentation.modules.stepflow.wordsDate("2028-12-31")).isEqualTo("31 Dec 2028")
    }

    @Test
    fun `a GS1 barcode's ISO date still fills it in`() {
        assertThat(parseExpiry("2027-12-31")).isEqualTo("2027-12-31")
    }

    @Test
    fun `dates that do not exist are refused`() {
        assertThat(parseExpiry("31-02-2027")).isNull()
        assertThat(parseExpiry("12-31-2027")).isNull()
        assertThat(parseExpiry("tomorrow")).isNull()
        assertThat(parseExpiry("311328")).isNull()
    }

    @Test
    fun `shown as day-month-year`() {
        assertThat(displayDate("2027-12-31")).isEqualTo("31-12-2027")
    }
}
