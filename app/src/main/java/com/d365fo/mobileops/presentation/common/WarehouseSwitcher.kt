package com.d365fo.mobileops.presentation.common

import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warehouse
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.d365fo.mobileops.data.remote.api.MobileOpsServiceApi
import com.d365fo.mobileops.data.remote.dto.UserWarehousesRequestBody
import com.d365fo.mobileops.data.remote.dto.UserWarehousesServiceRequest
import com.d365fo.mobileops.data.remote.error.D365ErrorMapper
import com.d365fo.mobileops.data.repository.AssignedWarehouse
import com.d365fo.mobileops.data.repository.WorkerSession
import com.d365fo.mobileops.data.repository.WorkerSessionRepository
import com.d365fo.mobileops.data.security.CompanyProvider
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors
import com.d365fo.mobileops.util.NetworkResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * The worker's current warehouse and the ones they may switch to. On first use it refreshes the
 * assigned list (with names) from HSMobileOpsUserWarehouseSvc, so names show even for a session
 * that never went through sign-in; a failure keeps whatever the session already has.
 */
@HiltViewModel
class WarehouseSwitcherViewModel @Inject constructor(
    private val sessions: WorkerSessionRepository,
    private val service: MobileOpsServiceApi,
    private val errorMapper: D365ErrorMapper,
    private val companyProvider: CompanyProvider,
    private val flowSessions: com.d365fo.mobileops.data.repository.FlowSessionRepository,
) : ViewModel() {

    val session: StateFlow<WorkerSession?> = sessions.session

    init {
        refresh()
    }

    private fun refresh() {
        val user = sessions.session.value?.userName?.takeIf { it.isNotBlank() } ?: return
        val company = companyProvider()?.takeIf { it.isNotBlank() }?.lowercase() ?: return
        viewModelScope.launch {
            val result = errorMapper.safeCall {
                service.getUserWarehouses(UserWarehousesServiceRequest(UserWarehousesRequestBody(company, user)))
            }
            if (result is NetworkResult.Success && result.data.success) {
                sessions.replaceWarehouses(
                    result.data.warehouses.filter { it.warehouseId.isNotBlank() }.map {
                        AssignedWarehouse(it.siteId, it.warehouseId, it.warehouseName, it.isDefault)
                    },
                )
            } else {
                Timber.w("assigned warehouses not refreshed: %s", result)
            }
        }
    }

    fun select(warehouseId: String) = sessions.selectWarehouse(warehouseId)

    /** Every unfinished job on the device, across warehouses - for the per-warehouse counts. */
    val allUnfinished: StateFlow<List<com.d365fo.mobileops.data.repository.UnfinishedWork>> =
        flowSessions.unfinished.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), emptyList())
}

/**
 * "Code · name" - the code first, so it survives when a long name is cut off in the top bar -
 * or the code alone when D365FO has no name (or the name is just the code).
 */
fun AssignedWarehouse.chipLabel(): String =
    if (warehouseName.isBlank() || warehouseName == warehouseId) warehouseId else "$warehouseId · $warehouseName"

/**
 * The top-bar warehouse pill: name and code, and - when more than one warehouse is assigned -
 * a tap opens the list to switch.
 */
@Composable
fun WarehouseChip(viewModel: WarehouseSwitcherViewModel = hiltViewModel()) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    val selected = session?.selected
    val choices = session?.warehouses.orEmpty()
    val canSwitch = choices.size > 1
    var open by remember { mutableStateOf(false) }

    Box {
        Text(
            text = (selected?.chipLabel() ?: if (canSwitch) "Choose warehouse" else "No warehouse assigned") +
                if (canSwitch) "  \u25BE" else "",
            style = MaterialTheme.typography.labelMedium,
            color = WarehouseStatusColors.accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .widthIn(max = 190.dp)
                .padding(end = 4.dp)
                .border(1.dp, WarehouseStatusColors.accent, RoundedCornerShape(50))
                .then(if (canSwitch) Modifier.clickable { open = true } else Modifier)
                .padding(horizontal = 12.dp, vertical = 5.dp),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            choices.forEach { warehouse ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                warehouse.warehouseName.ifBlank { warehouse.warehouseId },
                                fontWeight = if (warehouse.warehouseId == selected?.warehouseId) FontWeight.SemiBold else FontWeight.Normal,
                            )
                            Text(
                                warehouse.warehouseId + if (warehouse.isDefault) " \u00B7 default" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = WarehouseStatusColors.text3,
                            )
                        }
                    },
                    onClick = {
                        open = false
                        viewModel.select(warehouse.warehouseId)
                    },
                )
            }
        }
    }
}

/**
 * Settings' "Assigned warehouse": the current warehouse's name and code, and the others the
 * worker may switch to (Change warehouse, or Home's warehouse card).
 */
@Composable
fun AssignedWarehouseText(fallbackId: String, viewModel: WarehouseSwitcherViewModel = hiltViewModel()) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    val selected = session?.selected
    Column {
        Text(
            text = selected?.chipLabel() ?: fallbackId.ifBlank { "No warehouse - ask a supervisor" },
            style = MaterialTheme.typography.headlineSmall,
        )
        val others = session?.warehouses.orEmpty().filter { it.warehouseId != selected?.warehouseId }
        if (others.isNotEmpty()) {
            Text(
                text = "Also: " + others.joinToString { it.chipLabel() },
                style = MaterialTheme.typography.bodySmall,
                color = WarehouseStatusColors.text3,
            )
        }
    }
}

/** The selected warehouse's code alone, for Home's top bar. Nothing when none is selected. */
@Composable
fun WarehouseCode(viewModel: WarehouseSwitcherViewModel = hiltViewModel()) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    val code = session?.selected?.warehouseId ?: return
    // The same outlined pill as the old top-bar warehouse chip (WarehouseChip), on request.
    Text(
        text = code,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = WarehouseStatusColors.accent,
        modifier = Modifier
            .padding(end = 4.dp)
            .border(1.dp, WarehouseStatusColors.accent, RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 5.dp),
    )
}

/**
 * Home's "where am I" card, above the tiles: the warehouse code and name in full, wrapping. A
 * tap changes warehouse when several are assigned (also under Settings).
 */
@Composable
fun WarehouseHeader(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: WarehouseSwitcherViewModel = hiltViewModel(),
) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    val selected = session?.selected
    val canSwitch = session?.warehouses.orEmpty().size > 1
    val shape = RoundedCornerShape(com.d365fo.mobileops.presentation.common.theme.WarehouseDimens.CardCorner)
    androidx.compose.foundation.layout.Row(
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .background(androidx.compose.ui.graphics.Color.White, shape)
            .border(1.5.dp, WarehouseStatusColors.border, shape)
            .then(if (canSwitch) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        androidx.compose.material3.Icon(
            Icons.Default.Warehouse,
            contentDescription = null,
            tint = WarehouseStatusColors.accent,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = selected?.let { "Warehouse ${it.warehouseId}" } ?: "Warehouse",
                style = MaterialTheme.typography.labelMedium,
                color = WarehouseStatusColors.text3,
            )
            Text(
                text = selected?.let { it.warehouseName.ifBlank { it.warehouseId } }
                    ?: if (canSwitch) "Choose warehouse" else "No warehouse assigned",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = WarehouseStatusColors.accent,
            )
        }
    }
}

/**
 * Change warehouse: the worker's assigned warehouses, each with how much unfinished work is
 * saved there. Switching never deletes work - the current warehouse's unfinished jobs go on
 * hold and come back when the worker switches back; every screen opened afterwards loads the
 * new warehouse's data.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ChangeWarehouseScreen(
    onDone: () -> Unit,
    viewModel: WarehouseSwitcherViewModel = hiltViewModel(),
    unfinishedViewModel: UnfinishedWorkViewModel = hiltViewModel(),
) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    val all by viewModel.allUnfinished.collectAsStateWithLifecycle()
    val here by unfinishedViewModel.unfinished.collectAsStateWithLifecycle()
    var confirmFor by remember { mutableStateOf<AssignedWarehouse?>(null) }
    val selected = session?.selected

    confirmFor?.let { target ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmFor = null },
            title = { Text("Switch to ${target.warehouseId}?") },
            text = {
                Text(
                    "${here.size} unfinished job${if (here.size == 1) "" else "s"} wait${if (here.size == 1) "s" else ""} here for you.",
                )
            },
            confirmButton = {
                com.d365fo.mobileops.presentation.common.components.DialogButtonRow(
                    dismiss = { com.d365fo.mobileops.presentation.common.components.CancelOutlinedButton(onClick = { confirmFor = null }, modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget)) { Text("Cancel") } },
                    confirm = { com.d365fo.mobileops.presentation.common.components.ConfirmButton(onClick = {
                    viewModel.select(target.warehouseId)
                    confirmFor = null
                    onDone()
                }, modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget)) { Text("Switch") } },
                )
            },
        )
    }

    androidx.compose.material3.Scaffold(
        topBar = {
            com.d365fo.mobileops.presentation.common.components.AppTopBar(
                title = { Text("Change warehouse") },
                expandedHeight = com.d365fo.mobileops.presentation.common.theme.WarehouseDimens.TopBarHeight,
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = onDone) {
                        androidx.compose.material3.Icon(
                            androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
    ) { padding ->
        androidx.compose.foundation.lazy.LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        ) {
            val warehouses = session?.warehouses.orEmpty()
            if (warehouses.isEmpty()) {
                item { Text("No warehouse - ask a supervisor") }
            }
            items(warehouses.size) { index ->
                val warehouse = warehouses[index]
                val isCurrent = warehouse.warehouseId == selected?.warehouseId
                val saved = all.count { it.warehouseId.equals(warehouse.warehouseId, ignoreCase = true) }
                com.d365fo.mobileops.presentation.common.components.AppCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isCurrent) {
                            if (here.isEmpty()) {
                                viewModel.select(warehouse.warehouseId)
                                onDone()
                            } else {
                                confirmFor = warehouse
                            }
                        },
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            warehouse.warehouseName.ifBlank { warehouse.warehouseId },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                        )
                        Text(
                            listOfNotNull(
                                warehouse.warehouseId,
                                warehouse.siteId.takeIf { it.isNotBlank() }?.let { "site $it" },
                                "default".takeIf { warehouse.isDefault },
                                "current".takeIf { isCurrent },
                                if (saved > 0) "$saved unfinished${if (isCurrent) "" else " on hold"}" else null,
                            ).joinToString(" \u00B7 "),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isCurrent) WarehouseStatusColors.accent else WarehouseStatusColors.text3,
                        )
                    }
                }
            }
        }
    }
}


