package com.d365fo.mobileops.data.counting

import com.d365fo.mobileops.data.remote.api.CountTaskServiceApi
import com.d365fo.mobileops.data.remote.dto.CountClaimRequestBody
import com.d365fo.mobileops.data.remote.dto.CountClaimServiceRequest
import com.d365fo.mobileops.data.remote.dto.CountClaimServiceResponse
import com.d365fo.mobileops.data.remote.dto.CountClaimTokenRequestBody
import com.d365fo.mobileops.data.remote.dto.CountClaimTokenServiceRequest
import com.d365fo.mobileops.data.remote.dto.JournalStateRequestBody
import com.d365fo.mobileops.data.remote.dto.JournalStateServiceRequest
import com.d365fo.mobileops.data.remote.error.D365ErrorMapper
import com.d365fo.mobileops.data.repository.WorkerSessionRepository
import com.d365fo.mobileops.data.security.CompanyProvider
import com.d365fo.mobileops.data.security.SecureStore
import com.d365fo.mobileops.domain.counting.ClaimOutcome
import com.d365fo.mobileops.domain.counting.ClaimRefusal
import com.d365fo.mobileops.domain.counting.CountTaskRepository
import com.d365fo.mobileops.domain.counting.CounterProfile
import com.d365fo.mobileops.domain.counting.DiscrepancyReason
import com.d365fo.mobileops.domain.counting.LineAttributes
import com.d365fo.mobileops.domain.counting.JournalRemoteState
import com.d365fo.mobileops.domain.counting.OpportunityTask
import com.d365fo.mobileops.domain.counting.TaskResult
import com.d365fo.mobileops.domain.model.CountRange
import com.d365fo.mobileops.util.AppFailure
import com.d365fo.mobileops.util.Formatters
import com.d365fo.mobileops.util.NetworkResult
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LiveCountTaskRepository @Inject constructor(
    private val api: CountTaskServiceApi,
    private val errorMapper: D365ErrorMapper,
    private val companyProvider: CompanyProvider,
    private val secureStore: SecureStore,
    private val workerSession: WorkerSessionRepository,
) : CountTaskRepository {

    private fun company(): String = companyProvider()?.takeIf { it.isNotBlank() }?.lowercase().orEmpty()
    private fun userId(): String = workerSession.session.value?.userName.orEmpty()

    override suspend fun claim(journalId: String, range: CountRange?): ClaimOutcome {
        val result = errorMapper.safeCall {
            api.claimJournal(
                CountClaimServiceRequest(
                    CountClaimRequestBody(
                        dataAreaId = company(),
                        journalId = journalId,
                        userId = userId(),
                        deviceId = secureStore.deviceId(),
                        rangeId = range?.rangeId,
                        locationFrom = range?.locationFrom,
                        locationTo = range?.locationTo,
                    ),
                ),
            )
        }
        return result.toClaimOutcome(fallbackRange = range)
    }

    override suspend fun heartbeat(journalId: String, claimToken: String): ClaimOutcome =
        errorMapper.safeCall { api.heartbeatClaim(tokenRequest(journalId, claimToken)) }.toClaimOutcome(null)

    override suspend fun release(journalId: String, claimToken: String): Boolean {
        val result = errorMapper.safeCall { api.releaseJournal(tokenRequest(journalId, claimToken)) }
        val body = (result as? NetworkResult.Success)?.data ?: return false
        return body.success || body.errorCode in setOf("CLAIM_EXPIRED", "CLAIM_LOST")
    }

    override suspend fun journalState(journalId: String): NetworkResult<JournalRemoteState> {
        val result = errorMapper.safeCall {
            api.getJournalState(JournalStateServiceRequest(JournalStateRequestBody(company(), journalId)))
        }
        return when (result) {
            is NetworkResult.Error -> result
            NetworkResult.Loading -> NetworkResult.Error(AppFailure.Fatal("unexpected Loading"))
            is NetworkResult.Success -> {
                val body = result.data
                val state = JournalRemoteState.entries.firstOrNull { it.name == body.state }
                if (body.success && state != null) {
                    NetworkResult.Success(state)
                } else {
                    NetworkResult.Error(AppFailure.BusinessRule("Could not read the journal's state", body.errorCode))
                }
            }
        }
    }

    override suspend fun counterProfile(): NetworkResult<CounterProfile> {
        val result = errorMapper.safeCall {
            api.getCounterProfile(
                com.d365fo.mobileops.data.remote.dto.CounterProfileServiceRequest(
                    com.d365fo.mobileops.data.remote.dto.CounterProfileRequestBody(company(), userId()),
                ),
            )
        }
        return when (result) {
            is NetworkResult.Error -> result
            NetworkResult.Loading -> NetworkResult.Error(AppFailure.Fatal("unexpected Loading"))
            is NetworkResult.Success -> if (result.data.success) {
                NetworkResult.Success(
                    CounterProfile(
                        isExternalCounter = result.data.isExternalCounter,
                        counterTeam = result.data.counterTeam?.takeIf { it.isNotBlank() },
                        isCycleCountSupervisor = result.data.isCycleCountSupervisor,
                    ),
                )
            } else {
                NetworkResult.Error(AppFailure.BusinessRule(taskMessage(result.data.errorCode), result.data.errorCode))
            }
        }
    }

    override suspend fun lineAttributes(journalId: String): NetworkResult<List<LineAttributes>> {
        val result = errorMapper.safeCall {
            api.getLineAttributes(
                com.d365fo.mobileops.data.remote.dto.LineAttributesServiceRequest(
                    com.d365fo.mobileops.data.remote.dto.LineAttributesRequestBody(company(), journalId, userId()),
                ),
            )
        }
        return when (result) {
            is NetworkResult.Error -> result
            NetworkResult.Loading -> NetworkResult.Error(AppFailure.Fatal("unexpected Loading"))
            is NetworkResult.Success -> if (result.data.success) {
                NetworkResult.Success(
                    result.data.lines.map {
                        LineAttributes(
                            lineNumber = it.lineNumber.toInt(),
                            locationType = it.locationType?.takeIf { t -> t.isNotBlank() } ?: "PHYSICAL",
                            owner = it.owner?.takeIf { o -> o.isNotBlank() },
                            verifyOnly = it.verifyOnly,
                        )
                    },
                )
            } else {
                NetworkResult.Error(AppFailure.BusinessRule(taskMessage(result.data.errorCode), result.data.errorCode))
            }
        }
    }

    override suspend fun createSpotCount(warehouseId: String, itemId: String?, locationId: String?): TaskResult<String> {
        val result = errorMapper.safeCall {
            api.createSpotCount(
                com.d365fo.mobileops.data.remote.dto.SpotCountServiceRequest(
                    com.d365fo.mobileops.data.remote.dto.SpotCountRequestBody(
                        dataAreaId = company(),
                        userId = userId(),
                        warehouseId = warehouseId,
                        itemNumber = itemId?.trim()?.takeIf { it.isNotEmpty() },
                        locationId = locationId?.trim()?.takeIf { it.isNotEmpty() },
                    ),
                ),
            )
        }
        return result.toTaskResult { body ->
            if (body.success && !body.journalId.isNullOrBlank()) TaskResult.Ok(body.journalId)
            else TaskResult.Refused(body.errorCode, body.message?.takeIf { it.isNotBlank() } ?: taskMessage(body.errorCode))
        }
    }

    override suspend fun opportunityCount(warehouseId: String, locationId: String, itemId: String?): OpportunityTask? {
        val result = errorMapper.safeCall {
            api.getOpportunityCount(
                com.d365fo.mobileops.data.remote.dto.OpportunityServiceRequest(
                    com.d365fo.mobileops.data.remote.dto.OpportunityRequestBody(
                        dataAreaId = company(),
                        userId = userId(),
                        warehouseId = warehouseId,
                        locationId = locationId,
                        itemNumber = itemId,
                    ),
                ),
            )
        }
        val body = (result as? NetworkResult.Success)?.data ?: return null
        val line = body.line ?: return null
        val journalId = body.journalId?.takeIf { it.isNotBlank() } ?: return null
        if (!body.success || !body.eligible) return null
        return OpportunityTask(
            journalId = journalId,
            journalNameId = body.journalNameId.orEmpty(),
            description = body.description,
            lineNumber = line.lineNumber.toInt(),
            itemId = line.itemNumber,
            itemName = line.itemName?.takeIf { it.isNotBlank() } ?: line.itemNumber,
            locationId = line.locationId?.takeIf { it.isNotBlank() },
            batchId = line.batchId?.takeIf { it.isNotBlank() },
            serialId = line.serialId?.takeIf { it.isNotBlank() },
            siteId = line.siteId,
            warehouseId = line.warehouseId,
        )
    }

    override suspend fun reportDiscrepancy(
        warehouseId: String,
        locationId: String,
        itemId: String?,
        reason: DiscrepancyReason,
        note: String?,
    ): TaskResult<String?> {
        val result = errorMapper.safeCall {
            api.reportDiscrepancy(
                com.d365fo.mobileops.data.remote.dto.DiscrepancyServiceRequest(
                    com.d365fo.mobileops.data.remote.dto.DiscrepancyRequestBody(
                        dataAreaId = company(),
                        userId = userId(),
                        deviceId = secureStore.deviceId(),
                        warehouseId = warehouseId,
                        locationId = locationId,
                        itemNumber = itemId,
                        reason = reason.name,
                        note = note?.trim()?.takeIf { it.isNotEmpty() },
                    ),
                ),
            )
        }
        return result.toTaskResult { body ->
            if (body.success) TaskResult.Ok(body.triggeredJournalId?.takeIf { it.isNotBlank() })
            else TaskResult.Refused(body.errorCode, body.message?.takeIf { it.isNotBlank() } ?: taskMessage(body.errorCode))
        }
    }

    private fun <B, T> NetworkResult<B>.toTaskResult(onBody: (B) -> TaskResult<T>): TaskResult<T> = when (this) {
        is NetworkResult.Success -> onBody(data)
        NetworkResult.Loading -> TaskResult.Unreachable("did not complete")
        is NetworkResult.Error -> when (val f = failure) {
            is AppFailure.Offline, is AppFailure.Transient -> TaskResult.Unreachable(f.message)
            is AppFailure.BusinessRule -> if (f.httpCode == 404) {
                TaskResult.Refused("NOT_IMPLEMENTED", taskMessage("NOT_IMPLEMENTED"))
            } else {
                TaskResult.Refused(f.d365ErrorCode, f.message)
            }
            else -> TaskResult.Refused(null, f.message)
        }
    }

    private fun taskMessage(code: String?): String = when (code) {
        "NOT_ALLOWED" -> "You don't have permission to do this."
        "NOTHING_TO_COUNT" -> "Enter an item, a location, or both."
        "NOT_IMPLEMENTED" -> "This server does not support this yet."
        "INVALID_REQUEST" -> "D365FO refused the request as incomplete."
        else -> code ?: "D365FO refused the request."
    }

    private fun tokenRequest(journalId: String, claimToken: String) = CountClaimTokenServiceRequest(
        CountClaimTokenRequestBody(
            dataAreaId = company(),
            journalId = journalId,
            claimToken = claimToken,
            userId = userId(),
            deviceId = secureStore.deviceId(),
        ),
    )

    private fun NetworkResult<CountClaimServiceResponse>.toClaimOutcome(fallbackRange: CountRange?): ClaimOutcome =
        when (this) {
            is NetworkResult.Error -> failure.toClaimOutcome()
            NetworkResult.Loading -> ClaimOutcome.Unreachable("unexpected Loading")
            is NetworkResult.Success -> {
                val body = data
                val token = body.claimToken?.takeIf { it.isNotBlank() }
                if (body.success && token != null) {
                    ClaimOutcome.Granted(
                        claimToken = token,
                        claimedBy = body.claimedBy?.takeIf { it.isNotBlank() } ?: userId(),
                        rangeId = body.rangeId ?: fallbackRange?.rangeId,
                        locationFrom = body.locationFrom ?: fallbackRange?.locationFrom,
                        locationTo = body.locationTo ?: fallbackRange?.locationTo,
                        expiresAt = Formatters.serviceDateTimeToEpochMillis(body.expiresUtc),
                        heartbeatSeconds = body.heartbeatSeconds,
                    )
                } else {
                    ClaimOutcome.Refused(refusalFor(body.errorCode), body.claimedBy?.takeIf { it.isNotBlank() }, body.message?.takeIf { it.isNotBlank() })
                }
            }
        }

    private fun AppFailure.toClaimOutcome(): ClaimOutcome = when (this) {
        is AppFailure.Offline, is AppFailure.Transient -> ClaimOutcome.Unreachable(message)
        // A 404 on a custom-service path means the service is not deployed on this environment.
        is AppFailure.BusinessRule -> if (httpCode == 404) {
            ClaimOutcome.Refused(ClaimRefusal.NOT_AVAILABLE, null, message)
        } else {
            ClaimOutcome.Refused(refusalFor(d365ErrorCode), null, message)
        }
        else -> ClaimOutcome.Refused(ClaimRefusal.OTHER, null, message)
    }

    private fun refusalFor(code: String?): ClaimRefusal = when (code) {
        "CLAIMED_BY_OTHER" -> ClaimRefusal.CLAIMED_BY_OTHER
        "CLAIM_EXPIRED" -> ClaimRefusal.EXPIRED
        "CLAIM_LOST" -> ClaimRefusal.LOST
        "JOURNAL_POSTED" -> ClaimRefusal.JOURNAL_POSTED
        "JOURNAL_NOT_FOUND" -> ClaimRefusal.JOURNAL_NOT_FOUND
        "RECOUNT_SAME_USER" -> ClaimRefusal.SAME_COUNTER
        "NOT_IMPLEMENTED" -> ClaimRefusal.NOT_AVAILABLE
        else -> ClaimRefusal.OTHER
    }
}
