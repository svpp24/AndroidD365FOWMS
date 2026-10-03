package com.d365fo.mobileops.presentation.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.d365fo.mobileops.data.repository.AssignedWarehouse
import com.d365fo.mobileops.presentation.common.components.AppCard
import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors

/**
 * Right after sign-in, when the worker has several warehouses in D365FO and none is the default:
 * one tap picks where they work today. Nothing has started yet, so unlike Change warehouse there
 * is no work to put on hold. Leaving the default unset is a D365FO choice; this only asks.
 */
@Composable
fun PickWarehouseScreen(
    warehouses: List<AssignedWarehouse>,
    onSignOut: () -> Unit,
    viewModel: WarehouseSwitcherViewModel = hiltViewModel(),
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(WarehouseDimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Choose your warehouse", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "You can work in ${warehouses.size} warehouses. Pick where you are today - you can change it later from the title bar.",
            style = MaterialTheme.typography.bodyMedium,
            color = WarehouseStatusColors.text3,
        )
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(warehouses, key = { it.warehouseId }) { wh ->
                AppCard(
                    modifier = Modifier.fillMaxWidth().heightIn(min = WarehouseDimens.MinTouchTarget)
                        .clickable { viewModel.select(wh.warehouseId) },
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(wh.warehouseId, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        val name = wh.warehouseName.takeIf { it.isNotBlank() && !it.equals(wh.warehouseId, ignoreCase = true) }
                        Text(
                            listOfNotNull(name, wh.siteId.takeIf { it.isNotBlank() }?.let { "Site $it" }).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = WarehouseStatusColors.text3,
                        )
                    }
                }
            }
        }
        TextButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) { Text("Not you? Sign out") }
    }
}
