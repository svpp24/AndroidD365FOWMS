package com.d365fo.mobileops.data.repository

import com.d365fo.mobileops.data.local.dao.GoodsReceiptDao
import com.d365fo.mobileops.data.local.dao.ItemDao
import com.d365fo.mobileops.data.local.dao.LocationDao
import com.d365fo.mobileops.data.local.dao.ValidationAuditDao
import com.d365fo.mobileops.data.local.entity.ValidationAuditEntity
import com.d365fo.mobileops.data.remote.api.D365ODataApi
import com.d365fo.mobileops.data.remote.api.ODataFilter
import com.d365fo.mobileops.data.remote.error.D365ErrorMapper
import com.d365fo.mobileops.domain.model.RawScan
import com.d365fo.mobileops.domain.model.ScanResolution
import com.d365fo.mobileops.domain.repository.ScanRepository
import com.d365fo.mobileops.util.NetworkResult
import com.d365fo.mobileops.util.map
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScanRepositoryImpl @Inject constructor(
    private val itemDao: ItemDao,
    private val locationDao: LocationDao,
    private val receiptDao: GoodsReceiptDao,
    private val auditDao: ValidationAuditDao,
    private val api: D365ODataApi,
    private val errorMapper: D365ErrorMapper,
    private val ioDispatcher: CoroutineDispatcher,
) : ScanRepository {

    /**
     * Layers 1 and 2 in one indexed query. Never touches the network — a barcode that only
     * resolves when the access point is in range is not a working scanner.
     */
    override suspend fun resolve(raw: RawScan): ScanResolution = withContext(ioDispatcher) {
        val normalized = raw.normalized()

        if (normalized.isBlank() || normalized.length > MAX_BARCODE_LENGTH) {
            return@withContext ScanResolution.UnknownBarcode(raw)
        }

        val found = itemDao.resolveBarcode(normalized)
            ?: return@withContext ScanResolution.UnknownBarcode(raw)

        if (!found.itemActive || !found.barcodeActive) {
            return@withContext ScanResolution.ItemInactive(found.itemId, raw)
        }

        ScanResolution.Resolved(
            itemId = found.itemId,
            itemName = found.itemName,
            inventoryUnit = found.inventoryUnit,
            quantityPerScan = found.quantityPerScan.toBigDecimalOrNull() ?: BigDecimal.ONE,
            // An inner/outer-pack barcode carries its own unit; a plain item barcode does not.
            scanUnit = found.scanUnit ?: found.purchaseUnit ?: found.inventoryUnit,
            tracking = found.trackingRequirement.toDomain(),
            locationMandatory = found.locationMandatory,
            raw = raw,
        )
    }

    override suspend fun isLocationValid(
        siteId: String,
        warehouseId: String,
        locationId: String,
    ): Boolean = withContext(ioDispatcher) {
        // siteId is accepted for interface symmetry but not matched on; see LocationDao.find.
        locationDao.find(warehouseId, locationId.uppercase())?.active == true
    }

    override suspend fun isSerialUsedLocally(itemId: String, serialId: String): Boolean =
        withContext(ioDispatcher) {
            receiptDao.countLocalSerialUses(itemId, serialId) > 0
        }

    override suspend fun isSerialAlreadyRegistered(
        itemId: String,
        serialId: String,
    ): NetworkResult<Boolean> = withContext(ioDispatcher) {
        errorMapper.safeCall {
            api.findSerialNumber(
                filter = ODataFilter.and(
                    ODataFilter.eq("ItemNumber", itemId),
                    ODataFilter.eq("SerialNumber", serialId),
                ),
            )
        }.map { it.value.isNotEmpty() }
    }

    /**
     * Records a rejected scan. Kept deliberately verbatim — when a receiving dispute goes to
     * the vendor, the question is what the label actually said, not what the app made of it.
     */
    suspend fun audit(
        module: String,
        field: String,
        rawValue: String,
        reason: String,
        workerId: String,
        deviceId: String,
        overridden: Boolean = false,
    ) = withContext(ioDispatcher) {
        auditDao.log(
            ValidationAuditEntity(
                occurredAt = System.currentTimeMillis(),
                workerId = workerId,
                deviceId = deviceId,
                module = module,
                field = field,
                rawValue = rawValue,
                reason = reason,
                overriddenBySupervisor = overridden,
            ),
        )
    }

    private companion object {
        /** Longest symbology in practical warehouse use is GS1-128 at 48 characters. */
        const val MAX_BARCODE_LENGTH = 64
    }
}
