package com.d365fo.mobileops.presentation.common

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
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
 * worker may switch to from the top bar.
 */
@Composable
fun AssignedWarehouseText(fallbackId: String, viewModel: WarehouseSwitcherViewModel = hiltViewModel()) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    val selected = session?.selected
    Column {
        Text(
            text = selected?.chipLabel() ?: fallbackId.ifBlank { "No warehouse is assigned to you. Ask a supervisor." },
            style = MaterialTheme.typography.headlineSmall,
        )
        val others = session?.warehouses.orEmpty().filter { it.warehouseId != selected?.warehouseId }
        if (others.isNotEmpty()) {
            Text(
                text = "Also assigned: " + others.joinToString { it.chipLabel() } + ". Switch from the top bar.",
                style = MaterialTheme.typography.bodySmall,
                color = WarehouseStatusColors.text3,
            )
        }
    }
}

/**
 * The warehouse as a small line under a top-bar title - code and name, ellipsized, with the
 * switcher list behind a tap when more than one warehouse is assigned. Sized to fit under the
 * title without making the bar taller.
 */
@Composable
fun WarehouseSubtitle(viewModel: WarehouseSwitcherViewModel = hiltViewModel()) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    val selected = session?.selected
    val choices = session?.warehouses.orEmpty()
    val canSwitch = choices.size > 1
    var open by remember { mutableStateOf(false) }

    Box {
        Text(
            text = (selected?.chipLabel() ?: if (canSwitch) "Choose warehouse" else "No warehouse assigned") +
                if (canSwitch) " \u25BE" else "",
            style = MaterialTheme.typography.labelSmall,
            color = WarehouseStatusColors.accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = if (canSwitch) Modifier.clickable { open = true } else Modifier,
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
