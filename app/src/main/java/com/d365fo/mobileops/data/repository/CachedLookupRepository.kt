package com.d365fo.mobileops.data.repository

import com.d365fo.mobileops.data.local.dao.ItemDao
import com.d365fo.mobileops.data.local.dao.ScannedItem
import com.d365fo.mobileops.data.local.dao.WarehouseDao
import com.d365fo.mobileops.data.local.entity.BarcodeEntity
import com.d365fo.mobileops.data.local.entity.ItemEntity
import com.d365fo.mobileops.data.local.entity.TrackingRequirement
import com.d365fo.mobileops.data.local.entity.WarehouseEntity
import com.d365fo.mobileops.domain.repository.BarcodeMatch
import com.d365fo.mobileops.domain.repository.ItemFacts
import com.d365fo.mobileops.domain.repository.LocationFacts
import com.d365fo.mobileops.domain.repository.OnDemandLookupRepository
import com.d365fo.mobileops.domain.repository.OnHandLine
import com.d365fo.mobileops.domain.repository.OrderLookup
import com.d365fo.mobileops.domain.repository.WarehouseOption
import com.d365fo.mobileops.util.AppFailure
import com.d365fo.mobileops.util.NetworkResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local cache in front of the network lookups.
 *
 * Warehouse wifi drops. It drops in the aisle between racks, in the chiller, and behind the
 * bulk storage — the exact places someone is standing when they scan something. Without a
 * cache every one of those moments is a spinner and then an error, which is what makes an app
 * feel like a thin client over someone else's system rather than a tool.
 *
 * **What is cached, and what deliberately is not.**
 *
 * Cached: item facts and the warehouse list. Both are descriptions of how the world is set up
 * — an item's unit and whether it needs a batch, a warehouse's name and site. They change when
 * someone changes master data, which is rarely, and a value a few hours old is still true.
 *
 * Not cached: **on-hand quantities, order lines, and every validation.** A quantity is not a
 * description, it is a measurement, and one taken an hour ago is not a worse answer than none
 * — it is a wrong answer wearing the same clothes as a right one. A worker who reads "12
 * available" and picks twelve has been misled by the app, and they will find out at the rack.
 * The same goes for whether a location is blocked or a serial is already registered: those
 * exist to stop a mistake, and a stale yes is no protection at all.
 *
 * So this class makes the app keep working offline for the things it can answer honestly, and
 * keeps failing for the things it cannot. That is the distinction, and it is the whole design.
 */
@Singleton
class CachedLookupRepository @Inject constructor(
    private val network: ServiceLookupRepository,
    private val itemDao: ItemDao,
    private val warehouseDao: WarehouseDao,
    private val ioDispatcher: CoroutineDispatcher,
    private val listCache: ListCache,
) : OnDemandLookupRepository {

    // -- Cached -------------------------------------------------------------------------

    /**
     * Item facts, read through the cache.
     *
     * The cache is consulted *first* and returned immediately when it hits, rather than being
     * a fallback after a failed call. A repeat scan of the same item — which is most scans, on
     * a pallet of one product — then costs nothing at all instead of ~330 ms, and the
     * behaviour is identical whether or not the wifi is up.
     */
    override suspend fun lookupItem(itemNumber: String): NetworkResult<ItemFacts?> =
        withContext(ioDispatcher) {
            val key = itemNumber.trim()
            if (key.isEmpty()) return@withContext NetworkResult.Success(null)

            itemDao.getItem(key)?.let { return@withContext NetworkResult.Success(it.toFacts()) }

            when (val fresh = network.lookupItem(key)) {
                is NetworkResult.Success -> {
                    fresh.data?.let { runCatching { itemDao.upsertItems(listOf(it.toEntity().copy(category = itemDao.getItem(it.itemNumber)?.category))) } }
                    fresh
                }
                // A miss with no cache entry is a real miss; nothing to fall back to.
                else -> fresh
            }
        }

    /**
     * The warehouse list, refreshed when reachable and served from cache when not.
     *
     * Tried network-first, unlike items: the list is read once per screen rather than once per
     * scan, so the round trip is affordable, and a warehouse added this morning should appear
     * today rather than whenever the cache happens to be cleared.
     */
    @Volatile private var warehousesAt = 0L

    override suspend fun listWarehouses(): NetworkResult<List<WarehouseOption>> =
        withContext(ioDispatcher) {
        // Never answer before the caller's first suspension: a ViewModel asking from init got a
        // cached answer before its own state existed (crash, 10 Nov). Same timing as a network call.
        kotlinx.coroutines.yield()
            // Asked at most every 6 hours; the stored list is used in between (calls, 10 Nov).
            if (System.currentTimeMillis() - warehousesAt < WAREHOUSES_MAX_AGE_MS) {
                val kept = runCatching { warehouseDao.all() }.getOrDefault(emptyList())
                if (kept.isNotEmpty()) return@withContext NetworkResult.Success(kept.map { it.toOption() })
            }
            when (val fresh = network.listWarehouses()) {
                is NetworkResult.Success -> {
                    warehousesAt = System.currentTimeMillis()
                    runCatching {
                        warehouseDao.replaceAll(fresh.data.map { it.toEntity() })
                    }.onFailure { Timber.w(it, "warehouse cache write failed") }
                    fresh
                }

                is NetworkResult.Error -> {
                    val cached = runCatching { warehouseDao.all() }.getOrDefault(emptyList())
                    if (cached.isEmpty()) {
                        fresh
                    } else {
                        Timber.i("serving %d warehouses from cache: %s", cached.size, fresh.failure.message)
                        NetworkResult.Success(cached.map { it.toOption() })
                    }
                }

                NetworkResult.Loading -> fresh
            }
        }

    /**
     * Barcode-to-item resolution, read through the cache - the same shape and reasoning as
     * [lookupItem] just above.
     *
     * This was previously grouped with the live measurements below despite being exactly the
     * kind of thing the class doc says to cache: a barcode-to-item mapping is a description of
     * how the world is set up, not a measurement - it changes about as often as the item master
     * itself, and a few-hours-stale mapping is still the right mapping. Leaving it uncached meant
     * every single scan - including the common case of several units off one pallet - paid a
     * live OData round trip for no correctness reason. `ItemDao.resolveBarcode()` already
     * existed, already indexed, already unit-tested; it just had no caller.
     *
     * A cache hit only counts when both the barcode and the item it joins to are marked active,
     * so a stale/deactivated local row falls through to a real network re-check rather than
     * serving a wrong answer.
     */
    override suspend fun lookupBarcode(barcode: String): NetworkResult<BarcodeMatch?> =
        withContext(ioDispatcher) {
            val key = barcode.trim()
            if (key.isEmpty()) return@withContext NetworkResult.Success(null)

            itemDao.resolveBarcode(key)
                ?.takeIf { it.barcodeActive && it.itemActive }
                ?.let { return@withContext NetworkResult.Success(it.toMatch()) }

            when (val fresh = network.lookupBarcode(key)) {
                is NetworkResult.Success -> {
                    // Caches the barcode->item mapping itself. The join in resolveBarcode also
                    // needs a matching `item` row, which this alone does not guarantee - it
                    // arrives the normal way, via lookupItem(), which every caller of this method
                    // already calls next to fetch tracking/unit facts for the item just matched.
                    // A brand-new barcode's very first scan still costs a round trip; the second
                    // scan of it - the case worth optimizing - is already fully cached by then.
                    fresh.data?.let { runCatching { itemDao.upsertBarcodes(listOf(it.toEntity())) } }
                    fresh
                }
                else -> fresh
            }
        }

    // -- Not cached, on purpose ---------------------------------------------------------
    //
    // Everything below is a live measurement or a live check. See the class comment: serving
    // any of these from a cache would turn "the app could not reach D365FO" into a confident
    // wrong answer, which is the one failure mode a warehouse app must not have.

    override suspend fun lookupOrder(purchId: String): NetworkResult<OrderLookup> =
        network.lookupOrder(purchId)

    override suspend fun lookupLinesForItem(purchId: String, itemNumber: String) =
        network.lookupLinesForItem(purchId, itemNumber)

    override suspend fun lookupLocation(
        warehouseId: String,
        locationId: String,
    ): NetworkResult<LocationFacts?> = network.lookupLocation(warehouseId, locationId)

    override suspend fun lookupOnHand(
        itemNumber: String,
        warehouseId: String?,
    ): NetworkResult<List<OnHandLine>> = network.lookupOnHand(itemNumber, warehouseId)

    // Shared for a short while by Home, PO receiving, Today's POs and the prefetcher - see ListCache.
    override suspend fun lookupOrdersForToday(warehouseId: String?) =
        listCache.get("pos-today:${warehouseId.orEmpty().uppercase()}") { network.lookupOrdersForToday(warehouseId) }

    override suspend fun isSerialRegistered(
        itemNumber: String,
        serial: String,
    ): NetworkResult<Boolean> = network.isSerialRegistered(itemNumber, serial)

    override suspend fun isBatchRegistered(
        itemNumber: String,
        batchId: String,
    ): NetworkResult<Boolean> = network.isBatchRegistered(itemNumber, batchId)

    // -- Mapping ------------------------------------------------------------------------

    private fun ItemEntity.toFacts() = ItemFacts(
        itemNumber = itemId,
        productName = itemName,
        inventoryUnit = inventoryUnit,
        purchaseUnit = purchaseUnit,
        batchRequired = trackingRequirement.requiresBatch,
        serialRequired = trackingRequirement.requiresSerial,
        locationRequired = locationMandatory,
    )

    private fun ItemFacts.toEntity() = ItemEntity(
        itemId = itemNumber,
        itemName = productName,
        searchName = productName,
        inventoryUnit = inventoryUnit,
        purchaseUnit = purchaseUnit,
        salesUnit = null,
        trackingRequirement = when {
            batchRequired && serialRequired -> TrackingRequirement.BATCH_AND_SERIAL
            batchRequired -> TrackingRequirement.BATCH
            serialRequired -> TrackingRequirement.SERIAL
            else -> TrackingRequirement.NONE
        },
        locationMandatory = locationRequired,
        active = true,
        modifiedAt = System.currentTimeMillis(),
    )

    private fun ScannedItem.toMatch() = BarcodeMatch(
        barcode = barcode,
        itemNumber = itemId,
        quantityPerScan = quantityPerScan.toBigDecimal(),
        unitSymbol = scanUnit,
        variant = variant,
    )

    private fun BarcodeMatch.toEntity() = BarcodeEntity(
        barcode = barcode,
        itemId = itemNumber,
        quantityPerScan = quantityPerScan.toPlainString(),
        unitId = unitSymbol,
        active = true,
        modifiedAt = System.currentTimeMillis(),
    )

    private fun WarehouseOption.toEntity() = WarehouseEntity(
        warehouseId = warehouseId,
        warehouseName = warehouseName,
        siteId = siteId,
        // Not carried on the option today. Stored as false rather than guessed true: this app
        // is non-WMS, and the flag is only ever used to *withhold* a warehouse.
        wmsEnabled = false,
        cachedAt = System.currentTimeMillis(),
    )

    private fun WarehouseEntity.toOption() = WarehouseOption(
        warehouseId = warehouseId,
        warehouseName = warehouseName,
        siteId = siteId,
    )

}

private const val WAREHOUSES_MAX_AGE_MS = 6 * 60 * 60 * 1000L
