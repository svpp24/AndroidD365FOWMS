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
import com.d365fo.mobileops.domain.counting.JournalRemoteState
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
                    ClaimOutcome.Refused(refusalFor(body.errorCode), body.claimedBy, body.message)
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
        "NOT_IMPLEMENTED" -> ClaimRefusal.NOT_AVAILABLE
        else -> ClaimRefusal.OTHER
    }
}
