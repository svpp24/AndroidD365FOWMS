package com.d365fo.mobileops.presentation.modules.cyclecounting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.d365fo.mobileops.data.ai.AiSampleUploadScheduler
import com.d365fo.mobileops.data.ai.TrainingSampleStore
import com.d365fo.mobileops.presentation.common.components.SquareButton as Button
import com.d365fo.mobileops.presentation.common.components.SquareOutlinedButton as OutlinedButton
import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AiTrainingViewModel @Inject constructor(
    val store: TrainingSampleStore,
    private val uploads: AiSampleUploadScheduler,
) : ViewModel() {
    fun uploadNow() = uploads.enqueue(now = true)
}

/** Settings > AI training data: samples still waiting to go to D365FO, and a way to send or drop them. */
@Composable
fun AiTrainingSection(viewModel: AiTrainingViewModel = hiltViewModel()) {
    val scope = rememberCoroutineScope()
    val waiting by viewModel.store.waiting.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("AI training data", style = MaterialTheme.typography.titleMedium)
        Text(
            "AI counts you correct are sent to D365FO (Mobile AI count samples), where all of this " +
                "company's devices share them to train a model on your own products. Only corrected " +
                "counts are kept, as small photos, and each is removed from this device once sent.",
            style = MaterialTheme.typography.bodyMedium,
            color = WarehouseStatusColors.text3,
        )
        Text(
            if (waiting == 0) "Nothing waiting to send" else "$waiting waiting to send",
            style = MaterialTheme.typography.titleSmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                enabled = waiting > 0,
                onClick = viewModel::uploadNow,
                modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget),
            ) { Text("Send now") }
            OutlinedButton(
                enabled = waiting > 0,
                onClick = { confirmDelete = true },
                modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget),
            ) { Text("Delete") }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete waiting samples?") },
            text = { Text("The $waiting samples not yet sent are removed from this device.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; scope.launch { viewModel.store.clear() } }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
