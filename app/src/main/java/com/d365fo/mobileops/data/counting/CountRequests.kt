package com.d365fo.mobileops.data.counting

import com.d365fo.mobileops.data.remote.api.CountRequestServiceApi
import com.d365fo.mobileops.data.remote.dto.CountItemDto
import com.d365fo.mobileops.data.remote.dto.CountRequestBody
import com.d365fo.mobileops.data.remote.dto.CountRequestServiceRequest
import com.d365fo.mobileops.data.remote.dto.CountRequestServiceResponse
import com.d365fo.mobileops.data.remote.dto.CountResultDto
import com.d365fo.mobileops.data.remote.dto.CountSummaryDto
import com.d365fo.mobileops.data.remote.error.D365ErrorMapper
import com.d365fo.mobileops.data.repository.WorkerSessionRepository
import com.d365fo.mobileops.data.security.CompanyProvider
import com.d365fo.mobileops.data.security.SecureStore
import com.d365fo.mobileops.util.AppFailure
import com.d365fo.mobileops.util.NetworkResult
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The three kinds of count (user, 10 Nov 2026). None starts with a journal: counts go to the Stock
 * count control center, D365FO records on-hand and variance as they arrive, and the supervisor
 * decides duplicates and creates the journal.
 */
enum class CountType(val symbol: String, val label: String, val route: String) {
    HAZARD("Hazard", "Hazard count", "hazard"),
    WALL_TO_WALL("WallToWall", "Wall to wall count", "w2w"),
    BLIND("Blind", "Blind count", "blind");

    companion object {
        fun fromSymbol(value: String?): CountType? = entries.firstOrNull { it.symbol.equals(value, ignoreCase = true) }
        fun fromRoute(value: String?): CountType? = entries.firstOrNull { it.route == value }
    }
}

/** A count of the warehouse as D365FO lists it. */
data class CountSummary(
    val masterId: String,
    val type: CountType,
    val reference: String,
    val description: String,
    val dueUtc: String,
    val status: String,
    val journalId: String,
    val itemCount: Int,
    val counterCount: Int,
    val lineCount: Int,
    /** This worker's own part: Counting, Submitted, or blank when they have not joined. */
    val myStatus: String,
) {
    /** Still being counted - it can be joined. */
    val open: Boolean get() = status == "Open" || status == "Counting"
    /** The journal exists: counted, on-hand and variance can be shown. */
    val hasResults: Boolean get() = status == "JournalCreated" || status == "Posted"
}

/** What a hazard or blind count asks for: an item (with optional variant), a location, or both. */
@Serializable
data class RequestedItem(
    val itemId: String = "",
    val itemName: String = "",
    val configId: String = "",
    val sizeId: String = "",
    val colorId: String = "",
    val styleId: String = "",
    val locationId: String = "",
    /** First-level category (supervisor's view); blank when the item has none. */
    val category: String = "",
    /** Counted by any counter of this count - as D365FO knew it when the list was read. */
    val countedByAnyone: Boolean = false,
) {
    /** The same rule D365FO applies (HSMobileOpsCountControl::isRequested): a blank field matches anything. */
    fun matches(itemId: String, location: String, configId: String? = null, sizeId: String? = null,
                colorId: String? = null, styleId: String? = null): Boolean {
        fun eq(want: String, have: String?) = want.isBlank() || have == null || want.equals(have, ignoreCase = true)
        return eq(this.itemId, itemId) && eq(this.locationId, location) && eq(this.configId, configId) &&
            eq(this.sizeId, sizeId) && eq(this.colorId, colorId) && eq(this.styleId, styleId)
    }
}

/** One counting journal line of a finished count. */
data class CountResult(
    val itemId: String,
    val itemName: String,
    val variant: String,
    val batchId: String,
    val serialId: String,
    val locationId: String,
    val counted: Double,
    val onHand: Double,
    val variance: Double,
)

/** A category the supervisor can pick, or open to see its sub-categories. */
data class CategoryChoice(val id: String, val name: String, val hasChildren: Boolean, val itemCount: Int = 0)

/** What createCount answered: the control center record, and whether an open one was joined. */
data class CreatedCount(val masterId: String, val message: String, val existing: Boolean)

data class CountResults(val masterId: String, val status: String, val journalId: String, val posted: Boolean, val lines: List<CountResult>)

@Singleton
class CountRequestRepository @Inject constructor(
    private val api: CountRequestServiceApi,
    private val errorMapper: D365ErrorMapper,
    private val companyProvider: CompanyProvider,
    private val secureStore: SecureStore,
    private val workerSession: WorkerSessionRepository,
) {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    private fun body(warehouseId: String = "", masterId: String = "") = CountRequestServiceRequest(
        CountRequestBody(
            dataAreaId = companyProvider()?.takeIf { it.isNotBlank() }?.lowercase().orEmpty(),
            userName = workerSession.session.value?.userName.orEmpty(),
            deviceId = secureStore.deviceId(),
            warehouseId = warehouseId,
            masterId = masterId,
        ),
    )

    /** The warehouse's open counts of every type, and those finished in the last 7 days. */
    suspend fun counts(warehouseId: String): NetworkResult<List<CountSummary>> =
        errorMapper.safeCall { api.listCounts(body(warehouseId = warehouseId)) }.answer { r ->
            parse(r.payloadJson, CountSummaryDto.serializer()).mapNotNull { d ->
                val type = CountType.fromSymbol(d.type) ?: return@mapNotNull null
                CountSummary(
                    masterId = d.masterId, type = type, reference = d.reference, description = d.description,
                    dueUtc = d.dueUtc, status = d.status, journalId = d.journalId,
                    itemCount = d.itemCount.toIntOrNull() ?: 0, counterCount = d.counterCount.toIntOrNull() ?: 0,
                    lineCount = d.lineCount.toIntOrNull() ?: 0, myStatus = d.myStatus,
                )
            }
        }

    /** What a hazard or blind count asks to be counted. */
    suspend fun items(masterId: String): NetworkResult<List<RequestedItem>> =
        errorMapper.safeCall { api.getCountItems(body(masterId = masterId)) }.answer { r ->
            parse(r.payloadJson, CountItemDto.serializer()).map { d ->
                RequestedItem(
                    d.itemId, d.itemName, d.configId, d.sizeId, d.colorId, d.styleId, d.locationId,
                    category = d.category, countedByAnyone = d.counted.equals("true", ignoreCase = true),
                )
            }
        }

    /** Cycle count supervisor: categories under [parentId] (blank = first level), A to Z. */
    suspend fun categories(warehouseId: String, parentId: String = ""): NetworkResult<List<CategoryChoice>> =
        errorMapper.safeCall {
            api.listCategories(CountRequestServiceRequest(body(warehouseId = warehouseId).request.copy(categoryId = parentId)))
        }.answer { r ->
            parse(r.payloadJson, com.d365fo.mobileops.data.remote.dto.CountCategoryDto.serializer()).map {
                CategoryChoice(it.categoryId, it.name, it.hasChildren.equals("true", ignoreCase = true), it.itemCount.toIntOrNull() ?: 0)
            }
        }

    /**
     * Cycle count supervisor: a new Stock count control center record (user, 10 Nov) - every
     * counter of the warehouse sees it in the Count menu. Blind and hazard: the categories' items
     * (all of them) plus the items. Wall to wall: opens, or joins, the warehouse's one.
     */
    suspend fun create(
        type: CountType,
        warehouseId: String,
        categoryIds: List<String>,
        itemIds: List<String>,
    ): NetworkResult<CreatedCount> = errorMapper.safeCall {
        api.createCount(
            CountRequestServiceRequest(
                body(warehouseId = warehouseId).request.copy(
                    countType = type.symbol,
                    categoriesJson = if (categoryIds.isEmpty()) "" else
                        kotlinx.serialization.json.JsonArray(categoryIds.map { kotlinx.serialization.json.JsonPrimitive(it) }).toString(),
                    itemsJson = if (itemIds.isEmpty()) "" else
                        kotlinx.serialization.json.JsonArray(
                            itemIds.map { kotlinx.serialization.json.JsonObject(mapOf("ItemId" to kotlinx.serialization.json.JsonPrimitive(it))) },
                        ).toString(),
                ),
            ),
        )
    }.answer { r -> CreatedCount(r.masterId.orEmpty(), r.message.orEmpty(), r.existing) }

    /** Counted, on-hand and variance per journal line, once the supervisor has created the journal. */
    suspend fun results(masterId: String): NetworkResult<CountResults> =
        errorMapper.safeCall { api.getCountResults(body(masterId = masterId)) }.answer { r ->
            CountResults(
                masterId = r.masterId.orEmpty().ifBlank { masterId },
                status = r.status.orEmpty(),
                journalId = r.journalId.orEmpty(),
                posted = r.posted,
                lines = parse(r.payloadJson, CountResultDto.serializer()).map { d ->
                    CountResult(
                        itemId = d.itemId, itemName = d.itemName,
                        variant = listOf(d.configId, d.sizeId, d.colorId, d.styleId).filter { it.isNotBlank() }.joinToString(" / "),
                        batchId = d.batchId, serialId = d.serialId, locationId = d.locationId,
                        counted = d.counted.toDoubleOrNull() ?: 0.0, onHand = d.onHand.toDoubleOrNull() ?: 0.0,
                        variance = d.variance.toDoubleOrNull() ?: 0.0,
                    )
                },
            )
        }

    private fun <T> parse(payload: String?, serializer: KSerializer<T>): List<T> =
        payload?.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString(ListSerializer(serializer), it) }.getOrNull() }
            ?: emptyList()

    private fun <T> NetworkResult<CountRequestServiceResponse>.answer(map: (CountRequestServiceResponse) -> T): NetworkResult<T> =
        when (this) {
            is NetworkResult.Error -> this
            NetworkResult.Loading -> NetworkResult.Error(AppFailure.Fatal("unexpected Loading"))
            is NetworkResult.Success -> if (data.success) {
                NetworkResult.Success(map(data))
            } else {
                NetworkResult.Error(
                    AppFailure.BusinessRule(
                        data.message?.takeIf { it.isNotBlank() } ?: when (data.errorCode) {
                            "NOT_ALLOWED" -> "You are not an active mobile user in D365FO."
                            "COUNT_NOT_FOUND" -> "This count is not in D365FO any more."
                            "NOT_SUPERVISOR" -> "Only a cycle count supervisor can create counts."
                            else -> data.errorCode ?: "D365FO refused the request."
                        },
                        d365ErrorCode = data.errorCode,
                    ),
                )
            }
        }
}
