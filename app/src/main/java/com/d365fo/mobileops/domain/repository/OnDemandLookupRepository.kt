package com.d365fo.mobileops.domain.repository

import com.d365fo.mobileops.domain.model.OrderStatus
import com.d365fo.mobileops.domain.model.PurchaseOrder
import com.d365fo.mobileops.domain.model.PurchaseOrderLine
import com.d365fo.mobileops.util.NetworkResult

/**
 * Fetches exactly one thing, when the operator asks for it.
 *
 * This is the alternative to the preload model, and on this deployment it is the only one that
 * holds up. Nothing the app reads publishes a `ModifiedDateTime`, so there is no delta to pull:
 * a preload can only ever be a *full* pull of the item master, the barcode master, every
 * location and every open order — repeated in full on every sync, growing with the tenant, and
 * mostly wasted because a shift touches a handful of orders.
 *
 * Here a receipt costs a bounded, predictable number of small queries: one order header, that
 * order's lines, that order's posted receipts, and one lookup per dimension the operator
 * actually captures. Nothing is fetched until a scan asks for it.
 *
 * The trade is deliberate and worth stating plainly: **every step needs the network.** The
 * preload model let an operator work a whole shift with the radio down; this one does not. What
 * survives offline is everything already captured — the draft, the queue, and the post — because
 * those still write to Room first. A scan cannot be *validated* offline, and the flow says so
 * rather than accepting the scan and discovering the problem at posting time.
 */
interface OnDemandLookupRepository {

    /**
     * One purchase order, with its lines and their received quantities.
     *
     * Received quantity is summed from posted product receipts, because the line entity
     * publishes only the ordered quantity. Returns [com.d365fo.mobileops.util.AppFailure] rather
     * than an empty order when the number is unknown: at a receiving bay, "no such PO" and "that
     * PO has nothing left to receive" call for completely different responses from the operator.
     */
    suspend fun lookupOrder(purchId: String): NetworkResult<OrderLookup>

    /**
     * The outstanding lines for one item on one order.
     *
     * Used when [OrderLookup.linesInline] is false — that is, when the order carries too many
     * lines to send whole. A purchase order here can run to thousands of lines, and fetching all
     * of them so the device can filter to the one item just scanned means moving megabytes to draw
     * a single prompt. Asking per item makes the cost track the delivery rather than the order.
     */
    suspend fun lookupLinesForItem(
        purchId: String,
        itemNumber: String,
    ): NetworkResult<List<PurchaseOrderLine>>

    /**
     * Resolves a scanned barcode to an item against D365FO.
     *
     * Null means the barcode is not registered — which is a legitimate answer, not an error;
     * vendors put their own labels on cartons all the time.
     */
    suspend fun lookupBarcode(barcode: String): NetworkResult<BarcodeMatch?>

    /** Item master for one item: units, and whether it needs a batch, serial or location. */
    suspend fun lookupItem(itemNumber: String): NetworkResult<ItemFacts?>

    /**
     * Whether a location exists in this warehouse and can be received into.
     *
     * A location blocked for input is reported as existing-but-unusable rather than missing:
     * an operator who typed it correctly needs to know it is blocked, not that it is wrong.
     */
    suspend fun lookupLocation(
        warehouseId: String,
        locationId: String,
    ): NetworkResult<LocationFacts?>

    /** On-hand for one item, in one warehouse. Scanned on demand from the enquiry screen. */
    suspend fun lookupOnHand(
        itemNumber: String,
        warehouseId: String?,
    ): NetworkResult<List<OnHandLine>>

    /**
     * Purchase orders requested for delivery today, for the "PO's of today" enquiry.
     *
     * A header-only browse, not a keyed lookup — the one place in this repository that returns
     * more than one order at once. Bounded by today's date rather than a `$top` that hopes for
     * the best: a legal entity's daily intake is small, where "every open order" is not.
     */
    suspend fun lookupOrdersForToday(warehouseId: String?): NetworkResult<List<PurchaseOrderSummary>>

    /** True when D365FO already knows this serial — the layer-4 uniqueness check. */
    /**
     * Warehouses in the current legal entity, for the on-hand enquiry filter.
     *
     * Read once per screen and held in memory: the list changes about as often as the
     * warehouse estate does, and re-fetching it on every scan would put a second round trip
     * in front of an answer the operator is waiting for.
     */
    suspend fun listWarehouses(): NetworkResult<List<WarehouseOption>>

    suspend fun isSerialRegistered(itemNumber: String, serial: String): NetworkResult<Boolean>

    /**
     * True when this item and batch combination already has a master record in D365FO.
     *
     * False means the operator's scan is the first anyone has heard of this batch — the client
     * uses that to ask for a manufacturing and expiry date before the batch can be used, rather
     * than finding out only when the queued posting comes back rejected with nobody there to
     * answer.
     */
    suspend fun isBatchRegistered(itemNumber: String, batchId: String): NetworkResult<Boolean>
}

/**
 * An order, with however many of its lines it was sensible to send.
 *
 * [outstandingLineCount] is always the true total. [linesInline] says whether
 * [PurchaseOrder.lines] holds them: false with a non-zero count is not a failure, it means the
 * order is large enough that lines are fetched per item instead.
 */
data class OrderLookup(
    val order: PurchaseOrder,
    val outstandingLineCount: Int,
    val linesInline: Boolean,
    /**
     * Why the order cannot be received against, when it cannot: NOT_CONFIRMED, NOT_APPROVED,
     * NOT_OPEN. Null when it can.
     *
     * Reported separately from a plain refusal because the two blocked states send the operator to
     * different people — one is waiting on purchasing to confirm and send the order, the other on
     * a manager to approve it.
     */
    val blockReason: String? = null,
    /**
     * Whether receiving still asks for a put-away location when a line has none of its own.
     *
     * Backoffice-configurable per process (HSMobileOpsProcessSetup.RequireLocationCapture) -
     * true unless a supervisor has turned it off. A line that already carries a location is
     * unaffected either way: [stepsFor] always uses that and never asks, regardless of this
     * flag. Defaults true on any lookup path that does not resolve it (the OData fallback, the
     * offline cache), matching the old behaviour of always asking.
     */
    val requireLocationCapture: Boolean = true,
)

/** What a scanned barcode resolved to. */
data class BarcodeMatch(
    val barcode: String,
    val itemNumber: String,
    /** Units per scan, for a case or inner-pack barcode. One for a plain item barcode. */
    val quantityPerScan: java.math.BigDecimal,
    val unitSymbol: String?,
    /** The variant a variant barcode identifies; empty for an item-level barcode. */
    val variant: com.d365fo.mobileops.domain.model.ProductVariant = com.d365fo.mobileops.domain.model.ProductVariant(),
)

/** The item facts a receiving step needs, and nothing else. */
data class ItemFacts(
    val itemNumber: String,
    val productName: String,
    val inventoryUnit: String,
    val purchaseUnit: String?,
    val batchRequired: Boolean,
    val serialRequired: Boolean,
    val locationRequired: Boolean,
)

data class LocationFacts(
    val warehouseId: String,
    val locationId: String,
    /** False when a blocking cause is set for inbound movement. */
    val canReceiveInto: Boolean,
    val blockingCause: String?,
)

data class OnHandLine(
    val itemNumber: String,
    val siteId: String?,
    val warehouseId: String?,
    val onHand: java.math.BigDecimal,
    val available: java.math.BigDecimal,
    val reserved: java.math.BigDecimal,
    val ordered: java.math.BigDecimal,
    val totalAvailable: java.math.BigDecimal,
)

/** One row of the "PO's of today" enquiry — a header, not the full order and its lines. */
data class PurchaseOrderSummary(
    val purchId: String,
    val vendorAccount: String,
    val status: OrderStatus,
    val siteId: String?,
    val warehouseId: String?,
    val deliveryDate: Long?,
    /** Something is still left to receive - D365FO's own reading, see HSMobileOpsPurchListService. */
    val hasOpenLines: Boolean = true,
) {
    /** Worth offering for receiving: an open status and a line still to receive. */
    val receivable: Boolean get() = status.isOpen && hasOpenLines
}

/** One selectable warehouse. */
data class WarehouseOption(
    val warehouseId: String,
    val warehouseName: String,
    val siteId: String,
) {
    /** "Main store (AAC)", falling back to the code alone when there is no name. */
    val label: String
        get() = if (warehouseName.isBlank()) warehouseId else "$warehouseName ($warehouseId)"
}
