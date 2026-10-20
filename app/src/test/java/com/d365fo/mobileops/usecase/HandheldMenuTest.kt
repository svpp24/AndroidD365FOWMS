package com.d365fo.mobileops.usecase

import com.d365fo.mobileops.domain.model.HandheldMenu
import com.d365fo.mobileops.domain.model.MenuDefinition
import com.d365fo.mobileops.domain.model.MenuEntry
import com.d365fo.mobileops.domain.model.WarehouseWorkflow
import com.d365fo.mobileops.domain.model.WarehouseWorkflow.INVENTORY_COUNT
import com.d365fo.mobileops.domain.model.WarehouseWorkflow.PO_CREATE
import com.d365fo.mobileops.domain.model.WarehouseWorkflow.PO_RECEIVING
import com.d365fo.mobileops.domain.model.WarehouseWorkflow.TRANSFER_RECEIVE
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class HandheldMenuTest {

    private fun menu(id: String, vararg entries: MenuEntry) = MenuDefinition(id, id.lowercase(), entries.toList())
    private fun module(w: WarehouseWorkflow) = MenuEntry.Module(w)
    private fun sub(id: String) = MenuEntry.SubMenu(id)

    private val tree = HandheldMenu(
        rootId = "MAIN",
        menus = listOf(
            menu("MAIN", sub("INBOUND"), sub("INVENTORY"), module(INVENTORY_COUNT)),
            menu("INBOUND", module(PO_RECEIVING), module(TRANSFER_RECEIVE), module(PO_CREATE)),
            menu("INVENTORY", module(INVENTORY_COUNT)),
        ).associateBy { it.id },
    )

    @Test
    fun `a menu shows its lines in order`() {
        assertThat(tree.visibleEntries("MAIN") { true })
            .containsExactly(sub("INBOUND"), sub("INVENTORY"), module(INVENTORY_COUNT)).inOrder()
    }

    @Test
    fun `modules the worker may not use are left out`() {
        assertThat(tree.visibleEntries("INBOUND") { it != PO_CREATE })
            .containsExactly(module(PO_RECEIVING), module(TRANSFER_RECEIVE)).inOrder()
    }

    @Test
    fun `a sub-menu with nothing left in it is left out`() {
        assertThat(tree.visibleEntries("MAIN") { it != INVENTORY_COUNT }).containsExactly(sub("INBOUND"))
    }

    @Test
    fun `menu ids match without regard to case`() {
        assertThat(tree.visibleEntries("inbound") { true }).hasSize(3)
        assertThat(tree.menu("Main")).isNotNull()
    }

    @Test
    fun `a sub-menu that leads back to an open menu is not followed`() {
        val loop = HandheldMenu(
            rootId = "A",
            menus = listOf(menu("A", sub("B")), menu("B", sub("A"))).associateBy { it.id },
        )
        assertThat(loop.visibleEntries("A") { true }).isEmpty()
    }

    @Test
    fun `an unknown menu shows nothing`() {
        assertThat(tree.visibleEntries("NOPE") { true }).isEmpty()
    }
}
