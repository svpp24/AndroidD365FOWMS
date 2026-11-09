package com.d365fo.mobileops.presentation.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.d365fo.mobileops.domain.model.WarehouseWorkflow
import com.d365fo.mobileops.presentation.common.components.CancelOutlinedButton
import com.d365fo.mobileops.presentation.common.components.DialogButtonRow
import com.d365fo.mobileops.presentation.common.components.LineIcons
import com.d365fo.mobileops.presentation.common.components.ListRow
import com.d365fo.mobileops.presentation.common.components.PreviewSurface
import com.d365fo.mobileops.presentation.common.components.SquareButton
import com.d365fo.mobileops.presentation.common.components.SquareOutlinedButton
import com.d365fo.mobileops.presentation.common.scan.TransferDirection
import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors
import com.d365fo.mobileops.presentation.common.theme.WarehouseType

/*
 * Home's top half in the 9 Nov redesign (brief §4, phase 8): the big navy "Scan to start" card -
 * any PO, transfer, bin or item opens its process (HomeScanViewModel) - then four task tiles with
 * today's counts. The menu, Menu access and module switches are HomeScreen's as before.
 */

/** The navy of the Industrial palette - fixed, as the brief draws this card, whatever the palette. */
private val ScanCardNavy = Color(0xFF1E3A8A)
private val ScanCardLine = Color.White.copy(alpha = 0.9f)

/** "Scan to start": what a scan opens, with the camera and Search for when there's no label. */
@Composable
internal fun ScanToStartCard(
    onCamera: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(ScanCardNavy)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(Icons.Outlined.QrCodeScanner, contentDescription = null, tint = Color.White, modifier = Modifier.size(44.dp))
            Column {
                Text("Scan to start", style = WarehouseType.bandTitle, color = Color.White)
                Text("PO · transfer · bin · item", style = WarehouseType.line, color = ScanCardLine)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val onNavy = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
            val border = BorderStroke(1.5.dp, Color.White.copy(alpha = 0.7f))
            SquareOutlinedButton(
                onClick = onCamera,
                colors = onNavy,
                border = border,
                modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget),
            ) {
                Icon(Icons.Filled.PhotoCamera, contentDescription = null)
                Text("Camera", modifier = Modifier.padding(start = 8.dp))
            }
            SquareOutlinedButton(
                onClick = onSearch,
                colors = onNavy,
                border = border,
                modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget),
            ) {
                Icon(Icons.Filled.Search, contentDescription = null)
                Text("Search", modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

/** One of Home's four tasks. */
internal data class HomeTask(
    val title: String,
    val icon: ImageVector,
    /** Today's figure ("3 due"), or null when D365FO hasn't answered. */
    val count: String?,
    /** The modules behind it the worker may open - one opens at once, more ask which. */
    val modules: List<ModuleTile>,
)

/** Receive, Ship, Count, Create - those of them on the worker's menu, in that order. */
internal fun homeTasks(visible: List<ModuleTile>, counts: Map<WarehouseWorkflow, String>): List<HomeTask> {
    fun of(vararg workflows: WarehouseWorkflow) = workflows.mapNotNull { w -> visible.firstOrNull { it.workflow == w } }
    return listOf(
        HomeTask("Receive", LineIcons.Receive, counts[WarehouseWorkflow.PO_RECEIVING], of(WarehouseWorkflow.PO_RECEIVING)),
        HomeTask("Ship", LineIcons.Ship, counts[WarehouseWorkflow.TRANSFER_SHIP], of(WarehouseWorkflow.TRANSFER_SHIP)),
        HomeTask("Count", LineIcons.Count, counts[WarehouseWorkflow.INVENTORY_COUNT], of(WarehouseWorkflow.INVENTORY_COUNT)),
        HomeTask(
            "Create", LineIcons.Create, null,
            of(WarehouseWorkflow.PO_CREATE, WarehouseWorkflow.TO_CREATE, WarehouseWorkflow.PURCHASE_RETURN),
        ),
    ).filter { it.modules.isNotEmpty() }
}

/** The workflows Home's tiles open - the rest of the menu is listed under them. */
internal val HOME_TASK_WORKFLOWS = setOf(
    WarehouseWorkflow.PO_RECEIVING, WarehouseWorkflow.TRANSFER_SHIP, WarehouseWorkflow.INVENTORY_COUNT,
    WarehouseWorkflow.PO_CREATE, WarehouseWorkflow.TO_CREATE, WarehouseWorkflow.PURCHASE_RETURN,
)

/** The tiles, two to a row; rows grow with their text rather than cut it. */
@Composable
internal fun HomeTaskGrid(tasks: List<HomeTask>, onTask: (HomeTask) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tasks.chunked(2).forEach { pair ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            ) {
                pair.forEach { task ->
                    HomeTaskTile(task, onClick = { onTask(task) }, modifier = Modifier.weight(1f).fillMaxHeight())
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun HomeTaskTile(task: HomeTask, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .heightIn(min = 108.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(WarehouseStatusColors.surface)
            .border(1.dp, WarehouseStatusColors.divider, RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClickLabel = "Open ${task.title}", onClick = onClick)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(task.icon, contentDescription = null, tint = ScanCardNavy, modifier = Modifier.size(30.dp))
        Text(task.title, style = WarehouseType.title, color = WarehouseStatusColors.ink)
        task.count?.let { Text(it, style = WarehouseType.label, color = WarehouseStatusColors.inkMuted) }
    }
}

/** Create has three modules: when more than one is on the menu, pick one. */
@Composable
internal fun ModuleChooserDialog(title: String, modules: List<ModuleTile>, onPick: (ModuleTile) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.clip(RoundedCornerShape(12.dp))) {
                modules.forEach { module ->
                    ListRow(
                        title = module.title,
                        subtitles = listOf(module.summary),
                        onClick = { onPick(module) },
                    )
                }
            }
        },
        confirmButton = {
            DialogButtonRow(
                dismiss = null,
                confirm = {
                    CancelOutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget)) {
                        Text("Cancel")
                    }
                },
            )
        },
    )
}

/** A transfer the device can't place: ship it from here, or receive it here? */
@Composable
internal fun TransferDirectionDialog(
    transferId: String,
    choices: List<TransferDirection>,
    onChoose: (TransferDirection) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(transferId, style = WarehouseType.codeSmall) },
        text = { Text("Ship this transfer, or receive it?", style = WarehouseType.body) },
        confirmButton = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                choices.forEach { direction ->
                    SquareButton(
                        onClick = { onChoose(direction) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                    ) {
                        Icon(if (direction == TransferDirection.SHIP) LineIcons.Ship else LineIcons.Store, contentDescription = null)
                        Text(
                            if (direction == TransferDirection.SHIP) "Ship" else "Receive",
                            modifier = Modifier.padding(start = 10.dp),
                        )
                    }
                }
                CancelOutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = WarehouseDimens.MinTouchTarget)) {
                    Text("Cancel")
                }
            }
        },
    )
}

@Preview(name = "Home start", widthDp = 360, showBackground = true)
@Composable
private fun HomeStartPreview() = PreviewSurface { HomeStartSamples() }

@Preview(name = "Home start - 130%", widthDp = 360, showBackground = true)
@Composable
private fun HomeStartPreviewLarge() = PreviewSurface(large = true) { HomeStartSamples() }

@Composable
internal fun HomeStartSamples() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScanToStartCard(onCamera = {}, onSearch = {})
        HomeTaskGrid(
            tasks = homeTasks(
                MODULES,
                mapOf(WarehouseWorkflow.PO_RECEIVING to "12 due", WarehouseWorkflow.TRANSFER_SHIP to "3 to ship", WarehouseWorkflow.INVENTORY_COUNT to "2 open"),
            ),
            onTask = {},
        )
    }
}
