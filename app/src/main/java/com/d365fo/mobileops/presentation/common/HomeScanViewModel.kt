package com.d365fo.mobileops.presentation.common

import androidx.lifecycle.ViewModel
import com.d365fo.mobileops.data.local.dao.JournalDao
import com.d365fo.mobileops.data.repository.PendingFlowResume
import com.d365fo.mobileops.domain.model.ScanSource
import com.d365fo.mobileops.domain.model.WarehouseWorkflow
import com.d365fo.mobileops.presentation.common.components.StatusKind
import com.d365fo.mobileops.presentation.common.scan.HidScannerBridge
import com.d365fo.mobileops.presentation.common.scan.ScanContext
import com.d365fo.mobileops.presentation.common.scan.ScanFeedback
import com.d365fo.mobileops.presentation.common.scan.ScanRoute
import com.d365fo.mobileops.presentation.common.scan.ScanRouter
import com.d365fo.mobileops.presentation.common.scan.TransferDirection
import com.d365fo.mobileops.presentation.modules.stepflow.PendingOnHandItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import javax.inject.Inject

/**
 * Scanning on Home (redesign brief §3.2, phase 8): Home is on [ScanRouter] with
 * [ScanContext.Home], and every scan opens the screen it belongs to - see [homeScanTarget].
 *
 * How the scan reaches the screen it opens:
 * - PO receiving and the two transfer flows take the order through [PendingFlowResume], the
 *   hand-off "Continue where you left off" already uses: the flow opens with it scanned.
 * - On-hand takes the item through [PendingOnHandItem].
 * - Store count, location inquiry and counting read scans themselves: the same barcode is given
 *   to them again ([HidScannerBridge.submitCameraScan]) once they have had time to start
 *   listening. A screen on the router gets it through the router's own hand-over.
 */
@HiltViewModel
class HomeScanViewModel @Inject constructor(
    private val scanRouter: ScanRouter,
    private val scannerBridge: HidScannerBridge,
    private val pendingFlowResume: PendingFlowResume,
    private val pendingOnHand: PendingOnHandItem,
    private val journalDao: JournalDao,
    private val scanFeedback: ScanFeedback,
) : ViewModel() {

    /** What Home's status band says after a scan that opened nothing. */
    data class Band(val kind: StatusKind, val title: String, val line: String)

    private val _band = MutableStateFlow<Band?>(null)
    val band: StateFlow<Band?> = _band.asStateFlow()

    /** A transfer whose direction the device can't tell: Home asks Ship or Receive. */
    private val _ask = MutableStateFlow<HomeScanTarget.AskTransferDirection?>(null)
    val askDirection: StateFlow<HomeScanTarget.AskTransferDirection?> = _ask.asStateFlow()

    private val _navigate = Channel<String>(Channel.BUFFERED)
    /** Routes to open, one per scan - collected by Home. */
    val navigate: Flow<String> = _navigate.receiveAsFlow()

    /**
     * The workflows on the worker's menu, as Home's tiles show them - set by Home whenever its
     * Menu access changes. Never empty once Home has composed: with no menu yet it is every
     * built module the worker isn't blocked from (see [visibleModules]).
     */
    @Volatile var allowed: Set<WarehouseWorkflow> = emptySet()

    @Volatile private var handle: ScanRouter.Handle? = null

    /** The barcode given again to the screen Home opened - Home must not route it a second time. */
    @Volatile private var replayed: String? = null
    @Volatile private var replayedAt = 0L

    /** Home is on screen (true) or has gone (false). */
    fun onScreen(visible: Boolean) {
        if (visible) {
            if (handle == null) handle = scanRouter.attach(ScanContext.Home, ::onRoute)
        } else {
            handle?.close()
            handle = null
        }
    }

    override fun onCleared() {
        handle?.close()
        super.onCleared()
    }

    fun onCameraDecoded(value: String) = scannerBridge.submitCameraScan(value)

    fun dismissBand() {
        _band.value = null
    }

    fun dismissAsk() {
        _ask.value = null
    }

    /** The worker answered Ship or Receive for [ask]. */
    fun chooseDirection(ask: HomeScanTarget.AskTransferDirection, direction: TransferDirection) {
        _ask.value = null
        open(transferTarget(ask.transferId, direction))
    }

    private suspend fun onRoute(route: ScanRoute) {
        val raw = route.identity.raw
        if (raw.source == ScanSource.CAMERA && raw.value == replayed &&
            System.currentTimeMillis() - replayedAt < REPLAY_GUARD_MS
        ) {
            return
        }
        _ask.value = null
        val onDevice = route is ScanRoute.OpenCountJournal &&
            runCatching { journalDao.getJournal(route.journalId) != null }.getOrDefault(false)
        when (val target = homeScanTarget(route, allowed, onDevice)) {
            is HomeScanTarget.Notice -> {
                if (target.warn) scanFeedback.warning() else scanFeedback.error()
                _band.value = Band(if (target.warn) StatusKind.Warn else StatusKind.Bad, target.title, target.line)
            }
            is HomeScanTarget.AskTransferDirection -> {
                scanFeedback.warning()
                _band.value = null
                _ask.value = target
            }
            is HomeScanTarget.Replay -> {
                scanFeedback.success()
                leave(target.route)
                // Holds the router (the next scan waits) until the screen has started listening,
                // so scans keep their order.
                delay(REPLAY_DELAY_MS)
                replayed = target.raw
                replayedAt = System.currentTimeMillis()
                scannerBridge.submitCameraScan(target.raw)
            }
            else -> {
                scanFeedback.success()
                open(target)
            }
        }
    }

    private fun open(target: HomeScanTarget) {
        when (target) {
            is HomeScanTarget.Resume -> {
                pendingFlowResume.set(target.flowType, target.orderId)
                leave(target.route)
            }
            is HomeScanTarget.OnHand -> {
                pendingOnHand.set(target.itemId)
                leave(Routes.ON_HAND)
            }
            else -> Unit
        }
    }

    /** Lets go of the router before navigating, so a scan right behind this one waits for the next screen. */
    private fun leave(route: String) {
        _band.value = null
        handle?.close()
        handle = null
        _navigate.trySend(route)
    }

    private companion object {
        /** Navigation plus the next screen's first composition, with room for a slow device. */
        const val REPLAY_DELAY_MS = 700L
        const val REPLAY_GUARD_MS = 5_000L
    }
}
