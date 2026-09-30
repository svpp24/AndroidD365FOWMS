package com.d365fo.mobileops.data.sync.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.d365fo.mobileops.data.repository.SkuSyncRepository
import com.d365fo.mobileops.util.NetworkResult
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
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return when (val result = repository.downloadAndImport()) {
            is NetworkResult.Success -> {
                Timber.i(
                    "SKU sync complete: %d items, %d barcodes",
                    result.data.itemCount,
                    result.data.barcodeCount,
                )
                Result.success()
            }

            is NetworkResult.Error -> {
                Timber.w(
                    "SKU sync failed: %s (retryable=%s)",
                    result.failure.message,
                    result.failure.retryable,
                )
                // Always retry on WorkManager's own backoff, never dead-letter - unlike a
                // posting, there is no user-facing queue entry to abandon here, and a failed
                // sync just means the cache stays exactly as fresh as it was before this run.
                Result.retry()
            }

            NetworkResult.Loading -> Result.retry()
        }
    }
}
