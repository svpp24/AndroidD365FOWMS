package com.d365fo.mobileops.presentation.modules.cyclecounting

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.d365fo.mobileops.data.local.dao.ItemDao
import com.d365fo.mobileops.data.repository.SkuSyncRepository
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors
import com.d365fo.mobileops.util.Formatters
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.NumberFormat
import javax.inject.Inject

/** What the device holds of D365FO's item master - what counting offline looks items up in. */
data class ItemMasterCounts(val items: Int, val barcodes: Int, val syncedAt: Long?, val fullAt: Long? = null)

@HiltViewModel
class ItemMasterViewModel @Inject constructor(
    private val itemDao: ItemDao,
    private val skuSync: SkuSyncRepository,
) : ViewModel() {
    private val _counts = MutableStateFlow<ItemMasterCounts?>(null)
    val counts = _counts.asStateFlow()

    init { reload() }

    /** Counts again - after a manual sync finishes. */
    fun reload() {
        viewModelScope.launch {
            _counts.value = ItemMasterCounts(
                items = runCatching { itemDao.itemCount() }.getOrDefault(0),
                barcodes = runCatching { itemDao.barcodeCount() }.getOrDefault(0),
                syncedAt = skuSync.lastSummary()?.finishedAt,
                fullAt = skuSync.lastFullAt(),
            )
        }
    }
}

/**
 * "On this device: 12,345 items · 20,456 barcodes · synced 08 Nov 13:05" - so the counter knows
 * counting will work offline (user, 8 Nov). Amber when the item master is not on the device yet.
 */
@Composable
fun ItemMasterOnDevice(
    modifier: Modifier = Modifier,
    /** Settings' manual Sync (user, 9 Nov) - null shows no button. */
    onSync: (() -> Unit)? = null,
    /**
     * Settings' Full re-sync (user, 13 Nov): the whole item list again, after a confirmation - for
     * one device whose list looks wrong. Null shows no action.
     */
    onFullSync: (() -> Unit)? = null,
    syncing: Boolean = false,
    /** A failed sync's reason, shown under the counts. */
    syncError: String? = null,
    viewModel: ItemMasterViewModel = hiltViewModel(),
) {
    // A sync that just finished changes the counts.
    androidx.compose.runtime.LaunchedEffect(syncing) { if (!syncing) viewModel.reload() }
    val counts by viewModel.counts.collectAsStateWithLifecycle()
    val c = counts ?: return
    val n = NumberFormat.getIntegerInstance()
    val missing = c.items == 0
    // Harbour: a white card with a coloured edge - green "Data up to date", amber when missing.
    val edge = if (missing) WarehouseStatusColors.amber else WarehouseStatusColors.green
    androidx.compose.foundation.layout.Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .height(androidx.compose.foundation.layout.IntrinsicSize.Min),
    ) {
        androidx.compose.foundation.layout.Box(
            Modifier.width(4.dp).fillMaxHeight().background(edge),
        )
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(2.dp),
        ) {
            androidx.compose.foundation.layout.Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(5.dp),
            ) {
                androidx.compose.material3.Icon(
                    if (missing) androidx.compose.material.icons.Icons.Filled.Warning else androidx.compose.material.icons.Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = edge,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    if (missing) "Item master not on this device" else "Data up to date",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = edge,
                )
            }
            Text(
                text = if (missing) {
                    "Counting offline can't look items up - sync now."
                } else {
                    "${n.format(c.items)} items · ${n.format(c.barcodes)} barcodes"
                },
                style = MaterialTheme.typography.bodySmall,
                color = WarehouseStatusColors.text3,
            )
            if (!missing) c.syncedAt?.let {
                Text(
                    "Synced ${Formatters.shortDate(it)} ${Formatters.clockTime(it)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = WarehouseStatusColors.text3,
                )
            }
            if (!missing) c.fullAt?.let {
                Text(
                    "Last full sync ${Formatters.shortDate(it)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = WarehouseStatusColors.text3,
                )
            }
            syncError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = WarehouseStatusColors.red) }
            if (onFullSync != null) FullResyncAction(onFullSync, enabled = !syncing)
        }
        // The manual Sync (user, 9 Nov): items, barcodes and variants changed since the last one.
        if (onSync != null) {
            com.d365fo.mobileops.presentation.common.components.DockOutlinedButton(
                onClick = onSync,
                enabled = !syncing,
                icon = androidx.compose.material.icons.Icons.Filled.Refresh,
                text = if (syncing) "Syncing…" else "Sync",
                modifier = Modifier.align(androidx.compose.ui.Alignment.CenterVertically).padding(end = 10.dp),
            )
        }
    }
}

/**
 * The card's quieter second action: a navy text link, then the app's own confirmation (as Sign
 * out). A full re-sync is heavy - every item, barcode and variant again - so it is asked for.
 */
@Composable
private fun FullResyncAction(onFullSync: () -> Unit, enabled: Boolean) {
    var confirm by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Text(
        "Full re-sync",
        style = com.d365fo.mobileops.presentation.common.theme.FocusType.rowDetail.copy(fontWeight = FontWeight.Bold),
        color = if (enabled) com.d365fo.mobileops.presentation.common.theme.FocusColors.navy
        else com.d365fo.mobileops.presentation.common.theme.FocusColors.iconMuted,
        modifier = Modifier
            .padding(top = 4.dp)
            .heightIn(min = 48.dp)
            .clickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.Button) { confirm = true }
            .padding(vertical = 12.dp),
    )
    if (confirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Full re-sync?") },
            text = { Text("Downloads the whole item list again - every item, barcode and variant. It takes longer and uses more data than Sync; use Wi-Fi.") },
            confirmButton = {
                com.d365fo.mobileops.presentation.common.components.DialogButtonRow(
                    dismiss = {
                        com.d365fo.mobileops.presentation.common.components.CancelOutlinedButton(
                            onClick = { confirm = false },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        ) { Text("Cancel") }
                    },
                    confirm = {
                        com.d365fo.mobileops.presentation.common.focus.FocusButton(
                            "Re-sync all",
                            onClick = { confirm = false; onFullSync() },
                            primary = true,
                            modifier = Modifier.weight(1f),
                        )
                    },
                )
            },
        )
    }
}
