package com.d365fo.mobileops.data.counting

import com.d365fo.mobileops.data.local.dao.CountingDao
import com.d365fo.mobileops.data.local.dao.JournalDao
import com.d365fo.mobileops.data.local.dao.MergeResult
import com.d365fo.mobileops.data.local.entity.JournalEntity
import com.d365fo.mobileops.data.local.entity.JournalLineEntity
import com.d365fo.mobileops.data.local.entity.JournalStatus
import com.d365fo.mobileops.data.local.entity.JournalType
import com.d365fo.mobileops.data.local.entity.TransferMode
import com.d365fo.mobileops.data.remote.api.CountTaskServiceApi
import com.d365fo.mobileops.data.remote.dto.DirectCountRowDto
import com.d365fo.mobileops.data.remote.dto.DirectLinesRequestBody
import com.d365fo.mobileops.data.remote.dto.DirectLinesServiceRequest
import com.d365fo.mobileops.data.remote.dto.DirectSubmitRequestBody
import com.d365fo.mobileops.data.remote.dto.DirectSubmitServiceRequest
import com.d365fo.mobileops.data.remote.error.D365ErrorMapper
import com.d365fo.mobileops.data.repository.ImportSummary
import com.d365fo.mobileops.data.security.CompanyProvider
import com.d365fo.mobileops.data.security.SecureStore
import com.d365fo.mobileops.domain.model.CountRange
import com.d365fo.mobileops.domain.model.CountingJournalSummary
import com.d365fo.mobileops.util.AppFailure
import com.d365fo.mobileops.util.Formatters
import com.d365fo.mobileops.util.NetworkResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DIRECT transfer mode: a small journal's lines arrive in one `getJournalLines` call and its
 * counts go back in one `submitCounts` call, no DMF package either way. The lines are blind by
 * contract (no on-hand in the response), so there is no reference row to keep; a line marked
 * counted without a quantity is sent as MarkedOnly and the server decides what it means.
 */
@Singleton
class DirectCountingRepository @Inject constructor(
    private val api: CountTaskServiceApi,
    private val errorMapper: D365ErrorMapper,
    private val companyProvider: CompanyProvider,
    private val secureStore: SecureStore,
    private val journalDao: JournalDao,
    private val countingDao: CountingDao,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private fun company(): String = companyProvider()?.takeIf { it.isNotBlank() }?.lowercase().orEmpty()

    suspend fun downloadLines(
        journal: CountingJournalSummary,
        warehouseId: String,
        range: CountRange?,
        claimToken: String?,
    ): NetworkResult<ImportSummary> = withContext(ioDispatcher) {
        val result = errorMapper.safeCall {
            api.getJournalLines(
                DirectLinesServiceRequest(
                    DirectLinesRequestBody(
                        dataAreaId = company(),
                        journalId = journal.journalId,
                        claimToken = claimToken,
                        locationFrom = range?.locationFrom,
                        locationTo = range?.locationTo,
                    ),
                ),
            )
        }
        val body = when (result) {
            is NetworkResult.Error -> return@withContext result
            NetworkResult.Loading -> return@withContext NetworkResult.Error(AppFailure.Fatal("unexpected Loading"))
            is NetworkResult.Success -> result.data
        }
        if (!body.success) {
            return@withContext NetworkResult.Error(
                AppFailure.BusinessRule(messageFor(body.errorCode), d365ErrorCode = body.errorCode),
            )
        }

        val alreadyOnDevice = journalDao.getJournal(journal.journalId) != null
        if (!alreadyOnDevice) {
            journalDao.upsertJournal(
                JournalEntity(
                    journalId = journal.journalId,
                    type = JournalType.COUNTING,
                    journalNameId = journal.journalNameId,
                    serverJournalNumber = journal.journalId,
                    description = journal.description,
                    journalDate = journal.journalDate ?: System.currentTimeMillis(),
                    siteId = null,
                    warehouseId = warehouseId,
                    workerId = secureStore.getString(SecureStore.KEY_WORKER_ID) ?: "unknown",
                    deviceId = secureStore.deviceId(),
                    status = JournalStatus.DRAFT,
                    transferMode = TransferMode.DIRECT,
                ),
            )
        }

        val now = System.currentTimeMillis()
        val lines = body.lines
            .filter { range == null || range.contains(it.locationId) }
            .map { row ->
                val lineNumber = row.lineNumber.toInt()
                JournalLineEntity(
                    journalLineId = "${journal.journalId}-$lineNumber",
                    journalId = journal.journalId,
                    lineNumber = lineNumber,
                    itemId = row.itemNumber,
                    itemName = row.itemName?.takeIf { it.isNotBlank() } ?: row.itemNumber,
                    quantity = "",
                    unitId = "",
                    siteId = row.siteId,
                    warehouseId = row.warehouseId,
                    locationId = row.locationId,
                    batchId = row.batchId?.takeIf { it.isNotBlank() },
                    serialId = row.serialId?.takeIf { it.isNotBlank() },
                    inventDimId = null,
                    capturedAt = now,
                    recount = row.recount,
                )
            }

        val merge = if (alreadyOnDevice) {
            journalDao.mergeCountingLines(journal.journalId, lines)
        } else {
            journalDao.upsertLinesChunked(lines)
            MergeResult(added = lines.size, removed = 0)
        }
        journalDao.updateCountingAttributes(
            journal.journalId, TransferMode.DIRECT, journal.locationFirst, journal.zeroStockCheck,
            journal.countMode, journal.passNumber, journal.recountOf,
        )
        NetworkResult.Success(ImportSummary(lines.size, merge.added, merge.removed, refreshed = alreadyOnDevice))
    }

    suspend fun submit(journalId: String, audit: SubmissionAudit, claimToken: String?): NetworkResult<DirectSubmitResult> =
        withContext(ioDispatcher) {
            val counted = countingDao.getCountLines(journalId).filter { it.isCounted }
            if (counted.isEmpty()) {
                return@withContext NetworkResult.Error(
                    AppFailure.BusinessRule("Nothing has been counted yet.", d365ErrorCode = "NOTHING_COUNTED"),
                )
            }
            val startedByLine = journalDao.getLinesOnce(journalId).associate { it.journalLineId to it.lineCountStartedAt }

            val result = errorMapper.safeCall {
                api.submitCounts(
                    DirectSubmitServiceRequest(
                        DirectSubmitRequestBody(
                            dataAreaId = company(),
                            journalId = journalId,
                            claimToken = claimToken,
                            submissionId = audit.submissionId,
                            userId = audit.userId,
                            deviceId = audit.deviceId,
                            startedAtUtc = audit.startedAt?.let { Formatters.isoUtc(it) },
                            submittedAtUtc = Formatters.isoUtc(audit.submittedAt),
                            passNumber = audit.passNumber,
                            originType = audit.originType,
                            lines = counted.map { line ->
                                DirectCountRowDto(
                                    lineNumber = line.lineNumber.toDouble(),
                                    countedQuantity = line.countedQuantity?.toDoubleOrNull() ?: 0.0,
                                    markedOnly = line.countedQuantity == null && line.markedCounted,
                                    countedBy = line.countedBy,
                                    countedAtUtc = line.countedAt?.let { Formatters.isoUtc(it) },
                                    countStartedAtUtc = startedByLine[line.journalLineId]?.let { Formatters.isoUtc(it) },
                                )
                            },
                        ),
                    ),
                )
            }
            when (result) {
                is NetworkResult.Error -> result
                NetworkResult.Loading -> NetworkResult.Error(AppFailure.Fatal("unexpected Loading"))
                is NetworkResult.Success -> if (result.data.success) {
                    NetworkResult.Success(
                        DirectSubmitResult(
                            acceptedLines = result.data.acceptedLines,
                            thirdCountNeeded = result.data.thirdCountNeeded,
                            passResult = result.data.passResult?.takeIf { it.isNotBlank() },
                        ),
                    )
                } else {
                    NetworkResult.Error(
                        AppFailure.BusinessRule(
                            result.data.message ?: messageFor(result.data.errorCode),
                            d365ErrorCode = result.data.errorCode,
                        ),
                    )
                }
            }
        }

    /** Unexpected items and verified-empty locations - always via the direct service, whatever the journal's mode. */
    suspend fun submitExceptions(
        journalId: String,
        audit: SubmissionAudit,
        claimToken: String?,
        exceptions: List<com.d365fo.mobileops.data.local.entity.CountExceptionEntity>,
        emptyLocations: List<com.d365fo.mobileops.data.local.entity.CountLocationCheckEntity>,
    ): NetworkResult<Int> = withContext(ioDispatcher) {
        val result = errorMapper.safeCall {
            api.submitExceptions(
                com.d365fo.mobileops.data.remote.dto.CountExceptionsServiceRequest(
                    com.d365fo.mobileops.data.remote.dto.CountExceptionsRequestBody(
                        dataAreaId = company(),
                        journalId = journalId,
                        claimToken = claimToken,
                        submissionId = audit.submissionId,
                        userId = audit.userId,
                        deviceId = audit.deviceId,
                        exceptions = exceptions.map {
                            com.d365fo.mobileops.data.remote.dto.CountExceptionRowDto(
                                clientId = it.clientId,
                                itemNumber = it.itemId,
                                locationId = it.locationId,
                                quantity = it.quantity.toDoubleOrNull() ?: 0.0,
                                batchId = it.batchId,
                                serialId = it.serialId,
                                note = it.note,
                                capturedBy = it.capturedBy,
                                capturedAtUtc = Formatters.isoUtc(it.capturedAt),
                            )
                        },
                        emptyLocations = emptyLocations.map {
                            com.d365fo.mobileops.data.remote.dto.CountEmptyLocationRowDto(
                                locationId = it.locationId,
                                verifiedBy = it.verifiedBy,
                                verifiedAtUtc = Formatters.isoUtc(it.verifiedAt),
                            )
                        },
                    ),
                ),
            )
        }
        when (result) {
            is NetworkResult.Error -> result
            NetworkResult.Loading -> NetworkResult.Error(AppFailure.Fatal("unexpected Loading"))
            is NetworkResult.Success -> if (result.data.success) {
                NetworkResult.Success(result.data.accepted)
            } else {
                NetworkResult.Error(
                    AppFailure.BusinessRule(
                        result.data.message ?: messageFor(result.data.errorCode),
                        d365ErrorCode = result.data.errorCode,
                    ),
                )
            }
        }
    }

    private fun messageFor(code: String?): String = when (code) {
        "JOURNAL_POSTED" -> "This journal has already been posted in D365FO."
        "JOURNAL_NOT_FOUND" -> "This journal is closed or no longer exists in D365FO."
        "TOO_MANY_LINES" -> "This journal is too large for direct transfer."
        "NOT_IMPLEMENTED" -> "This server does not support direct counting yet."
        "CLAIM_LOST", "CLAIM_EXPIRED" -> "Your claim on this journal is no longer valid."
        "RECOUNT_SAME_USER" -> "This recount must be done by someone other than the previous counter."
        null -> "The server refused the request."
        else -> code
    }
}

/** What the server said about a DIRECT (or double-blind) submission. */
data class DirectSubmitResult(
    val acceptedLines: Int,
    val thirdCountNeeded: Boolean = false,
    val passResult: String? = null,
)
