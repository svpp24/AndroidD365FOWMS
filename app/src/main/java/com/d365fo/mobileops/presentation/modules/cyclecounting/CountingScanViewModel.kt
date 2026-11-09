package com.d365fo.mobileops.presentation.modules.cyclecounting

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.d365fo.mobileops.data.counting.CountCaptureActions
import com.d365fo.mobileops.data.counting.UnexpectedItemResult
import com.d365fo.mobileops.data.local.dao.CountLine
import com.d365fo.mobileops.data.local.dao.CountingDao
import com.d365fo.mobileops.data.local.dao.JournalDao
import com.d365fo.mobileops.data.local.entity.CountExceptionEntity
import com.d365fo.mobileops.data.local.entity.JournalEntity
import com.d365fo.mobileops.data.repository.WorkerSessionRepository
import com.d365fo.mobileops.domain.FeatureFlags
import com.d365fo.mobileops.data.local.dao.ItemDao
import com.d365fo.mobileops.presentation.common.scan.HidScannerBridge
import com.d365fo.mobileops.presentation.common.scan.ScanFeedback
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import com.d365fo.mobileops.presentation.common.components.StatusKind
import com.d365fo.mobileops.presentation.common.scan.BinRef
import com.d365fo.mobileops.presentation.common.scan.RejectReason
import com.d365fo.mobileops.presentation.common.scan.ScanContext
import com.d365fo.mobileops.presentation.common.scan.ScanIdentity
import com.d365fo.mobileops.presentation.common.scan.ScanKind
import com.d365fo.mobileops.presentation.common.scan.ScanRoute
import com.d365fo.mobileops.presentation.common.scan.ScanRouter
import java.math.BigDecimal
import javax.inject.Inject

/**
 * What the last scan did, shown under the scan box. [unexpectedItemId] non-null offers
 * "Not on this journal" for that item.
 */
data class ScanMessage(
    val text: String,
    val isError: Boolean,
    val unexpectedItemId: String? = null,
    /** Shows Undo: the scan just added to a count (see [CountingScanViewModel.undoLastScan]). */
    val undoable: Boolean = false,
    /** The cycle-count flow's band (redesign phase 6): a short title and a line; [text] when null. */
    val title: String? = null,
    val line: String? = null,
    /** Not wrong, but not what this step needs - a Warn band rather than a Bad one. */
    val warn: Boolean = false,
)

/** The quantity popup: which line, and what to start the box at. */
data class CountPrompt(val line: CountLine, val initial: String)

/** One location on the journal, with how many of its lines have a count. */
data class LocationChoice(val locationId: String, val counted: Int, val total: Int)

/** "Location is empty?" - which location, how many lines, and how many already have a count. */
data class EmptyLocationPrompt(val locationId: String, val lines: Int, val alreadyCounted: Int)

/** The "Not on this journal" form, prefilled from the scan. */
/** An item not on the journal that comes in variants, waiting for the one found. */
data class UnexpectedVariantChoice(val itemId: String, val variants: List<com.d365fo.mobileops.domain.model.ProductVariant>, val locationId: String?)

data class UnexpectedItemPrompt(
    val itemId: String,
    val locationId: String?,
    val error: String? = null,
    /** The item's variants (offline copy) - one must be chosen when there are any. */
    val variants: List<com.d365fo.mobileops.domain.model.ProductVariant> = emptyList(),
    /** Preselected: the variant the scanned barcode belongs to. */
    val variant: com.d365fo.mobileops.domain.model.ProductVariant? = null,
)

/**
 * The "Start counting" page: the journal's items in a light list, a scan box, and a quantity
 * popup. Scan (hardware, camera, or type and press Enter) an item or barcode and a popup asks for
 * the quantity; OK saves it and puts the cursor back in the scan box. Tapping a row opens the
 * same popup to correct it.
 *
 * Location-first ([FeatureFlags.countLocationFirst], on by default for journals the server marks
 * so): scan a location first and the list narrows to that location; items then match only there,
 * and "Location is empty" counts every line there as zero once confirmed. A scan that is not on
 * the journal - or not at this location - offers "Not on this journal"
 * ([FeatureFlags.countExceptions]), which records it as an exception for the server to resolve;
 * no journal line is ever added on the device.
 *
 * Blind throughout: lines come from [CountLine], which carries no on-hand quantity.
 */
@HiltViewModel
class CountingScanViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val journalDao: JournalDao,
    private val countingDao: CountingDao,
    private val workerSession: WorkerSessionRepository,
    private val itemDao: ItemDao,
    private val scannerBridge: HidScannerBridge,
    private val scanFeedback: ScanFeedback,
    private val capture: CountCaptureActions,
    private val flags: FeatureFlags,
    private val itemUnits: com.d365fo.mobileops.data.repository.ItemUnitRepository,
    private val binRepository: com.d365fo.mobileops.data.counting.CountBinRepository,
    private val countSubmissions: com.d365fo.mobileops.data.counting.CountSubmissions,
    private val scanRouter: com.d365fo.mobileops.presentation.common.scan.ScanRouter,
) : ViewModel() {

    val journalId: String = checkNotNull(savedStateHandle["journalId"])

    private val _query = MutableStateFlow("")
    private val _prompt = MutableStateFlow<PromptRequest?>(null)
    private val _choiceIds = MutableStateFlow<List<String>?>(null)
    private val _message = MutableStateFlow<ScanMessage?>(null)
    private val _ui = MutableStateFlow(LocationUi())

    /** The store's bins (none: the store counts by location alone), and the bin being counted. */
    private val _bins = MutableStateFlow<List<String>>(emptyList())

    /** The store's bins with the location each is in. */
    private var binRows: Map<String, com.d365fo.mobileops.data.local.entity.CountBinEntity> = emptyMap()
    private val _currentBin = MutableStateFlow<String?>(null)

    /** The store's bins with their locations, for the cycle-count flow's walking order. */
    private val _binRefs = MutableStateFlow<List<BinRef>>(emptyList())
    private val _addBinPrompt = MutableStateFlow<String?>(null)

    /** Where Change location / Change bin started, so Cancel can go back to it (user, 8 Nov). */
    private data class Back(val location: String?, val bin: String?)
    private val _back = MutableStateFlow<Back?>(null)

    /** Bumped whenever the scan box should take focus back (after the popup closes). */
    private val _refocus = MutableStateFlow(0)
    private var scanJob: Job? = null

    /** The count is done here - back to the journal list (a focused check sent, a wall-to-wall location settled). */
    private val _leave = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    val leave: kotlinx.coroutines.flow.Flow<Unit> = _leave.receiveAsFlow()

    private data class PromptRequest(val lineId: String, val initial: String)

    /** Location-first and exceptions UI state. [byLocation] null means "the journal's default". */
    private data class LocationUi(
        val byLocation: Boolean? = null,
        val selectedLocation: String? = null,
        val emptyPrompt: EmptyLocationPrompt? = null,
        val unexpected: UnexpectedItemPrompt? = null,
        /** An item not on the journal that comes in variants: which one was found. */
        val variantChoice: UnexpectedVariantChoice? = null,
        /** The not-on-the-journal row being edited (its quantity), by client id. */
        val editingException: String? = null,
        /** Show only lines of this location type (null = all). */
        val typeFilter: String? = null,
        /** A verify-only line waiting for "Confirm present". */
        val verifyLineId: String? = null,
    )

    private data class Base(
        val lines: List<CountLine>,
        val journal: JournalEntity?,
        val exceptions: List<CountExceptionEntity>,
        val query: String,
    )

    private val base = combine(
        countingDao.observeCountLines(journalId),
        journalDao.observeCountingJournals().map { list -> list.firstOrNull { it.journalId == journalId } },
        countingDao.observeExceptions(journalId),
        _query,
    ) { lines, journal, exceptions, query -> Base(lines, journal, exceptions, query) }

    private data class Overlay(
        val prompt: PromptRequest?,
        val choiceIds: List<String>?,
        val message: ScanMessage?,
        val refocus: Int,
        val ui: LocationUi,
        val bins: List<String>,
    )

    private val overlay = combine(_prompt, _choiceIds, _message, _refocus, combine(_ui, _bins) { ui, bins -> ui to bins }) { prompt, choice, message, refocus, (ui, bins) ->
        Overlay(prompt, choice, message, refocus, ui, bins)
    }

    val state: StateFlow<CountingScanState> = combine(base, overlay) { b, o ->
        val byId = b.lines.associateBy { it.journalLineId }
        // Wall-to-wall is counted location by location, with Location is empty: always.
        val locationAvailable = flags.countLocationFirst ||
            b.journal?.originType == com.d365fo.mobileops.domain.model.ORIGIN_WALL_TO_WALL
        // Location, then bin, then item at a store with bins (user, 8 Nov: a location holds
        // several bins), and wall-to-wall always: neither offers the By location switch.
        val binsByLocation = o.bins.isNotEmpty() && b.lines.any { !it.locationId.isNullOrBlank() }
        val byLocation = b.journal?.originType == com.d365fo.mobileops.domain.model.ORIGIN_WALL_TO_WALL || binsByLocation ||
            (locationAvailable && (o.ui.byLocation ?: (b.journal?.locationFirst == true)))
        // A journal of one location (a wall-to-wall task always) starts in it rather than asking
        // for it: one step fewer (user, 8 Nov: too many steps).
        val taskLocation = b.lines
            .mapNotNull { it.locationId?.takeIf { l -> l.isNotBlank() } }
            .distinctBy { it.uppercase() }.singleOrNull()
        val location = (o.ui.selectedLocation ?: taskLocation).takeIf { byLocation }
        val atLocation = location?.let { loc -> b.lines.filter { it.locationId.equals(loc, ignoreCase = true) } }
        val scope = (atLocation ?: b.lines).filter { o.ui.typeFilter == null || it.locationType == o.ui.typeFilter }
        CountingScanState(
            loaded = b.journal != null,
            journalDescription = b.journal?.description ?: journalId,
            originType = b.journal?.originType ?: com.d365fo.mobileops.domain.model.ORIGIN_SCHEDULED,
            totalLines = b.lines.size,
            counted = b.lines.count { it.isCounted },
            locked = b.journal?.status in LOCKED_STATUSES,
            lines = if (b.query.isBlank()) scope else scope.filter { it.matches(b.query) },
            query = b.query,
            prompt = o.prompt?.let { p -> byId[p.lineId]?.let { CountPrompt(it, p.initial) } },
            choice = o.choiceIds?.mapNotNull { byId[it] }.orEmpty(),
            message = o.message,
            refocusNonce = o.refocus,
            locationModeAvailable = locationAvailable,
            byLocation = byLocation,
            selectedLocation = location,
            locationCounted = atLocation?.count { it.isCounted } ?: 0,
            locationTotal = atLocation?.size ?: 0,
            zeroStockCheck = b.journal?.zeroStockCheck == true,
            exceptionsEnabled = flags.countExceptions,
            exceptions = b.exceptions,
            emptyPrompt = o.ui.emptyPrompt,
            unexpected = o.ui.unexpected,
            variantChoice = o.ui.variantChoice,
            editingException = b.exceptions.firstOrNull { it.clientId == o.ui.editingException },
            freezeBanner = b.journal?.let { j ->
                com.d365fo.mobileops.domain.model.freezeBanner(j.freezeState, j.snapshotAt) {
                    com.d365fo.mobileops.util.Formatters.clockTime(it)
                }
            },
            locationTypes = b.lines.map { it.locationType }.distinct().sorted().takeIf { it.size > 1 }.orEmpty(),
            locations = b.lines.filter { !it.locationId.isNullOrBlank() }
                .groupBy { it.locationId!!.uppercase() }
                .map { (_, at) -> LocationChoice(at.first().locationId!!, at.count { it.isCounted }, at.size) }
                .sortedBy { it.locationId },
            typeFilter = o.ui.typeFilter,
            verifyLine = o.ui.verifyLineId?.let { byId[it] },
        )
    }.combine(combine(_bins, _currentBin, _addBinPrompt, _back) { bins, bin, add, back -> listOf(bins, bin, add, back) }) { s, values ->
        @Suppress("UNCHECKED_CAST") val bins = values[0] as List<String>
        val back = values[3] as Back?
        s.copy(
            binsRequired = bins.isNotEmpty(),
            currentBin = values[1] as String?,
            addBinPrompt = values[2] as String?,
            changingFrom = back?.let { listOfNotNull(it.location, it.bin).joinToString("  \u203A  ") }
                ?.ifEmpty { null },
            layout = com.d365fo.mobileops.domain.model.countingLayout(s.originType, s.totalLines, bins.isNotEmpty()),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CountingScanState())

    init {
        viewModelScope.launch {
            val store = journalDao.getJournal(journalId)?.journal?.warehouseId?.takeIf { it.isNotBlank() }
                ?: return@launch
            launch { binRepository.refresh(store) }
            binRepository.observe(store).collect { rows ->
                binRows = rows.associateBy { it.binId }
                _binRefs.value = rows.map { com.d365fo.mobileops.presentation.common.scan.BinRef(it.binId, it.locationId) }
                _bins.value = rows.map { it.binId }
            }
        }
    }

    // -- Cycle-count flow (redesign phase 6) ------------------------------------------------------

    /** The line the last scan counted - the card shows it, F4 opens it. */
    private val _lastCountedId = MutableStateFlow<String?>(null)

    /** The flow screen is on top: misses add nothing, bins settle by a second scan. */
    private var flowMode = false
    private var missVariant: com.d365fo.mobileops.domain.model.ProductVariant? = null

    /** The last +1's write - the next scan waits for it, so two quick scans are two. */
    private var lastWrite: Job? = null

    /** The last settled stop, for Undo. */
    private var lastSettle: SettledStop? = null

    private var scanHandle: ScanRouter.Handle? = null

    /**
     * Scans reach this journal through [ScanRouter] while one of its screens is on top. [flow] is
     * the redesigned cycle-count screen; the legacy screen (wall-to-wall, one-line checks) gets
     * every scan as it always did.
     */
    fun scannerOnScreen(visible: Boolean, flow: Boolean) {
        if (visible) {
            flowMode = flow
            scanHandle?.close()
            scanHandle = if (flow) {
                scanRouter.attach({ cycleCountContext() }, ::onFlowRoute)
            } else {
                scanRouter.attach(
                    ScanContext.Flow(accepts = ScanKind.entries.toSet(), acceptUnknown = true),
                ) { route -> onScanned(route.identity.raw.value) }
            }
        } else {
            scanHandle?.close()
            scanHandle = null
        }
    }

    override fun onCleared() {
        scanHandle?.close()
        super.onCleared()
    }

    /** See [HidScannerBridge.justScanned]. */
    fun justScanned(): Boolean = scannerBridge.justScanned()

    /** Typed in (⋮ Type it in): the same path as a scan. */
    fun onTyped(value: String) {
        if (value.isNotBlank()) {
            scanRouter.submit(com.d365fo.mobileops.domain.model.RawScan(value.trim(), com.d365fo.mobileops.domain.model.ScanSource.MANUAL_ENTRY))
        }
    }

    private fun cycleCountContext() = ScanContext.Flow(
        accepts = setOf(ScanKind.ITEM, ScanKind.BIN, ScanKind.LOCATION),
        // A serial, or an item barcode the device doesn't have yet, still goes to the journal's lines.
        acceptUnknown = true,
        expecting = "Scan a bin label or an item",
    )

    /** Where the flow is: the walking order, what is settled, where the worker is now. */
    val flow: StateFlow<CycleFlowInfo> = combine(
        countingDao.observeCountLines(journalId),
        _binRefs,
        _currentBin,
        state.map { it.selectedLocation to it.byLocation }.distinctUntilChanged(),
        _lastCountedId,
    ) { lines, bins, bin, place, lastId -> FlowInputs(lines, bins, bin, place.first, place.second, lastId) }
        .map { i ->
            val lineBins = countingDao.getLineBins(journalId)
            val mode = when {
                i.bins.isNotEmpty() -> StopMode.BIN
                i.byLocation -> StopMode.LOCATION
                else -> StopMode.WHOLE
            }
            val last = i.lines.firstOrNull { it.journalLineId == i.lastId }
            cycleFlowInfo(
                lines = i.lines,
                lineBins = lineBins,
                bins = i.bins,
                mode = mode,
                location = i.location,
                bin = i.bin,
                lastCounted = last,
                lastCountedQuantity = last?.let { l ->
                    if (i.bin != null) {
                        lineBins.firstOrNull { it.journalLineId == l.journalLineId && it.binId.equals(i.bin, ignoreCase = true) }?.quantity
                    } else {
                        l.countedQuantity
                    }
                },
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CycleFlowInfo())

    private data class FlowInputs(
        val lines: List<CountLine>,
        val bins: List<BinRef>,
        val bin: String?,
        val location: String?,
        val byLocation: Boolean,
        val lastId: String?,
    )

    private fun popupOpen(): Boolean {
        val ui = _ui.value
        return _prompt.value != null || _choiceIds.value != null || ui.emptyPrompt != null || ui.unexpected != null ||
            ui.variantChoice != null || ui.verifyLineId != null || _addBinPrompt.value != null
    }

    private fun note(kind: StatusKind, title: String, line: String? = null, undoable: Boolean = false) {
        val bad = kind == StatusKind.Bad
        val warn = kind == StatusKind.Warn
        when {
            bad -> scanFeedback.error()
            warn -> scanFeedback.warning()
            else -> scanFeedback.success()
        }
        _message.value = ScanMessage(
            listOfNotNull(title, line).joinToString(" - "),
            isError = bad || warn,
            undoable = undoable,
            title = title,
            line = line,
            warn = warn,
        )
    }

    private suspend fun onFlowRoute(route: ScanRoute) {
        lastWrite?.join()
        val identity = route.identity
        val raw = identity.raw.value.trim()
        if (popupOpen()) {
            scanFeedback.warning()
            return
        }
        if (state.value.locked) return note(StatusKind.Warn, "Submitted", "Reopen it on the list to change")
        when (route) {
            is ScanRoute.Reject -> {
                val expected = route.reason == RejectReason.NOT_EXPECTED
                note(
                    if (expected) StatusKind.Warn else StatusKind.Bad,
                    route.title,
                    route.line + if (expected) "" else " - nothing was added",
                )
            }
            is ScanRoute.Leave -> note(
                StatusKind.Warn,
                if (identity.kind == ScanKind.COUNT_JOURNAL) "That's another journal" else "That's another document",
                "Finish this count, then open it",
            )
            is ScanRoute.Accept, is ScanRoute.SwitchDocument -> when (identity) {
                is ScanIdentity.Bin -> if (_bins.value.isNotEmpty()) scanBin(identity.bin) else handleScan(raw, typed = false)
                is ScanIdentity.Location -> scanLocation(identity.locationId, raw)
                else -> handleScan(raw, typed = false)
            }
            else -> Unit
        }
        lastWrite?.join()
    }

    /** A bin label: the current one again settles it; another settles the current one and moves there. */
    private suspend fun scanBin(ref: BinRef) {
        val bin = com.d365fo.mobileops.data.counting.normalizeBin(ref.binId)
        val lines = countingDao.getCountLines(journalId)
        val current = state.value
        val lineLocations = lines.mapNotNull { it.locationId?.takeIf { l -> l.isNotBlank() } }.distinctBy { it.uppercase() }
        val location = ref.locationId?.takeIf { it.isNotBlank() }
            ?.let { loc -> lineLocations.firstOrNull { it.equals(loc, ignoreCase = true) } ?: loc }
            ?: current.selectedLocation ?: lineLocations.singleOrNull()
        val stop = CountStop.of(location, bin)
        val info = flow.value
        if (info.stops.isNotEmpty() && stop !in info.stops) {
            return note(
                StatusKind.Warn,
                "$bin isn't on this count",
                listOfNotNull(ref.locationId?.let { "It is in $it" }, info.nextAfter(info.current)?.let { "Go to ${it.label}" })
                    .joinToString(" · ").ifBlank { null },
            )
        }
        val here = _currentBin.value
        if (here != null && here.equals(bin, ignoreCase = true) &&
            (location == null || location.equals(current.selectedLocation, ignoreCase = true))
        ) {
            // The same label twice: this bin is done - an empty one at 0.
            val settled = settle(stop)
            _currentBin.value = null
            _lastCountedId.value = null
            val next = info.copy(settled = info.settled + stop).nextAfter(stop)
            return note(
                StatusKind.Ok,
                "$bin done",
                listOfNotNull(settled.describe(), next?.let { "Go to ${it.label}" } ?: "Every bin is counted").joinToString(" · "),
                undoable = settled.written.isNotEmpty(),
            )
        }
        val left = here?.let { settle(CountStop.of(current.selectedLocation, it)) }
        if (location != null && !location.equals(current.selectedLocation, ignoreCase = true)) {
            _ui.update { it.copy(selectedLocation = location) }
        }
        _currentBin.value = bin
        _back.value = null
        _lastCountedId.value = null
        _query.value = ""
        note(
            StatusKind.Info,
            "Counting $bin",
            listOfNotNull(location, "Scan each item", left?.let { "${it.stop.label} done" }).joinToString(" · "),
            undoable = left?.written?.isNotEmpty() == true,
        )
        _refocus.update { it + 1 }
    }

    /** A location label: with bins, it narrows to that location's bins; without, it works like a bin. */
    private suspend fun scanLocation(locationId: String, raw: String) {
        val current = state.value
        if (!current.byLocation && _bins.value.isEmpty()) return handleScan(raw, typed = false)
        val lines = countingDao.getCountLines(journalId)
        val info = flow.value
        val location = lines.firstOrNull { it.locationId.equals(locationId, ignoreCase = true) }?.locationId
            ?: return note(StatusKind.Warn, "$locationId isn't on this count", info.nextAfter(info.current)?.let { "Go to ${it.label}" })
        val here = current.selectedLocation
        if (_bins.value.isNotEmpty()) {
            val left = _currentBin.value?.let { settle(CountStop.of(here, it)) }
            _currentBin.value = null
            _lastCountedId.value = null
            _ui.update { it.copy(selectedLocation = location) }
            return note(
                StatusKind.Info,
                "Counting $location",
                listOfNotNull("Scan a bin label", left?.let { "${it.stop.label} done" }).joinToString(" · "),
                undoable = left?.written?.isNotEmpty() == true,
            )
        }
        if (here != null && here.equals(location, ignoreCase = true)) {
            val stop = CountStop.of(location, null)
            val settled = settle(stop)
            _ui.update { it.copy(selectedLocation = null) }
            _lastCountedId.value = null
            val next = info.copy(settled = info.settled + stop).nextAfter(stop)
            return note(
                StatusKind.Ok,
                "$location done",
                listOfNotNull(settled.describe(), next?.let { "Go to ${it.label}" } ?: "Every location is counted").joinToString(" · "),
                undoable = settled.written.isNotEmpty(),
            )
        }
        val left = here?.let { settle(CountStop.of(it, null)) }
        _ui.update { it.copy(selectedLocation = location) }
        _lastCountedId.value = null
        note(
            StatusKind.Info,
            "Counting $location",
            listOfNotNull("Scan each item", left?.let { "${it.stop.label} done" }).joinToString(" · "),
            undoable = left?.written?.isNotEmpty() == true,
        )
    }

    /** What settling wrote: each line that had nothing counted in the stop, set to 0 there. */
    private data class SettledStop(val stop: CountStop, val written: List<String>, val counted: Int) {
        fun describe(): String = when {
            counted == 0 -> "Empty - counted 0"
            else -> "${if (counted == 1) "1 item" else "$counted items"} counted" + if (written.isNotEmpty()) ", ${written.size} at 0" else ""
        }
    }

    /**
     * Settles a stop: every line there with nothing counted in it gets 0 there - a bin's own row,
     * or the line's count where there are no bins. Nothing already counted changes.
     */
    private suspend fun settle(stop: CountStop): SettledStop = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
        val by = workerSession.session.value?.userName
        val at = System.currentTimeMillis()
        val lines = countingDao.getCountLines(journalId).filter { stop.covers(it) }
        val written = if (stop.binId != null) {
            val inBin = countingDao.getLineBins(journalId).filter { it.binId.equals(stop.binId, ignoreCase = true) }.map { it.journalLineId }.toSet()
            lines.filter { it.journalLineId !in inBin }.onEach { recordInBin(it, stop.binId, "0", by, at) }
        } else {
            lines.filter { !it.isCounted }.onEach { countingDao.recordCount(it.journalLineId, "0", by, at) }
        }
        SettledStop(stop, written.map { it.journalLineId }, counted = lines.size - written.size).also {
            if (it.written.isNotEmpty()) {
                lastSettle = it
                lastScan = null
            }
        }
    }

    private suspend fun undoSettle(settled: SettledStop) {
        val by = workerSession.session.value?.userName
        val at = System.currentTimeMillis()
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            settled.written.forEach { id ->
                val bin = settled.stop.binId
                if (bin != null) {
                    countingDao.deleteLineBin(id, bin)
                    val total = com.d365fo.mobileops.data.counting.lineTotalOfBins(countingDao.lineBinQuantities(id))
                    countingDao.recordCount(id, total, by, at)
                } else {
                    countingDao.recordCount(id, null, by, at)
                }
            }
        }
    }

    val canUndo: Boolean get() = lastSettle != null || lastScan != null

    /** Undo (F3): the last settled bin, or the last +1. */
    fun undo() {
        val settled = lastSettle ?: return undoLastScan()
        lastSettle = null
        viewModelScope.launch {
            undoSettle(settled)
            _message.value = ScanMessage("Undone", isError = false, title = "Undone", line = "${settled.stop.label} is open again")
        }
    }

    /** Keypad (F4): the last counted line's popup - its count here, to change. */
    fun keypadOnLast(): Boolean {
        val line = flow.value.lastCounted ?: return false
        openLine(line)
        return true
    }

    /** The band's "Record as found": a wrong item put on the journal as not on it, at the worker's word. */
    fun recordMissAsFound() {
        val itemId = _message.value?.unexpectedItemId ?: return
        val variant = missVariant
        missVariant = null
        viewModelScope.launch {
            addNotOnJournal(itemId, variant)
            if (_message.value?.unexpectedItemId == itemId) {
                _message.value = ScanMessage("$itemId recorded", isError = false, title = "$itemId recorded", line = "As found here - not on this journal")
            }
        }
    }

    /** Hold to end count: the journal goes through CountSubmissions, as from the list. */
    fun endCount() {
        viewModelScope.launch {
            when (val result = countSubmissions.endCount(journalId)) {
                com.d365fo.mobileops.data.counting.EndCountResult.Queued -> _leave.trySend(Unit)
                is com.d365fo.mobileops.data.counting.EndCountResult.Refused -> note(StatusKind.Bad, "Can't end the count", result.message)
            }
        }
    }

    fun onCameraDecoded(value: String) = scannerBridge.submitCameraScan(value)

    fun setQuery(query: String) = _query.update { query }

    fun dismissMessage() = _message.update { null }

    /** Enter pressed in the scan box: treat what was typed as a scan (quietly if it is not one). */
    fun submitTyped() {
        val text = _query.value
        if (text.isBlank()) return
        onScanned(text, typed = true)
    }

    fun onScanned(raw: String, typed: Boolean = false) {
        val value = raw.trim()
        if (value.isEmpty()) return
        // A scan while a popup is open must not silently replace what is being entered.
        val ui = _ui.value
        if (_prompt.value != null || _choiceIds.value != null || ui.emptyPrompt != null || ui.unexpected != null || _addBinPrompt.value != null) {
            scanFeedback.warning()
            return
        }
        scanJob?.cancel()
        scanJob = viewModelScope.launch { handleScan(value, typed) }
    }

    private suspend fun handleScan(value: String, typed: Boolean) {
        val lines = countingDao.getCountLines(journalId)
        val current = state.value
        soleLocation = lines.mapNotNull { it.locationId?.takeIf { l -> l.isNotBlank() } }.distinct().singleOrNull()

        // Location-first: a scan that is one of this journal's locations selects it - first thing
        // in a location, or to move on to the next one. Before the bin: a location holds bins.
        if (current.byLocation) {
            val location = lines.firstOrNull { it.locationId.equals(value, ignoreCase = true) }?.locationId
            if (location != null && lines.none { it.itemId.equals(value, ignoreCase = true) }) {
                selectLocation(location)
                return
            }
            if (current.selectedLocation == null) {
                return miss("$value is not a location here", typed, always = true)
            }
        }

        // Bins: a scan of one of the store's bins moves to it. With none chosen yet, nothing else
        // can be counted, and a code that is no item is taken for a bin that is not on the list.
        if (_bins.value.isNotEmpty()) {
            val bin = com.d365fo.mobileops.data.counting.normalizeBin(value)
            if (bin in _bins.value && lines.none { it.itemId.equals(value, ignoreCase = true) }) {
                // A bin of another location: Change location to count it (bins are per location).
                val row = binRows[bin]
                if (row != null && !row.isIn(current.selectedLocation)) {
                    _query.value = ""
                    _message.value = ScanMessage("$bin is in ${row.locationId}, not ${current.selectedLocation}. Change location to count it.", isError = true)
                    scanFeedback.error()
                    return
                }
                selectBin(bin)
                return
            }
            if (_currentBin.value == null) {
                val isItem = lines.any { it.itemId.equals(value, ignoreCase = true) } ||
                    itemDao.resolveBarcode(value) != null || itemDao.getItem(value) != null
                _query.value = ""
                if (isItem) {
                    _message.value = ScanMessage("Scan a bin first.", isError = true, warn = true, title = "Scan the bin label first", line = "Then scan items")
                    scanFeedback.warning()
                } else if (workerSession.session.value?.isCycleCountSupervisor == true) {
                    _addBinPrompt.value = bin
                    scanFeedback.warning()
                } else {
                    _message.value = ScanMessage("$bin is not a bin in this store. Ask a supervisor to add it.", isError = true)
                    scanFeedback.error()
                }
                return
            }
        }

        // GS1 (GS1-128 / DataMatrix / QR): the GTIN finds the item, a batch or serial in the
        // code narrows it to that line, and a count (30/37) is what this scan adds.
        com.d365fo.mobileops.domain.model.Gs1Parser.parse(value)?.let { gs1 ->
            return handleGs1(gs1, lines, current, typed)
        }

        // A serial number identifies exactly one unit, so its line: straight there. Serial-tracked
        // goods are often labelled with the serial alone - scanning it said "not an item on this
        // journal" although the line was right there (seen live).
        val bySerial = lines.filter { !it.serialId.isNullOrBlank() && it.serialId.equals(value, ignoreCase = true) }
        if (bySerial.isNotEmpty() && lines.none { it.itemId.equals(value, ignoreCase = true) }) {
            if (current.locked) {
                _message.value = ScanMessage("Submitted - reopen to change", isError = true)
                scanFeedback.warning()
                return
            }
            val location = current.selectedLocation
            val here = bySerial.filter { location == null || it.locationId.equals(location, ignoreCase = true) }
            if (here.isEmpty()) {
                return miss("Serial $value is not expected at $location", typed, unexpectedItemId = bySerial.first().itemId)
            }
            return routeToLine(here, BigDecimal.ONE, scanned = !typed)
        }

        // Item number first: by far the common scan, and needs no network.
        var itemId = lines.firstOrNull { it.itemId.equals(value, ignoreCase = true) }?.itemId
        var perScan = BigDecimal.ONE
        // Set when a variant barcode resolves: only lines of that variant can match.
        var scannedVariant = com.d365fo.mobileops.domain.model.ProductVariant()

        if (itemId == null) {
            // Barcodes resolve against the on-device SKU cache (SkuSyncWorker) - never OData, so a
            // scan is instant and works offline.
            val resolved = itemDao.resolveBarcode(value)
            if (resolved == null) {
                // Neither a barcode nor a line here: added as not on this journal only when it
                // is a real item - a mis-scan or typo became a "Not on this journal" line (seen
                // 20 Oct: "ddgter122"). With no item cache yet there is nothing to check against.
                val known = itemDao.getItem(value) ?: itemDao.getItem(value.uppercase())
                return when {
                    known != null -> miss("${known.itemId} is not on this journal", typed, unexpectedItemId = known.itemId)
                    itemDao.itemCount() == 0 -> miss("$value is not on this journal", typed, unexpectedItemId = value)
                    else -> miss("$value is not an item in D365FO - nothing added.", typed, always = true)
                }
            }
            perScan = inInventoryUnit(
                resolved,
                resolved.quantityPerScan.toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: BigDecimal.ONE,
                lines,
            )
            scannedVariant = resolved.variant
            itemId = lines.firstOrNull { it.itemId.equals(resolved.itemId, ignoreCase = true) }?.itemId
                ?: return miss(
                    "${resolved.itemId} (barcode $value) is not on this journal",
                    typed,
                    unexpectedItemId = resolved.itemId,
                    unexpectedVariant = resolved.variant,
                )
        }

        if (current.locked) {
            _message.value = ScanMessage("Submitted - reopen to change", isError = true)
            scanFeedback.warning()
            return
        }

        val location = current.selectedLocation
        val matching = lines
            .filter { it.itemId.equals(itemId, ignoreCase = true) }
            .filter { location == null || it.locationId.equals(location, ignoreCase = true) }
            .sortedBy { it.lineNumber }
        if (matching.isEmpty()) {
            return miss("$itemId is not expected at $location", typed, unexpectedItemId = itemId)
        }
        val ofVariant = matching.filter { it.variant.acceptsScanOf(scannedVariant) }
        if (ofVariant.isEmpty()) {
            return miss("$itemId ${scannedVariant.label} is not on this journal", typed, unexpectedItemId = itemId, unexpectedVariant = scannedVariant)
        }
        return routeToLine(ofVariant, perScan, scanned = !typed)
    }

    /**
     * Scan-to-count: a *scan* of an item on one line adds [perScan] (1, or the pack size) to that
     * line straight away - no popup - with Undo. Typed entries, taps and verify-only lines still
     * open the popup, where a number can be typed.
     */
    private fun routeToLine(matching: List<CountLine>, perScan: BigDecimal, scanned: Boolean) {
        if (scanned && matching.size == 1 && !matching.single().verifyOnly && !state.value.locked) {
            _query.value = ""
            addScanned(matching.single(), perScan)
            return
        }
        // Item on several lines, but the worker already chose one for it this session: count
        // there again (the message names the line, Undo takes it back, a tap on another line
        // switches) instead of asking every scan.
        val remembered = matching.firstOrNull { it.journalLineId == chosenLineByItem[matching.first().itemId.uppercase()] }
        if (scanned && remembered != null && !remembered.verifyOnly && !state.value.locked) {
            _query.value = ""
            addScanned(remembered, perScan)
            return
        }
        pickLine(matching, perScan, scanned)
    }

    private fun selectBin(bin: String) {
        _query.value = ""
        _currentBin.value = bin
        _back.value = null
        _message.value = ScanMessage("Counting bin $bin", isError = false)
        scanFeedback.success()
        _refocus.update { it + 1 }
    }

    /** Back to scanning a bin, in the same location. */
    fun changeBin() {
        if (_back.value == null) _back.value = Back(state.value.selectedLocation, _currentBin.value)
        _currentBin.value = null
        _message.value = null
        _refocus.update { it + 1 }
    }

    /** Cancel on Change location / Change bin: back to where counting was, nothing changed. */
    fun cancelChange() {
        val back = _back.value ?: return
        _back.value = null
        _ui.update { it.copy(selectedLocation = back.location) }
        _currentBin.value = back.bin
        _message.value = null
        _refocus.update { it + 1 }
    }

    fun cancelAddBin() {
        _addBinPrompt.value = null
        _refocus.update { it + 1 }
    }

    /** A supervisor adds a bin that is not on the store's list. D365FO checks they are one. */
    fun confirmAddBin() {
        val bin = _addBinPrompt.value ?: return
        _addBinPrompt.value = null
        viewModelScope.launch {
            val store = journalDao.getJournal(journalId)?.journal?.warehouseId.orEmpty()
            when (val added = binRepository.add(store, bin, state.value.selectedLocation)) {
                is com.d365fo.mobileops.util.NetworkResult.Success -> selectBin(added.data)
                is com.d365fo.mobileops.util.NetworkResult.Error ->
                    _message.value = ScanMessage(added.failure.message, isError = true)
                com.d365fo.mobileops.util.NetworkResult.Loading -> Unit
            }
            _refocus.update { it + 1 }
        }
    }

    /** Sets what is counted in the current bin, and the line's total from all its bins. */
    private suspend fun recordInBin(line: CountLine, bin: String, quantity: String?, by: String?, at: Long): String? {
        if (quantity == null) {
            countingDao.deleteLineBin(line.journalLineId, bin)
        } else {
            countingDao.upsertLineBin(com.d365fo.mobileops.data.local.entity.CountLineBinEntity(line.journalLineId, bin, journalId, quantity))
        }
        val total = com.d365fo.mobileops.data.counting.lineTotalOfBins(countingDao.lineBinQuantities(line.journalLineId))
        countingDao.recordCount(line.journalLineId, total, by, at)
        return total
    }

    private fun addScanned(line: CountLine, perScan: BigDecimal) {
        lastSettle = null
        // ATOMIC + NonCancellable around the writes - see ReceivingFlowViewModel.persist.
        lastWrite = viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.ATOMIC) {
            val before = line.countedQuantity
            val bin = _currentBin.value
            val by = workerSession.session.value?.userName
            val binBefore = bin?.let { countingDao.lineBinQuantity(line.journalLineId, it) }
            val text = if (bin != null) {
                val now = (binBefore?.toBigDecimalOrNull() ?: BigDecimal.ZERO) + perScan
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    countingDao.markLineStarted(line.journalLineId, System.currentTimeMillis())
                    recordInBin(line, bin, now.stripTrailingZeros().toPlainString(), by, System.currentTimeMillis()).orEmpty()
                }
            } else {
                val now = (before?.toBigDecimalOrNull() ?: BigDecimal.ZERO) + perScan
                val total = now.stripTrailingZeros().toPlainString()
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    countingDao.markLineStarted(line.journalLineId, System.currentTimeMillis())
                    countingDao.recordCount(line.journalLineId, total, by, System.currentTimeMillis())
                }
                total
            }
            lastScan = LastScan(line.journalLineId, before, bin, binBefore)
            scanFeedback.success()
            val what = listOfNotNull(
                line.itemId,
                line.variant.label.takeIf { it.isNotBlank() },
                line.locationId?.takeIf { it.isNotBlank() },
                // Lines of one item can differ only by batch (spot count BSP-JN-000552013).
                line.batchId?.takeIf { it.isNotBlank() }?.let { "Batch $it" },
                line.serialId?.takeIf { it.isNotBlank() }?.let { "Serial $it" },
            )
                .joinToString(" \u00B7 ")
            _lastCountedId.value = line.journalLineId
            val inBin = if (bin != null) countingDao.lineBinQuantity(line.journalLineId, bin) ?: text else text
            _message.value = ScanMessage(
                "$what: $text",
                isError = false,
                undoable = true,
                title = "+${perScan.stripTrailingZeros().toPlainString()} ${line.itemId}",
                line = "$inBin counted" + (bin?.let { " in $it" } ?: line.locationId?.takeIf { l -> l.isNotBlank() }?.let { " at $it" }.orEmpty()) +
                    listOfNotNull(
                        line.variant.label.takeIf { it.isNotBlank() },
                        line.batchId?.takeIf { it.isNotBlank() }?.let { "Batch $it" },
                        line.serialId?.takeIf { it.isNotBlank() }?.let { "Serial $it" },
                    ).joinToString("") { " \u00B7 $it" },
            )
            _refocus.update { it + 1 }
        }
    }

    private suspend fun handleGs1(
        gs1: com.d365fo.mobileops.domain.model.Gs1Data,
        lines: List<CountLine>,
        current: CountingScanState,
        typed: Boolean,
    ) {
        val resolved = gs1.gtinCandidates.firstNotNullOfOrNull { itemDao.resolveBarcode(it) }
            ?: return miss("Unknown barcode ${gs1.gtin}", typed, unexpectedItemId = gs1.gtin)
        if (current.locked) {
            _message.value = ScanMessage("Submitted - reopen to change", isError = true)
            scanFeedback.warning()
            return
        }
        val location = current.selectedLocation
        val matching = lines
            .filter { it.itemId.equals(resolved.itemId, ignoreCase = true) }
            .filter { location == null || it.locationId.equals(location, ignoreCase = true) }
            .filter { it.variant.acceptsScanOf(resolved.variant) }
            .filter { gs1.batch == null || it.batchId.isNullOrBlank() || it.batchId.equals(gs1.batch, ignoreCase = true) }
            .filter { gs1.serial == null || it.serialId.isNullOrBlank() || it.serialId.equals(gs1.serial, ignoreCase = true) }
            .sortedBy { it.lineNumber }
        if (matching.isEmpty()) {
            val what = listOfNotNull(resolved.itemId, gs1.batch?.let { "batch $it" }, gs1.serial?.let { "serial $it" }).joinToString(" ")
            return miss("$what is not on this journal${location?.let { " at $it" }.orEmpty()}", typed, unexpectedItemId = resolved.itemId)
        }
        val perScan = gs1.quantity?.takeIf { it.signum() > 0 }
            ?: inInventoryUnit(
                resolved,
                resolved.quantityPerScan.toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: BigDecimal.ONE,
                lines,
            )
        routeToLine(matching, perScan, scanned = !typed)
    }

    /**
     * A pack barcode's quantity is in the barcode's own unit (1 BOX); counting journals are in
     * the inventory unit. Only such a barcode asks the server - once, for the whole journal.
     */
    private suspend fun inInventoryUnit(
        resolved: com.d365fo.mobileops.data.local.dao.ScannedItem,
        quantity: BigDecimal,
        lines: List<CountLine>,
    ): BigDecimal {
        val scanUnit = resolved.scanUnit?.takeIf { it.isNotBlank() } ?: return quantity
        if (scanUnit.equals(resolved.inventoryUnit, ignoreCase = true)) return quantity
        val units = itemUnits.unitsFor(resolved.itemId, resolved.variant, lines.map { it.itemId to it.variant })
        return com.d365fo.mobileops.domain.model.UnitConversion
            .packInLineUnit(quantity, scanUnit, units, resolved.inventoryUnit) ?: quantity
    }

    /** Takes back the last scan-to-count addition - the line returns to its count before it. */
    fun undoLastScan() {
        val undo = lastScan ?: return
        lastScan = null
        viewModelScope.launch {
            if (undo.bin != null) {
                if (undo.binBefore == null) {
                    countingDao.deleteLineBin(undo.journalLineId, undo.bin)
                } else {
                    countingDao.upsertLineBin(com.d365fo.mobileops.data.local.entity.CountLineBinEntity(undo.journalLineId, undo.bin, journalId, undo.binBefore))
                }
            }
            countingDao.recordCount(undo.journalLineId, undo.before, workerSession.session.value?.userName, System.currentTimeMillis())
            _message.value = ScanMessage("Last scan undone.", isError = false, title = "Undone", line = "The last scan is taken back")
            _refocus.update { it + 1 }
        }
    }

    /** The count a line had before the last scan-to-count addition, for Undo. */
    private data class LastScan(val journalLineId: String, val before: String?, val bin: String? = null, val binBefore: String? = null)

    private var lastScan: LastScan? = null

    /** The line last picked for an item (upper-cased item id) this session - see [routeToLine]. */
    private val chosenLineByItem = HashMap<String, String>()

    /** Lines the scan could be, after a variant/location narrowing: one opens the popup, several ask. */
    private var choiceIsScan = false
    private var choicePerScan: BigDecimal = BigDecimal.ONE

    private fun pickLine(matching: List<CountLine>, perScan: BigDecimal, scanned: Boolean = false) {

        _query.value = ""
        _message.value = null
        scanFeedback.success()

        if (matching.size > 1) {
            // The same item on several lines: a scan cannot say which.
            choiceIsScan = scanned
            choicePerScan = perScan
            _choiceIds.value = matching.map { it.journalLineId }
        } else {
            openPrompt(matching.single(), perScan)
        }
    }

    // -- Location types and verify-only lines ----------------------------------------------------

    fun setTypeFilter(type: String?) = _ui.update { it.copy(typeFilter = type) }

    /** Verify-only (e.g. in transit): confirmed present, never counted. */
    fun confirmVerified(journalLineId: String, present: Boolean) {
        _ui.update { it.copy(verifyLineId = null) }
        viewModelScope.launch {
            if (!state.value.locked) {
                countingDao.confirmLine(
                    journalLineId,
                    workerSession.session.value?.userName.takeIf { present },
                    System.currentTimeMillis().takeIf { present },
                )
            }
            _refocus.update { it + 1 }
        }
    }

    fun cancelVerify() {
        _ui.update { it.copy(verifyLineId = null) }
        _refocus.update { it + 1 }
    }

    // -- Location-first -------------------------------------------------------------------------

    fun setByLocation(enabled: Boolean) = _ui.update { it.copy(byLocation = enabled, selectedLocation = null) }

    /** Change location: back to scanning a location - and then a bin, which belongs to it. */
    fun clearLocation() {
        if (_back.value == null) _back.value = Back(state.value.selectedLocation, _currentBin.value)
        _ui.update { it.copy(selectedLocation = null) }
        _currentBin.value = null
        _message.value = null
        _refocus.update { it + 1 }
    }

    /** Location-first: the location picked from the list instead of scanned. */
    fun pickLocation(location: String) {
        if (state.value.byLocation) selectLocation(location)
    }

    private fun selectLocation(location: String) {
        _query.value = ""
        // A bin is in a location: another location starts with its own bin.
        if (!location.equals(_ui.value.selectedLocation, ignoreCase = true)) _currentBin.value = null
        val askBin = _bins.value.isNotEmpty() && _currentBin.value == null
        _message.value = ScanMessage("Counting location $location" + if (askBin) " - scan a bin" else "", isError = false)
        _ui.update { it.copy(selectedLocation = location) }
        scanFeedback.success()
        _refocus.update { it + 1 }
    }

    /** "Location is empty": asks first - it counts every line at the location as zero. */
    fun requestLocationEmpty() {
        val current = state.value
        val location = current.selectedLocation ?: return
        if (current.locked) return
        viewModelScope.launch {
            val lines = capture.linesAt(journalId, location)
            _ui.update {
                it.copy(emptyPrompt = EmptyLocationPrompt(location, lines.size, lines.count { line -> line.isCounted }))
            }
        }
    }

    fun confirmLocationEmpty() {
        val prompt = _ui.value.emptyPrompt ?: return
        _ui.update { it.copy(emptyPrompt = null) }
        viewModelScope.launch {
            val changed = capture.markLocationEmpty(journalId, prompt.locationId, workerSession.session.value?.userName)
            countingDao.deleteLineBinsAt(journalId, prompt.locationId)
            val current = state.value
            if (current.layout == com.d365fo.mobileops.domain.model.CountingLayout.WALL_TO_WALL) {
                // On to the next location (user, 8 Nov): scan it here, or - a task of one
                // location - back to the list, where End count completes it.
                if (current.locations.size > 1) {
                    _ui.update { it.copy(selectedLocation = null) }
                    _message.value = ScanMessage("${prompt.locationId} empty - scan the next location", isError = false)
                } else {
                    _leave.trySend(Unit)
                }
            } else {
                _message.value = ScanMessage(
                    "${prompt.locationId} empty",
                    isError = false,
                )
            }
            _refocus.update { it + 1 }
        }
    }

    fun cancelLocationEmpty() {
        _ui.update { it.copy(emptyPrompt = null) }
        _refocus.update { it + 1 }
    }

    // -- Not on this journal ----------------------------------------------------------------------

    fun openUnexpected(itemId: String? = null, variant: com.d365fo.mobileops.domain.model.ProductVariant? = null) {
        if (!flags.countExceptions || state.value.locked) return
        _message.value = null
        val item = itemId.orEmpty()
        _ui.update {
            it.copy(unexpected = UnexpectedItemPrompt(item, state.value.selectedLocation ?: soleLocation))
        }
        // The item's variants, so the one found can be chosen (14 Oct: Dm001 was recorded without
        // its configuration and could not go on the journal).
        if (item.isNotBlank()) viewModelScope.launch {
            val variants = variantsOf(item)
            _ui.update { ui ->
                ui.copy(unexpected = ui.unexpected?.takeIf { it.itemId == item }?.copy(
                    variants = variants,
                    variant = variant?.takeIf { v -> !v.isEmpty && variants.any { it.sameAs(v) } }
                        ?: variants.singleOrNull(),
                ) ?: ui.unexpected)
            }
        }
    }

    private suspend fun variantsOf(itemId: String): List<com.d365fo.mobileops.domain.model.ProductVariant> =
        itemDao.variantsOf(itemId).map {
            com.d365fo.mobileops.domain.model.ProductVariant(
                configId = it.configId.ifBlank { null },
                sizeId = it.sizeId.ifBlank { null },
                colorId = it.colorId.ifBlank { null },
                styleId = it.styleId.ifBlank { null },
            )
        }.filterNot { it.isEmpty }

    private fun com.d365fo.mobileops.domain.model.ProductVariant.sameAs(other: com.d365fo.mobileops.domain.model.ProductVariant) =
        configId.orEmpty().equals(other.configId.orEmpty(), true) && sizeId.orEmpty().equals(other.sizeId.orEmpty(), true) &&
            colorId.orEmpty().equals(other.colorId.orEmpty(), true) && styleId.orEmpty().equals(other.styleId.orEmpty(), true)

    fun saveUnexpected(
        itemId: String,
        locationId: String,
        quantity: String,
        batchId: String,
        serialId: String,
        note: String,
        variant: com.d365fo.mobileops.domain.model.ProductVariant? = null,
    ) {
        viewModelScope.launch {
            val variants = variantsOf(itemId.trim())
            val result = capture.addUnexpectedItem(
                journalId = journalId,
                itemId = itemId,
                locationId = locationId,
                quantityText = quantity,
                batchId = batchId,
                serialId = serialId,
                note = note,
                capturedBy = workerSession.session.value?.userName,
                variant = variant ?: com.d365fo.mobileops.domain.model.ProductVariant(),
                variantRequired = variants.isNotEmpty(),
            )
            when (result) {
                is UnexpectedItemResult.Invalid -> _ui.update {
                    it.copy(unexpected = it.unexpected?.copy(itemId = itemId, locationId = locationId, error = result.message, variants = variants, variant = variant))
                }
                is UnexpectedItemResult.Saved -> {
                    _ui.update { it.copy(unexpected = null) }
                    _message.value = ScanMessage("$itemId recorded as not on this journal.", isError = false)
                    _refocus.update { it + 1 }
                }
            }
        }
    }

    /**
     * Adds one of [itemId] as not on this journal - or one more to the row already there for the
     * same item, variant and location. An item that comes in variants needs the one found: the
     * scanned barcode's, the only one, or the worker picks it ([UnexpectedVariantChoice]).
     */
    private suspend fun addNotOnJournal(itemId: String, variant: com.d365fo.mobileops.domain.model.ProductVariant?) {
        val location = state.value.selectedLocation ?: soleLocation
        val variants = variantsOf(itemId)
        val found = when {
            variants.isEmpty() -> com.d365fo.mobileops.domain.model.ProductVariant()
            variant != null && !variant.isEmpty && variants.any { it.sameAs(variant) } -> variant
            variants.size == 1 -> variants.single()
            else -> {
                _ui.update { it.copy(variantChoice = UnexpectedVariantChoice(itemId, variants, location)) }
                return
            }
        }
        addNotOnJournal(itemId, found, location)
    }

    private suspend fun addNotOnJournal(itemId: String, variant: com.d365fo.mobileops.domain.model.ProductVariant, location: String?) {
        val total = capture.addOneNotOnJournal(
            journalId = journalId,
            itemId = itemId,
            locationId = location,
            variant = variant,
            capturedBy = workerSession.session.value?.userName,
        )
        // A typed item number would stay in the box and filter the list down to nothing.
        _query.value = ""
        val what = listOfNotNull(itemId, variant.label.takeIf { it.isNotBlank() }).joinToString(" ")
        _message.value = ScanMessage("$what · $total added (not on journal)", isError = false)
        _refocus.update { it + 1 }
    }

    fun chooseUnexpectedVariant(variant: com.d365fo.mobileops.domain.model.ProductVariant) {
        val choice = _ui.value.variantChoice ?: return
        _ui.update { it.copy(variantChoice = null) }
        viewModelScope.launch { addNotOnJournal(choice.itemId, variant, choice.locationId) }
    }

    fun cancelVariantChoice() {
        _query.value = ""
        _ui.update { it.copy(variantChoice = null) }
        _refocus.update { it + 1 }
    }

    /** Tap on a not-on-the-journal row: its quantity can be set, or the row removed. */
    fun editUnexpected(clientId: String) {
        if (state.value.locked) return
        _ui.update { it.copy(editingException = clientId) }
    }

    fun setUnexpectedQuantity(clientId: String, text: String) {
        val qty = text.trim().trimStart('0').ifEmpty { "0" }
        if (text.isBlank() || text.any { !it.isDigit() }) return
        _ui.update { it.copy(editingException = null) }
        viewModelScope.launch {
            if (qty == "0") capture.removeUnexpectedItem(clientId) else capture.setUnexpectedQuantity(clientId, qty)
            _refocus.update { it + 1 }
        }
    }

    fun closeUnexpectedEdit() {
        _ui.update { it.copy(editingException = null) }
        _refocus.update { it + 1 }
    }

    fun cancelUnexpected() {
        _ui.update { it.copy(unexpected = null) }
        _refocus.update { it + 1 }
    }

    fun removeUnexpected(clientId: String) {
        _ui.update { it.copy(editingException = null) }
        viewModelScope.launch { capture.removeUnexpectedItem(clientId) }
    }

    // -- Line counting ---------------------------------------------------------------------------

    /** Pick one of several lines for the scanned item. */
    fun pickLine(journalLineId: String) {
        val line = state.value.choice.firstOrNull { it.journalLineId == journalLineId } ?: return
        _choiceIds.value = null
        // Chosen after a scan: count that scan on the chosen line, same as a single-line scan.
        chosenLineByItem[line.itemId.uppercase()] = line.journalLineId
        if (choiceIsScan && !line.verifyOnly && !state.value.locked) {
            choiceIsScan = false
            addScanned(line, choicePerScan)
        } else {
            openPrompt(line, BigDecimal.ONE)
        }
    }

    fun cancelChoice() {
        _choiceIds.value = null
        _refocus.update { it + 1 }
    }

    /** A tapped row: the same popup, to correct that line. */
    fun openLine(line: CountLine) {
        if (state.value.locked || _prompt.value != null) return
        if (_bins.value.isNotEmpty() && _currentBin.value == null) {
            _message.value = ScanMessage("Scan a bin first.", isError = true)
            scanFeedback.warning()
            return
        }
        // A tapped line is also where the next scans of this item go (see routeToLine).
        chosenLineByItem[line.itemId.uppercase()] = line.journalLineId
        openPrompt(line, BigDecimal.ONE)
    }

    private fun openPrompt(line: CountLine, perScan: BigDecimal) {
        viewModelScope.launch { countingDao.markLineStarted(line.journalLineId, System.currentTimeMillis()) }
        if (line.verifyOnly) {
            _ui.update { it.copy(verifyLineId = line.journalLineId) }
            return
        }
        val bin = _currentBin.value
        if (bin != null) {
            // In a bin the popup is about that bin. The line's total follows from its bins.
            viewModelScope.launch {
                val inBin = countingDao.lineBinQuantity(line.journalLineId, bin)
                _prompt.value = PromptRequest(
                    line.journalLineId,
                    inBin ?: perScan.takeIf { it > BigDecimal.ONE }?.stripTrailingZeros()?.toPlainString() ?: "",
                )
            }
            return
        }
        val initial = line.countedQuantity
            ?: perScan.takeIf { it > BigDecimal.ONE }?.stripTrailingZeros()?.toPlainString()
            ?: ""
        _prompt.value = PromptRequest(line.journalLineId, initial)
    }

    /** OK in the popup. Whole pieces: digits only; "007" is 7; blank clears the count. */
    fun confirmQuantity(journalLineId: String, text: String) {
        // ATOMIC + NonCancellable around the write - see ReceivingFlowViewModel.persist.
        viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.ATOMIC) {
            if (!state.value.locked) {
                val digits = text.filter { it.isDigit() }.take(MAX_DIGITS)
                val value = if (digits.isEmpty()) null else digits.trimStart('0').ifEmpty { "0" }
                val by = workerSession.session.value?.userName
                val at = System.currentTimeMillis()
                val bin = _currentBin.value
                val line = state.value.prompt?.line
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    if (bin != null && line != null) {
                        val total = recordInBin(line, bin, value, by, at)
                        if (total == null) countingDao.recordMarked(journalLineId, false, by, at)
                    } else {
                        countingDao.recordCount(journalLineId, value, by, at)
                        if (value == null) countingDao.recordMarked(journalLineId, false, by, at)
                    }
                }
            }
            _prompt.value = null
            _refocus.update { it + 1 }
        }
    }

    /**
     * The focused check's one line: saves the quantity and ends the count in one step, then back
     * to the list. End count waits for a connection like any other; a refusal stays on screen.
     */
    fun confirmAndEnd(text: String) {
        val line = state.value.focusLine ?: return
        viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.ATOMIC) {
            if (state.value.locked) return@launch
            val digits = text.filter { it.isDigit() }.take(MAX_DIGITS)
            if (digits.isEmpty()) return@launch
            val value = digits.trimStart('0').ifEmpty { "0" }
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                countingDao.recordCount(line.journalLineId, value, workerSession.session.value?.userName, System.currentTimeMillis())
            }
            when (val result = countSubmissions.endCount(journalId)) {
                com.d365fo.mobileops.data.counting.EndCountResult.Queued -> _leave.trySend(Unit)
                is com.d365fo.mobileops.data.counting.EndCountResult.Refused ->
                    _message.value = ScanMessage(result.message, isError = true)
            }
        }
    }

    fun cancelPrompt() {
        _prompt.value = null
        _refocus.update { it + 1 }
    }

    /** A scan that did not land. Silent for typed text, which may just be a search term. */
    /** Typed misses stay silent (typing filters the list) unless [always] - e.g. the location prompt. */
    /** The journal's one location, when all its lines are in one - "Not on this journal" starts there. */
    private var soleLocation: String? = null

    private fun miss(
        message: String,
        typed: Boolean,
        unexpectedItemId: String? = null,
        always: Boolean = false,
        unexpectedVariant: com.d365fo.mobileops.domain.model.ProductVariant? = null,
    ) {
        // An item scanned or typed that is not on the journal is added to the list as such - no
        // form (user, 14 Oct); it used to be an error with a button to a form.
        if (flowMode) {
            // The flow (redesign phase 6): a wrong item adds nothing - a Bad band, with the
            // choice to record it as found offered on the band.
            missVariant = unexpectedVariant
            _message.value = ScanMessage(
                message,
                isError = true,
                unexpectedItemId = unexpectedItemId.takeIf { flags.countExceptions && !state.value.locked },
                title = if (unexpectedItemId != null) "Wrong item" else "Not recognised",
                line = if (message.contains("nothing", ignoreCase = true)) message else "$message - nothing was added",
            )
            scanFeedback.error()
            return
        }
        if (unexpectedItemId != null && flags.countExceptions && !state.value.locked) {
            if (!typed) scanFeedback.warning()
            viewModelScope.launch { addNotOnJournal(unexpectedItemId, unexpectedVariant) }
            return
        }
        if (typed && unexpectedItemId == null && !always) return
        _message.value = ScanMessage(
            message,
            isError = true,
            unexpectedItemId = unexpectedItemId.takeIf { flags.countExceptions && !state.value.locked },
        )
        if (!typed) scanFeedback.error()
    }

    private fun CountLine.matches(query: String): Boolean {
        val q = query.trim()
        return q.split(' ').filter { it.isNotBlank() }.all { token ->
            itemId.contains(token, ignoreCase = true) ||
                itemName.contains(token, ignoreCase = true) ||
                locationId?.contains(token, ignoreCase = true) == true ||
                batchId?.contains(token, ignoreCase = true) == true ||
                serialId?.contains(token, ignoreCase = true) == true
        }
    }

    private companion object {
        const val MAX_DIGITS = 12
    }
}

data class CountingScanState(
    /** The journal is read from the device - until then [layout] is only the default. */
    val loaded: Boolean = false,
    val journalDescription: String = "",
    val originType: String = com.d365fo.mobileops.domain.model.ORIGIN_SCHEDULED,
    val layout: com.d365fo.mobileops.domain.model.CountingLayout = com.d365fo.mobileops.domain.model.CountingLayout.STANDARD,
    val totalLines: Int = 0,
    val counted: Int = 0,
    val locked: Boolean = false,
    /** The journal's lines (or the selected location's), narrowed by whatever is typed. */
    val lines: List<CountLine> = emptyList(),
    val query: String = "",
    /** Non-null while the quantity popup is open. */
    val prompt: CountPrompt? = null,
    /** Non-empty while the worker is picking one of several lines for a scanned item. */
    val choice: List<CountLine> = emptyList(),
    val message: ScanMessage? = null,
    val refocusNonce: Int = 0,
    val locationModeAvailable: Boolean = false,
    val byLocation: Boolean = false,
    val selectedLocation: String? = null,
    val locationCounted: Int = 0,
    val locationTotal: Int = 0,
    val zeroStockCheck: Boolean = false,
    val exceptionsEnabled: Boolean = false,
    val exceptions: List<CountExceptionEntity> = emptyList(),
    val emptyPrompt: EmptyLocationPrompt? = null,
    val unexpected: UnexpectedItemPrompt? = null,
    val variantChoice: UnexpectedVariantChoice? = null,
    val editingException: CountExceptionEntity? = null,
    /** Stock-freeze banner text, from the server's freeze state. */
    val freezeBanner: String? = null,
    /** Location types on this journal, when there is more than one - for the filter chips. */
    val locationTypes: List<String> = emptyList(),
    /** The journal's locations, for the location-first picker. */
    val locations: List<LocationChoice> = emptyList(),
    val typeFilter: String? = null,
    /** Non-null while "Confirm present" is open for a verify-only line. */
    val verifyLine: CountLine? = null,
    /** The store has bins: one must be chosen before anything can be counted. */
    val binsRequired: Boolean = false,
    val currentBin: String? = null,
    /** A supervisor scanned a bin that is not on the list: add it? */
    val addBinPrompt: String? = null,
    /** "location A-01, bin BIN-A1" while Change location / Change bin is under way: Cancel goes back. */
    val changingFrom: String? = null,
) {
    val percent: Int get() = if (totalLines == 0) 0 else (counted * 100) / totalLines

    /** What the screen shows for [layout] - each section is its own card, so any can go. */
    val showProgress: Boolean get() = layout != com.d365fo.mobileops.domain.model.CountingLayout.FOCUSED
    val showList: Boolean get() = layout != com.d365fo.mobileops.domain.model.CountingLayout.FOCUSED
    val showLocationSwitch: Boolean get() = layout == com.d365fo.mobileops.domain.model.CountingLayout.STANDARD && locationModeAvailable && !binsRequired
    val isWallToWall: Boolean get() = layout == com.d365fo.mobileops.domain.model.CountingLayout.WALL_TO_WALL
    val focusLine: CountLine? get() = lines.singleOrNull()?.takeIf { layout == com.d365fo.mobileops.domain.model.CountingLayout.FOCUSED }
}
