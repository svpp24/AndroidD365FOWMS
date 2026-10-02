package com.d365fo.mobileops.domain.model

import com.d365fo.mobileops.data.local.entity.TransferMode

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
    val transferMode: TransferMode = TransferMode.DMF,
    /** Who currently holds this journal, or which of its ranges - display only. */
    val claims: List<CountClaimInfo> = emptyList(),
    /** Claimable location ranges of a wall-to-wall journal; empty means it is claimed whole. */
    val ranges: List<CountRange> = emptyList(),
    /** Count location by location - decided by the server. */
    val locationFirst: Boolean = false,
    /** The system expects these locations to be empty. */
    val zeroStockCheck: Boolean = false,
    /** SINGLE or DOUBLE_BLIND - decided by the server. */
    val countMode: String = COUNT_MODE_SINGLE,
    /** 1 for a first count, 2+ for a recount or second pass. */
    val passNumber: Int = 1,
    /** For a RECOUNT journal: the journal it recounts. */
    val recountOf: String? = null,
    /** SCHEDULED, PERIODIC, SPOT, TRIGGERED, OPPORTUNITY, CONTROL_GROUP or RECOUNT. */
    val originType: String = ORIGIN_SCHEDULED,
    /** Higher first - decided by the server. */
    val priority: Int = 0,
    /** Display only, from the server. */
    val toleranceInfo: String? = null,
    val abcClass: String? = null,
    /** NONE, LOCATION_FROZEN or SNAPSHOT_ONLY - decided by the server. */
    val freezeState: String = FREEZE_NONE,
    val snapshotAt: Long? = null,
    /** CONTROL_GROUP: the fixed sample this journal repeats, and a display-only repeat note. */
    val controlGroupId: String? = null,
    val repeatInfo: String? = null,
) {
    val doubleBlind: Boolean get() = countMode == COUNT_MODE_DOUBLE_BLIND
}

const val ORIGIN_SCHEDULED = "SCHEDULED"
const val FREEZE_NONE = "NONE"

/**
 * The banner for a journal's stock-freeze state, or null when there is none. [clock] formats the
 * snapshot time for display. How movements during the count are measured is the server's
 * business - the app only sends timestamps.
 */
fun freezeBanner(freezeState: String, snapshotAt: Long?, clock: (Long) -> String): String? = when (freezeState) {
    "LOCATION_FROZEN" -> "Stock movements are blocked here while this count is open."
    "SNAPSHOT_ONLY" -> snapshotAt?.let { "Count is measured against stock at ${clock(it)}." }
        ?: "Count is measured against a stock snapshot."
    else -> null
}

/** The badge for a location type; null for ordinary physical stock. */
fun locationTypeLabel(locationType: String): String? = when (locationType) {
    "VIRTUAL" -> "Virtual"
    "CONSIGNMENT_OWN" -> "Consignment (own)"
    "CONSIGNMENT_VENDOR" -> "Vendor consignment"
    "TRANSIT" -> "In transit"
    else -> null
}

/** The badge for a count origin; null for an ordinary scheduled count. */
fun originLabel(originType: String): String? = when (originType) {
    "PERIODIC" -> "Periodic"
    "SPOT" -> "Spot count"
    "TRIGGERED" -> "Triggered"
    "OPPORTUNITY" -> "Opportunity"
    "CONTROL_GROUP" -> "Control group"
    "RECOUNT" -> "Recount"
    else -> null
}

/** Server priority first, then the oldest journal - the list never ranks origins itself. */
fun List<CountingJournalSummary>.byPriority(): List<CountingJournalSummary> =
    sortedWith(compareByDescending<CountingJournalSummary> { it.priority }.thenBy { it.journalDate ?: Long.MAX_VALUE })

const val COUNT_MODE_SINGLE = "SINGLE"
const val COUNT_MODE_DOUBLE_BLIND = "DOUBLE_BLIND"

/**
 * Blind recounts: on a second pass, or on any double-blind journal, a counter must never start
 * from what D365FO already holds as the count - that is someone else's count.
 */
fun keepsServerCounts(passNumber: Int, countMode: String): Boolean =
    passNumber <= 1 && countMode != COUNT_MODE_DOUBLE_BLIND

data class CountClaimInfo(
    val claimedBy: String,
    val deviceId: String?,
    val rangeId: String?,
    val locationFrom: String?,
    val locationTo: String?,
    val expiresAt: Long?,
)

data class CountRange(
    val rangeId: String,
    val label: String,
    val locationFrom: String?,
    val locationTo: String?,
    val lineCount: Int,
) {
    /** Whether [locationId] falls in this range - the app's own safety filter after a download. */
    fun contains(locationId: String?): Boolean {
        val loc = locationId.orEmpty()
        val from = locationFrom?.takeIf { it.isNotBlank() }
        val to = locationTo?.takeIf { it.isNotBlank() }
        return (from == null || loc >= from) && (to == null || loc <= to)
    }
}

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
