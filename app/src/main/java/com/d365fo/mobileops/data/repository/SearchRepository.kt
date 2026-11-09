package com.d365fo.mobileops.data.repository

import com.d365fo.mobileops.data.remote.api.MobileOpsServiceApi
import com.d365fo.mobileops.data.remote.dto.SearchHitDto
import com.d365fo.mobileops.data.remote.dto.SearchRequestBody
import com.d365fo.mobileops.data.remote.dto.SearchServiceRequest
import com.d365fo.mobileops.data.remote.error.D365ErrorMapper
import com.d365fo.mobileops.data.security.CompanyProvider
import com.d365fo.mobileops.util.AppFailure
import com.d365fo.mobileops.util.NetworkResult
import com.d365fo.mobileops.util.plainError
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

enum class SearchHitKind { PO, TO, JOURNAL }

/** One order or journal Home's search found. */
data class SearchHit(
    val kind: SearchHitKind,
    /** The query was this record's own number, not an item on it. */
    val orderMatch: Boolean,
    val reference: String,
    val title: String,
    val detail: String,
    val status: String,
    val date: String,
    val quantity: Double,
    val unitId: String,
    /** Orders: it ships from this warehouse. */
    val outbound: Boolean,
)

/** What a query found: the item it named, if any, and the orders and journals. */
data class SearchResult(
    val itemId: String?,
    val itemName: String?,
    val unitId: String?,
    val hits: List<SearchHit>,
)

/** Home's search ("Search items, orders, journals") - HSMobileOpsSearchSvc, a read. */
@Singleton
class SearchRepository @Inject constructor(
    private val service: MobileOpsServiceApi,
    private val errorMapper: D365ErrorMapper,
    private val companyProvider: CompanyProvider,
    private val ioDispatcher: CoroutineDispatcher,
) {

    suspend fun search(query: String, warehouseId: String): NetworkResult<SearchResult> = withContext(ioDispatcher) {
        val company = companyProvider()?.takeIf { it.isNotBlank() }?.lowercase().orEmpty()
        when (val r = errorMapper.safeCall { service.search(SearchServiceRequest(SearchRequestBody(company, warehouseId, query.trim()))) }) {
            is NetworkResult.Error -> r
            NetworkResult.Loading -> NetworkResult.Error(AppFailure.Fatal("unexpected Loading"))
            is NetworkResult.Success -> if (!r.data.success) {
                NetworkResult.Error(AppFailure.BusinessRule(plainError(r.data.errorCode, "search"), r.data.errorCode))
            } else {
                NetworkResult.Success(
                    SearchResult(
                        itemId = r.data.itemId?.takeIf { it.isNotBlank() },
                        itemName = r.data.itemName?.takeIf { it.isNotBlank() },
                        unitId = r.data.unitId?.takeIf { it.isNotBlank() },
                        hits = mergeLines(r.data.hits.mapNotNull(::toHit)),
                    ),
                )
            }
        }
    }

    /**
     * One row per record and place: a journal holding the item on two lines at one location
     * (two batches, say) reads as one row with the counted quantities added up.
     */
    private fun mergeLines(hits: List<SearchHit>): List<SearchHit> =
        hits.groupBy { Triple(it.kind, it.reference, it.detail to it.orderMatch) }
            .values
            .map { same -> same.first().copy(quantity = same.sumOf { it.quantity }) }

    private fun toHit(dto: SearchHitDto): SearchHit? {
        val kind = runCatching { SearchHitKind.valueOf(dto.kind) }.getOrNull() ?: return null
        return SearchHit(
            kind = kind,
            orderMatch = dto.match == "ORDER",
            reference = dto.reference,
            title = dto.title,
            detail = dto.detail,
            status = dto.status,
            date = dto.date,
            quantity = dto.quantity,
            unitId = dto.unitId,
            outbound = dto.direction == "OUT",
        )
    }
}
