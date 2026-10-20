package com.d365fo.mobileops.usecase

import com.d365fo.mobileops.util.Formatters
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.Locale

class DayLabelTest {

    private val today = LocalDate.of(2026, 10, 4)

    private fun label(iso: String) = Formatters.dayLabel(iso, today, Locale.UK)

    @Test
    fun `nearby days read as words`() {
        assertThat(label("2026-10-04")).isEqualTo("Today")
        assertThat(label("2026-10-05")).isEqualTo("Tomorrow")
        assertThat(label("2026-10-03")).isEqualTo("Yesterday")
    }

    @Test
    fun `the year shows only outside the current one`() {
        assertThat(label("2026-07-17")).isEqualTo("17 Jul")
        assertThat(label("2025-12-15")).isEqualTo("15 Dec 2025")
    }

    @Test
    fun `a datetime keeps its date and anything else comes back unchanged`() {
        assertThat(label("2026-10-05T00:00:00Z")).isEqualTo("Tomorrow")
        assertThat(label("")).isEqualTo("")
        assertThat(label("soon")).isEqualTo("soon")
        assertThat(Formatters.isoDay("1900-01-01")).isNull()
    }
}
