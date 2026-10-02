package com.d365fo.mobileops.data.repository

import android.content.Context
import com.d365fo.mobileops.data.counting.SubmissionAudit
import com.d365fo.mobileops.data.local.dao.CountingDao
import com.d365fo.mobileops.data.local.dao.JournalDao
import com.d365fo.mobileops.data.local.entity.CountingLineReferenceEntity
import com.d365fo.mobileops.data.local.entity.TransferMode
import com.d365fo.mobileops.domain.model.CountClaimInfo
import com.d365fo.mobileops.domain.model.COUNT_MODE_SINGLE
import com.d365fo.mobileops.domain.model.CountRange
import com.d365fo.mobileops.domain.model.FREEZE_NONE
import com.d365fo.mobileops.domain.model.ORIGIN_SCHEDULED
import com.d365fo.mobileops.domain.model.byPriority
import com.d365fo.mobileops.domain.model.keepsServerCounts
import com.d365fo.mobileops.data.local.dao.MergeResult
import com.d365fo.mobileops.data.local.entity.JournalEntity
import com.d365fo.mobileops.data.local.entity.JournalLineEntity
import com.d365fo.mobileops.data.local.entity.JournalStatus
import com.d365fo.mobileops.data.local.entity.JournalType
import com.d365fo.mobileops.data.local.entity.SyncState
import com.d365fo.mobileops.data.remote.api.BlobTransferService
import com.d365fo.mobileops.data.remote.api.D365ODataApi
import com.d365fo.mobileops.data.remote.api.ODataFilter
import com.d365fo.mobileops.data.remote.api.MobileOpsServiceApi
import com.d365fo.mobileops.data.remote.dto.AzureWritableBlob
import com.d365fo.mobileops.data.remote.dto.DmfExecutionStatus
import com.d365fo.mobileops.data.remote.dto.ExecutionIdRequest
import com.d365fo.mobileops.data.remote.dto.ExportToPackageRequest
import com.d365fo.mobileops.data.remote.dto.GetAzureWriteUrlRequest
import com.d365fo.mobileops.data.remote.dto.ImportCountsRequestBody
import com.d365fo.mobileops.data.remote.dto.ImportCountsServiceRequest
import com.d365fo.mobileops.data.remote.dto.JournalListRequestBody
import com.d365fo.mobileops.data.remote.dto.JournalListServiceRequest
import com.d365fo.mobileops.data.remote.dto.ScopeExportRequestBody
import com.d365fo.mobileops.data.remote.dto.ScopeExportServiceRequest
import com.d365fo.mobileops.data.remote.dto.stringListValue
import com.d365fo.mobileops.data.remote.dto.stringValue
import com.d365fo.mobileops.data.remote.error.D365ErrorMapper
import com.d365fo.mobileops.data.security.CompanyProvider
import com.d365fo.mobileops.data.security.SecureStore
import com.d365fo.mobileops.domain.model.CountingJournalSummary
import com.d365fo.mobileops.domain.model.ExportJobStatus
import com.d365fo.mobileops.domain.model.ImportJobStatus
import com.d365fo.mobileops.util.AppFailure
import com.d365fo.mobileops.util.DmfCsv
import com.d365fo.mobileops.util.DmfCsvTable
import com.d365fo.mobileops.util.DmfPackage
import com.d365fo.mobileops.util.DmfPackageFiles
import com.d365fo.mobileops.util.Formatters
import com.d365fo.mobileops.util.getCI
import com.d365fo.mobileops.util.headerName
import com.d365fo.mobileops.util.NetworkResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Counting journals: created in D365FO, scoped to the worker's assigned warehouse.
 *
 * [listAssignedJournals] is a cheap, direct `HSMobileOpsCountingSvc` read of journal headers.
 *
 * Export starts from the app: [startExport] has AOS build a request-private project scoped to one
 * journal, then calls the public `ExportToPackage` OData action itself with an execution id it
 * generates (how Microsoft's Recurring Integrations Scheduler drives exports - see
 * `HSMobileOpsCountingService::exportOpenJournals`'s own doc comment). [pollExport] polls it the
 * same way [pollImport] polls an import, and additionally fetches the download URL via
 * `GetExportedPackageUrl` once it succeeds.
 * * [submitCounts] calls `HSMobileOpsCountingSvc/importCounts`, which enqueues the import as a
 * D365 batch job (`DMFPackageImporter`) against the one shared `HSMobileOpsCountingImport`
 * project, rather than the public `ImportFromPackage` OData action. The app polls the returned
 * execution id: the public `GetExecutionSummaryStatus`/`GetExecutionErrors` OData actions.
 *
 * The counted-quantity-back direction never constructs DMF package XML: [downloadAndImportLines]
 * caches the real `Manifest.xml`/`PackageHeader.xml` a journal's own export produced, and
 * [submitCounts] re-zips those same two files with only the CSV's `CountedQuantity` column
 * changed, uploaded and imported against a second, import-direction definition group on the same
 * entity.
 */
@Singleton
class CountingJournalRepository @Inject constructor(
    private val service: MobileOpsServiceApi,
    private val odata: D365ODataApi,
    private val blob: BlobTransferService,
    private val errorMapper: D365ErrorMapper,
    private val companyProvider: CompanyProvider,
    private val secureStore: SecureStore,
    private val journalDao: JournalDao,
    private val countingDao: CountingDao,
    private val json: Json,
    private val ioDispatcher: CoroutineDispatcher,
    @ApplicationContext private val context: Context,
) {

    private fun company(): String =
        companyProvider()?.takeIf { it.isNotBlank() }?.lowercase().orEmpty()

    /** DMF's legalEntityId, not lowercased like the AOS dataAreaId - matches what a working ExportToPackage call used (BSP). */
    private fun legalEntity(): String = companyProvider()?.takeIf { it.isNotBlank() }.orEmpty()

    suspend fun listAssignedJournals(warehouseId: String, userId: String? = null): NetworkResult<List<CountingJournalSummary>> =
        withContext(ioDispatcher) {
            val trimmedWarehouse = warehouseId.trim()
            if (trimmedWarehouse.isEmpty()) {
                return@withContext NetworkResult.Success(emptyList())
            }

            val result = errorMapper.safeCall {
                service.listOpenJournals(
                    JournalListServiceRequest(
                        JournalListRequestBody(company(), trimmedWarehouse, userId),
                    ),
                )
            }

            when (result) {
                is NetworkResult.Error -> result
                NetworkResult.Loading -> NetworkResult.Error(AppFailure.Fatal("unexpected Loading"))
                is NetworkResult.Success -> NetworkResult.Success(
                    result.data.rows.map { row ->
                        CountingJournalSummary(
                            journalId = row.journalId,
                            journalNameId = row.journalNameId,
                            description = row.description,
                            journalDate = Formatters.serviceDateTimeToEpochMillis(row.journalDate),
                            lineCount = row.lineCount,
                            transferMode = TransferMode.parse(row.transferMode),
                            claims = row.claims.map { claim ->
                                CountClaimInfo(
                                    claimedBy = claim.claimedBy.orEmpty(),
                                    deviceId = claim.deviceId,
                                    rangeId = claim.rangeId,
                                    locationFrom = claim.locationFrom,
                                    locationTo = claim.locationTo,
                                    expiresAt = Formatters.serviceDateTimeToEpochMillis(claim.expiresUtc),
                                )
                            },
                            ranges = row.ranges.map { range ->
                                CountRange(
                                    rangeId = range.rangeId,
                                    label = range.label?.takeIf { it.isNotBlank() }
                                        ?: listOfNotNull(range.locationFrom, range.locationTo).joinToString(" - "),
                                    locationFrom = range.locationFrom,
                                    locationTo = range.locationTo,
                                    lineCount = range.lineCount,
                                )
                            },
                            locationFirst = row.locationFirst,
                            zeroStockCheck = row.zeroStockCheck,
                            countMode = row.countMode?.takeIf { it.isNotBlank() } ?: COUNT_MODE_SINGLE,
                            passNumber = row.passNumber.coerceAtLeast(1),
                            recountOf = row.recountOf?.takeIf { it.isNotBlank() },
                            originType = row.originType?.takeIf { it.isNotBlank() } ?: ORIGIN_SCHEDULED,
                            priority = row.priority,
                            toleranceInfo = row.toleranceInfo?.takeIf { it.isNotBlank() },
                            abcClass = row.abcClass?.takeIf { it.isNotBlank() },
                            freezeState = row.freezeState?.takeIf { it.isNotBlank() } ?: FREEZE_NONE,
                            snapshotAt = Formatters.serviceDateTimeToEpochMillis(row.snapshotAtUtc),
                        )
                    }.byPriority(),
                )
            }
        }

    // -- Export: journal lines onto the device -------------------------------------------

    /**
     * Starts an export of [journalId]'s lines and returns the execution id to poll with
     * [pollExport]. Two steps, split the way Microsoft's own Recurring Integrations Scheduler
     * splits an export:
     *
     * 1. `HSMobileOpsCountingSvc/exportOpenJournals` creates a brand-new, request-private DMF
     *    project scoped to just this journal and returns its name - the one thing the public API
     *    can't do (see HSMobileOpsCountingService::exportOpenJournals' own doc comment).
     * 2. This app generates its own execution id (`{project}-{timestamp}-{guid}`, RIS's format)
     *    and calls the public `ExportToPackage` OData action against that project with it, so
     *    there is no batch job to resolve into an execution id afterwards.
     */
    suspend fun startExport(
        journalId: String,
        range: CountRange? = null,
        claimToken: String? = null,
    ): NetworkResult<String> = withContext(ioDispatcher) {
        val scoped = errorMapper.safeCall {
            service.exportOpenJournals(
                ScopeExportServiceRequest(
                    ScopeExportRequestBody(
                        dataAreaId = company(),
                        journalId = journalId,
                        locationFrom = range?.locationFrom,
                        locationTo = range?.locationTo,
                        claimToken = claimToken,
                    ),
                ),
            )
        }
        val projectName = when (scoped) {
            is NetworkResult.Error -> return@withContext scoped
            NetworkResult.Loading -> return@withContext NetworkResult.Error(AppFailure.Fatal("unexpected Loading"))
            is NetworkResult.Success -> {
                val body = scoped.data
                if (!body.success) {
                    return@withContext NetworkResult.Error(
                        AppFailure.BusinessRule(body.errorCode ?: "could not set up the export"),
                    )
                }
                body.definitionGroupName?.takeIf { it.isNotBlank() }
                    ?: return@withContext NetworkResult.Error(
                        AppFailure.Fatal("export set up with no project name"),
                    )
            }
        }

        val executionId = "$projectName-${EXECUTION_STAMP.format(Date())}-${UUID.randomUUID()}"
        val started = errorMapper.safeCall {
            odata.exportToPackage(
                ExportToPackageRequest(
                    definitionGroupId = projectName,
                    packageName = executionId,
                    executionId = executionId,
                    reExecute = false,
                    legalEntityId = legalEntity(),
                ),
            )
        }
        when (started) {
            is NetworkResult.Error -> started
            NetworkResult.Loading -> NetworkResult.Error(AppFailure.Fatal("unexpected Loading"))
            is NetworkResult.Success -> NetworkResult.Success(executionId)
        }
    }
    /**
     * Polls [executionId] via the same `GetExecutionSummaryStatus`/`GetExecutionErrors` actions
     * [pollImport] uses, and additionally fetches the download URL via `GetExportedPackageUrl`
     * once the run succeeds - export has a downloadable result, unlike import.
     */
    suspend fun pollExport(executionId: String): NetworkResult<ExportJobStatus> =
        withContext(ioDispatcher) {
            when (val status = checkExecutionStatus(executionId)) {
                is NetworkResult.Error -> status
                NetworkResult.Loading -> NetworkResult.Error(AppFailure.Fatal("unexpected Loading"))
                is NetworkResult.Success -> when (val s = status.data) {
                    is DmfStatus.Pending -> NetworkResult.Success(ExportJobStatus.Enqueued)
                    is DmfStatus.Running -> NetworkResult.Success(ExportJobStatus.Running(s.percent))
                    DmfStatus.Succeeded, is DmfStatus.PartiallySucceeded -> fetchExportedPackageUrl(executionId)
                    is DmfStatus.Failed -> NetworkResult.Success(ExportJobStatus.Failed(s.reason))
                }
            }
        }

    private suspend fun fetchExportedPackageUrl(executionId: String): NetworkResult<ExportJobStatus> {
        val result = errorMapper.safeCall { odata.getExportedPackageUrl(ExecutionIdRequest(executionId)) }
        return when (result) {
            is NetworkResult.Error -> result
            NetworkResult.Loading -> NetworkResult.Error(AppFailure.Fatal("unexpected Loading"))
            is NetworkResult.Success -> result.data.stringValue()?.takeIf { it.isNotBlank() }
                ?.let { NetworkResult.Success(ExportJobStatus.Ready(it)) }
                ?: NetworkResult.Error(AppFailure.Fatal("GetExportedPackageUrl returned no value"))
        }
    }

    /**
     * Downloads [downloadUrl], unzips it, maps the CSV rows into Room (the same schema every
     * other journal type already uses), and caches the raw manifest/header/CSV bytes for
     * [submitCounts] to reuse later.
     *
     * Safe to run again on a journal already on the device ("Get data again", for lines added in
     * D365FO after the first export): the journal row is left alone and the lines are merged by
     * [JournalDao.mergeCountingLines], which keeps every count and marker already entered. The
     * cached package is replaced with the fresh one either way, so [submitCounts] sends the new
     * lines too.
     */
    suspend fun downloadAndImportLines(
        journal: CountingJournalSummary,
        downloadUrl: String,
        warehouseId: String,
        range: CountRange? = null,
    ): NetworkResult<ImportSummary> = withContext(ioDispatcher) {
        val packageBytes = try {
            blob.download(downloadUrl)
        } catch (e: Exception) {
            return@withContext NetworkResult.Error(
                AppFailure.Transient(e.message ?: "package download failed"),
            )
        }

        val files = try {
            DmfPackage.unzip(packageBytes)
        } catch (e: Exception) {
            return@withContext NetworkResult.Error(
                AppFailure.Fatal(e.message ?: "package unzip failed", e),
            )
        }

        val fullTable = DmfCsv.parse(files.dataFileBytes)
        if (fullTable.rows.isEmpty()) {
            return@withContext NetworkResult.Error(
                AppFailure.Fatal("exported package has no lines - unexpected header shape?"),
            )
        }

        // Export is scoped to this journal's own definition group now, so the package should
        // already hold only this journal's lines - filtered again here anyway as a cheap
        // defensive check against a stale/misscoped package, so a later submitCounts() re-import
        // can never touch another journal's rows even if the export-side scoping regresses.
        // The claimed range is filtered again here too - the server scopes the export, this only
        // makes sure a regression there can never put another worker's aisle on this device.
        val table = fullTable.copy(
            rows = fullTable.rows.filter {
                it.getCI("JournalNumber") == journal.journalId &&
                    (range == null || range.contains(it.getCI("WarehouseLocationId")))
            },
        )
        if (table.rows.isEmpty()) {
            return@withContext NetworkResult.Error(
                AppFailure.Fatal(
                    "${journal.journalId} was not in the exported package - it may have been " +
                        "closed since the export started",
                ),
            )
        }

        cachePackageFiles(journal.journalId, files.copy(dataFileBytes = DmfCsv.write(table)))

        val workerId = secureStore.getString(SecureStore.KEY_WORKER_ID) ?: "unknown"
        val alreadyOnDevice = journalDao.getJournal(journal.journalId) != null
        if (!alreadyOnDevice) journalDao.upsertJournal(
            JournalEntity(
                journalId = journal.journalId,
                type = JournalType.COUNTING,
                journalNameId = journal.journalNameId,
                serverJournalNumber = journal.journalId,
                description = journal.description,
                journalDate = journal.journalDate ?: System.currentTimeMillis(),
                siteId = null,
                warehouseId = warehouseId,
                workerId = workerId,
                deviceId = secureStore.deviceId(),
                status = JournalStatus.DRAFT,
                transferMode = TransferMode.DMF,
            ),
        )

        val onHandByLineNumber = HashMap<Int, String?>()
        val lines = table.rows.mapIndexed { index, row ->
            val lineNumber = row.getCI("LineNumber").toLineNumber() ?: (index + 1)
            onHandByLineNumber[lineNumber] = row.getCI("OnHandQuantity").toPlainQuantity()
            // What D365FO already holds as this line's count - so a journal that was counted and
            // submitted earlier (or on another device) comes back with its counts instead of
            // looking untouched. A non-zero COUNTEDQUANTITY is a count. Zero is ambiguous (counted
            // zero vs not counted) and only trusted when the export's zero flag says Yes.
            // Never on a recount pass or a double-blind journal: that count is someone else's.
            val serverCount = if (!keepsServerCounts(journal.passNumber, journal.countMode)) {
                null
            } else {
                row.getCI("CountedQuantity").toPlainQuantity()
                    ?.takeIf { it != "0" }
                    ?: "0".takeIf { row.getCI("IsCountedQuantityZero").equals("Yes", ignoreCase = true) }
            }
            JournalLineEntity(
                journalLineId = "${journal.journalId}-$lineNumber",
                journalId = journal.journalId,
                lineNumber = lineNumber,
                itemId = row.getCI("ItemNumber").orEmpty(),
                itemName = row.getCI("ItemNumber").orEmpty(),
                // Blind counting: on-hand never goes on the line - see CountingLineReferenceEntity.
                quantity = "",
                // InventInventoryCountingJournalLineEntity has no plain unit-symbol column -
                // only CatchWeightUnitSymbol, which is for catch-weight items specifically.
                unitId = "",
                countedQuantity = serverCount,
                expectedQuantity = null,
                siteId = row.getCI("InventorySiteId"),
                warehouseId = row.getCI("WarehouseId"),
                locationId = row.getCI("WarehouseLocationId"),
                batchId = row.getCI("ItemBatchNumber")?.takeIf { it.isNotBlank() },
                serialId = row.getCI("ItemSerialNumber")?.takeIf { it.isNotBlank() },
                inventDimId = null,
                capturedAt = System.currentTimeMillis(),
            )
        }
        val merge = if (alreadyOnDevice) {
            journalDao.mergeCountingLines(journal.journalId, lines)
        } else {
            journalDao.upsertLinesChunked(lines)
            MergeResult(added = lines.size, removed = 0)
        }
        journalDao.updateCountingAttributes(
            journal.journalId, TransferMode.DMF, journal.locationFirst, journal.zeroStockCheck,
            journal.countMode, journal.passNumber, journal.recountOf,
            journal.originType, journal.priority, journal.toleranceInfo, journal.abcClass,
            journal.freezeState, journal.snapshotAt,
        )
        // After the lines, so each reference row's journal exists (foreign key), and keyed by
        // the line id the merge actually kept.
        countingDao.upsertReferences(
            journalDao.getLinesOnce(journal.journalId).mapNotNull { line ->
                if (line.lineNumber !in onHandByLineNumber) return@mapNotNull null
                CountingLineReferenceEntity(line.journalLineId, journal.journalId, onHandByLineNumber[line.lineNumber])
            },
        )

        NetworkResult.Success(
            ImportSummary(
                total = lines.size,
                added = merge.added,
                removed = merge.removed,
                refreshed = alreadyOnDevice,
            ),
        )
    }

    // -- Import: counted quantities back to D365FO ---------------------------------------

    /**
     * Rewrites the cached export CSV's `CountedQuantity` column from Room's current counted
     * values - and, for a line marked counted without a quantity, from its on-hand quantity - then
     * re-zips it with the untouched cached manifest/header, uploads it, and enqueues an
     * import against the shared [IMPORT_DEFINITION_GROUP] via `HSMobileOpsCountingSvc/importCounts`
     * (`DMFPackageImporter`, not the public `ImportFromPackage` action - see this class's own doc
     * comment). Fails with a clear message if [journalId]'s lines were never downloaded via
     * [downloadAndImportLines] in the first place - there is nothing cached to rebuild a package
     * from.
     */
    suspend fun submitCounts(
        journalId: String,
        audit: SubmissionAudit,
        claimToken: String?,
    ): NetworkResult<String> = withContext(ioDispatcher) {
        val cached = readCachedPackageFiles(journalId)
            ?: return@withContext NetworkResult.Error(
                AppFailure.Fatal("No cached package for $journalId - fetch its lines again first"),
            )

        val table = DmfCsv.parse(cached.dataFileBytes)
        val journal = journalDao.getJournal(journalId)
            ?: return@withContext NetworkResult.Error(AppFailure.Fatal("$journalId not found locally"))
        val countedByLine = journal.lines.associateBy { it.lineNumber }
        val onHandByLineId = countingDao.getReferences(journalId).associate { it.journalLineId to it.onHandQuantity }

        // The header's own exact casing for the counted-quantity column - not assumed to be
        // "CountedQuantity" literally, since the real staging field name is upper case
        // (confirmed from this project's own field-mapping export: COUNTEDQUANTITY) and
        // DmfCsv.write looks values up by the header's exact spelling, not case-insensitively.
        val countedQuantityColumn = table.header.headerName("CountedQuantity")
            ?: return@withContext NetworkResult.Error(
                AppFailure.Fatal("cached package has no CountedQuantity column"),
            )

        // D365FO only treats a counted quantity of 0 as "counted" when this flag is Yes - without
        // it a zero reads as "not counted yet". Null if the export somehow lacks the column.
        val countedZeroColumn = table.header.headerName("IsCountedQuantityZero")

        // Only the counted lines are sent. Confirmed live: importing the WHOLE file - every
        // uncounted line re-sent with the export's COUNTEDQUANTITY of 0 - made D365FO recompute
        // every line's adjustment as 0 minus on-hand, so a line nobody had touched suddenly
        // showed a write-off of its entire stock (the adjustment was 0 before the first import).
        // Leaving untouched lines out of the package leaves them untouched, and shrinks a
        // ~3 minute import of 231 rows to just the rows that changed.
        val updatedRows = table.rows.mapNotNull { row ->
            val lineNumber = row.getCI("LineNumber").toLineNumber()
            val line = lineNumber?.let { countedByLine[it] }
            val counted = when {
                line == null -> null
                // Verify-only (e.g. in transit): confirmed, never counted - the confirmation goes
                // with the exceptions, and the line's quantity is left untouched.
                line.verifyOnly -> null
                line.countedQuantity != null -> line.countedQuantity
                // Marked counted without a quantity: send the on-hand quantity as the count, so
                // the line is counted with no adjustment (variance 0) instead of being left
                // uncounted - or, worse, counted as zero and written off. On-hand comes from the
                // line as imported, falling back to the export's own column.
                line.markedCounted ->
                    onHandByLineId[line.journalLineId] ?: row.getCI("OnHandQuantity").toPlainQuantity()
                else -> null
            }

            if (counted == null) {
                null
            } else {
                val isZero = counted.toBigDecimalOrNull()?.signum() == 0
                val withCount = row + (countedQuantityColumn to counted)
                if (countedZeroColumn != null) {
                    withCount + (countedZeroColumn to if (isZero) "Yes" else "No")
                } else {
                    withCount
                }
            }
        }
        if (updatedRows.isEmpty()) {
            return@withContext NetworkResult.Error(
                AppFailure.BusinessRule(
                    "Nothing has been counted yet - count or mark at least one line first.",
                    d365ErrorCode = "NOTHING_COUNTED",
                ),
            )
        }
        val updatedCsv = DmfCsv.write(DmfCsvTable(table.header, updatedRows))
        val packageBytes = DmfPackage.rezip(cached.copy(dataFileBytes = updatedCsv))

        val uniqueName = "$journalId-${UUID.randomUUID()}.zip"
        val writeUrlResult = errorMapper.safeCall {
            odata.getAzureWriteUrl(GetAzureWriteUrlRequest(uniqueName))
        }
        val blobUrl = when (writeUrlResult) {
            is NetworkResult.Error -> return@withContext writeUrlResult
            NetworkResult.Loading -> return@withContext NetworkResult.Error(AppFailure.Fatal("unexpected Loading"))
            is NetworkResult.Success -> {
                val embedded = writeUrlResult.data.stringValue()
                    ?: return@withContext NetworkResult.Error(
                        AppFailure.Fatal("GetAzureWriteUrl returned no value"),
                    )
                val parsed = runCatching { json.decodeFromString<AzureWritableBlob>(embedded) }
                    .getOrNull()
                parsed?.blobUrl?.takeIf { it.isNotBlank() }
                    ?: return@withContext NetworkResult.Error(
                        AppFailure.Fatal("could not parse GetAzureWriteUrl's embedded BlobUrl"),
                    )
            }
        }

        try {
            blob.upload(blobUrl, packageBytes)
        } catch (e: Exception) {
            return@withContext NetworkResult.Error(
                AppFailure.Transient(e.message ?: "package upload failed"),
            )
        }

        val importResult = errorMapper.safeCall {
            service.importCounts(
                ImportCountsServiceRequest(
                    ImportCountsRequestBody(
                        dataAreaId = company(),
                        journalId = journalId,
                        // The full URL, SAS token and all - the same one GetAzureWriteUrl handed
                        // back and the same one the upload just PUT the package to. Untested
                        // whether DMFPackageImporter's own storage-account context can reach the
                        // blob without it, versus expecting a local SharedServiceUnit file id
                        // instead - see aos/README.md's counting section for this open question.
                        packageUrl = blobUrl,
                        claimToken = claimToken,
                        submissionId = audit.submissionId,
                        userId = audit.userId,
                        deviceId = audit.deviceId,
                        startedAtUtc = audit.startedAt?.let { Formatters.isoUtc(it) },
                        submittedAtUtc = Formatters.isoUtc(audit.submittedAt),
                        passNumber = audit.passNumber,
                        originType = audit.originType,
                    ),
                ),
            )
        }
        when (importResult) {
            is NetworkResult.Error -> importResult
            NetworkResult.Loading -> NetworkResult.Error(AppFailure.Fatal("unexpected Loading"))
            is NetworkResult.Success -> {
                val body = importResult.data
                if (!body.success) {
                    NetworkResult.Error(
                        AppFailure.BusinessRule(body.errorCode ?: "import failed", d365ErrorCode = body.errorCode),
                    )
                } else {
                    body.executionId?.takeIf { it.isNotBlank() }
                        ?.let { NetworkResult.Success(it) }
                        ?: NetworkResult.Error(AppFailure.Fatal("importCounts returned no execution id"))
                }
            }
        }
    }

    suspend fun pollImport(executionId: String): NetworkResult<ImportJobStatus> =
        withContext(ioDispatcher) {
            when (val status = checkExecutionStatus(executionId)) {
                is NetworkResult.Error -> status
                NetworkResult.Loading -> NetworkResult.Error(AppFailure.Fatal("unexpected Loading"))
                is NetworkResult.Success -> when (val s = status.data) {
                    is DmfStatus.Pending -> NetworkResult.Success(ImportJobStatus.Enqueued)
                    is DmfStatus.Running -> NetworkResult.Success(ImportJobStatus.Running(s.percent))
                    DmfStatus.Succeeded -> NetworkResult.Success(ImportJobStatus.Succeeded)
                    is DmfStatus.PartiallySucceeded ->
                        NetworkResult.Success(ImportJobStatus.PartiallySucceeded(s.warning))
                    is DmfStatus.Failed -> NetworkResult.Success(ImportJobStatus.Failed(s.reason))
                }
            }
        }

    // -- Shared status polling -------------------------------------------------------------

    private sealed interface DmfStatus {
        data object Pending : DmfStatus
        data class Running(val percent: Int?) : DmfStatus
        data object Succeeded : DmfStatus

        /** Ran, but D365FO refused some rows - [warning] is [summarizeExecutionErrors]'s account of which. */
        data class PartiallySucceeded(val warning: String) : DmfStatus
        data class Failed(val reason: String) : DmfStatus
    }

    private suspend fun checkExecutionStatus(executionId: String): NetworkResult<DmfStatus> {
        val result = errorMapper.safeCall {
            odata.getExecutionSummaryStatus(ExecutionIdRequest(executionId))
        }
        return when (result) {
            is NetworkResult.Error -> result
            NetworkResult.Loading -> NetworkResult.Error(AppFailure.Fatal("unexpected Loading"))
            is NetworkResult.Success -> when (result.data.stringValue()) {
                DmfExecutionStatus.SUCCEEDED -> NetworkResult.Success(DmfStatus.Succeeded)

                DmfExecutionStatus.PARTIALLY_SUCCEEDED -> NetworkResult.Success(
                    DmfStatus.PartiallySucceeded(
                        fetchExecutionErrorSummary(executionId) ?: "Some lines were not accepted by D365FO",
                    ),
                )

                DmfExecutionStatus.FAILED, DmfExecutionStatus.CANCELED -> {
                    val reason = fetchExecutionErrorSummary(executionId) ?: "The run failed in D365FO"
                    NetworkResult.Success(DmfStatus.Failed(reason))
                }

                DmfExecutionStatus.NOT_RUN -> NetworkResult.Success(DmfStatus.Pending)

                // Executing, Unknown, or anything this app doesn't recognise yet - treated as
                // still running rather than failing on an unexpected value.
                else -> NetworkResult.Success(DmfStatus.Running(percent = null))
            }
        }
    }

    /**
     * A one-line account of why rows were refused, or null if there is nothing to say.
     * `GetExecutionErrors` returns a JSON *string* holding an array of objects
     * (`RecordId`, `Field`, `ErrorMessage`) - not a list of plain strings - so it is parsed as
     * such and the rows are grouped by message: e.g. "5 lines not accepted - A reservation
     * hierarchy must be set up for the item. (lines 227, 228, 229, 230, 231)". The record id is
     * `<journal> <line number>`; the line number is its last token.
     */
    private suspend fun fetchExecutionErrorSummary(executionId: String): String? {
        val errors = errorMapper.safeCall { odata.getExecutionErrors(ExecutionIdRequest(executionId)) }
        val raw = (errors as? NetworkResult.Success)?.data?.stringValue()?.takeIf { it.isNotBlank() }
            ?: return null

        val rows = runCatching { json.parseToJsonElement(raw).jsonArray }.getOrNull()
            ?: return raw.trim().take(300)

        val refused = rows.mapNotNull { it as? JsonObject }.map { row ->
            val line = (row["RecordId"] as? JsonPrimitive)?.content.orEmpty().trim().substringAfterLast(' ')
            val message = (row["ErrorMessage"] as? JsonPrimitive)?.content.orEmpty()
                .lines().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
            line to message
        }
        if (refused.isEmpty()) return null

        return refused.groupBy({ it.second }, { it.first }).entries.take(2).joinToString("; ") { (message, lines) ->
            val shown = lines.take(5).joinToString(", ") +
                if (lines.size > 5) " and ${lines.size - 5} more" else ""
            "${lines.size} line${if (lines.size == 1) "" else "s"} not accepted - $message (lines $shown)"
        }
    }

    // -- Counting workflow -------------------------------------------------------------------

    suspend fun startCounting(journalId: String) = withContext(ioDispatcher) {
        journalDao.markCountStarted(journalId, System.currentTimeMillis())
    }

    /**
     * Unlocks a SUBMITTED journal for more counting. Only after the caller has confirmed with
     * the server that the journal is still open in D365FO - a posted journal is never reopened
     * (see CountingJournalListViewModel.reopenCounting). Every count stays; End count re-sends
     * only counted lines, so re-sending is safe.
     */
    suspend fun reopenCounting(journalId: String) = withContext(ioDispatcher) {
        countingDao.deleteSubmission(journalId)
        journalDao.updateStatus(journalId, JournalStatus.DRAFT, SyncState.SYNCED, null)
    }

    /** The server says the journal was posted: lock it on the device for good. */
    suspend fun markPostedInD365(journalId: String) = withContext(ioDispatcher) {
        journalDao.markPosted(journalId, journalId, System.currentTimeMillis())
    }

    /**
     * Fills in item names for [journalId]'s lines. The export has no item name column (only
     * `ITEMNUMBER`), so every line starts out named after its own number; this resolves the
     * distinct item numbers against `ReleasedProductsV2` in small `or`-chained batches and writes
     * the operator-facing name (`SearchName`, same as the rest of the app) back. Best effort:
     * a failed batch leaves those lines named by number, and the next call retries only what is
     * still unresolved. Returns how many item numbers are still unnamed afterwards.
     */
    suspend fun enrichItemNames(journalId: String): Int = withContext(ioDispatcher) {
        val unnamed = journalDao.unnamedItemIds(journalId)
        if (unnamed.isEmpty()) return@withContext 0

        val dataArea = companyProvider()?.lowercase()?.takeIf { it.isNotBlank() }
        var stillUnnamed = unnamed.size

        unnamed.chunked(ITEM_NAME_BATCH).forEach { batch ->
            val result = errorMapper.safeCall {
                odata.getReleasedProducts(
                    filter = ODataFilter.and(
                        dataArea?.let { ODataFilter.eq("dataAreaId", it) },
                        ODataFilter.anyOf("ItemNumber", batch),
                    ),
                    top = batch.size,
                )
            }
            if (result is NetworkResult.Success) {
                result.data.value.forEach { product ->
                    val name = product.searchName?.takeIf { it.isNotBlank() } ?: return@forEach
                    journalDao.setItemName(journalId, product.itemNumber, name)
                    stillUnnamed--
                }
            }
        }
        stillUnnamed.coerceAtLeast(0)
    }

    // -- Local package cache -----------------------------------------------------------------
    //
    // Manifest.xml/PackageHeader.xml round-trip byte for byte between download and re-upload -
    // this app never constructs DMF package XML itself, only the CSV inside it.

    private fun packageDir(journalId: String): File =
        File(context.filesDir, "counting_packages/$journalId").apply { mkdirs() }

    private fun cachePackageFiles(journalId: String, files: DmfPackageFiles) {
        val dir = packageDir(journalId)
        File(dir, "Manifest.xml").writeBytes(files.manifestXml)
        File(dir, "PackageHeader.xml").writeBytes(files.packageHeaderXml)
        File(dir, "data.csv").writeBytes(files.dataFileBytes)
        File(dir, "dataFileName.txt").writeText(files.dataFileName)
    }

    private fun readCachedPackageFiles(journalId: String): DmfPackageFiles? {
        val dir = packageDir(journalId)
        val manifest = File(dir, "Manifest.xml").takeIf { it.exists() } ?: return null
        val header = File(dir, "PackageHeader.xml").takeIf { it.exists() } ?: return null
        val data = File(dir, "data.csv").takeIf { it.exists() } ?: return null
        val name = File(dir, "dataFileName.txt").takeIf { it.exists() }?.readText() ?: return null
        return DmfPackageFiles(
            manifestXml = manifest.readBytes(),
            packageHeaderXml = header.readBytes(),
            dataFileName = name,
            dataFileBytes = data.readBytes(),
        )
    }

    private companion object {
        /** Created once, by hand, in Data Management workspace - see aos/README.md. The export
         *  direction's own definition group name lives in X++ now (HSMobileOpsCountingExportSetup),
         *  since exportOpenJournals runs the export itself rather than the app triggering it. */
        const val IMPORT_DEFINITION_GROUP = "HSMobileOpsCountingImport"

        /** Item numbers per ReleasedProductsV2 request - an `or` chain, kept short enough for the URL. */
        const val ITEM_NAME_BATCH = 20

        /** RIS's execution-id timestamp shape. A getter, not a shared instance - SimpleDateFormat isn't thread-safe. */
        val EXECUTION_STAMP: SimpleDateFormat get() = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
    }
}

/** DMF exports numbers as decimals - `LINENUMBER` arrives as `1.0000000000000000`, which `toIntOrNull` rejects. */
private fun String?.toLineNumber(): Int? = this?.trim()?.toBigDecimalOrNull()?.toInt()

/** `5.000000` -> `5`, `.000000` -> `0`; null/blank/unparseable stays null. */
private fun String?.toPlainQuantity(): String? =
    this?.trim()?.takeIf { it.isNotEmpty() }?.toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString()

/**
 * What one download did to a journal's lines. [refreshed] is true when the journal was already on
 * the device (a "Get data again"), in which case [added]/[removed] are what changed; on a first
 * download every line counts as added.
 */
data class ImportSummary(val total: Int, val added: Int, val removed: Int, val refreshed: Boolean) {
    /** One line for the card / Activity page, or null for a first download (nothing to explain). */
    val note: String?
        get() = when {
            !refreshed -> null
            added == 0 && removed == 0 -> "Refreshed - no new lines"
            else -> buildString {
                append("Refreshed - $added new line${if (added == 1) "" else "s"} added")
                if (removed > 0) append(", $removed removed")
            }
        }
}
