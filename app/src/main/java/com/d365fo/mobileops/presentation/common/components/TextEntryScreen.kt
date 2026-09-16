package com.d365fo.mobileops.presentation.common.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import com.d365fo.mobileops.presentation.common.components.SquareButton as Button
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens

/**
 * The dedicated "type it in" page - the standard WMS app's own answer to "I'd rather type than
 * scan": tapping a scan-first field elsewhere in this app navigates here instead of popping the
 * keyboard up over whatever screen was already on. Reached by a plain nav push, so the origin
 * screen's ViewModel (and its live scanner-flow subscription) sits untouched underneath the whole
 * time - scanning still works exactly as before even while this screen is on top.
 *
 * Unlike every scan-first field this replaces, the keyboard shows immediately here - that is the
 * one reason this screen exists, so there is nothing to hide.
 *
 * No ViewModel of its own: purely presentational, parameterised by whoever navigates here (the
 * same pattern already set by [MenuRow]) so one implementation covers PO/transfer/item/location/
 * batch/serial numbers, PO/transfer/location lookups, and the quantity step's numeric entry.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextEntryScreen(
    title: String,
    hint: String? = null,
    initialValue: String = "",
    keyboardType: KeyboardType = KeyboardType.Text,
    showNumericKeypad: Boolean = false,
    onBack: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var entry by remember { mutableStateOf(initialValue) }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
        keyboardController?.show()
    }

    val submit = {
        if (entry.isNotBlank()) onSubmit(entry.trim())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                expandedHeight = WarehouseDimens.TopBarHeight,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = WarehouseDimens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            hint?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            OutlinedTextField(
                value = entry,
                onValueChange = { entry = it },
                singleLine = true,
                textStyle = if (keyboardType == KeyboardType.Decimal) {
                    MaterialTheme.typography.headlineSmall.copy(textAlign = TextAlign.Center)
                } else {
                    MaterialTheme.typography.headlineSmall
                },
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .focusRequester(focusRequester),
            )

            // The keyboard's own Done/OK action submits - see keyboardActions above - so there
            // is no separate Confirm button for plain typing, matching the reference app's own
            // dedicated entry screen. The numeric keypad is the one exception: it is a custom
            // tap-only pad with no action key of its own (see NumericKeypad), so it still needs
            // an explicit submit control.
            if (showNumericKeypad) {
                NumericKeypad(
                    enabled = true,
                    onDigit = { d -> entry += d },
                    onDecimal = { if (!entry.contains('.')) entry = if (entry.isEmpty()) "0." else "$entry." },
                    onBackspace = { if (entry.isNotEmpty()) entry = entry.dropLast(1) },
                )
                Button(
                    onClick = submit,
                    enabled = entry.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                ) {
                    Text("Confirm")
                }
            }
        }
    }
}
