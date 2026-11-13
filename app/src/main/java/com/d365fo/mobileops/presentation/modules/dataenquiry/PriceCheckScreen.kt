package com.d365fo.mobileops.presentation.modules.dataenquiry

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.d365fo.mobileops.data.local.dao.ItemDao
import com.d365fo.mobileops.data.repository.EnquiryRepository
import com.d365fo.mobileops.data.repository.ItemUnitRepository
import com.d365fo.mobileops.data.repository.PriceQuote
import com.d365fo.mobileops.data.repository.WorkerSessionRepository
import com.d365fo.mobileops.domain.model.ProductVariant
import com.d365fo.mobileops.presentation.common.focus.BandKind
import com.d365fo.mobileops.presentation.common.focus.BandState
import com.d365fo.mobileops.presentation.common.focus.FocusButton
import com.d365fo.mobileops.presentation.common.focus.FocusCaption
import com.d365fo.mobileops.presentation.common.focus.FocusCenter
import com.d365fo.mobileops.presentation.common.focus.FocusListRow
import com.d365fo.mobileops.presentation.common.focus.FocusScreen
import com.d365fo.mobileops.presentation.common.focus.ScanPillField
import com.d365fo.mobileops.presentation.common.scan.CameraScannerSheet
import com.d365fo.mobileops.presentation.common.scan.HidScannerBridge
import com.d365fo.mobileops.presentation.common.scan.ScanFeedback
import com.d365fo.mobileops.presentation.common.theme.FocusColors
import com.d365fo.mobileops.presentation.common.theme.FocusShapes
import com.d365fo.mobileops.presentation.common.theme.FocusType
import com.d365fo.mobileops.util.NetworkResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import javax.inject.Inject

/** The item in hand: from a barcode (its variant and unit) or a typed item number. */
data class PricedItem(
    val itemId: String,
    val itemName: String,
    val variant: ProductVariant,
)

data class PriceCheckState(
    val warehouseId: String? = null,
    val item: PricedItem? = null,
    val units: List<String> = emptyList(),
    val unit: String = "",
    val quantity: String = "1",
    val busy: Boolean = false,
    val quote: PriceQuote? = null,
    val error: String? = null,
)

/**
 * Price check (user, 12 Nov): scan an item or barcode, give quantity and unit, and D365FO prices
 * the line with Commerce's own price simulator for this store - same trade agreements, price
 * adjustments and discounts the simulator applies, with no customer.
 */
@HiltViewModel
class PriceCheckViewModel @Inject constructor(
    private val itemDao: ItemDao,
    private val units: ItemUnitRepository,
    private val enquiry: EnquiryRepository,
    private val scannerBridge: HidScannerBridge,
    private val scanFeedback: ScanFeedback,
    workerSession: WorkerSessionRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(PriceCheckState(warehouseId = workerSession.session.value?.selected?.warehouseId))
    val state = _state.asStateFlow()
    private var pending: Job? = null

    init {
        viewModelScope.launch { scannerBridge.scans.collect { onScanned(it.value) } }
    }

    fun onCameraDecoded(value: String) = scannerBridge.submitCameraScan(value)

    fun onScanned(raw: String) {
        val value = raw.trim()
        if (value.isEmpty()) return
        pending?.cancel()
        pending = viewModelScope.launch {
            val scanned = itemDao.resolveBarcode(value.uppercase())
            val item = scanned?.let { PricedItem(it.itemId, it.itemName, it.variant) }
                ?: (itemDao.getItem(value) ?: itemDao.getItem(value.uppercase()))?.let { PricedItem(it.itemId, it.itemName, ProductVariant()) }
            if (item == null) {
                scanFeedback.error()
                _state.update { it.copy(item = null, quote = null, error = "$value is not an item or barcode on this device") }
                return@launch
            }
            scanFeedback.success()
            val entity = itemDao.getItem(item.itemId)
            // The item's own units first (sales, then inventory and purchase), then any other it converts to.
            val own = listOfNotNull(entity?.salesUnit, entity?.inventoryUnit, entity?.purchaseUnit, scanned?.scanUnit)
            val converted = runCatching { units.unitsFor(item.itemId, item.variant, emptyList()).map { it.unitId } }.getOrDefault(emptyList())
            val choices = (own + converted).filter { it.isNotBlank() }.distinctBy { it.uppercase() }
            val startUnit = scanned?.scanUnit?.takeIf { it.isNotBlank() } ?: entity?.salesUnit ?: entity?.inventoryUnit.orEmpty()
            val startQty = scanned?.quantityPerScan?.toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString() ?: "1"
            _state.update {
                it.copy(item = item, units = choices, unit = startUnit, quantity = startQty, quote = null, error = null)
            }
        }
    }

    fun setQuantity(value: String) = _state.update { it.copy(quantity = value.filter { c -> c.isDigit() || c == '.' }, quote = null) }

    fun setUnit(unit: String) = _state.update { it.copy(unit = unit, quote = null) }

    fun checkPrice() {
        val s = _state.value
        val item = s.item ?: return
        val warehouse = s.warehouseId
        if (warehouse.isNullOrBlank()) {
            _state.update { it.copy(error = "No warehouse is assigned to you.") }
            return
        }
        val qty = s.quantity.toDoubleOrNull()
        if (qty == null || qty <= 0.0) {
            _state.update { it.copy(error = "Enter a quantity above 0") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, quote = null) }
            when (val r = enquiry.checkPrice(warehouse, item.itemId, item.variant, qty, s.unit)) {
                is NetworkResult.Success -> _state.update { it.copy(busy = false, quote = r.data) }
                is NetworkResult.Error -> _state.update { it.copy(busy = false, error = r.failure.message) }
                NetworkResult.Loading -> Unit
            }
        }
    }
}

internal fun money(currency: String, amount: Double): String =
    (if (currency.isBlank()) "" else "$currency ") + String.format(java.util.Locale.US, "%,.2f", amount)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PriceCheckScreen(
    onBack: () -> Unit,
    viewModel: PriceCheckViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showCamera by remember { mutableStateOf(false) }
    val item = state.item
    val quote = state.quote
    FocusScreen(
        title = "Price check",
        onBack = onBack,
        counter = state.warehouseId,
        band = when {
            state.error != null -> BandState(BandKind.Bad, state.error.orEmpty(), null)
            quote != null -> BandState(
                BandKind.Ok,
                money(quote.currency, quote.netAmount),
                "${plainNumber(quote.quantity)} ${quote.unitId}" + if (quote.priceIncludesTax) " · tax included" else " · before tax",
            )
            else -> null
        },
        scanPill = { ScanPillField(enabled = !state.busy) },
        topBarTrailing = {
            IconButton(onClick = { showCamera = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Default.PhotoCamera, contentDescription = "Camera scan", tint = FocusColors.ink)
            }
        },
    ) {
        if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp))
        if (item == null) {
            FocusCenter(caption = "Item", code = "–", description = "Scan an item or barcode")
            return@FocusScreen
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(item.itemId, style = FocusType.rowCode, color = FocusColors.ink)
                if (item.itemName.isNotBlank()) Text(item.itemName, style = FocusType.description, color = FocusColors.inkSoft)
                val variant = listOfNotNull(item.variant.configId, item.variant.sizeId, item.variant.colorId, item.variant.styleId)
                    .filter { it.isNotBlank() }.joinToString(" · ")
                if (variant.isNotEmpty()) Text(variant, style = FocusType.rowDetail, color = FocusColors.muted)
            }
            OutlinedTextField(
                value = state.quantity,
                onValueChange = viewModel::setQuantity,
                label = { Text("Quantity") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                shape = FocusShapes.button,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).heightIn(min = 60.dp),
            )
            FocusCaption("Unit", Modifier.padding(start = 20.dp, top = 8.dp, bottom = 4.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 20.dp),
            ) {
                state.units.forEach { u ->
                    com.d365fo.mobileops.presentation.common.focus.FocusChip(u, selected = u.equals(state.unit, ignoreCase = true), onClick = { viewModel.setUnit(u) })
                }
            }
            FocusButton(
                "Check price",
                onClick = viewModel::checkPrice,
                primary = true,
                enabled = !state.busy && state.unit.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            )
            if (quote != null) {
                FocusCaption("Price", Modifier.padding(start = 20.dp, top = 8.dp, bottom = 4.dp))
                FocusListRow(title = money(quote.currency, quote.unitPrice), subtitle = "Unit price, per ${quote.unitId}")
                FocusListRow(title = money(quote.currency, quote.grossAmount), subtitle = "${plainNumber(quote.quantity)} ${quote.unitId} before discount")
                FocusListRow(
                    title = if (quote.discountAmount == 0.0) "None" else "- " + money(quote.currency, quote.discountAmount) +
                        " (" + plainNumber(Math.round(quote.discountPct * 100) / 100.0) + "%)",
                    subtitle = "Discount" + if (quote.offers.isNotBlank()) " · ${quote.offers}" else "",
                )
                FocusListRow(title = money(quote.currency, quote.netAmount), subtitle = "To pay" + if (quote.priceIncludesTax) ", tax included" else ", before tax")
                if (quote.store.isNotBlank()) FocusListRow(title = quote.store, subtitle = "Store")
            }
        }
    }

    if (showCamera) {
        CameraScannerSheet(
            onDecoded = {
                viewModel.onCameraDecoded(it)
                showCamera = false
            },
            onDismiss = { showCamera = false },
        )
    }
}

private fun plainNumber(value: Double): String = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
