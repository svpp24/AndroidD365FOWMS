package com.d365fo.mobileops.presentation.common

import androidx.lifecycle.ViewModel
import com.d365fo.mobileops.data.security.OfflineSignIn
import com.d365fo.mobileops.data.sync.OfflinePrefetcher
import com.d365fo.mobileops.data.sync.OfflineReadiness
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** Keeps orders saved for offline use while the worker is online, and reports what is saved. */
@HiltViewModel
class OfflineReadyViewModel @Inject constructor(
    private val prefetcher: OfflinePrefetcher,
    private val skuSync: com.d365fo.mobileops.data.repository.SkuSyncRepository,
) : ViewModel() {

    /** The last finished item / barcode / variant sync - counts, time, memory, size. */
    fun lastSkuSync(): com.d365fo.mobileops.data.repository.SkuSyncSummary? = skuSync.lastSummary()

    val readiness: StateFlow<OfflineReadiness> = prefetcher.readiness

    val offlineSignInDays: Int = OfflineSignIn.VALID_DAYS

    /** D365FO answers again, or the warehouse changed: refresh the saved orders if they're old. */
    fun onlineAt(warehouseId: String) = prefetcher.maybeRun(warehouseId)

    fun saveNow(warehouseId: String) = prefetcher.runNow(warehouseId)
}
