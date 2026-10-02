package com.d365fo.mobileops.data.repository

import com.d365fo.mobileops.data.counting.CountSubmissions
import com.d365fo.mobileops.data.counting.DirectCountingRepository
import com.d365fo.mobileops.data.local.dao.JournalDao
import com.d365fo.mobileops.data.local.entity.CountSubmissionEntity
import com.d365fo.mobileops.data.local.entity.CountSubmissionState
import com.d365fo.mobileops.data.local.entity.TransferMode
import com.d365fo.mobileops.domain.model.CountRange
import com.d365fo.mobileops.domain.model.CountingJournalSummary
import com.d365fo.mobileops.domain.model.ExportJobStatus
import com.d365fo.mobileops.domain.model.ImportJobStatus
import com.d365fo.mobileops.util.NetworkResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One counting journal's "Get data" run. [imported] is true only once the lines are actually in
 * Room and ready to count against; [status] `Ready` with [imported] false is the download/import
 * still in progress.
 */
data class CountingDownload(
    val journalId: String,
    val description: String?,
    val status: ExportJobStatus,
    val imported: Boolean,
    val startedAt: Long,
    /** What a finished run did, when there is something to say - see [ImportSummary.note]. */
    val note: String? = null,
) {
    val inProgress: Boolean
        get() = !imported && status !is ExportJobStatus.Failed

    val label: String
        get() = when {
            imported -> note ?: "Ready - lines are on this device"
            status is ExportJobStatus.Enqueued -> "Requesting export…"
            status is ExportJobStatus.Running -> status.percent?.let { "Fetching… $it%" } ?: "Fetching…"
            status is ExportJobStatus.Ready -> "Importing lines…"
            status is ExportJobStatus.Failed -> status.reason
            else -> ""
        }
}

/** One journal's "End count" run - the counted quantities going back to D365FO. */
data class CountingSubmission(
    val journalId: String,
    val description: String?,
    val status: ImportJobStatus,
    val startedAt: Long,
) {
    val inProgress: Boolean
        get() = status is ImportJobStatus.Enqueued || status is ImportJobStatus.Running

    val label: String
        get() = when (status) {
            ImportJobStatus.Enqueued -> "Submitting counts…"
            is ImportJobStatus.Running -> status.percent?.let { "Submitting counts… $it%" } ?: "Submitting counts…"
            ImportJobStatus.Succeeded -> "Counts submitted to D365FO"
            is ImportJobStatus.PartiallySucceeded -> "Counts submitted, but some lines were not accepted: ${status.warning}"
            is ImportJobStatus.Failed -> status.reason
        }
}

/**
 * A download or a submission, in the one shape the Activity page lists. See
 * [CountingDownloadTracker.activities].
 */
data class CountingActivity(
    /** Unique across both kinds - pass to [CountingDownloadTracker.dismiss]. */
    val key: String,
    val journalId: String,
    val title: String,
    val description: String?,
    val label: String,
    val percent: Int?,
    val inProgress: Boolean,
    val failed: Boolean,
    /** Finished, but with something the worker should know - e.g. some lines were refused. */
    val warning: Boolean = false,
    val startedAt: Long,
)

/**
 * Runs each journal's export/download/import ("Get data") on the application scope, not a
 * ViewModel's - a worker who taps a button and immediately navigates elsewhere must not lose the
 * run, and the Activity page has to be able to show it. A killed download is simply started
 * again from the card.
 *
 * End count is not run here any more: it is a WorkManager job (CountSubmitWorker) driven by the
 * persisted count_submission row. This class only renders those rows for the Activity page.
 */
@Singleton
class CountingDownloadTracker @Inject constructor(
    private val repository: CountingJournalRepository,
    private val direct: DirectCountingRepository,
    private val countSubmissions: CountSubmissions,
    private val lineAttributes: com.d365fo.mobileops.data.counting.LineAttributesSync,
    private val flags: com.d365fo.mobileops.domain.FeatureFlags,
    journalDao: JournalDao,
    private val scope: CoroutineScope,
) {
    private val _downloads = MutableStateFlow<Map<String, CountingDownload>>(emptyMap())
    val downloads: StateFlow<Map<String, CountingDownload>> = _downloads.asStateFlow()

    /** Every End count on the device, from Room - survives the process being killed. */
    val submissions: StateFlow<Map<String, CountingSubmission>> =
        combine(countSubmissions.observe(), journalDao.observeCountingJournals()) { rows, journals ->
            val descriptions = journals.associate { it.journalId to it.description }
            rows.associate { it.journalId to it.toSubmission(descriptions[it.journalId]) }
        }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** Both kinds merged, newest first - what the Activity page lists. */
    val activities: StateFlow<List<CountingActivity>> =
        combine(_downloads, submissions) { downloads, submissions ->
            (downloads.values.map { it.toActivity() } + submissions.values.map { it.toActivity() })
                .sortedByDescending { it.startedAt }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val jobs = mutableMapOf<String, Job>()

    // -- Get data ---------------------------------------------------------------------------

    /**
     * [range] and [claimToken] come from the claim taken just before, when claims are on; a
     * DIRECT journal skips DMF entirely.
     */
    @Synchronized
    fun start(journal: CountingJournalSummary, warehouseId: String, range: CountRange? = null, claimToken: String? = null) {
        val key = downloadKey(journal.journalId)
        if (jobs[key]?.isActive == true) return

        setDownload(journal, ExportJobStatus.Enqueued, imported = false, startedAt = System.currentTimeMillis())
        jobs[key] = scope.launch {
            if (journal.transferMode == TransferMode.DIRECT) {
                runDirectDownload(journal, warehouseId, range, claimToken)
            } else {
                runDownload(journal, warehouseId, range, claimToken)
            }
        }
    }

    /** Removes a finished (or failed) entry from the Activity page. No effect while running. */
    @Synchronized
    fun dismiss(key: String) {
        if (jobs[key]?.isActive == true) return
        _downloads.update { it.filterKeys { id -> downloadKey(id) != key } }
        if (key.startsWith(SUBMIT_PREFIX)) {
            scope.launch { countSubmissions.dismiss(key.removePrefix(SUBMIT_PREFIX)) }
        }
    }

    private suspend fun runDirectDownload(
        journal: CountingJournalSummary,
        warehouseId: String,
        range: CountRange?,
        claimToken: String?,
    ) {
        setDownload(journal, ExportJobStatus.Running(null), imported = false)
        when (val result = direct.downloadLines(journal, warehouseId, range, claimToken)) {
            is NetworkResult.Error -> failDownload(journal, result.failure.message)
            NetworkResult.Loading -> Unit
            is NetworkResult.Success -> {
                if (flags.countLocationTypes) runCatching { lineAttributes.apply(journal.journalId) }
                setDownload(journal, ExportJobStatus.Ready(""), imported = true, note = result.data.note)
            }
        }
    }

    private suspend fun runDownload(
        journal: CountingJournalSummary,
        warehouseId: String,
        range: CountRange?,
        claimToken: String?,
    ) {
        val executionId = when (val started = repository.startExport(journal.journalId, range, claimToken)) {
            is NetworkResult.Error -> return failDownload(journal, started.failure.message)
            NetworkResult.Loading -> return failDownload(journal, "did not start")
            is NetworkResult.Success -> started.data
        }

        repeat(POLL_ATTEMPTS_MAX) {
            delay(POLL_INTERVAL_MS)

            when (val poll = repository.pollExport(executionId)) {
                is NetworkResult.Error -> return failDownload(journal, poll.failure.message)
                NetworkResult.Loading -> Unit
                is NetworkResult.Success -> {
                    setDownload(journal, poll.data, imported = false)
                    when (val s = poll.data) {
                        is ExportJobStatus.Ready -> return importLines(journal, s, warehouseId, range)
                        is ExportJobStatus.Failed -> return
                        else -> Unit
                    }
                }
            }
        }

        failDownload(journal, "Timed out waiting for the export")
    }

    private suspend fun importLines(
        journal: CountingJournalSummary,
        ready: ExportJobStatus.Ready,
        warehouseId: String,
        range: CountRange?,
    ) {
        when (val result = repository.downloadAndImportLines(journal, ready.downloadUrl, warehouseId, range)) {
            is NetworkResult.Error -> failDownload(journal, result.failure.message)
            NetworkResult.Loading -> Unit
            is NetworkResult.Success -> {
                // Location types / verify-only before the journal shows as ready, so a counter
                // never starts counting a transit line as if it were physical stock.
                if (flags.countLocationTypes) runCatching { lineAttributes.apply(journal.journalId) }
                setDownload(journal, ready, imported = true, note = result.data.note)
                // The export carries no item names; resolve them now that the lines are on the
                // device. Best effort and after the journal is usable - the lines screen picks
                // the names up live as they land, and can retry whatever is left.
                runCatching { repository.enrichItemNames(journal.journalId) }
            }
        }
    }

    private fun failDownload(journal: CountingJournalSummary, reason: String) =
        setDownload(journal, ExportJobStatus.Failed(reason), imported = false)

    private fun setDownload(
        journal: CountingJournalSummary,
        status: ExportJobStatus,
        imported: Boolean,
        startedAt: Long? = null,
        note: String? = null,
    ) {
        _downloads.update { current ->
            val existing = current[journal.journalId]
            current + (journal.journalId to CountingDownload(
                journalId = journal.journalId,
                description = journal.description,
                status = status,
                imported = imported,
                startedAt = startedAt ?: existing?.startedAt ?: System.currentTimeMillis(),
                note = note,
            ))
        }
    }

    private fun CountSubmissionEntity.toSubmission(description: String?) = CountingSubmission(
        journalId = journalId,
        description = description,
        status = when (state) {
            CountSubmissionState.QUEUED -> ImportJobStatus.Enqueued
            CountSubmissionState.UPLOADING -> ImportJobStatus.Running(null)
            CountSubmissionState.IMPORTING -> ImportJobStatus.Running(percent)
            CountSubmissionState.SUCCEEDED -> ImportJobStatus.Succeeded
            CountSubmissionState.PARTIAL -> ImportJobStatus.PartiallySucceeded(message.orEmpty())
            CountSubmissionState.FAILED -> ImportJobStatus.Failed(message ?: "The submission failed.")
        },
        startedAt = submittedAt,
    )

    private fun CountingDownload.toActivity() = CountingActivity(
        key = downloadKey(journalId),
        journalId = journalId,
        title = "Data download",
        description = description,
        label = label,
        percent = (status as? ExportJobStatus.Running)?.percent,
        inProgress = inProgress,
        failed = status is ExportJobStatus.Failed,
        startedAt = startedAt,
    )

    private fun CountingSubmission.toActivity() = CountingActivity(
        key = submitKey(journalId),
        journalId = journalId,
        title = "Count submission",
        description = description,
        label = label,
        percent = (status as? ImportJobStatus.Running)?.percent,
        inProgress = inProgress,
        failed = status is ImportJobStatus.Failed,
        warning = status is ImportJobStatus.PartiallySucceeded,
        startedAt = startedAt,
    )

    private fun downloadKey(journalId: String) = "download:$journalId"
    private fun submitKey(journalId: String) = "$SUBMIT_PREFIX$journalId"

    private companion object {
        const val POLL_INTERVAL_MS = 3_000L
        const val POLL_ATTEMPTS_MAX = 100
        const val SUBMIT_PREFIX = "submit:"
    }
}
