package com.d365fo.mobileops.presentation.modules.cyclecounting

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.d365fo.mobileops.data.local.dao.CountLine
import com.d365fo.mobileops.data.local.dao.CountingDao
import com.d365fo.mobileops.data.local.dao.JournalDao
import com.d365fo.mobileops.data.repository.WorkerSessionRepository
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

/** What the last scan did, shown under the scan box. */
data class ScanMessage(val text: String, val isError: Boolean)

/** The quantity popup: which line, and what to start the box at. */
data class CountPrompt(val line: CountLine, val initial: String)

/**
 * The "Start counting" page: the journal's items in a light list, a scan box, and a quantity
 * popup. Scan (hardware, camera, or type and press Enter) an item or barcode and a popup asks for
 * the quantity; OK saves it and puts the cursor back in the scan box, ready for the next scan.
 * Tapping a row opens the same popup to correct it. View lines is the separate, read-only
 * overview with a counted / not counted filter.
 *
 * What is typed in the scan box also filters the list live, so it doubles as a search; Enter
 * treats it as a scan and stays quiet if it is not an item or barcode.
 *
 * A scan of an item number is matched against the lines already on the device (instant,
 * offline); only if that misses is it looked up as a barcode, which resolves to an item number and
 * a quantity-per-scan (a case barcode starts the popup at the pack size). Counts are whole pieces.
 * An item on several lines (locations / batches) first asks which one. Every save is written to
 * Room immediately, so an interrupted count loses nothing.
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
) : ViewModel() {

    val journalId: String = checkNotNull(savedStateHandle["journalId"])

    private val _query = MutableStateFlow("")
    private val _prompt = MutableStateFlow<PromptRequest?>(null)
    private val _choiceIds = MutableStateFlow<List<String>?>(null)
    private val _message = MutableStateFlow<ScanMessage?>(null)

    /** Bumped whenever the scan box should take focus back (after the popup closes). */
    private val _refocus = MutableStateFlow(0)
    private var scanJob: Job? = null

    private data class PromptRequest(val lineId: String, val initial: String)

    private data class Base(
        val lines: List<CountLine>,
        val description: String,
        val locked: Boolean,
        val query: String,
    )

    private val base = combine(
        countingDao.observeCountLines(journalId),
        journalDao.observeCountingJournals().map { list -> list.firstOrNull { it.journalId == journalId } },
        _query,
    ) { lines, journal, query ->
        Base(
            lines = lines,
            description = journal?.description ?: journalId,
            locked = journal?.status in LOCKED_STATUSES,
            query = query,
        )
    }

    val state: StateFlow<CountingScanState> = combine(
        base, _prompt, _choiceIds, _message, _refocus,
    ) { b, prompt, choiceIds, message, refocus ->
        val byId = b.lines.associateBy { it.journalLineId }
        CountingScanState(
            journalDescription = b.description,
            totalLines = b.lines.size,
            counted = b.lines.count { it.isCounted },
            locked = b.locked,
            lines = if (b.query.isBlank()) b.lines else b.lines.filter { it.matches(b.query) },
            query = b.query,
            prompt = prompt?.let { p -> byId[p.lineId]?.let { CountPrompt(it, p.initial) } },
            choice = choiceIds?.mapNotNull { byId[it] }.orEmpty(),
            message = message,
            refocusNonce = refocus,
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
        // A scan while the popup is open must not silently replace what is being entered.
        if (_prompt.value != null || _choiceIds.value != null) {
            scanFeedback.warning()
            return
        }
        scanJob?.cancel()
        scanJob = viewModelScope.launch { handleScan(value, typed) }
    }

    private suspend fun handleScan(value: String, typed: Boolean) {
        val lines = countingDao.getCountLines(journalId)

        // Item number first: by far the common scan, and needs no network.
        var itemId = lines.firstOrNull { it.itemId.equals(value, ignoreCase = true) }?.itemId
        var perScan = BigDecimal.ONE

        if (itemId == null) {
            when (val match = lookups.lookupBarcode(value)) {
                is NetworkResult.Error -> return miss("Could not look up $value: ${match.failure.message}", typed)
                NetworkResult.Loading -> return miss("Lookup did not complete", typed)
                is NetworkResult.Success -> {
                    val resolved = match.data ?: return miss("$value is not an item on this journal", typed)
                    perScan = resolved.quantityPerScan
                    itemId = lines.firstOrNull { it.itemId.equals(resolved.itemNumber, ignoreCase = true) }?.itemId
                        ?: return miss("${resolved.itemNumber} (barcode $value) is not on this journal", typed)
                }
            }
        }

        if (state.value.locked) {
            _message.value = ScanMessage("Counts have been submitted, so $itemId can't be changed.", isError = true)
            scanFeedback.warning()
            return
        }

        val matching = lines.filter { it.itemId.equals(itemId, ignoreCase = true) }.sortedBy { it.lineNumber }
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
    private fun miss(message: String, typed: Boolean) {
        if (typed) return
        _message.value = ScanMessage(message, isError = true)
        scanFeedback.error()
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
    /** The journal's lines, narrowed by whatever is typed in the scan box. */
    val lines: List<CountLine> = emptyList(),
    val query: String = "",
    /** Non-null while the quantity popup is open. */
    val prompt: CountPrompt? = null,
    /** Non-empty while the worker is picking one of several lines for a scanned item. */
    val choice: List<CountLine> = emptyList(),
    val message: ScanMessage? = null,
    val refocusNonce: Int = 0,
) {
    val percent: Int get() = if (totalLines == 0) 0 else (counted * 100) / totalLines
}
