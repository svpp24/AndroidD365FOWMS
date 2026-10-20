package com.d365fo.mobileops.presentation.common.components

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import com.d365fo.mobileops.presentation.common.components.SquareButton as Button
import com.d365fo.mobileops.presentation.common.components.SquareTextButton as TextButton
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.d365fo.mobileops.R
import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors
import java.math.BigDecimal

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

/**
 * A page-level "this failed" message - solid red fill, bold white text, matching the amber
 * connectivity strips ([com.d365fo.mobileops.presentation.common.AppNavGraph]'s status bars) in
 * visual weight. Red rather than amber: this app already reserves red for a genuine failure that
 * needs a person's attention (see the Activity tab's own failed-posting badge), as opposed to
 * going offline, which queues itself and clears on its own.
 *
 * Replaces the `errorContainer`/`onErrorContainer` Card this app used to show `state.error` in
 * everywhere - Material3's own pale error tint read as too easy to miss on a screen a scan-first
 * workflow expects a person to glance past quickly, the same problem the connectivity strips had.
 */
@Composable
fun ErrorBanner(
    message: String,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .background(WarehouseStatusColors.red, RoundedCornerShape(WarehouseDimens.CardCorner))
            .padding(horizontal = WarehouseDimens.ScreenPadding, vertical = 10.dp),
    ) {
        Icon(
            Icons.Default.Error,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.weight(1f),
        )
        if (onDismiss != null) {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
            ) {
                Text("OK")
            }
        }
    }
}

/**
 * Same shape as [ErrorBanner], amber instead of red - a notice worth a person's attention but
 * that never blocked anything, e.g. a license nearing its expiry (see
 * [com.d365fo.mobileops.data.repository.WorkerSessionRepository.licenseWarning]). Always
 * dismissible - unlike a real error, there's nothing to retry, just something to note and clear.
 */
@Composable
fun WarningBanner(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .background(WarehouseStatusColors.amber, RoundedCornerShape(WarehouseDimens.CardCorner))
            .padding(horizontal = WarehouseDimens.ScreenPadding, vertical = 10.dp),
    ) {
        Icon(
            Icons.Default.Warning,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = onDismiss,
            colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
        ) {
            Text("OK")
        }
    }
}

/** Same shape as [ErrorBanner], green: something finished as intended. Dismissible. */
@Composable
fun SuccessBanner(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .background(WarehouseStatusColors.green, RoundedCornerShape(WarehouseDimens.CardCorner))
            .padding(horizontal = WarehouseDimens.ScreenPadding, vertical = 10.dp),
    ) {
        Icon(
            Icons.Default.CheckCircle,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = onDismiss,
            colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
        ) {
            Text("OK")
        }
    }
}
