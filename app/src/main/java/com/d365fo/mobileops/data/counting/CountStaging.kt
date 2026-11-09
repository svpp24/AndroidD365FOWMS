package com.d365fo.mobileops.data.counting

import com.d365fo.mobileops.data.local.dao.CountingDao
import com.d365fo.mobileops.data.local.dao.JournalDao
import com.d365fo.mobileops.data.remote.api.CountStageServiceApi
import com.d365fo.mobileops.data.remote.dto.CountStageLineDto
import com.d365fo.mobileops.data.remote.dto.CountStageRequestBody
import com.d365fo.mobileops.data.remote.dto.CountStageServiceRequest
import com.d365fo.mobileops.data.remote.dto.CountStageServiceResponse
import com.d365fo.mobileops.data.remote.dto.rowsJson
import com.d365fo.mobileops.data.remote.error.D365ErrorMapper
import com.d365fo.mobileops.data.repository.WorkerSessionRepository
import com.d365fo.mobileops.data.security.CompanyProvider
import com.d365fo.mobileops.data.security.SecureStore
import com.d365fo.mobileops.data.local.entity.CountSubmissionError
import com.d365fo.mobileops.util.AppFailure
import com.d365fo.mobileops.util.Formatters
import com.d365fo.mobileops.util.NetworkResult
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** A counter's staging journal in a store's stock count. */
data class StageHandle(val masterId: String, val stageId: String)

/** What D365FO said about staged lines: the stock count, lines taken, undecided duplicates. */
data class StagedCount(val masterId: String, val accepted: Int, val duplicates: Int)

/**
 * The Stock count control center from the handheld (decision, 11 Oct: counts are staged per
 * counter in the store's stock count; a supervisor reviews them in D365FO - items counted twice
 * included - and transfers them into a counting journal). Free store counts always stage; counts
 * against a counting journal stage only at stores set up to review them
 * (HSMobileOpsCountStore.StageJournalCounts). A server without the service (404) stages nothing.
 */
@Singleton
class CountStagingRepository @Inject constructor(
    private val api: CountStageServiceApi,
    private val errorMapper: D365ErrorMapper,
    private val companyProvider: CompanyProvider,
    private val secureStore: SecureStore,
    private val workerSession: WorkerSessionRepository,
    private val journalDao: JournalDao,
    private val countingDao: CountingDao,
) {
    private val stageModes = ConcurrentHashMap<String, Boolean>()

    /** submissionId -> lines D365FO has taken, so a retry goes on from there. A resend is harmless. */
    private val sentLines = ConcurrentHashMap<String, Int>()

    private fun company(): String = companyProvider()?.takeIf { it.isNotBlank() }?.lowercase().orEmpty()
    private fun userName(): String = workerSession.session.value?.userName.orEmpty()

    private fun body(
        warehouseId: String = "",
        sourceJournalId: String = "",
        stageId: String = "",
        submissionId: String = "",
        linesJson: String = "",
        endCount: Boolean = false,
        user: String = userName(),
    ) = CountStageServiceRequest(
        CountStageRequestBody(
            dataAreaId = company(),
            userName = user,
            deviceId = secureStore.deviceId(),
            warehouseId = warehouseId,
            sourceJournalId = sourceJournalId,
            stageId = stageId,
            submissionId = submissionId,
            linesJson = linesJson,
            endCount = endCount,
        ),
    )

    /** Whether counts against counting journals at [warehouseId] go to the control center. Cached per store. */
    suspend fun stagesJournalCounts(warehouseId: String): NetworkResult<Boolean> {
        stageModes[warehouseId.uppercase()]?.let { return NetworkResult.Success(it) }
        return when (val result = errorMapper.safeCall { api.getStageMode(body(warehouseId = warehouseId)) }) {
            is NetworkResult.Success -> {
                val staged = result.data.success && result.data.stageJournalCounts
                stageModes[warehouseId.uppercase()] = staged
                NetworkResult.Success(staged)
            }
            is NetworkResult.Error -> if (result.failure.notDeployed()) {
                stageModes[warehouseId.uppercase()] = false
                NetworkResult.Success(false)
            } else {
                result
            }
            NetworkResult.Loading -> NetworkResult.Error(AppFailure.Fatal("unexpected Loading"))
        }
    }

    /** Starts or rejoins this counter's staging journal in the store's open stock count. */
    suspend fun open(warehouseId: String, sourceJournalId: String? = null, user: String = userName()): NetworkResult<StageHandle> =
        errorMapper.safeCall { api.openStage(body(warehouseId = warehouseId, sourceJournalId = sourceJournalId.orEmpty(), user = user)) }
            .answer { StageHandle(it.masterId.orEmpty(), it.stageId.orEmpty()) }

    /** Stages [lines] (a resend replaces a line by its client id); [endCount] submits the counter's journal. */
    suspend fun submit(
        stageId: String,
        submissionId: String,
        lines: List<CountStageLineDto>,
        endCount: Boolean,
        user: String = userName(),
    ): NetworkResult<StagedCount> =
        errorMapper.safeCall {
            api.submitLines(
                body(stageId = stageId, submissionId = submissionId, linesJson = rowsJson(CountStageLineDto.serializer(), lines),
                    endCount = endCount, user = user),
            )
        }.answer { StagedCount(it.masterId.orEmpty(), it.accepted, it.duplicates) }

    /**
     * End count for a counting journal at a store that reviews journal counts: the counted lines
     * go to the control center instead of into the journal. Null when the store does not stage
     * (the normal DMF or direct submit runs instead).
     */
    suspend fun submitJournal(journalId: String, audit: SubmissionAudit): TransportResult<StagedCount>? {
        val journal = journalDao.getJournal(journalId)?.journal ?: return null
        if (journal.countMode == com.d365fo.mobileops.domain.model.COUNT_MODE_DOUBLE_BLIND) return null
        val warehouseId = journal.warehouseId?.takeIf { it.isNotBlank() } ?: return null
        when (val mode = stagesJournalCounts(warehouseId)) {
            is NetworkResult.Success -> if (!mode.data) return null
            is NetworkResult.Error -> return mode.failure.toTransport()
            NetworkResult.Loading -> return TransportResult.Retry("did not complete")
        }
        val counted = countingDao.getCountLines(journalId).filter { it.countedQuantity != null && !it.verifyOnly }
        // Items found that are not on the journal go to the supervisor with the counts, not
        // straight into the journal past the review (14 Oct); Transfer adds them as lines.
        val unexpected = countingDao.unsentExceptions(journalId)
        if (counted.isEmpty() && unexpected.isEmpty()) return TransportResult.Fail(CountSubmissionError.NOTHING_COUNTED, "Nothing has been counted yet.")

        val user = audit.userId.ifBlank { userName() }
        val stage = when (val opened = open(warehouseId, journalId, user)) {
            is NetworkResult.Success -> opened.data
            is NetworkResult.Error -> return opened.failure.stageTransport()
            NetworkResult.Loading -> return TransportResult.Retry("did not complete")
        }
        val lines = counted.map { line ->
            CountStageLineDto(
                clientLineId = line.journalLineId,
                itemId = line.itemId,
                configId = line.configId,
                sizeId = line.sizeId,
                colorId = line.colorId,
                styleId = line.styleId,
                batchId = line.batchId,
                serialId = line.serialId,
                locationId = line.locationId,
                quantity = line.countedQuantity?.toDoubleOrNull() ?: 0.0,
                countedAtUtc = line.countedAt?.let { Formatters.isoUtc(it) },
                sourceLineNum = line.lineNumber.toDouble(),
            )
        } + unexpected.map { item ->
            CountStageLineDto(
                clientLineId = item.clientId,
                itemId = item.itemId,
                configId = item.configId,
                sizeId = item.sizeId,
                colorId = item.colorId,
                styleId = item.styleId,
                batchId = item.batchId,
                serialId = item.serialId,
                locationId = item.locationId,
                quantity = item.quantity.toDoubleOrNull() ?: 0.0,
                countedAtUtc = Formatters.isoUtc(item.capturedAt),
            )
        }
        // In batches: a whole warehouse in one call is one transaction that outlasts the read
        // timeout, and each retry then starts the same one again beside the first.
        val batches = lines.chunked(LINES_PER_CALL)
        val alreadySent = sentLines[audit.submissionId] ?: 0
        var lastAnswer: StagedCount? = null
        batches.forEachIndexed { index, batch ->
            val last = index == batches.lastIndex
            if (!last && (index + 1) * LINES_PER_CALL <= alreadySent) return@forEachIndexed
            when (val sent = submit(stage.stageId, audit.submissionId, batch, endCount = last, user = user)) {
                is NetworkResult.Success -> {
                    sentLines[audit.submissionId] = (index + 1) * LINES_PER_CALL
                    lastAnswer = sent.data
                }
                is NetworkResult.Error -> return sent.failure.stageTransport()
                NetworkResult.Loading -> return TransportResult.Retry("did not complete")
            }
        }
        sentLines.remove(audit.submissionId)
        if (unexpected.isNotEmpty()) countingDao.markExceptionsSent(journalId, System.currentTimeMillis())
        // The last call's count is one batch; the worker's message is about the whole count.
        return lastAnswer?.let { TransportResult.Ok(it.copy(accepted = lines.size)) } ?: TransportResult.Retry("did not complete")
    }

    private fun <T> NetworkResult<CountStageServiceResponse>.answer(map: (CountStageServiceResponse) -> T): NetworkResult<T> =
        when (this) {
            is NetworkResult.Error -> this
            NetworkResult.Loading -> NetworkResult.Error(AppFailure.Fatal("unexpected Loading"))
            is NetworkResult.Success -> if (data.success) {
                NetworkResult.Success(map(data))
            } else {
                NetworkResult.Error(
                    AppFailure.BusinessRule(data.message?.takeIf { it.isNotBlank() } ?: messageFor(data.errorCode), d365ErrorCode = data.errorCode),
                )
            }
        }

    /** Transferred or cancelled by the supervisor is a definite answer; the rest as End count reads it. */
    private fun AppFailure.stageTransport(): TransportResult<Nothing> =
        if (this is AppFailure.BusinessRule && d365ErrorCode in setOf("COUNT_TRANSFERRED", "COUNT_CANCELLED", "STAGE_NOT_FOUND", "NOT_ALLOWED")) {
            TransportResult.Fail(CountSubmissionError.OTHER, message)
        } else {
            toTransport()
        }

    private fun AppFailure.notDeployed(): Boolean = this is AppFailure.BusinessRule && httpCode == 404

    private companion object {
        const val LINES_PER_CALL = 500
    }

    private fun messageFor(code: String?): String = when (code) {
        "NOT_ALLOWED" -> "You are not an active mobile user in D365FO."
        "JOURNAL_POSTED" -> "This journal has already been posted in D365FO."
        "JOURNAL_NOT_FOUND" -> "Journal closed."
        "RETRY" -> "Started elsewhere at the same time - try again."
        else -> code ?: "D365FO refused the count."
    }
}
