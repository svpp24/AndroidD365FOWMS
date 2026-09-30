package com.d365fo.mobileops.presentation.auth

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.d365fo.mobileops.R
import com.d365fo.mobileops.presentation.common.components.AppCard as Card
import com.d365fo.mobileops.presentation.common.components.ErrorBanner
import com.d365fo.mobileops.presentation.common.components.SquareButton as Button
import com.d365fo.mobileops.presentation.common.scan.CameraScannerSheet
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors
import androidx.compose.material3.CardDefaults
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource

/**
 * One-time device activation: scan the vendor-issued certificate QR, verify it against the
 * compiled-in root key, and store it - see `LicenseRepository.activate` and `AppRoot`'s own
 * gate, which shows this screen exactly when [com.d365fo.mobileops.data.repository.LicenseState]
 * is `NeedsActivation`.
 *
 * The scanned string goes straight from [CameraScannerSheet] to the ViewModel - deliberately
 * NOT through `HidScannerBridge`/`RawScan.normalized()`, which uppercases values and would
 * corrupt this case-sensitive base64url certificate. See `LicenseViewModel.onCertificateScanned`.
 */
@Composable
fun LicenseActivationScreen(viewModel: LicenseViewModel = hiltViewModel()) {
    val activationError by viewModel.activationError.collectAsStateWithLifecycle()
    var showScanner by remember { mutableStateOf(false) }

    if (showScanner) {
        CameraScannerSheet(
            onDecoded = {
                showScanner = false
                viewModel.onCertificateScanned(it)
            },
            onDismiss = { showScanner = false },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Same logo + copyright block WorkerLoginScreen shows - the one visual identity every
        // worker/administrator sees on this device, activated or not, signed in or not.
        Image(
            painter = painterResource(id = R.drawable.ic_app_logo),
            contentDescription = null,
            modifier = Modifier.heightIn(max = 104.dp),
        )

        Text(
            text = stringResource(R.string.copyright_notice),
            style = MaterialTheme.typography.labelSmall,
            color = WarehouseStatusColors.text3,
        )

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "This device needs to be activated",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "Scan the activation QR code your administrator provided. This is a " +
                        "one-time step per device.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }

        activationError?.let {
            ErrorBanner(
                message = it,
                onDismiss = viewModel::dismissActivationError,
                modifier = Modifier.padding(top = 16.dp),
            )
        }

        Button(
            onClick = { showScanner = true },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp),
        ) {
            Text("Scan activation QR code")
        }
    }
}
