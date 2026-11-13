package com.d365fo.mobileops.presentation.modules.cyclecounting.counts

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.d365fo.mobileops.data.counting.CategoryChoice
import com.d365fo.mobileops.data.counting.CountRequestRepository
import com.d365fo.mobileops.data.counting.CountType
import com.d365fo.mobileops.data.repository.WorkerSessionRepository
import com.d365fo.mobileops.presentation.common.focus.BandKind
import com.d365fo.mobileops.presentation.common.focus.BandState
import com.d365fo.mobileops.presentation.common.focus.FocusButton
import com.d365fo.mobileops.presentation.common.focus.FocusCaption
import com.d365fo.mobileops.presentation.common.focus.FocusListRow
import com.d365fo.mobileops.presentation.common.focus.FocusScreen
import com.d365fo.mobileops.presentation.common.focus.RowState
import com.d365fo.mobileops.presentation.common.focus.ScanPillField
import com.d365fo.mobileops.presentation.common.scan.HidScannerBridge
import com.d365fo.mobileops.presentation.common.theme.FocusColors
import com.d365fo.mobileops.presentation.common.theme.FocusType
import com.d365fo.mobileops.util.NetworkResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CountCreateState(
    val type: CountType = CountType.BLIND,
    val warehouseId: String = "",
    /** Categories chosen: all their items (and sub-categories') go into the count. */
    val categories: List<CategoryChoice> = emptyList(),
    /** Item numbers scanned or typed - a barcode is turned into its item number first. */
    val items: List<String> = emptyList(),
    /** Names of the items above, from the device's item list (missing = not on the device). */
    val itemNames: Map<String, String> = emptyMap(),
    /** The category browser: the path opened so far; null = not browsing. */
    val path: List<CategoryChoice>? = null,
    val rows: List<CategoryChoice> = emptyList(),
    val loading: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    /** What D365FO answered once the count was created. */
    val created: String? = null,
) {
    val browsing: Boolean get() = path != null
    val canCreate: Boolean get() = !busy && created == null &&
        (type == CountType.WALL_TO_WALL || categories.isNotEmpty() || items.isNotEmpty())
}

@HiltViewModel
class CountCreateViewModel @Inject constructor(
    private val requests: CountRequestRepository,
    private val scannerBridge: HidScannerBridge,
    private val itemDao: com.d365fo.mobileops.data.local.dao.ItemDao,
    workerSession: WorkerSessionRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val _state = MutableStateFlow(
        CountCreateState(
            type = CountType.fromRoute(savedStateHandle.get<String>("type")) ?: CountType.BLIND,
            warehouseId = workerSession.session.value?.selected?.warehouseId.orEmpty(),
        ),
    )
    val state = _state.asStateFlow()

    init {
        // A scanned or typed code adds an item (not while browsing categories, nor once created).
        viewModelScope.launch {
            scannerBridge.scans.collect { scan ->
                val s = _state.value
                if (s.browsing || s.created != null || s.type == CountType.WALL_TO_WALL) return@collect
                val raw = scan.value.trim()
                if (raw.isBlank()) return@collect
                // D365FO takes item numbers only: a barcode becomes its item here (user, 14 Nov).
                // Unknown to the device: sent as typed - D365FO names it if it is no item there either.
                val byBarcode = itemDao.resolveBarcode(raw)
                val item = byBarcode?.let { it.itemId to it.itemName }
                    ?: itemDao.getItem(raw.uppercase())?.let { it.itemId to it.itemName }
                val id = item?.first ?: raw.uppercase()
                if (id !in s.items) {
                    _state.update {
                        it.copy(
                            items = it.items + id,
                            itemNames = if (item != null) it.itemNames + (id to item.second) else it.itemNames,
                            error = null,
                        )
                    }
                }
            }
        }
    }

    /** Opens the category browser at the first level, or a category's sub-categories. */
    fun browse(into: CategoryChoice? = null) {
        val path = if (into == null) emptyList() else _state.value.path.orEmpty() + into
        load(path)
    }

    /** One level up; above the first level closes the browser. */
    fun up() {
        val path = _state.value.path ?: return
        if (path.isEmpty()) _state.update { it.copy(path = null, rows = emptyList()) } else load(path.dropLast(1))
    }

    private fun load(path: List<CategoryChoice>) {
        _state.update { it.copy(path = path, rows = emptyList(), loading = true, error = null) }
        viewModelScope.launch {
            when (val r = requests.categories(_state.value.warehouseId, path.lastOrNull()?.id.orEmpty())) {
                is NetworkResult.Success -> _state.update { it.copy(loading = false, rows = r.data) }
                is NetworkResult.Error -> _state.update { it.copy(loading = false, error = r.failure.message) }
                NetworkResult.Loading -> Unit
            }
        }
    }

    /** Adds or removes a category; a sub-category of a chosen one is already in it. */
    fun toggle(category: CategoryChoice) = _state.update { s ->
        val chosen = s.categories.any { it.id == category.id }
        s.copy(categories = if (chosen) s.categories.filterNot { it.id == category.id } else s.categories + category)
    }

    fun removeItem(itemId: String) = _state.update { it.copy(items = it.items - itemId) }

    fun create() {
        val s = _state.value
        if (!s.canCreate) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val r = requests.create(s.type, s.warehouseId, s.categories.map { it.id }, s.items)) {
                is NetworkResult.Success -> _state.update {
                    it.copy(
                        busy = false,
                        created = r.data.message.ifBlank { "${r.data.masterId} created." } +
                            if (r.data.existing) " It was already open - counters keep counting it." else "",
                    )
                }
                is NetworkResult.Error -> _state.update { it.copy(busy = false, error = r.failure.message) }
                NetworkResult.Loading -> Unit
            }
        }
    }
}

/**
 * A cycle count supervisor's new count from the handheld (user, 10 Nov): a Stock count control
 * center record every counter of the warehouse sees in the Count menu. Blind and hazard by
 * category (all the category's items, and its sub-categories') or item; wall to wall opens the
 * warehouse's count. No one is assigned - the supervisor hands out the work.
 */
@Composable
fun CountCreateScreen(onBack: () -> Unit, viewModel: CountCreateViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (state.browsing) {
        BackHandler { viewModel.up() }
        CategoryBrowser(state, onUp = viewModel::up, onOpen = viewModel::browse, onToggle = viewModel::toggle)
        return
    }
    val wallToWall = state.type == CountType.WALL_TO_WALL
    FocusScreen(
        title = if (wallToWall) "Open wall to wall count" else "New ${state.type.label.lowercase()}",
        onBack = onBack,
        counter = if (wallToWall) null else (state.categories.size + state.items.size).toString(),
        band = when {
            state.created != null -> BandState(BandKind.Ok, "Created", state.created)
            state.error != null -> BandState(BandKind.Bad, "Not created", state.error)
            wallToWall -> BandState(BandKind.Info, state.warehouseId, "Every counter of ${state.warehouseId} counts it, location by location.")
            else -> BandState(BandKind.Info, state.warehouseId, "Add categories, or scan items. Every counter here sees the count.")
        },
        scanPill = if (!wallToWall && state.created == null) { { ScanPillField(label = "Scan or type an item", enabled = !state.busy) } } else null,
        actions = {
            if (state.created != null) {
                FocusButton("Done", onClick = onBack, primary = true, modifier = Modifier.weight(1f))
            } else {
                if (!wallToWall) {
                    FocusButton("Add category", onClick = { viewModel.browse() }, icon = Icons.Default.Add,
                        enabled = !state.busy, modifier = Modifier.weight(1f))
                }
                FocusButton(
                    if (state.busy) "Creating…" else if (wallToWall) "Open" else "Create",
                    onClick = viewModel::create,
                    primary = true,
                    enabled = state.canCreate,
                    modifier = Modifier.weight(1f),
                )
            }
        },
    ) {
        if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp))
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (state.categories.isNotEmpty()) {
                item(key = "cat-caption") { FocusCaption("Categories · all their items", Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)) }
                items(state.categories, key = { "cat-" + it.id }) { c ->
                    Removable(enabled = state.created == null, onRemove = { viewModel.toggle(c) }) {
                        FocusListRow(title = c.name, subtitle = itemsText(c.itemCount), state = RowState.Open, modifier = Modifier.weight(1f))
                    }
                }
            }
            if (state.items.isNotEmpty()) {
                item(key = "item-caption") { FocusCaption("Items", Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)) }
                items(state.items, key = { "item-$it" }) { id ->
                    Removable(enabled = state.created == null, onRemove = { viewModel.removeItem(id) }) {
                        FocusListRow(
                            title = id,
                            titleIsCode = true,
                            subtitle = state.itemNames[id]?.ifBlank { null } ?: "Not in this device's item list",
                            state = RowState.Open,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            if (!wallToWall && state.categories.isEmpty() && state.items.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "Nothing chosen yet.",
                        style = FocusType.description,
                        color = FocusColors.muted,
                        modifier = Modifier.padding(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Removable(enabled: Boolean, onRemove: () -> Unit, row: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        row()
        if (enabled) {
            IconButton(onClick = onRemove, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Remove", tint = FocusColors.muted)
            }
        }
    }
}

private fun itemsText(n: Int) = when (n) {
    0 -> "No items"
    1 -> "1 item"
    else -> "$n items"
}

/** The retail product hierarchy, level by level: tap opens a category, Add takes it whole. */
@Composable
private fun CategoryBrowser(
    state: CountCreateState,
    onUp: () -> Unit,
    onOpen: (CategoryChoice) -> Unit,
    onToggle: (CategoryChoice) -> Unit,
) {
    val here = state.path?.lastOrNull()
    FocusScreen(
        title = here?.name ?: "Categories",
        onBack = onUp,
        counter = if (state.loading) null else state.rows.size.toString(),
        band = when {
            state.error != null -> BandState(BandKind.Bad, "Not loaded", state.error)
            state.loading -> BandState(BandKind.Info, "Loading", "Asking D365FO for the categories")
            else -> null
        },
        actions = if (here == null) null else { {
            val chosen = state.categories.any { it.id == here.id }
            FocusButton(
                if (chosen) "Remove ${here.name}" else "Add all of ${here.name} (${itemsText(here.itemCount).lowercase()})",
                onClick = { onToggle(here) },
                primary = !chosen,
                modifier = Modifier.weight(1f),
            )
        } },
    ) {
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(state.rows, key = { it.id }) { c ->
                val chosen = state.categories.any { it.id == c.id }
                FocusListRow(
                    title = c.name,
                    state = if (chosen) RowState.Done else RowState.Open,
                    subtitle = itemsText(c.itemCount) + " · " + if (c.hasChildren) "tap to see its sub-categories" else "tap to add",
                    count = if (chosen) "Added" else null,
                    chevron = c.hasChildren,
                    onClick = { if (c.hasChildren) onOpen(c) else onToggle(c) },
                )
            }
        }
    }
}
