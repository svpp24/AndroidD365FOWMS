package com.d365fo.mobileops.presentation.common.focus

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import com.d365fo.mobileops.presentation.common.focus.TaskIcons
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.d365fo.mobileops.presentation.common.theme.FocusColors
import com.d365fo.mobileops.presentation.common.theme.FocusType
import com.d365fo.mobileops.presentation.modules.ordercreate.TapToTypeField

/** One row of [PickItemPage]: the item, what it is, and a figure on the right ("48 / 48"). */
data class PickRow(
    val key: String,
    val itemId: String,
    val name: String,
    val variant: String = "",
    val figure: String? = null,
    /** True when [query] matches something else of the row - its variant ("Red", "C2"). */
    val extraMatch: (String) -> Boolean = { false },
)

/**
 * A document's items as a page, not a pop-up (user, 17 Nov 2026): "Pick an item", then "Search
 * item or barcode" and the rows. PO receiving's "PO items" and the transfers' "Transfer items".
 */
@Composable
fun PickItemPage(
    title: String,
    rows: List<PickRow>,
    onPick: (PickRow) -> Unit,
    onClose: () -> Unit,
    empty: String = "Nothing left on this document",
) {
    androidx.activity.compose.BackHandler(onBack = onClose)
    var query by remember { mutableStateOf("") }
    val barcodeItem = rememberBarcodeItemId(query)
    val needle = query.trim()
    val shown = if (needle.isEmpty()) rows else rows.filter {
        it.itemId.contains(needle, ignoreCase = true) ||
            it.name.contains(needle, ignoreCase = true) ||
            it.extraMatch(needle) ||
            (barcodeItem != null && it.itemId.equals(barcodeItem, ignoreCase = true))
    }
    FocusScreen(title = title, onBack = onClose, counter = rows.size.toString()) {
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            item(key = "task") { ListTaskBox("Pick an item", TaskIcons.Pick) }
            item(key = "search") {
                TapToTypeField(query, { query = it }, ITEM_OR_BARCODE, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            }
            if (shown.isEmpty()) {
                item(key = "none") {
                    Text(
                        if (needle.isEmpty()) empty else "Nothing here matches \"$needle\"",
                        style = FocusType.rowDetail,
                        color = FocusColors.muted,
                        modifier = Modifier.padding(20.dp),
                    )
                }
            }
            items(shown, key = { it.key }) { row ->
                FocusListRow(
                    title = row.itemId,
                    titleIsCode = true,
                    subtitle = listOf(row.name, row.variant).filter { it.isNotBlank() }.joinToString(" · "),
                    count = row.figure,
                    countColor = FocusColors.ink,
                    onClick = { onPick(row) },
                )
            }
        }
    }
}
