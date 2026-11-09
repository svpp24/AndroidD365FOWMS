package com.d365fo.mobileops.presentation.modules.cyclecounting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.d365fo.mobileops.data.counting.CountClaimManager
import com.d365fo.mobileops.data.counting.CountSubmissions
import com.d365fo.mobileops.data.counting.EndCountResult
import com.d365fo.mobileops.data.local.dao.CaptureExtrasRow
import com.d365fo.mobileops.data.local.dao.CountingDao
import com.d365fo.mobileops.data.local.dao.CountingProgressRow
import com.d365fo.mobileops.data.local.dao.JournalDao
import com.d365fo.mobileops.data.local.entity.CountClaimEntity
import com.d365fo.mobileops.data.local.entity.CountSubmissionEntity
import com.d365fo.mobileops.data.local.entity.JournalEntity
import com.d365fo.mobileops.data.local.entity.JournalStatus
import com.d365fo.mobileops.data.local.entity.TransferMode
import com.d365fo.mobileops.data.repository.CountingDownloadTracker
import com.d365fo.mobileops.data.repository.CountingJournalRepository
import com.d365fo.mobileops.data.repository.WorkerSessionRepository
import com.d365fo.mobileops.domain.FeatureFlags
import com.d365fo.mobileops.domain.counting.ClaimOutcome
import com.d365fo.mobileops.domain.counting.ClaimRefusal
import com.d365fo.mobileops.domain.counting.CountTaskRepository
import com.d365fo.mobileops.domain.counting.JournalRemoteState
import com.d365fo.mobileops.domain.model.CountRange
import com.d365fo.mobileops.domain.model.CountingJournalSummary
import com.d365fo.mobileops.domain.model.byPriority
import com.d365fo.mobileops.domain.model.ExportJobStatus
import com.d365fo.mobileops.util.NetworkResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Where one counting journal is in its life on this device. */
enum class CountPhase {
    /** Data is on the device; counting has not been started. */
    READY,

    /** Start counting was pressed; counts are being entered. */
    COUNTING,

    /** End count was pressed and the counts are on their way to D365FO (a WorkManager job). */
    SUBMITTING,

    /** D365FO accepted the counts. The journal is still open there until a supervisor posts it. */
    SUBMITTED,

    /** The journal was posted in D365FO. Closed for good on this device. */
    POSTED,

    /** The last submission failed - End count can be pressed again. */
    FAILED,
}

/** A journal whose lines are on this device, and how far the count has got. */
data class JournalCountState(
    val journalId: String,
    val phase: CountPhase,
    val total: Int,
    /** Lines with an entered quantity. */
    val withQuantity: Int,
    /** Lines marked counted without a quantity. */
    val markedOnly: Int,
    /** FAILED: why. SUBMITTED: a warning - some lines were refused - or null if all landed. */
    val error: String? = null,
    /** Items recorded as "Not on this journal". */
    val exceptions: Int = 0,
    /** Of those, how many the server refused and are waiting to be sent again. */
    val failedExceptions: Int = 0,
    /** Locations confirmed empty. */
    val emptyLocations: Int = 0,
    /** What the server said about the last pass (double-blind), display only. */
    val passNote: String? = null,
    val thirdCountNeeded: Boolean = false,
) {
    val counted: Int get() = withQuantity + markedOnly
    val notCounted: Int get() = (total - counted).coerceAtLeast(0)
    /** Whole-number percentage of lines counted (quantity entered or marked), 0 for an empty journal. */
    val percent: Int get() = if (total == 0) 0 else (counted * 100) / total
}

/**
 * Open counting journals for this worker's assigned warehouse. Loads itself on entry.
 *
 * Before data, "Get data" hands the journal to [CountingDownloadTracker]. With claims on
 * ([FeatureFlags.countClaims]) the journal - or, for a wall-to-wall journal the planner split
 * into ranges, one range picked by the worker - is claimed first, and only that range's lines
 * are downloaded. After data, the card's state comes from Room. End count persists a submission
 * and hands it to WorkManager ([CountSubmissions]); nothing about it lives only in memory.
 */
@HiltViewModel
class CountingJournalListViewModel @Inject constructor(
    private val blindCountNotices: com.d365fo.mobileops.data.counting.BlindCountNotices,
    private val journals: CountingJournalRepository,
    private val workerSession: WorkerSessionRepository,
    private val downloads: CountingDownloadTracker,
    private val journalDao: JournalDao,
    private val claims: CountClaimManager,
    private val countSubmissions: CountSubmissions,
    private val countTasks: CountTaskRepository,
    private val flags: FeatureFlags,
    private val countingDao: CountingDao,
    private val triggeredAlerts: com.d365fo.mobileops.data.counting.TriggeredCountAlerts,
    private val counterProfiles: com.d365fo.mobileops.data.counting.CounterProfiles,
) : ViewModel() {

    /** The signed-in user, to tell a claim of theirs from someone else's. */
    val currentUserName: String? get() = workerSession.session.value?.userName

    private val _loaded = MutableStateFlow(
        CountingJournalListState(
            claimsEnabled = flags.countClaims,
            // Cosmetic gate: the supervisor service checks IsCycleCountSupervisor on every call.
            canReviewVariance = flags.countSupervisor && workerSession.session.value?.isCycleCountSupervisor == true,
            // Cosmetic gate too: createSpotCount checks the permission server-side.
            canCreateSpot = flags.countSpot &&
                (workerSession.session.value?.isCycleCountSupervisor == true || flags.countSpotForWorkers),
        ),
    )

    private fun applyCapabilities(profile: com.d365fo.mobileops.domain.counting.CounterProfile?) {
        val caps = com.d365fo.mobileops.data.counting.countingCapabilities(
            flags, profile, workerSession.session.value?.isCycleCountSupervisor == true,
        )
        _loaded.update {
            it.copy(
                canReviewVariance = caps.canReviewVariance,
                canCreateSpot = caps.canCreateSpot,
                // Same supervisor gate as variance review; getAccuracyReport checks it again.
                canViewAccuracy = flags.countAccuracy && !caps.externalCounter &&
                    (profile?.isCycleCountSupervisor ?: (workerSession.session.value?.isCycleCountSupervisor == true)),
                externalCounter = caps.externalCounter,
                counterTeam = profile?.counterTeam,
            )
        }
    }

    private data class RoomSide(
        val journals: List<JournalEntity>,
        val progress: List<CountingProgressRow>,
        val claims: List<CountClaimEntity>,
        val submissions: List<CountSubmissionEntity>,
        val extras: List<CaptureExtrasRow>,
    )

    private val roomSide = combine(
        journalDao.observeCountingJournals(),
        journalDao.observeCountingProgress(),
        claims.observe(),
        countSubmissions.observe(),
        countingDao.observeCaptureExtras(),
    ) { journals, progress, claimRows, submissions, extras -> RoomSide(journals, progress, claimRows, submissions, extras) }

    /** What the last dual count came to: the journal itself has left the device. */
    val blindCountNotice: StateFlow<String?> get() = blindCountNotices.notice

    fun dismissBlindCountNotice() = blindCountNotices.dismiss()

    val state: StateFlow<CountingJournalListState> = combine(
        _loaded,
        downloads.downloads,
        roomSide,
    ) { loaded, tracked, room ->
        val progressById = room.progress.associateBy { it.journalId }
        val submissionById = room.submissions.associateBy { it.journalId }
        val extrasById = room.extras.associateBy { it.journalId }
        // Room is the source of truth: a journal whose lines are on this device is listed even when
        // the server list could not be loaded (offline) or no longer includes it (posted/closed
        // there) - its counts must stay reachable. Server rows win when both exist.
        val serverIds = loaded.journals.mapTo(HashSet()) { it.journalId }
        // Only this warehouse's: a journal downloaded at another warehouse stays on the device
        // (on hold) and is listed again when the worker switches back there.
        val here = loaded.warehouseId
        val localOnly = room.journals
            .filter { (progressById[it.journalId]?.total ?: 0) > 0 }
            .filter { it.journalId !in serverIds }
            .filter { here.isNullOrBlank() || it.warehouseId.isNullOrBlank() || it.warehouseId.equals(here, ignoreCase = true) }
            .map { journal ->
                CountingJournalSummary(
                    journalId = journal.journalId,
                    journalNameId = journal.journalNameId,
                    description = journal.description,
                    journalDate = journal.journalDate,
                    lineCount = progressById[journal.journalId]?.total ?: 0,
                    transferMode = journal.transferMode,
                    locationFirst = journal.locationFirst,
                    zeroStockCheck = journal.zeroStockCheck,
                    countMode = journal.countMode,
                    passNumber = journal.passNumber,
                    recountOf = journal.recountOf,
                    originType = journal.originType,
                    priority = journal.priority,
                    toleranceInfo = journal.toleranceInfo,
                    abcClass = journal.abcClass,
                    freezeState = journal.freezeState,
                    snapshotAt = journal.snapshotAt,
                )
            }
        val countStates = room.journals.filter { (progressById[it.journalId]?.total ?: 0) > 0 }.associate { journal ->
            journal.journalId to journal.toCountState(
                progressById[journal.journalId],
                submissionById[journal.journalId],
                extrasById[journal.journalId],
            )
        }
        val localOnlyIds = localOnly.mapTo(HashSet()) { it.journalId }
        val all = loaded.journals.byPriority() + localOnly.byPriority()
        loaded.copy(
            // What can still be counted first. Mixed in by date, a device with a history buried
            // today's journal under hundreds of closed ones.
            journals = all,
            localOnlyIds = localOnlyIds,
            doneIds = all.filter { isDone(it, countStates[it.journalId], it.journalId in localOnlyIds) }
                .mapTo(HashSet()) { it.journalId },
            exportStatuses = tracked
                .filterValues { !it.imported }
                .mapValues { (_, download) -> download.status },
            refreshNotes = tracked.values
                .filter { it.imported && it.note != null }
                .associate { it.journalId to it.note.orEmpty() },
            // A journal whose lines never landed (the app died during a download) is not on the
            // device yet: no counting controls, so the row offers Get data again.
            countStates = countStates,
            myClaims = room.claims.associateBy { it.journalId },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CountingJournalListState())

    init {
        applyCapabilities(null)
        if (flags.countExternalCounters) {
            viewModelScope.launch { applyCapabilities(counterProfiles.refresh()) }
        }
        viewModelScope.launch {
            val warehouseId = workerSession.session.value?.selected?.warehouseId
            _loaded.update { it.copy(warehouseId = warehouseId) }
            load(warehouseId)
        }
        // A claim given back (End count, or by hand): the list still holds D365FO's row from before,
        // which names the claim - and with this device's own gone, reads as "on another device".
        viewModelScope.launch {
            var held: Set<String>? = null
            claims.observe().collect { rows ->
                val now = rows.mapTo(HashSet()) { it.journalId }
                if (held?.any { it !in now } == true) load(_loaded.value.warehouseId)
                held = now
            }
        }
    }

    fun refresh() {
        viewModelScope.launch { load(_loaded.value.warehouseId) }
    }

    /** The journal's lines on this device as CSV - Export as CSV on the card (feedback, 12 Oct). */
    suspend fun csvFor(journalId: String): String =
        com.d365fo.mobileops.data.counting.CountCsv.of(journalId, countingDao.getCountLines(journalId))

    /** Says how an export went - the dialog the list already uses for messages. */
    fun exported(message: String) = showMessage(message)

    fun dismissMessage() = _loaded.update { it.copy(message = null) }

    /** Get data (or Get data again). Claims first when claims are on. */
    fun startExport(journalId: String) {
        val journal = state.value.journals.firstOrNull { it.journalId == journalId } ?: return
        val warehouseId = _loaded.value.warehouseId
        if (warehouseId.isNullOrBlank()) return
        if (journal.doubleBlind && journal.lineCount == 0) {
            showMessage("You have counted your lines on this journal. Use Review to see how it stands.")
            return
        }

        // A wall-to-wall count only works by location claims and direct transfer, whatever the
        // flags say for other journals: a location is a task, claimed by one counter.
        if (journal.transferMode == TransferMode.DIRECT && !flags.countDirect && !journal.isWallToWall) {
            showMessage("Direct transfer isn't enabled in this app.")
            return
        }
        if (!flags.countClaims && !journal.isWallToWall) {
            downloads.start(journal, warehouseId)
            return
        }

        viewModelScope.launch {
            val existing = claims.localClaim(journalId)
            when {
                existing != null && !existing.lost -> downloads.start(
                    journal, warehouseId, existing.toRange(), existing.claimToken,
                )
                existing != null -> showMessage(existing.lostReason ?: "Claim lost - claim again.")
                journal.ranges.isNotEmpty() -> _loaded.update { it.copy(rangePickerFor = journalId) }
                else -> claimAndDownload(journal, warehouseId, range = null)
            }
        }
    }

    fun pickRange(journalId: String, range: CountRange) {
        _loaded.update { it.copy(rangePickerFor = null) }
        val journal = state.value.journals.firstOrNull { it.journalId == journalId } ?: return
        val warehouseId = _loaded.value.warehouseId ?: return
        viewModelScope.launch { claimAndDownload(journal, warehouseId, range) }
    }

    fun cancelRangePicker() = _loaded.update { it.copy(rangePickerFor = null) }

    /** A lost claim: take it again (same range). Local counts are untouched either way. */
    fun reclaim(journalId: String) {
        viewModelScope.launch {
            _loaded.update { it.copy(claiming = journalId) }
            val outcome = claims.reclaim(journalId)
            _loaded.update { it.copy(claiming = null) }
            if (outcome is ClaimOutcome.Granted) {
                showMessage("Claimed again - you can end the count now.")
            } else {
                showMessage(outcome.describe())
            }
        }
    }

    /** Records the first Start counting press. The caller then navigates to the count screen. */
    fun startCounting(journalId: String) {
        viewModelScope.launch { journals.startCounting(journalId) }
    }

    /**
     * Reopens a SUBMITTED journal, only once the server confirms it is still open. A journal
     * posted in D365FO is locked on the device instead, and the worker is told why.
     */
    fun reopenCounting(journalId: String) {
        viewModelScope.launch {
            _loaded.update { it.copy(checking = journalId) }
            val result = countTasks.journalState(journalId)
            _loaded.update { it.copy(checking = null) }
            when (result) {
                is NetworkResult.Success -> when (result.data) {
                    JournalRemoteState.OPEN -> journals.reopenCounting(journalId)
                    JournalRemoteState.POSTED -> {
                        journals.markPostedInD365(journalId)
                        showMessage("$journalId is posted - can't reopen.")
                    }
                    JournalRemoteState.NOT_FOUND ->
                        showMessage("$journalId no longer exists.")
                }
                is NetworkResult.Error -> showMessage(
                    "Reopening needs a connection.",
                )
                NetworkResult.Loading -> Unit
            }
        }
    }

    /** End count: persisted and handed to WorkManager. Offline just waits for a connection. */
    fun endCount(journalId: String) {
        viewModelScope.launch {
            when (val result = countSubmissions.endCount(journalId)) {
                EndCountResult.Queued -> Unit
                is EndCountResult.Refused -> showMessage(result.message)
            }
        }
    }

    /** Sends unexpected items / empty locations again after the server refused them. */
    fun resendExceptions(journalId: String) {
        viewModelScope.launch {
            when (val result = countSubmissions.resendExceptions(journalId)) {
                EndCountResult.Queued -> Unit
                is EndCountResult.Refused -> showMessage(result.message)
            }
        }
    }

    private suspend fun claimAndDownload(journal: CountingJournalSummary, warehouseId: String, range: CountRange?) {
        _loaded.update { it.copy(claiming = journal.journalId) }
        val outcome = claims.claim(journal.journalId, range)
        _loaded.update { it.copy(claiming = null) }
        when {
            outcome is ClaimOutcome.Granted -> downloads.start(
                journal,
                warehouseId,
                range ?: outcome.rangeId?.let { CountRange(it, it, outcome.locationFrom, outcome.locationTo, 0) },
                outcome.claimToken,
            )
            // The server doesn't do claims (yet): it isn't enforcing them either, so count unclaimed
            // rather than block counting altogether.
            outcome is ClaimOutcome.Refused && outcome.reason == ClaimRefusal.NOT_AVAILABLE -> {
                downloads.start(journal, warehouseId, range, claimToken = null)
                // Said once per app session - repeating it on every "Get data" only interrupts.
                if (!claimNoticeShown) {
                    claimNoticeShown = true
                    showMessage("Counting without a claim.")
                }
            }
            else -> showMessage(outcome.describe())
        }
    }

    private fun ClaimOutcome.describe(): String = when (this) {
        is ClaimOutcome.Granted -> "Claimed."
        is ClaimOutcome.Unreachable -> "Can't claim - offline."
        is ClaimOutcome.Refused -> when (reason) {
            ClaimRefusal.CLAIMED_BY_OTHER ->
                claimedBy?.let { "Already claimed by $it." } ?: "Someone else has already claimed this."
            ClaimRefusal.EXPIRED, ClaimRefusal.LOST -> "Claim expired - try again."
            ClaimRefusal.JOURNAL_POSTED -> "Already posted."
            ClaimRefusal.JOURNAL_NOT_FOUND -> "Journal closed."
            ClaimRefusal.SAME_COUNTER -> "Someone else must do this recount."
            ClaimRefusal.NOT_AVAILABLE -> "Claiming isn't available."
            ClaimRefusal.OTHER -> message ?: "The claim was refused."
        }
    }

    private fun CountClaimEntity.toRange(): CountRange? =
        rangeId?.let { CountRange(it, it, locationFrom, locationTo, 0) }

    private fun showMessage(text: String) = _loaded.update { it.copy(message = text) }

    private companion object {
        /** Whether the "server doesn't support claiming" notice was shown this app session. */
        @Volatile var claimNoticeShown = false
    }

    private fun JournalEntity.toCountState(
        progress: CountingProgressRow?,
        submission: CountSubmissionEntity?,
        extras: CaptureExtrasRow?,
    ): JournalCountState {
        val submitting = submission?.state?.inProgress == true
        val phase = when (status) {
            JournalStatus.POSTED -> CountPhase.POSTED
            JournalStatus.SUBMITTED -> CountPhase.SUBMITTED
            // PENDING_POST with no live submission is a pre-WorkManager install's interrupted run.
            JournalStatus.PENDING_POST -> if (submitting) CountPhase.SUBMITTING else CountPhase.FAILED
            JournalStatus.FAILED -> CountPhase.FAILED
            JournalStatus.DRAFT -> if (countStartedAt != null) CountPhase.COUNTING else CountPhase.READY
        }
        val error = when {
            phase == CountPhase.FAILED && status == JournalStatus.PENDING_POST ->
                "Interrupted - tap End count again."
            phase == CountPhase.FAILED -> sync.lastError ?: submission?.message ?: "The submission failed."
            phase == CountPhase.SUBMITTED -> sync.lastError
            phase == CountPhase.SUBMITTING -> submission?.message
            else -> null
        }
        return JournalCountState(
            journalId = journalId,
            phase = phase,
            total = progress?.total ?: 0,
            withQuantity = progress?.withQuantity ?: 0,
            markedOnly = progress?.markedOnly ?: 0,
            error = error,
            exceptions = extras?.exceptions ?: 0,
            failedExceptions = extras?.failedExceptions ?: 0,
            emptyLocations = extras?.emptyLocations ?: 0,
            passNote = submission?.resultNote,
            thirdCountNeeded = submission?.thirdCountNeeded == true,
        )
    }

    private suspend fun load(warehouseId: String?) {
        if (warehouseId.isNullOrBlank()) {
            _loaded.update {
                it.copy(busy = false, error = "No warehouse assigned - ask a supervisor")
            }
            return
        }

        _loaded.update { it.copy(busy = true, error = null) }
        triggeredAlerts.rememberWarehouse(warehouseId)
        when (val result = journals.listAssignedJournals(warehouseId, workerSession.session.value?.userName, includeDone = true)) {
            is NetworkResult.Error -> _loaded.update {
                it.copy(busy = false, error = result.failure.message)
            }
            NetworkResult.Loading -> _loaded.update {
                it.copy(busy = false, error = "Lookup did not complete")
            }
            is NetworkResult.Success -> _loaded.update {
                it.copy(busy = false, journals = result.data)
            }
        }
    }
}

/**
 * Finished for this worker: posted, or its counts accepted with nothing left to do here - no
 * recount lines for them, no refused items to send again, no third count. A wall-to-wall journal
 * stays on its tab while D365FO still lists it, since other locations and passes may follow.
 */
internal fun isDone(journal: CountingJournalSummary, count: JournalCountState?, localOnly: Boolean): Boolean =
    journal.doneNote != null || when (count?.phase) {
        CountPhase.POSTED -> true
        CountPhase.SUBMITTED -> count.failedExceptions == 0 && !count.thirdCountNeeded &&
            journal.recountLines == 0 && (!journal.isWallToWall || localOnly)
        else -> false
    }

data class CountingJournalListState(
    val warehouseId: String? = null,
    val journals: List<CountingJournalSummary> = emptyList(),
    /** Journals listed only because their lines are on this device (not in the server list). */
    val localOnlyIds: Set<String> = emptySet(),
    val busy: Boolean = false,
    val error: String? = null,
    /** A "Get data" run in progress or failed, for journals with no data on the device yet. */
    val exportStatuses: Map<String, ExportJobStatus> = emptyMap(),
    val refreshNotes: Map<String, String> = emptyMap(),
    /** Journals whose lines are on this device, keyed by journal id. Derived from Room. */
    val countStates: Map<String, JournalCountState> = emptyMap(),
    val claimsEnabled: Boolean = false,
    /** Cycle-count supervisor with the supervisor feature on. */
    val canReviewVariance: Boolean = false,
    val canCreateSpot: Boolean = false,
    val canViewAccuracy: Boolean = false,
    /** A third-party counter - sees only their team's journals (server-side). */
    val externalCounter: Boolean = false,
    val counterTeam: String? = null,
    /** Journals this worker has finished, listed under Done instead of their usual tab. */
    val doneIds: Set<String> = emptySet(),
    /** This device's own claims, keyed by journal id. */
    val myClaims: Map<String, CountClaimEntity> = emptyMap(),
    /** Non-null while the worker is choosing which range of this journal to claim. */
    val rangePickerFor: String? = null,
    /** Journal id with a claim request in flight. */
    val claiming: String? = null,
    /** Journal id whose server state is being checked before a reopen. */
    val checking: String? = null,
    /** A one-off message for the worker (claim refused, can't reopen, ...). */
    val message: String? = null,
)
