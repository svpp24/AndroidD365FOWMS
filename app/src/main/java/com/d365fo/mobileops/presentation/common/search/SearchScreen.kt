package com.d365fo.mobileops.presentation.common.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.d365fo.mobileops.data.repository.SearchHit
import com.d365fo.mobileops.data.repository.SearchHitKind
import com.d365fo.mobileops.presentation.common.components.AppTopBar
import com.d365fo.mobileops.presentation.common.components.ErrorBanner
import com.d365fo.mobileops.presentation.common.components.HeroCameraButton
import com.d365fo.mobileops.presentation.common.components.HeroEnterBadge
import com.d365fo.mobileops.presentation.common.components.HeroFieldShape
import com.d365fo.mobileops.presentation.common.components.LineIcons
import com.d365fo.mobileops.presentation.common.components.heroFieldColors
import com.d365fo.mobileops.presentation.common.scan.CameraScannerSheet
import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors
import com.d365fo.mobileops.util.Formatters

private val CardShape = RoundedCornerShape(12.dp)

/**
 * Home's search: an item, PO, transfer or counting journal - by number, barcode or (items) name.
 * An item shows its stock here and every open order and journal holding it; tapping an order
 * opens it in its flow, a journal opens the counting journals.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onOpenReceiving: () -> Unit,
    onOpenShip: () -> Unit,
    onOpenTransferReceive: () -> Unit,
    onOpenJournals: () -> Unit,
    onOpenJournalLines: (String) -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showCamera by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    // A finished search closes the keyboard - a hardware scan left it open over the results.
    LaunchedEffect(state.busy, state.searched) {
        if (!state.busy && state.searched != null) {
            keyboard?.hide()
            focusManager.clearFocus()
        }
    }
    DisposableEffect(Unit) {
        viewModel.setScannerInterception(true)
        onDispose { viewModel.setScannerInterception(true) }
    }
    val open: (SearchHit) -> Unit = { hit ->
        if (viewModel.prepareOpen(hit)) {
            when (hit.kind) {
                SearchHitKind.PO -> onOpenReceiving()
                SearchHitKind.TO -> if (hit.outbound) onOpenShip() else onOpenTransferReceive()
                SearchHitKind.JOURNAL -> viewModel.openJournal(hit.reference) { onDevice ->
                    if (onDevice) onOpenJournalLines(hit.reference) else onOpenJournals()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = { Text("Search") },
                expandedHeight = WarehouseDimens.TopBarHeight,
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "field") {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth().background(Color.White, CardShape).padding(14.dp),
                ) {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = viewModel::setQuery,
                        singleLine = true,
                        placeholder = { Text("Item, barcode, PO, transfer or journal") },
                        leadingIcon = { Icon(LineIcons.SearchThin, contentDescription = null, tint = WarehouseStatusColors.text3) },
                        trailingIcon = {
                            Box(Modifier.clickable(role = Role.Button, onClickLabel = "Search") { keyboard?.hide(); viewModel.submit() }) {
                                HeroEnterBadge()
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); viewModel.submit() }),
                        shape = HeroFieldShape,
                        colors = heroFieldColors(),
                        textStyle = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).focusRequester(focus),
                    )
                    HeroCameraButton(onClick = { showCamera = true })
                }
            }
            item(key = "busy") {
                Box(Modifier.fillMaxWidth().height(4.dp)) {
                    if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            state.error?.let { message -> item(key = "error") { ErrorBanner(message = message) } }
            state.notice?.let { message ->
                item(key = "notice") {
                    Text(
                        message,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth().background(WarehouseStatusColors.accentDim, CardShape).padding(14.dp),
                    )
                }
            }

            if (state.suggestions.isNotEmpty()) {
                item(key = "sugg-title") {
                    SectionTitle(if (state.searched != null && state.result == null) "Did you mean" else "Items")
                }
                item(key = "sugg") {
                    Column(Modifier.fillMaxWidth().background(Color.White, CardShape)) {
                        state.suggestions.forEachIndexed { i, s ->
                            if (i > 0) HorizontalDivider(color = WarehouseStatusColors.border.copy(alpha = 0.45f), modifier = Modifier.padding(horizontal = 14.dp))
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(role = Role.Button) { keyboard?.hide(); viewModel.submit(s.itemId) }
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                            ) {
                                Text(s.itemId, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                Text(s.itemName, style = MaterialTheme.typography.bodyMedium, color = WarehouseStatusColors.text3)
                            }
                        }
                    }
                }
            } else {
                val result = state.result
                if (result != null) {
                    if (result.itemId != null) {
                        item(key = "item") { ItemCard(result.itemId, result.itemName, result.unitId, state.available, state.onHand) }
                    }
                    val groups = listOf(
                        Triple(SearchHitKind.PO, "Purchase orders", LineIcons.Receive),
                        Triple(SearchHitKind.TO, "Transfer orders", LineIcons.Ship),
                        Triple(SearchHitKind.JOURNAL, "Counting journals", LineIcons.Count),
                    )
                    for ((kind, title, icon) in groups) {
                        val hits = result.hits.filter { it.kind == kind }
                        if (hits.isEmpty()) continue
                        item(key = "t-$kind") { SectionTitle("$title (${hits.size})") }
                        // By position: one journal can hold the item twice at a location (batches),
                        // so reference and detail are not unique (crashed, 20 Oct).
                        items(hits.size, key = { i -> "h-$kind-$i" }) { i ->
                            HitRow(hits[i], icon, onClick = { open(hits[i]) })
                        }
                    }
                    if (result.itemId == null && result.hits.isEmpty()) {
                        item(key = "none") {
                            Text(
                                "Nothing found for ${state.searched}.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = WarehouseStatusColors.text3,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    } else if (result.itemId != null && result.hits.isEmpty()) {
                        item(key = "no-work") {
                            Text(
                                "No open orders or counts.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = WarehouseStatusColors.text3,
                            )
                        }
                    }
                } else if (state.searched == null && !state.busy) {
                    item(key = "help") {
                        Text(
                            "Scan or type an item, PO, transfer or journal.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = WarehouseStatusColors.text3,
                        )
                    }
                }
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

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun ItemCard(itemId: String, itemName: String?, unitId: String?, available: java.math.BigDecimal?, onHand: java.math.BigDecimal?) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth().background(Color.White, CardShape).padding(14.dp),
    ) {
        Text(itemId, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
        itemName?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), modifier = Modifier.padding(top = 6.dp)) {
            Figure("Available", available?.let { Formatters.qtyUnit(it, unitId) } ?: "–")
            Figure("On hand", onHand?.let { Formatters.qtyUnit(it, unitId) } ?: "–")
        }
    }
}

@Composable
private fun Figure(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = WarehouseStatusColors.text3)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun HitRow(hit: SearchHit, icon: ImageVector, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White, CardShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(14.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(32.dp).background(tintOf(hit.kind), RoundedCornerShape(8.dp)),
        ) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(17.dp))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(hit.reference, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            if (hit.title.isNotBlank()) Text(hit.title, style = MaterialTheme.typography.bodyMedium)
            val detail = if (hit.kind == SearchHitKind.PO && hit.orderMatch && hit.detail.isNotBlank()) "Vendor ${hit.detail}" else hit.detail
            val meta = listOf(detail, statusLabel(hit), hit.date).filter { it.isNotBlank() }.joinToString("  ·  ")
            if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.bodySmall, color = WarehouseStatusColors.text3)
        }
        if (!hit.orderMatch) {
            val qty = Formatters.qtyUnit(java.math.BigDecimal.valueOf(hit.quantity).stripTrailingZeros(), hit.unitId.ifBlank { null })
            Text(
                if (hit.kind == SearchHitKind.JOURNAL) "$qty counted" else qty,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

private fun statusLabel(hit: SearchHit): String = when (hit.status) {
    "Backorder" -> "Open"
    "Created" -> "To ship"
    "Shipped" -> if (hit.outbound) "Shipped" else "To receive"
    else -> if (hit.kind == SearchHitKind.TO && hit.status == hit.detail) "" else hit.status
}.let { if (hit.kind == SearchHitKind.TO && !hit.orderMatch) "" else it }

private fun tintOf(kind: SearchHitKind): Color = when (kind) {
    SearchHitKind.PO -> Color(0xFF1D63D6)
    SearchHitKind.TO -> Color(0xFF0E7F74)
    SearchHitKind.JOURNAL -> Color(0xFF2E7D4F)
}
