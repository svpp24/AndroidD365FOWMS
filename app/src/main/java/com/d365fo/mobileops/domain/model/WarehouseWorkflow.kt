package com.d365fo.mobileops.domain.model

/**
 * The app menus (modules) an administrator allows or blocks per worker - set in D365FO on the
 * Mobile device users form, Menu access tab, and sent with sign-in. Names match HSMobileOpsModule.
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
    LOCATION_INQUIRY("Location inquiry"),
    PO_OF_TODAY("PO's of today"),
    PO_LINES("PO lines"),
}
