package com.d365fo.mobileops.data.sync.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.d365fo.mobileops.data.repository.ManualSync
import com.d365fo.mobileops.data.repository.SkuSyncRepository
import com.d365fo.mobileops.data.repository.SkuSyncStep
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber

/**
 * The bulk item + barcode master data load, run only when Wi-Fi-and-charging (see
 * `SyncScheduler.schedulePeriodicWork` — the constraint that makes this different from the
 * master-data pull worker this codebase already removed once for competing with on-demand scan
 * traffic; see `SyncScheduler.kt`'s own doc comment on that history).
 *
 * A full re-sync every run, not delta — see `SkuSyncRepository`'s own doc comment and the plan
 * this implements: nothing the AOS side publishes a `ModifiedDateTime` for today, so there is no
 * watermark to sync against yet. `upsertItemsChunked`/`upsertBarcodesChunked` are `REPLACE`-only
 * upserts, so a re-run never loses a row that is still in the export — it just costs a full
 * download and a chunked write each time, which is exactly why this worker is fenced to
 * Wi-Fi-and-charging rather than run on whatever connection happens to be up.
 */
@HiltWorker
class SkuSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: SkuSyncRepository,
    private val scheduler: com.d365fo.mobileops.data.sync.SyncScheduler,
    private val orders: com.d365fo.mobileops.data.repository.OrderCreationRepository,
) : CoroutineWorker(context, params) {

    /** Settings > Sync masters now: set on its first run only, so a continuation never restarts it. */
    private val manual = inputData.getString(KEY_MANUAL)?.let { runCatching { ManualSync.valueOf(it) }.getOrNull() }

    /** Started from Settings: its continuations go on any connection, like it did. */
    private val manualChain = inputData.getBoolean(KEY_MANUAL_CHAIN, false)

    /**
     * Vendors come with the master sync (user, 15 Nov 2026): before, only Create PO / Return to
     * vendor opened online fetched them, so a new or wiped device went offline with none ("No
     * vendors yet - connect once"). At most every 6 hours; every time when asked for in Settings.
     */
    private suspend fun refreshVendors() {
        when (val result = runCatching { orders.refreshVendors(force = manual == ManualSync.CHANGES || manual == ManualSync.FULL) }.getOrNull()) {
            is com.d365fo.mobileops.util.NetworkResult.Success -> Timber.i("vendors: %d on the device", result.data)
            is com.d365fo.mobileops.util.NetworkResult.Error -> Timber.w("vendors not refreshed: %s", result.failure.message)
            else -> Unit
        }
        // The approved vendor rules with them (user, 17 Nov 2026): Create PO checks a scan against
        // these on the device, without asking D365FO.
        when (val result = runCatching { orders.refreshApprovedRules(force = manual == ManualSync.CHANGES || manual == ManualSync.FULL) }.getOrNull()) {
            is com.d365fo.mobileops.util.NetworkResult.Success -> Timber.i("approved vendor rules: %d items on the device", result.data)
            is com.d365fo.mobileops.util.NetworkResult.Error -> Timber.w("approved vendor rules not refreshed: %s", result.failure.message)
            else -> Unit
        }
    }

    override suspend fun doWork(): Result =
        // A step, not the whole sync: an export still running in D365FO is picked up again by a
        // continuation a few minutes later - see SkuSyncRepository.runStep. Kept well inside
        // WorkManager's ten-minute limit for one run.
        // A retry carries on where the failed attempt stopped: a full sync is not restarted.
        when (val step = repository.runStep(
            RUN_BUDGET_MS,
            manual.takeIf { runAttemptCount == 0 || it == ManualSync.SCHEDULED } ?: manual?.let { ManualSync.CHANGES },
        )) {
            is SkuSyncStep.Done -> {
                step.summary?.let {
                    Timber.i(
                        "SKU sync complete (%s): %d items, %d barcodes, %d variants in %d s",
                        if (it.full) "full" else "delta", it.itemCount, it.barcodeCount, it.variantCount, it.seconds,
                    )
                }
                refreshVendors()
                Result.success()
            }
            SkuSyncStep.Pending -> {
                Timber.i("SKU sync: out of time part way - continuing shortly")
                // The 2-hourly changes run carries on on any connection, as one asked for in Settings.
                if (manualChain || manual == ManualSync.SCHEDULED) scheduler.continueMasterSyncNow() else scheduler.scheduleSkuContinuation()
                Result.success()
            }
            is SkuSyncStep.Failed -> {
                Timber.w("SKU sync failed: %s", step.message)
                // Asked for in Settings: the worker is told it failed rather than it retrying on
                // for hours behind the button. The position is kept for the next sync.
                if (manualChain && runAttemptCount >= MANUAL_ATTEMPTS - 1) {
                    Result.failure(androidx.work.workDataOf(KEY_ERROR to step.message))
                } else {
                    // WorkManager's own backoff; the cache stays as fresh as it was.
                    Result.retry()
                }
            }
        }

    companion object {
        private const val RUN_BUDGET_MS = 7 * 60 * 1000L
        const val KEY_MANUAL = "manual"
        const val KEY_MANUAL_CHAIN = "manualChain"
        const val KEY_ERROR = "error"
        private const val MANUAL_ATTEMPTS = 3
    }
}
