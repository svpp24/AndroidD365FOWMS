package com.d365fo.mobileops.data.counting

import com.d365fo.mobileops.data.local.dao.CountingDao
import com.d365fo.mobileops.data.local.entity.CountBinEntity
import com.d365fo.mobileops.data.remote.api.CountTaskServiceApi
import com.d365fo.mobileops.data.remote.dto.CountBinRequestBody
import com.d365fo.mobileops.data.remote.dto.CountBinServiceRequest
import com.d365fo.mobileops.data.remote.dto.CountBinLinesRequestBody
import com.d365fo.mobileops.data.remote.dto.CountBinLinesServiceRequest
import com.d365fo.mobileops.data.remote.dto.CountBinRowDto
import com.d365fo.mobileops.data.remote.dto.CountBinLineRowDto
import com.d365fo.mobileops.data.remote.dto.rowsJson
import com.d365fo.mobileops.data.remote.error.D365ErrorMapper
import com.d365fo.mobileops.data.repository.WorkerSessionRepository
import com.d365fo.mobileops.data.security.CompanyProvider
import com.d365fo.mobileops.util.AppFailure
import com.d365fo.mobileops.util.NetworkResult
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** A line's count is what its bins add up to. No bins left: no count. */
fun lineTotalOfBins(binQuantities: List<String>): String? =
    binQuantities.takeIf { it.isNotEmpty() }
        ?.fold(BigDecimal.ZERO) { sum, quantity -> sum + (quantity.toBigDecimalOrNull() ?: BigDecimal.ZERO) }
        ?.let { if (it.signum() == 0) "0" else it.stripTrailingZeros().toPlainString() }

/** Bins are kept in upper case, so what a worker types meets what was set up. */
fun normalizeBin(raw: String): String = raw.trim().uppercase()

/**
 * A store's virtual bins for counting. A record of where a count was made: D365FO's on-hand stays
 * at location level. The list is kept on the device, so a count can go on with no connection. A
 * store with no bins counts by location alone.
 */
@Singleton
class CountBinRepository @Inject constructor(
    private val api: CountTaskServiceApi,
    private val errorMapper: D365ErrorMapper,
    private val companyProvider: CompanyProvider,
    private val workerSession: WorkerSessionRepository,
    private val countingDao: CountingDao,
) {
    private val sentRows = ConcurrentHashMap<String, Int>()

    private fun company(): String = companyProvider()?.takeIf { it.isNotBlank() }?.lowercase().orEmpty()
    private fun userName(): String = workerSession.session.value?.userName.orEmpty()

    fun observe(warehouseId: String) = countingDao.observeBins(warehouseId.uppercase())

    /** Best effort: with no connection the list last fetched stands. */
    suspend fun refresh(warehouseId: String) {
        val result = errorMapper.safeCall {
            api.getCountBins(CountBinServiceRequest(CountBinRequestBody(company(), warehouseId, userName())))
        }
        val body = (result as? NetworkResult.Success)?.data ?: return
        if (!body.success) return
        countingDao.replaceBins(
            warehouseId.uppercase(),
            body.bins.filter { it.binId.isNotBlank() }.map { it.toEntity(warehouseId) },
        )
    }

    /** A supervisor adds a bin that is not on the list. D365FO checks they are one. */
    suspend fun add(warehouseId: String, binId: String, locationId: String? = null): NetworkResult<String> {
        val bin = normalizeBin(binId)
        val location = locationId?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
        val result = errorMapper.safeCall {
            api.addCountBin(CountBinServiceRequest(CountBinRequestBody(company(), warehouseId, userName(), bin, location)))
        }
        return when (result) {
            is NetworkResult.Error -> result
            NetworkResult.Loading -> NetworkResult.Error(AppFailure.Fatal("unexpected Loading"))
            is NetworkResult.Success -> if (result.data.success) {
                val placed = result.data.bins.firstOrNull()?.locationId?.takeIf { it.isNotBlank() } ?: location
                countingDao.upsertBin(CountBinEntity(warehouseId.uppercase(), bin, locationId = placed))
                NetworkResult.Success(bin)
            } else {
                NetworkResult.Error(
                    AppFailure.BusinessRule(result.data.message?.takeIf { it.isNotBlank() } ?: "D365FO refused the bin.", d365ErrorCode = result.data.errorCode),
                )
            }
        }
    }

    /** What was counted where goes first, in batches. A server without the call (404) keeps nothing, and counts still go. */
    suspend fun submit(journalId: String, audit: SubmissionAudit): TransportResult<Unit> {
        val rows = countingDao.getLineBins(journalId)
        if (rows.isEmpty()) return TransportResult.Ok(Unit)
        val lineNumbers = countingDao.getCountLines(journalId).associate { it.journalLineId to it.lineNumber }
        val payload = rows.mapNotNull { row ->
            lineNumbers[row.journalLineId]?.let { CountBinLineRowDto(it.toDouble(), row.binId, row.quantity.toDoubleOrNull() ?: 0.0) }
        }.sortedBy { it.lineNumber }
        // A line's rows stay in one call: each call replaces what it names.
        val batches = ArrayList<List<CountBinLineRowDto>>()
        var batch = ArrayList<CountBinLineRowDto>()
        payload.groupBy { it.lineNumber }.values.forEach { line ->
            if (batch.size + line.size > ROWS_PER_CALL && batch.isNotEmpty()) {
                batches += batch
                batch = ArrayList()
            }
            batch += line
        }
        if (batch.isNotEmpty()) batches += batch

        val alreadySent = sentRows[audit.submissionId] ?: 0
        batches.forEachIndexed { index, rowsOfCall ->
            if (index < alreadySent) return@forEachIndexed
            val result = errorMapper.safeCall {
                api.submitBinCounts(
                    CountBinLinesServiceRequest(
                        CountBinLinesRequestBody(company(), journalId, audit.userId, audit.submissionId, rowsJson(CountBinLineRowDto.serializer(), rowsOfCall)),
                    ),
                )
            }
            when (result) {
                is NetworkResult.Error -> {
                    val failure = result.failure
                    if (failure is AppFailure.BusinessRule && failure.httpCode == 404) return TransportResult.Ok(Unit)
                    return failure.toTransport()
                }
                NetworkResult.Loading -> return TransportResult.Retry("did not complete")
                is NetworkResult.Success -> {
                    if (!result.data.success) {
                        return if (result.data.errorCode == "RETRY") {
                            TransportResult.Retry("D365FO was busy with the same lines")
                        } else {
                            AppFailure.BusinessRule(result.data.message?.takeIf { it.isNotBlank() } ?: "D365FO refused the counts per bin.", d365ErrorCode = result.data.errorCode).toTransport()
                        }
                    }
                    sentRows[audit.submissionId] = index + 1
                }
            }
        }
        sentRows.remove(audit.submissionId)
        return TransportResult.Ok(Unit)
    }

    private fun CountBinRowDto.toEntity(warehouseId: String) =
        CountBinEntity(
            warehouseId.uppercase(),
            normalizeBin(binId),
            description?.takeIf { it.isNotBlank() },
            locationId?.trim()?.uppercase()?.takeIf { it.isNotBlank() },
        )

    private companion object {
        const val ROWS_PER_CALL = 500
    }
}
