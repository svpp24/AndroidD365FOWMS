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
    /** Dual count: how many of this person's lines are recounts, shown under Recount. */
    val recountLines: Int = 0,
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
    /** From D365FO: finished for this user - posted, or counted and waiting on others. Listed under Done. */
    val doneNote: String? = null,
) {
    val doubleBlind: Boolean get() = countMode == COUNT_MODE_DOUBLE_BLIND

    /** A wall-to-wall count: counted location by location, its warehouse frozen meanwhile. */
    val isWallToWall: Boolean get() = originType == ORIGIN_WALL_TO_WALL

    /** Under the Recount tab: it has recounts for this person. */
    val onRecountTab: Boolean get() = !isWallToWall && recountLines > 0

    /** Under the Cycle tab: anything else it has to count. A supervisor's journal with nothing left to count stays here too. */
    val onCycleTab: Boolean get() = !isWallToWall && (recountLines == 0 || lineCount > recountLines)

    /** Under the Wall-to-wall tab: first counts, second counts and recounts alike. */
    val onWallToWallTab: Boolean get() = isWallToWall
}

const val ORIGIN_WALL_TO_WALL = "WALL_TO_WALL"

const val ORIGIN_SCHEDULED = "SCHEDULED"
const val FREEZE_NONE = "NONE"

/**
 * The Inventory count sub-menus a journal is listed under (user, 8 Nov). Store count is the fourth
 * and has no journal on the device: it becomes one only in the Stock count control center.
 * Triggered, opportunity, control group and recount journals are cycle counting. There is no Spot
 * count sub-menu (user, 8 Nov: it is the same as Store count), so a spot journal still open from
 * before is listed under Cycle count, marked Spot count.
 */
enum class CountKind(val route: String) {
    CYCLE("cycle"),
    WALL_TO_WALL("w2w");

    companion object {
        fun fromRoute(value: String?): CountKind? = entries.firstOrNull { it.route == value }
    }
}

val CountingJournalSummary.kind: CountKind
    get() = when {
        isWallToWall -> CountKind.WALL_TO_WALL
        else -> CountKind.CYCLE
    }

/**
 * The shape the Counting screen takes (user, 8 Nov): the list and progress for an ordinary count,
 * location-first with a prominent Location is empty for wall-to-wall, and one card with a large
 * quantity box for an urgent check of a single line. Decided from what D365FO says the journal is.
 */
enum class CountingLayout { STANDARD, WALL_TO_WALL, FOCUSED }

/** Triggered and opportunity checks of one line, at a store without bins (a bin needs the list). */
fun countingLayout(originType: String?, lineCount: Int, binsRequired: Boolean): CountingLayout = when {
    originType == ORIGIN_WALL_TO_WALL -> CountingLayout.WALL_TO_WALL
    originType in FOCUSED_ORIGINS && lineCount == 1 && !binsRequired -> CountingLayout.FOCUSED
    else -> CountingLayout.STANDARD
}

private val FOCUSED_ORIGINS = setOf("TRIGGERED", "OPPORTUNITY")

/**
 * The banner for a journal's stock-freeze state, or null when there is none. [clock] formats the
 * snapshot time for display. How movements during the count are measured is the server's
 * business - the app only sends timestamps.
 */
fun freezeBanner(freezeState: String, snapshotAt: Long?, clock: (Long) -> String): String? = when (freezeState) {
    "LOCATION_FROZEN" -> "Stock is frozen while counting."
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
    ORIGIN_WALL_TO_WALL -> "Wall-to-wall"
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
