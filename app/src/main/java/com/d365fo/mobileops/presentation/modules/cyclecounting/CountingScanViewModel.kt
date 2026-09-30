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
import com.d365fo.mobileops.domain.repository.OnDemandLookupRepository
import com.d365fo.mobileops.presentation.common.scan.HidScannerBridge
import com.d365fo.mobileops.presentation.common.scan.ScanFeedback
import com.d365fo.mobileops.util.NetworkResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import javax.inject.Inject

/**
 * What the last scan did, shown under the scan box. [unexpectedItemId] non-null offers
 * "Not on this journal" for that item.
 */
data class ScanMessage(val text: String, val isError: Boolean, val unexpectedItemId: String? = null)

/** The quantity popup: which line, and what to start the box at. */
data class CountPrompt(val line: CountLine, val initial: String)

/** "Location is empty?" - which location, how many lines, and how many already have a count. */
data class EmptyLocationPrompt(val locationId: String, val lines: Int, val alreadyCounted: Int)

/** The "Not on this journal" form, prefilled from the scan. */
data class UnexpectedItemPrompt(val itemId: String, val locationId: String?, val error: String? = null)

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
    private val lookups: OnDemandLookupRepository,
    private val scannerBridge: HidScannerBridge,
    private val scanFeedback: ScanFeedback,
    private val capture: CountCaptureActions,
    private val flags: FeatureFlags,
) : ViewModel() {

    val journalId: String = checkNotNull(savedStateHandle["journalId"])

    private val _query = MutableStateFlow("")
    private val _prompt = MutableStateFlow<PromptRequest?>(null)
    private val _choiceIds = MutableStateFlow<List<String>?>(null)
    private val _message = MutableStateFlow<ScanMessage?>(null)
    private val _ui = MutableStateFlow(LocationUi())

    /** Bumped whenever the scan box should take focus back (after the popup closes). */
    private val _refocus = MutableStateFlow(0)
    private var scanJob: Job? = null

    private data class PromptRequest(val lineId: String, val initial: String)

    /** Location-first and exceptions UI state. [byLocation] null means "the journal's default". */
    private data class LocationUi(
        val byLocation: Boolean? = null,
        val selectedLocation: String? = null,
        val emptyPrompt: EmptyLocationPrompt? = null,
        val unexpected: UnexpectedItemPrompt? = null,
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
    )

    private val overlay = combine(_prompt, _choiceIds, _message, _refocus, _ui) { prompt, choice, message, refocus, ui ->
        Overlay(prompt, choice, message, refocus, ui)
    }

    val state: StateFlow<CountingScanState> = combine(base, overlay) { b, o ->
        val byId = b.lines.associateBy { it.journalLineId }
        val locationAvailable = flags.countLocationFirst
        val byLocation = locationAvailable && (o.ui.byLocation ?: (b.journal?.locationFirst == true))
        val location = o.ui.selectedLocation.takeIf { byLocation }
        val atLocation = location?.let { loc -> b.lines.filter { it.locationId.equals(loc, ignoreCase = true) } }
        val scope = atLocation ?: b.lines
        CountingScanState(
            journalDescription = b.journal?.description ?: journalId,
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
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CountingScanState())

    init {
        viewModelScope.launch { scannerBridge.scans.collect { onScanned(it.value) } }
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
        if (_prompt.value != null || _choiceIds.value != null || ui.emptyPrompt != null || ui.unexpected != null) {
            scanFeedback.warning()
            return
        }
        scanJob?.cancel()
        scanJob = viewModelScope.launch { handleScan(value, typed) }
    }

    private suspend fun handleScan(value: String, typed: Boolean) {
        val lines = countingDao.getCountLines(journalId)
        val current = state.value

        // Location-first: a scan that is one of this journal's locations selects it - first thing
        // in a location, or to move on to the next one.
        if (current.byLocation) {
            val location = lines.firstOrNull { it.locationId.equals(value, ignoreCase = true) }?.locationId
            if (location != null && lines.none { it.itemId.equals(value, ignoreCase = true) }) {
                selectLocation(location)
                return
            }
            if (current.selectedLocation == null) {
                return miss("$value is not a location on this journal. Scan a location first.", typed)
            }
        }

        // Item number first: by far the common scan, and needs no network.
        var itemId = lines.firstOrNull { it.itemId.equals(value, ignoreCase = true) }?.itemId
        var perScan = BigDecimal.ONE

        if (itemId == null) {
            when (val match = lookups.lookupBarcode(value)) {
                is NetworkResult.Error -> return miss("Could not look up $value: ${match.failure.message}", typed)
                NetworkResult.Loading -> return miss("Lookup did not complete", typed)
                is NetworkResult.Success -> {
                    val resolved = match.data
                        ?: return miss("$value is not an item on this journal", typed, unexpectedItemId = value)
                    perScan = resolved.quantityPerScan
                    itemId = lines.firstOrNull { it.itemId.equals(resolved.itemNumber, ignoreCase = true) }?.itemId
                        ?: return miss(
                            "${resolved.itemNumber} (barcode $value) is not on this journal",
                            typed,
                            unexpectedItemId = resolved.itemNumber,
                        )
                }
            }
        }

        if (current.locked) {
            _message.value = ScanMessage("Counts have been submitted, so $itemId can't be changed.", isError = true)
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

        _query.value = ""
        _message.value = null
        scanFeedback.success()

        if (matching.size > 1) {
            // The same item on several lines: a scan cannot say which.
            _choiceIds.value = matching.map { it.journalLineId }
        } else {
            openPrompt(matching.single(), perScan)
        }
    }

    // -- Location-first -------------------------------------------------------------------------

    fun setByLocation(enabled: Boolean) = _ui.update { it.copy(byLocation = enabled, selectedLocation = null) }

    fun clearLocation() {
        _ui.update { it.copy(selectedLocation = null) }
        _refocus.update { it + 1 }
    }

    private fun selectLocation(location: String) {
        _query.value = ""
        _message.value = ScanMessage("Counting location $location", isError = false)
        _ui.update { it.copy(selectedLocation = location) }
        scanFeedback.success()
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
            _message.value = ScanMessage(
                "${prompt.locationId} confirmed empty - $changed line${if (changed == 1) "" else "s"} counted as 0.",
                isError = false,
            )
            _refocus.update { it + 1 }
        }
    }

    fun cancelLocationEmpty() {
        _ui.update { it.copy(emptyPrompt = null) }
        _refocus.update { it + 1 }
    }

    // -- Not on this journal ----------------------------------------------------------------------

    fun openUnexpected(itemId: String? = null) {
        if (!flags.countExceptions || state.value.locked) return
        _message.value = null
        _ui.update {
            it.copy(unexpected = UnexpectedItemPrompt(itemId.orEmpty(), state.value.selectedLocation))
        }
    }

    fun saveUnexpected(itemId: String, locationId: String, quantity: String, batchId: String, serialId: String, note: String) {
        viewModelScope.launch {
            val result = capture.addUnexpectedItem(
                journalId = journalId,
                itemId = itemId,
                locationId = locationId,
                quantityText = quantity,
                batchId = batchId,
                serialId = serialId,
                note = note,
                capturedBy = workerSession.session.value?.userName,
            )
            when (result) {
                is UnexpectedItemResult.Invalid -> _ui.update {
                    it.copy(unexpected = it.unexpected?.copy(itemId = itemId, locationId = locationId, error = result.message))
                }
                is UnexpectedItemResult.Saved -> {
                    _ui.update { it.copy(unexpected = null) }
                    _message.value = ScanMessage("$itemId recorded as not on this journal.", isError = false)
                    _refocus.update { it + 1 }
                }
            }
        }
    }

    fun cancelUnexpected() {
        _ui.update { it.copy(unexpected = null) }
        _refocus.update { it + 1 }
    }

    fun removeUnexpected(clientId: String) {
        viewModelScope.launch { capture.removeUnexpectedItem(clientId) }
    }

    // -- Line counting ---------------------------------------------------------------------------

    /** Pick one of several lines for the scanned item. */
    fun pickLine(journalLineId: String) {
        val line = state.value.choice.firstOrNull { it.journalLineId == journalLineId } ?: return
        _choiceIds.value = null
        openPrompt(line, BigDecimal.ONE)
    }

    fun cancelChoice() {
        _choiceIds.value = null
        _refocus.update { it + 1 }
    }

    /** A tapped row: the same popup, to correct that line. */
    fun openLine(line: CountLine) {
        if (state.value.locked || _prompt.value != null) return
        openPrompt(line, BigDecimal.ONE)
    }

    private fun openPrompt(line: CountLine, perScan: BigDecimal) {
        viewModelScope.launch { countingDao.markLineStarted(line.journalLineId, System.currentTimeMillis()) }
        val initial = line.countedQuantity
            ?: perScan.takeIf { it > BigDecimal.ONE }?.stripTrailingZeros()?.toPlainString()
            ?: ""
        _prompt.value = PromptRequest(line.journalLineId, initial)
    }

    /** OK in the popup. Whole pieces: digits only; "007" is 7; blank clears the count. */
    fun confirmQuantity(journalLineId: String, text: String) {
        viewModelScope.launch {
            if (!state.value.locked) {
                val digits = text.filter { it.isDigit() }.take(MAX_DIGITS)
                val value = if (digits.isEmpty()) null else digits.trimStart('0').ifEmpty { "0" }
                val by = workerSession.session.value?.userName
                val at = System.currentTimeMillis()
                countingDao.recordCount(journalLineId, value, by, at)
                if (value == null) countingDao.recordMarked(journalLineId, false, by, at)
            }
            _prompt.value = null
            _refocus.update { it + 1 }
        }
    }

    fun cancelPrompt() {
        _prompt.value = null
        _refocus.update { it + 1 }
    }

    /** A scan that did not land. Silent for typed text, which may just be a search term. */
    private fun miss(message: String, typed: Boolean, unexpectedItemId: String? = null) {
        if (typed && unexpectedItemId == null) return
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
    val journalDescription: String = "",
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
) {
    val percent: Int get() = if (totalLines == 0) 0 else (counted * 100) / totalLines
}
