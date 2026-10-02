package com.d365fo.mobileops.data.counting.supervisor

import com.d365fo.mobileops.data.remote.api.CountSupervisorServiceApi
import com.d365fo.mobileops.data.remote.dto.PostJournalRequestBody
import com.d365fo.mobileops.data.remote.dto.PostJournalServiceRequest
import com.d365fo.mobileops.data.remote.dto.ReviewRequestBody
import com.d365fo.mobileops.data.remote.dto.ReviewServiceRequest
import com.d365fo.mobileops.data.remote.dto.SupervisorServiceResponse
import com.d365fo.mobileops.data.remote.dto.VarianceRequestBody
import com.d365fo.mobileops.data.remote.dto.VarianceServiceRequest
import com.d365fo.mobileops.data.remote.error.D365ErrorMapper
import com.d365fo.mobileops.data.repository.WorkerSessionRepository
import com.d365fo.mobileops.data.security.CompanyProvider
import com.d365fo.mobileops.domain.counting.supervisor.JournalVariance
import com.d365fo.mobileops.domain.counting.supervisor.ReviewAction
import com.d365fo.mobileops.domain.counting.supervisor.SupervisorCountRepository
import com.d365fo.mobileops.domain.counting.supervisor.SupervisorRefusal
import com.d365fo.mobileops.domain.counting.supervisor.SupervisorResult
import com.d365fo.mobileops.domain.counting.supervisor.VarianceLine
import com.d365fo.mobileops.util.AppFailure
import com.d365fo.mobileops.util.NetworkResult
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LiveSupervisorCountRepository @Inject constructor(
    private val api: CountSupervisorServiceApi,
    private val errorMapper: D365ErrorMapper,
    private val companyProvider: CompanyProvider,
    private val workerSession: WorkerSessionRepository,
) : SupervisorCountRepository {

    private fun company(): String = companyProvider()?.takeIf { it.isNotBlank() }?.lowercase().orEmpty()
    private fun userId(): String = workerSession.session.value?.userName.orEmpty()

    override suspend fun variance(journalId: String): NetworkResult<JournalVariance> {
        val result = errorMapper.safeCall {
            api.getVariance(VarianceServiceRequest(VarianceRequestBody(company(), journalId, userId())))
        }
        return when (result) {
            is NetworkResult.Error -> result
            NetworkResult.Loading -> NetworkResult.Error(AppFailure.Fatal("unexpected Loading"))
            is NetworkResult.Success -> {
                val body = result.data
                if (!body.success) {
                    NetworkResult.Error(AppFailure.BusinessRule(messageFor(refusalFor(body.errorCode), null), body.errorCode))
                } else {
                    NetworkResult.Success(
                        JournalVariance(
                            currencyCode = body.currencyCode,
                            lines = body.lines.map {
                                VarianceLine(
                                    lineNumber = it.lineNumber,
                                    itemNumber = it.itemNumber,
                                    itemName = it.itemName?.takeIf { n -> n.isNotBlank() } ?: it.itemNumber,
                                    locationId = it.locationId?.takeIf { l -> l.isNotBlank() },
                                    batchId = it.batchId?.takeIf { b -> b.isNotBlank() },
                                    serialId = it.serialId?.takeIf { s -> s.isNotBlank() },
                                    counted = it.counted,
                                    countedQty = it.countedQty,
                                    systemQty = it.systemQty,
                                    varianceQty = it.varianceQty,
                                    varianceValue = it.varianceValue,
                                    toleranceStatus = it.toleranceStatus?.takeIf { t -> t.isNotBlank() } ?: "UNKNOWN",
                                    abcClass = it.abcClass?.takeIf { a -> a.isNotBlank() },
                                    countedBy = it.countedBy?.takeIf { c -> c.isNotBlank() },
                                    reviewStatus = it.reviewStatus?.takeIf { r -> r.isNotBlank() } ?: "PENDING",
                                )
                            },
                        ),
                    )
                }
            }
        }
    }

    override suspend fun review(
        journalId: String,
        lineNumbers: List<Double>,
        action: ReviewAction,
        assignTo: String?,
        note: String?,
    ): SupervisorResult = errorMapper.safeCall {
        api.reviewLines(
            ReviewServiceRequest(
                ReviewRequestBody(
                    dataAreaId = company(),
                    journalId = journalId,
                    userId = userId(),
                    action = action.name,
                    assignTo = assignTo?.trim()?.takeIf { it.isNotEmpty() },
                    note = note?.trim()?.takeIf { it.isNotEmpty() },
                    lineNumbers = lineNumbers,
                ),
            ),
        )
    }.toResult()

    override suspend fun post(journalId: String): SupervisorResult = errorMapper.safeCall {
        api.postJournal(PostJournalServiceRequest(PostJournalRequestBody(company(), journalId, userId())))
    }.toResult()

    private fun NetworkResult<SupervisorServiceResponse>.toResult(): SupervisorResult = when (this) {
        NetworkResult.Loading -> SupervisorResult.Unreachable("did not complete")
        is NetworkResult.Error -> when (val f = failure) {
            is AppFailure.Offline, is AppFailure.Transient -> SupervisorResult.Unreachable(f.message)
            is AppFailure.BusinessRule ->
                if (f.httpCode == 404) SupervisorResult.Refused(SupervisorRefusal.NOT_AVAILABLE, null)
                else SupervisorResult.Refused(refusalFor(f.d365ErrorCode), f.message)
            else -> SupervisorResult.Refused(SupervisorRefusal.OTHER, f.message)
        }
        is NetworkResult.Success -> if (data.success) {
            SupervisorResult.Done(data.affected)
        } else {
            SupervisorResult.Refused(refusalFor(data.errorCode), data.message)
        }
    }

    companion object {
        fun refusalFor(code: String?): SupervisorRefusal = when (code) {
            "NOT_SUPERVISOR" -> SupervisorRefusal.NOT_SUPERVISOR
            "JOURNAL_POSTED" -> SupervisorRefusal.ALREADY_POSTED
            "JOURNAL_NOT_FOUND" -> SupervisorRefusal.JOURNAL_NOT_FOUND
            "LINES_NEED_APPROVAL" -> SupervisorRefusal.LINES_NEED_APPROVAL
            "RECOUNT_SAME_USER" -> SupervisorRefusal.RECOUNT_SAME_USER
            "POST_FAILED" -> SupervisorRefusal.POST_FAILED
            "NOT_IMPLEMENTED" -> SupervisorRefusal.NOT_AVAILABLE
            else -> SupervisorRefusal.OTHER
        }

        /** What each refusal means for the supervisor, in plain words. */
        fun messageFor(reason: SupervisorRefusal, serverMessage: String?): String = when (reason) {
            SupervisorRefusal.NOT_SUPERVISOR -> "Only a cycle-count supervisor can do this."
            SupervisorRefusal.ALREADY_POSTED -> "This journal has already been posted."
            SupervisorRefusal.JOURNAL_NOT_FOUND -> "This journal is closed or no longer exists in D365FO."
            SupervisorRefusal.LINES_NEED_APPROVAL ->
                "Some lines are outside tolerance and need approval before the journal can be posted."
            SupervisorRefusal.RECOUNT_SAME_USER -> "A recount can't be assigned to the person who counted the line."
            SupervisorRefusal.POST_FAILED -> serverMessage ?: "D365FO could not post the journal."
            SupervisorRefusal.NOT_AVAILABLE -> "This server does not support this action yet."
            SupervisorRefusal.OTHER -> serverMessage ?: "D365FO refused the request."
        }
    }
}
