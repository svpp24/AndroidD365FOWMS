package com.d365fo.mobileops.presentation.modules.countcontrol

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.d365fo.mobileops.domain.countcontrol.CountedTwiceBin
import com.d365fo.mobileops.domain.countcontrol.StoreJournalSummary
import com.d365fo.mobileops.presentation.common.components.HoldButton
import com.d365fo.mobileops.presentation.common.components.Lead
import com.d365fo.mobileops.presentation.common.components.LineRow
import com.d365fo.mobileops.presentation.common.components.ListCard
import com.d365fo.mobileops.presentation.common.components.RowChevron
import com.d365fo.mobileops.presentation.common.components.RowDivider
import com.d365fo.mobileops.presentation.common.components.StatusTag
import com.d365fo.mobileops.presentation.common.focus.BandKind
import com.d365fo.mobileops.presentation.common.focus.BandState
import com.d365fo.mobileops.presentation.common.focus.FocusChip
import com.d365fo.mobileops.presentation.common.focus.FocusScreen
import com.d365fo.mobileops.presentation.common.theme.FocusColors
import com.d365fo.mobileops.presentation.common.theme.FocusType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/*
 * Count control for the store supervisor (spec section 4; mocks app-count-control.png and
 * app-count-control-more.png, 24 Nov 2026), built from the Harbour components. Every call is
 * checked by D365FO: a cycle count supervisor, for their default store.
 */

private val TIME = DateTimeFormatter.ofPattern("HH:mm")

/** An ISO UTC time from D365FO as the device's local HH:mm; blank stays blank. */
internal fun localTime(iso: String): String =
    runCatching { TIME.format(Instant.parse(iso.trim()).atZone(ZoneId.systemDefault())) }.getOrDefault("")

/** A quantity without a needless ".0". */
internal fun qty(value: Double): String =
    if (value == Math.floor(value) && !value.isInfinite()) value.toLong().toString() else "%.2f".format(value).trimEnd('0').trimEnd('.')

private fun signed(value: Double): String = (if (value > 0) "+" else "") + qty(value)

/** Outline of a card, so white cards stand apart from the near-white page (user, 25 Nov 2026). */
private val CardEdge = Color(0xFFCBD5E1)

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .background(FocusColors.surface, RoundedCornerShape(14.dp))
            .border(1.dp, CardEdge, RoundedCornerShape(14.dp))
            .padding(14.dp),
        content = content,
    )
}

@Composable
private fun Sub(text: String) = Text(text, style = FocusType.rowDetail, color = FocusColors.muted)

@Composable
private fun AmountRow(label: String, value: String, muted: Boolean = false, valueColor: Color = FocusColors.ink) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = FocusType.rowDetail, color = if (muted) FocusColors.muted else FocusColors.ink, modifier = Modifier.weight(1f))
        Text(value, style = FocusType.rowWord, color = if (muted) FocusColors.muted else valueColor)
    }
}

private fun bandFor(loading: Boolean, error: String?): BandState? = when {
    error != null -> BandState(BandKind.Bad, "Not loaded", error)
    loading -> BandState(BandKind.Info, "Loading", "Asking D365FO")
    else -> null
}

private fun journalTag(journal: StoreJournalSummary): Pair<String, Lead> = when {
    journal.onHold -> "On hold" to Lead.Warn
    journal.countedTwice > 0 -> "Waiting for you" to Lead.Bad
    journal.status == "ReadyToPost" -> "Posts tonight" to Lead.Done
    journal.status == "FailedToCreate" -> "Failed to create" to Lead.Bad
    else -> journal.statusLabel.ifBlank { journal.status } to Lead.Idle
}

// ---------------------------------------------------------------------------------------------
// 1 · Count control

@Composable
fun CountControlScreen(
    onBack: () -> Unit,
    onCountedTwice: () -> Unit,
    onCounters: () -> Unit,
    onBlindCounts: () -> Unit,
    onVarianceLines: () -> Unit,
    viewModel: CountControlViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.load() }
    val overview = state.overview
    val journal = overview?.journal
    FocusScreen(
        title = "Count control",
        onBack = onBack,
        band = state.outcome?.let { (ok, text) -> BandState(if (ok) BandKind.Ok else BandKind.Bad, if (ok) "Done" else "Not done", text) }
            ?: bandFor(state.loading && overview == null, state.error),
        topBarTrailing = {
            IconButton(onClick = viewModel::load) { Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = FocusColors.ink) }
        },
        actions = if (journal == null) null else {
            {
                // As the mock: Hold/Release outlined (a tap - it can be undone), Post now filled and
                // still held to confirm (it cannot); no hand icon, so each label stays on one line.
                com.d365fo.mobileops.presentation.common.focus.FocusButton(
                    text = if (journal.onHold) "Release journal" else "Hold journal",
                    onClick = viewModel::toggleHold,
                    enabled = !state.busy,
                    compact = true,
                    modifier = Modifier
                        .weight(1f)
                        .border(2.dp, FocusColors.navy, com.d365fo.mobileops.presentation.common.theme.FocusShapes.button),
                )
                com.d365fo.mobileops.presentation.common.focus.HoldToConfirmButton(
                    label = "Post now",
                    onConfirm = viewModel::post,
                    enabled = !state.busy && !journal.onHold,
                    compact = true,
                    modifier = Modifier.weight(1f),
                )
            }
        },
    ) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
            if (overview != null) {
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Tile(overview.binsCountedTwice, "Bins counted twice", FocusColors.badIcon, Modifier.weight(1f))
                    Tile(overview.countersCounting, if (overview.countersCounting == 1) "Counter still counting" else "Counters still counting",
                        FocusColors.warnIcon, Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Tile(overview.blindCountsToday, "Blind counts today", FocusColors.okIcon, Modifier.weight(1f))
                    Tile(overview.linesWithVariance, "Lines with variance", FocusColors.navy, Modifier.weight(1f))
                }
                if (journal == null) {
                    Card { Sub("No open store journal. The next blind count starts one.") }
                } else {
                    Card {
                        Text(
                            "Store journal ${journal.journalId.ifBlank { "(not created yet)" }}",
                            style = FocusType.rowTitle,
                            color = FocusColors.ink,
                        )
                        val (tag, kind) = journalTag(journal)
                        Row(Modifier.padding(vertical = 6.dp)) { StatusTag(tag, kind) }
                        Sub("${journal.blindCounts} blind count${if (journal.blindCounts == 1) "" else "s"} · ${journal.lines} line${if (journal.lines == 1) "" else "s"}")
                        Sub(
                            if (journal.onHold) {
                                "Held by ${journal.heldBy.ifBlank { "a supervisor" }} · skipped by the nightly batch"
                            } else {
                                "Posts in the nightly batch"
                            },
                        )
                        if (journal.countedTwice > 0) {
                            Text(
                                "Won't post until ${journal.countedTwice} bin${if (journal.countedTwice == 1) " is" else "s are"} decided.",
                                style = FocusType.rowDetail,
                                color = FocusColors.warnText,
                                modifier = Modifier
                                    .padding(top = 10.dp)
                                    .fillMaxWidth()
                                    .background(FocusColors.warnBg, RoundedCornerShape(10.dp))
                                    .padding(10.dp),
                            )
                        }
                    }
                }
                ListCard(Modifier.padding(top = 12.dp).border(1.dp, CardEdge, RoundedCornerShape(14.dp))) {
                    LinkRow("Counted twice", overview.binsCountedTwice.takeIf { it > 0 }, onCountedTwice)
                    RowDivider()
                    LinkRow("Counters", null, onCounters)
                    RowDivider()
                    LinkRow("Blind counts", null, onBlindCounts)
                    RowDivider()
                    LinkRow("Variance lines", null, onVarianceLines)
                }
                Spacer(Modifier.padding(bottom = 16.dp))
            }
        }
    }
}

@Composable
internal fun Tile(number: Int, label: String, accent: Color, modifier: Modifier) {
    // Coloured top edge that follows the rounded corners and thins down the sides (as in the mock).
    Column(
        modifier
            .padding(top = 10.dp)
            .border(1.dp, CardEdge, RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .background(FocusColors.surface)
            // Accent only behind the top, so the bottom corners have no coloured hairline.
            .drawBehind { drawRect(accent, size = androidx.compose.ui.geometry.Size(size.width, 18.dp.toPx())) },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(top = 4.dp)
                .background(FocusColors.surface, RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp, bottomStart = 0.dp, bottomEnd = 0.dp))
                .padding(12.dp),
        ) {
            Text(number.toString(), fontSize = 28.sp, fontWeight = FontWeight.Medium, color = FocusColors.ink)
            Text(label, style = FocusType.rowDetail, color = FocusColors.muted)
        }
    }
}

@Composable
private fun LinkRow(title: String, badge: Int?, onClick: () -> Unit) {
    LineRow(
        title = title,
        status = badge?.let { { StatusTag(it.toString(), Lead.Bad) } },
        onClick = onClick,
        trailing = { RowChevron() },
    )
}

// ---------------------------------------------------------------------------------------------
// 2 · Counted twice

@Composable
fun CountedTwiceScreen(
    onBack: () -> Unit,
    onBin: (lineId: String) -> Unit,
    viewModel: CountedTwiceViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.load() }
    val result = state.result
    FocusScreen(
        title = "Counted twice",
        onBack = onBack,
        band = bandFor(state.loading && result == null, state.error)
            ?: if (result != null && result.bins.isEmpty()) {
                BandState(BandKind.Info, if (state.decided) "None decided yet" else "Nothing to decide", "No bin is counted twice in this store journal")
            } else null,
        topBarTrailing = {
            IconButton(onClick = viewModel::load) { Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = FocusColors.ink) }
        },
    ) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FocusChip("To decide ${result?.toDecide ?: 0}", !state.decided, { viewModel.show(false) })
                FocusChip("Decided ${result?.decided ?: 0}", state.decided, { viewModel.show(true) })
            }
            result?.bins.orEmpty().forEach { bin -> BinCard(bin) { onBin(bin.lineId) } }
            Spacer(Modifier.padding(bottom = 16.dp))
        }
    }
}

private fun decisionTag(decision: String): Pair<String, Lead> = when (decision) {
    "KeepThis" -> "Kept one count" to Lead.Done
    "Sum" -> "Added together" to Lead.Done
    "Reject" -> "Rejected" to Lead.Warn
    else -> "To decide" to Lead.Bad
}

@Composable
private fun BinCard(bin: CountedTwiceBin, onClick: () -> Unit) {
    Card(Modifier.clickable(role = Role.Button, onClick = onClick)) {
        // Location · bin first, as the mock; a line without a location starts with the item.
        if (bin.title.isNotBlank()) {
            Text(bin.title, style = FocusType.rowTitle, color = FocusColors.ink)
            Sub("${bin.itemId} · ${bin.itemName}")
        } else {
            Text("${bin.itemId} · ${bin.itemName}", style = FocusType.rowTitle, color = FocusColors.ink)
        }
        val (tag, kind) = decisionTag(bin.decision)
        Row(Modifier.padding(vertical = 6.dp)) { StatusTag(tag, kind) }
        bin.counts.forEach { c ->
            AmountRow(
                listOf(c.counterName, c.masterId, localTime(c.countedAt)).filter { it.isNotBlank() }.joinToString(" · "),
                qty(c.qty),
                valueColor = if (bin.decision != "None" && !c.included) FocusColors.caption else FocusColors.ink,
            )
        }
        AmountRow("On-hand in D365", qty(bin.onHand), muted = true)
    }
}

// ---------------------------------------------------------------------------------------------
// 3 · Decide a bin

@Composable
fun DecideBinScreen(
    onBack: () -> Unit,
    onDecided: () -> Unit,
    viewModel: DecideBinViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.load() }
    LaunchedEffect(state.done) { if (state.done) onDecided() }
    val bin = state.bin
    FocusScreen(
        title = bin?.title ?: "Decide a bin",
        onBack = onBack,
        band = bandFor(state.loading && bin == null, state.error),
        actions = if (bin == null) null else {
            {
                HoldButton(
                    label = "Hold to confirm",
                    onConfirm = viewModel::confirm,
                    enabled = state.choice != null && !state.busy,
                    modifier = Modifier.weight(1f),
                )
            }
        },
    ) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
            if (bin != null) {
                Text(
                    "${bin.itemId} · ${bin.itemName} · On-hand ${qty(bin.onHand)}",
                    style = FocusType.rowDetail,
                    color = FocusColors.muted,
                    modifier = Modifier.padding(top = 12.dp),
                )
                bin.counts.forEach { c ->
                    Option(
                        title = "Keep ${c.counterName}'s count",
                        detail = listOf(c.masterId, localTime(c.countedAt)).filter { it.isNotBlank() }.joinToString(" · "),
                        amount = qty(c.qty),
                        selected = state.choice == DecideBinViewModel.Choice.Keep(c.lineId),
                        onClick = { viewModel.choose(DecideBinViewModel.Choice.Keep(c.lineId)) },
                    )
                }
                Option(
                    title = "Add together",
                    detail = "Different stock in the same bin",
                    amount = qty(bin.total),
                    selected = state.choice == DecideBinViewModel.Choice.Sum,
                    onClick = { viewModel.choose(DecideBinViewModel.Choice.Sum) },
                )
                Option(
                    title = if (bin.counts.size > 2) "Reject all" else "Reject both",
                    detail = "Bin must be counted again",
                    amount = "-",
                    selected = state.choice == DecideBinViewModel.Choice.Reject,
                    onClick = { viewModel.choose(DecideBinViewModel.Choice.Reject) },
                )
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun Option(title: String, detail: String, amount: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .background(if (selected) FocusColors.navyDim else FocusColors.surface, RoundedCornerShape(14.dp))
            .border(BorderStroke(2.dp, if (selected) FocusColors.navy else FocusColors.divider), RoundedCornerShape(14.dp))
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.weight(1f).padding(start = 6.dp)) {
            Text(title, style = FocusType.rowWord, color = FocusColors.ink)
            Text(detail, style = FocusType.rowDetail, color = FocusColors.muted)
        }
        Text(amount, fontSize = 20.sp, fontWeight = FontWeight.Medium, color = FocusColors.navy)
    }
}

// ---------------------------------------------------------------------------------------------
// 4 · Counters, 5 · Blind counts, 6 · Variance lines

@Composable
private fun <T> ListScreen(
    title: String,
    state: ListState<T>,
    empty: String,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    header: (@Composable ColumnScope.() -> Unit)? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    row: @Composable (T) -> Unit,
) {
    FocusScreen(
        title = title,
        onBack = onBack,
        band = bandFor(state.loading && state.rows.isEmpty(), state.error)
            ?: if (!state.loading && state.rows.isEmpty()) BandState(BandKind.Info, "Nothing here", empty) else null,
        topBarTrailing = {
            IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = FocusColors.ink) }
        },
    ) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
            header?.invoke(this)
            state.rows.forEach { row(it) }
            footer?.invoke(this)
            Spacer(Modifier.padding(bottom = 16.dp))
        }
    }
}

@Composable
fun CountersScreen(onBack: () -> Unit, viewModel: CountersViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.load() }
    ListScreen(
        title = "Counters",
        state = state,
        empty = "Nobody has counted in this store journal yet.",
        onBack = onBack,
        onRefresh = viewModel::load,
    ) { c ->
        Card {
            Text(c.name, style = FocusType.rowTitle, color = FocusColors.ink)
            Sub(listOf(c.masterId, c.deviceId.takeIf { it.isNotBlank() }?.let { "Handheld $it" }.orEmpty()).filter { it.isNotBlank() }.joinToString(" · "))
            Sub(
                buildList {
                    localTime(c.startedAt).takeIf { it.isNotBlank() }?.let { add("Started $it") }
                    add("${c.lines} line${if (c.lines == 1) "" else "s"}")
                    if (!c.ended) localTime(c.lastScanAt).takeIf { it.isNotBlank() }?.let { add("last scan $it") }
                }.joinToString(" · "),
            )
            Row(Modifier.padding(top = 6.dp)) { StatusTag(if (c.ended) "Ended" else "Still counting", if (c.ended) Lead.Done else Lead.Warn) }
        }
    }
}

@Composable
fun BlindCountsScreen(onBack: () -> Unit, viewModel: BlindCountsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.load() }
    ListScreen(
        title = "Blind counts",
        state = state,
        empty = "No blind count in this store journal yet.",
        onBack = onBack,
        onRefresh = viewModel::load,
    ) { b ->
        Card {
            Text(b.masterId, style = FocusType.rowTitle, color = FocusColors.ink)
            Sub(listOf(b.description, localTime(b.startedAt).takeIf { it.isNotBlank() }?.let { "started $it" }.orEmpty()).filter { it.isNotBlank() }.joinToString(" · "))
            val (tag, kind) = when {
                b.inJournal -> "In journal" to Lead.Done
                b.message.startsWith("Transfer failed") -> "Failed to create" to Lead.Bad
                else -> "Counting" to Lead.Warn
            }
            Row(Modifier.padding(vertical = 6.dp)) { StatusTag(tag, kind) }
            Sub("${b.counters} counter${if (b.counters == 1) "" else "s"} · ${b.lines} line${if (b.lines == 1) "" else "s"}")
            if (tag == "Failed to create") Sub(b.message)
        }
    }
}

@Composable
fun VarianceLinesScreen(onBack: () -> Unit, viewModel: VarianceLinesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val journalId by viewModel.journalId.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.load() }
    ListScreen(
        title = "Variance lines",
        state = state,
        empty = "No line in the store journal differs from on-hand.",
        onBack = onBack,
        onRefresh = viewModel::load,
        header = {
            if (state.rows.isNotEmpty()) {
                Text(listOf(journalId.takeIf { it.isNotBlank() }?.let { "Store journal $it" }.orEmpty(),
                    "${state.rows.size} line${if (state.rows.size == 1) "" else "s"}").filter { it.isNotBlank() }.joinToString(" · "), style = FocusType.rowDetail, color = FocusColors.muted, modifier = Modifier.padding(top = 12.dp))
            }
        },
    ) { v ->
        Card {
            Text("${v.itemId} · ${v.itemName}", style = FocusType.rowTitle, color = FocusColors.ink)
            listOf(v.locationId, v.batchId.takeIf { it.isNotBlank() }?.let { "Batch $it" }.orEmpty(),
                v.serialId.takeIf { it.isNotBlank() }?.let { "Serial $it" }.orEmpty()).filter { it.isNotBlank() }.joinToString(" · ")
                .takeIf { it.isNotBlank() }?.let { Sub(it) }
            AmountRow("Counted", qty(v.counted))
            AmountRow("On-hand in D365", qty(v.onHand), muted = true)
            AmountRow("Variance", signed(v.variance), valueColor = if (v.variance < 0) FocusColors.badIcon else FocusColors.okIcon)
        }
    }
}
