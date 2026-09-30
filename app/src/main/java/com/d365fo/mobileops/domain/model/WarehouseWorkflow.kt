package com.d365fo.mobileops.domain.model

/**
 * The four warehouse workflows an Admin can individually enable or disable per worker.
 *
 * Kept as its own enum rather than reusing `Routes.RECEIVE_FLOW`/etc. (`AppNavGraph.kt`)
 * directly: those are plain strings that exist for Compose Navigation's own reasons (and are
 * deliberately not grouped into any enum today), while permission storage - Room locally, a new
 * `HSMobileOpsModule` base enum on the AOS side - needs a stable identifier of its own. Mapping
 * one to the other is a single `when` at the Home screen boundary (`HomeScreen.kt`), not
 * something every layer needs to share.
 */
enum class WarehouseWorkflow(val label: String) {
    PO_RECEIVING("PO receiving"),
    TRANSFER_SHIP("Transfer — Ship"),
    TRANSFER_RECEIVE("Transfer — Receive"),
    INVENTORY_COUNT("Inventory count"),
}
