package com.d365fo.mobileops.presentation.modules.cyclecounting

import com.d365fo.mobileops.presentation.common.components.StatusKind
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicLong

/**
 * What the last scan or action did on a counting flow (redesign phase 6) - the status band's
 * content. [id] changes with every new notice, so the band's Undo window restarts.
 */
data class CountNotice(
    val kind: StatusKind,
    val title: String,
    val line: String? = null,
    /** The band offers Undo: the notice is a +1 (or a settled bin) that can be taken back. */
    val undoable: Boolean = false,
    val id: Long = ids.incrementAndGet(),
) {
    private companion object {
        val ids = AtomicLong(0)
    }
}

/** The status band for a counting screen - pure, so it is unit-tested. */
data class CountBand(
    val kind: StatusKind,
    val title: String,
    val line: String? = null,
    val undoable: Boolean = false,
    val key: Any? = null,
)

/** The next-card for a counting screen - pure, so it is unit-tested. */
data class CountCard(
    val label: String,
    val code: String,
    val description: String? = null,
    val location: String? = null,
    val locationLabel: String = "Location",
    val quantity: String? = null,
    val unit: String? = null,
)

internal fun CountNotice.toBand(): CountBand = CountBand(kind, title, line, undoable, id)

/** "3", "2.5" - a count as the worker reads it. */
internal fun countText(value: BigDecimal): String = value.stripTrailingZeros().toPlainString()

internal fun countText(value: Double): String = countText(BigDecimal.valueOf(value))

/** "1 line", "3 lines". */
internal fun nLines(n: Int): String = if (n == 1) "1 line" else "$n lines"
