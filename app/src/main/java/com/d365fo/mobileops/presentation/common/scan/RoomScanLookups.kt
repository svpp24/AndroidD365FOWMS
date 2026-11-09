package com.d365fo.mobileops.presentation.common.scan

import com.d365fo.mobileops.data.local.dao.CountingDao
import com.d365fo.mobileops.data.local.dao.ItemDao
import com.d365fo.mobileops.data.local.dao.LocationDao
import com.d365fo.mobileops.data.local.dao.ScannedItem
import com.d365fo.mobileops.data.local.dao.TransferOrderDao
import com.d365fo.mobileops.data.repository.WorkerSessionRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * [ScanLookups] over the device's own Room copy of D365FO's master data - nothing here calls the
 * network, so identification works offline exactly as it does online (brief: works offline).
 *
 * Reads only through DAO queries that already exist - the redesign doesn't touch the data layer.
 * The current warehouse is the worker's selected one, read at the moment of the scan, so a
 * warehouse change takes effect on the very next scan.
 */
class RoomScanLookups @Inject constructor(
    private val itemDao: ItemDao,
    private val locationDao: LocationDao,
    private val countingDao: CountingDao,
    private val transferOrderDao: TransferOrderDao,
    private val workerSession: WorkerSessionRepository,
) : ScanLookups {

    private val warehouseId: String? get() = workerSession.session.value?.selected?.warehouseId

    override suspend fun barcode(code: String): ScannedItem? = itemDao.resolveBarcode(code)

    override suspend fun itemNumber(code: String): ItemRef? =
        itemDao.getItem(code)?.let { ItemRef(it.itemId, it.itemName, it.active) }

    override suspend fun location(code: String): String? {
        val wh = warehouseId ?: return null
        return locationDao.find(wh, code)?.takeIf { it.active }?.locationId
    }

    // A store has a handful of bins - the existing list query, filtered here, rather than a new
    // DAO method for one row.
    override suspend fun bin(code: String): BinRef? {
        val wh = warehouseId ?: return null
        return countingDao.observeBins(wh).first()
            .firstOrNull { it.binId.equals(code, ignoreCase = true) }
            ?.let { BinRef(it.binId, it.locationId?.takeIf { loc -> loc.isNotBlank() }) }
    }

    override suspend fun transferDirection(transferId: String): TransferDirection? {
        val wh = warehouseId ?: return null
        val order = transferOrderDao.get(transferId)?.order ?: return null
        return when {
            order.fromWarehouseId.equals(wh, ignoreCase = true) -> TransferDirection.SHIP
            order.toWarehouseId.equals(wh, ignoreCase = true) -> TransferDirection.RECEIVE
            else -> null
        }
    }
}
