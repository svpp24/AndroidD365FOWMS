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
import com.d365fo.mobileops.data.remote.dto.rowsJson
import com.d365fo.mobileops.data.remote.error.D365ErrorMapper
import com.d365fo.mobileops.data.repository.ImportSummary
import com.d365fo.mobileops.data.security.CompanyProvider
import com.d365fo.mobileops.data.security.SecureStore
import com.d365fo.mobileops.domain.model.COUNT_MODE_DOUBLE_BLIND
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
    private val workerSession: com.d365fo.mobileops.data.repository.WorkerSessionRepository,
) {
    /** What D365FO has taken of a submission, so a retry goes on from there. A resend changes nothing. */
    private data class SubmitProgress(
        val lines: Int = 0,
        val accepted: Int = 0,
        val matched: Int = 0,
        val mismatched: Int = 0,
        val sameUser: Int = 0,
        val settled: Int = 0,
    )
    private val submitProgress = java.util.concurrent.ConcurrentHashMap<String, SubmitProgress>()

    private fun company(): String = companyProvider()?.takeIf { it.isNotBlank() }?.lowercase().orEmpty()

    suspend fun downloadLines(
        journal: CountingJournalSummary,
        warehouseId: String,
        range: CountRange?,
        claimToken: String?,
    ): NetworkResult<ImportSummary> = withContext(ioDispatcher) {
        // In pages: a dual count journal can be a whole warehouse. A small journal is one page.
        val rows = ArrayList<com.d365fo.mobileops.data.remote.dto.DirectLineRowDto>()
        var fromLine = 0.0
        do {
            val result = errorMapper.safeCall {
                api.getJournalLines(
                    DirectLinesServiceRequest(
                        DirectLinesRequestBody(
                            dataAreaId = company(),
                            journalId = journal.journalId,
                            claimToken = claimToken,
                            locationFrom = range?.locationFrom,
                            locationTo = range?.locationTo,
                            userId = workerSession.session.value?.userName,
                            fromLine = fromLine,
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
            rows += body.lines
            fromLine = body.nextLine
        } while (fromLine > 0)

        val alreadyOnDevice = journalDao.getJournal(journal.journalId) != null

        val now = System.currentTimeMillis()
        val lines = rows
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
                    unitId = row.unitId.orEmpty(),
                    configId = row.configId?.takeIf { it.isNotBlank() },
                    sizeId = row.sizeId?.takeIf { it.isNotBlank() },
                    colorId = row.colorId?.takeIf { it.isNotBlank() },
                    styleId = row.styleId?.takeIf { it.isNotBlank() },
                    siteId = row.siteId,
                    warehouseId = row.warehouseId,
                    locationId = row.locationId,
                    batchId = row.batchId?.takeIf { it.isNotBlank() },
                    serialId = row.serialId?.takeIf { it.isNotBlank() },
                    inventDimId = null,
                    capturedAt = now,
                    recount = row.recount,
                    locationType = row.locationType?.takeIf { it.isNotBlank() } ?: "PHYSICAL",
                    locationOwner = row.owner?.takeIf { it.isNotBlank() },
                    verifyOnly = row.verifyOnly,
                )
            }

        val merge = if (alreadyOnDevice) {
            journalDao.mergeCountingLines(journal.journalId, lines)
        } else {
            journalDao.saveDownloadedJournal(
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
                lines,
            )
            MergeResult(added = lines.size, removed = 0)
        }
        journalDao.updateCountingAttributes(
            journal.journalId, TransferMode.DIRECT, journal.locationFirst, journal.zeroStockCheck,
            journal.countMode, journal.passNumber, journal.recountOf,
            journal.originType, journal.priority, journal.toleranceInfo, journal.abcClass,
            journal.freezeState, journal.snapshotAt,
        )
        NetworkResult.Success(ImportSummary(lines.size, merge.added, merge.removed, refreshed = alreadyOnDevice))
    }

    suspend fun submit(journalId: String, audit: SubmissionAudit, claimToken: String?): NetworkResult<DirectSubmitResult> =
        withContext(ioDispatcher) {
            // Verify-only lines are confirmed, not counted - their confirmations go with the exceptions.
            val counted = countingDao.getCountLines(journalId).filter { it.isCounted && !it.verifyOnly }
            if (counted.isEmpty()) {
                return@withContext NetworkResult.Error(
                    AppFailure.BusinessRule("Nothing has been counted yet.", d365ErrorCode = "NOTHING_COUNTED"),
                )
            }
            val startedByLine = journalDao.getLinesOnce(journalId).associate { it.journalLineId to it.lineCountStartedAt }
            val doubleBlind = journalDao.getJournal(journalId)?.journal?.countMode == COUNT_MODE_DOUBLE_BLIND

            // In batches, like the control center's: a whole warehouse in one call outlasts the read timeout.
            var progress = submitProgress[audit.submissionId] ?: SubmitProgress()
            var passResult: String? = null
            for (batch in counted.drop(progress.lines).chunked(LINES_PER_CALL)) {
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
                                linesJson = rowsJson(DirectCountRowDto.serializer(), batch.map { line ->
                                    DirectCountRowDto(
                                        lineNumber = line.lineNumber.toDouble(),
                                        countedQuantity = line.countedQuantity?.toDoubleOrNull() ?: 0.0,
                                        markedOnly = line.countedQuantity == null && line.markedCounted,
                                        countedBy = line.countedBy,
                                        countedAtUtc = line.countedAt?.let { Formatters.isoUtc(it) },
                                        countStartedAtUtc = startedByLine[line.journalLineId]?.let { Formatters.isoUtc(it) },
                                    )
                                }),
                            ),
                        ),
                    )
                }
                when (result) {
                    is NetworkResult.Error -> return@withContext result
                    NetworkResult.Loading -> return@withContext NetworkResult.Error(AppFailure.Fatal("unexpected Loading"))
                    is NetworkResult.Success -> {
                        val answer = result.data
                        if (!answer.success) {
                            return@withContext NetworkResult.Error(
                                AppFailure.BusinessRule(
                                    answer.message?.takeIf { it.isNotBlank() } ?: messageFor(answer.errorCode),
                                    d365ErrorCode = answer.errorCode,
                                ),
                            )
                        }
                        passResult = answer.passResult?.takeIf { it.isNotBlank() }
                        progress = progress.copy(
                            lines = progress.lines + batch.size,
                            accepted = progress.accepted + answer.acceptedLines,
                            matched = progress.matched + answer.matchedLines,
                            mismatched = progress.mismatched + answer.mismatchedLines,
                            sameUser = progress.sameUser + answer.sameUserLines,
                            settled = progress.settled + answer.settledLines,
                        )
                        submitProgress[audit.submissionId] = progress
                    }
                }
            }
            submitProgress.remove(audit.submissionId)
            NetworkResult.Success(
                DirectSubmitResult(
                    acceptedLines = progress.accepted,
                    thirdCountNeeded = progress.mismatched > 0,
                    passResult = if (doubleBlind) dualCountSummary(progress, audit.passNumber >= 3) else passResult,
                ),
            )
        }

    /** What the counter is told. Counts and outcomes only: never a quantity. */
    private fun dualCountSummary(progress: SubmitProgress, tiebreak: Boolean): String = listOfNotNull(
        "${progress.accepted} counted",
        "${progress.matched} matched".takeIf { progress.matched > 0 },
        (if (tiebreak) "${progress.mismatched} for the supervisor to decide" else "${progress.mismatched} to recount").takeIf { progress.mismatched > 0 },
        "${progress.sameUser} you had counted before, not taken".takeIf { progress.sameUser > 0 },
        "${progress.settled} already settled, not taken".takeIf { progress.settled > 0 },
    ).joinToString(", ") + "."

    /** Unexpected items and verified-empty locations - always via the direct service, whatever the journal's mode. */
    suspend fun submitExceptions(
        journalId: String,
        audit: SubmissionAudit,
        claimToken: String?,
        exceptions: List<com.d365fo.mobileops.data.local.entity.CountExceptionEntity>,
        emptyLocations: List<com.d365fo.mobileops.data.local.entity.CountLocationCheckEntity>,
        confirmations: List<com.d365fo.mobileops.data.local.entity.JournalLineEntity> = emptyList(),
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
                        exceptionsJson = rowsJson(com.d365fo.mobileops.data.remote.dto.CountExceptionRowDto.serializer(), exceptions.map {
                            com.d365fo.mobileops.data.remote.dto.CountExceptionRowDto(
                                clientId = it.clientId,
                                itemNumber = it.itemId,
                                configId = it.configId,
                                sizeId = it.sizeId,
                                colorId = it.colorId,
                                styleId = it.styleId,
                                locationId = it.locationId,
                                quantity = it.quantity.toDoubleOrNull() ?: 0.0,
                                batchId = it.batchId,
                                serialId = it.serialId,
                                note = it.note,
                                capturedBy = it.capturedBy,
                                capturedAtUtc = Formatters.isoUtc(it.capturedAt),
                            )
                        }),
                        emptyLocationsJson = rowsJson(com.d365fo.mobileops.data.remote.dto.CountEmptyLocationRowDto.serializer(), emptyLocations.map {
                            com.d365fo.mobileops.data.remote.dto.CountEmptyLocationRowDto(
                                locationId = it.locationId,
                                verifiedBy = it.verifiedBy,
                                verifiedAtUtc = Formatters.isoUtc(it.verifiedAt),
                            )
                        }),
                        confirmationsJson = rowsJson(com.d365fo.mobileops.data.remote.dto.CountConfirmationRowDto.serializer(), confirmations.map {
                            com.d365fo.mobileops.data.remote.dto.CountConfirmationRowDto(
                                lineNumber = it.lineNumber.toDouble(),
                                confirmedBy = it.confirmedBy,
                                confirmedAtUtc = Formatters.isoUtc(it.confirmedAt ?: 0L),
                            )
                        }),
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
                        result.data.message?.takeIf { it.isNotBlank() } ?: messageFor(result.data.errorCode),
                        d365ErrorCode = result.data.errorCode,
                    ),
                )
            }
        }
    }

    /**
     * Wall-to-wall: after a location's counts are in, the server completes it. Its answer is said
     * to the counter either way: done and how many are left, or what is still missing there.
     */
    suspend fun completeLocation(journalId: String, audit: SubmissionAudit, locationId: String): NetworkResult<String> =
        withContext(ioDispatcher) {
            val result = errorMapper.safeCall {
                api.completeLocation(
                    com.d365fo.mobileops.data.remote.dto.CountLocationServiceRequest(
                        com.d365fo.mobileops.data.remote.dto.CountLocationRequestBody(
                            dataAreaId = company(),
                            journalId = journalId,
                            userId = audit.userId,
                            deviceId = audit.deviceId,
                            locationId = locationId,
                        ),
                    ),
                )
            }
            when (result) {
                is NetworkResult.Error -> result
                NetworkResult.Loading -> NetworkResult.Error(AppFailure.Fatal("unexpected Loading"))
                is NetworkResult.Success -> if (result.data.success) {
                    NetworkResult.Success(result.data.message?.takeIf { it.isNotBlank() } ?: "Location $locationId is complete.")
                } else {
                    NetworkResult.Error(
                        AppFailure.BusinessRule(
                            result.data.message?.takeIf { it.isNotBlank() } ?: messageFor(result.data.errorCode),
                            d365ErrorCode = result.data.errorCode,
                        ),
                    )
                }
            }
        }

    private fun messageFor(code: String?): String = when (code) {
        "JOURNAL_POSTED" -> "This journal has already been posted in D365FO."
        "JOURNAL_NOT_FOUND" -> "Journal closed."
        "TOO_MANY_LINES" -> "This journal is too large for direct transfer."
        "NOT_IMPLEMENTED" -> "This server does not support direct counting yet."
        "CLAIM_LOST", "CLAIM_EXPIRED" -> "Your claim on this journal is no longer valid."
        "RECOUNT_SAME_USER" -> "Someone else must do this recount."
        null -> "The server refused the request."
        else -> code
    }
}

private const val LINES_PER_CALL = 500

/** What the server said about a DIRECT (or double-blind) submission. */
data class DirectSubmitResult(
    val acceptedLines: Int,
    val thirdCountNeeded: Boolean = false,
    val passResult: String? = null,
)
