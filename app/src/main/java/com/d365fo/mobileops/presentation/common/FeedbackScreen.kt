package com.d365fo.mobileops.presentation.common

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.d365fo.mobileops.data.repository.FeedbackEntry
import com.d365fo.mobileops.data.repository.FeedbackType
import com.d365fo.mobileops.presentation.common.components.AppCard
import com.d365fo.mobileops.presentation.common.components.LocalFeedbackLauncher
import com.d365fo.mobileops.presentation.common.components.SquareButton
import com.d365fo.mobileops.presentation.common.components.SquareOutlinedButton
import com.d365fo.mobileops.presentation.common.components.StatusChip
import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens
import com.d365fo.mobileops.presentation.common.theme.WarehouseStatusColors
import com.d365fo.mobileops.presentation.common.components.AppTopBar as TopAppBar

/** Send feedback - see [FeedbackViewModel]. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FeedbackScreen(onBack: () -> Unit, viewModel: FeedbackViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val unsent by viewModel.unsent.collectAsStateWithLifecycle()
    val shot = remember(state.screenshot) {
        state.screenshot?.let { runCatching { BitmapFactory.decodeFile(it.absolutePath)?.asImageBitmap() }.getOrNull() }
    }

    if (state.editing != null) EditFeedbackDialog(state, viewModel)

    // No feedback button on the feedback screen itself.
    CompositionLocalProvider(LocalFeedbackLauncher provides null) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Send feedback") },
                    expandedHeight = WarehouseDimens.TopBarHeight,
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                    },
                )
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = WarehouseDimens.ScreenPadding),
                verticalArrangement = Arrangement.spacedBy(WarehouseDimens.ItemSpacing),
            ) {
                item { if (state.sending || state.loadingMine) LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }

                if (state.sentId != null) {
                    item {
                        AppCard(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Thank you", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Sent. Replies show under My feedback.",
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                        }
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SquareOutlinedButton(onClick = viewModel::sendMore, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Send more") }
                            SquareButton(onClick = onBack, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Done") }
                        }
                    }
                } else {
                    // -- The form -------------------------------------------------------------
                    item {
                        Text(viewModel.context, style = MaterialTheme.typography.bodySmall, color = WarehouseStatusColors.text3)
                    }
                    item { Text("What is it?", style = MaterialTheme.typography.titleMedium) }
                    item {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FeedbackType.entries.forEach { type ->
                                com.d365fo.mobileops.presentation.common.focus.FocusChip(type.label, selected = state.type == type, onClick = { viewModel.setType(type) })
                            }
                        }
                    }
                    item {
                        MessageField(
                            value = state.message,
                            onValueChange = viewModel::setMessage,
                            placeholder = when (state.type) {
                                FeedbackType.Problem -> "What happened, and what did you expect?"
                                FeedbackType.Idea -> "What would make this easier?"
                                FeedbackType.Question -> "What would you like to know?"
                            },
                        )
                    }
                    if (shot != null) {
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Include screenshot", style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "Of ${state.screen}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = WarehouseStatusColors.text3,
                                    )
                                }
                                Switch(checked = state.includeScreenshot, onCheckedChange = viewModel::setIncludeScreenshot)
                            }
                        }
                        if (state.includeScreenshot) {
                            item {
                                Image(
                                    bitmap = shot,
                                    contentDescription = "Screenshot",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.width(180.dp).heightIn(max = 340.dp).clip(RoundedCornerShape(8.dp)),
                                )
                            }
                        }
                    }
                    item {
                        SquareButton(
                            onClick = viewModel::send,
                            enabled = state.message.isNotBlank() && !state.sending,
                            modifier = Modifier.fillMaxWidth().heightIn(min = WarehouseDimens.MinTouchTarget),
                        ) { Text("Send feedback") }
                    }
                    item {
                        Text(
                            "Sent with the app version and log.",
                            style = MaterialTheme.typography.bodySmall,
                            color = WarehouseStatusColors.text3,
                        )
                    }
                }

                // -- My feedback ----------------------------------------------------------------
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Text("My feedback", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        IconButton(onClick = viewModel::refreshMine) { Icon(Icons.Default.Refresh, contentDescription = "Refresh") }
                    }
                }
                state.mineError?.let { item { Text(it, style = MaterialTheme.typography.bodySmall, color = WarehouseStatusColors.text3) } }
                val all = unsent + state.mine.filterNot { m -> unsent.any { it.id == m.id } }
                if (all.isEmpty() && !state.loadingMine && state.mineError == null) {
                    item { Text("Nothing sent yet.", style = MaterialTheme.typography.bodyMedium, color = WarehouseStatusColors.text3) }
                }
                items(all, key = { it.id }) { entry ->
                    FeedbackCard(entry, onEdit = if (!entry.waiting && entry.status == "New") ({ viewModel.startEdit(entry) }) else null)
                }
            }
        }
    }
}

/** Changing feedback the team has not picked up yet (status New). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditFeedbackDialog(state: FeedbackState, viewModel: FeedbackViewModel) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = viewModel::cancelEdit,
        title = { Text("Change feedback") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "About ${state.editing?.screen.orEmpty()}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = WarehouseStatusColors.text3,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FeedbackType.entries.forEach { type ->
                        com.d365fo.mobileops.presentation.common.focus.FocusChip(type.label, selected = state.editType == type, onClick = { viewModel.setEditType(type) })
                    }
                }
                MessageField(value = state.editMessage, onValueChange = viewModel::setEditMessage, placeholder = "Your feedback")
                state.editError?.let { Text(it, color = WarehouseStatusColors.red, style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = {
            com.d365fo.mobileops.presentation.common.components.DialogButtonRow(
                dismiss = { com.d365fo.mobileops.presentation.common.components.CancelOutlinedButton(onClick = viewModel::cancelEdit, modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget)) { Text("Cancel") } },
                confirm = { com.d365fo.mobileops.presentation.common.components.ConfirmButton(onClick = viewModel::saveEdit, enabled = state.editMessage.isNotBlank() && !state.saving, modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget)) { Text(if (state.saving) "Saving..." else "Save") } },
            )
        },
    )
}

/** A multi-line message box that opens the keyboard only when tapped (see TapToTypeField). */
@Composable
private fun MessageField(value: String, onValueChange: (String) -> Unit, placeholder: String) {
    var typing by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val interaction = remember { MutableInteractionSource() }
    LaunchedEffect(interaction) {
        interaction.interactions.collect {
            if (it is PressInteraction.Release && !typing) {
                typing = true
                keyboard?.show()
            }
        }
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        readOnly = !typing,
        minLines = 5,
        interactionSource = interaction,
        placeholder = { Text(placeholder) },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun FeedbackCard(entry: FeedbackEntry, onEdit: (() -> Unit)? = null) {
    val content: @Composable () -> Unit = {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${entry.type} · ${entry.screen}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                when (entry.status) {
                    "Done" -> StatusChip("Done", WarehouseStatusColors.green, WarehouseStatusColors.greenDim)
                    "Not sent", "Won't do" -> StatusChip(entry.status, WarehouseStatusColors.red, WarehouseStatusColors.redDim)
                    else -> StatusChip(entry.status, WarehouseStatusColors.amber, WarehouseStatusColors.amberDim)
                }
            }
            Text(entry.message, style = MaterialTheme.typography.bodyMedium)
            Text(entry.sentAt, style = MaterialTheme.typography.bodySmall, color = WarehouseStatusColors.text3)
            if (entry.reply.isNotBlank()) {
                Text(
                    (if (entry.waiting) "" else "Reply: ") + entry.reply,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (entry.waiting) WarehouseStatusColors.red else MaterialTheme.colorScheme.onSurface,
                )
            }
            if (onEdit != null) {
                Text("Tap to change", style = MaterialTheme.typography.bodySmall, color = WarehouseStatusColors.accent)
            }
        }
    }
    if (onEdit != null) {
        AppCard(onClick = onEdit, modifier = Modifier.fillMaxWidth()) { content() }
    } else {
        AppCard(modifier = Modifier.fillMaxWidth()) { content() }
    }
}
