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
import com.d365fo.mobileops.domain.model.TransferOrderLine
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
) : ViewModel() {

    private val _state = MutableStateFlow(TransferFlowState())
    val state: StateFlow<TransferFlowState> = _state.asStateFlow()

    private var pending: Job? = null

    // Same fix as ReceivingFlowViewModel - see the doc there. This screen shares the same
    // scanner bridge and the same one-in-flight-request pattern, so it is exposed to the
    // same repeated-broadcast hardware quirk.
    private var lastRaw: String? = null
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
                TransferStep.ITEM -> onItem(value)
                TransferStep.QUANTITY -> onQuantity(value)
                TransferStep.LOCATION -> onLocation(value)
                TransferStep.BATCH -> onBatch(value)
                TransferStep.SERIAL -> onSerial(value)
                TransferStep.CONFIRM -> Unit
            }
            _state.update { it.copy(busy = false) }
        }
    }

    fun back() {
        pending?.cancel()
        val current = _state.value
        val sequence = stepsForTransfer(current.capture)
        val index = sequence.indexOf(current.step)
        if (index <= 0) return

        val previous = sequence[index - 1]
        _state.update {
            it.copy(capture = it.capture.cleared(previous), step = previous, error = null, busy = false)
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

        _state.update {
            it.copy(
                captured = it.captured + CapturedTransferLine(
                    lineNumber = line.lineNumber,
                    itemId = line.itemId,
                    itemName = line.itemName,
                    quantity = quantity,
                    unitSymbol = capture.unitSymbol.orEmpty(),
                    locationId = capture.locationId,
                    batchId = capture.batchId,
                    serialIds = capture.serialIds,
                    // What was outstanding on the line when this was banked - the ceiling for
                    // adjustCapturedQuantity, so nudging a line up in the list can never book
                    // more than the order actually has left.
                    maxQuantity = line.remainingQuantity,
                ),
                capture = TransferCapture(
                    transferId = capture.transferId,
                    mode = capture.mode,
                    fromWarehouseId = capture.fromWarehouseId,
                    toWarehouseId = capture.toWarehouseId,
                    // Kept across lines on the same order - see the identical note in
                    // ReceivingFlowViewModel.bankCurrentLine.
                    requireLocationCaptureFallback = capture.requireLocationCaptureFallback,
                ),
                error = null,
            )
        }
        return true
    }

    fun removeCaptured(index: Int) {
        _state.update { current ->
            if (index !in current.captured.indices) {
                current
            } else {
                current.copy(captured = current.captured.filterIndexed { i, _ -> i != index })
            }
        }
        persist()
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
        _state.update { current ->
            if (index !in current.captured.indices) return@update current
            val line = current.captured[index]
            var next = line.quantity + BigDecimal(delta)
            if (next < BigDecimal.ONE) next = BigDecimal.ONE
            line.maxQuantity?.let { max -> if (next > max) next = max }
            if (next == line.quantity) return@update current
            current.copy(
                captured = current.captured.toMutableList().apply {
                    this[index] = line.copy(quantity = next)
                },
            )
        }
        persist()
    }

    fun showCaptured(show: Boolean) = _state.update { it.copy(showingCaptured = show) }

    fun reset() {
        pending?.cancel()
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
            val postedLive = syncQueue.leaseOne(entry.queueId)?.let { leased ->
                when (val result = postingDispatcher.dispatch(leased)) {
                    is NetworkResult.Success -> {
                        syncQueue.onSuccess(leased)
                        true
                    }
                    is NetworkResult.Error -> {
                        syncQueue.onFailure(leased, result.failure)
                        false
                    }
                    NetworkResult.Loading -> false
                }
            } ?: false
            if (!postedLive) syncScheduler.requestImmediatePush()

            scanFeedback.success()
            notifier.notifyWorkCompleted(transferId, postedLive)
            _state.value = TransferFlowState(capture = TransferCapture(mode = capture.mode))
        }
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

        viewModelScope.launch {
            flowSessions.save(flowTypeFor(capture.mode), transferId, warehouse, user, lines)
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
                            "NOT_RECEIVABLE" ->
                                "${order.transferId} has nothing in transit yet"
                            else -> "${order.transferId} cannot be worked right now"
                        },
                    )
                }

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
                        capture = TransferCapture(
                            transferId = order.transferId,
                            mode = mode,
                            fromWarehouseId = order.fromWarehouseId,
                            toWarehouseId = order.toWarehouseId,
                            requireLocationCaptureFallback = order.requireLocationCapture,
                        ),
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

    private suspend fun onItem(value: String) {
        linesFor(value)?.let { return chooseAmong(it) }

        // See the identical fast-fail on ReceivingFlowViewModel.onItem and the same reasoning:
        // an already-fully-captured item's own number falling through to the barcode-lookup
        // fallback can only ever end in "not a known barcode", after paying for a full network
        // round trip first.
        if (_state.value.order?.linesInline == true &&
            _state.value.openLines.any { it.itemId.equals(value, ignoreCase = true) }
        ) {
            return fail("$value has already been fully captured on this order")
        }

        when (val result = lookups.lookupBarcode(value)) {
            is NetworkResult.Error -> fail(result.failure.message)
            NetworkResult.Loading -> Unit
            is NetworkResult.Success -> {
                val match = result.data
                    ?: return fail("$value is not on this order, and not a known barcode")
                val matched = linesFor(match.itemNumber)
                    ?: return fail(
                        "${match.itemNumber} is not outstanding on " +
                            "${_state.value.capture.transferId}",
                    )
                val ofVariant = matched.filter { it.variant.acceptsScanOf(match.variant) }
                if (ofVariant.isEmpty()) {
                    return fail("${match.itemNumber} ${match.variant.label} is not outstanding on ${_state.value.capture.transferId}")
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
    private fun sessionRemaining(line: TransferOrderLine): BigDecimal =
        (line.remainingQuantity - capturedThisSessionFor(line)).coerceAtLeast(BigDecimal.ZERO)

    private fun capturedThisSessionFor(line: TransferOrderLine): BigDecimal =
        _state.value.captured
            .filter { it.lineNumber == line.lineNumber }
            .fold(BigDecimal.ZERO) { total, entry -> total + entry.quantity }

    private fun chooseAmong(lines: List<TransferOrderLine>) {
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
                    // Not pre-answered when receiving: where the pallet actually goes is what the
                    // operator scans, and the line's location is the *source* bin anyway.
                    locationId = null,
                ),
                suggestedQuantity = sessionRemaining(line),
                entryUnit = null,
                error = null,
            )
        }
        advance()
    }

    private fun onQuantity(value: String) {
        val line = _state.value.capture.line ?: return fail("Pick a line first")
        // "3", or "3 BOX" / a unit chip: converted to the line's unit per the item's conversions.
        val entered = com.d365fo.mobileops.domain.model.UnitConversion
            .parse(value, line.units, _state.value.entryUnit, line.unitId)
            .getOrElse { return fail(it.message ?: "\"$value\" is not a number") }
            .quantity
        if (entered.signum() <= 0) return fail("Quantity must be more than zero")

        if (line.serialRequired && entered.stripTrailingZeros().scale() > 0) {
            return fail("${line.itemId} is serial tracked, so it moves in whole units")
        }

        // The ceiling is what is outstanding in this direction, which the server decided - minus
        // whatever this same in-progress flow already banked against this line a moment ago and
        // has not posted or queued yet. See sessionRemaining.
        val maximum = sessionRemaining(line)
        if (entered > maximum) {
            val mode = _state.value.capture.mode
            return fail(
                if (mode == TransferMode.SHIP) {
                    "Only ${plain(maximum)} left to ship on this line"
                } else {
                    "Only ${plain(maximum)} was shipped and is still to receive"
                },
            )
        }

        _state.update { it.copy(capture = it.capture.copy(quantity = entered), error = null) }
        advance()
    }

    /** Picks the unit the quantity step reads its number in; the line's own unit clears it. */
    fun selectEntryUnit(unitId: String) = _state.update { s ->
        val lineUnit = s.capture.line?.unitId
        s.copy(entryUnit = unitId.takeUnless { lineUnit != null && it.equals(lineUnit, ignoreCase = true) })
    }

    private suspend fun onLocation(value: String) {
        val warehouse = _state.value.capture.toWarehouseId
            ?: return fail("This transfer has no destination warehouse")

        when (val result = lookups.lookupLocation(warehouse, value)) {
            is NetworkResult.Error -> fail(result.failure.message)
            NetworkResult.Loading -> Unit
            is NetworkResult.Success -> {
                val location = result.data
                    ?: return fail("No location $value in warehouse $warehouse")
                if (!location.canReceiveInto) {
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
        val line = _state.value.capture.line ?: return fail("Pick a line first")

        when (val result = lookups.isBatchRegistered(line.itemId, value)) {
            is NetworkResult.Error -> return fail(result.failure.message)
            NetworkResult.Loading -> return
            is NetworkResult.Success -> if (!result.data) {
                return fail("Batch $value is not known for ${line.itemId} - check the batch label")
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
                is NetworkResult.Error -> return fail(result.failure.message)
                NetworkResult.Loading -> return
                is NetworkResult.Success -> Unit
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
            bankCurrentLine()
            _state.update { it.copy(step = TransferStep.ITEM) }
            persist()
        } else {
            scanFeedback.success()
            _state.update { it.copy(step = next) }
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
    /** See adjustCapturedQuantity. Null for a line resumed from a saved session - unbounded. */
    val maxQuantity: BigDecimal? = null,
)

private fun TransferCapture.cleared(step: TransferStep): TransferCapture = when (step) {
    TransferStep.TRANSFER_ORDER -> TransferCapture(mode = mode)
    TransferStep.ITEM -> copy(
        line = null, quantity = null, unitSymbol = null,
        locationId = null, batchId = null, serialIds = emptyList(),
    )
    TransferStep.QUANTITY -> copy(
        quantity = null, locationId = null, batchId = null, serialIds = emptyList(),
    )
    TransferStep.LOCATION -> copy(locationId = null, batchId = null, serialIds = emptyList())
    TransferStep.BATCH -> copy(batchId = null, serialIds = emptyList())
    TransferStep.SERIAL -> copy(serialIds = serialIds.dropLast(1))
    TransferStep.CONFIRM -> this
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
)
