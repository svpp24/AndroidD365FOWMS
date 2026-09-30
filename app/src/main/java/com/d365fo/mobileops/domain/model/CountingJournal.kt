package com.d365fo.mobileops.domain.model

/**
 * One open counting journal for the worker's assigned warehouse — header only.
 *
 * Fetched by [com.d365fo.mobileops.data.repository.CountingJournalRepository.listAssignedJournals],
 * a cheap direct read (see `HSMobileOpsCountingService::listOpenJournals`'s own class doc). The
 * journal's lines are a separate, bulk fetch that only happens once the worker opens one of these.
 */
data class CountingJournalSummary(
    val journalId: String,
    val journalNameId: String,
    val description: String?,
    val journalDate: Long?,
    val lineCount: Int,
)

/**
 * Where a journal's bulk line export is, end to end.
 *
 * Mirrors the DMF Recurring Integrations job lifecycle this is built on: enqueue, poll, then
 * either a downloadable result or a failure — there is no "partially ready" state, because DMF
 * does not expose one and a partial local import would be worse than none (a worker counting
 * against half a journal without knowing it).
 */
sealed interface ExportJobStatus {
    data object Enqueued : ExportJobStatus

    /** [percent] is null, not 0, when the server doesn't report row-level progress — whether it
     *  ever does on this tenant is unconfirmed (see HSMobileOpsExportStatusResponse's own doc
     *  comment). Null renders as an indeterminate spinner, never as a fabricated 0%. */
    data class Running(val percent: Int?) : ExportJobStatus

    /** [downloadUrl] is a direct blob URL — the download happens against storage, not the AOS
     *  session that issued it. See CountingJournalRepository.downloadAndImport. */
    data class Ready(val downloadUrl: String) : ExportJobStatus
    data class Failed(val reason: String) : ExportJobStatus
}

/**
 * Where the counted-quantity-back import is, end to end - the mirror of [ExportJobStatus] for
 * the opposite direction. No [ExportJobStatus.Ready] equivalent: an import has no downloadable
 * result, only success or failure, so [Succeeded] is the terminal state on its own.
 */
sealed interface ImportJobStatus {
    data object Enqueued : ImportJobStatus
    data class Running(val percent: Int?) : ImportJobStatus
    data object Succeeded : ImportJobStatus

    /** D365FO ran the import but refused some rows; [warning] says which and why. The rest landed. */
    data class PartiallySucceeded(val warning: String) : ImportJobStatus
    data class Failed(val reason: String) : ImportJobStatus
}
