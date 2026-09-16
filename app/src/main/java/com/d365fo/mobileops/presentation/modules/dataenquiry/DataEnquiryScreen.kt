package com.d365fo.mobileops.presentation.modules.dataenquiry

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Inventory
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Store
import androidx.compose.material.icons.filled.Today
import com.d365fo.mobileops.presentation.common.components.SquareTextButton as TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.d365fo.mobileops.presentation.common.Routes
import com.d365fo.mobileops.presentation.common.components.MenuRow
import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors

private data class EnquiryTile(
    val title: String,
    val detail: String,
    val icon: ImageVector,
    val route: String?,
)

private val TILES = listOf(
    EnquiryTile(
        title = "Location inquiry",
        detail = "Scan or type a warehouse and location. Answers whether it exists and " +
            "whether it can be received into right now.",
        icon = Icons.Default.LocationOn,
        route = Routes.INQUIRY_LOCATION,
    ),
    EnquiryTile(
        title = "PO's of today",
        detail = "Every purchase order requested for delivery today in your warehouse.",
        icon = Icons.Default.Today,
        route = Routes.INQUIRY_PO_TODAY,
    ),
    EnquiryTile(
        title = "PO lines",
        detail = "Scan or type a purchase order number to see its lines - ordered, received " +
            "and outstanding quantities. Read-only: nothing here starts a receipt.",
        icon = Icons.Default.Inventory,
        route = Routes.INQUIRY_PO_LINES,
    ),
    EnquiryTile(
        title = "TO's by Store",
        detail = "Not built yet. There is no list-of-orders query for transfers today - only " +
            "one-at-a-time lookups, the same ones Transfer - ship/receive already use - so " +
            "this needs a new AOS service method before it can be built.",
        icon = Icons.Default.Store,
        route = null,
    ),
    EnquiryTile(
        title = "Transfer orders",
        detail = "Not built yet, for the same reason as \"TO's by Store\" - no list query " +
            "exists on this deployment for transfer order headers yet.",
        icon = Icons.Default.Inventory,
        route = null,
    ),
    EnquiryTile(
        title = "Picked serials",
        detail = "Not built yet. Serials captured on this device only exist while a job is " +
            "still in progress and are cleared the moment it posts, so there is nothing " +
            "durable to query yet - a history of what was picked would need a new AOS query.",
        icon = Icons.Default.Fingerprint,
        route = null,
    ),
)

/**
 * Read-only lookups, grouped the way the standard WMS app groups its own "Data enquiry" menu.
 * Everything behind this screen only ever reads from D365FO.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataEnquiryScreen(
    onBack: () -> Unit,
    onEnquirySelected: (String) -> Unit,
) {
    var infoFor by remember { mutableStateOf<EnquiryTile?>(null) }

    infoFor?.let { tile ->
        AlertDialog(
            onDismissRequest = { infoFor = null },
            icon = { Icon(tile.icon, contentDescription = null) },
            title = { Text(tile.title) },
            text = { Text(tile.detail) },
            confirmButton = {
                TextButton(onClick = { infoFor = null }) { Text("Close") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Data enquiry") },
                expandedHeight = WarehouseDimens.TopBarHeight,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(WarehouseDimens.ScreenPadding),
        ) {
            Card(
                shape = RoundedCornerShape(WarehouseDimens.CardCorner),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column {
                    TILES.forEachIndexed { index, tile ->
                        MenuRow(
                            icon = tile.icon,
                            title = tile.title,
                            available = tile.route != null,
                            onClick = { tile.route?.let(onEnquirySelected) },
                            onInfo = { infoFor = tile },
                        )
                        if (index != TILES.lastIndex) {
                            HorizontalDivider(color = WarehouseStatusColors.border)
                        }
                    }
                }
            }
        }
    }
}
