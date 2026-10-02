package com.d365fo.mobileops.data.repository

import com.d365fo.mobileops.data.remote.api.MobileOpsServiceApi
import com.d365fo.mobileops.data.remote.dto.ItemUnitsKeyDto
import com.d365fo.mobileops.data.remote.dto.ItemUnitsRequestBody
import com.d365fo.mobileops.data.remote.dto.ItemUnitsServiceRequest
import com.d365fo.mobileops.data.remote.dto.toItemUnits
import com.d365fo.mobileops.data.remote.error.D365ErrorMapper
import com.d365fo.mobileops.data.security.CompanyProvider
import com.d365fo.mobileops.domain.model.ItemUnit
import com.d365fo.mobileops.domain.model.ProductVariant
import com.d365fo.mobileops.util.NetworkResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Item units to the inventory unit, for counting (receiving and transfers get theirs on the
 * lines). Asked for only when a scan needs a conversion, and then for every item of the journal
 * in one call, so a journal costs at most one request; results are kept for the session.
 */
@Singleton
class ItemUnitRepository @Inject constructor(
    private val service: MobileOpsServiceApi,
    private val errorMapper: D365ErrorMapper,
    private val companyProvider: CompanyProvider,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val cache = ConcurrentHashMap<String, List<ItemUnit>>()
    private val mutex = Mutex()

    /** Units of [itemId]/[variant]; on a miss, fetches every one of [journalItems] still unknown. */
    suspend fun unitsFor(
        itemId: String,
        variant: ProductVariant,
        journalItems: Collection<Pair<String, ProductVariant>>,
    ): List<ItemUnit> {
        cache[key(itemId, variant)]?.let { return it }
        return mutex.withLock {
            cache[key(itemId, variant)]?.let { return@withLock it }
            val wanted = (journalItems + (itemId to variant))
                .distinctBy { key(it.first, it.second) }
                .filter { !cache.containsKey(key(it.first, it.second)) }
                .take(MAX_ITEMS)
            fetch(wanted)
            // Unknown or failed: remember empty so the same scan does not ask again.
            cache.getOrPut(key(itemId, variant)) { emptyList() }
        }
    }

    private suspend fun fetch(items: List<Pair<String, ProductVariant>>) = withContext(ioDispatcher) {
        if (items.isEmpty()) return@withContext
        val result = errorMapper.safeCall {
            service.getItemUnits(
                ItemUnitsServiceRequest(
                    ItemUnitsRequestBody(
                        dataAreaId = companyProvider()?.lowercase().orEmpty(),
                        items = items.map { (id, v) ->
                            ItemUnitsKeyDto(id, v.configId.orEmpty(), v.sizeId.orEmpty(), v.colorId.orEmpty(), v.styleId.orEmpty())
                        },
                    ),
                ),
            )
        }
        if (result is NetworkResult.Success && result.data.success) {
            result.data.items.forEach { r ->
                cache[key(r.itemId, ProductVariant.of(r.configId, r.sizeId, r.colorId, r.styleId))] = r.units.toItemUnits()
            }
        }
    }

    private fun key(itemId: String, v: ProductVariant) =
        listOf(itemId, v.configId, v.sizeId, v.colorId, v.styleId).joinToString("|") { it.orEmpty().uppercase() }

    private companion object {
        /** Matches HSMobileOpsUnitService.maxItems. */
        const val MAX_ITEMS = 500
    }
}
