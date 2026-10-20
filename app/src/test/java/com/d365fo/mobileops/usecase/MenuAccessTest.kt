package com.d365fo.mobileops.usecase

import com.d365fo.mobileops.domain.model.WarehouseWorkflow
import com.d365fo.mobileops.domain.model.WarehouseWorkflow.PO_CONFIRM
import com.d365fo.mobileops.domain.model.WarehouseWorkflow.PURCHASE_RETURN
import com.d365fo.mobileops.domain.model.WarehouseWorkflow.PO_CREATE
import com.d365fo.mobileops.domain.model.WarehouseWorkflow.PO_RECEIVING
import com.d365fo.mobileops.domain.model.WarehouseWorkflow.TO_CREATE
import com.d365fo.mobileops.domain.model.WarehouseWorkflow.TRANSFER_SHIP
import com.d365fo.mobileops.domain.model.blockedWorkflows
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** Menu access (Mobile device users) as an allow list - see [blockedWorkflows]. */
class MenuAccessTest {

    @Test
    fun `a worker with no rows keeps every normal module, but not the order modules`() {
        val blocked = blockedWorkflows(allowed = emptySet(), hasRows = false)

        assertThat(blocked).containsExactly(PO_CREATE, TO_CREATE, PO_CONFIRM, PURCHASE_RETURN)
    }

    @Test
    fun `a worker with rows sees only what is allowed`() {
        // Worker 002 on the dev VM: Transfer - Ship and the three order modules allowed.
        val allowed = setOf(TRANSFER_SHIP, PO_CREATE, TO_CREATE, PO_CONFIRM)

        val blocked = blockedWorkflows(allowed = allowed, hasRows = true)

        assertThat(blocked).isEqualTo(WarehouseWorkflow.entries.toSet() - allowed)
        assertThat(blocked).contains(PO_RECEIVING)
    }

    @Test
    fun `rows that are all not-allowed block everything`() {
        val blocked = blockedWorkflows(allowed = emptySet(), hasRows = true)

        assertThat(blocked).isEqualTo(WarehouseWorkflow.entries.toSet())
    }

    @Test
    fun `an allowed order module shows for a worker with only that row`() {
        val blocked = blockedWorkflows(allowed = setOf(PO_CREATE), hasRows = true)

        assertThat(blocked).doesNotContain(PO_CREATE)
        assertThat(blocked).contains(TRANSFER_SHIP)
    }
}
