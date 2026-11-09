package com.d365fo.mobileops.presentation.modules.cyclecounting.store

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.d365fo.mobileops.data.counting.CountStagingRepository
import com.d365fo.mobileops.data.remote.dto.CountStageLineDto
import com.d365fo.mobileops.data.repository.OrderCreationRepository
import com.d365fo.mobileops.data.repository.OrderItem
import com.d365fo.mobileops.data.repository.OrderVariant
import com.d365fo.mobileops.data.repository.WorkerSessionRepository
import com.d365fo.mobileops.data.security.SecureStore
import com.d365fo.mobileops.data.sync.SyncStatusProvider
import com.d365fo.mobileops.domain.repository.OnDemandLookupRepository
import com.d365fo.mobileops.presentation.common.components.StatusKind
import com.d365fo.mobileops.presentation.common.scan.HidScannerBridge
import com.d365fo.mobileops.presentation.common.scan.RejectReason
import com.d365fo.mobileops.presentation.common.scan.ScanContext
import com.d365fo.mobileops.presentation.common.scan.ScanFeedback
import com.d365fo.mobileops.presentation.common.scan.ScanIdentity
import com.d365fo.mobileops.presentation.common.scan.ScanKind
import com.d365fo.mobileops.presentation.common.scan.ScanRoute
import com.d365fo.mobileops.presentation.common.scan.ScanRouter
import com.d365fo.mobileops.presentation.modules.cyclecounting.CountNotice
import com.d365fo.mobileops.presentation.modules.cyclecounting.countText
import com.d365fo.mobileops.presentation.modules.cyclecounting.nLines
import com.d365fo.mobileops.util.AppFailure
import com.d365fo.mobileops.util.Formatters
import com.d365fo.mobileops.util.NetworkResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject

/** One counted line of a store count, kept on the device until D365FO has it. */
@Serializable
data class StoreCountLine(
    val clientLineId: String,
    val itemId: String,
    val itemName: String,
    val configId: String = "",
    val sizeId: String = "",
    val colorId: String = "",
    val styleId: String = "",
    val batchId: String = "",
    val serialId: String = "",
    val locationId: String,
    val quantity: Double,
    val unitId: String,
    val countedAt: Long,
    val sent: Boolean = false,
    /** The bin it was counted in - for counting only (user, 8 Nov). */
    val binId: String = "",
)

/** The store count on this device: the stock count and staging journal, and the lines counted. */
@Serializable
data class StoreCountDraft(
    val warehouseId: String,
    val masterId: String = "",
    val stageId: String = "",
    val lines: List<StoreCountLine> = emptyList(),
    /**
     * Counting has started on the device. Offline the stock count is not opened yet ([stageId]
     * blank): it is opened in D365FO with the first send that gets through (user, 8 Nov: counting
     * should work offline).
     */
    val started: Boolean = false,
) {
    val counting: Boolean get() = started || stageId.isNotBlank()
}

/** An item scanned and waiting for its variant, batch or serial. */
data class PendingCount(
    val item: OrderItem,
    val variant: OrderVariant? = null,
    val batchId: String = "",
    val serialId: String = "",
    /** What this scan counts once it is complete - 1, a pack, or a GS1 label's count. */
    val quantity: Double = 1.0,
) {
    val needsVariant: Boolean get() = item.hasProductDimensions && variant == null
    val needsBatch: Boolean get() = item.batchActive && batchId.isBlank()
    val needsSerial: Boolean get() = item.serialActive && serialId.isBlank()

    /** Waiting for a scan (batch or serial), not a tap (the variant). */
    val needsScan: Boolean get() = !needsVariant && (needsBatch || needsSerial)
}

/** The last +1 (or keypad change), so Undo can take exactly that back. */
data class LastCount(
    val clientLineId: String,
    /** What the line held before; null when the scan made the line. */
    val before: Double?,
)

data class StoreCountState(
    val warehouseId: String? = null,
    val draft: StoreCountDraft? = null,
    val locationId: String = "",
    /** The bin being counted; blank while one is asked for. */
    val binId: String = "",
    /** The store has bins: a bin label sets location and bin. None: location, then items. */
    val binsRequired: Boolean = false,
    /** Where a count starts: the last place counted from, else the counting menu's location. */
    val startLocation: String? = null,
    val pending: PendingCount? = null,
    val busy: Boolean = false,
    val notice: CountNotice? = null,
    val ended: Boolean = false,
    /** The supervisor transferred or cancelled this stock count while lines were still on the device. */
    val countClosed: Boolean = false,
    /** Lines (F2) is open. */
    val showingLines: Boolean = false,
    /** Lines on their way to D365FO now. */
    val inFlight: Set<String> = emptySet(),
    val last: LastCount? = null,
    /** "Bin A1 · 3 lines sent" - the tray's last result. */
    val lastSent: String? = null,
) {
    val counting: Boolean get() = draft?.counting == true
    val unsent: Int get() = draft?.lines?.count { !it.sent } ?: 0
    val needsLocation: Boolean get() = locationId.isBlank()
    val needsBin: Boolean get() = !needsLocation && binsRequired && binId.isBlank()

    /** The lines of the place being counted now, newest first. */
    val here: List<StoreCountLine>
        get() = draft?.lines?.filter { it.isAt(locationId, binId) }.orEmpty()

    /** Lines counted earlier, in another place, and not with the supervisor yet - the tray's "waiting". */
    val waitingToSend: Int
        get() = draft?.lines?.count { !it.sent && (it.clientLineId in inFlight || !it.isAt(locationId, binId)) } ?: 0

    /** The line Undo and the keypad act on: the last one counted, while it is still on the device. */
    val lastLine: StoreCountLine?
        get() = last?.let { l -> draft?.lines?.firstOrNull { it.clientLineId == l.clientLineId } }
            ?.takeIf { !it.sent && it.clientLineId !in inFlight }
}

internal fun StoreCountLine.isAt(locationId: String, binId: String): Boolean =
    this.locationId.equals(locationId, ignoreCase = true) && this.binId.equals(binId, ignoreCase = true)

/** "STOCK › A1", or the location alone. */
internal val StoreCountLine.place: String get() = placeText(locationId, binId)

internal fun placeText(locationId: String, binId: String): String =
    if (binId.isBlank()) locationId else "$locationId › $binId"

/**
 * Adds [quantity] of an item at a place to the draft's lines - pure, so it is unit-tested. A line
 * for the same item, variant, batch, serial and place that is still on the device (and not being
 * sent right now) grows and moves to the top; otherwise a new line goes on top.
 *
 * @return the new lines and what Undo needs to take this back.
 */
internal fun addCount(
    lines: List<StoreCountLine>,
    counted: StoreCountLine,
    inFlight: Set<String> = emptySet(),
): Pair<List<StoreCountLine>, LastCount> {
    val same = lines.firstOrNull {
        !it.sent && it.clientLineId !in inFlight && it.sameThingAs(counted)
    }
    if (same == null) return (listOf(counted) + lines) to LastCount(counted.clientLineId, before = null)
    val grown = same.copy(quantity = same.quantity + counted.quantity, countedAt = counted.countedAt)
    return (listOf(grown) + lines.filterNot { it.clientLineId == same.clientLineId }) to LastCount(same.clientLineId, same.quantity)
}

private fun StoreCountLine.sameThingAs(other: StoreCountLine): Boolean =
    itemId.equals(other.itemId, ignoreCase = true) &&
        configId == other.configId && sizeId == other.sizeId && colorId == other.colorId && styleId == other.styleId &&
        batchId.equals(other.batchId, ignoreCase = true) &&
        // A serial is one unit: two scans of serials are two lines, never one line of 2.
        serialId.isBlank() && other.serialId.isBlank() &&
        isAt(other.locationId, other.binId)

/** Undo of [last]: the line back to what it held, or gone when the scan made it. Pure. */
internal fun undoCount(lines: List<StoreCountLine>, last: LastCount): List<StoreCountLine> = when (val before = last.before) {
    null -> lines.filterNot { it.clientLineId == last.clientLineId && !it.sent }
    else -> lines.map { if (it.clientLineId == last.clientLineId && !it.sent) it.copy(quantity = before) else it }
}

/**
 * What a store count accepts at each step (redesign brief §3-4) - pure, so it is unit-tested. A
 * bin label is the count's "document": another bin moves there and sends the bin before; an item
 * adds one.
 */
internal fun storeCountContext(s: StoreCountState): ScanContext.Flow = when {
    s.ended -> ScanContext.Flow(accepts = emptySet(), expecting = "This count has ended")
    // Before Start: a bin label (or location) starts the count there.
    !s.counting -> ScanContext.Flow(
        accepts = setOf(ScanKind.BIN, ScanKind.LOCATION),
        documentKind = ScanKind.BIN,
        expecting = "Tap Start, or scan a bin label",
    )
    // A batch or serial is free text no table knows: everything goes to the step.
    s.pending?.needsScan == true -> ScanContext.Flow(
        accepts = setOf(ScanKind.ITEM, ScanKind.LOCATION, ScanKind.BIN),
        acceptUnknown = true,
    )
    // A location the device doesn't list yet is still checked online.
    s.needsLocation -> ScanContext.Flow(
        accepts = setOf(ScanKind.BIN, ScanKind.LOCATION),
        documentKind = ScanKind.BIN,
        acceptUnknown = true,
        expecting = if (s.binsRequired) "Scan a bin label" else "Scan a location",
    )
    s.needsBin -> ScanContext.Flow(
        accepts = setOf(ScanKind.BIN, ScanKind.LOCATION),
        documentKind = ScanKind.BIN,
        expecting = "Scan a bin label",
    )
    // Counting: items add one; an item barcode not synced yet is still looked up online.
    else -> ScanContext.Flow(
        accepts = setOf(ScanKind.ITEM, ScanKind.LOCATION),
        documentKind = ScanKind.BIN,
        currentDocument = s.binId.ifBlank { null },
        acceptUnknown = true,
        expecting = "Scan an item",
    )
}

/**
 * Store count - a free count of the store for the Stock count control center (feedback, 11 Oct),
 * scan only since the redesign (docs/ui-redesign-brief.md, phase 6). A bin label sets the
 * location and bin, each item scan adds one, and the next bin label sends the bin before to this
 * counter's own journal in the store's stock count. The supervisor sees every counter's lines in
 * D365FO - items counted twice flagged - and transfers them into a counting journal. Nothing here
 * touches a counting journal itself.
 *
 * The staging calls ([CountStagingRepository.open] and [CountStagingRepository.submit]) and the
 * offline rules are unchanged: what changes is that scans come through [ScanRouter] and the
 * quantity is +1 per scan, with Undo and the keypad to correct it.
 */
@HiltViewModel
class StoreCountViewModel @Inject constructor(
    private val staging: CountStagingRepository,
    private val items: OrderCreationRepository,
    private val lookup: OnDemandLookupRepository,
    private val secureStore: SecureStore,
    private val workerSession: WorkerSessionRepository,
    private val scannerBridge: HidScannerBridge,
    private val scanRouter: ScanRouter,
    private val scanFeedback: ScanFeedback,
    private val syncStatus: SyncStatusProvider,
    private val locationDao: com.d365fo.mobileops.data.local.dao.LocationDao,
    private val binRepository: com.d365fo.mobileops.data.counting.CountBinRepository,
    private val menuRepository: com.d365fo.mobileops.data.repository.HandheldMenuRepository,
) : ViewModel() {

    private val json = Json { ignoreUnknownKeys = true }
    private val user = workerSession.session.value?.userName.orEmpty()
    private val warehouse = workerSession.session.value?.selected?.warehouseId
    private val draftKey = "store_count_draft::${warehouse.orEmpty().uppercase()}::${user.uppercase()}"
    private val startKey = "store_count_start::${warehouse.orEmpty().uppercase()}::${user.uppercase()}"

    /** Where a count starts: the place last started from, else the counting menu's location (user, 8 Nov). */
    private val menuStart = menuRepository.menu.value?.countStartLocation

    private val _state = MutableStateFlow(
        loadDraft().let { draft ->
            val start = secureStore.getString(startKey)?.takeIf { it.isNotBlank() } ?: menuStart
            StoreCountState(
                warehouseId = warehouse,
                draft = draft,
                startLocation = start,
                // Back into a count already started: in the start location again.
                locationId = if (draft?.counting == true) start.orEmpty() else "",
            )
        },
    )
    val state = _state.asStateFlow()

    /** The store's bins, with the location each is in. Kept on the device: works offline. */
    private var bins: Map<String, com.d365fo.mobileops.data.local.entity.CountBinEntity> = emptyMap()

    /** Items looked up this count - the second scan of an item needs no lookup. */
    private val itemCache = HashMap<String, OrderItem>()

    /** The last send could not reach D365FO; it is retried when the connection is back. */
    private var waitingForConnection = false

    /** End count was held offline: the retry ends the count too. */
    private var endWhenOnline = false

    /** D365FO answers: checked before every call, so offline never waits for a timeout. */
    private var online = true

    init {
        warehouse?.let { wh ->
            viewModelScope.launch { binRepository.refresh(wh) }
            viewModelScope.launch {
                binRepository.observe(wh).collect { rows ->
                    bins = rows.associateBy { it.binId }
                    _state.update { it.copy(binsRequired = rows.isNotEmpty()) }
                }
            }
        }
        // A send that failed for want of a connection goes again by itself once D365FO answers -
        // the offline notice promises it (test, 13 Oct: the counter had to tap Send again).
        viewModelScope.launch {
            syncStatus.status.map { it.fullyOnline }.distinctUntilChanged().collect { up ->
                online = up
                val s = _state.value
                if (up && waitingForConnection && !s.busy && (s.unsent > 0 || endWhenOnline)) send(end = endWhenOnline)
            }
        }
    }

    // -- scans (redesign phase 6: through ScanRouter) ------------------------------------------

    private var scanHandle: ScanRouter.Handle? = null

    /** Scans reach the count only while its screen is on top - see ReceivingFlowViewModel.scannerOnScreen. */
    fun scannerOnScreen(visible: Boolean) {
        if (visible) {
            if (scanHandle == null) scanHandle = scanRouter.attach({ storeCountContext(_state.value) }, ::onRoute)
        } else {
            scanHandle?.close()
            scanHandle = null
        }
    }

    override fun onCleared() {
        scanHandle?.close()
        super.onCleared()
    }

    /** See [HidScannerBridge.justScanned] - the screen's Back ignores the scanner's phantom back. */
    fun justScanned(): Boolean = scannerBridge.justScanned()

    fun onCameraDecoded(value: String) = scannerBridge.submitCameraScan(value)

    /** Typed in (⋮ Type it in): the same path as a scan. */
    fun onTyped(value: String) {
        if (value.isNotBlank()) scanRouter.submit(com.d365fo.mobileops.domain.model.RawScan(value.trim(), com.d365fo.mobileops.domain.model.ScanSource.MANUAL_ENTRY))
    }

    private suspend fun onRoute(route: ScanRoute) {
        val s = _state.value
        val identity = route.identity
        val raw = identity.raw.value.trim()
        when (route) {
            is ScanRoute.Accept, is ScanRoute.SwitchDocument -> when {
                !s.counting -> startAt(identity, raw)
                s.pending?.needsScan == true && identity !is ScanIdentity.Bin && identity !is ScanIdentity.Location -> fillPending(raw)
                identity is ScanIdentity.Bin -> moveToBin(identity.bin.binId, identity.bin.locationId)
                identity is ScanIdentity.Location -> moveToLocation(identity.locationId)
                s.needsLocation -> checkLocation(raw)
                identity is ScanIdentity.Item -> countItem(identity)
                else -> countUnknown(raw)
            }
            is ScanRoute.Leave -> bad(
                StatusKind.Warn,
                "That's " + when (identity.kind) {
                    ScanKind.PURCHASE_ORDER -> "a purchase order"
                    ScanKind.TRANSFER_ORDER -> "a transfer order"
                    else -> "a counting journal"
                },
                "Finish the store count, then scan it on Home",
            )
            is ScanRoute.Reject -> bad(
                if (route.reason == RejectReason.NOT_EXPECTED) StatusKind.Warn else StatusKind.Bad,
                route.title,
                if (route.reason == RejectReason.NOT_EXPECTED) route.line else "${route.line} - nothing was added",
            )
            else -> Unit
        }
    }

    private fun bad(kind: StatusKind, title: String, line: String?) {
        if (kind == StatusKind.Bad) scanFeedback.error() else scanFeedback.warning()
        _state.update { it.copy(notice = CountNotice(kind, title, line)) }
    }

    private fun info(kind: StatusKind, title: String, line: String? = null, undoable: Boolean = false) =
        _state.update { it.copy(notice = CountNotice(kind, title, line, undoable)) }

    // -- start --------------------------------------------------------------------------------

    private fun loadDraft(): StoreCountDraft? = runCatching {
        secureStore.getString(draftKey)?.let { json.decodeFromString(StoreCountDraft.serializer(), it) }
    }.getOrNull()

    private fun saveDraft(draft: StoreCountDraft?) {
        if (draft == null) secureStore.remove(draftKey) else secureStore.putString(draftKey, json.encodeToString(StoreCountDraft.serializer(), draft))
        _state.update { it.copy(draft = draft) }
    }

    /** The start location, tapped and changed on the start card - remembered for next time. */
    fun setStartLocation(value: String) {
        val id = value.trim().uppercase().ifBlank { return }
        secureStore.putString(startKey, id)
        _state.update { it.copy(startLocation = id) }
    }

    /** A bin label or location on the start screen: start, and count there. */
    private suspend fun startAt(identity: ScanIdentity, raw: String) {
        startNow()
        if (!_state.value.counting) return
        when (identity) {
            is ScanIdentity.Bin -> moveToBin(identity.bin.binId, identity.bin.locationId)
            is ScanIdentity.Location -> moveToLocation(identity.locationId)
            else -> checkLocation(raw)
        }
    }

    /** Opens (or rejoins) this counter's journal in the store's stock count. */
    fun start() {
        if (_state.value.busy) return
        viewModelScope.launch { startNow() }
    }

    private suspend fun startNow() {
        val wh = warehouse ?: return bad(StatusKind.Bad, "No warehouse", "No warehouse is assigned to you.")
        if (_state.value.counting) return
        // Offline: count on the device now; the stock count is opened with the first send.
        if (!online) return startOffline(wh)
        _state.update { it.copy(busy = true) }
        when (val opened = staging.open(wh)) {
            is NetworkResult.Success -> {
                val current = _state.value.draft ?: StoreCountDraft(warehouseId = wh)
                saveDraft(current.copy(masterId = opened.data.masterId, stageId = opened.data.stageId, started = true))
                scanFeedback.success()
                _state.update {
                    it.copy(
                        busy = false,
                        locationId = it.locationId.ifBlank { it.startLocation.orEmpty() },
                        notice = CountNotice(StatusKind.Info, "Counting for ${opened.data.masterId}", nextStep(it)),
                    )
                }
            }
            is NetworkResult.Error ->
                if (opened.failure.retryable) startOffline(wh)
                else {
                    _state.update { it.copy(busy = false) }
                    bad(StatusKind.Bad, "Couldn't start", opened.failure.message)
                }
            NetworkResult.Loading -> _state.update { it.copy(busy = false) }
        }
    }

    private fun startOffline(wh: String) {
        val current = _state.value.draft ?: StoreCountDraft(warehouseId = wh)
        saveDraft(current.copy(started = true))
        waitingForConnection = true
        _state.update {
            it.copy(
                busy = false,
                locationId = it.locationId.ifBlank { it.startLocation.orEmpty() },
                notice = CountNotice(StatusKind.Warn, "Offline - counting on this device", "Counts go to D365FO when it answers"),
            )
        }
    }

    private fun nextStep(s: StoreCountState): String = when {
        s.binsRequired -> "Scan a bin label"
        s.locationId.isBlank() && s.startLocation.isNullOrBlank() -> "Scan a location"
        else -> "Scan items"
    }

    // -- place --------------------------------------------------------------------------------

    /** A bin label: location and bin from the label, and the bin before goes to D365FO. */
    private fun moveToBin(binId: String, binLocation: String?) {
        val s = _state.value
        val location = binLocation?.takeIf { it.isNotBlank() } ?: s.locationId.ifBlank { s.startLocation.orEmpty() }
        if (location.isBlank()) {
            return bad(StatusKind.Warn, "Scan the location first", "Bin $binId has no location on the bin list")
        }
        if (binId.equals(s.binId, ignoreCase = true) && location.equals(s.locationId, ignoreCase = true)) {
            scanFeedback.success()
            return info(StatusKind.Info, "Counting $binId", "${nLines(s.here.size)} here - scan items")
        }
        val leaving = s.binId.takeIf { it.isNotBlank() }?.let { placeText(s.locationId, it) }
        _state.update { it.copy(locationId = location.uppercase(), binId = binId, pending = null, last = null) }
        rememberStart(location)
        scanFeedback.success()
        info(
            StatusKind.Info,
            "Counting $binId",
            listOfNotNull(location.uppercase(), leaving?.let { "$it sent" }).joinToString(" · "),
        )
        sendInBackground()
    }

    /** A location: counting there (and then a bin, where the store has bins). */
    private fun moveToLocation(locationId: String) {
        val s = _state.value
        val same = locationId.equals(s.locationId, ignoreCase = true)
        _state.update { it.copy(locationId = locationId, binId = if (same) it.binId else "", pending = null, last = if (same) it.last else null) }
        rememberStart(locationId)
        scanFeedback.success()
        val now = _state.value
        info(StatusKind.Info, "Counting $locationId", if (now.needsBin) "Scan a bin label" else "Scan items")
        if (!same) sendInBackground()
    }

    private fun rememberStart(locationId: String) {
        secureStore.putString(startKey, locationId.uppercase())
        _state.update { it.copy(startLocation = locationId.uppercase()) }
    }

    /** A location the device doesn't list: D365FO says whether it is one; offline it is taken as scanned. */
    private suspend fun checkLocation(value: String) {
        val wh = warehouse ?: return
        if (!online) {
            val known = runCatching { locationDao.find(wh, value) ?: locationDao.find(wh, value.uppercase()) }.getOrNull()
            return moveToLocation(known?.locationId ?: value.uppercase())
        }
        _state.update { it.copy(busy = true) }
        val checked = lookup.lookupLocation(wh, value)
        _state.update { it.copy(busy = false) }
        val known = (checked as? NetworkResult.Success)?.data
        when {
            checked is NetworkResult.Success && known == null ->
                bad(StatusKind.Bad, "Not recognised", "$value isn't a location or bin in $wh - nothing was added")
            else -> moveToLocation(known?.locationId ?: value.uppercase())
        }
    }

    // -- items --------------------------------------------------------------------------------

    private suspend fun countItem(identity: ScanIdentity.Item) {
        val code = identity.barcode?.barcode ?: identity.item.itemId
        val item = itemCache[code.uppercase()] ?: lookupItem(code) ?: return
        val barcodeVariant = identity.barcode?.let {
            OrderVariant(it.configId.orEmpty(), it.sizeId.orEmpty(), it.colorId.orEmpty(), it.styleId.orEmpty())
        }?.takeIf { it != OrderVariant() }
        val perScan = identity.labelQuantity?.takeIf { it.signum() > 0 }?.toDouble()
            ?: identity.barcode?.takeIf { b -> b.scanUnit.isNullOrBlank() || b.scanUnit.equals(item.inventUnit, ignoreCase = true) }
                ?.quantityPerScan?.toDoubleOrNull()?.takeIf { it > 0 }
            ?: 1.0
        val pending = PendingCount(
            item = item,
            variant = barcodeVariant ?: item.scannedVariant ?: if (!item.hasProductDimensions) OrderVariant() else item.variants.singleOrNull(),
            batchId = identity.batch.orEmpty(),
            serialId = identity.serial.orEmpty(),
            quantity = if (item.serialActive) 1.0 else perScan,
        )
        offer(pending)
    }

    /** A barcode the device doesn't know: D365FO may (not synced yet); otherwise a Bad band. */
    private suspend fun countUnknown(value: String) {
        val item = lookupItem(value) ?: return
        offer(
            PendingCount(
                item = item,
                variant = item.scannedVariant ?: if (!item.hasProductDimensions) OrderVariant() else item.variants.singleOrNull(),
            ),
        )
    }

    private suspend fun lookupItem(code: String): OrderItem? {
        _state.update { it.copy(busy = true) }
        val found = items.findItem(code, warehouse.orEmpty(), offline = !online)
        _state.update { it.copy(busy = false) }
        return when (found) {
            is NetworkResult.Success -> found.data.also { itemCache[code.uppercase()] = it }
            is NetworkResult.Error -> {
                bad(StatusKind.Bad, "Not recognised", "$code isn't an item, location or bin - nothing was added")
                null
            }
            NetworkResult.Loading -> null
        }
    }

    /** Counts the scan now, or asks for the one thing it still needs. */
    private fun offer(pending: PendingCount) {
        when {
            pending.needsVariant -> {
                scanFeedback.warning()
                _state.update { it.copy(pending = pending, notice = CountNotice(StatusKind.Warn, "Which variant?", "${pending.item.itemId} - pick it below")) }
            }
            pending.needsBatch -> {
                scanFeedback.warning()
                _state.update { it.copy(pending = pending, notice = CountNotice(StatusKind.Warn, "Scan the batch", "${pending.item.itemId} is batch-tracked")) }
            }
            pending.needsSerial -> {
                scanFeedback.warning()
                _state.update { it.copy(pending = pending, notice = CountNotice(StatusKind.Warn, "Scan the serial", "${pending.item.itemId} is serial-tracked")) }
            }
            else -> add(pending)
        }
    }

    private fun fillPending(value: String) {
        val pending = _state.value.pending ?: return
        offer(if (pending.needsBatch) pending.copy(batchId = value) else pending.copy(serialId = value))
    }

    fun pickVariant(variant: OrderVariant) {
        val pending = _state.value.pending ?: return
        offer(pending.copy(variant = variant))
    }

    fun cancelPending() = _state.update {
        it.copy(pending = null, notice = CountNotice(StatusKind.Info, "Nothing was added", "Scan the next item"))
    }

    /** One scan counted: +1 (or the pack) on the line for that item here. */
    private fun add(pending: PendingCount) {
        val s = _state.value
        val draft = s.draft ?: return
        val variant = pending.variant ?: OrderVariant()
        val counted = StoreCountLine(
            clientLineId = UUID.randomUUID().toString(),
            itemId = pending.item.itemId,
            itemName = pending.item.itemName,
            configId = variant.configId,
            sizeId = variant.sizeId,
            colorId = variant.colorId,
            styleId = variant.styleId,
            batchId = pending.batchId,
            serialId = pending.serialId,
            locationId = s.locationId,
            quantity = pending.quantity,
            unitId = pending.item.inventUnit,
            countedAt = System.currentTimeMillis(),
            binId = s.binId,
        )
        val (lines, last) = addCount(draft.lines, counted, s.inFlight)
        saveDraft(draft.copy(lines = lines))
        val line = lines.first()
        scanFeedback.success()
        _state.update {
            it.copy(
                pending = null,
                last = last,
                notice = CountNotice(
                    StatusKind.Ok,
                    "+${countText(pending.quantity)} ${line.itemId}",
                    listOfNotNull(
                        "${countText(line.quantity)} ${line.unitId} counted",
                        variant.label.takeIf { v -> v.isNotBlank() },
                        line.batchId.takeIf { b -> b.isNotBlank() }?.let { b -> "Batch $b" },
                        line.serialId.takeIf { b -> b.isNotBlank() }?.let { b -> "Serial $b" },
                    ).joinToString(" · "),
                    undoable = true,
                ),
            )
        }
    }

    // -- Undo, keypad, lines ------------------------------------------------------------------

    val canUndo: Boolean get() = _state.value.lastLine != null

    /** Takes back the last +1 (or keypad change). */
    fun undo() {
        val s = _state.value
        val last = s.last ?: return
        if (s.lastLine == null) return
        val draft = s.draft ?: return
        saveDraft(draft.copy(lines = undoCount(draft.lines, last)))
        _state.update { it.copy(last = null, notice = CountNotice(StatusKind.Info, "Undone", "Scan the next item")) }
    }

    /** What the keypad starts at - the last line's count; null when there is nothing to change. */
    val keypadLine: StoreCountLine? get() = _state.value.lastLine ?: _state.value.here.firstOrNull { !it.sent && it.clientLineId !in _state.value.inFlight }

    /** The keypad overrides the last line's count; 0 takes the line off. */
    fun applyKeypad(text: String) {
        val line = keypadLine ?: return
        val qty = text.toDoubleOrNull()?.takeIf { it >= 0 } ?: return
        val draft = _state.value.draft ?: return
        val lines = if (qty == 0.0) {
            draft.lines.filterNot { it.clientLineId == line.clientLineId }
        } else {
            draft.lines.map { if (it.clientLineId == line.clientLineId) it.copy(quantity = qty) else it }
        }
        saveDraft(draft.copy(lines = lines))
        _state.update {
            it.copy(
                last = if (qty == 0.0) null else LastCount(line.clientLineId, line.quantity),
                notice = if (qty == 0.0) {
                    CountNotice(StatusKind.Info, "${line.itemId} taken off", line.place)
                } else {
                    CountNotice(StatusKind.Ok, "${line.itemId}: ${countText(qty)} ${line.unitId}", "Changed from ${countText(line.quantity)}", undoable = true)
                },
            )
        }
    }

    fun keypadError(text: String): String? {
        val qty = text.toDoubleOrNull() ?: return "Enter a number"
        if (qty < 0) return "Can't be negative"
        if (keypadLine?.serialId?.isNotBlank() == true && qty > 1) return "A serial is one unit"
        return null
    }

    fun showLines(show: Boolean) = _state.update { it.copy(showingLines = show) }

    /** Removes a line that has not been sent yet (Lines, F2). */
    fun remove(line: StoreCountLine) {
        val draft = _state.value.draft ?: return
        if (line.sent || line.clientLineId in _state.value.inFlight) return
        saveDraft(draft.copy(lines = draft.lines.filterNot { it.clientLineId == line.clientLineId }))
        _state.update { it.copy(last = if (it.last?.clientLineId == line.clientLineId) null else it.last) }
    }

    // -- sending ------------------------------------------------------------------------------

    /**
     * There is no Send button (user, 8 Nov): what was counted goes to the supervisor by itself
     * each time the counter moves on to another bin or location, and with End count. Offline it
     * stays on the device and goes when D365FO answers.
     */
    private fun sendInBackground() {
        val s = _state.value
        if (s.draft?.lines?.none { !it.sent && it.clientLineId !in s.inFlight } != false || !s.counting) return
        if (!online) {
            waitingForConnection = true
            return
        }
        if (!s.busy) send(end = false)
    }

    /** End count (held): every line still on the device goes, and the count ends for this counter. */
    fun endCount() = send(end = true)

    /** Sends the unsent lines; [end] also ends this counter's count. */
    fun send(end: Boolean) {
        val started = _state.value.draft ?: return
        if (!started.counting) return
        if (!online) {
            waitingForConnection = true
            if (end) endWhenOnline = true
            return info(
                StatusKind.Warn,
                "Offline - kept on this device",
                "Your counts are " + (if (endWhenOnline) "sent and the count ended" else "sent") + " when D365FO answers",
            )
        }
        val unsent = started.lines.filter { !it.sent && it.clientLineId !in _state.value.inFlight }
        if (unsent.isEmpty() && !end) return
        val sending = unsent.map { it.clientLineId }.toSet()
        _state.update { it.copy(inFlight = it.inFlight + sending, busy = it.busy || end) }
        viewModelScope.launch {
            try {
                sendNow(started, unsent, end)
            } finally {
                _state.update { it.copy(inFlight = it.inFlight - sending, busy = false) }
            }
        }
    }

    private suspend fun sendNow(started: StoreCountDraft, unsent: List<StoreCountLine>, end: Boolean) {
        // Started offline: the stock count is opened now, with this first send.
        val masterId = if (started.stageId.isNotBlank()) started.masterId else null
        val stageId = started.stageId.ifBlank {
            when (val opened = staging.open(started.warehouseId)) {
                is NetworkResult.Success -> {
                    _state.value.draft?.let { saveDraft(it.copy(masterId = opened.data.masterId, stageId = opened.data.stageId)) }
                    opened.data.stageId
                }
                is NetworkResult.Error -> {
                    waitingForConnection = opened.failure.retryable
                    if (end && waitingForConnection) endWhenOnline = true
                    return info(
                        StatusKind.Warn,
                        "Not sent yet",
                        opened.failure.message + " Your counts are kept on this device" +
                            if (waitingForConnection) " and sent when the connection is back." else ".",
                    )
                }
                NetworkResult.Loading -> return
            }
        }
        val result = staging.submit(
            stageId = stageId,
            submissionId = UUID.randomUUID().toString(),
            lines = unsent.map { it.toDto() },
            endCount = end,
        )
        val master = masterId ?: _state.value.draft?.masterId.orEmpty()
        when (result) {
            is NetworkResult.Success -> {
                waitingForConnection = false
                endWhenOnline = false
                val sentIds = unsent.map { it.clientLineId }.toSet()
                // The whole stock count's figure, every counter's lines - not just these (test, 8 Nov).
                val dupNote = if (result.data.duplicates > 0) "${result.data.duplicates} item(s) in $master counted more than once - your supervisor decides" else null
                val places = unsent.map { it.place }.distinct()
                val sentWhat = "${nLines(unsent.size)} sent" + (places.singleOrNull()?.let { " · $it" } ?: "")
                if (end) {
                    val total = _state.value.draft?.lines?.size ?: unsent.size
                    saveDraft(null)
                    scanFeedback.success()
                    _state.update {
                        it.copy(
                            ended = true,
                            last = null,
                            lastSent = "Count $master ended · ${nLines(total)}",
                            notice = CountNotice(StatusKind.Ok, "Count ended", listOfNotNull("${nLines(total)} with your supervisor", dupNote).joinToString(" · ")),
                        )
                    }
                } else {
                    _state.value.draft?.let { d ->
                        saveDraft(d.copy(lines = d.lines.map { if (it.clientLineId in sentIds) it.copy(sent = true) else it }))
                    }
                    _state.update { s ->
                        s.copy(
                            lastSent = "$sentWhat · $master",
                            last = s.last?.takeIf { it.clientLineId !in sentIds },
                            notice = dupNote?.let { CountNotice(StatusKind.Warn, "Counted twice", it) } ?: s.notice,
                        )
                    }
                }
            }
            is NetworkResult.Error -> {
                waitingForConnection = result.failure is AppFailure.Offline || result.failure is AppFailure.Transient
                if (end && waitingForConnection) endWhenOnline = true
                val code = (result.failure as? AppFailure.BusinessRule)?.d365ErrorCode
                val closed = code == "COUNT_TRANSFERRED" || code == "COUNT_CANCELLED"
                if (closed && unsent.isEmpty()) {
                    // Everything was already sent: nothing to keep or move, the count is over (test, 13 Oct).
                    saveDraft(null)
                    _state.update { it.copy(ended = true, notice = CountNotice(StatusKind.Info, "Count ended", result.failure.message.substringBefore(" - "))) }
                } else {
                    if (!waitingForConnection) scanFeedback.error()
                    _state.update {
                        it.copy(
                            countClosed = closed,
                            notice = CountNotice(
                                if (waitingForConnection) StatusKind.Warn else StatusKind.Bad,
                                if (closed) "This stock count is closed" else "Not sent yet",
                                result.failure.message + " Your counts are kept on this device" +
                                    if (waitingForConnection) " and sent when the connection is back." else ".",
                            ),
                        )
                    }
                }
            }
            NetworkResult.Loading -> Unit
        }
    }

    /**
     * The stock count was transferred or cancelled with lines still on the device (test, 13 Oct:
     * every send was then refused and the lines were stuck): they move to the store's next stock
     * count - D365FO opens one when none is open - and the lines already sent stay with the old one.
     */
    fun moveToNewCount() {
        val draft = _state.value.draft ?: return
        saveDraft(draft.copy(masterId = "", stageId = "", started = false, lines = draft.lines.filterNot { it.sent }))
        _state.update { it.copy(countClosed = false, notice = null) }
        start()
    }

    private fun StoreCountLine.toDto() = CountStageLineDto(
        clientLineId = clientLineId,
        itemId = itemId,
        configId = configId.ifBlank { null },
        sizeId = sizeId.ifBlank { null },
        colorId = colorId.ifBlank { null },
        styleId = styleId.ifBlank { null },
        batchId = batchId.ifBlank { null },
        serialId = serialId.ifBlank { null },
        locationId = locationId.ifBlank { null },
        binId = binId.ifBlank { null },
        quantity = quantity,
        unitId = unitId,
        countedAtUtc = Formatters.isoUtc(countedAt),
    )
}

