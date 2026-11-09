package com.d365fo.mobileops.presentation.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.d365fo.mobileops.domain.model.RawScan
import com.d365fo.mobileops.domain.model.WarehouseWorkflow
import com.d365fo.mobileops.presentation.common.focus.BandKind
import com.d365fo.mobileops.presentation.common.focus.BandState
import com.d365fo.mobileops.presentation.common.scan.HidScannerBridge
import com.d365fo.mobileops.presentation.common.scan.ScanContext
import com.d365fo.mobileops.presentation.common.scan.ScanRoute
import com.d365fo.mobileops.presentation.common.scan.ScanRouter
import com.d365fo.mobileops.presentation.common.scan.TransferDirection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject

/**
 * Scanning on Home (redesign brief section 6): an item opens On-hand, a PO PO receiving, a
 * transfer Ship or Receive by its direction, a journal its cycle count, a location or bin label
 * Location inquiry. The process's own screen then gets the very same scan, so it opens on that
 * document - nothing is asked twice. Anything else is a band on Home; nothing opens.
 */
@HiltViewModel
class HomeScanViewModel @Inject constructor(
    private val router: ScanRouter,
    private val bridge: HidScannerBridge,
) : ViewModel() {

    /** Where to go; [forward] is the scan to hand the new screen once it listens. */
    data class Open(val route: String, val workflow: WarehouseWorkflow?, val forward: RawScan?)

    private val _band = MutableStateFlow<BandState?>(null)
    val band: StateFlow<BandState?> = _band.asStateFlow()

    private val opens = Channel<Open>(Channel.BUFFERED)
    val open = opens.receiveAsFlow()

    private var handle: ScanRouter.Handle? = null

    /** While Home is in front. */
    fun attach() {
        if (handle == null) handle = router.attach(ScanContext.Home) { route -> onRoute(route) }
    }

    fun detach() {
        handle?.close()
        handle = null
    }

    fun clearBand() {
        _band.value = null
    }

    private suspend fun onRoute(route: ScanRoute) {
        val raw = route.identity.raw
        val target: Open? = when (route) {
            is ScanRoute.OpenOnHand -> Open(Routes.ON_HAND, null, raw)
            is ScanRoute.OpenPoReceiving -> Open(Routes.RECEIVE_FLOW, WarehouseWorkflow.PO_RECEIVING, raw)
            is ScanRoute.OpenTransfer -> when (route.direction) {
                TransferDirection.SHIP -> Open(Routes.TRANSFER_SHIP, WarehouseWorkflow.TRANSFER_SHIP, raw)
                TransferDirection.RECEIVE -> Open(Routes.TRANSFER_RECEIVE, WarehouseWorkflow.TRANSFER_RECEIVE, raw)
                // Not on this device, so whether it ships or receives here isn't known.
                null -> null.also { _band.value = BandState(BandKind.Bad, "Transfer not on this device", "${route.transferId} · nothing opened") }
            }
            is ScanRoute.OpenCountJournal -> Open(
                Routes.COUNTING_CAPTURE.replace("{journalId}", android.net.Uri.encode(route.journalId)),
                WarehouseWorkflow.INVENTORY_COUNT,
                null,
            )
            is ScanRoute.StartStoreCount, is ScanRoute.OpenLocation ->
                Open(Routes.INQUIRY_LOCATION, WarehouseWorkflow.LOCATION_INQUIRY, raw)
            is ScanRoute.Reject -> null.also { _band.value = BandState(BandKind.Bad, route.title, route.line) }
            // Home is never a flow - these don't come from ScanContext.Home.
            is ScanRoute.Accept, is ScanRoute.SwitchDocument, is ScanRoute.Leave -> null
        }
        if (target != null) {
            _band.value = null
            opens.send(target)
        }
    }

    /**
     * Hands [scan] to the screen just opened, once it collects scans (its listener count goes
     * up). A screen that never starts listening within [FORWARD_WAIT_MS] doesn't get it.
     */
    fun forwardWhenListening(scan: RawScan, listenersBefore: Int) {
        viewModelScope.launch {
            val ready = withTimeoutOrNull(FORWARD_WAIT_MS) { bridge.listeners.first { it > listenersBefore } }
            if (ready == null) {
                Timber.w("home scan not handed on: no screen started listening")
                return@launch
            }
            router.passThrough(scan)
            bridge.forward(scan)
        }
    }

    fun listenersNow(): Int = bridge.listeners.value

    override fun onCleared() {
        detach()
    }

    private companion object {
        const val FORWARD_WAIT_MS = 3_000L
    }
}
