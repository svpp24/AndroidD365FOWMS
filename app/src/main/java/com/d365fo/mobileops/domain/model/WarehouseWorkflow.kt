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
    TRANSFER_ORDERS("Transfer orders"),
    TRANSFERS_BY_STORE("TO's by Store"),
    PICKED_SERIALS("Picked serials"),
    PO_CREATE("PO creation"),
    TO_CREATE("TO creation"),
    PO_CONFIRM("PO confirmation"),
    PURCHASE_RETURN("Purchase return"),
    ;

    /**
     * Creating orders needs an explicit allow under Menu access: no row means blocked, unlike
     * every other module (absence-means-allowed). D365FO enforces the same - see
     * HSMobileOpsOrderCreateService::mayCreate.
     */
    val requiresExplicitAllow: Boolean get() = this == PO_CREATE || this == TO_CREATE || this == PO_CONFIRM || this == PURCHASE_RETURN
}

/**
 * The modules a worker may not use, from their Menu access rows (Mobile device users in D365FO).
 *
 * An allow list: once a worker has any rows, only the modules ticked Allowed show. A worker with
 * no rows at all keeps every normal module, so nobody loses access before an admin sets them up.
 * The order modules ([WarehouseWorkflow.requiresExplicitAllow]) always need an Allowed row.
 */
fun blockedWorkflows(allowed: Set<WarehouseWorkflow>, hasRows: Boolean): Set<WarehouseWorkflow> =
    WarehouseWorkflow.entries.filter { workflow ->
        workflow !in allowed && (hasRows || workflow.requiresExplicitAllow)
    }.toSet()
