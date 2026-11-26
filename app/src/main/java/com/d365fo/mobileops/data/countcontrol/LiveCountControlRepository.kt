package com.d365fo.mobileops.data.countcontrol

import com.d365fo.mobileops.data.remote.api.CountControlServiceApi
import com.d365fo.mobileops.data.remote.dto.BlindCountDto
import com.d365fo.mobileops.data.remote.dto.CounterDto
import com.d365fo.mobileops.data.remote.dto.CountControlPayload
import com.d365fo.mobileops.data.remote.dto.CountControlRequestBody
import com.d365fo.mobileops.data.remote.dto.CountControlServiceRequest
import com.d365fo.mobileops.data.remote.dto.CountControlServiceResponse
import com.d365fo.mobileops.data.remote.dto.CountedTwicePayload
import com.d365fo.mobileops.data.remote.dto.VarianceLineRowDto
import com.d365fo.mobileops.data.remote.error.D365ErrorMapper
import com.d365fo.mobileops.data.repository.WorkerSessionRepository
import com.d365fo.mobileops.data.security.CompanyProvider
import com.d365fo.mobileops.domain.countcontrol.BinCount
import com.d365fo.mobileops.domain.countcontrol.BinDecision
import com.d365fo.mobileops.domain.countcontrol.CountControlOverview
import com.d365fo.mobileops.domain.countcontrol.CountControlRepository
import com.d365fo.mobileops.domain.countcontrol.CountedTwice
import com.d365fo.mobileops.domain.countcontrol.CountedTwiceBin
import com.d365fo.mobileops.domain.countcontrol.StoreBlindCount
import com.d365fo.mobileops.domain.countcontrol.StoreCounter
import com.d365fo.mobileops.domain.countcontrol.StoreJournalSummary
import com.d365fo.mobileops.domain.countcontrol.VarianceRow
import com.d365fo.mobileops.util.AppFailure
import com.d365fo.mobileops.util.NetworkResult
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import retrofit2.Response
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LiveCountControlRepository @Inject constructor(
    private val api: CountControlServiceApi,
    private val errorMapper: D365ErrorMapper,
    private val companyProvider: CompanyProvider,
    private val workerSession: WorkerSessionRepository,
) : CountControlRepository {

    private fun body(lineId: String = "", decision: String = "", decided: Boolean = false, hold: Boolean = false) =
        CountControlServiceRequest(
            CountControlRequestBody(
                dataAreaId = companyProvider()?.takeIf { it.isNotBlank() }?.lowercase().orEmpty(),
                userName = workerSession.session.value?.userName.orEmpty(),
                warehouseId = workerSession.session.value?.selected?.warehouseId.orEmpty(),
                lineId = lineId,
                decision = decision,
                decided = decided,
                hold = hold,
            ),
        )

    override suspend fun overview(): NetworkResult<CountControlOverview> =
        read({ api.getCountControl(body()) }) { json -> toOverview(PAYLOAD_JSON.decodeFromString<CountControlPayload>(json)) }

    override suspend fun countedTwice(decided: Boolean): NetworkResult<CountedTwice> =
        read({ api.getCountedTwice(body(decided = decided)) }) { json -> toCountedTwice(PAYLOAD_JSON.decodeFromString<CountedTwicePayload>(json)) }

    override suspend fun counters(): NetworkResult<List<StoreCounter>> =
        read({ api.getCounters(body()) }) { json ->
            PAYLOAD_JSON.decodeFromString<List<CounterDto>>(json).map {
                StoreCounter(it.userName, it.name.ifBlank { it.userName }, it.masterId, it.deviceId, it.startedAt,
                    it.lines.toIntOrZero(), it.lastScanAt, it.ended.toBool())
            }
        }

    override suspend fun blindCounts(): NetworkResult<List<StoreBlindCount>> =
        read({ api.getBlindCounts(body()) }) { json ->
            PAYLOAD_JSON.decodeFromString<List<BlindCountDto>>(json).map {
                StoreBlindCount(it.masterId, it.description, it.status, it.counters.toIntOrZero(), it.lines.toIntOrZero(),
                    it.startedAt, it.inJournal.toBool(), it.message)
            }
        }

    override suspend fun varianceLines(): NetworkResult<List<VarianceRow>> =
        read({ api.getVarianceLines(body()) }) { json ->
            PAYLOAD_JSON.decodeFromString<List<VarianceLineRowDto>>(json).map {
                VarianceRow(it.itemId, it.itemName.ifBlank { it.itemId }, it.locationId, it.batchId, it.serialId,
                    it.counted.toDoubleOrZero(), it.onHand.toDoubleOrZero(), it.variance.toDoubleOrZero())
            }
        }

    override suspend fun decide(lineId: String, decision: BinDecision): NetworkResult<String> =
        write { api.decideBin(body(lineId = lineId, decision = decision.name)) }

    override suspend fun hold(hold: Boolean): NetworkResult<String> =
        write { api.holdStoreJournal(body(hold = hold)) }

    override suspend fun post(): NetworkResult<String> =
        write { api.postStoreJournal(body()) }

    private suspend fun <T> read(call: suspend () -> Response<CountControlServiceResponse>, parse: (String) -> T): NetworkResult<T> =
        when (val result = refusalsMapped(errorMapper.safeCall(call))) {
            is NetworkResult.Error -> result
            NetworkResult.Loading -> NetworkResult.Error(AppFailure.Fatal("unexpected Loading"))
            is NetworkResult.Success -> try {
                NetworkResult.Success(parse(result.data.payloadJson?.takeIf { it.isNotBlank() } ?: "{}"))
            } catch (e: Exception) {
                NetworkResult.Error(AppFailure.Fatal("D365FO's answer could not be read.", e))
            }
        }

    private suspend fun write(call: suspend () -> Response<CountControlServiceResponse>): NetworkResult<String> =
        when (val result = refusalsMapped(errorMapper.safeCall(call))) {
            is NetworkResult.Error -> result
            NetworkResult.Loading -> NetworkResult.Error(AppFailure.Fatal("unexpected Loading"))
            is NetworkResult.Success -> NetworkResult.Success(result.data.message.orEmpty())
        }

    companion object {
        internal val PAYLOAD_JSON = Json { ignoreUnknownKeys = true; isLenient = true }

        /** What the supervisor sees for D365FO's refusal codes; the server's own message when it gave one. */
        internal fun messageFor(code: String?, serverMessage: String?): String = when (code) {
            "NOT_SUPERVISOR" -> "Count control is for cycle count supervisors."
            "NOT_DEFAULT_WAREHOUSE" -> "Count control covers only your default store. Switch to it first."
            "NOT_AVAILABLE" -> "This server does not support Count control yet."
            else -> serverMessage?.takeIf { it.isNotBlank() } ?: "D365FO refused this ($code)."
        }

        /** A refusal in the body, and a 404 (service not deployed), as business-rule failures. */
        internal fun refusalsMapped(result: NetworkResult<CountControlServiceResponse>): NetworkResult<CountControlServiceResponse> =
            when (result) {
                is NetworkResult.Error -> {
                    val failure = result.failure
                    if (failure is AppFailure.BusinessRule && failure.httpCode == 404) {
                        NetworkResult.Error(AppFailure.BusinessRule(messageFor("NOT_AVAILABLE", null), "NOT_AVAILABLE", httpCode = 404))
                    } else {
                        result
                    }
                }
                is NetworkResult.Success -> if (result.data.success) {
                    result
                } else {
                    NetworkResult.Error(AppFailure.BusinessRule(messageFor(result.data.errorCode, result.data.message), result.data.errorCode))
                }
                NetworkResult.Loading -> result
            }

        internal fun toOverview(p: CountControlPayload) = CountControlOverview(
            storeId = p.storeId,
            storeName = p.storeName,
            binsCountedTwice = p.tiles.binsCountedTwice.toIntOrZero(),
            countersCounting = p.tiles.countersCounting.toIntOrZero(),
            blindCountsToday = p.tiles.blindCountsToday.toIntOrZero(),
            linesWithVariance = p.tiles.linesWithVariance.toIntOrZero(),
            journal = p.storeJournal?.let {
                StoreJournalSummary(it.journalId, it.status, it.statusLabel, it.blindCounts.toIntOrZero(), it.lines.toIntOrZero(),
                    it.countedTwice.toIntOrZero(), it.onHold.toBool(), it.heldBy, it.postMessage)
            },
        )

        internal fun toCountedTwice(p: CountedTwicePayload) = CountedTwice(
            toDecide = p.toDecide.toIntOrZero(),
            decided = p.decided.toIntOrZero(),
            bins = p.bins.map { b ->
                CountedTwiceBin(
                    lineId = b.lineId, locationId = b.locationId, binId = b.binId, itemId = b.itemId,
                    itemName = b.itemName.ifBlank { b.itemId }, onHand = b.onHand.toDoubleOrZero(), decision = b.decision,
                    counts = b.counts.map { c ->
                        BinCount(c.lineId, c.counter, c.counterName.ifBlank { c.counter }, c.masterId, c.countedAt,
                            c.qty.toDoubleOrZero(), c.included.toBool())
                    },
                )
            },
        )

        private fun String.toIntOrZero(): Int = trim().toDoubleOrNull()?.toInt() ?: 0
        private fun String.toDoubleOrZero(): Double = trim().toDoubleOrNull() ?: 0.0
        private fun String.toBool(): Boolean = trim().equals("true", ignoreCase = true) || trim() == "1"
    }
}
