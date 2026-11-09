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
import com.d365fo.mobileops.domain.model.stepsForTransfer
import com.d365fo.mobileops.domain.repository.OnDemandLookupRepository
import com.d365fo.mobileops.presentation.common.scan.HidScannerBridge
import com.d365fo.mobileops.presentation.common.scan.ScanFeedback
import com.d365fo.mobileops.util.AppFailure
import com.d365fo.mobileops.util.NetworkResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.math.BigDecimal
import javax.inject.Inject

/** See [TransferFlowViewModel.lastRaw] - the window a repeated identical scan is ignored in. */
private const val DUPLICATE_SCAN_WINDOW_MS = 1500L

/** See the identical constant and doc on ReceivingFlowViewModel - the same live-post budget. */
private const val LIVE_POST_TIMEOUT_MS = 10_000L

/**
 * Drives the transfer flow, in whichever direction it was opened for.
 *
 * Structurally the receiving flow's twin, with one rule that is entirely its own: **the quantity
 * ceiling is what is outstanding in this direction**, and the server decides which that is. The
 * client must never look at the ordered quantity when receiving — a ten-unit line with four
 * shipped has four to receive, and offering ten would invite booking in stock that never left.
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

    init {
        viewModelScope.launch {
            scannerBridge.scans.collect { submit(it.value) }
        }
    }

    /** Set once when the screen opens; the direction never changes mid-flow. */
    fun setMode(mode: TransferMode) {
        if (_state.value.capture.mode == mode && _state.value.capture.transferId != null) return
        _state.value = TransferFlowState(capture = TransferCapture(mode = mode))

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

    fun onManualEntry(value: String) = submit(value)

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
    }

    fun onCameraDecoded(value: String) = scannerBridge.submitCameraScan(value)

    /** See [HidScannerBridge.justScanned] - used by the screen's BackHandler. */
    fun justScanned(): Boolean = scannerBridge.justScanned()

    fun setScannerInterception(enabled: Boolean) {
        scannerBridge.enabled = enabled
    }

    fun submit(raw: String) {
        val value = raw.trim()
        if (value.isEmpty()) return

        val now = android.os.SystemClock.elapsedRealtime()
        if (value == lastRaw && now - lastRawAtMs < DUPLICATE_SCAN_WINDOW_MS) return
        lastRaw = value
        lastRawAtMs = now

        pending?.cancel()
        pending = viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            when (_state.value.step) {
                TransferStep.TRANSFER_ORDER -> onTransferOrder(value)
                TransferStep.ITEM -> if (!switchedOrder(value)) onItem(value)
                TransferStep.QUANTITY -> if (!switchedOrder(value) && !isScanOfLineBatch(value) && !isScanOfCurrentItem(value)) onQuantity(value)
                TransferStep.LOCATION -> if (!switchedOrder(value)) onLocation(value)
                TransferStep.BATCH -> onBatch(value)
                TransferStep.SERIAL -> onSerial(value)
                TransferStep.CONFIRM -> Unit
            }
            _state.update { it.copy(busy = false) }
        }
    }

    fun clearLineNotice() = _state.update { it.copy(lineNotice = null) }

    fun back() {
        pending?.cancel()
        val current = _state.value
        val sequence = stepsForTransfer(current.capture)
        val index = sequence.indexOf(current.step)
        if (index <= 0) return

        val previous = sequence[index - 1]
        // Back to the order prompt starts over - see ReceivingFlowViewModel.back. The banked
        // lines are saved per order and come back when it is scanned again.
        val toOrder = previous == TransferStep.TRANSFER_ORDER
        _state.update {
            it.copy(
                capture = it.capture.cleared(previous),
                step = previous,
                captured = if (toOrder) emptyList() else it.captured,
                error = null,
                busy = false,
            )
        }
    }

    /** Banks the line in hand into [TransferFlowState.captured]. False when there is none. */
    private fun bankCurrentLine(): Boolean {
        pending?.cancel()
        val capture = _state.value.capture
        val line = capture.line ?: return false
        val quantity = capture.quantity ?: return false

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
            // What was outstanding on the line when this was banked - the ceiling for
            // adjustCapturedQuantity, so nudging a line up in the list can never book
            // more than the order actually has left.
            maxQuantity = line.remainingQuantity,
        )
        _state.update {
            it.copy(
                captured = it.captured + banked,
                lineNotice = com.d365fo.mobileops.presentation.common.components.QuickNoticeMessage(
                    id = System.nanoTime(),
                    text = (if (capture.mode == TransferMode.SHIP) "Picked " else "Received ") +
                        "${plain(quantity)} × ${line.itemName.ifBlank { line.itemId }}",
                ),
                capture = TransferCapture(
                    transferId = capture.transferId,
                    mode = capture.mode,
                    fromWarehouseId = capture.fromWarehouseId,
                    toWarehouseId = capture.toWarehouseId,
                    // Kept across lines on the same order - see the identical note in
                    // ReceivingFlowViewModel.bankCurrentLine.
                    requireLocationCaptureFallback = capture.requireLocationCaptureFallback,
                    defaultLocationId = capture.defaultLocationId,
                    locationId = capture.defaultLocationId,
                ),
                error = null,
            )
        }
        // Every scan is picked - a second batch of a line is a second pick of that line; the
        // line itself is never changed (user, 8 Oct 2026).
        if (capture.mode == TransferMode.SHIP) pickLive(banked)
        return true
    }

    /**
     * Picks a just-banked shipment line in D365FO (feedback 8b3daea2). Offline it stays unpicked
     * and the posting picks it; a refusal (not that much in the bin, say) is shown, and posting
     * would refuse the same.
     */
    private fun pickLive(line: CapturedTransferLine) {
        val transferId = _state.value.capture.transferId ?: return
        viewModelScope.launch {
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
                                lineNotice = com.d365fo.mobileops.presentation.common.components.QuickNoticeMessage(
                                    id = System.nanoTime(),
                                    text = "Already picked on another device",
                                    hint = true,
                                ),
                            )
                        }
                        persist()
                    } else {
                        _state.update { it.copy(error = "Not picked in D365FO: ${result.failure.message}") }
                    }
                }
                NetworkResult.Loading -> Unit
            }
        }
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
            _state.update { current -> current.copy(captured = current.captured.filterNot { it.id == line.id }) }
            refreshSuggested()
            persist()
        }
    }

    /**
     * Nudges a banked line's quantity up or down from the "scanned so far" list - the correction
     * path now that scanning a line banks it straight away instead of pausing for a yes/no. Held
     * to whole units either way: fine for the common case of an over- or under-count by a piece,
     * and simpler than reopening a numeric entry for a one-off tweak. Serial-tracked lines are
     * excluded at the call site - one serial per unit means a quantity nudge here would leave the
     * serial list wrong, not just the count.
     */
    fun adjustCapturedQuantity(index: Int, delta: Int) {
        val line = _state.value.captured.getOrNull(index) ?: return
        var next = line.quantity + BigDecimal(delta)
        if (next < BigDecimal.ONE) next = BigDecimal.ONE
        line.maxQuantity?.let { max -> if (next > max) next = max }
        if (next == line.quantity) return
        changePicked(line, next - line.quantity) {
            _state.update { current ->
                current.copy(captured = current.captured.map { if (it.id == line.id) it.copy(quantity = next) else it })
            }
            refreshSuggested()
            persist()
        }
    }

    fun showCaptured(show: Boolean) = _state.update { it.copy(showingCaptured = show) }

    fun reset() {
        pending?.cancel()
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
        _state.value = TransferFlowState(capture = TransferCapture(mode = capture.mode))

        // Abandoning means abandoning; the saved copy goes with it.
        capture.transferId?.takeIf { it.isNotBlank() }?.let { id ->
            viewModelScope.launch { flowSessions.clear(flowTypeFor(capture.mode), id) }
        }
    }

    /** Ship and receive on the same order are separate jobs, so they save separately. */
    private fun flowTypeFor(mode: TransferMode): FlowType =
        if (mode == TransferMode.SHIP) FlowType.TRANSFER_SHIP else FlowType.TRANSFER_RECEIVE

    /**
     * Commits the whole basket to the outbound queue and returns to a fresh order prompt.
     *
     * See ReceivingFlowViewModel.postReceipt for the full reasoning — enqueueing, not a server
     * reply, is the commitment point, because a handheld shipping or receiving a transfer is
     * exactly as likely to be on bad warehouse wifi as one receiving a purchase order.
     */
    fun postTransfer() {
        val state = _state.value
        val capture = state.capture
        val transferId = capture.transferId
        if (transferId.isNullOrBlank() || state.captured.isEmpty()) return
        // Re-entrancy guard - see the identical one and its full reasoning on
        // ReceivingFlowViewModel.postReceipt. A second call while state.busy is still true from
        // the first would mint its own idempotencyKey from the same still-captured lines, and
        // D365FO durably registers both as distinct transfers - real double-moved inventory.
        if (state.busy) return

        pending?.cancel()
        _state.update { it.copy(busy = true, error = null) }

        viewModelScope.launch {
            val session = sessions.session.value
            val deviceId = secureStore.deviceId()
            val dataAreaId = companyProvider().orEmpty()
            val userName = session?.userName.orEmpty()
            val personnelNumber = session?.personnelNumber
            val idempotencyKey = java.util.UUID.randomUUID().toString()
            val lines = state.captured.map { line ->
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
            // Back to a fresh order prompt, saying what just happened - the screen used to
            // reset with no word on whether the transfer went anywhere.
            val lineCount = state.captured.size
            val lineWord = "$lineCount " + if (lineCount == 1) "line" else "lines"
            _state.value = TransferFlowState(
                capture = TransferCapture(mode = capture.mode),
                postNotice = when {
                    rejectedReason != null -> PostNotice(
                        PostNoticeKind.REFUSED,
                        "Refused: $rejectedReason. Fix it in Activity.",
                    )
                    held -> PostNotice(
                        PostNoticeKind.QUEUED,
                        "$transferId · $lineWord held - posting is off",
                    )
                    postedLive && postingDispatcher.finishedInCall(idempotencyKey) -> PostNotice(
                        PostNoticeKind.SENT,
                        if (capture.mode == TransferMode.SHIP) {
                            "$transferId · $lineWord shipped"
                        } else {
                            "$transferId · $lineWord posted"
                        },
                    )
                    postedLive -> PostNotice(
                        PostNoticeKind.SENT,
                        "$transferId · $lineWord sent - posts in a few minutes",
                    )
                    else -> PostNotice(
                        PostNoticeKind.QUEUED,
                        "$transferId · $lineWord saved - sent when online",
                    )
                },
            )
        }
    }

    fun dismissPostNotice() = _state.update { it.copy(postNotice = null) }

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
        _state.update { it.copy(postNotice = null) }
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
                _state.update {
                    // Anything banked against this order and direction comes back with it.
                    val resumed = flowSessions
                        .restore(flowTypeFor(mode), order.transferId)
                        .map { line -> line.toCapturedTransfer() }

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
                        error = null,
                    )
                }

                // Only when the lines are in hand: on a large order the count may be one but the
                // line itself has not been fetched, so the operator still scans.
                if (order.linesInline && open.size == 1) {
                    selectLine(open.single())
                } else {
                    advance()
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

    /**
     * What [line] still has left *right now*, in the middle of this in-progress flow - see the
     * identical [ReceivingFlowViewModel.sessionRemaining] and the same reasoning:
     * [TransferOrderLine.remainingQuantity] is a server-computed snapshot from when this order
     * was looked up, so it knows nothing about a line this same flow already banked into
     * [TransferFlowState.captured] a moment ago and has not posted or queued yet. Without this,
     * banking a line's only unit and returning to the item step still offers that same item with
     * its original quantity, letting one worker in one sitting bank the same line twice.
     */
    /** Recomputes the open line's outstanding quantity after the banked list changed. */
    private fun refreshSuggested() {
        val line = _state.value.capture.line ?: return
        _state.update { it.copy(suggestedQuantity = sessionRemaining(line)) }
    }

    private fun sessionRemaining(line: TransferOrderLine): BigDecimal =
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

    private fun chooseAmong(lines: List<TransferOrderLine>) {
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
        _state.update { it.copy(lineChoices = emptyList()) }
        selectLine(line)
    }

    private fun selectLine(line: TransferOrderLine) {
        _state.update {
            it.copy(
                capture = it.capture.copy(
                    line = line,
                    unitSymbol = line.unitId,
                    // Shipping: the bin scanned before the item (or the warehouse's default issue
                    // bin) stays, unless the item tracks none. Receiving: the warehouse's default
                    // receipt bin when it has one (feedback 88c029a1) - the line's own location is
                    // the *source* bin, never where it goes - else the operator scans it.
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
        advance()
    }

    /**
     * Another transfer order (same number series, "BSP-TO-...") scanned mid-order - see
     * ReceivingFlowViewModel.switchedOrder. It was read as a bin or an item; opens that order
     * instead, in the same direction, unless lines are already scanned on this one.
     */
    private suspend fun switchedOrder(value: String): Boolean {
        val current = _state.value.capture.transferId ?: return false
        val prefix = current.substringBeforeLast('-', missingDelimiterValue = "").takeIf { it.isNotBlank() } ?: return false
        if (!value.startsWith("$prefix-", ignoreCase = true) || value.equals(current, ignoreCase = true)) return false
        if (_state.value.captured.isNotEmpty()) {
            fail("Finish or clear $current first")
            return true
        }
        _state.value = TransferFlowState(capture = TransferCapture(mode = _state.value.capture.mode))
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
        _state.update {
            it.copy(
                lineNotice = com.d365fo.mobileops.presentation.common.components.QuickNoticeMessage(
                    id = System.nanoTime(),
                    text = "Enter the quantity first, then the batch",
                    hint = true,
                ),
            )
        }
        return true
    }

    /** See ReceivingFlowViewModel.isScanOfCurrentItem - the same single-line case on a transfer. */
    private suspend fun isScanOfCurrentItem(value: String): Boolean {
        val line = _state.value.capture.line ?: return false
        if (value.length < 8 && value.all { it.isDigit() || it == '.' }) return false
        val same = value.equals(line.itemId, ignoreCase = true) ||
            ((lookups.lookupBarcode(value) as? NetworkResult.Success)?.data?.itemNumber?.equals(line.itemId, ignoreCase = true) == true)
        if (same) {
            _state.update {
                it.copy(
                    lineNotice = com.d365fo.mobileops.presentation.common.components.QuickNoticeMessage(
                        id = System.nanoTime(),
                        text = "Enter the quantity",
                        hint = true,
                    ),
                )
            }
        }
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
     * pad stays open - closing it first put the error on the step screen, whose buttons then
     * moved when the notice was dismissed.
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
                _state.update {
                    it.copy(capture = it.capture.copy(locationId = location.locationId), error = null)
                }
                advance()
            }
        }
    }

    /**
     * Unlike PO receiving, a transfer batch is never new - it is existing stock moving between
     * warehouses, not arriving from a vendor for the first time, and [TransferStep] has no
     * expiry-date step to fall back on for one that is. So this rejects an unregistered batch
     * outright rather than branching into a new-batch capture: confirmed live, before this
     * check existed, the flow would happily build a whole captured line - quantity, batch, the
     * lot - around a batch number that did not exist, and only D365FO's own posting would have
     * caught it, after the operator had moved on to the next item.
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
     * There used to be a confirm screen in between ("Ready to ship" / "Add and scan next") that
     * made the operator say yes to their own scan before it counted; it added a tap to every
     * single line for a decision the scan had already made. Correcting a line now happens in the
     * "scanned so far" list (the badge icon) instead - adjustCapturedQuantity or removeCaptured -
     * rather than by not banking it in the first place.
     */
    private fun advance() {
        val next = nextIncompleteTransferStep(_state.value.capture)
        if (next == TransferStep.CONFIRM) {
            gs1Answers.clear()
            bankCurrentLine()
            _state.update { it.copy(step = TransferStep.ITEM) }
            persist()
        } else {
            scanFeedback.success()
            _state.update { it.copy(step = next) }
            // A GS1 scan already answered this step: apply it, after this submit has finished.
            gs1Answers.remove(next)?.let { answer ->
                viewModelScope.launch {
                    kotlinx.coroutines.delay(GS1_AUTOFILL_DELAY_MS)
                    lastRaw = null
                    submit(answer)
                }
            }
        }
    }

    private fun fail(message: String) {
        scanFeedback.error()
        _state.update { it.copy(error = message) }
    }

    private fun plain(value: BigDecimal) = value.stripTrailingZeros().toPlainString()
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
    /** "Received 2 × …" for a second after a line is banked - see QuickNotice. */
    val lineNotice: com.d365fo.mobileops.presentation.common.components.QuickNoticeMessage? = null,
    /** What the last post did, shown on the fresh order prompt until dismissed or the next order. */
    val postNotice: PostNotice? = null,
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

enum class PostNoticeKind { SENT, QUEUED, REFUSED }

data class PostNotice(val kind: PostNoticeKind, val message: String)

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
    /** See adjustCapturedQuantity. Null for a line resumed from a saved session - unbounded. */
    val maxQuantity: BigDecimal? = null,
    /** Shipping: picked in D365FO when it was scanned (TransferPicker). */
    val picked: Boolean = false,
    /** It was already picked before this scan - by an earlier session or another device. */
    val pickedEarlier: Boolean = false,
    /** Tells banked lines apart while a live pick for one is in flight. */
    val id: String = java.util.UUID.randomUUID().toString(),
)

/**
 * Going back to [step]: its answer and every later one are cleared, in the order this direction
 * asks them (stepsForTransfer) - shipping asks the bin before the item, so going back to the
 * item keeps the bin; receiving asks it last. Serial, the step itself, drops only the last scan.
 */
private fun TransferCapture.cleared(step: TransferStep): TransferCapture {
    if (step == TransferStep.TRANSFER_ORDER) return TransferCapture(mode = mode)
    val sequence = stepsForTransfer(this)
    val from = sequence.indexOf(step).takeIf { it >= 0 } ?: return this
    val gone = sequence.drop(from).toSet()
    return copy(
        line = if (TransferStep.ITEM in gone) null else line,
        unitSymbol = if (TransferStep.ITEM in gone) null else unitSymbol,
        quantity = if (TransferStep.QUANTITY in gone) null else quantity,
        locationId = if (TransferStep.LOCATION in gone) null else locationId,
        batchId = if (TransferStep.BATCH in gone) null else batchId,
        serialIds = when {
            step == TransferStep.SERIAL -> serialIds.dropLast(1)
            TransferStep.SERIAL in gone -> emptyList()
            else -> serialIds
        },
        skipped = skipped - gone,
    )
}

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

private const val GS1_AUTOFILL_DELAY_MS = 150L

/** How long a line picked in the item search stays the answer to "which line?". */
private const val SEARCHED_LINE_VALID_MS = 10_000L
