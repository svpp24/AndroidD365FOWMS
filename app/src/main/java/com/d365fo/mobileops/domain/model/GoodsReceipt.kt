package com.d365fo.mobileops.domain.model

import java.math.BigDecimal

/** A receiving session against one purchase order, before or after it is queued for posting. */
data class GoodsReceipt(
    val receiptId: String,
    val purchId: String,
    val workerId: String,
    val deviceId: String,
    val vendorPackingSlipId: String,
    val receiptDate: Long,
    val status: ReceiptStatus,
    val serverPackingSlipId: String?,
    val lines: List<GoodsReceiptLine>,
    val lastError: String?,
) {
    val isEmpty: Boolean get() = lines.isEmpty()
    val isEditable: Boolean get() = status == ReceiptStatus.DRAFT
    val lineCount: Int get() = lines.size

    fun totalFor(purchLineId: String): BigDecimal =
        lines.filter { it.purchLineId == purchLineId }
            .fold(BigDecimal.ZERO) { acc, line -> acc + line.inventoryQuantity }
}

data class GoodsReceiptLine(
    val receiptLineId: String,
    val purchLineId: String,
    val lineNumber: Double,
    val itemId: String,
    val itemName: String,
    /** As keyed by the worker, in [enteredUnit] — kept for display and for audit. */
    val enteredQuantity: BigDecimal,
    val enteredUnit: String,
    /** Converted to the inventory unit. This is what posts to D365FO. */
    val inventoryQuantity: BigDecimal,
    val inventoryUnit: String,
    val siteId: String?,
    val warehouseId: String?,
    val locationId: String?,
    val batchId: String?,
    val serialId: String?,
    val toleranceOverridden: Boolean,
    val capturedAt: Long,
)

enum class ReceiptStatus {
    DRAFT,
    PENDING_POST,
    POSTED,
    FAILED;

    val isTerminal: Boolean get() = this == POSTED
}

/** Identity and defaults for the signed-in worker. Every posting is attributed to one. */
data class WorkerContext(
    val workerId: String,
    val personnelNumber: String?,
    val displayName: String,
    val deviceId: String,
    val dataAreaId: String,
    val siteId: String,
    val warehouseId: String,
) {
    companion object {
        /** Used only before sign-in completes; no posting may be built from it. */
        val UNAUTHENTICATED = WorkerContext(
            workerId = "",
            personnelNumber = null,
            displayName = "",
            deviceId = "",
            dataAreaId = "",
            siteId = "",
            warehouseId = "",
        )
    }
}
