package com.d365fo.mobileops.presentation.common

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.background
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
import com.d365fo.mobileops.presentation.common.components.SquareTextButton as TextButton
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
    // In the Focus list style (user, 15 Nov 2026): who signed in, the question, one row per warehouse.
    val session by viewModel.session.collectAsStateWithLifecycle()
    val user = session?.displayName?.trim()?.takeIf { it.isNotBlank() } ?: session?.userName.orEmpty()
    val online = com.d365fo.mobileops.presentation.common.components.LocalSyncStatus.current?.fullyOnline != false
    val colors = com.d365fo.mobileops.presentation.common.theme.FocusColors
    Column(modifier = Modifier.fillMaxSize().background(colors.page)) {
        androidx.compose.foundation.layout.Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 8.dp),
        ) {
            StatusAvatar(initials = initialsOf(user), tag = "", online = online)
            Column(Modifier.weight(1f)) {
                if (user.isNotBlank()) {
                    Text("Signed in as $user", style = com.d365fo.mobileops.presentation.common.theme.FocusType.rowDetail, color = colors.muted)
                }
                Text(
                    "Where are you today?",
                    style = com.d365fo.mobileops.presentation.common.theme.FocusType.panelTitle,
                    color = colors.ink,
                    modifier = Modifier.semantics { heading() },
                )
            }
        }
        com.d365fo.mobileops.presentation.common.focus.FocusCaption(
            "Choose your warehouse · ${warehouses.size}",
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 6.dp),
        )
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(warehouses, key = { it.warehouseId }) { wh ->
                WarehouseRow(warehouse = wh, current = false, pill = null, onClick = { viewModel.select(wh.warehouseId) })
            }
        }
        com.d365fo.mobileops.presentation.common.focus.FocusButton(
            "Not you? Sign out",
            onClick = onSignOut,
            contentColor = colors.badIcon,
            modifier = Modifier.fillMaxWidth().padding(20.dp),
        )
    }
}
