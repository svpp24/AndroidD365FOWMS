package com.d365fo.mobileops.presentation.common.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors

/** One choice in a [DropdownField]: [id] is handed back, [label] is shown. */
data class DropdownOption(val id: String, val label: String)

/**
 * The app's one way to pick a single value from a list too long for chips - TO creation's From
 * and To warehouses, an item's units when it has more than a few. A full-width outlined field
 * showing the current choice (wrapped, never cut off) that opens a menu of the rest.
 */
@Composable
fun DropdownField(
    options: List<DropdownOption>,
    selectedId: String?,
    placeholder: String,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** Put before the selected label, e.g. "Unit: " - so the field says what it chooses. */
    prefix: String = "",
) {
    var open by remember { mutableStateOf(false) }
    val selected = options.firstOrNull { it.id.equals(selectedId, ignoreCase = true) }
    Box(modifier = modifier) {
        DropdownTrigger(text = selected?.let { prefix + it.label }, placeholder = placeholder, onClick = { open = true })
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.fillMaxWidth(0.9f),
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            option.label,
                            fontWeight = if (option.id.equals(selectedId, ignoreCase = true)) FontWeight.SemiBold else null,
                        )
                    },
                    onClick = { open = false; onPick(option.id) },
                    modifier = Modifier.heightIn(min = WarehouseDimens.MinTouchTarget),
                )
            }
        }
    }
}

/**
 * The closed look of a dropdown - also for a choice made in a dialog of its own (On-hand's
 * searchable warehouse list). Styled as a field, not a button: grey outline, the choice in body
 * text, the placeholder dimmed - a red outline read as one more action to press.
 */
@Composable
fun DropdownTrigger(text: String?, placeholder: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    SquareOutlinedButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = WarehouseDimens.MinTouchTarget),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
        border = BorderStroke(1.dp, WarehouseStatusColors.border),
    ) {
        Text(
            text = text ?: placeholder,
            style = MaterialTheme.typography.bodyLarge,
            color = if (text == null) WarehouseStatusColors.text3 else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Choose")
    }
}
