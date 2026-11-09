package com.d365fo.mobileops.presentation.modules.cyclecounting

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
data class ItemMasterCounts(val items: Int, val barcodes: Int, val syncedAt: Long?)

@HiltViewModel
class ItemMasterViewModel @Inject constructor(
    private val itemDao: ItemDao,
    private val skuSync: SkuSyncRepository,
) : ViewModel() {
    private val _counts = MutableStateFlow<ItemMasterCounts?>(null)
    val counts = _counts.asStateFlow()

    init {
        viewModelScope.launch {
            _counts.value = ItemMasterCounts(
                items = runCatching { itemDao.itemCount() }.getOrDefault(0),
                barcodes = runCatching { itemDao.barcodeCount() }.getOrDefault(0),
                syncedAt = skuSync.lastSummary()?.finishedAt,
            )
        }
    }
}

/**
 * "On this device: 12,345 items · 20,456 barcodes · synced 08 Nov 13:05" - so the counter knows
 * counting will work offline (user, 8 Nov). Amber when the item master is not on the device yet.
 */
@Composable
fun ItemMasterOnDevice(modifier: Modifier = Modifier, viewModel: ItemMasterViewModel = hiltViewModel()) {
    val counts by viewModel.counts.collectAsStateWithLifecycle()
    val c = counts ?: return
    val n = NumberFormat.getIntegerInstance()
    val missing = c.items == 0
    Text(
        text = if (missing) {
            "The item master is not on this device yet - counting offline can't look items up. Sync masters in Settings."
        } else {
            listOfNotNull(
                "On this device: ${n.format(c.items)} items",
                "${n.format(c.barcodes)} barcodes",
                c.syncedAt?.let { "synced ${Formatters.shortDate(it)} ${Formatters.clockTime(it)}" },
            ).joinToString(" · ")
        },
        style = MaterialTheme.typography.bodySmall,
        fontWeight = if (missing) FontWeight.SemiBold else FontWeight.Normal,
        color = if (missing) WarehouseStatusColors.amber else WarehouseStatusColors.text3,
        modifier = modifier
            .fillMaxWidth()
            .background(if (missing) WarehouseStatusColors.amberDim else Color.Transparent, RoundedCornerShape(10.dp))
            .padding(horizontal = if (missing) 12.dp else 0.dp, vertical = if (missing) 8.dp else 0.dp),
    )
}
