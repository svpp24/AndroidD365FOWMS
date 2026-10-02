package com.d365fo.mobileops.counting

import com.d365fo.mobileops.data.counting.countingCapabilities
import com.d365fo.mobileops.domain.counting.CounterProfile
import com.d365fo.mobileops.domain.model.freezeBanner
import com.d365fo.mobileops.domain.model.locationTypeLabel
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class CountingRolesAndFreezeTest {

    private val everything = TestFlags(countSupervisor = true, countSpot = true, countSpotForWorkers = true)

    @Test
    fun `an external counter never gets supervisor screens or spot counts`() {
        val caps = countingCapabilities(
            everything,
            CounterProfile(isExternalCounter = true, counterTeam = "ACME", isCycleCountSupervisor = true),
            sessionIsCycleCountSupervisor = true,
        )
        assertThat(caps.externalCounter).isTrue()
        assertThat(caps.canReviewVariance).isFalse()
        assertThat(caps.canCreateSpot).isFalse()
    }

    @Test
    fun `a supervisor profile from the server wins over the sign-in flag`() {
        val caps = countingCapabilities(
            everything,
            CounterProfile(isExternalCounter = false, counterTeam = null, isCycleCountSupervisor = false),
            sessionIsCycleCountSupervisor = true,
        )
        assertThat(caps.canReviewVariance).isFalse()
        assertThat(caps.canCreateSpot).isTrue() // workers may create spot counts by flag
    }

    @Test
    fun `without a profile the sign-in supervisor flag applies`() {
        val caps = countingCapabilities(TestFlags(countSupervisor = true, countSpot = true), null, sessionIsCycleCountSupervisor = true)
        assertThat(caps.canReviewVariance).isTrue()
        assertThat(caps.canCreateSpot).isTrue()

        val worker = countingCapabilities(TestFlags(countSupervisor = true, countSpot = true), null, sessionIsCycleCountSupervisor = false)
        assertThat(worker.canReviewVariance).isFalse()
        assertThat(worker.canCreateSpot).isFalse()
    }

    @Test
    fun `freeze banner says what the server decided`() {
        assertThat(freezeBanner("NONE", null) { "x" }).isNull()
        assertThat(freezeBanner("LOCATION_FROZEN", null) { "x" }).contains("blocked")
        assertThat(freezeBanner("SNAPSHOT_ONLY", 1_000L) { "14:30" }).isEqualTo("Count is measured against stock at 14:30.")
    }

    @Test
    fun `location types get badges, physical stock does not`() {
        assertThat(locationTypeLabel("PHYSICAL")).isNull()
        assertThat(locationTypeLabel("TRANSIT")).isEqualTo("In transit")
        assertThat(locationTypeLabel("CONSIGNMENT_VENDOR")).isNotNull()
    }

    @Test
    fun `a confirmed verify-only line counts as done without a quantity`() {
        val line = countLine(1, "ITEM", "TRANSIT").copy(verifyOnly = true, locationType = "TRANSIT")
        assertThat(line.isCounted).isFalse()
        assertThat(line.copy(confirmedAt = 5L).isCounted).isTrue()
        assertThat(line.copy(confirmedAt = 5L).countedQuantity).isNull()
    }
}

