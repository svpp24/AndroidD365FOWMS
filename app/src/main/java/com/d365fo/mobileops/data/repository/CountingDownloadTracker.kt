package com.d365fo.mobileops.data.repository

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
 * Runs each journal's export/download/import ("Get data") and its counts submission ("End
 * count") on the application scope, not a ViewModel's - a worker who taps a button and
 * immediately navigates elsewhere must not lose the run, and the Activity page has to be able to
 * show it. Survives navigation, not the process being killed; a killed run is simply started
 * again from the card (a submission left `PENDING_POST` by a kill shows as failed-to-finish and
 * End count can be tapped again).
 */
@Singleton
class CountingDownloadTracker @Inject constructor(
    private val repository: CountingJournalRepository,
    private val scope: CoroutineScope,
) {
    private val _downloads = MutableStateFlow<Map<String, CountingDownload>>(emptyMap())
    val downloads: StateFlow<Map<String, CountingDownload>> = _downloads.asStateFlow()

    private val _submissions = MutableStateFlow<Map<String, CountingSubmission>>(emptyMap())
    val submissions: StateFlow<Map<String, CountingSubmission>> = _submissions.asStateFlow()

    /** Both kinds merged, newest first - what the Activity page lists. */
    val activities: StateFlow<List<CountingActivity>> =
        combine(_downloads, _submissions) { downloads, submissions ->
            (downloads.values.map { it.toActivity() } + submissions.values.map { it.toActivity() })
                .sortedByDescending { it.startedAt }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val jobs = mutableMapOf<String, Job>()

    // -- Get data ---------------------------------------------------------------------------

    @Synchronized
    fun start(journal: CountingJournalSummary, warehouseId: String) {
        val key = downloadKey(journal.journalId)
        if (jobs[key]?.isActive == true) return

        setDownload(journal, ExportJobStatus.Enqueued, imported = false, startedAt = System.currentTimeMillis())
        jobs[key] = scope.launch { runDownload(journal, warehouseId) }
    }

    // -- End count --------------------------------------------------------------------------

    @Synchronized
    fun submit(journalId: String, description: String?) {
        val key = submitKey(journalId)
        if (jobs[key]?.isActive == true) return

        setSubmission(journalId, description, ImportJobStatus.Enqueued, startedAt = System.currentTimeMillis())
        jobs[key] = scope.launch { runSubmit(journalId, description) }
    }

    /** Removes a finished (or failed) entry from the Activity page. No effect while running. */
    @Synchronized
    fun dismiss(key: String) {
        if (jobs[key]?.isActive == true) return
        _downloads.update { it.filterKeys { id -> downloadKey(id) != key } }
        _submissions.update { it.filterKeys { id -> submitKey(id) != key } }
    }

    private suspend fun runDownload(journal: CountingJournalSummary, warehouseId: String) {
        val executionId = when (val started = repository.startExport(journal.journalId)) {
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
                        is ExportJobStatus.Ready -> return importLines(journal, s, warehouseId)
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
    ) {
        when (val result = repository.downloadAndImportLines(journal, ready.downloadUrl, warehouseId)) {
            is NetworkResult.Error -> failDownload(journal, result.failure.message)
            NetworkResult.Loading -> Unit
            is NetworkResult.Success -> {
                setDownload(journal, ready, imported = true, note = result.data.note)
                // The export carries no item names; resolve them now that the lines are on the
                // device. Best effort and after the journal is usable - the lines screen picks
                // the names up live as they land, and can retry whatever is left.
                runCatching { repository.enrichItemNames(journal.journalId) }
            }
        }
    }

    private suspend fun runSubmit(journalId: String, description: String?) {
        repository.markSubmitting(journalId)

        val executionId = when (val started = repository.submitCounts(journalId)) {
            is NetworkResult.Error -> return failSubmit(journalId, description, started.failure.message)
            NetworkResult.Loading -> return failSubmit(journalId, description, "did not start")
            is NetworkResult.Success -> started.data
        }

        repeat(POLL_ATTEMPTS_MAX) {
            delay(POLL_INTERVAL_MS)

            when (val poll = repository.pollImport(executionId)) {
                is NetworkResult.Error -> return failSubmit(journalId, description, poll.failure.message)
                NetworkResult.Loading -> Unit
                is NetworkResult.Success -> {
                    when (val s = poll.data) {
                        ImportJobStatus.Succeeded -> {
                            repository.markSubmitted(journalId)
                            setSubmission(journalId, description, s)
                            return
                        }
                        is ImportJobStatus.PartiallySucceeded -> {
                            repository.markSubmittedWithWarning(journalId, s.warning)
                            setSubmission(journalId, description, s)
                            return
                        }
                        is ImportJobStatus.Failed -> return failSubmit(journalId, description, s.reason)
                        else -> setSubmission(journalId, description, s)
                    }
                }
            }
        }

        failSubmit(journalId, description, "Timed out waiting for the import")
    }

    private suspend fun failSubmit(journalId: String, description: String?, reason: String) {
        repository.markSubmitFailed(journalId, reason)
        setSubmission(journalId, description, ImportJobStatus.Failed(reason))
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

    private fun setSubmission(
        journalId: String,
        description: String?,
        status: ImportJobStatus,
        startedAt: Long? = null,
    ) {
        _submissions.update { current ->
            val existing = current[journalId]
            current + (journalId to CountingSubmission(
                journalId = journalId,
                description = description,
                status = status,
                startedAt = startedAt ?: existing?.startedAt ?: System.currentTimeMillis(),
            ))
        }
    }

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
    private fun submitKey(journalId: String) = "submit:$journalId"

    private companion object {
        const val POLL_INTERVAL_MS = 3_000L
        const val POLL_ATTEMPTS_MAX = 100
    }
}
