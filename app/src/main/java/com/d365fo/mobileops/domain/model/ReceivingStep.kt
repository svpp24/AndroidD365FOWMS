package com.d365fo.mobileops.domain.model

import java.math.BigDecimal

/**
 * One prompt at a time, in the order a WMS handheld asks for it.
 *
 * The step is the whole screen. An operator holding a scanner in one hand and a carton in the
 * other reads exactly one question, scans exactly one thing, and is either advanced or told why
 * not. There is no form to fill in, no field to tab between, and nothing to scroll — the ordering
 * below *is* the interaction design, which is why it lives in the domain rather than the UI.
 *
 * Steps are not a fixed list. Which ones appear is decided per line, from the item's dimension
 * groups: an item with no tracking dimensions never sees a batch prompt. Asking for a dimension
 * D365FO does not track is not merely noise — the posting rejects it.
 */
enum class ReceivingStep {
    /** Which purchase order. The only step that is always first, and always required. */
    PURCHASE_ORDER,

    /**
     * The vendor's delivery note number.
     *
     * No longer asked - see [stepsFor]. This was written to the receipt journal as the vendor's
     * reference; now that receiving only registers (see HSMobileOpsPostingService's class doc)
     * there is no receipt journal for it to reconcile against, so the step cost a scan for a
     * field nothing downstream reads. [ReceivingCapture.vendorPackingSlipId] and the plumbing
     * that carries it to the AOS both still exist, idle, in case a real product-receipt posting
     * comes back and needs it again.
     */
    PACKING_SLIP,

    /**
     * Which line. Scanning the item resolves it, so this step is skipped when the order has
     * exactly one line still outstanding — the answer is not in doubt and asking wastes a scan.
     */
    ITEM,

    /** How many, in the line's purchase unit. */
    QUANTITY,

    /**
     * Where the stock is being put away.
     *
     * The bulk storage location it ends up in, not the dock it landed on. In a non-WMS receipt
     * there is no separate put-away transaction to correct this afterwards — the location posted
     * on the receipt *is* where the system believes the stock now sits, so what the operator scans
     * here has to be where they actually put it.
     *
     * Skipped entirely when the purchase order line already carries a location — that is used
     * as-is, never re-asked. Otherwise shown when the item's storage dimension group tracks
     * location *and* [ReceivingCapture.requireLocationCaptureFallback] says this process still
     * asks when a line does not supply one; a supervisor can turn that off per process in
     * HSMobileOpsProcessSetup.
     */
    LOCATION,

    /** Batch number. Shown only when the item's tracking dimension group has batch active. */
    BATCH,

    /**
     * The batch's expiry date.
     *
     * Shown only when the batch just scanned has no InventBatch master record yet in D365FO -
     * an existing batch already has its own dates, decided whenever it was first created, and
     * the operator is not asked to re-supply them for a batch someone else already registered.
     * Manufacturing date is deliberately not captured here — expiry is what a receiving decision
     * actually turns on, and asking for both cost a second prompt nobody used for anything.
     */
    BATCH_EXPIRY_DATE,

    /**
     * Serial number.
     *
     * A serial-tracked item is captured one unit at a time, because a serial identifies exactly
     * one physical unit. Ten units means ten serials, and the flow loops here rather than
     * accepting a quantity of ten against one serial.
     */
    SERIAL,

    /** Everything captured, shown back before it is committed. */
    CONFIRM,
}

/**
 * What the operator has entered so far.
 *
 * Deliberately flat and immutable: each step returns a new capture, so going back is dropping a
 * field rather than unwinding a mutation, and the confirm screen is a pure function of this.
 */
data class ReceivingCapture(
    val purchId: String? = null,
    val vendorAccount: String? = null,
    /** The vendor's delivery note. One per visit to the bay, retained across lines. */
    val vendorPackingSlipId: String? = null,
    val line: PurchaseOrderLine? = null,
    val item: ItemDimensions? = null,
    val quantity: BigDecimal? = null,
    val unitSymbol: String? = null,
    val siteId: String? = null,
    val warehouseId: String? = null,
    val locationId: String? = null,
    val batchId: String? = null,
    /**
     * Null until the batch step resolves a live answer, then fixed for the rest of this line.
     * True means D365FO has never seen this item-and-batch combination before, which is what
     * turns on the two date steps below.
     */
    val batchIsNew: Boolean? = null,
    val batchExpiryDate: String? = null,
    val serialIds: List<String> = emptyList(),
    /**
     * Whether this process still asks for a location when a line does not supply one - the
     * order's own answer to HSMobileOpsProcessSetup.RequireLocationCapture, carried on the
     * capture because [stepsFor] is a pure function of it and has no other way to see it. True
     * (keep asking) is the safe default before an order has been resolved.
     */
    val requireLocationCaptureFallback: Boolean = true,
    /** Optional steps the operator skipped - see [canSkipStep]. */
    val skipped: Set<ReceivingStep> = emptySet(),
) {
    /** Serials needed: one per unit under serial number control, otherwise one for the line. */
    private val serialsNeeded: Int
        get() = if (line?.rules?.serialPerUnit == false) 1 else quantity?.toBigIntegerExact()?.toInt() ?: 0

    /**
     * Serial capture is complete when there is one serial per unit.
     *
     * Compared by value rather than by count of a scaled quantity: a serial-tracked item can
     * only be received in whole units, and 3 vs 3.000000 must not be the difference between
     * "done" and "one more please".
     */
    fun serialsComplete(): Boolean {
        if (quantity == null) return false
        return serialIds.size >= serialsNeeded
    }

    val serialsOutstanding: Int
        get() = (serialsNeeded - serialIds.size).coerceAtLeast(0)
}

/**
 * What D365FO tracks for this item, resolved once at the item step.
 *
 * These are the *tracking* and *storage* dimensions — the ones captured while receiving. The
 * product dimensions (configuration, size, colour, style, version) are deliberately absent: they
 * identify which product variant a purchase order line is for, and that choice was made when the
 * order was raised. An operator does not pick a colour at the bay; scanning an item that resolves
 * to a different variant than the line is a mismatch to reject, not a dimension to capture.
 */
data class ItemDimensions(
    val itemNumber: String,
    val productName: String,
    val inventoryUnit: String,
    val batchRequired: Boolean,
    val serialRequired: Boolean,
    val locationRequired: Boolean,
)

/**
 * The step sequence for one captured line.
 *
 * Computed rather than stored, so it cannot drift from the capture it describes. Called on every
 * advance, which is cheap and means a correction at the item step immediately reshapes the rest
 * of the flow — change to an untracked item and the batch prompt simply is not there any more.
 */
fun stepsFor(capture: ReceivingCapture): List<ReceivingStep> = buildList {
    add(ReceivingStep.PURCHASE_ORDER)
    // PACKING_SLIP deliberately not added - see its own doc.
    add(ReceivingStep.ITEM)
    add(ReceivingStep.QUANTITY)
    val item = capture.item
    // A line that already carries a location is always used as-is - see the class doc on
    // LOCATION. Only when it does not does the item's own tracking, combined with the
    // per-process fallback flag, decide whether to ask.
    if (capture.line?.locationId.isNullOrBlank() &&
        (item == null || (item.locationRequired && capture.requireLocationCaptureFallback))
    ) {
        add(ReceivingStep.LOCATION)
    }
    if (item?.batchRequired == true) {
        add(ReceivingStep.BATCH)
        if (capture.batchIsNew == true) add(ReceivingStep.BATCH_EXPIRY_DATE)
    }
    if (item?.serialRequired == true) add(ReceivingStep.SERIAL)
    add(ReceivingStep.CONFIRM)
}

/**
 * The first step that has no answer yet.
 *
 * Resuming from the earliest gap rather than from a stored cursor means a correction cannot
 * leave the flow pointing past a field it just cleared.
 */
fun nextIncompleteStep(capture: ReceivingCapture): ReceivingStep =
    stepsFor(capture).first { step -> !isAnswered(step, capture) }

/** A batch, serial or location step the dimension group does not require for a receipt. */
fun canSkipStep(step: ReceivingStep, capture: ReceivingCapture): Boolean {
    val rules = capture.line?.rules ?: return false
    return when (step) {
        ReceivingStep.LOCATION -> !rules.locationMandatory
        ReceivingStep.BATCH -> !rules.batchMandatory
        ReceivingStep.SERIAL -> !rules.serialMandatory
        else -> false
    }
}

private fun isAnswered(step: ReceivingStep, capture: ReceivingCapture): Boolean = step in capture.skipped || when (step) {
    ReceivingStep.PURCHASE_ORDER -> !capture.purchId.isNullOrBlank()
    ReceivingStep.PACKING_SLIP -> !capture.vendorPackingSlipId.isNullOrBlank()
    ReceivingStep.ITEM -> capture.line != null && capture.item != null
    ReceivingStep.QUANTITY -> capture.quantity != null
    ReceivingStep.LOCATION -> !capture.locationId.isNullOrBlank()
    ReceivingStep.BATCH -> !capture.batchId.isNullOrBlank()
    ReceivingStep.BATCH_EXPIRY_DATE -> !capture.batchExpiryDate.isNullOrBlank()
    ReceivingStep.SERIAL -> capture.serialsComplete()
    // Never "answered": it is the terminal step, and the flow rests here until the operator
    // commits or backs out.
    ReceivingStep.CONFIRM -> false
}
