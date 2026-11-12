package com.d365fo.mobileops.presentation.modules.dataenquiry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.d365fo.mobileops.data.repository.WorkerSessionRepository
import com.d365fo.mobileops.domain.repository.LocationFacts
import com.d365fo.mobileops.domain.repository.OnDemandLookupRepository
import com.d365fo.mobileops.presentation.common.scan.HidScannerBridge
import com.d365fo.mobileops.presentation.common.scan.ScanFeedback
import com.d365fo.mobileops.util.NetworkResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * One location, one answer: does it exist in this warehouse, and can it be received into.
 *
 * Same shape as [com.d365fo.mobileops.presentation.modules.stepflow.OnHandInquiryViewModel] -
 * a single scan, a single query, nothing cached, because a blocked location is exactly the kind
 * of fact that must never be answered from a stale local copy.
 */
@HiltViewModel
class LocationInquiryViewModel @Inject constructor(
    private val lookups: OnDemandLookupRepository,
    private val workerSession: WorkerSessionRepository,
    private val scannerBridge: HidScannerBridge,
    private val scanFeedback: ScanFeedback,
    private val enquiry: com.d365fo.mobileops.data.repository.EnquiryRepository,
    private val itemDao: com.d365fo.mobileops.data.local.dao.ItemDao,
) : ViewModel() {

    private val _state = MutableStateFlow(LocationInquiryState())
    val state: StateFlow<LocationInquiryState> = _state.asStateFlow()

    private var pending: Job? = null

    init {
        viewModelScope.launch {
            workerSession.session.collect { session ->
                _state.update {
                    // Only prefill when the operator has not already typed a different
                    // warehouse - the assigned warehouse is a default, not a lock.
                    if (it.warehouseId.isBlank()) {
                        it.copy(warehouseId = session?.selected?.warehouseId.orEmpty())
                    } else {
                        it
                    }
                }
            }
        }
        viewModelScope.launch {
            scannerBridge.scans.collect { onLocationEntered(it.value) }
        }
    }

    fun onCameraDecoded(value: String) = scannerBridge.submitCameraScan(value)

    fun setScannerInterception(enabled: Boolean) {
        scannerBridge.enabled = enabled
    }

    fun onWarehouseChange(value: String) {
        _state.update { it.copy(warehouseId = value) }
    }

    fun onLocationEntered(scanned: String) {
        val locationId = scanned.trim()
        val warehouseId = _state.value.warehouseId.trim()
        if (locationId.isEmpty()) return
        if (warehouseId.isEmpty()) {
            _state.update { it.copy(error = "Enter a warehouse first", result = null) }
            return
        }

        pending?.cancel()
        pending = viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, result = null, locationId = locationId, stock = emptyList(), stockError = null) }

            when (val result = lookups.lookupLocation(warehouseId, locationId)) {
                is NetworkResult.Error -> {
                    scanFeedback.error()
                    _state.update { it.copy(busy = false, error = result.failure.message) }
                }
                NetworkResult.Loading -> {
                    _state.update { it.copy(busy = false, error = "Lookup did not complete") }
                }
                is NetworkResult.Success -> {
                    val facts = result.data
                    if (facts == null) {
                        scanFeedback.error()
                        _state.update {
                            it.copy(
                                busy = false,
                                error = "No location $locationId in $warehouseId",
                            )
                        }
                    } else {
                        scanFeedback.success()
                        _state.update { it.copy(busy = false, result = facts, stockLoading = true, stock = emptyList(), stockError = null) }
                        loadStock(warehouseId, facts.locationId.ifBlank { locationId })
                    }
                }
            }
        }
    }

    /**
     * What is on the location (user, 12 Nov), by category: D365FO's on-hand there, with names and
     * categories from the device's item list.
     */
    private suspend fun loadStock(warehouseId: String, locationId: String) {
        when (val r = enquiry.locationItems(warehouseId, locationId)) {
            is NetworkResult.Success -> {
                val known = r.data.map { it.itemId }.distinct().chunked(500)
                    .flatMap { itemDao.itemsByIds(it) }.associateBy { it.itemId }
                _state.update { it.copy(stockLoading = false, stock = groupByCategory(r.data, known)) }
            }
            is NetworkResult.Error -> _state.update { it.copy(stockLoading = false, stockError = r.failure.message) }
            NetworkResult.Loading -> Unit
        }
    }
}

/** One item on the location, named from the device's item list when it has it. */
data class LocationItemRow(
    val itemId: String,
    val itemName: String,
    val onHand: Double,
    val reserved: Double,
    val unit: String,
)

internal const val LOCATION_NO_CATEGORY = "No category"

/** Category path ("No category" last), A to Z; items by name inside each. */
internal fun groupByCategory(
    stock: List<com.d365fo.mobileops.data.repository.LocationStock>,
    items: Map<String, com.d365fo.mobileops.data.local.entity.ItemEntity>,
): List<Pair<String, List<LocationItemRow>>> =
    stock.groupBy { items[it.itemId]?.category?.takeIf { c -> c.isNotBlank() } ?: LOCATION_NO_CATEGORY }
        .map { (category, rows) ->
            category to rows.map { s ->
                val item = items[s.itemId]
                LocationItemRow(s.itemId, item?.itemName.orEmpty(), s.onHand, s.reserved, item?.inventoryUnit.orEmpty())
            }.sortedWith(compareBy({ it.itemName.ifBlank { it.itemId }.lowercase() }, { it.itemId }))
        }
        .sortedWith(compareBy({ it.first == LOCATION_NO_CATEGORY }, { it.first.lowercase() }))

data class LocationInquiryState(
    val warehouseId: String = "",
    val locationId: String? = null,
    val result: LocationFacts? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val stockLoading: Boolean = false,
    val stock: List<Pair<String, List<LocationItemRow>>> = emptyList(),
    val stockError: String? = null,
)
