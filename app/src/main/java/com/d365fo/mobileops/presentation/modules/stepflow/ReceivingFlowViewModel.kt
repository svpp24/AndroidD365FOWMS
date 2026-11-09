package com.d365fo.mobileops.presentation.modules.stepflow

import androidx.lifecycle.ViewModel
import androidx.room.withTransaction
import androidx.lifecycle.viewModelScope
import com.d365fo.mobileops.domain.model.ItemDimensions
import com.d365fo.mobileops.domain.model.PurchaseOrder
import com.d365fo.mobileops.domain.model.PurchaseOrderLine
import com.d365fo.mobileops.domain.model.ReceivingCapture
import com.d365fo.mobileops.domain.model.ReceivingStep
import com.d365fo.mobileops.domain.model.nextIncompleteStep
import com.d365fo.mobileops.domain.model.stepsFor
import com.d365fo.mobileops.data.local.entity.FlowType
import com.d365fo.mobileops.data.local.entity.SyncOperation
import com.d365fo.mobileops.data.repository.FlowSessionRepository
import com.d365fo.mobileops.data.repository.PendingFlowResume
import com.d365fo.mobileops.data.repository.StoredLine
import com.d365fo.mobileops.data.repository.WorkerSessionRepository
import com.d365fo.mobileops.data.security.CompanyProvider
import com.d365fo.mobileops.data.security.SecureStore
import com.d365fo.mobileops.data.sync.SyncQueueRepository
import com.d365fo.mobileops.data.sync.SyncScheduler
import com.d365fo.mobileops.data.sync.payload.ProductReceiptPayload
import com.d365fo.mobileops.data.sync.payload.ReceiptLinePayload
import com.d365fo.mobileops.domain.repository.ItemFacts
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

/** See [ReceivingFlowViewModel.lastRaw] - the window a repeated identical scan is ignored in. */
private const val DUPLICATE_SCAN_WINDOW_MS = 1500L

/**
 * How long [ReceivingFlowViewModel.postReceipt] waits for a live answer before falling back to
 * the background queue. Short on purpose relative to the shared OkHttp client's own 60s read
 * timeout - confirmed live that a slow tenant can actually take the full 60 seconds, which is a
 * fine budget for a background retry nobody is watching and a poor one for an operator standing
 * at the screen. The fallback is exactly as safe either way, so there is no reason to make them
 * wait longer than this to reach it.
 */
private const val LIVE_POST_TIMEOUT_MS = 10_000L

/**
 * The same wait with Post immediately on (6 Nov): D365FO does the work in this call, and with Auto
 * GRN that includes the product receipt - about 4 s warm, 1 min 44 s first after an AOS restart.
 * Cut off at 10 s, the worker saw "Saved on this device" and never the GRN number. Inside
 * LongPostingTimeoutInterceptor's 3 minutes, so this limit is the one that applies.
 */
private const val LIVE_POST_NOW_TIMEOUT_MS = 120_000L

/**
 * Drives the step flow: one prompt, one answer, one validation, advance or explain.
 *
 * Every answer is validated against D365FO *before* the flow advances. That is the whole point
 * of the design — an operator finds out that a location is blocked while standing next to it,
 * not an hour later when a queued receipt fails to post. The cost is a round trip per step, and
 * the steps are chosen so each one is a single small keyed query.
 */
@HiltViewModel
class ReceivingFlowViewModel @Inject constructor(
    private val lookups: OnDemandLookupRepository,
    private val scannerBridge: HidScannerBridge,
    private val flowSessions: FlowSessionRepository,
    private val workerSession: WorkerSessionRepository,
    private val scanFeedback: ScanFeedback,
    private val syncQueue: SyncQueueRepository,
    private val syncScheduler: SyncScheduler,
    private val secureStore: SecureStore,
    private val companyProvider: CompanyProvider,
    private val database: com.d365fo.mobileops.data.local.D365Database,
    private val notifier: com.d365fo.mobileops.data.sync.SyncNotifier,
    private val postingDispatcher: com.d365fo.mobileops.data.sync.PostingDispatcher,
    private val pendingFlowResume: PendingFlowResume,
    private val stagedLines: com.d365fo.mobileops.data.repository.StagedLineRepository,
    private val opportunityCounts: com.d365fo.mobileops.data.counting.OpportunityCounts,
    private val networkPreferences: com.d365fo.mobileops.data.security.NetworkPreferences,
    private val freezes: com.d365fo.mobileops.data.repository.WarehouseFreezeRepository,
) : ViewModel() {

    init {
        // Every scan answers the step that is showing. That is the entire routing rule, and it
        // is why this flow needs no per-field focus management: there is only ever one question
        // on screen, so a scan cannot land in the wrong box.
        viewModelScope.launch {
            scannerBridge.scans.collect {
                // Which step each scan landed on - so feedback about a scan "not taking" shows
                // whether it reached the app at all (see ReceivingFlowScreen's scan prompt).
                timber.log.Timber.i("receiving scan (%s) at %s%s", it.source, _state.value.step,
                    if (_state.value.busy) ", busy" else "")
                submit(it.value)
            }
        }
        loadTodaysOrders()
    }

    /**
     * Today's purchase orders for the worker's warehouse, offered under the PO step so a tap
     * starts receiving and a typed tail ("552000") is enough. Best-effort: a failure just leaves
     * the list empty - scanning and typing the full number still work.
     */
    fun loadTodaysOrders() {
        viewModelScope.launch {
            val warehouse = workerSession.session.value?.selected?.warehouseId
            val result = lookups.lookupOrdersForToday(warehouse)
            if (result is NetworkResult.Success) {
                _state.update { s -> s.copy(todaysOrders = result.data.filter { it.receivable }) }
            }
        }
    }

    /** Starts receiving one of [ReceivingFlowState.todaysOrders] - same path as a scan. */
    fun pickTodaysOrder(purchId: String) = submit(purchId)

    /**
     * A typed or scanned PO number, completed from today's orders when it is only the tail of
     * one (and of exactly one) - otherwise as given.
     */
    private fun completeOrderNumber(value: String): String {
        val todays = _state.value.todaysOrders
        if (todays.any { it.purchId.equals(value, ignoreCase = true) }) return value
        return todays.filter { it.purchId.endsWith(value, ignoreCase = true) }
            .singleOrNull()?.purchId ?: value
    }

    /** Skips the current batch/serial/location step when D365FO allows it blank. */
    fun skipStep() {
        val s = _state.value
        if (s.busy || !com.d365fo.mobileops.domain.model.canSkipStep(s.step, s.capture)) return
        _state.update { it.copy(capture = it.capture.copy(skipped = it.capture.skipped + it.step), error = null) }
        advance()
    }

    /** Typed entry - damaged label, or a device with neither imager nor wedge. */
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

    /** Camera decode, routed through the same path as a wedge scan. */
    fun onCameraDecoded(value: String) = scannerBridge.submitCameraScan(value)

    /** See [HidScannerBridge.justScanned] - used by the screen's BackHandler. */
    fun justScanned(): Boolean = scannerBridge.justScanned()

    /**
     * Suspends wedge interception while a text field holds focus.
     *
     * On a rugged handheld the wedge and the physical keyboard are one device, so without this
     * the interceptor swallows the keystrokes of anyone typing a batch number by hand.
     */
    fun setScannerInterception(enabled: Boolean) {
        scannerBridge.enabled = enabled
    }

    private val _state = MutableStateFlow(ReceivingFlowState())
    val state: StateFlow<ReceivingFlowState> = _state.asStateFlow()

    /**
     * The in-flight lookup, cancelled whenever a new answer arrives.
     *
     * Handheld scanners fire fast and operators double-trigger them. Without this, two
     * overlapping lookups race and the slower one wins, landing the flow on an answer the
     * operator has already replaced.
     */
    private var pending: Job? = null

    // The OEM scanner on this hardware can fire its broadcast more than once for a single
    // trigger pull - confirmed live: five broadcasts landed for one held press, ~300-450ms
    // apart. Each used to be treated as a fresh answer to whatever step was showing by the
    // time it arrived, so a two-echo pull didn't just retry the current step - it answered
    // the current step *and* raced through the next one with the same barcode text, and a
    // three-or-more echo pull could cancel every request before any of them finished,
    // leaving the flow stuck exactly where it started. There is no legitimate flow here
    // where the identical raw text is meant to answer two different prompts, so an identical
    // value repeated within this window is dropped rather than resubmitted.
    private var lastRaw: String? = null
    private var lastRawAtMs: Long = 0L

    init {
        // "Continue where you left off" on Home hands the PO number off here rather than
        // carrying it as a nav argument - see PendingFlowResume. Submitted exactly like a real
        // scan of that same number would be, which is what re-triggers the lookup that restores
        // this order's already-banked lines from disk.
        //
        // Deliberately its own init block, placed after _state/pending/lastRaw above rather than
        // folded into the first one at the top of the class: submit() reads all three, and a
        // NullPointerException confirmed live - Kotlin runs init blocks and property
        // initializers in file order, so calling submit() from an init block any earlier than
        // this one hit _state while it was still null.
        pendingFlowResume.consume(FlowType.RECEIVING)?.let(::submit)
    }

    /** Answers the current step. [raw] is whatever was scanned or typed. */
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
                ReceivingStep.PURCHASE_ORDER -> onPurchaseOrder(value)
                ReceivingStep.PACKING_SLIP -> onPackingSlip(value)
                ReceivingStep.ITEM -> if (!switchedOrder(value)) onItem(value)
                // A single-line order opens straight at the quantity, so another PO scanned
                // there is the same "wrong order" case as at the item step.
                ReceivingStep.QUANTITY -> if (!switchedOrder(value) && !isScanOfCurrentItem(value)) onQuantity(value)
                ReceivingStep.LOCATION -> onLocation(value)
                ReceivingStep.BATCH -> onBatch(value)
                ReceivingStep.BATCH_EXPIRY_DATE -> onBatchExpiryDate(value)
                ReceivingStep.SERIAL -> onSerial(value)
                ReceivingStep.CONFIRM -> Unit
            }
            _state.update { it.copy(busy = false) }
        }
    }

    /** Steps back one prompt, clearing that step's answer so it is asked again. */
    fun clearLineNotice() = _state.update { it.copy(lineNotice = null) }

    fun back() {
        pending?.cancel()
        val current = _state.value
        val sequence = stepsFor(current.capture)
        val index = sequence.indexOf(current.step)
        if (index <= 0) return

        val previous = sequence[index - 1]
        // Back to the order prompt starts over, so the lines banked against this order leave
        // the screen too. They are saved per order (persist) and come back when it is scanned
        // again; left here, they showed a Register button that could not post them, because
        // the order they belong to had just been cleared.
        val toOrder = previous == ReceivingStep.PURCHASE_ORDER
        _state.update {
            it.copy(
                capture = it.capture.cleared(previous),
                step = previous,
                captured = if (toOrder) emptyList() else it.captured,
                stagedElsewhere = if (toOrder) emptyMap() else it.stagedElsewhere,
                error = null,
                busy = false,
            )
        }
    }

    /** Banks the line in hand into [ReceivingFlowState.captured]. False when there is none. */
    private fun bankCurrentLine(): Boolean {
        pending?.cancel()
        val state = _state.value
        val capture = state.capture
        val line = capture.line ?: return false
        val item = capture.item ?: return false
        val quantity = capture.quantity ?: return false

        val entry = CapturedLine(
            lineNumber = line.lineNumber,
            itemId = item.itemNumber,
            itemName = item.productName,
            quantity = quantity,
            unitSymbol = capture.unitSymbol.orEmpty(),
            locationId = capture.locationId,
            batchId = capture.batchId,
            batchExpiryDate = capture.batchExpiryDate,
            serialIds = capture.serialIds,
            variantLabel = line.variant.label,
            // What was outstanding on the line when this was banked - see the identical field
            // and the same reasoning on CapturedTransferLine.maxQuantity.
            maxQuantity = line.outstandingQuantity,
        )

        // A distinct double cue, not the plain single-step tone: banking a line is the operator
        // closing out one whole item, not just answering one prompt, and they are often not
        // looking at the screen when it happens. The system notification is the same signal for
        // whoever is not looking at the screen at all - see SyncNotifier.
        scanFeedback.success()
        scanFeedback.success()
        notifier.notifyLineAdded(item.productName.ifBlank { item.itemNumber })
        // Putaway confirmed at this location: the server may want it counted while we're here.
        opportunityCounts.afterMovement(capture.locationId, item.itemNumber)

        _state.update {
            it.copy(
                captured = it.captured + entry,
                lineNotice = com.d365fo.mobileops.presentation.common.components.QuickNoticeMessage(
                    id = System.nanoTime(),
                    text = "Received ${quantity.stripTrailingZeros().toPlainString()} × " +
                        item.productName.ifBlank { item.itemNumber },
                ),
                capture = ReceivingCapture(
                    purchId = capture.purchId,
                    vendorAccount = capture.vendorAccount,
                    // Kept: one delivery note covers everything off the lorry, so re-asking for it
                    // per line would be both wrong and infuriating.
                    vendorPackingSlipId = capture.vendorPackingSlipId,
                    siteId = capture.siteId,
                    warehouseId = capture.warehouseId,
                    // Kept too - it is the process's own setting for this order, not a per-line
                    // answer, and resetting it to the true-by-default would silently start
                    // re-asking for location on line two of an order where a supervisor turned
                    // that off.
                    requireLocationCaptureFallback = capture.requireLocationCaptureFallback,
                ),
                error = null,
            )
        }
        return true
    }

    /**
     * Removes a banked line.
     *
     * Removal rather than editing, deliberately: re-scanning three fields is quicker and less
     * error-prone than navigating back into a capture to change one of them, and it leaves no
     * partially-edited entry to reason about.
     */
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
     * Nudges a banked line's quantity up or down from the "scanned so far" list - see the
     * identical [TransferFlowViewModel.adjustCapturedQuantity] and the same reasoning: scanning a
     * line now banks it straight away instead of pausing on a confirm screen, so this is the
     * correction path for an off-by-a-piece count. Held to whole units, and clamped between one
     * and whatever was outstanding on the line when it was banked. Excluded at the call site for
     * a serial-tracked line, where a quantity nudge would leave the serial list wrong too.
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

    /** Abandons everything captured and starts again at the purchase order prompt. */
    fun reset() {
        pending?.cancel()
        val purchId = _state.value.capture.purchId
        // Keep today's orders on screen for the next receipt.
        _state.value = ReceivingFlowState(todaysOrders = _state.value.todaysOrders)
        loadTodaysOrders()

        // Abandoning means abandoning. Leaving the saved copy behind would offer the work back
        // on the home screen moments after someone deliberately threw it away.
        if (!purchId.isNullOrBlank()) {
            viewModelScope.launch { flowSessions.clear(FlowType.RECEIVING, purchId) }
        }
    }

    /**
     * Commits the whole basket to the outbound queue and returns to a fresh order prompt.
     *
     * **Enqueueing is the point of no return, not the server accepting it.** A handheld posting
     * from the goods-in bay is routinely offline or on wifi that drops mid-request, and the
     * operator's shift does not wait for a round trip to finish — they are already reaching for
     * the next pallet. So this returns as soon as the queue has durably recorded the work; what
     * happens to it after that is [com.d365fo.mobileops.data.sync.PostingDispatcher]'s job, run
     * by [com.d365fo.mobileops.data.sync.worker.TransactionPushWorker] in the background, with
     * its own retry ladder. A posting that ultimately fails for good shows up on the failed-sync
     * screen, not silently here.
     *
     * The idempotency key is minted once, here, and travels with the payload for its entire
     * life — including every retry. That is what lets a retried post recognise itself: the AOS
     * service treats a repeat of the same key as the same document, not a new one.
     */
    fun postReceipt() {
        val state = _state.value
        val purchId = state.capture.purchId
        if (purchId.isNullOrBlank() || state.captured.isEmpty()) return
        // Re-entrancy guard - confirmed live as a real gap, not a theoretical one: nothing
        // previously stopped a second tap (or a second call from a different entry point -
        // this button and the captured-lines sheet's own Post both call this) landing while the
        // first call's coroutine was still running. Each call mints its own idempotencyKey, so
        // two calls from the *same* still-captured lines is not one request retried - it is two
        // distinct receipts, and D365FO durably registers both. That is real double-received
        // inventory, not a cosmetic UI glitch, which is why this returns rather than merely
        // disabling the button - a disabled Compose button can still be reached by an event
        // already queued before recomposition catches up.
        if (state.posting || state.postOutcome != null) return

        pending?.cancel()
        _state.update { it.copy(posting = true, error = null) }

        viewModelScope.launch {
            val session = workerSession.session.value
            val payload = ProductReceiptPayload(
                idempotencyKey = java.util.UUID.randomUUID().toString(),
                dataAreaId = companyProvider().orEmpty(),
                receiptId = java.util.UUID.randomUUID().toString(),
                purchaseOrderNumber = purchId,
                vendorPackingSlipId = state.capture.vendorPackingSlipId.orEmpty(),
                receiptDate = java.time.Instant.now().toString(),
                userName = session?.userName.orEmpty(),
                personnelNumber = session?.personnelNumber,
                deviceId = secureStore.deviceId(),
                lines = state.captured.map { line ->
                    ReceiptLinePayload(
                        purchLineId = "$purchId:${plain(java.math.BigDecimal(line.lineNumber))}",
                        lineNumber = line.lineNumber,
                        itemNumber = line.itemId,
                        receiveNowQuantity = line.quantity.toDouble(),
                        unitSymbol = line.unitSymbol,
                        warehouseId = state.capture.warehouseId,
                        locationId = line.locationId,
                        batchNumber = line.batchId,
                        batchExpiryDate = line.batchExpiryDate,
                        serialNumbers = line.serialIds,
                    )
                },
            )

            val entry = syncQueue.buildEntry(
                operation = SyncOperation.POST_PRODUCT_RECEIPT,
                aggregateId = purchId,
                payload = payload,
                workerId = session?.userName.orEmpty(),
                deviceId = payload.deviceId,
            )

            // Atomic on purpose. These are two separate tables - the outbound queue and the
            // locally-saved capture - and a kill between two independent writes would leave the
            // receipt safely queued (it will still post) but the local session un-cleared, so
            // re-scanning this order afterward would offer the same already-queued lines back
            // for posting a second time. withTransaction makes it all-or-nothing: either both
            // land, or neither does and the capture is still there to post again cleanly.
            database.withTransaction {
                syncQueue.enqueue(entry)
                flowSessions.clear(FlowType.RECEIVING, purchId)
            }

            // Written unconditionally, before the live-dispatch attempt below even starts -
            // these lines are spoken for the instant they are durably enqueued, regardless of
            // whether that attempt succeeds, fails, or loses the lease race to a background
            // worker run. See StagedLineRepository's class doc and sessionRemaining. Ignored,
            // then removed, once the posting has left this device's queue - D365FO nets it from
            // then on (StagedLineDao.forOrder).
            stagedLines.stage(
                flowType = FlowType.RECEIVING,
                orderId = purchId,
                queueId = entry.queueId,
                lines = state.captured.map { it.lineNumber to it.quantity },
            )

            // Tried live, right here, instead of only ever handing this to the background
            // worker and telling the operator "queued" without knowing whether it actually
            // went anywhere. The entry is already durably enqueued above, so this attempt
            // costs nothing on failure - it is exactly the one TransactionPushWorker would
            // have made anyway, just not deferred to WorkManager's own schedule. A real
            // network condition (offline, a slow tenant, a timeout) fails the same way it
            // always would and falls straight back to the normal queue and retry ladder.
            //
            // Leased first, via the exact same claiming mechanism the background worker itself
            // uses (see SyncQueueDao.leaseOne) - enqueue() above already made this row visible
            // to a worker run, and SyncStatusProvider reactively requests an immediate push the
            // instant the pending count changes, which this enqueue itself just did. Dispatching
            // the freshly-built entry directly, unleased, raced exactly that: confirmed live,
            // two postProductReceipt calls 151ms apart with an identical payload size - this
            // code and a worker run each independently dispatching the same row. leaseOne
            // returning null means the worker won the race this time; this path then backs off
            // completely rather than touching an entry it does not own, and the summary below
            // reports it as queued rather than confirmed - accurate, since this code truly does
            // not know the outcome, the worker does.
            //
            // Bounded to LIVE_POST_TIMEOUT_MS specifically, well under the shared OkHttp
            // client's own 60s read timeout - confirmed live that a slow tenant can actually
            // take the full 60 seconds, and that is the client's timeout budget for *any*
            // request, background ones included, where nobody is standing there waiting. A
            // human is standing there for this one. withTimeoutOrNull cancels the coroutine
            // and, with it, the underlying call - the server may still be working when that
            // happens, which is exactly what the durable queue and idempotency key already
            // exist to make safe to walk away from.
            // Set when D365FO answered and refused it: the entry went straight to Failed
            // postings, and nothing will post it on its own - see PostOutcome.rejectedReason.
            var rejectedReason: String? = null
            // Posting switched off in Settings: queued and held, no live attempt.
            val held = !postingDispatcher.postingAllowed()
            val postedLive = if (held) false else syncQueue.leaseOne(entry.queueId)?.let { leased ->
                when (
                    val result =
                        withTimeoutOrNull(
                            if (networkPreferences.postImmediately.value) LIVE_POST_NOW_TIMEOUT_MS else LIVE_POST_TIMEOUT_MS,
                        ) { postingDispatcher.dispatch(leased) }
                ) {
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
                    NetworkResult.Loading, null -> {
                        syncQueue.onFailure(
                            leased,
                            AppFailure.Transient("D365FO did not respond in time"),
                        )
                        false
                    }
                }
            } ?: false
            if (!postedLive) syncScheduler.requestImmediatePush()
            // Registered there and then (Post immediately): D365FO's outstanding quantity already
            // shows it, so the staged figure must go now - kept, it was subtracted a second time
            // and the order came back as "already staged" with a Pack still open (PO 161706).
            if (postedLive && postingDispatcher.finishedInCall(payload.idempotencyKey)) {
                stagedLines.clearForQueue(entry.queueId)
            }

            scanFeedback.success()
            notifier.notifyWorkCompleted(purchId, postedLive)
            // Deliberately not a full reset back to ReceivingFlowState() here any more - the
            // operator gets a summary of what was just registered instead of the screen
            // silently jumping back to a blank order prompt, and captured/capture/openLines
            // etc. all stay exactly as they were so that summary (and a retry, if it did not
            // post live) has something to show. dismissPostOutcome is what actually resets, once
            // the operator has seen it.
            _state.update {
                it.copy(
                    posting = false,
                    postOutcome = PostOutcome(
                        purchId = purchId,
                        lineCount = state.captured.size,
                        postedAtEpochMs = System.currentTimeMillis(),
                        postedLive = postedLive,
                        rejectedReason = rejectedReason,
                        held = held,
                        postedNow = postedLive && postingDispatcher.finishedInCall(payload.idempotencyKey),
                        grnId = if (postedLive) postingDispatcher.documentFor(payload.idempotencyKey) else null,
                    ),
                )
            }
        }
    }

    /**
     * Leaves the post summary and goes back to Scan item on the same order - even when nothing
     * is left to receive (feedback, 6 Oct 2026: workers often scan the next pallet of the same
     * PO). The lines just registered count as staged, so they cannot be received twice. Without
     * an order in hand, a fresh order prompt.
     */
    fun dismissPostOutcome() {
        val s = _state.value
        val purchId = s.postOutcome?.purchId ?: s.capture.purchId
        if (purchId.isNullOrBlank() || s.order == null) {
            _state.value = ReceivingFlowState()
            return
        }
        val c = s.capture
        _state.value = ReceivingFlowState(
            step = ReceivingStep.ITEM,
            todaysOrders = s.todaysOrders,
            order = s.order,
            openLines = s.openLines,
            outstandingLineCount = s.outstandingLineCount,
            linesInline = s.linesInline,
            capture = ReceivingCapture(
                purchId = c.purchId,
                vendorAccount = c.vendorAccount,
                vendorPackingSlipId = c.vendorPackingSlipId,
                siteId = c.siteId,
                warehouseId = c.warehouseId,
                requireLocationCaptureFallback = c.requireLocationCaptureFallback,
            ),
            // openLines (small order) is still the order as it was looked up, so what this screen
            // has posted since is added here, not read back from the device's holds: those go as
            // soon as D365FO has the posting (see StagedLineDao.forOrder), while openLines never
            // learns it. A fresh look-up of the order starts from D365FO's own figures again.
            stagedElsewhere = if (s.linesInline && s.postOutcome?.rejectedReason == null) {
                s.stagedElsewhere.toMutableMap().apply {
                    s.captured.forEach { line -> merge(line.lineNumber, line.quantity, BigDecimal::add) }
                }
            } else {
                s.stagedElsewhere
            },
        )
        // Large order: each item's lines are fetched fresh from D365FO, so only the holds still
        // waiting on this device apply.
        if (!s.linesInline) {
            viewModelScope.launch {
                val staged = stagedLines.stagedFor(FlowType.RECEIVING, purchId)
                _state.update { if (it.capture.purchId != purchId) it else it.copy(stagedElsewhere = staged) }
            }
        }
        _state.update {
            val staged = it.stagedElsewhere
            it.copy(
                // Lines just registered are not outstanding any more for this device - the
                // server count lags until D365FO has processed them.
                outstandingLineCount = if (it.linesInline) {
                    it.openLines.count { line ->
                        (line.outstandingQuantity - (staged[line.lineNumber] ?: BigDecimal.ZERO)).signum() > 0
                    }
                } else {
                    it.outstandingLineCount
                },
            )
        }
    }

    /**
     * Nudges the background push worker to try again right now, for a posting that is durably
     * queued but did not get a live answer in time - see [PostOutcome.postedLive]. Does not
     * retry the live HTTP call inline: the entry is already safely on the same durable queue
     * [postReceipt] itself uses, so this is the one true way anything gets pushed, live tap or
     * background schedule alike - no second code path to keep in sync with it. The order's
     * entries are made due first, or the worker would skip them until their backoff ran out.
     */
    fun retryPost() {
        val purchId = _state.value.postOutcome?.purchId ?: return
        viewModelScope.launch {
            // A timed-out live post put the entry on the backoff ladder (1 minute first); the
            // push alone would find nothing due and do nothing. The worker asked for now.
            syncQueue.retryNow(purchId)
            syncScheduler.requestImmediatePush()
        }
    }

    /**
     * Writes the banked lines to disk.
     *
     * Fire-and-forget on the ViewModel scope, and never awaited by the caller: a scan must not
     * wait on a database write, and a failed write must not reject the scan. The cost of losing
     * a save is having to re-scan; the cost of blocking one is a queue at the goods-in door.
     */
    private fun persist() {
        val state = _state.value
        val purchId = state.capture.purchId ?: return
        val lines = state.captured.map { it.toStored() }
        val warehouse = state.capture.warehouseId
        val user = workerSession.session.value?.userName

        // ATOMIC + NonCancellable: the save runs to the end even when the screen closes right
        // after the scan - a cancelled write is a scanned line that silently isn't there later.
        viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.ATOMIC) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                flowSessions.save(FlowType.RECEIVING, purchId, warehouse, user, lines)
            }
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    // -- steps ---------------------------------------------------------------------------

    private suspend fun onPurchaseOrder(typed: String) {
        val value = completeOrderNumber(typed)
        when (val result = lookups.lookupOrder(value)) {
            is NetworkResult.Error -> fail(result.failure.message)
            NetworkResult.Loading -> Unit
            is NetworkResult.Success -> {
                val lookup = result.data
                val order = lookup.order
                if (!order.approved) {
                    return fail(
                        when (lookup.blockReason) {
                            "NOT_CONFIRMED" ->
                                "${order.purchId} is not confirmed yet"
                            "NOT_OPEN" -> "${order.purchId} is not open for receiving"
                            else -> "${order.purchId} has not been approved yet"
                        },
                    )
                }
                if (lookup.outstandingLineCount == 0) {
                    return fail("${order.purchId} has nothing left to receive")
                }
                // Only orders for the warehouse the worker is in: the header's warehouse, or any
                // line's (a line can deliver elsewhere than its header).
                val here = workerSession.session.value?.selected?.warehouseId
                if (!here.isNullOrBlank()) {
                    val forHere = order.warehouseId.equals(here, ignoreCase = true) ||
                        order.lines.any { it.warehouseId.equals(here, ignoreCase = true) }
                    val elsewhere = order.warehouseId?.takeIf { it.isNotBlank() }
                        ?: order.lines.firstNotNullOfOrNull { it.warehouseId?.takeIf { w -> w.isNotBlank() } }
                    if (!forHere && elsewhere != null) {
                        return fail("${order.purchId} is for $elsewhere, not $here")
                    }
                }
                // A wall-to-wall count freezes the warehouse: said now, not after the scanning.
                freezes.frozenMessage(here ?: order.warehouseId)?.let { return fail(it) }

                val open = order.lines.filter { it.hasOutstanding }
                itemsOnOrder.clear()
                open.mapTo(itemsOnOrder) { it.itemId.lowercase() }

                val capture = ReceivingCapture(
                    purchId = order.purchId,
                    vendorAccount = order.vendorAccount,
                    siteId = order.siteId,
                    warehouseId = order.warehouseId,
                    requireLocationCaptureFallback = lookup.requireLocationCapture,
                )

                // Anything banked against this order in an earlier session comes back with it.
                val resumed = flowSessions.restore(FlowType.RECEIVING, order.purchId)
                    .map { it.toCaptured() }
                // See ReceivingFlowState.stagedElsewhere and StagedLineRepository's class doc -
                // one fast local read, right alongside this network call, not one per line.
                val staged = stagedLines.stagedFor(FlowType.RECEIVING, order.purchId)

                // Fails right here, at the order level, when every line the server still calls
                // outstanding has in fact already been staged under an earlier "Register" - the
                // server does not know that yet (see sessionRemaining), but this device does.
                // Scoped to linesInline: on a large order open.isEmpty() by design (see below),
                // so there is nothing here to check against without a call per line, which would
                // cost exactly the round trip this check exists to avoid.
                if (lookup.linesInline && open.isNotEmpty() &&
                    open.all { (it.outstandingQuantity - (staged[it.lineNumber] ?: BigDecimal.ZERO)).signum() <= 0 }
                ) {
                    return fail("${order.purchId} is already received")
                }

                _state.update {
                    it.copy(
                        order = order,
                        openLines = open,
                        outstandingLineCount = lookup.outstandingLineCount,
                        // False on a large order: its lines were deliberately not sent, and the
                        // item step queries per item instead. See OnDemandLookupRepository.
                        linesInline = lookup.linesInline,
                        capture = capture,
                        // Whatever was banked against *this* order last time, and nothing else.
                        // Carrying captures between orders is how a line gets received against
                        // the wrong one; carrying them within one is how a half-done pallet
                        // survives the app being killed.
                        captured = resumed,
                        stagedElsewhere = staged,
                        error = null,
                    )
                }

                // One outstanding line means the item step has exactly one possible answer.
                // Asking anyway costs a scan and teaches operators to scan without reading.
                //
                // Only taken when the lines are actually in hand. On a large order the count may
                // be one, but the line itself has not been fetched - so the operator still scans,
                // and that scan is what fetches it.
                if (lookup.linesInline && open.size == 1) {
                    selectLine(open.single())
                } else {
                    advance()
                }
            }
        }
    }

    /**
     * The vendor's delivery note number. Free text — it is the vendor's reference, not ours, so
     * there is nothing to validate it against beyond being present.
     */
    private fun onPackingSlip(value: String) {
        _state.update {
            it.copy(capture = it.capture.copy(vendorPackingSlipId = value), error = null)
        }
        advance()
    }

    /**
     * Answers carried by a GS1 barcode (batch, expiry, serial, count), applied as the flow reaches
     * each step - so one GS1 scan can fill the item, quantity, batch and expiry at once. Cleared
     * when the next line starts.
     */
    private val gs1Answers = HashMap<ReceivingStep, String>()

    /**
     * Items seen open on this order since it was scanned, lower case. openLines cannot answer
     * this: a refresh ([freshLinesFor]) drops a line with nothing left, and its next scan then
     * read "not on this order" - even after the line had been unregistered (6 Nov).
     */
    private val itemsOnOrder = HashSet<String>()

    /** The item step: a GS1 element string first (GTIN + data), otherwise item number or barcode. */
    private suspend fun onItem(raw: String) {
        val gs1 = com.d365fo.mobileops.domain.model.Gs1Parser.parse(raw) ?: return onItemCode(raw)
        gs1Answers.clear()
        gs1.quantity?.let { gs1Answers[ReceivingStep.QUANTITY] = it.stripTrailingZeros().toPlainString() }
        gs1.batch?.let { gs1Answers[ReceivingStep.BATCH] = it }
        gs1.expiry?.let { gs1Answers[ReceivingStep.BATCH_EXPIRY_DATE] = it.toString() }
        gs1.serial?.let { gs1Answers[ReceivingStep.SERIAL] = it }
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
        // A scan is either the item number itself or a barcode that maps to one. Item number
        // first: on a small order it is answered from lines already in hand, at no network cost.
        linesFor(value)?.let { return chooseAmong(it, quantityPerScan = BigDecimal.ONE) }

        // Fails fast here, before ever reaching the barcode-lookup fallback below, when the
        // scanned text is unmistakably this order's own item number for a line that is simply
        // done - not "unrecognised", which is what that fallback exists to resolve. Confirmed
        // live: without this, re-scanning an already-fully-received item fell through to a
        // barcode lookup for its own item number, which can only ever end in "not a known
        // barcode" - after paying for a full network round trip (up to this client's 60s read
        // timeout on a slow tenant) to learn what was already known for free from openLines,
        // already in hand. Scoped to the small-order case (linesInline) - the large-order path
        // would need its own network call just to tell the two apart, which defeats the point.
        if (_state.value.linesInline && value.lowercase() in itemsOnOrder) {
            freshLinesFor(value)?.let { return chooseAmong(it, quantityPerScan = BigDecimal.ONE) }
            return fail("$value is already received")
        }

        when (val result = lookups.lookupBarcode(value)) {
            is NetworkResult.Error -> fail(result.failure.message)
            NetworkResult.Loading -> Unit
            is NetworkResult.Success -> {
                val match = result.data
                    ?: return fail("$value is not on this order")
                val matched = linesFor(match.itemNumber)
                    ?: freshLinesFor(match.itemNumber)
                    ?: return fail(
                        "${match.itemNumber} is not open on this PO",
                    )
                val ofVariant = matched.filter { it.variant.acceptsScanOf(match.variant) }
                if (ofVariant.isEmpty()) {
                    return fail("${match.itemNumber} ${match.variant.label} is not open on this PO")
                }
                chooseAmong(ofVariant, quantityPerScan = match.quantityPerScan, scanUnit = match.unitSymbol)
            }
        }
    }

    /**
     * The outstanding lines carrying an item, from wherever they can be had cheapest.
     *
     * On an ordinary order the lines arrived with the header and this is a filter. On an order of
     * thousands of lines they deliberately did not, and this is one keyed query for the handful
     * that carry the scanned item — which is why a five-thousand-line order behaves like a
     * five-line one from the operator's side.
     *
     * Null means the item is not outstanding on this order; an empty list is never returned.
     */
    private suspend fun linesFor(itemNumber: String): List<PurchaseOrderLine>? {
        val state = _state.value
        val purchId = state.capture.purchId ?: return null

        if (state.linesInline) {
            return state.openLines
                .filter { it.itemId.equals(itemNumber, ignoreCase = true) && sessionRemaining(it).signum() > 0 }
                .takeIf { it.isNotEmpty() }
        }

        return when (val result = lookups.lookupLinesForItem(purchId, itemNumber)) {
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
     * Asks D365FO again before saying a line of this order is done. openLines is the order as it
     * was looked up, so a registration undone in D365FO since then (6 Nov: registered here,
     * unregistered there, back to this same screen) still read "already received". Only on that
     * refusal, so a normal scan costs no extra call; no answer leaves the screen as it was.
     * What is banked in this flow stays banked, and [sessionRemaining] still nets it.
     */
    private suspend fun freshLinesFor(itemNumber: String): List<PurchaseOrderLine>? {
        val state = _state.value
        val purchId = state.capture.purchId ?: return null
        if (!state.linesInline || itemNumber.lowercase() !in itemsOnOrder) return null
        val lookup = (lookups.lookupOrder(purchId) as? NetworkResult.Success)?.data ?: return null
        val staged = stagedLines.stagedFor(FlowType.RECEIVING, purchId)
        lookup.order.lines.filter { it.hasOutstanding }.mapTo(itemsOnOrder) { it.itemId.lowercase() }
        _state.update {
            if (it.capture.purchId != purchId) return@update it
            it.copy(
                order = lookup.order,
                openLines = lookup.order.lines.filter { line -> line.hasOutstanding },
                outstandingLineCount = lookup.outstandingLineCount,
                linesInline = lookup.linesInline,
                stagedElsewhere = staged,
            )
        }
        return linesFor(itemNumber)
    }

    /**
     * What [line] still has left *right now*, in the middle of this in-progress flow.
     *
     * [PurchaseOrderLine.outstandingQuantity] claims to subtract
     * [PurchaseOrderLine.uncommittedQuantity] - work already handed to D365FO but not yet
     * actually registered - but AOS never computes that field (confirmed by reading
     * `HSMobileOpsReceivingService`: no reference to the post queue exists there at all), so it
     * is always zero and this has to net out two things itself instead:
     *
     * 1. [ReceivingFlowState.captured] - banked in *this* in-progress flow a moment ago, not
     *    even staged yet (postReceipt does that once the whole order is done).
     * 2. [ReceivingFlowState.stagedElsewhere] - handed to D365FO by an earlier "Register" on
     *    this same order (this session or a previous one) and landed as Pending. Nothing
     *    server-side reflects it until AOS's batch job or "Process pending" registers it - see
     *    [com.d365fo.mobileops.data.repository.StagedLineRepository]'s class doc.
     *
     * Without both, banking (or registering) a line's only unit and returning to the item step
     * still offers that same item with its original quantity - confirmed live for both cases,
     * including two receipts staged for the same one-pack line minutes apart, neither aware of
     * the other because the server genuinely did not know yet either.
     */
    /** Not private - the item-search sheet shows this per line so an operator can see it before picking. */
    internal fun sessionRemaining(line: PurchaseOrderLine): BigDecimal =
        (line.outstandingQuantity - capturedThisSessionFor(line) - stagedElsewhereFor(line))
            .coerceAtLeast(BigDecimal.ZERO)

    /** See [sessionRemaining] - the part of [line] already banked into captured, this flow, right now. */
    private fun capturedThisSessionFor(line: PurchaseOrderLine): BigDecimal =
        _state.value.captured
            .filter { it.lineNumber == line.lineNumber }
            .fold(BigDecimal.ZERO) { total, entry -> total + entry.quantity }

    /** See [sessionRemaining] - the part of [line] already staged under an earlier "Register", not yet confirmed off the queue. */
    private fun stagedElsewhereFor(line: PurchaseOrderLine): BigDecimal =
        _state.value.stagedElsewhere[line.lineNumber] ?: BigDecimal.ZERO

    /**
     * The same item can appear on several lines of one order, and they are not interchangeable:
     * each has its own quantity and its own delivery schedule. When that happens the operator
     * picks, because nothing in the scan says which was meant.
     */
    private suspend fun chooseAmong(lines: List<PurchaseOrderLine>, quantityPerScan: BigDecimal, scanUnit: String? = null) {
        val searched = takeSearchedLine()?.let { number -> lines.firstOrNull { it.lineNumber == number } }
        if (searched != null) {
            selectLine(searched, quantityPerScan, scanUnit)
            return
        }
        if (lines.size > 1) {
            _state.update { it.copy(lineChoices = lines, scannedPackSize = quantityPerScan, scannedPackUnit = scanUnit) }
            return
        }
        selectLine(lines.single(), quantityPerScan, scanUnit)
    }

    fun chooseLine(line: PurchaseOrderLine) {
        pending?.cancel()
        pending = viewModelScope.launch {
            val packSize = _state.value.scannedPackSize ?: BigDecimal.ONE
            val packUnit = _state.value.scannedPackUnit
            _state.update { it.copy(busy = true, lineChoices = emptyList(), scannedPackSize = null, scannedPackUnit = null) }
            selectLine(line, packSize, packUnit)
            _state.update { it.copy(busy = false) }
        }
    }

    private suspend fun selectLine(
        line: PurchaseOrderLine,
        scannedQuantity: BigDecimal = BigDecimal.ONE,
        scanUnit: String? = null,
    ) {
        // A pack barcode counts in its own unit (1 BOX); the line is in its purchase unit.
        val quantityPerScan = com.d365fo.mobileops.domain.model.UnitConversion
            .packInLineUnit(scannedQuantity, scanUnit, line.units, line.purchaseUnit) ?: scannedQuantity
        // The item's dimension groups decide which steps come next, so these facts have to be
        // in hand before the flow can know what it is going to ask.
        //
        // The AOS service path already resolved them: it returns BatchRequired, SerialRequired
        // and LocationRequired inline on every line, which is the exact round trip lookupItem
        // exists to make. line.locationRequired is non-null only when that happened - see its
        // field doc - so this skips a second live call entirely for the deployed, common case
        // and asks lookupItem only when the line truly does not carry the answer already: the
        // OData fallback and the offline Room cache, neither of which can join that data this
        // cheaply.
        val facts = line.locationRequired?.let { locationRequired ->
            ItemFacts(
                itemNumber = line.itemId,
                productName = line.itemName,
                inventoryUnit = line.inventoryUnit,
                purchaseUnit = line.purchaseUnit,
                batchRequired = line.tracking.requiresBatch,
                serialRequired = line.tracking.requiresSerial,
                locationRequired = locationRequired,
            )
        } ?: when (val result = lookups.lookupItem(line.itemId)) {
            is NetworkResult.Error -> return fail(result.failure.message)
            NetworkResult.Loading -> return
            is NetworkResult.Success -> result.data
                ?: return fail("${line.itemId} is not released here")
        }

        _state.update {
            it.copy(
                capture = it.capture.copy(
                    line = line,
                    item = ItemDimensions(
                        itemNumber = facts.itemNumber,
                        productName = facts.productName,
                        inventoryUnit = facts.inventoryUnit,
                        batchRequired = facts.batchRequired,
                        serialRequired = facts.serialRequired,
                        locationRequired = facts.locationRequired,
                    ),
                    unitSymbol = line.purchaseUnit.ifBlank { facts.inventoryUnit },
                    siteId = line.siteId ?: it.capture.siteId,
                    warehouseId = line.warehouseId ?: it.capture.warehouseId,
                    // Pre-answered from the line when it has one. This used to be deliberately
                    // left blank - the line's location is where purchasing said the goods would
                    // go, not necessarily where the worker actually puts them, and pre-filling
                    // risked a reflex confirm against a bin nobody walked to. That trade was
                    // overridden on request: when a line already carries a location, use it and
                    // skip asking - see stepsFor. LOCATION only stays a real prompt for a line
                    // that has none of its own, or when this process's own fallback flag says to
                    // keep asking regardless.
                    locationId = line.locationId?.takeIf { loc -> loc.isNotBlank() },
                ),
                suggestedQuantity = sessionRemaining(line),
                suggestedLocation = line.locationId?.takeIf { loc -> loc.isNotBlank() },
                scannedPackSize = quantityPerScan.takeIf { size -> size > BigDecimal.ONE },
                entryUnit = null,
                conversionNote = null,
                error = null,
            )
        }
        advance()
    }

    /**
     * Another purchase order scanned at the item or quantity step - same number series as the open one
     * ("BSP-PO-..."). After registering, the flow stays on the order, and scanning the next one
     * was read as an item ("… is not an item on this order"). Opens that order instead; with
     * lines already scanned it asks for them to be registered or removed first.
     */
    private suspend fun switchedOrder(value: String): Boolean {
        val current = _state.value.capture.purchId ?: return false
        val prefix = current.substringBeforeLast('-', missingDelimiterValue = "").takeIf { it.isNotBlank() } ?: return false
        if (!value.startsWith("$prefix-", ignoreCase = true)) return false
        if (_state.value.captured.isNotEmpty()) {
            fail("Finish or clear $current first")
            return true
        }
        val todays = _state.value.todaysOrders
        _state.value = ReceivingFlowState(todaysOrders = todays)
        onPurchaseOrder(value)
        return true
    }

    /**
     * The item already selected, scanned again at the quantity step - the order's only line is
     * picked as soon as the order is scanned, and the worker then scans the item anyway. Its
     * number or barcode was read as the quantity: "Only 2 outstanding" for 9502930970077, or
     * "BSR-47274-0 is not a number". Now it is recognised and the quantity is still asked for.
     */
    private suspend fun isScanOfCurrentItem(value: String): Boolean {
        val line = _state.value.capture.line ?: return false
        // A short number is a quantity, never an item - barcodes are 8 digits and up.
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
        checkQuantity(value)?.let { return fail(it) }
        val line = _state.value.capture.line ?: return fail("Pick a line first")
        val converted = com.d365fo.mobileops.domain.model.UnitConversion
            .parse(value, line.units, _state.value.entryUnit, line.purchaseUnit)
            .getOrElse { return fail(it.message ?: "\"$value\" is not a number") }
        val entered = converted.quantity
        val note = converted.enteredUnit.takeIf { it.isNotBlank() }?.let {
            "${plain(converted.entered)} $it = ${plain(entered)} ${line.purchaseUnit}"
        }
        _state.update { it.copy(capture = it.capture.copy(quantity = entered), conversionNote = note, error = null) }
        advance()
    }

    /**
     * Why [value] cannot be the current line's quantity, or null when it can - asked by the
     * keypad screen before it closes; see TransferFlowViewModel.quantityError.
     */
    fun quantityError(value: String): String? =
        if (_state.value.step != ReceivingStep.QUANTITY) null else checkQuantity(value.trim())

    private fun checkQuantity(value: String): String? {
        val line = _state.value.capture.line ?: return "Pick a line first"
        // "3", or "3 BOX" / a unit chip: converted to the line's unit per the item's conversions.
        val converted = com.d365fo.mobileops.domain.model.UnitConversion
            .parse(value, line.units, _state.value.entryUnit, line.purchaseUnit)
            .getOrElse { return it.message ?: "\"$value\" is not a number" }
        val entered = converted.quantity
        if (entered.signum() <= 0) return "Quantity must be more than zero"

        val item = _state.value.capture.item
        if (item?.serialRequired == true && entered.stripTrailingZeros().scale() > 0) {
            return "Whole units only (serial item)"
        }

        // Stops at what is left on the order (6 Nov). D365FO registers only what is still Ordered, so
        // anything above it, whatever the line's over-delivery % or the worker's own allowance, is
        // refused there: taking it here only meant a failed posting later. If more really arrived,
        // a supervisor raises the order line's quantity first. Nets out everything spoken for:
        // posted (receivedQuantity), staged under an earlier "Register" and not yet registered
        // (stagedElsewhereFor - uncommittedQuantity itself is always zero, AOS never computes it,
        // see sessionRemaining), and banked into this flow a moment ago (capturedThisSessionFor).
        val maximum = line.orderedQuantity - line.receivedQuantity - line.uncommittedQuantity -
            capturedThisSessionFor(line) - stagedElsewhereFor(line)
        if (entered > maximum) {
            return "Only ${plain(maximum)} open"
        }
        return null
    }

    /** Picks the unit the quantity step reads its number in; the line's own unit clears it. */
    fun selectEntryUnit(unitId: String) = _state.update { s ->
        val lineUnit = s.capture.line?.purchaseUnit
        s.copy(entryUnit = unitId.takeUnless { lineUnit != null && it.equals(lineUnit, ignoreCase = true) })
    }

    private suspend fun onLocation(value: String) {
        // The signed-in worker's own backoffice-assigned warehouse - never a device-level
        // setting, and never something the operator picks. See WorkerSessionRepository.
        val warehouse = _state.value.capture.warehouseId
            ?: workerSession.session.value?.selected?.warehouseId
        if (warehouse.isNullOrBlank()) {
            return fail("No warehouse assigned - ask a supervisor")
        }

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
                if (!location.canReceiveInto) {
                    return fail(
                        "$value is blocked for receiving" +
                            (location.blockingCause?.let { " ($it)" } ?: ""),
                    )
                }
                _state.update {
                    it.copy(
                        capture = it.capture.copy(locationId = location.locationId),
                        error = null,
                    )
                }
                advance()
            }
        }
    }

    private suspend fun onBatch(value: String) {
        val item = _state.value.capture.item ?: return fail("Pick an item first")

        // Live, not cached: whether this batch is new decides whether the flow asks for an
        // expiry date next, and that decision has to be right the moment it is made - the
        // posting itself refuses a batch it cannot resolve a dimension for, and finding that out
        // on the sync queue is much later than the operator standing here.
        when (val result = lookups.isBatchRegistered(item.itemNumber, value)) {
            // No connection: taken as scanned and left to D365FO, which creates a batch it does not know yet when the
            // posting arrives - a wrong one comes back in Activity rather than stopping work here.
            is NetworkResult.Error -> if (result.failure.retryable) {
                _state.update { it.copy(capture = it.capture.copy(batchId = value, batchIsNew = false), error = null) }
                advance()
            } else {
                return fail(result.failure.message)
            }
            NetworkResult.Loading -> return
            is NetworkResult.Success -> {
                _state.update {
                    it.copy(
                        capture = it.capture.copy(batchId = value, batchIsNew = !result.data),
                        error = null,
                    )
                }
                advance()
            }
        }
    }

    private fun onBatchExpiryDate(value: String) {
        val parsed = parseExpiryDate(value) ?: return fail("$value is not a date - enter it as DD-MM-YYYY")
        // A new batch that has already expired is a typing slip far more often than real stock -
        // received as it is, it is unusable stock the moment it lands.
        if (parsed < java.time.LocalDate.now().toString()) {
            return fail("$value is in the past - check the pack")
        }
        _state.update {
            it.copy(capture = it.capture.copy(batchExpiryDate = parsed), error = null)
        }
        advance()
    }

    /**
     * The expiry date as typed - DD-MM-YYYY (also with / or ., or as 8 digits DDMMYYYY) - or as
     * a GS1 barcode supplies it (YYYY-MM-DD). Returned as YYYY-MM-DD, the shape the AOS service
     * parses (see HSMobileOpsPostingService::parseIsoDate); null when it is not a real date.
     */
    internal fun parseExpiryDate(value: String): String? = parseExpiry(value)

    private suspend fun onSerial(value: String) {
        val capture = _state.value.capture
        val item = capture.item ?: return fail("Pick an item first")

        if (capture.serialIds.any { it.equals(value, ignoreCase = true) }) {
            return fail("$value has already been scanned for this receipt")
        }

        // A serial is meant to identify one physical unit for the life of that unit. Receiving a
        // second one under the same number makes the two indistinguishable in every later
        // enquiry, so this is checked against the server before it is accepted, not after.
        when (val result = lookups.isSerialRegistered(item.itemNumber, value)) {
            // No connection: taken as scanned and left to D365FO, which checks it again when the
            // posting arrives - a wrong one comes back in Activity rather than stopping work here.
            is NetworkResult.Error -> if (!result.failure.retryable) return fail(result.failure.message)
            NetworkResult.Loading -> return
            is NetworkResult.Success ->
                if (result.data) return fail("$value is already registered in D365FO")
        }

        _state.update {
            it.copy(
                capture = it.capture.copy(serialIds = it.capture.serialIds + value),
                error = null,
            )
        }
        advance()
    }

    // -- plumbing ------------------------------------------------------------------------

    /**
     * Moves to the earliest unanswered step for the capture as it now stands.
     *
     * Every accepted scan passes through here, and only here — one tone-and-buzz call site
     * for the whole flow, rather than one at each of the seven steps that could drift out of
     * sync with each other.
     */
    /**
     * Moves to the next unanswered field - or, once every field on this line is answered, banks
     * it straight to [ReceivingFlowState.captured] and reopens the item prompt for the next scan.
     * See the identical [TransferFlowViewModel.advance] and the same reasoning: the confirm
     * screen that used to sit here ("Ready to receive" / "Add to receipt and scan next") made the
     * operator say yes to their own scan before it counted, on every single line. Correcting a
     * line now happens in the "scanned so far" list (the badge icon) instead - see
     * adjustCapturedQuantity and removeCaptured.
     */
    private fun advance() {
        val next = nextIncompleteStep(_state.value.capture)
        if (next == ReceivingStep.CONFIRM) {
            bankCurrentLine()
            _state.update { it.copy(step = ReceivingStep.ITEM) }
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
            return
        }
        gs1Answers.clear()
    }

    private fun fail(message: String) {
        scanFeedback.error()
        _state.update { it.copy(error = message) }
    }

    private fun plain(value: BigDecimal) = value.stripTrailingZeros().toPlainString()
}

/**
 * Everything the step screen renders.
 *
 * [order] and [openLines] are kept alongside [capture] because the flow needs the order it
 * fetched — re-fetching it to answer "which lines are outstanding" at every step would turn one
 * round trip into five.
 */
data class ReceivingFlowState(
    val step: ReceivingStep = ReceivingStep.PURCHASE_ORDER,
    /** Open POs due today at the worker's warehouse - tap to start, see [ReceivingFlowViewModel.loadTodaysOrders]. */
    val todaysOrders: List<com.d365fo.mobileops.domain.repository.PurchaseOrderSummary> = emptyList(),
    val capture: ReceivingCapture = ReceivingCapture(),
    val order: PurchaseOrder? = null,
    val openLines: List<PurchaseOrderLine> = emptyList(),
    /**
     * The true number of outstanding lines, which is not [openLines].size on a large order —
     * there, the lines were not sent and openLines is empty.
     */
    val outstandingLineCount: Int = 0,
    /** False when lines must be fetched per item because the order is too large to send whole. */
    val linesInline: Boolean = true,
    /** Lines banked in this session against this order, most recent last. */
    val captured: List<CapturedLine> = emptyList(),
    /**
     * lineNumber -> quantity already staged for this order (this device, an earlier "Register"
     * on this same order) but not yet confirmed off D365FO's own queue - see
     * [com.d365fo.mobileops.data.repository.StagedLineRepository]'s class doc for why this
     * exists and [ReceivingFlowViewModel.sessionRemaining] for how it is used. Fetched once, in
     * [ReceivingFlowViewModel.onPurchaseOrder], not re-queried per line.
     */
    val stagedElsewhere: Map<Double, BigDecimal> = emptyMap(),
    val showingCaptured: Boolean = false,
    /** Non-empty when one scan matched several lines and the operator has to pick. */
    val lineChoices: List<PurchaseOrderLine> = emptyList(),
    /** Units per scan from a case barcode, offered as the default at the quantity step. */
    val scannedPackSize: BigDecimal? = null,
    /** The scanned pack barcode's own unit, converted per line when one is picked. */
    val scannedPackUnit: String? = null,
    /** The unit the quantity is being entered in; null = the line's own unit. */
    val entryUnit: String? = null,
    /** "2 BOX = 24 PCS" after a converted entry, shown on the next steps' summary. */
    val conversionNote: String? = null,
    val suggestedQuantity: BigDecimal? = null,
    /** Where the order line said the goods would go. A hint only — never an answer. */
    val suggestedLocation: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
    /** "Received 2 × …" for a second after a line is banked - see QuickNotice. */
    val lineNotice: com.d365fo.mobileops.presentation.common.components.QuickNoticeMessage? = null,
    /** True from the moment "Register scanned so far" is tapped until [postOutcome] is set. */
    val posting: Boolean = false,
    /** Set once a post attempt finishes; cleared only when the operator dismisses the summary. */
    val postOutcome: PostOutcome? = null,
)

/**
 * What happened the last time this order's captured lines were registered - shown as a summary
 * once posting finishes, in place of silently resetting straight back to a fresh order prompt.
 * [lineCount] and [purchId] are captured at post time rather than read back off [ReceivingFlowState]
 * afterward, because [ReceivingFlowViewModel.postReceipt] keeps [ReceivingFlowState.captured]
 * around unchanged until the operator dismisses this summary - see its class doc.
 */
data class PostOutcome(
    val purchId: String,
    val lineCount: Int,
    val postedAtEpochMs: Long,
    /**
     * True only when this device got a live answer from D365FO before [postReceipt]'s own
     * timeout. False means the posting is durably queued and will still go through on its own
     * via the background retry ladder - never a sign of data loss - but the operator standing
     * there gets to know that and retrigger a live attempt now instead of only finding out on
     * the Activity screen later.
     */
    val postedLive: Boolean,
    /** Posting is switched off in Settings: queued on the device, sent once it is switched on. */
    val held: Boolean = false,
    /** Settings > Post immediately: D365FO registered it in the same call - not just staged. */
    val postedNow: Boolean = false,
    /**
     * D365FO's reason when it answered and refused the posting. Not queued for retry then -
     * it is under Failed postings on the Activity screen - so the outcome must not say it will
     * post by itself.
     */
    val rejectedReason: String? = null,
    /** Auto GRN (Mobile process setup): the product receipt D365FO posted with the registration. */
    val grnId: String? = null,
)

/**
 * A line the operator has finished capturing in this session.
 *
 * Held in memory only, and deliberately so while posting does not exist: writing these to Room
 * would create a durable record the operator would reasonably expect to be sent to D365FO, and
 * nothing yet sends it. A basket that survives a crash but never posts is worse than one that
 * does not survive.
 */
data class CapturedLine(
    val lineNumber: Double,
    val itemId: String,
    val itemName: String,
    val quantity: BigDecimal,
    val unitSymbol: String,
    val locationId: String? = null,
    val batchId: String? = null,
    val batchExpiryDate: String? = null,
    val serialIds: List<String> = emptyList(),
    /** See CapturedTransferLine.variantLabel. */
    val variantLabel: String = "",
    /** See adjustCapturedQuantity. Null for a line resumed from a saved session - unbounded. */
    val maxQuantity: BigDecimal? = null,
)

/**
 * Clears one step's answer, and everything downstream of it.
 *
 * Going back to the item step must not leave a quantity captured against the line that is being
 * replaced — that is how an operator ends up receiving one item's count against another's line.
 */
private fun ReceivingCapture.cleared(step: ReceivingStep): ReceivingCapture = when (step) {
    // ReceivingCapture() resets requireLocationCaptureFallback to its own true default, which
    // is correct here specifically: PURCHASE_ORDER starts an entirely new order, and the next
    // lookupOrder resolves the real flag for whichever order that turns out to be.
    ReceivingStep.PURCHASE_ORDER -> ReceivingCapture()
    ReceivingStep.PACKING_SLIP -> copy(
        vendorPackingSlipId = null, line = null, item = null, quantity = null,
        unitSymbol = null, locationId = null, batchId = null, batchIsNew = null,
        batchExpiryDate = null, serialIds = emptyList(),
    )
    ReceivingStep.ITEM -> copy(
        line = null, item = null, quantity = null, unitSymbol = null,
        locationId = null, batchId = null, batchIsNew = null,
        batchExpiryDate = null, serialIds = emptyList(),
    )
    ReceivingStep.QUANTITY -> copy(
        quantity = null, locationId = null, batchId = null, batchIsNew = null,
        batchExpiryDate = null, serialIds = emptyList(),
    )
    ReceivingStep.LOCATION -> copy(
        locationId = null, batchId = null, batchIsNew = null,
        batchExpiryDate = null, serialIds = emptyList(),
    )
    ReceivingStep.BATCH -> copy(
        batchId = null, batchIsNew = null,
        batchExpiryDate = null, serialIds = emptyList(),
    )
    ReceivingStep.BATCH_EXPIRY_DATE -> copy(batchExpiryDate = null)
    // Dropping the last serial rather than all of them: re-scanning nine good serials to correct
    // the tenth is exactly the kind of thing that makes an operator stop using the app.
    ReceivingStep.SERIAL -> copy(serialIds = serialIds.dropLast(1))
    ReceivingStep.CONFIRM -> this
}

/** Storage shape of a banked line. Quantity as a string: see StoredLine. */
private fun CapturedLine.toStored() = StoredLine(
    lineNumber = lineNumber,
    itemId = itemId,
    itemName = itemName,
    quantity = quantity.toPlainString(),
    unitSymbol = unitSymbol,
    locationId = locationId,
    batchId = batchId,
    batchExpiryDate = batchExpiryDate,
    serialIds = serialIds,
)

private fun StoredLine.toCaptured() = CapturedLine(
    lineNumber = lineNumber,
    itemId = itemId,
    itemName = itemName,
    quantity = quantityValue,
    unitSymbol = unitSymbol,
    locationId = locationId,
    batchId = batchId,
    batchExpiryDate = batchExpiryDate,
    serialIds = serialIds,
)

private const val GS1_AUTOFILL_DELAY_MS = 150L

/** How long a line picked in the item search stays the answer to "which line?". */
private const val SEARCHED_LINE_VALID_MS = 10_000L

/** See [ReceivingFlowViewModel.parseExpiryDate]. */
internal fun parseExpiry(value: String): String? {
    val text = value.trim()
    val (day, month, year) = Regex("""(\d{4})-(\d{2})-(\d{2})""").matchEntire(text)?.destructured?.let { (y, m, d) -> Triple(d, m, y) }
        ?: Regex("""(\d{1,2})[-/.](\d{1,2})[-/.](\d{4})""").matchEntire(text)?.destructured?.let { (d, m, y) -> Triple(d, m, y) }
        ?: Regex("""(\d{2})(\d{2})(\d{4})""").matchEntire(text)?.destructured?.let { (d, m, y) -> Triple(d, m, y) }
        ?: return null
    return runCatching { java.time.LocalDate.of(year.toInt(), month.toInt(), day.toInt()).toString() }.getOrNull()
}

/** YYYY-MM-DD as DD-MM-YYYY, for showing; anything else unchanged. */
internal fun displayDate(iso: String?): String? =
    iso?.let { Regex("""(\d{4})-(\d{2})-(\d{2})""").matchEntire(it)?.destructured?.let { (y, m, d) -> "$d-$m-$y" } ?: it }
