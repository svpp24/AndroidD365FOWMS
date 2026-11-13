package com.d365fo.mobileops.presentation.modules.dataenquiry

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.d365fo.mobileops.data.local.dao.CategoryCount
import com.d365fo.mobileops.data.local.dao.ItemDao
import com.d365fo.mobileops.data.local.entity.BarcodeEntity
import com.d365fo.mobileops.data.local.entity.ItemEntity
import com.d365fo.mobileops.data.local.entity.TrackingRequirement
import com.d365fo.mobileops.presentation.common.focus.BandKind
import com.d365fo.mobileops.presentation.common.focus.BandState
import com.d365fo.mobileops.presentation.common.focus.FocusCaption
import com.d365fo.mobileops.presentation.common.focus.FocusListRow
import com.d365fo.mobileops.presentation.common.focus.FocusScreen
import com.d365fo.mobileops.presentation.common.focus.RowState
import com.d365fo.mobileops.presentation.common.theme.FocusColors
import com.d365fo.mobileops.presentation.common.theme.FocusType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Separator of the category path the item list stores ("Health Care > Vitamins"). */
internal const val CATEGORY_SEPARATOR = " > "

/** The path standing for items with no category - never a real category name. */
internal const val NO_CATEGORY = "∅"

/**
 * A category's name as shown: without its parent's name in front. The company's categories repeat
 * it ("Beauty Care.Skin Care" under "Beauty Care"); [parent] is the parent's own stored name.
 */
internal fun shortCategoryName(name: String, parent: String?): String =
    if (parent.isNullOrEmpty()) name else name.removePrefix("$parent.").ifBlank { name }

/** A stored category path as shown: "Beauty Care > Skin Care > Face Care". */
internal fun displayCategoryPath(path: String): String {
    val parts = path.split(CATEGORY_SEPARATOR)
    return parts.mapIndexed { i, part -> shortCategoryName(part, parts.getOrNull(i - 1)) }.joinToString(CATEGORY_SEPARATOR)
}

/** A category under the one being shown, with the active items in it and below it. */
data class CategoryNode(val path: String, val name: String, val itemCount: Int)

/**
 * The categories directly under [parent] ("" = the first level), A to Z, each with every item in
 * it or its sub-categories. Items without a category are one "No category" entry at the first level.
 */
internal fun childCategories(counts: List<CategoryCount>, parent: String): List<CategoryNode> {
    val prefix = if (parent.isEmpty()) "" else parent + CATEGORY_SEPARATOR
    val nodes = counts
        .filter { it.category.isNotEmpty() && it.category.startsWith(prefix) && it.category != parent }
        .groupBy { it.category.removePrefix(prefix).substringBefore(CATEGORY_SEPARATOR) }
        .map { (name, rows) ->
            CategoryNode(prefix + name, shortCategoryName(name, parent.substringAfterLast(CATEGORY_SEPARATOR).ifEmpty { null }), rows.sumOf { it.items })
        }
        .sortedBy { it.name.lowercase() }
    val none = counts.filter { it.category.isEmpty() }.sumOf { it.items }
    return if (parent.isEmpty() && none > 0) nodes + CategoryNode(NO_CATEGORY, "No category", none) else nodes
}

data class ItemCategoryState(
    val path: String = "",
    val loading: Boolean = true,
    val categories: List<CategoryNode> = emptyList(),
    val items: List<ItemEntity> = emptyList(),
    /** The device's item list has items, but none carries a category yet. */
    val noCategoriesYet: Boolean = false,
    val noItems: Boolean = false,
    /** The search box: items of this category and below matching it, instead of the tree. */
    val query: String = "",
    val searching: Boolean = false,
    val results: List<ItemEntity> = emptyList(),
) {
    val searchActive: Boolean get() = query.trim().isNotEmpty()
}

/** Most search results shown; a fuller list means typing more. */
internal const val CATEGORY_SEARCH_LIMIT = 200

@HiltViewModel
class ItemCategoryViewModel @Inject constructor(
    private val itemDao: ItemDao,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val path: String = savedStateHandle.get<String>("path")?.let { android.net.Uri.decode(it) }.orEmpty()
    private val _state = MutableStateFlow(ItemCategoryState(path = path))
    val state = _state.asStateFlow()

    private var searchJob: kotlinx.coroutines.Job? = null

    /** Searches this category and its sub-categories (the whole list at the first level). */
    fun setQuery(query: String) {
        _state.value = _state.value.copy(query = query, searching = query.isNotBlank())
        searchJob?.cancel()
        val q = query.trim()
        if (q.isEmpty()) {
            _state.value = _state.value.copy(results = emptyList(), searching = false)
            return
        }
        searchJob = viewModelScope.launch {
            kotlinx.coroutines.delay(300)
            val escaped = path.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            val results = itemDao.searchInCategory(
                all = path.isEmpty(),
                none = path == NO_CATEGORY,
                path = path,
                below = escaped + CATEGORY_SEPARATOR + "%",
                query = q,
                limit = CATEGORY_SEARCH_LIMIT,
            )
            _state.value = _state.value.copy(results = results, searching = false)
        }
    }

    init {
        viewModelScope.launch {
            val counts = itemDao.categoryCounts()
            val items = when {
                path == NO_CATEGORY -> itemDao.itemsInCategory("")
                path.isNotEmpty() -> itemDao.itemsInCategory(path)
                else -> emptyList()
            }
            _state.value = _state.value.copy(
                path = path,
                loading = false,
                categories = if (path == NO_CATEGORY) emptyList() else childCategories(counts, path),
                items = items,
                noCategoriesYet = path.isEmpty() && counts.isNotEmpty() && counts.all { it.category.isEmpty() },
                noItems = counts.isEmpty(),
            )
        }
    }
}

/**
 * Items by category (user, 12 Nov), from the device's own item list: the retail product
 * hierarchy a level at a time - sub-categories first, then the items placed directly in the
 * category. An item opens its details and barcodes.
 */
@Composable
fun ItemCategoryScreen(
    onBack: () -> Unit,
    onCategory: (String) -> Unit,
    onItem: (String) -> Unit,
    viewModel: ItemCategoryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val title = when (state.path) {
        "" -> "Items by category"
        NO_CATEGORY -> "No category"
        else -> displayCategoryPath(state.path).substringAfterLast(CATEGORY_SEPARATOR)
    }
    FocusScreen(
        title = title,
        onBack = onBack,
        counter = (if (state.searchActive) state.results.size else state.items.size)
            .takeIf { !state.loading && !state.searching && it > 0 }?.toString(),
        band = when {
            state.loading -> null
            state.searchActive -> when {
                state.searching -> null
                state.results.isEmpty() -> BandState(BandKind.Info, "No items match \"${state.query.trim()}\"", if (state.path.isEmpty()) null else "Only this category and its sub-categories are searched.")
                state.results.size >= CATEGORY_SEARCH_LIMIT -> BandState(BandKind.Info, "First $CATEGORY_SEARCH_LIMIT matches", "Type more to narrow the list.")
                else -> null
            }
            state.noItems -> BandState(BandKind.Info, "No items on this device yet", "They arrive with the item list download.")
            state.noCategoriesYet -> BandState(BandKind.Info, "Categories not on this device yet", "They arrive with the next full item list download.")
            state.categories.isEmpty() && state.items.isEmpty() -> BandState(BandKind.Info, "Nothing in this category", null)
            else -> null
        },
    ) {
        if (!state.noItems && !state.noCategoriesYet) {
            com.d365fo.mobileops.presentation.common.focus.FocusSearchField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                placeholder = if (state.path.isEmpty()) "Search items or barcode" else "Search in this category",
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
        if (state.searchActive) {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(state.results, key = { "r:" + it.itemId }) { item ->
                    FocusListRow(
                        title = item.itemName.ifBlank { item.itemId },
                        subtitle = listOfNotNull(
                            item.itemId.takeIf { item.itemName.isNotBlank() },
                            item.category?.takeIf { it.isNotBlank() && it != state.path }?.let {
                                displayCategoryPath(it).substringAfterLast(CATEGORY_SEPARATOR)
                            },
                        ).joinToString(" · ").ifBlank { null },
                        chevron = true,
                        onClick = { onItem(item.itemId) },
                    )
                }
            }
            return@FocusScreen
        }
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (state.path.isNotEmpty() && state.path != NO_CATEGORY) {
                item(key = "path") {
                    Text(
                        displayCategoryPath(state.path),
                        style = FocusType.rowDetail,
                        color = FocusColors.muted,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
            }
            if (state.categories.isNotEmpty() && state.items.isNotEmpty()) {
                item(key = "cap-cat") { FocusCaption("Categories", Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp)) }
            }
            items(state.categories, key = { "c:" + it.path }) { node ->
                FocusListRow(
                    title = node.name,
                    count = node.itemCount.toString(),
                    chevron = true,
                    onClick = { onCategory(node.path) },
                )
            }
            if (state.categories.isNotEmpty() && state.items.isNotEmpty()) {
                item(key = "cap-items") { FocusCaption("Items", Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp)) }
            }
            items(state.items, key = { "i:" + it.itemId }) { item ->
                FocusListRow(
                    title = item.itemName.ifBlank { item.itemId },
                    subtitle = item.itemId.takeIf { item.itemName.isNotBlank() },
                    chevron = true,
                    onClick = { onItem(item.itemId) },
                )
            }
        }
    }
}

data class ItemDetailState(
    val loading: Boolean = true,
    val item: ItemEntity? = null,
    val barcodes: List<BarcodeEntity> = emptyList(),
)

@HiltViewModel
class ItemDetailViewModel @Inject constructor(
    private val itemDao: ItemDao,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val itemId: String = savedStateHandle.get<String>("itemId")?.let { android.net.Uri.decode(it) }.orEmpty()
    private val _state = MutableStateFlow(ItemDetailState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.value = ItemDetailState(loading = false, item = itemDao.getItem(itemId), barcodes = itemDao.barcodesOf(itemId))
        }
    }
}

/** One item from the device's item list: what the handheld knows of it, then its barcodes. */
@Composable
fun ItemDetailScreen(
    onBack: () -> Unit,
    viewModel: ItemDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val item = state.item
    FocusScreen(
        title = "Item",
        onBack = onBack,
        band = if (!state.loading && item == null) {
            BandState(BandKind.Bad, "Not on this device", "Item ${viewModel.itemId} is not in the item list.")
        } else {
            null
        },
    ) {
        if (item == null) return@FocusScreen
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            item(key = "head") {
                androidx.compose.foundation.layout.Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Text(item.itemId, style = FocusType.rowCode, color = FocusColors.ink)
                    if (item.itemName.isNotBlank()) Text(item.itemName, style = FocusType.description, color = FocusColors.inkSoft)
                }
            }
            item(key = "facts") {
                FocusCaption("Details", Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp))
                fact("Category", item.category?.let(::displayCategoryPath) ?: "None")
                fact("Inventory unit", item.inventoryUnit.ifBlank { "-" })
                item.purchaseUnit?.let { fact("Purchase unit", it) }
                item.salesUnit?.let { fact("Sales unit", it) }
                fact(
                    "Tracking",
                    when (item.trackingRequirement) {
                        TrackingRequirement.NONE -> "None"
                        TrackingRequirement.BATCH -> "Batch"
                        TrackingRequirement.SERIAL -> "Serial"
                        TrackingRequirement.BATCH_AND_SERIAL -> "Batch and serial"
                    },
                )
                fact("Location", if (item.locationMandatory) "Required" else "Not required")
                fact("Status", if (item.active) "Active" else "Stopped")
                FocusCaption(
                    "Barcodes · ${state.barcodes.size}",
                    Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
                )
                if (state.barcodes.isEmpty()) {
                    Text(
                        "No barcodes for this item",
                        style = FocusType.rowDetail,
                        color = FocusColors.muted,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
            }
            items(state.barcodes, key = { "b:" + it.barcode }) { b ->
                val variant = listOfNotNull(b.configId, b.sizeId, b.colorId, b.styleId).filter { it.isNotBlank() }.joinToString(" · ")
                FocusListRow(
                    title = b.barcode,
                    titleIsCode = true,
                    state = if (b.active) RowState.Plain else RowState.Warn,
                    subtitle = listOfNotNull(
                        "${b.quantityPerScan.removeSuffix(".0")} ${b.unitId ?: item.inventoryUnit}".trim(),
                        variant.takeIf { it.isNotEmpty() },
                    ).joinToString(" · "),
                    count = if (b.active) null else "Blocked",
                )
            }
        }
    }
}

@Composable
private fun fact(label: String, value: String) {
    FocusListRow(title = value, subtitle = label)
}
