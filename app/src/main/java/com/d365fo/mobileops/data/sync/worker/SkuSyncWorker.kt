package com.d365fo.mobileops.data.sync.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
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
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result =
        // A step, not the whole sync: an export still running in D365FO is picked up again by a
        // continuation a few minutes later - see SkuSyncRepository.runStep. Kept well inside
        // WorkManager's ten-minute limit for one run.
        when (val step = repository.runStep(RUN_BUDGET_MS)) {
            is SkuSyncStep.Done -> {
                step.summary?.let {
                    Timber.i(
                        "SKU sync complete (%s): %d items, %d barcodes, %d variants in %d s",
                        if (it.full) "full" else "delta", it.itemCount, it.barcodeCount, it.variantCount, it.seconds,
                    )
                }
                Result.success()
            }
            SkuSyncStep.Pending -> {
                Timber.i("SKU sync: out of time part way - continuing shortly")
                scheduler.scheduleSkuContinuation()
                Result.success()
            }
            is SkuSyncStep.Failed -> {
                Timber.w("SKU sync failed: %s", step.message)
                // WorkManager's own backoff; the cache stays as fresh as it was.
                Result.retry()
            }
        }

    private companion object {
        const val RUN_BUDGET_MS = 7 * 60 * 1000L
    }
}
