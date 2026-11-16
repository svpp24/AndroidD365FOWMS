package com.d365fo.mobileops.presentation.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.d365fo.mobileops.data.repository.ManualSync
import com.d365fo.mobileops.data.repository.OrderCreationRepository
import com.d365fo.mobileops.data.repository.SkuSyncRepository
import com.d365fo.mobileops.data.sync.MasterSyncState
import com.d365fo.mobileops.data.sync.OfflinePrefetcher
import com.d365fo.mobileops.data.sync.SyncScheduler
import com.d365fo.mobileops.data.sync.WorkBackup
import com.d365fo.mobileops.presentation.common.focus.FocusButton
import com.d365fo.mobileops.presentation.common.focus.FocusCaption
import com.d365fo.mobileops.presentation.common.theme.FocusColors
import com.d365fo.mobileops.presentation.common.theme.FocusType
import dagger.hilt.android.lifecycle.HiltViewModel
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

/** What the Sync screen shows - read when it opens. */
data class SyncStatus(
    val itemsLast: String? = null,
    val itemsFull: String? = null,
    val itemsNext: String = "",
    val vendors: String? = null,
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
            val itemsLast = last?.let {
                (if (it.full) "Full" else "Changes") + " · " + whenText(it.finishedAt) + " · " + "%,d".format(it.itemCount) + " items"
            }
            val lastFull = sku.lastFullAt()
            val itemsFull = listOfNotNull(
                lastFull?.let { "Last " + dayText(it) },
                sku.nextFullBy()?.let { "next by " + dayText(it) + ", on Wi-Fi while charging" },
            ).joinToString(" · ").ifBlank { null }
            val itemsNext = when {
                com.d365fo.mobileops.BuildConfig.DEBUG -> "Only when asked - this is a test build"
                else -> {
                    val due = (sku.lastSuccessAt() ?: 0L) + com.d365fo.mobileops.data.sync.SKU_CHANGES_EVERY_HOURS * 3_600_000L
                    if (due <= System.currentTimeMillis()) "Changes · within minutes, when online"
                    else "Changes · about " + whenText(due) + ", when online"
                }
            }
            val vendors = runCatching {
                val count = orders.vendorCount()
                orders.vendorsSavedAt()?.let { "%,d".format(count) + " · " + whenText(it) }
            }.getOrNull()
            _status.update { SyncStatus(itemsLast, itemsFull, itemsNext, vendors, loaded = true) }
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
    }
}

@Composable
internal fun SyncStatusSection(viewModel: SyncStatusViewModel = hiltViewModel()) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    val readiness by viewModel.readiness.collectAsStateWithLifecycle()
    val backupAt by viewModel.lastBackupAt.collectAsStateWithLifecycle()
    val masters by viewModel.mastersSync.collectAsStateWithLifecycle()
    val syncStatus = com.d365fo.mobileops.presentation.common.components.LocalSyncStatus.current
    val online = syncStatus?.fullyOnline != false
    val running = masters is MasterSyncState.Running
    LaunchedEffect(running) { viewModel.refresh() }

    Column(Modifier.fillMaxWidth()) {
        if (running) {
            com.d365fo.mobileops.presentation.common.focus.ResultBand(
                com.d365fo.mobileops.presentation.common.focus.BandState(
                    com.d365fo.mobileops.presentation.common.focus.BandKind.Info, "Syncing item data…", "It carries on if you leave this screen.",
                ),
                modifier = Modifier.padding(top = 8.dp),
            )
        } else if (masters is MasterSyncState.Failed) {
            com.d365fo.mobileops.presentation.common.focus.ResultBand(
                com.d365fo.mobileops.presentation.common.focus.BandState(
                    com.d365fo.mobileops.presentation.common.focus.BandKind.Bad, "Item data not synced", (masters as MasterSyncState.Failed).reason,
                ),
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        SyncCaption("On this device - for offline use")
        SyncRow(
            "Items and barcodes",
            listOfNotNull(
                "Last" to (status.itemsLast ?: if (status.loaded) "Not synced yet" else "…"),
                "Next" to status.itemsNext,
                status.itemsFull?.let { "Full" to it },
            ),
        )
        SyncRow(
            "Vendors",
            listOf(
                "Last" to (status.vendors ?: "Not synced yet"),
                "Next" to "With the item sync, at most every 6 hours",
            ),
        )
        SyncRow(
            "Open orders and counts",
            listOf(
                "Last" to (readiness.savedAt?.let {
                    "${readiness.purchaseOrders} POs · ${readiness.transferOrders} TOs · ${readiness.counts} counts · " + SyncStatusViewModel.whenText(it)
                } ?: "Not saved yet"),
                "Next" to "When the device comes online, at most every 30 min",
            ),
        )

        SyncCaption("Sent from this device")
        SyncRow(
            "Postings",
            listOf(
                "Waiting" to listOfNotNull(
                    "${syncStatus?.pendingCount ?: 0} waiting",
                    syncStatus?.failedCount?.takeIf { it > 0 }?.let { "$it refused - see Activity" },
                ).joinToString(" · "),
                "Next" to "As soon as posted, when online; checked every 15 min",
            ),
        )
        SyncRow(
            "Copy of unsent work",
            listOf(
                "Last" to (backupAt?.let { SyncStatusViewModel.whenText(it) } ?: "Not saved yet"),
                "Next" to "A few seconds after each change",
            ),
        )

        SyncCaption("Read live from D365FO")
        SyncRow("Stock, prices and order details", listOf("When" to "Each time a screen opens"))

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            FocusButton("Sync changes now", onClick = { viewModel.syncNow(full = false) }, enabled = online && !running, modifier = Modifier.weight(1f))
            FocusButton("Full sync", onClick = { viewModel.syncNow(full = true) }, primary = true, enabled = online && !running, modifier = Modifier.weight(1f))
        }
        if (!online) {
            Text(
                "Sync needs a connection.",
                style = FocusType.rowDetail,
                color = FocusColors.muted,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
    }
}

@Composable
private fun SyncCaption(text: String) {
    FocusCaption(text, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 6.dp))
    HorizontalDivider(color = FocusColors.divider)
}

@Composable
private fun SyncRow(title: String, lines: List<Pair<String, String>>) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
        Text(title, style = FocusType.rowTitle, color = FocusColors.ink)
        lines.forEach { (label, value) ->
            Row(Modifier.padding(top = 2.dp)) {
                Text(label, style = FocusType.rowDetail, color = FocusColors.muted, modifier = Modifier.weight(0.28f))
                Text(value, style = FocusType.rowDetail, color = FocusColors.ink, modifier = Modifier.weight(0.72f))
            }
        }
    }
    HorizontalDivider(color = FocusColors.divider)
}
