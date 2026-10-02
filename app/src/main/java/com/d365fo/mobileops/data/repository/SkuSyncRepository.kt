package com.d365fo.mobileops.data.repository

import com.d365fo.mobileops.data.local.dao.ItemDao
import com.d365fo.mobileops.data.local.entity.BarcodeEntity
import com.d365fo.mobileops.data.local.entity.ItemEntity
import com.d365fo.mobileops.data.local.entity.TrackingRequirement
import com.d365fo.mobileops.data.remote.api.BlobTransferService
import com.d365fo.mobileops.data.remote.api.DmfGateway
import com.d365fo.mobileops.data.remote.api.MobileOpsServiceApi
import com.d365fo.mobileops.data.remote.dto.DmfExecutionStatus
import com.d365fo.mobileops.data.remote.dto.ExecutionIdRequest
import com.d365fo.mobileops.data.remote.dto.ExportToPackageRequest
import com.d365fo.mobileops.data.remote.dto.SkuExportRequestBody
import com.d365fo.mobileops.data.remote.dto.SkuExportServiceRequest
import com.d365fo.mobileops.data.remote.dto.stringValue
import com.d365fo.mobileops.data.remote.error.D365ErrorMapper
import com.d365fo.mobileops.data.security.CompanyProvider
import com.d365fo.mobileops.util.AppFailure
import com.d365fo.mobileops.util.Constants
import com.d365fo.mobileops.util.DmfCsv
import com.d365fo.mobileops.util.DmfPackage
import com.d365fo.mobileops.util.NetworkResult
import com.d365fo.mobileops.util.getCI
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bulk item + barcode master data, for the offline SKU cache `ItemDao.resolveBarcode` reads —
 * the 1M+ SKU side of the offline-scaling plan. Called only from [SkuSyncWorker][com.d365fo.mobileops.data.sync.worker.SkuSyncWorker],
 * Wi-Fi+charging gated (see `SyncScheduler`).
 *
 * **Same division of labour as [CountingJournalRepository]'s export path.**
 * `HSMobileOpsSkuSvc/exportSkuMaster` only creates the two DMF projects (item master, barcodes)
 * and returns their names; this repository drives the actual export itself via the public
 * `ExportToPackage`/`GetExecutionSummaryStatus`/`GetExportedPackageUrl` OData actions — the same
 * flow `CountingJournalRepository.startExport`/`pollExport` already use, just polled to
 * completion in one suspend call rather than exposed step-by-step, since there is no UI here to
 * show incremental progress to.
 *
 * **CSV mapping mirrors [Mappers.kt]'s live on-demand mapping exactly** (`ReleasedProductDto.toEntity`/
 * `ProductBarcodeDto.toEntity`), field for field, just reading the same fields out of a CSV row
 * instead of a JSON DTO — [getCI] handles the header-casing uncertainty every DMF CSV in this app
 * already carries (see `DmfPackage.kt`'s own doc comment).
 */
@Singleton
class SkuSyncRepository @Inject constructor(
    private val service: MobileOpsServiceApi,
    private val dmf: DmfGateway,
    private val blob: BlobTransferService,
    private val errorMapper: D365ErrorMapper,
    private val companyProvider: CompanyProvider,
    private val itemDao: ItemDao,
    private val ioDispatcher: CoroutineDispatcher,
) {

    private fun company(): String =
        companyProvider()?.takeIf { it.isNotBlank() }?.lowercase().orEmpty()

    /** DMF's legalEntityId, not lowercased like the AOS dataAreaId — see CountingJournalRepository's own note. */
    private fun legalEntity(): String = companyProvider()?.takeIf { it.isNotBlank() }.orEmpty()

    suspend fun downloadAndImport(): NetworkResult<SkuSyncSummary> = withContext(ioDispatcher) {
        val scoped = errorMapper.safeCall {
            service.exportSkuMaster(SkuExportServiceRequest(SkuExportRequestBody(company())))
        }
        val (itemGroup, barcodeGroup) = when (scoped) {
            is NetworkResult.Error -> return@withContext scoped
            NetworkResult.Loading -> return@withContext NetworkResult.Error(AppFailure.Fatal("unexpected Loading"))
            is NetworkResult.Success -> {
                val body = scoped.data
                if (!body.success) {
                    return@withContext NetworkResult.Error(
                        AppFailure.BusinessRule(body.errorCode ?: "could not set up the SKU export"),
                    )
                }
                val items = body.itemDefinitionGroupName?.takeIf { it.isNotBlank() }
                    ?: return@withContext NetworkResult.Error(
                        AppFailure.Fatal("SKU export set up with no item project name"),
                    )
                val barcodes = body.barcodeDefinitionGroupName?.takeIf { it.isNotBlank() }
                    ?: return@withContext NetworkResult.Error(
                        AppFailure.Fatal("SKU export set up with no barcode project name"),
                    )
                items to barcodes
            }
        }

        val itemBytes = when (val r = exportAndDownload(itemGroup)) {
            is NetworkResult.Error -> return@withContext r
            NetworkResult.Loading -> return@withContext NetworkResult.Error(AppFailure.Fatal("unexpected Loading"))
            is NetworkResult.Success -> r.data
        }
        val barcodeBytes = when (val r = exportAndDownload(barcodeGroup)) {
            is NetworkResult.Error -> return@withContext r
            NetworkResult.Loading -> return@withContext NetworkResult.Error(AppFailure.Fatal("unexpected Loading"))
            is NetworkResult.Success -> r.data
        }

        val itemTable = DmfCsv.parse(DmfPackage.unzip(itemBytes).dataFileBytes)
        val barcodeTable = DmfCsv.parse(DmfPackage.unzip(barcodeBytes).dataFileBytes)

        val items = itemTable.rows.mapNotNull { it.toItemEntity() }
        val barcodes = barcodeTable.rows.mapNotNull { it.toBarcodeEntity() }

        itemDao.upsertItemsChunked(items, Constants.SKU_SYNC_BATCH_SIZE)
        itemDao.upsertBarcodesChunked(barcodes, Constants.SKU_SYNC_BATCH_SIZE)

        NetworkResult.Success(SkuSyncSummary(itemCount = items.size, barcodeCount = barcodes.size))
    }

    /**
     * Triggers [definitionGroupId]'s export with a fresh execution id (RIS's own format, same as
     * [CountingJournalRepository.startExport]) and polls it to a terminal state, returning the
     * downloaded package bytes on success. Bounded to [MAX_POLL_ATTEMPTS] * [POLL_INTERVAL_MS] —
     * about ten minutes per project — so a stuck export cannot pin [SkuSyncWorker] past
     * WorkManager's own execution window; a run that times out here simply retries on the next
     * scheduled tick rather than blocking forever.
     */
    private suspend fun exportAndDownload(definitionGroupId: String): NetworkResult<ByteArray> {
        val executionId = "$definitionGroupId-${EXECUTION_STAMP.format(Date())}-${UUID.randomUUID()}"
        val started = errorMapper.safeCall {
            dmf.exportToPackage(
                ExportToPackageRequest(
                    definitionGroupId = definitionGroupId,
                    packageName = executionId,
                    executionId = executionId,
                    reExecute = false,
                    legalEntityId = legalEntity(),
                ),
            )
        }
        when (started) {
            is NetworkResult.Error -> return started
            NetworkResult.Loading -> return NetworkResult.Error(AppFailure.Fatal("unexpected Loading"))
            is NetworkResult.Success -> Unit
        }

        repeat(MAX_POLL_ATTEMPTS) {
            delay(POLL_INTERVAL_MS)

            val status = errorMapper.safeCall { dmf.getExecutionSummaryStatus(ExecutionIdRequest(executionId)) }
            when (status) {
                is NetworkResult.Error -> return status
                NetworkResult.Loading -> return NetworkResult.Error(AppFailure.Fatal("unexpected Loading"))
                is NetworkResult.Success -> when (status.data.stringValue()) {
                    DmfExecutionStatus.SUCCEEDED, DmfExecutionStatus.PARTIALLY_SUCCEEDED -> {
                        val urlResult =
                            errorMapper.safeCall { dmf.getExportedPackageUrl(ExecutionIdRequest(executionId)) }
                        val url = when (urlResult) {
                            is NetworkResult.Error -> return urlResult
                            NetworkResult.Loading ->
                                return NetworkResult.Error(AppFailure.Fatal("unexpected Loading"))
                            is NetworkResult.Success -> urlResult.data.stringValue()?.takeIf { it.isNotBlank() }
                                ?: return NetworkResult.Error(
                                    AppFailure.Fatal("GetExportedPackageUrl returned no value"),
                                )
                        }
                        return try {
                            NetworkResult.Success(blob.download(url))
                        } catch (e: Exception) {
                            NetworkResult.Error(AppFailure.Transient(e.message ?: "package download failed"))
                        }
                    }

                    DmfExecutionStatus.FAILED, DmfExecutionStatus.CANCELED ->
                        return NetworkResult.Error(
                            AppFailure.BusinessRule("SKU export failed for $definitionGroupId"),
                        )

                    // NotRun / Executing / Unknown / anything unrecognised - keep polling.
                    else -> Unit
                }
            }
        }

        return NetworkResult.Error(
            AppFailure.Transient("SKU export for $definitionGroupId did not finish in time"),
        )
    }

    // -- CSV mapping ----------------------------------------------------------------------

    /**
     * Mirrors [Mappers.kt]'s `ReleasedProductDto.toEntity` field for field. `IsBatchNumberActive`/
     * `IsSerialNumberActive` are read straight off the row, same as the on-demand path — no join
     * needed, since (confirmed live for the on-demand path) the entity publishes them directly.
     *
     * Null on a missing `ItemNumber` - a row this malformed cannot be upserted against anything.
     */
    private fun Map<String, String>.toItemEntity(): ItemEntity? {
        val itemNumber = getCI("ItemNumber")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val batchActive = getCI("IsBatchNumberActive").toDmfBoolean()
        val serialActive = getCI("IsSerialNumberActive").toDmfBoolean()
        val storageDimensionGroup = getCI("StorageDimensionGroupName")
        val lifecycleState = getCI("ProductLifecycleStateId")

        return ItemEntity(
            itemId = itemNumber,
            // ReleasedProductsV2 has no ProductName; SearchName is the only name it publishes -
            // same note as Mappers.kt's live mapping.
            itemName = getCI("SearchName").orEmpty(),
            searchName = getCI("SearchName"),
            inventoryUnit = getCI("InventoryUnitSymbol").orEmpty(),
            purchaseUnit = getCI("PurchaseUnitSymbol"),
            salesUnit = getCI("SalesUnitSymbol"),
            trackingRequirement = when {
                batchActive && serialActive -> TrackingRequirement.BATCH_AND_SERIAL
                batchActive -> TrackingRequirement.BATCH
                serialActive -> TrackingRequirement.SERIAL
                else -> TrackingRequirement.NONE
            },
            // Same "defaulting to required is the safe direction" heuristic as the on-demand
            // path (OnDemandLookupRepositoryImpl.lookupItem) - no StorageDimensionGroups entity
            // exists on this tenant, so this is inferred from the group's name.
            locationMandatory = storageDimensionGroup?.contains("Loc", ignoreCase = true) ?: true,
            active = lifecycleState.isNullOrBlank() || lifecycleState.equals("Active", true),
            modifiedAt = System.currentTimeMillis(),
        )
    }

    /** Mirrors [Mappers.kt]'s `ProductBarcodeDto.toEntity` field for field. */
    private fun Map<String, String>.toBarcodeEntity(): BarcodeEntity? {
        val barcode = getCI("Barcode")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val itemNumber = getCI("ItemNumber")?.trim().orEmpty()
        val quantity = getCI("ProductQuantity")?.toBigDecimalOrNull()

        return BarcodeEntity(
            // Scanner output is normalised to upper case, so the stored key must match - same
            // as the live mapping.
            barcode = barcode.uppercase(),
            itemId = itemNumber,
            quantityPerScan = (quantity?.takeIf { it.signum() > 0 } ?: BigDecimal.ONE).toPlainString(),
            unitId = getCI("ProductQuantityUnitSymbol"),
            // ProductBarcodesV3 has no active flag - a retired barcode is deleted rather than
            // deactivated, so anything the export returns is by definition in use.
            active = true,
            modifiedAt = System.currentTimeMillis(),
        )
    }

    private companion object {
        /** ~5 minutes per project before giving up and letting the next scheduled run retry. */
        const val POLL_INTERVAL_MS = 5_000L
        const val MAX_POLL_ATTEMPTS = 60
        val EXECUTION_STAMP: SimpleDateFormat get() = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
    }
}

data class SkuSyncSummary(val itemCount: Int, val barcodeCount: Int)

/**
 * D365's own NoYes export convention is unconfirmed for these two entities (the live on-demand
 * path decodes it via [com.d365fo.mobileops.data.remote.dto.NoYesSerializer] against JSON, not
 * CSV) - this accepts every spelling that convention plausibly uses (`Yes`/`1`/`true`) rather
 * than matching one exact string and silently under-counting tracked items.
 */
private fun String?.toDmfBoolean(): Boolean {
    val v = this?.trim()?.lowercase() ?: return false
    return v == "yes" || v == "1" || v == "true"
}
