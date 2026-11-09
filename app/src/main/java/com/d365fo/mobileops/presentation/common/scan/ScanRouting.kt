package com.d365fo.mobileops.presentation.common.scan

/**
 * What the screen in front of the worker accepts - each screen declares it and [ScanRouter]
 * routes by it (redesign brief §3.2). A flow updates its context as it moves: after the PO is
 * scanned, receiving accepts items and knows the order's lines.
 */
sealed interface ScanContext {

    /** Home: any scan starts the process it belongs to. */
    data object Home : ScanContext

    /**
     * Inside a process.
     *
     * @param accepts what moves the current step on - [ScanRoute.Accept].
     * @param documentKind what this process works on (a purchase order for receiving, a bin for
     *   store count): another one of these switches to it, the current one again is accepted
     *   (a bin label scanned twice settles an empty bin, brief §4).
     * @param documentItemIds the open lines' items, once known - an item outside it is
     *   [RejectReason.NOT_ON_DOCUMENT] and nothing is added. Null: not checked here.
     */
    data class Flow(
        val accepts: Set<ScanKind>,
        val documentKind: ScanKind? = null,
        val currentDocument: String? = null,
        val documentItemIds: Set<String>? = null,
    ) : ScanContext
}

/** Why a scan was turned away - the screen shows [ScanRoute.Reject.title] in a band. */
enum class RejectReason {
    /** Nothing on the device matches - a Bad band. */
    UNKNOWN,
    /** Known item, blocked or discontinued - a Bad band. */
    INACTIVE_ITEM,
    /** Known item, not on this document - a Bad band, nothing added (brief §4). */
    NOT_ON_DOCUMENT,
    /** A real thing, but not what this step needs (a location while items are expected) - a Warn band. */
    NOT_EXPECTED,
}

/** What to do with one scan. */
sealed interface ScanRoute {
    val identity: ScanIdentity

    // -- From Home: open the process the scan belongs to ---------------------------------------

    data class OpenPoReceiving(override val identity: ScanIdentity, val purchId: String) : ScanRoute

    /** Ship or receive by [direction]; null when the device can't tell - the screen asks which. */
    data class OpenTransfer(
        override val identity: ScanIdentity,
        val transferId: String,
        val direction: TransferDirection?,
    ) : ScanRoute

    data class OpenCountJournal(override val identity: ScanIdentity, val journalId: String) : ScanRoute

    /** A bin label starts a store count there (brief §3.2). */
    data class StartStoreCount(override val identity: ScanIdentity, val bin: BinRef) : ScanRoute

    data class OpenLocation(override val identity: ScanIdentity, val locationId: String) : ScanRoute

    /** An item opens On-hand. */
    data class OpenOnHand(override val identity: ScanIdentity.Item) : ScanRoute

    // -- Inside a flow ------------------------------------------------------------------------

    /** The type this step expects: the scan is the confirm, the step advances (brief §3). */
    data class Accept(override val identity: ScanIdentity) : ScanRoute

    /** Another document (or bin) of the flow's own kind: switch to it. */
    data class SwitchDocument(override val identity: ScanIdentity, val documentId: String) : ScanRoute

    /**
     * A document belonging to a different process, scanned mid-flow. [then] is where it goes from
     * Home - the flow finishes or keeps its work, then opens that.
     */
    data class Leave(override val identity: ScanIdentity, val then: ScanRoute) : ScanRoute

    /** Turned away: nothing was added. [title] and [line] are for the screen's status band. */
    data class Reject(
        override val identity: ScanIdentity,
        val reason: RejectReason,
        val title: String,
        val line: String,
    ) : ScanRoute
}

/**
 * Routes one identified scan for [context] - the second half of the brief's §3. Pure: no lookups,
 * no state, so every case is a plain unit test.
 */
fun routeScan(identity: ScanIdentity, context: ScanContext): ScanRoute = when (context) {
    ScanContext.Home -> routeFromHome(identity)
    is ScanContext.Flow -> routeInFlow(identity, context)
}

private fun routeFromHome(identity: ScanIdentity): ScanRoute = when (identity) {
    is ScanIdentity.Item -> ScanRoute.OpenOnHand(identity)
    is ScanIdentity.Document -> when (identity.kind) {
        ScanKind.PURCHASE_ORDER -> ScanRoute.OpenPoReceiving(identity, identity.documentId)
        ScanKind.TRANSFER_ORDER -> ScanRoute.OpenTransfer(identity, identity.documentId, identity.direction)
        else -> ScanRoute.OpenCountJournal(identity, identity.documentId)
    }
    is ScanIdentity.Bin -> ScanRoute.StartStoreCount(identity, identity.bin)
    is ScanIdentity.Location -> ScanRoute.OpenLocation(identity, identity.locationId)
    is ScanIdentity.Unknown -> unknown(identity)
}

private fun routeInFlow(identity: ScanIdentity, flow: ScanContext.Flow): ScanRoute {
    if (identity is ScanIdentity.Unknown) return unknown(identity)

    // The flow's own kind of document: the current one again is a real action (a bin scanned
    // twice), any other switches to it.
    if (flow.documentKind != null && identity.kind == flow.documentKind) {
        val id = identity.documentKey()
        return if (flow.currentDocument != null && !id.equals(flow.currentDocument, ignoreCase = true)) {
            ScanRoute.SwitchDocument(identity, id)
        } else {
            ScanRoute.Accept(identity)
        }
    }

    if (identity.kind in flow.accepts) {
        if (identity is ScanIdentity.Item) {
            if (!identity.item.active) {
                return ScanRoute.Reject(
                    identity, RejectReason.INACTIVE_ITEM,
                    "Item blocked",
                    "${identity.item.itemId} is blocked in D365FO - nothing was added",
                )
            }
            val lines = flow.documentItemIds
            if (lines != null && lines.none { it.equals(identity.item.itemId, ignoreCase = true) }) {
                return ScanRoute.Reject(
                    identity, RejectReason.NOT_ON_DOCUMENT,
                    "Wrong item",
                    "${identity.item.itemId} is not on " +
                        (flow.currentDocument?.let { "this order ($it)" } ?: "this order") + " - nothing was added",
                )
            }
        }
        return ScanRoute.Accept(identity)
    }

    // Another process's document: go there, rather than misreading it as an item or a bin.
    if (identity is ScanIdentity.Document) return ScanRoute.Leave(identity, routeFromHome(identity))

    return ScanRoute.Reject(
        identity, RejectReason.NOT_EXPECTED,
        "Scan " + flow.accepts.sortedBy { it.ordinal }.joinToString(" or ") { it.noun() },
        "${identity.documentKey()} is ${identity.kind.noun()}",
    )
}

private fun unknown(identity: ScanIdentity): ScanRoute.Reject {
    val gs1 = (identity as? ScanIdentity.Unknown)?.gs1
    return if (gs1 != null) {
        ScanRoute.Reject(
            identity, RejectReason.UNKNOWN,
            "Item not found",
            "GTIN ${gs1.gtin} isn't in the item list on this device",
        )
    } else {
        ScanRoute.Reject(
            identity, RejectReason.UNKNOWN,
            "Not recognised",
            "${identity.raw.value.trim()} isn't an item, order, location or bin",
        )
    }
}

/** The id a scan stands for - the document, location, bin or item number. */
private fun ScanIdentity.documentKey(): String = when (this) {
    is ScanIdentity.Document -> documentId
    is ScanIdentity.Location -> locationId
    is ScanIdentity.Bin -> bin.binId
    is ScanIdentity.Item -> item.itemId
    is ScanIdentity.Unknown -> raw.value.trim()
}

private fun ScanKind.noun(): String = when (this) {
    ScanKind.ITEM -> "an item"
    ScanKind.PURCHASE_ORDER -> "a purchase order"
    ScanKind.TRANSFER_ORDER -> "a transfer order"
    ScanKind.COUNT_JOURNAL -> "a counting journal"
    ScanKind.LOCATION -> "a location"
    ScanKind.BIN -> "a bin label"
    ScanKind.UNKNOWN -> "not recognised"
}
