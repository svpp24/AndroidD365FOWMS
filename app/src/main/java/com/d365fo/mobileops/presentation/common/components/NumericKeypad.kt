package com.d365fo.mobileops.presentation.common.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors

/**
 * A tap-only numeric pad - the same "type the number, don't fiddle with the OS keyboard" pattern
 * a WMS mobile app's own quantity screens use. Digits, one decimal point, backspace - nothing any
 * quantity entry in this app needs beyond that.
 *
 * Was duplicated private in `ReceivingFlowScreen.kt` and `TransferFlowScreen.kt` (identical, down
 * to the doc comment); extracted here once both flows' dedicated quantity-entry screens needed it.
 */
@Composable
fun NumericKeypad(
    enabled: Boolean,
    onDigit: (Char) -> Unit,
    onDecimal: () -> Unit,
    onBackspace: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf('1' to '3', '4' to '6', '7' to '9').forEach { (first, last) ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                (first..last).forEach { digit ->
                    KeypadButton(
                        text = digit.toString(),
                        enabled = enabled,
                        onClick = { onDigit(digit) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            KeypadButton(text = ".", enabled = enabled, onClick = onDecimal, modifier = Modifier.weight(1f))
            KeypadButton(text = "0", enabled = enabled, onClick = { onDigit('0') }, modifier = Modifier.weight(1f))
            KeypadButton(
                icon = Icons.AutoMirrored.Filled.Backspace,
                enabled = enabled,
                onClick = onBackspace,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun KeypadButton(
    modifier: Modifier = Modifier,
    text: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .background(WarehouseStatusColors.surface2, RoundedCornerShape(WarehouseDimens.CardCorner))
            .border(
                BorderStroke(1.dp, WarehouseStatusColors.border),
                RoundedCornerShape(WarehouseDimens.CardCorner),
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (text != null) {
            Text(
                text = text,
                style = MaterialTheme.typography.headlineSmall,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else WarehouseStatusColors.text3,
            )
        }
        icon?.let {
            Icon(
                it,
                contentDescription = "Delete digit",
                tint = if (enabled) MaterialTheme.colorScheme.onSurface else WarehouseStatusColors.text3,
            )
        }
    }
}
