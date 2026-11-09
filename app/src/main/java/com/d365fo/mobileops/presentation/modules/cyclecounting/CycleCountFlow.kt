package com.d365fo.mobileops.presentation.modules.cyclecounting

import com.d365fo.mobileops.data.local.dao.CountLine
import com.d365fo.mobileops.data.local.entity.CountLineBinEntity
import com.d365fo.mobileops.presentation.common.components.StatusKind
import com.d365fo.mobileops.presentation.common.scan.BinRef

/**
 * The cycle-count flow's model (redesign phase 6) - pure, so it is unit-tested
 * (CycleCountFlowTest). The worker walks the count stop by stop: "Go to" names the next one, its
 * label is scanned, items are scanned blind (+1 each), and scanning the label again settles it -
 * an empty bin at 0. Bins are settled one by one; End count sends the journal as it always has.
 */

/** How a journal is walked: bin by bin (the store has bins), location by location, or as a whole. */
enum class StopMode { BIN, LOCATION, WHOLE }

/** One stop on the walk - a bin in a location, a location, or (neither) the whole journal. Upper case. */
data class CountStop(val locationId: String?, val binId: String?) {
    val label: String get() = binId ?: locationId ?: "the shelf"

    /** The lines this stop counts - those in its location (all of them, with no location). */
    fun covers(line: CountLine): Boolean =
        !line.verifyOnly && (locationId == null || line.locationId.equals(locationId, ignoreCase = true))

    companion object {
        fun of(locationId: String?, binId: String?) = CountStop(
            locationId?.trim()?.uppercase()?.takeIf { it.isNotBlank() },
            binId?.trim()?.uppercase()?.takeIf { it.isNotBlank() },
        )
    }
}

/**
 * The walking order: the journal's locations in order - location ids are laid out aisle, rack,
 * shelf, so sorted is the walk - and, with bins, each location's bins in order within it. A bin
 * with no location on the bin list belongs to any location.
 */
internal fun walkingOrder(lineLocations: Collection<String?>, bins: List<BinRef>, mode: StopMode): List<CountStop> {
    val locations = lineLocations.mapNotNull { it?.trim()?.uppercase()?.takeIf { l -> l.isNotBlank() } }.distinct().sorted()
    return when (mode) {
        StopMode.WHOLE -> listOf(CountStop(null, null))
        StopMode.LOCATION -> locations.map { CountStop(it, null) }.ifEmpty { listOf(CountStop(null, null)) }
        StopMode.BIN -> {
            val byBin = bins.distinctBy { it.binId.uppercase() }.sortedBy { it.binId.uppercase() }
            if (locations.isEmpty()) {
                byBin.map { CountStop.of(null, it.binId) }
            } else {
                locations.flatMap { loc ->
                    byBin.filter { it.locationId.isNullOrBlank() || it.locationId.equals(loc, ignoreCase = true) }
                        .map { CountStop.of(loc, it.binId) }
                }
            }
        }
    }
}

/**
 * A stop is settled when every line it covers has a count there: a row for that bin, or - with no
 * bins - a count. A stop with no lines (a bin of a location the journal has none in) is settled.
 */
internal fun isSettled(stop: CountStop, lines: List<CountLine>, lineBins: List<CountLineBinEntity>): Boolean {
    val at = lines.filter { stop.covers(it) }
    val bin = stop.binId ?: return at.all { it.isCounted }
    val inBin = lineBins.filter { it.binId.equals(bin, ignoreCase = true) }.map { it.journalLineId }.toSet()
    return at.all { it.journalLineId in inBin }
}

data class CycleFlowInfo(
    val loaded: Boolean = false,
    val mode: StopMode = StopMode.WHOLE,
    val stops: List<CountStop> = emptyList(),
    val settled: Set<CountStop> = emptySet(),
    /** Where the worker is counting now; null while going to the next stop. */
    val current: CountStop? = null,
    /** The line the last scan counted, and its count at this stop. */
    val lastCounted: CountLine? = null,
    val lastCountedQuantity: String? = null,
    /** Lines with a count, of all the journal's lines. */
    val counted: Int = 0,
    val total: Int = 0,
) {
    val allSettled: Boolean get() = stops.isNotEmpty() && stops.all { it in settled }

    /** The next unsettled stop after [from] in walking order, wrapping round to the first one skipped. */
    fun nextAfter(from: CountStop?): CountStop? {
        val open = stops.filter { it !in settled && it != from }
        if (from == null) return open.firstOrNull()
        val start = stops.indexOf(from)
        return open.firstOrNull { stops.indexOf(it) > start } ?: open.firstOrNull()
    }

    /** "2 of 5" - the stop's place in the walk. */
    fun positionOf(stop: CountStop): String? = stops.indexOf(stop).takeIf { it >= 0 }?.let { "${it + 1} of ${stops.size}" }
}

internal fun cycleFlowInfo(
    lines: List<CountLine>,
    lineBins: List<CountLineBinEntity>,
    bins: List<BinRef>,
    mode: StopMode,
    location: String?,
    bin: String?,
    lastCounted: CountLine? = null,
    lastCountedQuantity: String? = null,
): CycleFlowInfo {
    val stops = walkingOrder(lines.map { it.locationId }, bins, mode)
    val current = when (mode) {
        StopMode.BIN -> bin?.let { CountStop.of(location, it) }
        StopMode.LOCATION -> location?.let { CountStop.of(it, null) }
        StopMode.WHOLE -> CountStop(null, null)
    }
    return CycleFlowInfo(
        loaded = true,
        mode = mode,
        stops = stops,
        settled = stops.filter { isSettled(it, lines, lineBins) }.toSet(),
        current = current,
        lastCounted = lastCounted?.takeIf { current?.covers(it) == true },
        lastCountedQuantity = lastCountedQuantity,
        counted = lines.count { it.isCounted },
        total = lines.size,
    )
}

// -- the band and the card --------------------------------------------------------------------

/** The status band for the cycle-count flow. */
internal fun cycleCountBand(s: CountingScanState, f: CycleFlowInfo): CountBand {
    s.message?.let { m ->
        val kind = when {
            m.warn -> StatusKind.Warn
            m.isError -> StatusKind.Bad
            m.undoable -> StatusKind.Ok
            else -> StatusKind.Info
        }
        return CountBand(kind, m.title ?: m.text, m.line, undoable = m.undoable, key = m)
    }
    if (s.locked) return CountBand(StatusKind.Info, "Submitted", "Reopen it on the list to change")
    if (f.allSettled) return CountBand(StatusKind.Ok, "All counted", "Hold to end count below")
    val here = f.current
    return when {
        here == null || here in f.settled && f.mode != StopMode.WHOLE ->
            f.nextAfter(here)?.let { CountBand(StatusKind.Info, "Go to ${it.label}", "Then scan its label") }
                ?: CountBand(StatusKind.Info, "Scan a bin label")
        f.mode == StopMode.WHOLE -> CountBand(StatusKind.Info, "Scan items", "One scan counts one")
        else -> CountBand(StatusKind.Info, "Counting ${here.label}", "Scan each item · scan ${here.label} again when it's done")
    }
}

/** The next-card for the cycle-count flow: where to go, or what was last counted here. Blind: no stock figure. */
internal fun cycleCountCard(s: CountingScanState, f: CycleFlowInfo): CountCard {
    if (s.locked) return CountCard(label = "Submitted", code = s.journalDescription)
    val here = f.current?.takeIf { f.mode == StopMode.WHOLE || it !in f.settled }
    if (here == null) {
        val next = f.nextAfter(f.current)
            ?: return CountCard(label = "All counted · ${f.counted} of ${f.total} lines", code = s.journalDescription, description = "Hold to end count below")
        return CountCard(
            label = "Go to · ${f.positionOf(next) ?: "next"}",
            code = next.label,
            description = if (next.binId != null) "Scan the bin label - then each item" else "Scan the location label - then each item",
            location = next.locationId.takeIf { next.binId != null },
        )
    }
    val label = listOfNotNull("Counting", f.positionOf(here)?.takeIf { f.mode != StopMode.WHOLE }).joinToString(" · ")
    val line = f.lastCounted ?: return CountCard(
        label = label,
        code = here.label,
        description = if (f.mode == StopMode.WHOLE) "Scan each item - one scan counts one" else "Scan each item - or ${here.label} again if it's empty",
        location = here.locationId.takeIf { here.binId != null },
        locationLabel = "Location",
    )
    return CountCard(
        label = label,
        code = line.itemId,
        description = listOfNotNull(
            line.itemName.takeIf { it.isNotBlank() && !it.equals(line.itemId, ignoreCase = true) },
            line.variant.label.takeIf { it.isNotBlank() },
            line.batchId?.takeIf { it.isNotBlank() }?.let { "Batch $it" },
            line.serialId?.takeIf { it.isNotBlank() }?.let { "Serial $it" },
        ).joinToString(" · ").ifBlank { null },
        location = here.label.takeIf { f.mode != StopMode.WHOLE },
        locationLabel = if (here.binId != null) "Bin" else "Location",
        quantity = f.lastCountedQuantity,
        unit = "counted",
    )
}
