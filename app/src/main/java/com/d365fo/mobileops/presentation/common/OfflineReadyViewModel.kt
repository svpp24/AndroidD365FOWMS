package com.d365fo.mobileops.presentation.common

import androidx.lifecycle.ViewModel
import com.d365fo.mobileops.data.security.OfflineSignIn
import com.d365fo.mobileops.data.sync.OfflinePrefetcher
import com.d365fo.mobileops.data.sync.OfflineReadiness
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Keeps orders saved for offline use while the worker is online, and reports what is saved. */
@HiltViewModel
class OfflineReadyViewModel @Inject constructor(
    private val prefetcher: OfflinePrefetcher,
    private val skuSync: com.d365fo.mobileops.data.repository.SkuSyncRepository,
    private val scheduler: com.d365fo.mobileops.data.sync.SyncScheduler,
) : ViewModel() {

    /** Settings > Sync masters now: running, failed, or idle. */
    val mastersSync: StateFlow<com.d365fo.mobileops.data.sync.MasterSyncState> = scheduler.mastersSync
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), com.d365fo.mobileops.data.sync.MasterSyncState.Idle)

    /** Items, barcodes and variants from D365FO now, on any connection - changes, or every row. */
    fun syncMastersNow(full: Boolean) = scheduler.syncMastersNow(
        if (full) com.d365fo.mobileops.data.repository.ManualSync.FULL else com.d365fo.mobileops.data.repository.ManualSync.CHANGES,
    )

    /** The last finished item / barcode / variant sync - counts, time, memory, size. */
    fun lastSkuSync(): com.d365fo.mobileops.data.repository.SkuSyncSummary? = skuSync.lastSummary()

    val readiness: StateFlow<OfflineReadiness> = prefetcher.readiness

    val offlineSignInDays: Int = OfflineSignIn.VALID_DAYS

    /** D365FO answers again, or the warehouse changed: refresh the saved orders if they're old. */
    fun onlineAt(warehouseId: String) = prefetcher.maybeRun(warehouseId)

    fun saveNow(warehouseId: String) = prefetcher.runNow(warehouseId)
}
