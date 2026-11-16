package com.d365fo.mobileops.presentation.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.d365fo.mobileops.data.repository.ManualSync
import com.d365fo.mobileops.data.repository.OrderCreationRepository
import com.d365fo.mobileops.data.repository.SkuSyncRepository
import com.d365fo.mobileops.data.sync.MasterSyncState
import com.d365fo.mobileops.data.sync.OfflinePrefetcher
import com.d365fo.mobileops.data.sync.SKU_CHANGES_EVERY_HOURS
import com.d365fo.mobileops.data.sync.SyncScheduler
import com.d365fo.mobileops.data.sync.WorkBackup
import com.d365fo.mobileops.presentation.common.components.LineIcons
import com.d365fo.mobileops.presentation.common.focus.FocusButton
import com.d365fo.mobileops.presentation.common.focus.FocusCaption
import com.d365fo.mobileops.presentation.common.theme.FocusColors
import com.d365fo.mobileops.presentation.common.theme.FocusType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

/** What the Sync screen shows - read when it opens and after each sync. */
data class SyncStatus(
    /** The last finished item sync of any kind - the ring counts from it. */
    val lastAt: Long? = null,
    val lastFull: Boolean = false,
    val itemCount: Int? = null,
    /** "21 Nov" - when the next full sync is due by. */
    val fullBy: String? = null,
    val vendorCount: Int? = null,
    val vendorsAt: Long? = null,
    val loaded: Boolean = false,
)

/** Settings > Sync (user, 16 Nov 2026): for each kind of data, when it last synced and when next. */
@HiltViewModel
class SyncStatusViewModel @Inject constructor(
    private val sku: SkuSyncRepository,
    private val orders: OrderCreationRepository,
    private val scheduler: SyncScheduler,
    prefetcher: OfflinePrefetcher,
    backup: WorkBackup,
) : ViewModel() {
    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()
    val readiness = prefetcher.readiness
    val lastBackupAt = backup.lastBackupAt
    val mastersSync: StateFlow<MasterSyncState> =
        scheduler.mastersSync.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MasterSyncState.Idle)

    fun refresh() {
        viewModelScope.launch {
            val last = sku.lastSummary()
            val vendorCount = runCatching { orders.vendorCount() }.getOrNull()
            val vendorsAt = runCatching { orders.vendorsSavedAt() }.getOrNull()
            _status.update {
                SyncStatus(
                    lastAt = sku.lastSuccessAt() ?: last?.finishedAt,
                    lastFull = last?.full == true,
                    itemCount = last?.itemCount,
                    fullBy = sku.nextFullBy()?.let(::dayText),
                    vendorCount = vendorCount,
                    vendorsAt = vendorsAt,
                    loaded = true,
                )
            }
        }
    }

    fun syncNow(full: Boolean) = scheduler.syncMastersNow(if (full) ManualSync.FULL else ManualSync.CHANGES)

    companion object {
        /** "13:05" today, "15 Nov 13:05" another day. */
        fun whenText(ms: Long): String {
            val t = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
            return if (t.toLocalDate() == LocalDate.now()) t.format(DateTimeFormatter.ofPattern("HH:mm"))
            else t.format(DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ENGLISH))
        }

        fun dayText(ms: Long): String =
            Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH))

        /** "1h 06m", "12m" - the time left on the ring. */
        fun leftText(ms: Long): String {
            val minutes = ((ms + 59_999) / 60_000).coerceAtLeast(1)
            return if (minutes >= 60) "${minutes / 60}h %02dm".format(minutes % 60) else "${minutes}m"
        }
    }
}

private const val PERIOD_MS = SKU_CHANGES_EVERY_HOURS * 3_600_000L

/**
 * Settings > Sync, approved mock (user, 16 Nov 2026): a ring that fills over the 2 hours to the
 * next changes sync with the time left inside, beside it what is waiting, then one short row per
 * kind of data under On this device / Sent from this device / Read live from D365FO.
 */
@Composable
internal fun SyncStatusSection(viewModel: SyncStatusViewModel = hiltViewModel()) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    val readiness by viewModel.readiness.collectAsStateWithLifecycle()
    val backupAt by viewModel.lastBackupAt.collectAsStateWithLifecycle()
    val masters by viewModel.mastersSync.collectAsStateWithLifecycle()
    val syncStatus = com.d365fo.mobileops.presentation.common.components.LocalSyncStatus.current
    val online = syncStatus?.fullyOnline != false
    val running = masters is MasterSyncState.Running
    val waiting = syncStatus?.pendingCount ?: 0
    val refused = syncStatus?.failedCount ?: 0
    LaunchedEffect(running) { viewModel.refresh() }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    val testBuild = com.d365fo.mobileops.BuildConfig.DEBUG

    Column(Modifier.fillMaxWidth()) {
        // Header card: ring + summary.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .background(FocusColors.surface, RoundedCornerShape(12.dp))
                .border(1.dp, FocusColors.divider, RoundedCornerShape(12.dp))
                .padding(14.dp),
        ) {
            val lastAt = status.lastAt
            when {
                running -> RingBox {
                    CircularProgressIndicator(
                        color = FocusColors.navy,
                        trackColor = FocusColors.navyDim,
                        strokeWidth = 8.dp,
                        strokeCap = StrokeCap.Round,
                        modifier = Modifier.size(92.dp),
                    )
                    RingText("Syncing", null, FocusColors.navy)
                }
                !online -> RingBox {
                    Ring(0f)
                    RingText("Offline", null, FocusColors.muted)
                }
                testBuild || lastAt == null -> RingBox {
                    Ring(0f)
                    RingText(if (lastAt == null) "Not yet" else "On ask", null, FocusColors.muted)
                }
                else -> {
                    val due = lastAt + PERIOD_MS
                    val fraction = ((now - lastAt).toFloat() / PERIOD_MS).coerceIn(0f, 1f)
                    RingBox {
                        Ring(fraction)
                        if (due > now) RingText(SyncStatusViewModel.leftText(due - now), "to next", FocusColors.ink)
                        else RingText("Due", "when online", FocusColors.ink)
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                when {
                    running -> {
                        Text("Syncing item data…", style = FocusType.rowTitle, color = FocusColors.navy)
                        Detail("It carries on if you leave this screen.")
                    }
                    !online -> {
                        Text("Waiting for connection", style = FocusType.rowTitle, color = FocusColors.ink)
                        Detail("Sync needs a connection.")
                    }
                    else -> {
                        val allGood = refused == 0 && waiting == 0
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (allGood) Icon(LineIcons.Sync, contentDescription = null, tint = FocusColors.okIcon, modifier = Modifier.size(18.dp))
                            Text(if (allGood) "All synced" else "Synced", style = FocusType.rowTitle, color = FocusColors.ink)
                        }
                        when {
                            refused > 0 -> Detail("$refused refused - see Activity", FocusColors.badText)
                            waiting > 0 -> Detail("except $waiting ${if (waiting == 1) "posting" else "postings"} waiting", FocusColors.warnText)
                        }
                    }
                }
                Detail(
                    listOfNotNull(
                        status.lastAt?.let { "Last " + (if (status.lastFull) "full " else "changes ") + SyncStatusViewModel.whenText(it) }
                            ?: if (status.loaded) "Not synced yet" else null,
                        if (testBuild) "Test build: only when asked" else status.fullBy?.let { "Full by $it, Wi-Fi + charging" },
                    ).joinToString("\n"),
                    top = 6.dp,
                )
            }
        }

        if (masters is MasterSyncState.Failed) {
            com.d365fo.mobileops.presentation.common.focus.ResultBand(
                com.d365fo.mobileops.presentation.common.focus.BandState(
                    com.d365fo.mobileops.presentation.common.focus.BandKind.Bad, "Item data not synced", (masters as MasterSyncState.Failed).reason,
                ),
            )
        }

        SyncCaption("On this device")
        SyncRow(
            LineIcons.Serial, "Items and barcodes",
            if (running) "syncing now" else status.itemCount?.let { "%,d".format(it) + if (status.lastFull) " · full" else " · changes" } ?: "Not synced yet",
            if (running) null else status.lastAt?.let(SyncStatusViewModel::whenText),
        )
        SyncRow(
            LineIcons.Ship, "Vendors",
            status.vendorCount?.takeIf { status.vendorsAt != null }?.let { "%,d".format(it) + " · with items" } ?: "Not synced yet",
            status.vendorsAt?.let(SyncStatusViewModel::whenText),
        )
        SyncRow(
            LineIcons.ListLines, "Orders and counts",
            if (readiness.savedAt != null) "${readiness.purchaseOrders} POs · ${readiness.transferOrders} TOs · ${readiness.counts} counts" else "Not saved yet",
            readiness.savedAt?.let(SyncStatusViewModel::whenText),
        )

        SyncCaption("Sent from this device")
        SyncRow(
            LineIcons.Upload, "Postings",
            if (refused > 0) "$refused refused - see Activity" else "as soon as online",
            null,
            pill = waiting.takeIf { it > 0 }?.let { "$it waiting" },
        )
        SyncRow(
            LineIcons.Folder, "Copy of unsent work", "seconds after each change",
            backupAt?.let(SyncStatusViewModel::whenText) ?: "Not yet",
        )

        SyncCaption("Read live from D365FO")
        SyncRow(LineIcons.ActivityThin, "Stock, prices, order details", "each time a screen opens", null)

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
        ) {
            FocusButton("Sync changes", onClick = { viewModel.syncNow(full = false) }, enabled = online && !running, modifier = Modifier.weight(1f))
            FocusButton("Full sync", onClick = { viewModel.syncNow(full = true) }, primary = true, enabled = online && !running, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun RingBox(content: @Composable () -> Unit) {
    Box(Modifier.size(92.dp), contentAlignment = Alignment.Center) { content() }
}

/** The 2-hour ring: track, and the part of the period gone by. */
@Composable
private fun Ring(fraction: Float) {
    val track = FocusColors.navyDim
    val fill = FocusColors.navy
    Canvas(Modifier.size(92.dp)) {
        val w = 8.dp.toPx()
        val inset = w / 2
        val arcSize = Size(size.width - w, size.height - w)
        drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(w))
        if (fraction > 0f) drawArc(fill, -90f, 360f * fraction, false, Offset(inset, inset), arcSize, style = Stroke(w, cap = StrokeCap.Round))
    }
}

@Composable
private fun RingText(main: String, sub: String?, color: androidx.compose.ui.graphics.Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(main, style = FocusType.rowTitle.copy(fontSize = 15.sp), color = color, textAlign = TextAlign.Center)
        if (sub != null) Text(sub, style = FocusType.rowDetail.copy(fontSize = 11.sp), color = FocusColors.muted, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Detail(text: String, color: androidx.compose.ui.graphics.Color = FocusColors.muted, top: androidx.compose.ui.unit.Dp = 2.dp) {
    if (text.isNotBlank()) Text(text, style = FocusType.rowDetail, color = color, modifier = Modifier.padding(top = top))
}

@Composable
private fun SyncCaption(text: String) {
    FocusCaption(text, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 6.dp))
    HorizontalDivider(color = FocusColors.divider)
}

@Composable
private fun SyncRow(icon: ImageVector, title: String, detail: String, time: String?, pill: String? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().background(FocusColors.surface).padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = FocusColors.navy, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = FocusType.rowTitle, color = FocusColors.ink)
            Text(detail, style = FocusType.rowDetail, color = FocusColors.muted)
        }
        if (pill != null) {
            Text(
                pill,
                style = FocusType.pill,
                color = FocusColors.warnText,
                modifier = Modifier.background(FocusColors.warnBg, RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
            )
        } else if (time != null) {
            Text(time, style = FocusType.rowDetail, color = FocusColors.muted)
        }
    }
    HorizontalDivider(color = FocusColors.divider)
}
