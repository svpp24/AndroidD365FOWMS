package com.d365fo.mobileops.presentation.modules.transfer

import androidx.lifecycle.ViewModel
import androidx.room.withTransaction
import androidx.lifecycle.viewModelScope
import com.d365fo.mobileops.data.repository.TransferLookupRepository
import com.d365fo.mobileops.data.repository.WorkerSessionRepository
import com.d365fo.mobileops.domain.model.TransferCapture
import com.d365fo.mobileops.data.local.entity.FlowType
import com.d365fo.mobileops.data.local.entity.SyncOperation
import com.d365fo.mobileops.data.repository.FlowSessionRepository
import com.d365fo.mobileops.data.repository.PendingFlowResume
import com.d365fo.mobileops.data.repository.StockRow
import com.d365fo.mobileops.data.repository.StoredLine
import com.d365fo.mobileops.data.security.CompanyProvider
import com.d365fo.mobileops.data.security.SecureStore
import com.d365fo.mobileops.data.sync.SyncQueueRepository
import com.d365fo.mobileops.data.sync.SyncScheduler
import com.d365fo.mobileops.data.sync.payload.TransferLinePayload
import com.d365fo.mobileops.data.sync.payload.TransferReceivePayload
import com.d365fo.mobileops.data.sync.payload.TransferShipPayload
import com.d365fo.mobileops.domain.model.TransferMode
import com.d365fo.mobileops.domain.model.ShippedBatchCheck
import com.d365fo.mobileops.domain.model.TransferOrderLine
import com.d365fo.mobileops.domain.model.checkShippedBatch
import com.d365fo.mobileops.domain.model.TransferStep
import com.d365fo.mobileops.domain.model.TransferOrderLookup
import com.d365fo.mobileops.domain.model.nextIncompleteTransferStep
import com.d365fo.mobileops.domain.repository.OnDemandLookupRepository
import com.d365fo.mobileops.presentation.common.scan.HidScannerBridge
import com.d365fo.mobileops.presentation.common.scan.RejectReason
import com.d365fo.mobileops.presentation.common.scan.ScanContext
import com.d365fo.mobileops.presentation.common.scan.ScanFeedback
import com.d365fo.mobileops.presentation.common.scan.ScanIdentity
import com.d365fo.mobileops.presentation.common.scan.ScanKind
import com.d365fo.mobileops.presentation.common.scan.ScanRoute
import com.d365fo.mobileops.presentation.common.scan.ScanRouter
import com.d365fo.mobileops.util.AppFailure
import com.d365fo.mobileops.util.NetworkResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.math.BigDecimal
import javax.inject.Inject

/** See [TransferFlowViewModel.lastRaw] - the window a repeated identical scan is ignored in. */
private const val DUPLICATE_SCAN_WINDOW_MS = 1500L

/**
 * Drives the transfer flow, in whichever direction it was opened for.
 *
 * Structurally the receiving flow's twin, with one rule that is entirely its own: **the quantity
 * ceiling is what is outstanding in this direction**, and the server decides which that is. The
 * client must never look at the ordered quantity when receiving — a ten-unit line with four
 * shipped has four to receive, and offering ten would invite booking in stock that never left.
 *
 * Redesign phase 5 (docs/ui-redesign-brief.md): scan only, the same way phase 4 rebuilt PO
 * receiving. The transfer scan opens the order; an item scan fills the line in full - shipping
 * picks the one location (and batch) with stock, receiving takes the batch from the shipment and
 * the warehouse's receipt location - and the last line ships or posts by itself after the Undo
 * window. Nothing in stock is a Warn band with Skip line / Ship without it. The posting path
 * (queue, idempotency key, live attempt, retry) is unchanged; only when it runs changes.
 */
@HiltViewModel
class TransferFlowViewModel @Inject constructor(
    private val transfers: TransferLookupRepository,
    private val lookups: OnDemandLookupRepository,
    private val sessions: WorkerSessionRepository,
    private val scannerBridge: HidScannerBridge,
    private val flowSessions: FlowSessionRepository,
    private val scanFeedback: ScanFeedback,
    private val syncQueue: SyncQueueRepository,
    private val syncScheduler: SyncScheduler,
    private val secureStore: SecureStore,
    private val companyProvider: CompanyProvider,
    private val database: com.d365fo.mobileops.data.local.D365Database,
    private val notifier: com.d365fo.mobileops.data.sync.SyncNotifier,
    private val postingDispatcher: com.d365fo.mobileops.data.sync.PostingDispatcher,
    private val pendingFlowResume: PendingFlowResume,
    private val opportunityCounts: com.d365fo.mobileops.data.counting.OpportunityCounts,
    private val picker: com.d365fo.mobileops.data.sync.TransferPicker,
    private val orderCreation: com.d365fo.mobileops.data.repository.OrderCreationRepository,
    private val freezes: com.d365fo.mobileops.data.repository.WarehouseFreezeRepository,
    private val scanRouter: ScanRouter,
) : ViewModel() {

    private val _state = MutableStateFlow(TransferFlowState())
    val state: StateFlow<TransferFlowState> = _state.asStateFlow()

    private var pending: Job? = null

    // Same fix as ReceivingFlowViewModel - see the doc there. This screen shares the same
    // scanner bridge and the same one-in-flight-request pattern, so it is exposed to the
    // same repeated-broadcast hardware quirk.
    private var lastRaw: String? = null
    /** A batch other than the line's own, scanned once and warned about - see onBatch. */
    private var otherBatchWarned: String? = null
    private var lastRawAtMs: Long = 0L

    // -- scans (redesign phase 5: through ScanRouter) ------------------------------------------

    /**
     * Scans arrive through [ScanRouter] while this flow is on screen - the flow screen and its
     * typing screen both register, so a scan made while the keypad is open still reaches this
     * flow. Routed by [scanContext], read fresh for every scan; the next scan waits until
     * [onRoute] has finished with this one. See the identical ReceivingFlowViewModel.scannerOnScreen.
     */
    private var scanHandle: ScanRouter.Handle? = null
    private var scanScreens = 0

    fun scannerOnScreen(visible: Boolean) {
        if (visible) {
            if (scanScreens++ == 0) scanHandle = scanRouter.attach({ scanContext() }, ::onRoute)
        } else if (scanScreens > 0 && --scanScreens == 0) {
            scanHandle?.close()
            scanHandle = null
        }
    }

    override fun onCleared() {
        scanHandle?.close()
        super.onCleared()
    }

    /** What each step accepts - see [ScanContext.Flow]. */
    private fun scanContext(s: TransferFlowState = _state.value): ScanContext {
        val to = s.capture.transferId
        val shipping = s.capture.mode == TransferMode.SHIP
        return when (s.step) {
            TransferStep.TRANSFER_ORDER -> ScanContext.Flow(
                accepts = setOf(ScanKind.TRANSFER_ORDER),
                documentKind = ScanKind.TRANSFER_ORDER,
                expecting = "Scan a transfer order",
            )
            // The order's items, so a wrong item is turned away at once. Shipping also takes a
            // bin here - the worker standing at it first picks from it.
            TransferStep.ITEM, TransferStep.QUANTITY -> ScanContext.Flow(
                accepts = if (shipping && s.step == TransferStep.ITEM) {
                    setOf(ScanKind.ITEM, ScanKind.LOCATION, ScanKind.BIN)
                } else {
                    setOf(ScanKind.ITEM)
                },
                documentKind = ScanKind.TRANSFER_ORDER,
                currentDocument = to,
                documentItemIds = s.openLines.takeIf { s.order?.linesInline == true && it.isNotEmpty() }
                    ?.map { it.itemId }?.toSet(),
                acceptUnknown = true,
                expecting = "Scan an item on this transfer",
            )
            // Batch, serial and location: free text, or a location the online lookup checks.
            else -> ScanContext.Flow(
                accepts = setOf(ScanKind.ITEM, ScanKind.LOCATION, ScanKind.BIN),
                documentKind = ScanKind.TRANSFER_ORDER,
                currentDocument = to,
                acceptUnknown = true,
            )
        }
    }

    private suspend fun onRoute(route: ScanRoute) {
        // A posting in progress finishes first: the next scan is about what comes after it.
        _state.first { !it.posting }
        val raw = route.identity.raw.value
        timber.log.Timber.i("transfer scan (%s) at %s: %s", route.identity.raw.source, _state.value.step, route::class.simpleName)
        when (route) {
            is ScanRoute.Accept -> {
                val identity = route.identity
                val s = _state.value
                // Shipping, bin first: a location at the item step is where the next pick comes from.
                val bin = when (identity) {
                    is ScanIdentity.Location -> identity.locationId
                    is ScanIdentity.Bin -> identity.bin.locationId
                    else -> null
                }
                if (bin != null && s.step == TransferStep.ITEM && s.capture.mode == TransferMode.SHIP) {
                    pickFrom(bin)
                } else {
                    submit(raw)?.join()
                }
            }
            // Another transfer: what is banked on this one ships or posts, then that order opens.
            is ScanRoute.SwitchDocument -> {
                pending?.cancel()
                switchToOrder(route.documentId)
            }
            is ScanRoute.Leave -> {
                scanFeedback.error()
                val what = when (route.identity.kind) {
                    ScanKind.PURCHASE_ORDER -> "a purchase order"
                    ScanKind.COUNT_JOURNAL -> "a counting journal"
                    else -> "another document"
                }
                notice(TransferNotice.Tone.WARN, "That's $what", "Finish this transfer, then scan it on Home")
            }
            is ScanRoute.Reject -> {
                scanFeedback.error()
                val tone = if (route.reason == RejectReason.NOT_EXPECTED) TransferNotice.Tone.WARN else TransferNotice.Tone.BAD
                notice(tone, route.title, route.line)
            }
            // Home routes - never produced for a flow context.
            else -> Unit
        }
    }

    private fun notice(tone: TransferNotice.Tone, title: String, line: String? = null, undoable: Boolean = false) =
        _state.update { it.copy(notice = TransferNotice(tone, title, line, undoable), error = null) }

    /** Shipping, a bin scanned at the item step: the next item is picked from it. */
    private fun pickFrom(locationId: String) {
        binScanned = true
        scanFeedback.success()
        _state.update {
            it.copy(
                capture = it.capture.copy(locationId = locationId),
                notice = TransferNotice(TransferNotice.Tone.INFO, "Picking from $locationId", "Scan the item"),
                error = null,
            )
        }
    }

    /** Shipping: the worker scanned the bin (rather than it being the warehouse default). */
    private var binScanned = false

    /** Set once when the screen opens; the direction never changes mid-flow. */
    fun setMode(mode: TransferMode) {
        if (_state.value.capture.mode == mode && _state.value.capture.transferId != null) return
        _state.value = TransferFlowState(capture = TransferCapture(mode = mode), lastPost = _state.value.lastPost)

        // See the identical hand-off in ReceivingFlowViewModel.init and PendingFlowResume - the
        // direction is not known until setMode runs (it arrives as a LaunchedEffect(mode) from
        // the screen, after this ViewModel's own init), so the resume check has to live here
        // instead, gated by the same "nothing looked up yet" guard above rather than its own.
        val flowType = when (mode) {
            TransferMode.SHIP -> FlowType.TRANSFER_SHIP
            TransferMode.RECEIVE -> FlowType.TRANSFER_RECEIVE
        }
        pendingFlowResume.consume(flowType)?.let(::submit)
    }

    fun onManualEntry(value: String) {
        submit(value)
    }

    /**
     * The line picked in the item search, so the "which line?" step takes it rather than asking
     * again - the search already showed each line's variant and the worker chose one. Only for
     * that item, and only briefly: a later scan of the same item still asks.
     */
    private var searchedLine: Pair<Double, Long>? = null

    fun pickSearchedLine(itemId: String, lineNumber: Double) {
        searchedLine = lineNumber to System.currentTimeMillis()
        submit(itemId)
    }

    private fun takeSearchedLine(): Double? {
        val picked = searchedLine ?: return null
        searchedLine = null
        return picked.first.takeIf { System.currentTimeMillis() - picked.second < SEARCHED_LINE_VALID_MS }
    }

    /** Skips the current batch/serial/location step when D365FO allows it blank for this direction. */
    fun skipStep() {
        val s = _state.value
        if (s.busy || !com.d365fo.mobileops.domain.model.canSkipTransferStep(s.step, s.capture)) return
        _state.update { it.copy(capture = it.capture.copy(skipped = it.capture.skipped + it.step), error = null) }
        advance()
        if (labelAnswer != null) viewModelScope.launch { drainLabelAnswers() }
    }

    fun onCameraDecoded(value: String) = scannerBridge.submitCameraScan(value)

    /** See [HidScannerBridge.justScanned] - used by the screen's BackHandler. */
    fun justScanned(): Boolean = scannerBridge.justScanned()

    /**
     * Answers the current step. [raw] is whatever was scanned or typed. Returns the job doing it,
     * so [onRoute] can wait for it before routing the next scan; null when nothing was started.
     */
    fun submit(raw: String): Job? {
        val value = raw.trim()
        if (value.isEmpty()) return null

        val now = android.os.SystemClock.elapsedRealtime()
        if (value == lastRaw && now - lastRawAtMs < DUPLICATE_SCAN_WINDOW_MS) return null
        lastRaw = value
        lastRawAtMs = now

        pending?.cancel()
        val job = viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, notice = null, outOfStock = null) }
            dispatch(value)
            // What a GS1 label already answered (batch, serial), step by step, in this same job -
            // so the next scan can never land on a step the label is still filling.
            drainLabelAnswers()
            _state.update { it.copy(busy = false) }
        }
        pending = job
        return job
    }

    private suspend fun dispatch(value: String) {
        when (_state.value.step) {
            TransferStep.TRANSFER_ORDER -> onTransferOrder(value)
            TransferStep.ITEM -> if (!switchedOrder(value)) onItem(value)
            TransferStep.QUANTITY -> if (!switchedOrder(value) && !isScanOfLineBatch(value) && !isScanOfCurrentItem(value)) onQuantity(value)
            TransferStep.LOCATION -> if (!switchedOrder(value)) onLocation(value)
            TransferStep.BATCH -> onBatch(value)
            TransferStep.SERIAL -> onSerial(value)
            TransferStep.CONFIRM -> Unit
        }
    }

    /** The label's answer for the step [advance] just moved to, applied until none is left. */
    private var labelAnswer: String? = null

    private suspend fun drainLabelAnswers() {
        while (true) {
            val answer = labelAnswer ?: return
            labelAnswer = null
            if (_state.value.error != null) return
            dispatch(answer)
        }
    }

    /** Banks the line in hand into [TransferFlowState.captured]. False when there is none. */
    private fun bankCurrentLine(): Boolean {
        val capture = _state.value.capture
        val line = capture.line ?: return false
        val quantity = capture.quantity ?: return false
        val shipping = capture.mode == TransferMode.SHIP

        // Same double cue as receiving: banking closes out a whole item, not just one prompt.
        scanFeedback.success()
        scanFeedback.success()
        notifier.notifyLineAdded(line.itemName.ifBlank { line.itemId })
        // Pick (ship) or putaway (receive) confirmed here: maybe an opportunity count.
        opportunityCounts.afterMovement(capture.locationId, line.itemId)

        val banked = CapturedTransferLine(
            lineNumber = line.lineNumber,
            itemId = line.itemId,
            itemName = line.itemName,
            quantity = quantity,
            unitSymbol = capture.unitSymbol.orEmpty(),
            locationId = capture.locationId,
            batchId = capture.batchId,
            serialIds = capture.serialIds,
            variantLabel = line.variant.label,
            // What was outstanding on the line when this was banked - the ceiling for the keypad,
            // so changing a line can never book more than the order actually has left.
            maxQuantity = line.remainingQuantity,
        )
        // Shipping less than the line because that is all the stock there is: the rest stays on
        // the order, and the transfer ships without waiting for it.
        val short = shipping && sessionRemaining(line) > quantity && shortOf == line.lineNumber
        shortOf = null
        _state.update {
            it.copy(
                captured = it.captured + banked,
                setAside = if (short) it.setAside + line.lineNumber else it.setAside,
                // The scan is the confirm: a green band says it counted, with Undo for a moment.
                notice = TransferNotice(
                    if (short) TransferNotice.Tone.WARN else TransferNotice.Tone.OK,
                    (if (shipping) "Picked " else "Received ") +
                        com.d365fo.mobileops.util.Formatters.qtyUnit(quantity, capture.unitSymbol),
                    listOfNotNull(
                        line.itemId,
                        line.itemName.takeIf { n -> n.isNotBlank() && !n.equals(line.itemId, ignoreCase = true) },
                        capture.batchId?.let { b -> "batch $b" },
                        capture.locationId?.let { l -> if (shipping) "from $l" else "to $l" },
                        if (short) "only that in stock - the rest stays on the order" else null,
                    ).joinToString(" · "),
                    undoable = true,
                ),
                capture = nextCapture(capture),
                step = TransferStep.ITEM,
                suggestedQuantity = null,
                stockHint = null,
                error = null,
            )
        }
        binScanned = false
        lineStock = null
        // Every scan is picked - a second batch of a line is a second pick of that line; the
        // line itself is never changed (user, 8 Oct 2026).
        if (shipping) pickLive(banked)
        persist()
        maybeAutoPost()
        return true
    }

    /** The capture for the next line on the same order: the order's fields and default bin only. */
    private fun nextCapture(capture: TransferCapture) = TransferCapture(
        transferId = capture.transferId,
        mode = capture.mode,
        fromWarehouseId = capture.fromWarehouseId,
        toWarehouseId = capture.toWarehouseId,
        // Kept across lines on the same order - see the identical note in
        // ReceivingFlowViewModel.bankCurrentLine.
        requireLocationCaptureFallback = capture.requireLocationCaptureFallback,
        defaultLocationId = capture.defaultLocationId,
        locationId = capture.defaultLocationId,
    )

    /** Live picks still in flight - a posting waits for them, so a line is never picked twice. */
    private val picks = mutableListOf<Job>()

    /**
     * Picks a just-banked shipment line in D365FO (feedback 8b3daea2). Offline it stays unpicked
     * and the posting picks it; a refusal (not that much in the bin, say) is shown, and posting
     * would refuse the same.
     */
    private fun pickLive(line: CapturedTransferLine) {
        val transferId = _state.value.capture.transferId ?: return
        val job = viewModelScope.launch {
            when (val result = pickCall(transferId, line, line.quantity)) {
                is NetworkResult.Success -> {
                    _state.update { s -> s.copy(captured = s.captured.map { if (it.id == line.id) it.copy(picked = true) else it }) }
                    persist()
                }
                is NetworkResult.Error -> if (result.failure is AppFailure.BusinessRule) {
                    if ((result.failure as AppFailure.BusinessRule).d365ErrorCode == "ALREADY_PICKED") {
                        // Picked in D365FO before (a session lost on the device, say): it ships
                        // as it is when picking is confirmed.
                        _state.update { s ->
                            s.copy(
                                captured = s.captured.map { if (it.id == line.id) it.copy(picked = true, pickedEarlier = true) else it },
                                // Two devices on one line (TO-40, 10 Oct): both read "Picked in D365FO"
                                // and the second only learnt otherwise when confirming.
                                notice = TransferNotice(TransferNotice.Tone.WARN, "Already picked on another device", line.itemId),
                            )
                        }
                        persist()
                    } else {
                        // Not picked: the line stays banked but the shipment must not go by
                        // itself with a refusal standing - the worker decides (Undo, or Lines).
                        autoPost?.cancel()
                        autoPost = null
                        _state.update { it.copy(error = "Not picked in D365FO: ${result.failure.message}", autoPostAt = null) }
                    }
                }
                NetworkResult.Loading -> Unit
            }
        }
        synchronized(picks) { picks.add(job) }
        job.invokeOnCompletion { synchronized(picks) { picks.remove(job) } }
    }

    private suspend fun pickCall(transferId: String, line: CapturedTransferLine, quantity: BigDecimal) =
        picker.pick(
            dataAreaId = companyProvider().orEmpty(),
            userName = sessions.session.value?.userName.orEmpty(),
            deviceId = secureStore.deviceId(),
            transferId = transferId,
            lineNumber = line.lineNumber,
            itemId = line.itemId,
            unitId = line.unitSymbol,
            quantity = quantity,
            batchId = line.batchId,
            serialIds = line.serialIds,
            locationId = line.locationId,
        )

    /**
     * Changes a picked line in D365FO first - unpicking or picking [delta] - and only then on the
     * device, so the two never disagree. Lines not picked change on the device only.
     */
    private fun changePicked(line: CapturedTransferLine, delta: BigDecimal, apply: () -> Unit) {
        val transferId = _state.value.capture.transferId
        if (!line.picked || transferId == null || delta.signum() == 0) return apply()
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val result = pickCall(transferId, line, delta)
            _state.update { it.copy(busy = false) }
            when (result) {
                is NetworkResult.Success -> apply()
                is NetworkResult.Error -> _state.update {
                    it.copy(
                        error = (if (delta.signum() < 0) "Could not undo the pick in D365FO" else "Could not pick more in D365FO") +
                            ": ${result.failure.message}",
                    )
                }
                NetworkResult.Loading -> Unit
            }
        }
    }

    fun removeCaptured(index: Int) {
        val line = _state.value.captured.getOrNull(index) ?: return
        changePicked(line, line.quantity.negate()) {
            _state.update { current ->
                current.copy(
                    captured = current.captured.filterNot { it.id == line.id },
                    setAside = current.setAside - line.lineNumber,
                )
            }
            refreshSuggested()
            persist()
            maybeAutoPost()
        }
    }

    fun showCaptured(show: Boolean) = _state.update { it.copy(showingCaptured = show) }

    fun reset() {
        pending?.cancel()
        autoPost?.cancel()
        autoPost = null
        val picked = _state.value.captured.filter { it.picked }
        val transferId = _state.value.capture.transferId
        if (picked.isNotEmpty() && transferId != null) {
            // Undone in D365FO first; a line that cannot be unpicked keeps the session open.
            viewModelScope.launch {
                _state.update { it.copy(busy = true) }
                var failed: Pair<CapturedTransferLine, String>? = null
                for (line in picked) {
                    val result = pickCall(transferId, line, line.quantity.negate())
                    if (result is NetworkResult.Error) {
                        failed = line to result.failure.message
                        break
                    }
                    // Undone in D365FO: no longer picked, whatever happens to the rest.
                    _state.update { st -> st.copy(captured = st.captured.map { if (it.id == line.id) it.copy(picked = false) else it }) }
                    persist()
                }
                _state.update { it.copy(busy = false) }
                if (failed != null) {
                    _state.update { it.copy(error = "Undo failed for ${failed.first.itemId}: ${failed.second}") }
                } else {
                    resetNow()
                }
            }
            return
        }
        resetNow()
    }

    private fun resetNow() {
        val capture = _state.value.capture
        _state.value = TransferFlowState(capture = TransferCapture(mode = capture.mode), lastPost = _state.value.lastPost)
        binScanned = false
        lineStock = null

        // Abandoning means abandoning; the saved copy goes with it.
        capture.transferId?.takeIf { it.isNotBlank() }?.let { id ->
            viewModelScope.launch { flowSessions.clear(flowTypeFor(capture.mode), id) }
        }
    }

    /** Ship and receive on the same order are separate jobs, so they save separately. */
    private fun flowTypeFor(mode: TransferMode): FlowType =
        if (mode == TransferMode.SHIP) FlowType.TRANSFER_SHIP else FlowType.TRANSFER_RECEIVE

    /** Lines (F2) "Ship now" / "Receive now": posts what is banked at once. */
    fun postTransfer() {
        if (_state.value.posting) return
        autoPost?.cancel()
        autoPost = null
        viewModelScope.launch { postNow() }
    }

    /**
     * Commits the whole basket to the outbound queue and returns to a fresh order prompt.
     *
     * See ReceivingFlowViewModel.postReceipt for the full reasoning — enqueueing, not a server
     * reply, is the commitment point, because a handheld shipping or receiving a transfer is
     * exactly as likely to be on bad warehouse wifi as one receiving a purchase order.
     */
    private suspend fun postNow() {
        val state = _state.value
        val capture = state.capture
        val transferId = capture.transferId
        if (transferId.isNullOrBlank() || state.captured.isEmpty()) return
        // Re-entrancy guard - see the identical one and its full reasoning on
        // ReceivingFlowViewModel.postReceipt. A second call while one is posting would mint its
        // own idempotencyKey from the same still-captured lines, and D365FO durably registers
        // both as distinct transfers - real double-moved inventory.
        if (state.posting) return

        // Not the job this runs in (a transfer typed over banked lines posts from inside it).
        if (pending !== kotlin.coroutines.coroutineContext[Job]) pending?.cancel()
        _state.update { it.copy(posting = true, busy = false, error = null, autoPostAt = null, showingCaptured = false) }

        // Once started, a posting runs to the end whatever is pressed meanwhile - an Undo after
        // the countdown, or the screen closing - the same guarantee the queue write needs.
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            // A live pick still going would be picked a second time by the posting (picked =
            // false in the payload): wait for it, then read the lines as it left them.
            synchronized(picks) { picks.toList() }.joinAll()
            val captured = _state.value.captured

            val session = sessions.session.value
            val deviceId = secureStore.deviceId()
            val dataAreaId = companyProvider().orEmpty()
            val userName = session?.userName.orEmpty()
            val personnelNumber = session?.personnelNumber
            val idempotencyKey = java.util.UUID.randomUUID().toString()
            val lines = captured.map { line ->
                TransferLinePayload(
                    lineNumber = line.lineNumber,
                    itemNumber = line.itemId,
                    quantity = line.quantity.toDouble(),
                    unitSymbol = line.unitSymbol,
                    batchNumber = line.batchId,
                    serialNumbers = line.serialIds,
                    locationId = line.locationId,
                    picked = line.picked,
                )
            }

            val operation: SyncOperation
            val payload: com.d365fo.mobileops.data.sync.payload.SyncPayload
            if (capture.mode == TransferMode.SHIP) {
                operation = SyncOperation.TRANSFER_ORDER_SHIP
                payload = TransferShipPayload(
                    idempotencyKey = idempotencyKey,
                    dataAreaId = dataAreaId,
                    transferId = transferId,
                    shipDate = java.time.Instant.now().toString(),
                    userName = userName,
                    personnelNumber = personnelNumber,
                    deviceId = deviceId,
                    lines = lines,
                )
            } else {
                operation = SyncOperation.TRANSFER_ORDER_RECEIVE
                payload = TransferReceivePayload(
                    idempotencyKey = idempotencyKey,
                    dataAreaId = dataAreaId,
                    transferId = transferId,
                    receiveDate = java.time.Instant.now().toString(),
                    userName = userName,
                    personnelNumber = personnelNumber,
                    deviceId = deviceId,
                    lines = lines,
                )
            }

            val entry = syncQueue.buildEntry(
                operation = operation,
                aggregateId = transferId,
                payload = payload,
                workerId = userName,
                deviceId = deviceId,
            )
            // Atomic for the same reason as ReceivingFlowViewModel.postReceipt: a kill between
            // these two independent writes would leave the transfer safely queued but the local
            // session un-cleared, offering the same lines back for posting a second time.
            database.withTransaction {
                syncQueue.enqueue(entry)
                flowSessions.clear(flowTypeFor(capture.mode), transferId)
            }

            // See the identical note in ReceivingFlowViewModel.postReceipt - tried live before
            // ever falling back to the background queue, so the operator finds out now, not
            // whenever WorkManager next runs. Leased first via the same claiming mechanism the
            // background worker uses (SyncQueueDao.leaseOne) - dispatching the freshly-built,
            // unleased entry directly raced a worker run that SyncStatusProvider's own reactive
            // push request can trigger the instant enqueue() above changes the pending count;
            // see the full account of that live-confirmed race on postReceipt.
            // Set when D365FO answered and refused it - see the identical note on
            // ReceivingFlowViewModel.postReceipt.
            var rejectedReason: String? = null
            // Posting switched off in Settings: queued and held, no live attempt.
            val held = !postingDispatcher.postingAllowed()
            val postedLive = if (held) false else syncQueue.leaseOne(entry.queueId)?.let { leased ->
                when (val result = postingDispatcher.dispatch(leased)) {
                    is NetworkResult.Success -> {
                        syncQueue.onSuccess(leased)
                        true
                    }
                    is NetworkResult.Error -> {
                        if (syncQueue.onFailure(leased, result.failure)) {
                            rejectedReason = result.failure.message
                        }
                        false
                    }
                    NetworkResult.Loading -> false
                }
            } ?: false
            if (!postedLive) syncScheduler.requestImmediatePush()

            scanFeedback.success()
            notifier.notifyWorkCompleted(transferId, postedLive)
            val outcome = TransferPost(
                transferId = transferId,
                mode = capture.mode,
                lineCount = captured.size,
                postedLive = postedLive,
                rejectedReason = rejectedReason,
                held = held,
                postedNow = postedLive && postingDispatcher.finishedInCall(idempotencyKey),
                documentId = if (postedLive) postingDispatcher.documentFor(idempotencyKey) else null,
            )
            // No summary and no Done tap (redesign phase 5): the band says what happened and
            // the screen is ready for the next transfer at once.
            _state.value = TransferFlowState(
                capture = TransferCapture(mode = capture.mode),
                lastPost = outcome,
                notice = noticeFor(outcome),
            )
            binScanned = false
            lineStock = null
        }
    }

    private fun noticeFor(o: TransferPost): TransferNotice {
        val what = "${o.lineCount} " + (if (o.lineCount == 1) "line" else "lines") + " · ${o.transferId}"
        val shipped = o.mode == TransferMode.SHIP
        return when {
            o.rejectedReason != null ->
                TransferNotice(TransferNotice.Tone.BAD, "Not registered", "Refused: ${o.rejectedReason}. Fix it in Activity.")
            o.postedNow -> TransferNotice(
                TransferNotice.Tone.OK,
                (if (shipped) "Shipped" else "Received") + (o.documentId?.let { " · $it" } ?: ""),
                what,
            )
            o.postedLive -> TransferNotice(TransferNotice.Tone.OK, "Sent to D365FO", "$what · posts in a few minutes")
            o.held -> TransferNotice(TransferNotice.Tone.WARN, "Kept on this device", "$what · posting is off in Settings")
            else -> TransferNotice(TransferNotice.Tone.INFO, "Saved on this device", "$what · sends when D365FO answers", retry = true)
        }
    }

    /** "Retry now" on a posting that is saved but not sent - see ReceivingFlowViewModel.retryPost. */
    fun retryPost() {
        val transferId = _state.value.lastPost?.transferId ?: return
        viewModelScope.launch {
            syncQueue.retryNow(transferId)
            syncScheduler.requestImmediatePush()
        }
    }

    // -- auto-post, undo, keypad, out of stock (redesign phase 5) -----------------------------

    /** The countdown from the last line to its posting - see [AUTO_POST_DELAY_MS]. */
    private var autoPost: Job? = null

    /** After every change to the banked lines: start, restart or stop the countdown. */
    private fun maybeAutoPost() {
        autoPost?.cancel()
        autoPost = null
        val s = _state.value
        if (s.posting || s.error != null || !transferComplete(s, ::sessionRemaining)) {
            _state.update { it.copy(autoPostAt = null) }
            return
        }
        _state.update { it.copy(autoPostAt = System.currentTimeMillis() + AUTO_POST_DELAY_MS) }
        autoPost = viewModelScope.launch {
            delay(AUTO_POST_DELAY_MS)
            postNow()
        }
    }

    /** True while the last line's countdown is running - Back then posts at once instead of leaving. */
    val autoPostPending: Boolean get() = autoPost?.isActive == true

    /** Back during the countdown: post now and stay until it has gone (it is never left behind). */
    fun postPendingNow() {
        if (!autoPostPending) return
        autoPost?.cancel()
        autoPost = null
        viewModelScope.launch { postNow() }
    }

    /** What Undo (F3, or the band's Undo) would take back - nothing while a posting is going. */
    val canUndo: Boolean
        get() = _state.value.let {
            !it.posting && !it.busy && (it.capture.line != null || it.captured.isNotEmpty() || it.outOfStock != null)
        }

    /**
     * Undo replaces "are you sure?" (brief §3): the line in hand is dropped, back to Scan item;
     * otherwise the last banked line is removed (unpicked in D365FO first when it was picked).
     */
    fun undo() {
        val s = _state.value
        if (s.posting || s.busy) return
        pending?.cancel()
        autoPost?.cancel()
        autoPost = null
        gs1Answers.clear()
        labelAnswer = null
        shortOf = null
        if (s.outOfStock != null || s.capture.line != null) {
            val item = s.outOfStock?.itemId ?: s.capture.line?.itemId
            _state.update {
                it.copy(
                    capture = nextCapture(it.capture), step = TransferStep.ITEM, outOfStock = null,
                    suggestedQuantity = null, stockHint = null, lineChoices = emptyList(),
                    busy = false, error = null, autoPostAt = null,
                    notice = TransferNotice(TransferNotice.Tone.INFO, "Undone", "$item not added - scan it again"),
                )
            }
            binScanned = false
            lineStock = null
            return
        }
        val index = s.captured.lastIndex.takeIf { it >= 0 } ?: return
        val last = s.captured[index]
        changePicked(last, last.quantity.negate()) {
            _state.update {
                it.copy(
                    captured = it.captured.filterNot { c -> c.id == last.id },
                    setAside = it.setAside - last.lineNumber,
                    error = null, autoPostAt = null,
                    notice = TransferNotice(
                        TransferNotice.Tone.INFO, "Removed ${last.itemId}",
                        com.d365fo.mobileops.util.Formatters.qtyUnit(last.quantity, last.unitSymbol) +
                            (if (s.capture.mode == TransferMode.SHIP) " not shipped" else " not received") + " - scan it again",
                    ),
                )
            }
            persist()
            maybeAutoPost()
        }
    }

    /** What the keypad (F4, or tapping the quantity) changes: the quantity step, the line in hand, or the last banked line. */
    val keypadValue: BigDecimal?
        get() = _state.value.let { s ->
            when {
                s.posting -> null
                s.step == TransferStep.QUANTITY -> s.suggestedQuantity ?: BigDecimal.ZERO
                s.capture.line != null -> s.capture.quantity
                else -> s.captured.lastOrNull()?.takeIf { it.serialIds.isEmpty() }?.quantity
            }
        }

    /** Why [value] can't be the keypad's quantity, or null when it can. */
    fun keypadError(value: String): String? {
        val s = _state.value
        if (s.step == TransferStep.QUANTITY || s.capture.line != null) return checkQuantity(value.trim()).exceptionOrNull()?.message
        val last = s.captured.lastOrNull() ?: return "Nothing to change"
        val q = value.trim().toBigDecimalOrNull() ?: return "\"$value\" is not a number"
        if (q.signum() <= 0) return "Quantity must be more than zero - Undo removes the line"
        last.maxQuantity?.let { if (q > it) return "Only ${plain(it)} open" }
        return null
    }

    fun applyKeypad(value: String) {
        if (keypadError(value) != null) return
        val s = _state.value
        when {
            s.step == TransferStep.QUANTITY -> onManualEntry(value)
            s.capture.line != null -> {
                val q = checkQuantity(value.trim()).getOrNull() ?: return
                _state.update { it.copy(capture = it.capture.copy(quantity = q)) }
            }
            else -> {
                val q = value.trim().toBigDecimal()
                val last = s.captured.last()
                changePicked(last, q - last.quantity) {
                    _state.update {
                        it.copy(
                            captured = it.captured.map { c -> if (c.id == last.id) c.copy(quantity = q) else c },
                            // Changed by hand: no longer "short because of stock" - the worker decided.
                            setAside = it.setAside - last.lineNumber,
                            notice = TransferNotice(
                                TransferNotice.Tone.OK,
                                (if (it.capture.mode == TransferMode.SHIP) "Picked " else "Received ") +
                                    com.d365fo.mobileops.util.Formatters.qtyUnit(q, last.unitSymbol),
                                "${last.itemId} · changed on the keypad",
                                undoable = true,
                            ),
                        )
                    }
                    persist()
                    maybeAutoPost()
                }
            }
        }
    }

    /** Out of stock, Skip line: the line stays on the order; the rest of the transfer goes on. */
    fun skipOutOfStock() {
        val oos = _state.value.outOfStock ?: return
        _state.update {
            it.copy(
                outOfStock = null,
                setAside = it.setAside + oos.lineNumber,
                capture = nextCapture(it.capture),
                step = TransferStep.ITEM,
                notice = TransferNotice(TransferNotice.Tone.INFO, "Skipped ${oos.itemId}", "Left on the order - not shipped"),
            )
        }
        binScanned = false
        lineStock = null
        maybeAutoPost()
    }

    /** Out of stock, Ship without it: skip the line and ship what is picked now. */
    fun shipWithoutIt() {
        val oos = _state.value.outOfStock ?: return
        if (_state.value.captured.isEmpty()) return skipOutOfStock()
        _state.update { it.copy(outOfStock = null, setAside = it.setAside + oos.lineNumber, capture = nextCapture(it.capture), step = TransferStep.ITEM) }
        postTransfer()
    }

    /**
     * Another transfer scanned with work banked here: that work ships or posts first - the work
     * ended - then the new order opens (brief §3: scanning the next document posts).
     */
    private suspend fun switchToOrder(transferId: String) {
        autoPost?.cancel()
        autoPost = null
        if (_state.value.captured.isNotEmpty()) postNow()
        val keep = _state.value
        _state.value = TransferFlowState(capture = TransferCapture(mode = keep.capture.mode), lastPost = keep.lastPost, busy = true)
        binScanned = false
        lineStock = null
        lastRaw = null
        onTransferOrder(transferId)
        _state.update { it.copy(busy = false) }
    }

    /** See ReceivingFlowViewModel.persist: fire-and-forget, never blocking a scan. */
    private fun persist() {
        val state = _state.value
        val capture = state.capture
        val transferId = capture.transferId ?: return
        val lines = state.captured.map { it.toStored() }
        val warehouse = if (capture.mode == TransferMode.SHIP) {
            capture.fromWarehouseId
        } else {
            capture.toWarehouseId
        }
        val user = sessions.session.value?.userName

        // ATOMIC + NonCancellable: the save runs to the end even when the screen closes right
        // after the scan - a cancelled write is a scanned line that silently isn't there later.
        viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.ATOMIC) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                flowSessions.save(flowTypeFor(capture.mode), transferId, warehouse, user, lines)
            }
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    // -- steps ---------------------------------------------------------------------------

    private suspend fun onTransferOrder(value: String) {
        val mode = _state.value.capture.mode
        val atWarehouse = sessions.session.value?.selected?.warehouseId

        when (val result = transfers.lookupOrder(value, mode, atWarehouse)) {
            is NetworkResult.Error -> fail(result.failure.message)
            NetworkResult.Loading -> Unit
            is NetworkResult.Success -> {
                val order = result.data

                if (!order.actionable) {
                    return fail(
                        when (order.blockReason) {
                            // The commonest mistake in this flow, and worth naming precisely:
                            // "nothing to do" would send the operator hunting a data problem.
                            "WRONG_WAREHOUSE" -> if (mode == TransferMode.SHIP) {
                                "${order.transferId} ships from ${order.fromWarehouseId}, " +
                                    "not from here"
                            } else {
                                "${order.transferId} is bound for ${order.toWarehouseId}, " +
                                    "not here"
                            }
                            "NOT_SHIPPABLE" ->
                                "${order.transferId} cannot be shipped — it is ${order.transferStatus}"
                            // "Received" when D365FO says so; otherwise not shipped yet. "Nothing in
                            // transit yet" read wrong for an order already received in full.
                            "NOT_RECEIVABLE" -> if (order.transferStatus.equals("Received", ignoreCase = true)) {
                                "${order.transferId} is already received"
                            } else {
                                "${order.transferId} isn't shipped yet"
                            }
                            else -> "${order.transferId} cannot be worked right now"
                        },
                    )
                }

                // A wall-to-wall count freezes the warehouse this end moves stock in.
                freezes.frozenMessage(if (mode == TransferMode.SHIP) order.fromWarehouseId else order.toWarehouseId)
                    ?.let { return fail(it) }

                if (order.outstandingLineCount == 0) {
                    return fail(
                        if (mode == TransferMode.SHIP) {
                            "${order.transferId} has nothing left to ship"
                        } else {
                            "${order.transferId} has nothing left to receive"
                        },
                    )
                }

                val open = order.lines.filter { it.hasOutstanding }
                // Anything banked against this order and direction comes back with it.
                val resumed = flowSessions
                    .restore(flowTypeFor(mode), order.transferId)
                    .map { line -> line.toCapturedTransfer() }
                _state.update {
                    it.copy(
                        order = order,
                        openLines = open,
                        capture = (if (mode == TransferMode.SHIP) order.defaultIssueLocation else order.defaultReceiptLocation)
                            .let { default ->
                                TransferCapture(
                                    transferId = order.transferId,
                                    mode = mode,
                                    fromWarehouseId = order.fromWarehouseId,
                                    toWarehouseId = order.toWarehouseId,
                                    requireLocationCaptureFallback = order.requireLocationCapture,
                                    defaultLocationId = default,
                                    locationId = default,
                                )
                            },
                        captured = resumed,
                        setAside = emptySet(),
                        error = null,
                    )
                }

                // Only when the lines are in hand: on a large order the count may be one but the
                // line itself has not been fetched, so the operator still scans. Otherwise the
                // item comes next in both directions - shipping no longer asks the bin first:
                // the location with stock is picked for the item (redesign brief §3).
                if (order.linesInline && open.size == 1 && resumed.isEmpty()) {
                    scanFeedback.success()
                    selectLine(open.single())
                } else {
                    scanFeedback.success()
                    _state.update { it.copy(step = TransferStep.ITEM) }
                }
            }
        }
    }

    /**
     * Answers carried by a GS1 barcode (count, batch, serial), applied as the flow reaches each
     * step - one GS1 scan can answer the item, quantity, batch and serial. Cleared per line.
     */
    private val gs1Answers = HashMap<TransferStep, String>()

    /** The item step: a GS1 element string first (GTIN + data), otherwise item number or barcode. */
    private suspend fun onItem(raw: String) {
        val gs1 = com.d365fo.mobileops.domain.model.Gs1Parser.parse(raw) ?: return onItemCode(raw)
        gs1Answers.clear()
        gs1.quantity?.let { gs1Answers[TransferStep.QUANTITY] = it.stripTrailingZeros().toPlainString() }
        gs1.batch?.let { gs1Answers[TransferStep.BATCH] = it }
        gs1.serial?.let { gs1Answers[TransferStep.SERIAL] = it }
        // The GTIN is the item's barcode in D365FO, stored as GTIN-14 or shortened to 13/12/8.
        val code = gs1.gtinCandidates.firstOrNull { candidate ->
            (lookups.lookupBarcode(candidate) as? NetworkResult.Success)?.data != null
        } ?: run {
            gs1Answers.clear()
            return fail("Unknown barcode ${gs1.gtin}")
        }
        onItemCode(code)
    }

    private suspend fun onItemCode(value: String) {
        linesFor(value)?.let { return chooseAmong(it) }

        // See the identical fast-fail on ReceivingFlowViewModel.onItem and the same reasoning:
        // an already-fully-captured item's own number falling through to the barcode-lookup
        // fallback can only ever end in "not a known barcode", after paying for a full network
        // round trip first.
        if (_state.value.order?.linesInline == true &&
            _state.value.openLines.any { it.itemId.equals(value, ignoreCase = true) }
        ) {
            return fail("$value is already done")
        }

        when (val result = lookups.lookupBarcode(value)) {
            is NetworkResult.Error -> fail(result.failure.message)
            NetworkResult.Loading -> Unit
            is NetworkResult.Success -> {
                val match = result.data
                    ?: return fail("$value is not on this order")
                val matched = linesFor(match.itemNumber)
                    ?: return fail(
                        "${match.itemNumber} is not outstanding on " +
                            "${_state.value.capture.transferId}",
                    )
                val ofVariant = matched.filter { it.variant.acceptsScanOf(match.variant) }
                if (ofVariant.isEmpty()) {
                    return fail("${match.itemNumber} ${match.variant.label} is not open on this order")
                }
                chooseAmong(ofVariant)
            }
        }
    }

    /** Lines for an item, from memory on a small order or one keyed query on a large one. */
    private suspend fun linesFor(itemNumber: String): List<TransferOrderLine>? {
        val state = _state.value
        val transferId = state.capture.transferId ?: return null

        if (state.order?.linesInline == true) {
            return state.openLines
                .filter { it.itemId.equals(itemNumber, ignoreCase = true) && sessionRemaining(it).signum() > 0 }
                .takeIf { it.isNotEmpty() }
        }

        return when (
            val result = transfers.lookupLinesForItem(transferId, itemNumber, state.capture.mode)
        ) {
            is NetworkResult.Error -> {
                fail(result.failure.message)
                null
            }
            NetworkResult.Loading -> null
            is NetworkResult.Success ->
                result.data.filter { it.hasOutstanding && sessionRemaining(it).signum() > 0 }
                    .takeIf { it.isNotEmpty() }
        }
    }

    /** Recomputes the open line's outstanding quantity after the banked list changed. */
    private fun refreshSuggested() {
        val line = _state.value.capture.line ?: return
        _state.update { it.copy(suggestedQuantity = sessionRemaining(line)) }
    }

    /**
     * What [line] still has left *right now*, in the middle of this in-progress flow - see the
     * identical ReceivingFlowViewModel.sessionRemaining and the same reasoning:
     * [TransferOrderLine.remainingQuantity] is a server-computed snapshot from when this order
     * was looked up, so it knows nothing about a line this same flow already banked into
     * [TransferFlowState.captured] a moment ago and has not posted or queued yet.
     */
    fun sessionRemaining(line: TransferOrderLine): BigDecimal =
        (line.remainingQuantity - capturedThisSessionFor(line)).coerceAtLeast(BigDecimal.ZERO)

    private fun capturedThisSessionFor(line: TransferOrderLine): BigDecimal =
        _state.value.captured
            .filter { it.lineNumber == line.lineNumber }
            .fold(BigDecimal.ZERO) { total, entry -> total + entry.quantity }

    /** What this session already banked of [batchId] on [line]. */
    private fun scannedOfBatch(line: TransferOrderLine, batchId: String): BigDecimal =
        _state.value.captured
            .filter { it.lineNumber == line.lineNumber && it.batchId.equals(batchId, ignoreCase = true) }
            .fold(BigDecimal.ZERO) { total, entry -> total + entry.quantity }

    private suspend fun chooseAmong(lines: List<TransferOrderLine>) {
        val searched = takeSearchedLine()?.let { number -> lines.firstOrNull { it.lineNumber == number } }
        if (searched != null) {
            selectLine(searched)
            return
        }
        if (lines.size > 1) {
            _state.update { it.copy(lineChoices = lines) }
        } else {
            selectLine(lines.single())
        }
    }

    fun chooseLine(line: TransferOrderLine) {
        pending?.cancel()
        pending = viewModelScope.launch {
            _state.update { it.copy(lineChoices = emptyList(), busy = true) }
            selectLine(line)
            drainLabelAnswers()
            _state.update { it.copy(busy = false) }
        }
    }

    private suspend fun selectLine(line: TransferOrderLine) {
        _state.update {
            it.copy(
                capture = it.capture.copy(
                    line = line,
                    unitSymbol = line.unitId,
                    // Shipping: the bin scanned before the item (or the warehouse's default issue
                    // bin) stays, unless the item tracks none - autoFill may pick another, the
                    // one with stock. Receiving: the warehouse's default receipt bin when it has
                    // one (feedback 88c029a1) - the line's own location is the *source* bin,
                    // never where it goes - else the operator scans it.
                    locationId = if (it.capture.mode == TransferMode.SHIP) {
                        it.capture.locationId.takeIf { _ -> line.locationRequired }
                    } else {
                        it.capture.defaultLocationId?.takeIf { _ -> line.locationRequired }
                    },
                ),
                suggestedQuantity = sessionRemaining(line),
                entryUnit = null,
                error = null,
            )
        }
        if (!autoFill(line)) return
        advance()
    }

    // -- the scan is the confirm (redesign phase 5) ---------------------------------------------

    /** Shipping: the item's stock in the From warehouse, by location - kept for a scanned location. */
    private var lineStock: List<StockRow>? = null

    /** The line [bankCurrentLine] banks short on purpose - all the stock there is. */
    private var shortOf: Double? = null

    /**
     * Fills in what the worker would otherwise be asked (brief §3: ask only when there is a real
     * choice). The quantity: the label's count (GS1 AI 30/37), else everything still open on the
     * line. Shipping: the one location with stock, and its one batch - none in stock stops here
     * with Skip line / Ship without it. Receiving: the batch from the shipment and the warehouse's
     * receipt location. F4 or tapping the quantity changes it. False when the line stopped.
     */
    private suspend fun autoFill(line: TransferOrderLine): Boolean {
        val label = gs1Answers.remove(TransferStep.QUANTITY)
        val labelBatch = gs1Answers[TransferStep.BATCH]
        val open = sessionRemaining(line)
        if (open.signum() <= 0) return true
        var cap: BigDecimal? = null
        val capture = _state.value.capture

        if (capture.mode == TransferMode.SHIP) {
            val rows = stockFor(line, capture.fromWarehouseId)
            lineStock = rows
            if (rows != null) {
                val plan = planShipPick(
                    rows = rows,
                    preferredLocation = capture.locationId,
                    locationScanned = binScanned,
                    locationTracked = line.locationRequired,
                    batchTracked = line.batchRequired,
                    lineBatch = line.lineBatchId,
                    labelBatch = labelBatch,
                )
                when (plan) {
                    is ShipPick.OutOfStock -> {
                        scanFeedback.error()
                        gs1Answers.clear()
                        _state.update {
                            it.copy(
                                outOfStock = OutOfStock(line.lineNumber, line.itemId, plan.at ?: capture.fromWarehouseId),
                                capture = nextCapture(it.capture).copy(locationId = if (binScanned) it.capture.locationId else it.capture.defaultLocationId),
                                step = TransferStep.ITEM,
                                suggestedQuantity = null,
                            )
                        }
                        lineStock = null
                        return false
                    }
                    is ShipPick.Ready -> {
                        _state.update {
                            it.copy(
                                capture = it.capture.copy(
                                    // No location from the stock and none to offer (stock not
                                    // kept by bin): the bin already in hand stays.
                                    locationId = when {
                                        !line.locationRequired -> it.capture.locationId
                                        plan.locationId != null -> plan.locationId
                                        plan.askLocations.isEmpty() -> it.capture.locationId
                                        else -> null
                                    },
                                    batchId = if (labelBatch == null) plan.batchId else null,
                                ),
                                stockHint = plan.askLocations.takeIf { l -> l.isNotEmpty() }?.joinToString(" · ") { (loc, q) -> "$loc (${plain(q)})" },
                            )
                        }
                        cap = plan.available
                    }
                }
            }
        } else if (line.batchRequired) {
            // Receiving: which batch came on the truck is known from the shipment.
            val left = line.shippedBatches.map { it to (it.quantity - scannedOfBatch(line, it.batchId)) }.filter { it.second.signum() > 0 }
            if (labelBatch != null) {
                cap = left.firstOrNull { it.first.batchId.equals(labelBatch, ignoreCase = true) }?.second
            } else {
                left.firstOrNull()?.let { (batch, qty) ->
                    _state.update { it.copy(capture = it.capture.copy(batchId = batch.batchId)) }
                    cap = qty
                }
            }
        }

        val full = cap?.let { open.min(it) } ?: open
        val proposed = label ?: plain(full)
        val problem = checkQuantity(proposed).exceptionOrNull()?.message
        if (problem != null) {
            if (label != null) notice(TransferNotice.Tone.WARN, "Label says $label", "$problem - enter the quantity")
            return true
        }
        val quantity = BigDecimal(proposed)
        // Short because the stock is: banked with a word, and the rest left on the order.
        if (capture.mode == TransferMode.SHIP && label == null && quantity < open) shortOf = line.lineNumber
        _state.update { it.copy(capture = it.capture.copy(quantity = quantity)) }
        return true
    }

    /** The item's available stock at [warehouseId], by location; null when it can't be read (offline). */
    private suspend fun stockFor(line: TransferOrderLine, warehouseId: String?): List<StockRow>? {
        warehouseId ?: return null
        val v = line.variant
        val result = orderCreation.stock(
            line.itemId,
            com.d365fo.mobileops.data.repository.OrderVariant(v.configId.orEmpty(), v.sizeId.orEmpty(), v.colorId.orEmpty(), v.styleId.orEmpty()),
            warehouseId,
            byLocation = line.locationRequired,
        )
        return (result as? NetworkResult.Success)?.data
    }

    /**
     * Another transfer order (same number series, "BSP-TO-...") typed mid-order - a scanned one
     * comes through [onRoute] as a switch. Opens that order in the same direction, after
     * shipping or posting what is banked on this one - see [switchToOrder].
     */
    private suspend fun switchedOrder(value: String): Boolean {
        val current = _state.value.capture.transferId ?: return false
        val prefix = current.substringBeforeLast('-', missingDelimiterValue = "").takeIf { it.isNotBlank() } ?: return false
        if (!value.startsWith("$prefix-", ignoreCase = true) || value.equals(current, ignoreCase = true)) return false
        autoPost?.cancel()
        autoPost = null
        if (_state.value.captured.isNotEmpty()) postNow()
        val keep = _state.value
        _state.value = TransferFlowState(capture = TransferCapture(mode = keep.capture.mode), lastPost = keep.lastPost, busy = true)
        binScanned = false
        lineStock = null
        onTransferOrder(value)
        return true
    }

    /**
     * The line's batch label scanned at the quantity step - read as a quantity ('"AN9U" is not a
     * number', TO-36). Known batches only: the one shipped on the line, or the one it was made for.
     */
    private fun isScanOfLineBatch(value: String): Boolean {
        val line = _state.value.capture.line ?: return false
        val known = line.shippedBatches.map { it.batchId } + listOfNotNull(line.lineBatchId)
        if (known.none { it.equals(value, ignoreCase = true) }) return false
        notice(TransferNotice.Tone.WARN, "Enter the quantity first", "$value is the batch - F4 for the quantity")
        return true
    }

    /** See ReceivingFlowViewModel.isScanOfCurrentItem - the same single-line case on a transfer. */
    private suspend fun isScanOfCurrentItem(value: String): Boolean {
        val line = _state.value.capture.line ?: return false
        if (value.length < 8 && value.all { it.isDigit() || it == '.' }) return false
        val same = value.equals(line.itemId, ignoreCase = true) ||
            ((lookups.lookupBarcode(value) as? NetworkResult.Success)?.data?.itemNumber?.equals(line.itemId, ignoreCase = true) == true)
        if (same) notice(TransferNotice.Tone.WARN, "Enter the quantity", "Already scanned - F4 or tap the quantity")
        return same
    }

    private fun onQuantity(value: String) {
        val entered = checkQuantity(value).getOrElse { return fail(it.message ?: "\"$value\" is not a number") }
        _state.update { it.copy(capture = it.capture.copy(quantity = entered), error = null) }
        advance()
    }

    /**
     * Why [value] cannot be the current line's quantity, or null when it can. The keypad screen
     * asks this before it closes, so a refused quantity is said there, under the number, and the
     * pad stays open.
     */
    fun quantityError(value: String): String? =
        if (_state.value.step != TransferStep.QUANTITY) null else checkQuantity(value.trim()).exceptionOrNull()?.message

    private fun checkQuantity(value: String): Result<BigDecimal> {
        val line = _state.value.capture.line ?: return Result.failure(IllegalStateException("Pick a line first"))
        // "3", or "3 BOX" / a unit chip: converted to the line's unit per the item's conversions.
        val entered = com.d365fo.mobileops.domain.model.UnitConversion
            .parse(value, line.units, _state.value.entryUnit, line.unitId)
            .getOrElse { return Result.failure(IllegalArgumentException(it.message ?: "\"$value\" is not a number")) }
            .quantity
        if (entered.signum() <= 0) return Result.failure(IllegalArgumentException("Quantity must be more than zero"))

        if (line.serialRequired && entered.stripTrailingZeros().scale() > 0) {
            return Result.failure(IllegalArgumentException("Whole units only (serial item)"))
        }

        // The ceiling is what is outstanding in this direction, which the server decided - minus
        // whatever this same in-progress flow already banked against this line a moment ago and
        // has not posted or queued yet. See sessionRemaining.
        val maximum = sessionRemaining(line)
        if (entered > maximum) {
            val mode = _state.value.capture.mode
            return Result.failure(
                IllegalArgumentException(
                    if (mode == TransferMode.SHIP) {
                        "Only ${plain(maximum)} left to ship"
                    } else {
                        "Only ${plain(maximum)} left to receive"
                    },
                ),
            )
        }
        return Result.success(entered)
    }

    /** Picks the unit the quantity step reads its number in; the line's own unit clears it. */
    fun selectEntryUnit(unitId: String) = _state.update { s ->
        val lineUnit = s.capture.line?.unitId
        s.copy(entryUnit = unitId.takeUnless { lineUnit != null && it.equals(lineUnit, ignoreCase = true) })
    }

    private suspend fun onLocation(value: String) {
        // Shipping picks from a bin of the From warehouse; receiving puts away into the To one.
        val shipping = _state.value.capture.mode == TransferMode.SHIP
        val warehouse = (if (shipping) _state.value.capture.fromWarehouseId else _state.value.capture.toWarehouseId)
            ?: return fail(if (shipping) "This transfer has no From warehouse" else "This transfer has no destination warehouse")

        when (val result = lookups.lookupLocation(warehouse, value)) {
            // No connection: taken as scanned and left to D365FO, which checks it again when the
            // posting arrives - a wrong one comes back in Activity rather than stopping work here.
            is NetworkResult.Error -> if (result.failure.retryable) {
                _state.update { it.copy(capture = it.capture.copy(locationId = value), error = null) }
                advance()
            } else {
                fail(result.failure.message)
            }
            NetworkResult.Loading -> Unit
            is NetworkResult.Success -> {
                val location = result.data
                    ?: return fail("No location $value in warehouse $warehouse")
                if (!shipping && !location.canReceiveInto) {
                    return fail("$value is not available for put-away")
                }
                // Shipping, the location asked because several have stock: its own batch and
                // quantity now, as for the one location with stock.
                if (shipping && !applyStockAt(location.locationId)) return
                _state.update {
                    it.copy(capture = it.capture.copy(locationId = location.locationId), error = null, stockHint = null)
                }
                advance()
            }
        }
    }

    /** Shipping: the batch and quantity at a scanned location, from the stock already read. False: none there. */
    private fun applyStockAt(locationId: String): Boolean {
        val line = _state.value.capture.line ?: return true
        val rows = lineStock ?: return true
        val plan = planShipPick(
            rows = rows,
            preferredLocation = locationId,
            locationScanned = true,
            locationTracked = line.locationRequired,
            batchTracked = line.batchRequired,
            lineBatch = line.lineBatchId,
            labelBatch = gs1Answers[TransferStep.BATCH],
        )
        if (plan is ShipPick.OutOfStock) {
            fail("No ${line.itemId} in stock at $locationId")
            return false
        }
        plan as ShipPick.Ready
        val open = sessionRemaining(line)
        val q = plan.available?.let { open.min(it) } ?: open
        shortOf = if (q < open) line.lineNumber else null
        _state.update {
            it.copy(
                capture = it.capture.copy(
                    batchId = it.capture.batchId ?: plan.batchId,
                    quantity = if (gs1Answers.containsKey(TransferStep.QUANTITY)) it.capture.quantity else q.takeIf { v -> v.signum() > 0 } ?: it.capture.quantity,
                ),
            )
        }
        return true
    }

    /**
     * Unlike PO receiving, a transfer batch is never new - it is existing stock moving between
     * warehouses, not arriving from a vendor for the first time, and [TransferStep] has no
     * expiry-date step to fall back on for one that is. So this rejects an unregistered batch
     * outright rather than branching into a new-batch capture.
     */
    private suspend fun onBatch(value: String) {
        var line = _state.value.capture.line ?: return fail("Pick a line first")

        // Receiving: the batch must be one shipped on this line, in no more than was shipped of
        // it. A batch shipped on another line of the same item - a line D365FO split by batch
        // when it was shipped - switches to that line.
        if (_state.value.capture.mode == TransferMode.RECEIVE && line.shippedBatches.isNotEmpty()) {
            val quantity = _state.value.capture.quantity ?: BigDecimal.ZERO
            if (line.shippedBatches.none { it.batchId.equals(value, ignoreCase = true) }) {
                _state.value.openLines.firstOrNull { other ->
                    other.lineNumber != line.lineNumber &&
                        other.itemId.equals(line.itemId, ignoreCase = true) &&
                        other.variant == line.variant &&
                        other.shippedBatches.any { it.batchId.equals(value, ignoreCase = true) } &&
                        sessionRemaining(other) >= quantity
                }?.let { other ->
                    line = other
                    _state.update { it.copy(capture = it.capture.copy(line = other, unitSymbol = other.unitId)) }
                }
            }
            when (val check = checkShippedBatch(line, value, quantity, scannedOfBatch(line, value))) {
                ShippedBatchCheck.Ok -> Unit
                is ShippedBatchCheck.NotShipped -> return fail(
                    "Batch $value wasn't shipped. Shipped: " + check.shipped.joinToString { "${it.batchId} (${plain(it.quantity)})" },
                )
                is ShippedBatchCheck.TooMany -> return fail(
                    "Only ${plain(check.shipped)} of batch $value shipped" +
                        (if (check.alreadyScanned.signum() > 0) ", ${plain(check.alreadyScanned)} scanned" else ""),
                )
            }
            _state.update { it.copy(capture = it.capture.copy(batchId = value), error = null) }
            return advance()
        }

        // Shipping a line created for a chosen batch (TO creation's "which stock?"): another
        // batch was picked without a word (AN9U on TO-35, ordered from HSB-PAN-001). Warned
        // once; the same batch scanned again is taken - stock does get picked from another lot.
        val ordered = line.lineBatchId
        if (_state.value.capture.mode == TransferMode.SHIP && ordered != null && !ordered.equals(value, ignoreCase = true)) {
            if (!value.equals(otherBatchWarned, ignoreCase = true)) {
                otherBatchWarned = value
                return fail("Line wants batch $ordered - scan $value again to use it")
            }
        }
        otherBatchWarned = null

        when (val result = lookups.isBatchRegistered(line.itemId, value)) {
            // No connection: taken as scanned and left to D365FO, which checks it again when the
            // posting arrives - a wrong one comes back in Activity rather than stopping work here.
            is NetworkResult.Error -> if (!result.failure.retryable) return fail(result.failure.message)
            NetworkResult.Loading -> return
            is NetworkResult.Success -> if (!result.data) {
                return fail("Unknown batch $value")
            }
        }

        _state.update { it.copy(capture = it.capture.copy(batchId = value), error = null) }
        advance()
    }

    private suspend fun onSerial(value: String) {
        val capture = _state.value.capture
        val line = capture.line ?: return fail("Pick a line first")

        if (capture.serialIds.any { it.equals(value, ignoreCase = true) }) {
            return fail("$value has already been scanned")
        }

        // Only checked when receiving. A serial being shipped is already registered by
        // definition — it is the stock leaving — so uniqueness would refuse every valid scan.
        if (capture.mode == TransferMode.RECEIVE) {
            when (val result = lookups.isSerialRegistered(line.itemId, value)) {
                // No connection: taken as scanned - D365FO refuses a duplicate when the posting arrives.
                is NetworkResult.Error -> if (!result.failure.retryable) return fail(result.failure.message)
                NetworkResult.Loading -> return
                is NetworkResult.Success -> Unit
            }
        }

        // Shipping: the serial must be in stock at the From warehouse. It was not checked at all,
        // and D365FO picked a serial that does not exist there (ZZSER1 on TO-39, 10 Oct) - the
        // item allows negative stock. Offline it is taken as scanned, as before.
        if (capture.mode == TransferMode.SHIP) {
            val from = capture.fromWarehouseId
            if (from != null) {
                val v = line.variant
                val stock = orderCreation.stock(
                    line.itemId,
                    com.d365fo.mobileops.data.repository.OrderVariant(v.configId.orEmpty(), v.sizeId.orEmpty(), v.colorId.orEmpty(), v.styleId.orEmpty()),
                    from,
                )
                if (stock is NetworkResult.Success && stock.data.none { it.serialId.equals(value, ignoreCase = true) && it.available > 0 }) {
                    return fail("Serial $value is not in stock at $from")
                }
            }
        }

        _state.update {
            it.copy(capture = it.capture.copy(serialIds = it.capture.serialIds + value), error = null)
        }
        advance()
    }

    /**
     * Moves to the next unanswered field - or, once every field on this line is answered, banks
     * it straight to [TransferFlowState.captured] and reopens the item prompt for the next scan.
     * The scan is the confirm; Undo (F3) and the keypad (F4) are the corrections.
     */
    private fun advance() {
        val next = nextIncompleteTransferStep(_state.value.capture)
        if (next == TransferStep.CONFIRM) {
            gs1Answers.clear()
            labelAnswer = null
            bankCurrentLine()
        } else {
            scanFeedback.success()
            _state.update { it.copy(step = next) }
            // A GS1 scan already answered this step: drainLabelAnswers applies it next, in the
            // job that is running now.
            labelAnswer = gs1Answers.remove(next)
        }
    }

    private fun fail(message: String) {
        scanFeedback.error()
        _state.update { it.copy(error = message) }
    }

    private fun plain(value: BigDecimal) = value.stripTrailingZeros().toPlainString()
}

// -- pure rules (unit-tested: TransferRedesignTest) ---------------------------------------------

/** Where a shipped line is picked from, from the item's stock - see [planShipPick]. */
internal sealed interface ShipPick {
    /** Nothing available - at [at] when a location was scanned, else anywhere in the warehouse. */
    data class OutOfStock(val at: String?) : ShipPick

    /**
     * Pick from [locationId] (null: not chosen - ask, from [askLocations]), batch [batchId] when
     * only one is possible, up to [available] (null: unknown).
     */
    data class Ready(
        val locationId: String?,
        val batchId: String?,
        val available: BigDecimal?,
        val askLocations: List<Pair<String, BigDecimal>> = emptyList(),
    ) : ShipPick
}

/**
 * The redesign's "pick the location with stock" (brief §4, Ship transfer), pure. A scanned bin is
 * the worker's word: it is used, or it has none. Otherwise the warehouse's default issue bin when
 * it has stock, else the one location that has, else the locations are offered (most first).
 * The batch: the label's, else the line's own (TO creation's chosen batch) when it is there,
 * else the only one at that location.
 */
internal fun planShipPick(
    rows: List<StockRow>,
    preferredLocation: String?,
    locationScanned: Boolean,
    locationTracked: Boolean,
    batchTracked: Boolean,
    lineBatch: String?,
    labelBatch: String?,
): ShipPick {
    var inStock = rows.filter { it.available > 0 }
    if (batchTracked && labelBatch != null) {
        inStock.filter { it.batchId.equals(labelBatch, ignoreCase = true) }.takeIf { it.isNotEmpty() }?.let { inStock = it }
    }
    if (inStock.isEmpty()) return ShipPick.OutOfStock(preferredLocation.takeIf { locationScanned })

    fun total(of: List<StockRow>) = of.fold(BigDecimal.ZERO) { t, r -> t + BigDecimal.valueOf(r.available) }
    fun batchOf(at: List<StockRow>): String? {
        if (!batchTracked) return null
        labelBatch?.let { return null } // the label's batch goes through the batch step's own checks
        val batches = at.map { it.batchId }.filter { it.isNotBlank() }.distinct()
        lineBatch?.let { lb -> batches.firstOrNull { it.equals(lb, ignoreCase = true) }?.let { return it } }
        return batches.singleOrNull()
    }
    fun availableOf(at: List<StockRow>, batch: String?) =
        total(if (batch != null) at.filter { it.batchId.equals(batch, ignoreCase = true) } else at)

    val byLocation = inStock.filter { it.locationId.isNotBlank() }.groupBy { it.locationId }
    if (!locationTracked || byLocation.isEmpty()) {
        val batch = batchOf(inStock)
        return ShipPick.Ready(null, batch, availableOf(inStock, batch))
    }

    val preferred = preferredLocation?.let { p -> byLocation.keys.firstOrNull { it.equals(p, ignoreCase = true) } }
    val chosen = when {
        preferred != null -> preferred
        locationScanned && preferredLocation != null -> return ShipPick.OutOfStock(preferredLocation)
        byLocation.size == 1 -> byLocation.keys.single()
        else -> null
    }
    if (chosen == null) {
        val offer = byLocation.map { (loc, at) -> loc to total(at) }.sortedByDescending { it.second }
        return ShipPick.Ready(null, null, null, askLocations = offer)
    }
    val at = byLocation.getValue(chosen)
    val batch = batchOf(at)
    return ShipPick.Ready(chosen, batch, availableOf(at, batch))
}

/**
 * Nothing left to scan on the transfer, so it ships or posts by itself. Small orders: every open
 * line has nothing left for this device, or was left on the order on purpose ([TransferFlowState.setAside]:
 * skipped for no stock, or shipped short). Large ones (lines fetched per item): as many lines
 * done as the order has outstanding.
 */
internal fun transferComplete(s: TransferFlowState, remainingFor: (TransferOrderLine) -> BigDecimal): Boolean {
    if (s.captured.isEmpty()) return false
    val order = s.order ?: return false
    if (order.linesInline && s.openLines.isNotEmpty()) {
        return s.openLines.all { it.lineNumber in s.setAside || remainingFor(it).signum() <= 0 }
    }
    val left = s.outstandingLineCount ?: return false
    val setAsideStillOpen = s.setAside.count { n ->
        s.captured.none { it.lineNumber == n } || s.captured.filter { it.lineNumber == n }.let { banked ->
            val max = banked.firstNotNullOfOrNull { it.maxQuantity }
            max == null || banked.fold(BigDecimal.ZERO) { t, c -> t + c.quantity } < max
        }
    }
    return left - setAsideStillOpen <= 0
}

data class TransferFlowState(
    val step: TransferStep = TransferStep.TRANSFER_ORDER,
    val capture: TransferCapture = TransferCapture(),
    val order: TransferOrderLookup? = null,
    val openLines: List<TransferOrderLine> = emptyList(),
    val lineChoices: List<TransferOrderLine> = emptyList(),
    val suggestedQuantity: BigDecimal? = null,
    /** The unit the quantity is being entered in; null = the line's own unit. */
    val entryUnit: String? = null,
    val captured: List<CapturedTransferLine> = emptyList(),
    val showingCaptured: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    /** What the last scan or action did, for the status band - see [TransferNotice]. */
    val notice: TransferNotice? = null,
    /** True while a posting runs - the band says so and nothing else moves. */
    val posting: Boolean = false,
    /** The last posting this screen made: the band's and send tray's result after it settles. */
    val lastPost: TransferPost? = null,
    /** When the last line's countdown posts (epoch ms) - null when none is running. */
    val autoPostAt: Long? = null,
    /** Shipping: the item just scanned has no stock - the band offers Skip line / Ship without it. */
    val outOfStock: OutOfStock? = null,
    /** Lines left on the order on purpose (skipped for no stock, shipped short) - not waited for. */
    val setAside: Set<Double> = emptySet(),
    /** Shipping, the location step: where the item has stock - "A-01 (5) · B-02 (3)". */
    val stockHint: String? = null,
) {
    /**
     * Lines still to scan: the server's count, less the lines this session has scanned in full.
     * The server's count alone still said "1 line outstanding" with both units of a 2-unit line
     * scanned (as 1 + 1, from two batches).
     */
    val outstandingLineCount: Int?
        get() {
            val order = order ?: return null
            val scannedByLine = captured.groupBy { it.lineNumber }
                .mapValues { (_, lines) -> lines.fold(BigDecimal.ZERO) { total, line -> total + line.quantity } }
            if (order.linesInline && openLines.isNotEmpty()) {
                return openLines.count { line ->
                    (line.remainingQuantity - (scannedByLine[line.lineNumber] ?: BigDecimal.ZERO)).signum() > 0
                }
            }
            val fullyScanned = captured.groupBy { it.lineNumber }.count { (lineNumber, lines) ->
                val ceiling = lines.firstNotNullOfOrNull { it.maxQuantity }
                ceiling != null && scannedByLine.getValue(lineNumber) >= ceiling
            }
            return (order.outstandingLineCount - fullyScanned).coerceAtLeast(0)
        }
}

/** Shipping: a line with no stock to pick from - [at] a scanned bin, or the From warehouse. */
data class OutOfStock(val lineNumber: Double, val itemId: String, val at: String?)

/**
 * What the last scan or action did, for the redesigned status band (phase 5) - a picked or
 * received line with its Undo, a wrong item, a posting's result. Cleared by the next scan; the
 * band then says what the current step needs. See ReceivingNotice, its PO twin.
 */
data class TransferNotice(
    val tone: Tone,
    val title: String,
    val line: String? = null,
    /** Offer Undo (for the band's Undo window). */
    val undoable: Boolean = false,
    /** Offer Retry now: queued, D365FO didn't answer in time. */
    val retry: Boolean = false,
    val id: Long = System.nanoTime(),
) {
    enum class Tone { INFO, OK, WARN, BAD }
}

/** What the last ship or receive posting did - the band's result and the send tray's last sent. */
data class TransferPost(
    val transferId: String,
    val mode: TransferMode,
    val lineCount: Int,
    val postedLive: Boolean,
    val rejectedReason: String?,
    val held: Boolean,
    val postedNow: Boolean,
    /** The shipment or receipt document D365FO answered with, when it did. */
    val documentId: String? = null,
)

/** A line banked in this session. In memory only, as with receiving, while posting does not exist. */
data class CapturedTransferLine(
    val lineNumber: Double,
    val itemId: String,
    val itemName: String,
    val quantity: BigDecimal,
    val unitSymbol: String,
    val locationId: String? = null,
    val batchId: String? = null,
    val serialIds: List<String> = emptyList(),
    /** "Config C1 · Size L" - which variant of a product master; blank for a plain item. */
    val variantLabel: String = "",
    /** The keypad's ceiling. Null for a line resumed from a saved session - unbounded. */
    val maxQuantity: BigDecimal? = null,
    /** Shipping: picked in D365FO when it was scanned (TransferPicker). */
    val picked: Boolean = false,
    /** It was already picked before this scan - by an earlier session or another device. */
    val pickedEarlier: Boolean = false,
    /** Tells banked lines apart while a live pick for one is in flight. */
    val id: String = java.util.UUID.randomUUID().toString(),
)

/** Storage shape of a banked transfer line. */
private fun CapturedTransferLine.toStored() = StoredLine(
    lineNumber = lineNumber,
    itemId = itemId,
    itemName = itemName,
    quantity = quantity.toPlainString(),
    unitSymbol = unitSymbol,
    locationId = locationId,
    batchId = batchId,
    serialIds = serialIds,
    picked = picked,
)

private fun StoredLine.toCapturedTransfer() = CapturedTransferLine(
    lineNumber = lineNumber,
    itemId = itemId,
    itemName = itemName,
    quantity = quantityValue,
    unitSymbol = unitSymbol,
    locationId = locationId,
    batchId = batchId,
    serialIds = serialIds,
    picked = picked,
)

/** The wait between the last line and the transfer shipping or posting by itself - see ReceivingFlowViewModel's. */
internal const val TRANSFER_AUTO_POST_DELAY_MS = com.d365fo.mobileops.presentation.common.components.UNDO_WINDOW_MS
private const val AUTO_POST_DELAY_MS = TRANSFER_AUTO_POST_DELAY_MS

/** How long a line picked in the item search stays the answer to "which line?". */
private const val SEARCHED_LINE_VALID_MS = 10_000L
