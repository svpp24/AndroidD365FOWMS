package com.d365fo.mobileops.presentation.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import com.d365fo.mobileops.presentation.common.components.SquareButton as Button
import com.d365fo.mobileops.presentation.common.components.AppCard as Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.d365fo.mobileops.R
import com.d365fo.mobileops.data.security.TenantConfig

/**
 * Shown when the device cannot reach D365FO at all.
 *
 * There is deliberately no worker sign-in button here. The device authenticates as itself,
 * through the one-time device-code sign-in done during commissioning (see
 * DeviceCodeTokenProvider), so an ordinary worker cannot fix this by signing in as anybody — the
 * only thing that resolves it is fixing the connection or redoing that device-code sign-in, both
 * in Settings. Offering a worker login control would be offering a button that authenticates
 * nobody, which is worse than offering none: it invites a worker to tap it repeatedly at the
 * start of a shift, conclude the app is broken, and go and find paper.
 */
@Composable
fun NotConfiguredScreen(
    state: SessionState,
    tenant: TenantConfig,
    onOpenSettings: () -> Unit,
) {
    // Dead centre of the page, on its own - not sharing a Column with the title above it. A
    // Column with Arrangement.Center centres the *pair* of title-plus-spinner as one block,
    // which leaves the spinner itself sitting below the true middle of the screen. The
    // standard WMS app puts its loading spinner at the actual centre of the open page;
    // matching that means the spinner has to be the only thing in this state, not one of two
    // things sharing the centred group.
    if (state is SessionState.Checking) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = when (state) {
                        is SessionState.NotConfigured -> "This device is not configured yet"
                        is SessionState.SignedOut ->
                            state.reason ?: "This device cannot reach D365FO"
                        else -> "Setting up"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )

                if (tenant.resourceUrl.isNotBlank()) {
                    Text(
                        text = tenant.host,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }

                Text(
                    text = "Ask a supervisor to check the connection settings.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }

        Button(
            onClick = onOpenSettings,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp),
        ) {
            Text("Connection settings")
        }
    }
}
