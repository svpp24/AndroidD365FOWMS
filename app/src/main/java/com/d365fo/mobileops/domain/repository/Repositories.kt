package com.d365fo.mobileops.domain.repository

import com.d365fo.mobileops.domain.model.GoodsReceipt
import com.d365fo.mobileops.domain.model.GoodsReceiptLine
import com.d365fo.mobileops.domain.model.PurchaseOrder
import com.d365fo.mobileops.domain.model.RawScan
import com.d365fo.mobileops.domain.model.ScanResolution
import com.d365fo.mobileops.domain.model.WorkerContext
import com.d365fo.mobileops.util.NetworkResult
import kotlinx.coroutines.flow.Flow

/**
 * Every read returns a [Flow] backed by Room, so the UI renders from the device immediately
 * and updates when a background pull lands. Nothing in this layer waits on the network to
 * produce its first emission.
 */

interface GoodsReceiptRepository {

    /** Resumes the worker's draft for this order, or starts one. Never returns null. */
    suspend fun openDraft(purchId: String, worker: WorkerContext): GoodsReceipt

    fun observeReceipt(receiptId: String): Flow<GoodsReceipt?>

    /** Writes the captured line to Room. Does not touch the network. */
    suspend fun addLine(receiptId: String, line: GoodsReceiptLine): NetworkResult<Unit>

    suspend fun removeLine(receiptId: String, receiptLineId: String): NetworkResult<Unit>

    suspend fun updatePackingSlipId(receiptId: String, vendorPackingSlipId: String)

    /**
     * Commits the receipt and enqueues the GRN post — in a single Room transaction, so a
     * crash between the two is impossible. Returns as soon as the local write is durable;
     * the D365FO posting happens later, on the sync worker.
     */
    suspend fun submitForPosting(receiptId: String): NetworkResult<Unit>

    suspend fun discardDraft(receiptId: String)

    fun observePendingReceiptCount(): Flow<Int>
}

interface ScanRepository {

    /** Layers 1 and 2. Room-only — a barcode lookup must never depend on connectivity. */
    suspend fun resolve(raw: RawScan): ScanResolution

    /**
     * Layer 4. Returns true when the serial is already registered in D365FO. Offline, this
     * returns a failure rather than a false negative: silently accepting a possibly
     * duplicate serial creates an inventory discrepancy that surfaces weeks later.
     */
    suspend fun isSerialAlreadyRegistered(itemId: String, serialId: String): NetworkResult<Boolean>

    suspend fun isLocationValid(siteId: String, warehouseId: String, locationId: String): Boolean

    /** Serials captured on this device but not yet posted. */
    suspend fun isSerialUsedLocally(itemId: String, serialId: String): Boolean
}


interface WorkerContextRepository {
    fun observe(): Flow<WorkerContext>
    suspend fun current(): WorkerContext
}
