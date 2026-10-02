package com.d365fo.mobileops.counting

import com.d365fo.mobileops.data.counting.OpportunityGate
import com.d365fo.mobileops.domain.FeatureFlags
import com.d365fo.mobileops.domain.counting.OpportunityTask
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class OpportunityGateTest {

    private val remote = FakeCountTaskRepository()
    private val flags = TestFlags(countOpportunity = true)
    private val gate = OpportunityGate(remote, flags)

    private val task = OpportunityTask(
        journalId = "OPP-1",
        journalNameId = "Count",
        description = null,
        lineNumber = 1,
        itemId = "ITEM-A",
        itemName = "Item A",
        locationId = "A-01",
        batchId = null,
        serialId = null,
        siteId = "S1",
        warehouseId = "WH1",
    )

    @Test
    fun `server offers an opportunity count - it is shown`() = runTest {
        remote.opportunity = task
        assertThat(gate.offer("WH1", "A-01", "ITEM-A")).isEqualTo(task)
    }

    @Test
    fun `server says no - nothing is shown`() = runTest {
        remote.opportunity = null
        assertThat(gate.offer("WH1", "A-01", "ITEM-A")).isNull()
        assertThat(remote.opportunityCalls).isEqualTo(1)
    }

    @Test
    fun `the same spot is not asked about twice in a session`() = runTest {
        remote.opportunity = task
        gate.offer("WH1", "A-01", "ITEM-A")
        assertThat(gate.offer("WH1", "a-01", "item-a")).isNull()
        assertThat(remote.opportunityCalls).isEqualTo(1)

        gate.offer("WH1", "A-02", "ITEM-A")
        assertThat(remote.opportunityCalls).isEqualTo(2)
    }

    @Test
    fun `feature off or no location - the server is never asked`() = runTest {
        assertThat(OpportunityGate(remote, TestFlags()).offer("WH1", "A-01", "ITEM-A")).isNull()
        assertThat(gate.offer("WH1", " ", "ITEM-A")).isNull()
        assertThat(remote.opportunityCalls).isEqualTo(0)
    }
}

data class TestFlags(
    override val countClaims: Boolean = false,
    override val countDirect: Boolean = false,
    override val countLocationFirst: Boolean = false,
    override val countExceptions: Boolean = false,
    override val countSupervisor: Boolean = false,
    override val countSpot: Boolean = false,
    override val countSpotForWorkers: Boolean = false,
    override val countOpportunity: Boolean = false,
    override val countDiscrepancy: Boolean = false,
    override val countTriggeredAlerts: Boolean = false,
    override val countLocationTypes: Boolean = false,
    override val countExternalCounters: Boolean = false,
) : FeatureFlags
