package com.d365fo.mobileops.data.repository

import android.content.Context
import com.d365fo.mobileops.data.local.dao.ItemDao
import com.d365fo.mobileops.data.remote.api.MobileOpsServiceApi
import com.d365fo.mobileops.data.remote.dto.SkuPageRequestBody
import com.d365fo.mobileops.data.remote.dto.SkuPageServiceRequest
import com.d365fo.mobileops.data.remote.error.D365ErrorMapper
import com.d365fo.mobileops.data.security.CompanyProvider
import com.d365fo.mobileops.util.NetworkResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The offline item, barcode and variant cache - what a scan resolves against with no connection
 * (`ItemDao.resolveBarcode`, the variant choice in order creation).
 *
 * Read from `HSMobileOpsSkuSvc/getSkuPage`, a page of [PAGE_SIZE] rows at a time, in key order
 * from indexed tables, only the columns the handheld uses. It replaced a DMF export of the
 * standard released-product entity, which took 6-7 minutes in D365FO for 18,495 items and
 * redid all of it every day.
 *
 * - **Delta, daily.** Only items and barcodes D365FO changed since the last sync (by its own
 *   clock, with an overlap), and variants added since (by RecId - the variant table has no
 *   modified date).
 * - **Full, weekly** (and the first time): everything, then whatever the refresh did not see is
 *   removed - deleted items, barcodes and variants, which a delta cannot see.
 * - **Resumable.** The position (feed and last key) is kept after every page, so a sync stopped
 *   by Android's time limit, a lost connection or the device being unplugged carries on where
 *   it was - see [runStep].
 * - **Gentle on both systems.** One page at a time with a pause between, each page written to
 *   the database in one short transaction, never more than a page in memory.
 * - **Snapshot first** (14 Nov). A full sync downloads D365FO's item snapshot - each feed as one
 *   ZIP file, built nightly - checks every file's size and SHA-256, loads them only once all
 *   arrived, then runs the feeds as a delta from the snapshot's build time. No snapshot, or a
 *   failed download: the pages, as before. Load test: 86 minutes of pages for 1M items.
 */
@Singleton
class SkuSyncRepository @Inject constructor(
    private val service: MobileOpsServiceApi,
    private val errorMapper: D365ErrorMapper,
    private val companyProvider: CompanyProvider,
    private val itemDao: ItemDao,
    private val ioDispatcher: CoroutineDispatcher,
    @ApplicationContext private val context: Context,
    private val sessions: WorkerSessionRepository,
) {
    private val prefs = context.getSharedPreferences("sku_sync", Context.MODE_PRIVATE)

    /**
     * The warehouse the items are for - the signed-in worker's selected one, kept so a sync in the
     * background with nobody signed in asks for the same items. A warehouse that syncs by
     * assortment in D365FO (Mobile warehouse control) gets only its store's items.
     */
    private fun warehouse(): String {
        val selected = sessions.session.value?.selected?.warehouseId?.takeIf { it.isNotBlank() }
        if (selected != null) {
            if (prefs.getString(KEY_WAREHOUSE, null) != selected) prefs.edit().putString(KEY_WAREHOUSE, selected).apply()
            return selected
        }
        return prefs.getString(KEY_WAREHOUSE, "").orEmpty()
    }

    /**
     * The warehouse a worker chose. Answers true when it differs from the one the item cache was
     * last asked for - a sync is then worth running now, since the store's items may differ.
     */
    fun warehouseChosen(warehouseId: String): Boolean {
        if (warehouseId.isBlank()) return false
        val before = prefs.getString(KEY_WAREHOUSE, null)
        if (before == warehouseId) return false
        prefs.edit().putString(KEY_WAREHOUSE, warehouseId).apply()
        // Never synced: the catch-up sync at start takes care of it.
        return before != null && prefs.getLong(KEY_LAST_SUCCESS, 0L) > 0L
    }

    /**
     * Whether an answer is for other items than this cycle expects (the worker changed warehouse,
     * or D365FO switched the warehouse to or from its store's assortment). The first answer of a
     * full sync sets what it expects.
     */
    private fun scopeChanged(serverScope: String): Boolean {
        if (!prefs.contains(KEY_CYCLE_SCOPE)) {
            prefs.edit().putString(KEY_CYCLE_SCOPE, serverScope).apply()
            return false
        }
        if (prefs.getString(KEY_CYCLE_SCOPE, "") == serverScope) return false
        Timber.w("SKU sync: the items are now for '%s' (was '%s') - starting a full sync", serverScope, prefs.getString(KEY_CYCLE_SCOPE, ""))
        // Rows already here stay until the new full sync has every row of the new scope.
        startCycle(forceFull = true)
        return true
    }

    /** The app's memory in use now (Java heap + native), in bytes - sampled after each page. */
    private fun memoryInUse(): Long {
        val rt = Runtime.getRuntime()
        return (rt.totalMemory() - rt.freeMemory()) + android.os.Debug.getNativeHeapAllocatedSize()
    }

    private fun notePeakMemory() {
        val now = memoryInUse()
        if (now > prefs.getLong(KEY_PEAK_MEMORY, 0L)) prefs.edit().putLong(KEY_PEAK_MEMORY, now).apply()
    }

    /** The last finished sync, kept for Settings and support - null before the first one. */
    fun lastSummary(): SkuSyncSummary? {
        val at = prefs.getLong(KEY_LAST_SUCCESS, 0L).takeIf { it > 0 } ?: return null
        if (!prefs.contains(KEY_SUM_ITEMS)) return null
        return SkuSyncSummary(
            itemCount = prefs.getInt(KEY_SUM_ITEMS, 0),
            barcodeCount = prefs.getInt(KEY_SUM_BARCODES, 0),
            variantCount = prefs.getInt(KEY_SUM_VARIANTS, 0),
            full = prefs.getBoolean(KEY_SUM_FULL, true),
            seconds = prefs.getInt(KEY_SUM_SECONDS, 0),
            activeSeconds = prefs.getInt(KEY_SUM_ACTIVE, 0),
            peakMemoryBytes = prefs.getLong(KEY_SUM_PEAK, 0L),
            storedBytes = prefs.getLong(KEY_SUM_STORED, 0L),
            finishedAt = at,
        )
    }

    /** One step at a time - the periodic run and a continuation must not both page. */
    private val lock = Mutex()

    private fun company(): String =
        companyProvider()?.takeIf { it.isNotBlank() }?.lowercase().orEmpty()

    /**
     * Pages through the feeds until done or [budgetMs] is spent. [SkuSyncStep.Pending] means
     * stopped part way - the position is kept and the worker comes back for the rest.
     */
    suspend fun runStep(budgetMs: Long, manual: ManualSync? = null): SkuSyncStep = withContext(ioDispatcher) {
        lock.withLock {
            val stepStarted = System.currentTimeMillis()
            val deadline = stepStarted + budgetMs
            fun addActive() = prefs.edit().putLong(KEY_ACTIVE_MS, prefs.getLong(KEY_ACTIVE_MS, 0L) + System.currentTimeMillis() - stepStarted).apply()
            // Settings > Sync masters now (30 Oct): a full sync starts over even part way through
            // another; changes only carry on with one already running.
            if (manual == ManualSync.FULL) prefs.edit().putLong(KEY_CYCLE_STARTED, 0L).apply()
            if (prefs.getLong(KEY_CYCLE_STARTED, 0L) == 0L) {
                // Not again within the day - unless a feed gained a column (SCHEMA), which needs a
                // full sync now: the guard used to come first and kept the variant names away.
                // Asked for by the worker (manual), it runs whenever.
                if (manual == null && prefs.getInt(KEY_SCHEMA, 0) >= SCHEMA &&
                    System.currentTimeMillis() - prefs.getLong(KEY_LAST_SUCCESS, 0L) < MIN_CYCLE_INTERVAL_MS
                ) {
                    return@withLock SkuSyncStep.Done(null)
                }
                startCycle(forceFull = manual == ManualSync.FULL)
            }
            val full = prefs.getString(KEY_MODE, MODE_FULL) == MODE_FULL
            if (full && prefs.getString(KEY_SNAP_STATE, "") != SNAP_SKIPPED && prefs.getString(KEY_SNAP_STATE, "") != SNAP_LOADED) {
                when (snapshotStep(deadline)) {
                    SnapshotResult.Pending -> { addActive(); return@withLock SkuSyncStep.Pending }
                    // A new full sync for the new items was started: the next step runs it.
                    SnapshotResult.ScopeChanged -> { addActive(); return@withLock SkuSyncStep.Pending }
                    SnapshotResult.Skipped -> prefs.edit().putString(KEY_SNAP_STATE, SNAP_SKIPPED).apply()
                    SnapshotResult.Loaded -> {
                        // The rest is a delta from when the snapshot was built.
                        val built = prefs.getString(KEY_SNAP_BUILT, "").orEmpty()
                        prefs.edit().putString(KEY_SNAP_STATE, SNAP_LOADED)
                            .putString(KEY_CHANGED_SINCE, withOverlap(built))
                            .putInt(KEY_FEED, 0)
                            .putString(KEY_AFTER, initialKey(FEEDS[0], false)).apply()
                    }
                }
            }
            // A full sync whose rows came from the snapshot pages only the changes since its build.
            val feedsFull = full && prefs.getString(KEY_SNAP_STATE, "") != SNAP_LOADED

            while (true) {
                val feedIndex = prefs.getInt(KEY_FEED, 0)
                if (feedIndex >= FEEDS.size) break
                val feed = FEEDS[feedIndex]
                when (val r = pageThrough(feed, feedsFull, deadline)) {
                    FeedResult.Finished -> {
                        if (feed == FEED_VARIANTS) prefs.edit().putString(KEY_VARIANT_MAX, prefs.getString(KEY_AFTER, "0")).apply()
                        val next = feedIndex + 1
                        prefs.edit().putInt(KEY_FEED, next)
                            .putString(KEY_AFTER, FEEDS.getOrNull(next)?.let { initialKey(it, feedsFull) }.orEmpty()).apply()
                    }
                    FeedResult.OutOfTime, FeedResult.ScopeChanged -> { addActive(); return@withLock SkuSyncStep.Pending }
                    // The position is kept either way; a failure waits out WorkManager's backoff
                    // rather than knocking on an AOS that is down every couple of minutes.
                    is FeedResult.Failed -> { addActive(); return@withLock SkuSyncStep.Failed(r.message) }
                }
            }

            val cycleStarted = prefs.getLong(KEY_CYCLE_STARTED, 0L)
            if (full) {
                // Everything D365FO has was just written with a newer stamp; older rows are gone there.
                val items = itemDao.deleteItemsNotSeenSince(cycleStarted)
                val barcodes = itemDao.deleteBarcodesNotSeenSince(cycleStarted)
                val variants = itemDao.deleteVariantsNotSeenSince(cycleStarted)
                // Only once units came with this sync: a D365FO without the UNITS feed sends none.
                if (prefs.getInt(countKey(FEED_UNITS), 0) > 0) itemDao.deleteUnitsNotSeenSince(cycleStarted)
                Timber.i("SKU sync: removed %d items, %d barcodes, %d variants no longer in D365FO or not in this warehouse's items", items, barcodes, variants)
            }
            // Items by category reads these counts instead of recounting the list on every level.
            itemDao.rebuildCategoryCounts()
            notePeakMemory()
            val summary = SkuSyncSummary(
                itemCount = prefs.getInt(countKey(FEED_ITEMS), 0),
                barcodeCount = prefs.getInt(countKey(FEED_BARCODES), 0),
                variantCount = prefs.getInt(countKey(FEED_VARIANTS), 0),
                full = full,
                seconds = ((System.currentTimeMillis() - cycleStarted) / 1000L).toInt(),
                // Time actually spent syncing - a big sync runs in 7-minute steps with gaps.
                activeSeconds = ((prefs.getLong(KEY_ACTIVE_MS, 0L) + System.currentTimeMillis() - stepStarted) / 1000L).toInt(),
                peakMemoryBytes = prefs.getLong(KEY_PEAK_MEMORY, 0L),
                storedBytes = context.getDatabasePath(com.d365fo.mobileops.util.Constants.DATABASE_NAME).length(),
                finishedAt = System.currentTimeMillis(),
            )
            // At warning level so it is in the release log (Settings > Logs, feedback) too.
            Timber.w(
                "SKU sync done (%s): %d items, %d barcodes, %d variants in %d s (%d s active), peak memory %d MB, database %d MB",
                if (full) "full" else "delta", summary.itemCount, summary.barcodeCount, summary.variantCount,
                summary.seconds, summary.activeSeconds, summary.peakMemoryBytes / MB, summary.storedBytes / MB,
            )
            val watermark = prefs.getString(KEY_CYCLE_SERVER_UTC, null)
            val variantMax = prefs.getString(KEY_VARIANT_MAX, "0")
            val lastFull = if (full) System.currentTimeMillis() else prefs.getLong(KEY_LAST_FULL, 0L)
            // The cache now holds the items of the scope this cycle was answered for.
            val scope = prefs.getString(KEY_CYCLE_SCOPE, null) ?: prefs.getString(KEY_SCOPE, "").orEmpty()
            val warehouse = prefs.getString(KEY_WAREHOUSE, null)
            prefs.edit().clear()
                .putString(KEY_SCOPE, scope)
                .putString(KEY_WAREHOUSE, warehouse)
                .putLong(KEY_LAST_SUCCESS, System.currentTimeMillis())
                .putLong(KEY_LAST_FULL, lastFull)
                .putString(KEY_WATERMARK, watermark)
                .putString(KEY_VARIANT_MAX, variantMax)
                .putInt(KEY_SCHEMA, if (full) SCHEMA else prefs.getInt(KEY_SCHEMA, 0))
                .putInt(KEY_SUM_ITEMS, summary.itemCount)
                .putInt(KEY_SUM_BARCODES, summary.barcodeCount)
                .putInt(KEY_SUM_VARIANTS, summary.variantCount)
                .putBoolean(KEY_SUM_FULL, full)
                .putInt(KEY_SUM_SECONDS, summary.seconds)
                .putInt(KEY_SUM_ACTIVE, summary.activeSeconds)
                .putLong(KEY_SUM_PEAK, summary.peakMemoryBytes)
                .putLong(KEY_SUM_STORED, summary.storedBytes)
                .apply()
            SkuSyncStep.Done(summary)
        }
    }

    /** When the last full download of the item list finished; null for never. */
    fun lastFullAt(): Long? = prefs.getLong(KEY_LAST_FULL, 0L).takeIf { it > 0L }

    /** Never synced, or stopped part way: worth a run soon after start rather than the daily one. */
    fun needsCatchUp(): Boolean =
        prefs.getLong(KEY_LAST_SUCCESS, 0L) == 0L || prefs.getLong(KEY_CYCLE_STARTED, 0L) != 0L ||
            prefs.getInt(KEY_SCHEMA, 0) < SCHEMA

    private fun startCycle(forceFull: Boolean = false) {
        val now = System.currentTimeMillis()
        val watermark = prefs.getString(KEY_WATERMARK, null)
        // A new column on a feed (variant names SCHEMA 2, item category 3) needs every row again, not a delta.
        // A store's assortment changes reach it by a full sync - of the store's items only, so
        // a small download - daily; every item of the company, weekly.
        val scope = prefs.getString(KEY_SCOPE, "").orEmpty()
        val fullInterval = if (scope.isBlank()) FULL_REFRESH_INTERVAL_MS else SCOPED_FULL_REFRESH_INTERVAL_MS
        val full = forceFull || watermark.isNullOrBlank() || now - prefs.getLong(KEY_LAST_FULL, 0L) >= fullInterval ||
            prefs.getInt(KEY_SCHEMA, 0) < SCHEMA
        prefs.edit()
            // A delta must be for the items the cache holds; a full sync takes the first answer's.
            .apply { if (full) remove(KEY_CYCLE_SCOPE) else putString(KEY_CYCLE_SCOPE, scope) }
            .putLong(KEY_CYCLE_STARTED, now)
            .putString(KEY_MODE, if (full) MODE_FULL else MODE_DELTA)
            .putString(KEY_CHANGED_SINCE, if (full) "" else withOverlap(watermark.orEmpty()))
            .putInt(KEY_FEED, 0)
            .putString(KEY_AFTER, initialKey(FEEDS[0], full))
            .remove(KEY_CYCLE_SERVER_UTC)
            .remove(KEY_SNAP_STATE).remove(KEY_SNAP_MANIFEST).remove(KEY_SNAP_BUILT)
            .remove(KEY_SNAP_FILE).remove(KEY_SNAP_LINE)
            .putLong(KEY_ACTIVE_MS, 0L)
            .putLong(KEY_PEAK_MEMORY, 0L)
            .apply()
        Timber.i("SKU sync: %s cycle started", if (full) "full" else "delta")
    }

    private fun initialKey(feed: String, full: Boolean): String = when (feed) {
        FEED_ITEMS -> ""
        // Variants have no modified date: a delta is everything after the highest RecId seen.
        FEED_VARIANTS -> if (full) "0" else prefs.getString(KEY_VARIANT_MAX, "0") ?: "0"
        else -> "0"
    }

    private sealed interface FeedResult {
        data object Finished : FeedResult
        data object OutOfTime : FeedResult
        /** The answer was for other items: a new full sync was started. */
        data object ScopeChanged : FeedResult
        data class Failed(val message: String, val retryable: Boolean) : FeedResult
    }

    private suspend fun pageThrough(feed: String, full: Boolean, deadline: Long): FeedResult {
        // A full ITEMS download already carries each item's category.
        if (feed == FEED_CATEGORIES && full) return FeedResult.Finished
        // Variants ignore the date - see initialKey.
        val since = if (full || feed == FEED_VARIANTS) "" else prefs.getString(KEY_CHANGED_SINCE, "").orEmpty()
        while (true) {
            if (System.currentTimeMillis() >= deadline) return FeedResult.OutOfTime
            val after = prefs.getString(KEY_AFTER, initialKey(feed, full)).orEmpty()
            val started = System.currentTimeMillis()
            val result = errorMapper.safeCall {
                service.getSkuPage(
                    SkuPageServiceRequest(
                        SkuPageRequestBody(dataAreaId = company(), feed = feed, afterKey = after, changedSinceUtc = since, pageSize = PAGE_SIZE, warehouse = warehouse()),
                    ),
                )
            }
            val page = when (result) {
                is NetworkResult.Success -> result.data
                is NetworkResult.Error -> return FeedResult.Failed(result.failure.message, result.failure.retryable)
                NetworkResult.Loading -> return FeedResult.Failed("unexpected Loading", true)
            }
            // A D365FO package older than the CATEGORIES feed answers it as an unknown feed: the delta
            // then just leaves categories to the weekly full sync, as before.
            if (!page.success && (feed == FEED_CATEGORIES || feed == FEED_UNITS) && page.errorCode == "INVALID_REQUEST") return FeedResult.Finished
            if (!page.success) {
                return FeedResult.Failed(page.message.ifBlank { "D365FO refused the $feed page (${page.errorCode})" }, retryable = false)
            }
            if (scopeChanged(page.scope)) return FeedResult.ScopeChanged
            if (prefs.getString(KEY_CYCLE_SERVER_UTC, null) == null && page.serverUtc.isNotBlank()) {
                prefs.edit().putString(KEY_CYCLE_SERVER_UTC, page.serverUtc).apply()
            }

            val rows = Json.parseToJsonElement(page.rowsJson).jsonArray.map { it.jsonArray }
            val now = System.currentTimeMillis()
            when (feed) {
                FEED_ITEMS -> itemDao.upsertItems(rows.mapNotNull { SkuRows.item(it, now) })
                FEED_BARCODES -> itemDao.upsertBarcodes(rows.mapNotNull { SkuRows.barcode(it, now) })
                FEED_VARIANTS -> itemDao.upsertVariants(rows.mapNotNull { SkuRows.variant(it, now) })
                FEED_CATEGORIES -> rows.mapNotNull { SkuRows.category(it) }.forEach { (itemId, path) -> itemDao.setCategory(itemId, path) }
                FEED_UNITS -> itemDao.applyUnitRows(rows.mapNotNull { SkuRows.units(it, now) })
            }
            prefs.edit()
                .putString(KEY_AFTER, page.nextKey.ifBlank { after })
                .putInt(countKey(feed), prefs.getInt(countKey(feed), 0) + rows.size)
                .apply()
            Timber.d("SKU sync: %s page after '%s' - %d rows in %d ms", feed, after, rows.size, System.currentTimeMillis() - started)
            notePeakMemory()

            if (!page.hasMore) return FeedResult.Finished
            delay(PAGE_PAUSE_MS)
        }
    }

    private fun countKey(feed: String) = "count_$feed"

    private enum class SnapshotResult { Loaded, Skipped, Pending, ScopeChanged }

    /** Set by [fetchManifest] when the answer was for other items than this cycle's. */
    private var manifestScopeChanged = false

    private val snapshotDir: java.io.File get() = java.io.File(context.cacheDir, "sku_snapshot")

    /**
     * The snapshot part of a full sync, resumable: manifest, then every file downloaded and
     * checked, then loaded a line (one page of rows) at a time. Skipped - the pages instead -
     * when D365FO has no snapshot (or is older than it) or a file cannot be downloaded intact.
     */
    private suspend fun snapshotStep(deadline: Long): SnapshotResult {
        var state = prefs.getString(KEY_SNAP_STATE, "").orEmpty()
        if (state.isEmpty()) {
            val manifest = fetchManifest() ?: return if (manifestScopeChanged) SnapshotResult.ScopeChanged else SnapshotResult.Skipped
            snapshotDir.deleteRecursively()
            snapshotDir.mkdirs()
            prefs.edit().putString(KEY_SNAP_MANIFEST, Json.encodeToString(com.d365fo.mobileops.data.remote.dto.SkuSnapshotManifest.serializer(), manifest))
                .putString(KEY_SNAP_BUILT, manifest.builtAtUtc)
                .putString(KEY_SNAP_STATE, SNAP_DOWNLOADING).putInt(KEY_SNAP_FILE, 0).apply()
            state = SNAP_DOWNLOADING
        }
        var manifest = savedManifest() ?: return SnapshotResult.Skipped
        if (state == SNAP_DOWNLOADING) {
            while (true) {
                val index = prefs.getInt(KEY_SNAP_FILE, 0)
                val file = manifest.files.getOrNull(index) ?: break
                if (System.currentTimeMillis() >= deadline) return SnapshotResult.Pending
                val started = System.currentTimeMillis()
                var ok = download(file, index)
                if (!ok) {
                    // A newer snapshot may have replaced this one: ask which is current, once.
                    val fresh = fetchManifest()
                    if (manifestScopeChanged) {
                        snapshotDir.deleteRecursively()
                        return SnapshotResult.ScopeChanged
                    }
                    if (fresh == null || fresh.builtAtUtc != manifest.builtAtUtc) {
                        Timber.w("SKU snapshot: %s download failed and the snapshot changed - paging instead", file.area)
                        snapshotDir.deleteRecursively()
                        return SnapshotResult.Skipped
                    }
                    manifest = fresh
                    prefs.edit().putString(KEY_SNAP_MANIFEST, Json.encodeToString(com.d365fo.mobileops.data.remote.dto.SkuSnapshotManifest.serializer(), fresh)).apply()
                    ok = download(fresh.files.getOrNull(index) ?: file, index)
                }
                if (!ok) {
                    Timber.w("SKU snapshot: %s could not be downloaded intact - paging instead", file.area)
                    snapshotDir.deleteRecursively()
                    return SnapshotResult.Skipped
                }
                Timber.w("SKU snapshot: %s part %d downloaded, %d KB in %d ms", file.area, file.part, file.bytes / 1024, System.currentTimeMillis() - started)
                prefs.edit().putInt(KEY_SNAP_FILE, index + 1).apply()
            }
            prefs.edit().putString(KEY_SNAP_STATE, SNAP_IMPORTING).putInt(KEY_SNAP_FILE, 0).putInt(KEY_SNAP_LINE, 0).apply()
        }
        // Every file is here and checked: load them.
        while (true) {
            val index = prefs.getInt(KEY_SNAP_FILE, 0)
            val file = manifest.files.getOrNull(index) ?: break
            val feed = file.area
            val skip = prefs.getInt(KEY_SNAP_LINE, 0)
            var line = 0
            val started = System.currentTimeMillis()
            // A ZIP with one .jsonl entry (D365FO attaches only its listed file types).
            java.util.zip.ZipInputStream(java.io.BufferedInputStream(java.io.FileInputStream(fileFor(index)), 64 * 1024)).also { it.nextEntry }
                .bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    val text = reader.readLine() ?: break
                    line++
                    if (line <= skip || text.isBlank()) continue
                    if (System.currentTimeMillis() >= deadline) return SnapshotResult.Pending
                    val rows = Json.parseToJsonElement(text).jsonArray.map { it.jsonArray }
                    val now = System.currentTimeMillis()
                    when (feed) {
                        FEED_ITEMS -> itemDao.upsertItems(rows.mapNotNull { SkuRows.item(it, now) })
                        FEED_BARCODES -> itemDao.upsertBarcodes(rows.mapNotNull { SkuRows.barcode(it, now) })
                        FEED_VARIANTS -> itemDao.upsertVariants(rows.mapNotNull { SkuRows.variant(it, now) })
                        FEED_UNITS -> itemDao.applyUnitRows(rows.mapNotNull { SkuRows.units(it, now) })
                    }
                    prefs.edit().putInt(KEY_SNAP_LINE, line)
                        .putInt(countKey(feed), prefs.getInt(countKey(feed), 0) + rows.size).apply()
                    notePeakMemory()
                }
            }
            Timber.w("SKU snapshot: %s loaded in %d ms", feed, System.currentTimeMillis() - started)
            prefs.edit().putInt(KEY_SNAP_FILE, index + 1).putInt(KEY_SNAP_LINE, 0).apply()
        }
        snapshotDir.deleteRecursively()
        return SnapshotResult.Loaded
    }

    private fun savedManifest(): com.d365fo.mobileops.data.remote.dto.SkuSnapshotManifest? =
        prefs.getString(KEY_SNAP_MANIFEST, null)?.let {
            runCatching { Json.decodeFromString(com.d365fo.mobileops.data.remote.dto.SkuSnapshotManifest.serializer(), it) }.getOrNull()
        }

    /** The current snapshot, or null - none ready, a D365FO without it, or not reachable. */
    private suspend fun fetchManifest(): com.d365fo.mobileops.data.remote.dto.SkuSnapshotManifest? {
        manifestScopeChanged = false
        val result = errorMapper.safeCall {
            service.getSkuSnapshot(SkuPageServiceRequest(SkuPageRequestBody(dataAreaId = company(), feed = "", warehouse = warehouse())))
        }
        val answer = (result as? NetworkResult.Success)?.data
        // NO_SNAPSHOT says for which items too: a store whose snapshot is not built yet pages
        // through its own items now.
        if (answer != null && scopeChanged(answer.scope)) {
            manifestScopeChanged = true
            return null
        }
        if (answer == null || !answer.success) {
            Timber.w("SKU snapshot: none (%s) - paging", answer?.errorCode?.ifBlank { null } ?: (result as? NetworkResult.Error)?.failure?.message ?: "no answer")
            return null
        }
        val manifest = runCatching {
            Json { ignoreUnknownKeys = true }.decodeFromString(com.d365fo.mobileops.data.remote.dto.SkuSnapshotManifest.serializer(), answer.rowsJson)
        }.getOrNull()
        val known = setOf(FEED_ITEMS, FEED_BARCODES, FEED_VARIANTS, FEED_UNITS)
        return manifest?.takeIf { m -> m.builtAtUtc.isNotBlank() && m.files.isNotEmpty() && m.files.all { it.area in known && it.id > 0L } }
    }

    /** The manifest's [index]th file - an area comes in parts, in order. */
    private fun fileFor(index: Int) = java.io.File(snapshotDir, "part-$index.zip")

    /** Reads one part from D365FO and checks its size and SHA-256; false (file removed) when not intact. */
    private suspend fun download(file: com.d365fo.mobileops.data.remote.dto.SkuSnapshotFile, index: Int): Boolean {
        val target = fileFor(index)
        val result = errorMapper.safeCall {
            service.getSkuSnapshotPart(SkuPageServiceRequest(SkuPageRequestBody(dataAreaId = company(), feed = "", afterKey = file.id.toString())))
        }
        val answer = (result as? NetworkResult.Success)?.data?.takeIf { it.success }
        if (answer == null) {
            Timber.w("SKU snapshot: %s part %d not read - %s", file.area, file.part,
                (result as? NetworkResult.Success)?.data?.errorCode ?: (result as? NetworkResult.Error)?.failure?.message)
            return false
        }
        return runCatching {
            val bytes = java.util.Base64.getDecoder().decode(answer.rowsJson)
            val sha = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            if (bytes.size.toLong() != file.bytes || !sha.equals(file.sha256, ignoreCase = true)) {
                error("size ${bytes.size} of ${file.bytes}, checksum ${if (sha.equals(file.sha256, true)) "ok" else "wrong"}")
            }
            target.writeBytes(bytes)
            true
        }.getOrElse {
            Timber.w("SKU snapshot: %s part %d not intact: %s", file.area, file.part, it.message)
            target.delete()
            false
        }
    }

    private fun withOverlap(serverUtc: String): String = SkuRows.withOverlap(serverUtc, OVERLAP_MINUTES)

    private companion object {
        const val FEED_ITEMS = "ITEMS"
        const val FEED_BARCODES = "BARCODES"
        const val FEED_VARIANTS = "VARIANTS"
        // Category changes do not touch the item, so a delta of ITEMS misses them (13 Nov).
        const val FEED_CATEGORIES = "CATEGORIES"
        // Units per item, and the item's own units (InventTableModule) - 14 Nov.
        const val FEED_UNITS = "UNITS"
        val FEEDS = listOf(FEED_ITEMS, FEED_BARCODES, FEED_VARIANTS, FEED_CATEGORIES, FEED_UNITS)

        // The server's maximum. 50k items + 65k barcodes is ~24 pages instead of ~60 - fewer round
        // trips; a page in memory is a few MB more, which a handheld does not notice.
        const val PAGE_SIZE = 5000
        const val PAGE_PAUSE_MS = 250L
        const val OVERLAP_MINUTES = 10L
        const val MIN_CYCLE_INTERVAL_MS = 20 * 60 * 60 * 1000L
        const val FULL_REFRESH_INTERVAL_MS = 7 * 24 * 60 * 60 * 1000L
        /** A store that syncs by assortment: its (small) item list again each day, for assortment changes. */
        const val SCOPED_FULL_REFRESH_INTERVAL_MS = 20 * 60 * 60 * 1000L
        /** The scope of the cache's items: blank every item, a warehouse its store's assortment. */
        const val KEY_SCOPE = "scope"
        /** The scope this cycle's answers must be for. */
        const val KEY_CYCLE_SCOPE = "cycle_scope"
        /** The warehouse the items are asked for - see warehouse(). */
        const val KEY_WAREHOUSE = "warehouse"

        const val MODE_FULL = "FULL"
        const val MODE_DELTA = "DELTA"
        const val KEY_CYCLE_STARTED = "cycle_started"
        const val KEY_CYCLE_SERVER_UTC = "cycle_server_utc"
        const val KEY_MODE = "mode"
        const val KEY_CHANGED_SINCE = "changed_since"
        const val KEY_FEED = "feed"
        const val KEY_AFTER = "after"
        const val KEY_LAST_SUCCESS = "last_success"
        const val KEY_LAST_FULL = "last_full"
        const val KEY_WATERMARK = "watermark"
        const val KEY_VARIANT_MAX = "variant_max"
        const val KEY_SCHEMA = "schema"
        const val KEY_ACTIVE_MS = "active_ms"
        const val KEY_PEAK_MEMORY = "peak_memory"
        const val KEY_SUM_ITEMS = "sum_items"
        const val KEY_SUM_BARCODES = "sum_barcodes"
        const val KEY_SUM_VARIANTS = "sum_variants"
        const val KEY_SUM_FULL = "sum_full"
        const val KEY_SUM_SECONDS = "sum_seconds"
        const val KEY_SUM_ACTIVE = "sum_active"
        const val KEY_SUM_PEAK = "sum_peak"
        const val KEY_SUM_STORED = "sum_stored"
        const val KEY_SNAP_STATE = "snap_state"
        const val KEY_SNAP_MANIFEST = "snap_manifest"
        const val KEY_SNAP_BUILT = "snap_built"
        const val KEY_SNAP_FILE = "snap_file"
        const val KEY_SNAP_LINE = "snap_line"
        const val SNAP_DOWNLOADING = "DOWNLOADING"
        const val SNAP_IMPORTING = "IMPORTING"
        const val SNAP_LOADED = "LOADED"
        const val SNAP_SKIPPED = "SKIPPED"
        const val MB = 1024L * 1024L
        /** Bumped when a feed gains a column; 2 = variant names, 3 = categories, 4 = units. */
        const val SCHEMA = 4
    }
}

data class SkuSyncSummary(
    val itemCount: Int,
    val barcodeCount: Int,
    val variantCount: Int = 0,
    val full: Boolean = true,
    val seconds: Int = 0,
    /** Syncing time without the gaps between steps. */
    val activeSeconds: Int = 0,
    /** Highest app memory in use (Java heap + native) seen during the sync. */
    val peakMemoryBytes: Long = 0L,
    /** The device database's size on disk after the sync. */
    val storedBytes: Long = 0L,
    val finishedAt: Long = 0L,
)

/** What one [SkuSyncRepository.runStep] got to. */
sealed interface SkuSyncStep {
    /** Finished - or nothing to do yet ([summary] null: synced less than a day ago). */
    data class Done(val summary: SkuSyncSummary?) : SkuSyncStep
    /** Out of time part way through; the next step carries on from the same page. */
    data object Pending : SkuSyncStep
    data class Failed(val message: String) : SkuSyncStep
}

/** Settings > Sync masters now: only the changes since the last sync, or every row again. */
enum class ManualSync { CHANGES, FULL }
