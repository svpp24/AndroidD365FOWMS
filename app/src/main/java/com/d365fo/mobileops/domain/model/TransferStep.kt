package com.d365fo.mobileops.domain.model

import java.math.BigDecimal

/**
 * Which end of a transfer order is being worked.
 *
 * The same order is handled twice — shipped out of one warehouse, received into another — and the
 * two are not mirror images. Shipping takes stock off a pick face and has nowhere to put it;
 * receiving has to record where it went. That difference is what [stepsForTransfer] encodes.
 */
enum class TransferMode {
    SHIP,
    RECEIVE,
    ;

    /** The wire value the AOS service expects. */
    val wire: String get() = if (this == SHIP) "Ship" else "Receive"
}

/**
 * One prompt at a time, as with receiving — but the sequence depends on the direction.
 */
enum class TransferStep {
    /** Which transfer order. */
    TRANSFER_ORDER,

    /** Which item. Skipped when the order has exactly one line outstanding in this direction. */
    ITEM,

    /** How many, in the line's unit. */
    QUANTITY,

    /**
     * The bin. Shipping asks it first - the bin the worker picks from, before the item; receiving
     * asks it last - where the stock was put away, after the batch (feedback 46890e91,
     * 8 Oct 2026: pick is bin, item, qty, batch; receive is item, qty, batch, bin).
     */
    LOCATION,

    /** Batch number, when the item's tracking group has batch active. */
    BATCH,

    /** Serial number, one per unit, when the item is serial tracked. */
    SERIAL,

    CONFIRM,
}

/**
 * What the operator has entered so far on a transfer.
 */
data class TransferCapture(
    val transferId: String? = null,
    val mode: TransferMode = TransferMode.RECEIVE,
    val line: TransferOrderLine? = null,
    val quantity: BigDecimal? = null,
    val unitSymbol: String? = null,
    val fromWarehouseId: String? = null,
    val toWarehouseId: String? = null,
    val locationId: String? = null,
    val batchId: String? = null,
    val serialIds: List<String> = emptyList(),
    /** See the identical field on ReceivingCapture - the same per-process fallback flag. */
    val requireLocationCaptureFallback: Boolean = true,
    /** Optional steps the operator skipped - see [canSkipTransferStep]. */
    val skipped: Set<TransferStep> = emptySet(),
    /**
     * The warehouse master's default bin for this direction - issue when shipping, receipt
     * when receiving (feedback 88c029a1). It answers the bin step; scanning a bin overrides it.
     */
    val defaultLocationId: String? = null,
) {
    /** Serials needed: one per unit under serial number control, otherwise one for the line. */
    private val serialsNeeded: Int
        get() = if (line?.rules?.serialPerUnit == false) 1 else quantity?.toBigIntegerExact()?.toInt() ?: 0

    fun serialsComplete(): Boolean {
        if (quantity == null) return false
        return serialIds.size >= serialsNeeded
    }

    val serialsOutstanding: Int
        get() = (serialsNeeded - serialIds.size).coerceAtLeast(0)
}

/** A batch, serial or location step the dimension group does not require for this process. */
fun canSkipTransferStep(step: TransferStep, capture: TransferCapture): Boolean {
    val rules = capture.line?.rules ?: return false
    return when (step) {
        TransferStep.LOCATION -> !rules.locationMandatory
        TransferStep.BATCH -> !rules.batchMandatory
        TransferStep.SERIAL -> !rules.serialMandatory
        else -> false
    }
}

/**
 * The step sequence for a transfer capture, the way the work is done on the floor:
 *
 * - shipping (picking): bin, item, quantity, batch - the worker stands at the bin first;
 * - receiving: item, quantity, batch, bin - the carton is read in hand, the bin scanned once
 *   it is put away.
 *
 * The bin is asked only for an item that tracks location. Shipping asks before the item is
 * known, so it is asked unless the item turns out not to track one; receiving also honours the
 * per-process fallback flag.
 */
fun stepsForTransfer(capture: TransferCapture): List<TransferStep> = buildList {
    val line = capture.line
    val shipping = capture.mode == TransferMode.SHIP
    add(TransferStep.TRANSFER_ORDER)
    if (shipping && (line == null || line.locationRequired)) add(TransferStep.LOCATION)
    add(TransferStep.ITEM)
    add(TransferStep.QUANTITY)
    if (line?.batchRequired == true) add(TransferStep.BATCH)
    if (line?.serialRequired == true) add(TransferStep.SERIAL)
    if (!shipping && (line == null || (line.locationRequired && capture.requireLocationCaptureFallback))) {
        add(TransferStep.LOCATION)
    }
    add(TransferStep.CONFIRM)
}

/** The first step with no answer yet; see nextIncompleteStep for why it resumes from the gap. */
fun nextIncompleteTransferStep(capture: TransferCapture): TransferStep =
    stepsForTransfer(capture).first { step -> !isTransferStepAnswered(step, capture) }

private fun isTransferStepAnswered(step: TransferStep, capture: TransferCapture): Boolean =
    step in capture.skipped || when (step) {
        TransferStep.TRANSFER_ORDER -> !capture.transferId.isNullOrBlank()
        TransferStep.ITEM -> capture.line != null
        TransferStep.QUANTITY -> capture.quantity != null
        TransferStep.LOCATION -> !capture.locationId.isNullOrBlank()
        TransferStep.BATCH -> !capture.batchId.isNullOrBlank()
        TransferStep.SERIAL -> capture.serialsComplete()
        TransferStep.CONFIRM -> false
    }

/**
 * One line of a transfer order, as the handheld needs it.
 *
 * [remainingQuantity] is outstanding *in the direction being worked* — never the ordered quantity.
 * Receiving is bounded by what was shipped, and conflating the two is how an operator ends up
 * invited to book in stock that never left the source site.
 */
data class TransferOrderLine(
    val lineNumber: Double,
    val itemId: String,
    val itemName: String,
    val unitId: String,
    val transferQuantity: BigDecimal,
    val shippedQuantity: BigDecimal,
    val receivedQuantity: BigDecimal,
    val remainingQuantity: BigDecimal,
    val fromSiteId: String?,
    val fromWarehouseId: String?,
    val toSiteId: String?,
    val toWarehouseId: String?,
    /** The source-side location. A hint when shipping, never an answer when receiving. */
    val locationId: String?,
    /** Which variant of a product master the line is (product dimensions), from D365FO. */
    val variant: com.d365fo.mobileops.domain.model.ProductVariant = com.d365fo.mobileops.domain.model.ProductVariant(),
    val batchRequired: Boolean,
    val serialRequired: Boolean,
    val locationRequired: Boolean,
    /** Required vs optional per dimension for this process - see [com.d365fo.mobileops.domain.model.DimensionRules]. */
    val rules: com.d365fo.mobileops.domain.model.DimensionRules = com.d365fo.mobileops.domain.model.DimensionRules(),
    /** Units the quantity can be entered in, from the item's conversion setup - see [ItemUnit]. */
    val units: List<com.d365fo.mobileops.domain.model.ItemUnit> = emptyList(),
    /**
     * Receive direction: the batches in transit on this line - shipped and not yet received -
     * with their quantities. Empty when shipping, for an item without batches, or from a server
     * that does not send them; then any registered batch is taken, as before.
     */
    val shippedBatches: List<ShippedBatch> = emptyList(),
    /** The batch the line was created for, when one was chosen - a hint when shipping. */
    val lineBatchId: String? = null,
) {
    val hasOutstanding: Boolean get() = remainingQuantity.signum() > 0
}

data class ShippedBatch(val batchId: String, val quantity: BigDecimal)

/** Whether a batch scanned while receiving was shipped on the line, in that quantity. */
sealed interface ShippedBatchCheck {
    data object Ok : ShippedBatchCheck
    data class NotShipped(val shipped: List<ShippedBatch>) : ShippedBatchCheck
    data class TooMany(val shipped: BigDecimal, val alreadyScanned: BigDecimal) : ShippedBatchCheck
}

/**
 * [batchId] against [TransferOrderLine.shippedBatches]: it must be one that was shipped, and
 * [quantity] plus what this session already scanned of it ([alreadyScanned]) no more than was
 * shipped of it. A line without shipped batches takes any batch.
 */
fun checkShippedBatch(
    line: TransferOrderLine,
    batchId: String,
    quantity: BigDecimal,
    alreadyScanned: BigDecimal,
): ShippedBatchCheck {
    if (line.shippedBatches.isEmpty()) return ShippedBatchCheck.Ok
    val shipped = line.shippedBatches.firstOrNull { it.batchId.equals(batchId, ignoreCase = true) }
        ?: return ShippedBatchCheck.NotShipped(line.shippedBatches)
    return if (alreadyScanned + quantity > shipped.quantity) {
        ShippedBatchCheck.TooMany(shipped.quantity, alreadyScanned)
    } else {
        ShippedBatchCheck.Ok
    }
}

/** A transfer order with however many of its lines it was sensible to send. */
data class TransferOrderLookup(
    val transferId: String,
    val transferStatus: String?,
    val actionable: Boolean,
    val blockReason: String?,
    val fromSiteId: String?,
    val fromWarehouseId: String?,
    val toSiteId: String?,
    val toWarehouseId: String?,
    val outstandingLineCount: Int,
    val linesInline: Boolean,
    val lines: List<TransferOrderLine>,
    /**
     * Receive direction only - whether this process still asks for a put-away location when a
     * line does not track one it can use. Backoffice-configurable per process; see the
     * identical field on OrderLookup for the full explanation.
     */
    val requireLocationCapture: Boolean = true,
    /** The warehouses' default bins (feedback 88c029a1) - used unless another is scanned. */
    val defaultIssueLocation: String? = null,
    val defaultReceiptLocation: String? = null,
)
