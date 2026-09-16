package com.d365fo.mobileops.presentation.common.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Sync
import com.d365fo.mobileops.presentation.common.components.SquareButton as Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.d365fo.mobileops.R
import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens
import java.math.BigDecimal

/**
 * Quantity entry: a numeric field flanked by large ±1 steppers.
 *
 * The steppers exist because the overwhelmingly common corrections in receiving are "one
 * more" and "one fewer", and hitting a soft-keyboard digit with a glove on is unreliable.
 * The field itself stays editable for bulk quantities.
 */
@Composable
fun QuantityStepper(
    value: String,
    onValueChange: (String) -> Unit,
    unit: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    supportingText: String? = null,
    enabled: Boolean = true,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(WarehouseDimens.ItemSpacing),
        ) {
            StepperButton(
                icon = Icons.Default.Remove,
                contentDescription = stringResource(R.string.qty_decrement),
                enabled = enabled,
                onClick = { onValueChange(value.stepBy(BigDecimal.ONE.negate())) },
            )

            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                enabled = enabled,
                isError = isError,
                singleLine = true,
                textStyle = MaterialTheme.typography.headlineMedium.copy(
                    textAlign = TextAlign.Center,
                    // Monospaced digits so the value does not jitter horizontally as it
                    // changes — a moving number is misread far more often than a still one.
                    fontFamily = FontFamily.Monospace,
                ),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Done,
                ),
                suffix = { Text(unit, style = MaterialTheme.typography.titleMedium) },
            )

            StepperButton(
                icon = Icons.Default.Add,
                contentDescription = stringResource(R.string.qty_increment),
                enabled = enabled,
                onClick = { onValueChange(value.stepBy(BigDecimal.ONE)) },
            )
        }

        if (supportingText != null) {
            Text(
                text = supportingText,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

private fun String.stepBy(delta: BigDecimal): String {
    val current = trim().replace(',', '.').toBigDecimalOrNull() ?: BigDecimal.ZERO
    // Never step below zero — a negative received quantity is meaningless here, and the
    // validator would reject it anyway, so the control should not offer it.
    return (current + delta).coerceAtLeast(BigDecimal.ZERO).stripTrailingZeros().toPlainString()
}

@Composable
private fun StepperButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(WarehouseDimens.CardCorner),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        modifier = Modifier
            .size(WarehouseDimens.StepperButton)
            .semantics { this.contentDescription = contentDescription },
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(32.dp))
    }
}

/** One task per screen, with an explicit "Step X of N" so a worker never loses their place. */
@Composable
fun StepIndicator(
    currentStep: Int,
    totalSteps: Int,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.step_x_of_n, currentStep, totalSteps),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LinearProgressIndicator(
            progress = { currentStep.toFloat() / totalSteps.coerceAtLeast(1) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .height(6.dp),
        )
    }
}

/**
 * A large read-only value display for the fields that must not be misread: item number,
 * order number, quantity.
 */
@Composable
fun CriticalField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    accent: Color? = null,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            color = accent ?: MaterialTheme.colorScheme.onSurface,
            fontFamily = FontFamily.Monospace,
        )
    }
}
