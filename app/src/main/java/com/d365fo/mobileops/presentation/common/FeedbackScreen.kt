package com.d365fo.mobileops.presentation.common

import com.d365fo.mobileops.presentation.common.theme.FocusType
import com.d365fo.mobileops.presentation.common.theme.FocusColors
import com.d365fo.mobileops.presentation.common.focus.ResultBand
import com.d365fo.mobileops.presentation.common.focus.FocusScreen
import com.d365fo.mobileops.presentation.common.focus.FocusDimens
import com.d365fo.mobileops.presentation.common.focus.FocusCaption
import com.d365fo.mobileops.presentation.common.focus.FocusButton
import com.d365fo.mobileops.presentation.common.focus.BandState
import com.d365fo.mobileops.presentation.common.focus.BandKind
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.drawBehind
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.height
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

/**
 * Send feedback - see [FeedbackViewModel]. In the Focus list style (user, 15 Nov 2026): the kind,
 * the message, up to 5 pictures from the gallery, Send; then the worker's earlier feedback with
 * the team's status and reply.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FeedbackScreen(onBack: () -> Unit, viewModel: FeedbackViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val unsent by viewModel.unsent.collectAsStateWithLifecycle()
    val max = com.d365fo.mobileops.data.repository.MAX_PICTURES
    var viewing by remember { mutableStateOf<java.io.File?>(null) }
    // Android's photo picker: no storage permission, the app only gets what is chosen.
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia(max),
    ) { uris -> viewModel.addPictures(uris) }

    if (state.editing != null) EditFeedbackDialog(state, viewModel)
    viewing?.let { file -> PictureViewer(file, onClose = { viewing = null }) }

    CompositionLocalProvider(LocalFeedbackLauncher provides null) {
        FocusScreen(title = "Send feedback", onBack = onBack) {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (state.sending || state.loadingMine || state.addingPictures) {
                    item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
                }
                if (state.sentId != null) {
                    item {
                        ResultBand(
                            BandState(
                                BandKind.Ok,
                                "Thank you - feedback sent",
                                listOfNotNull(
                                    state.sentType.label,
                                    when (state.sentPictures) {
                                        0 -> null
                                        1 -> "1 picture"
                                        else -> "${state.sentPictures} pictures"
                                    },
                                    "replies show below",
                                ).joinToString(" · "),
                            ),
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    item {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = FocusDimens.side, vertical = 16.dp),
                        ) {
                            FocusButton("Send more", onClick = viewModel::sendMore, modifier = Modifier.weight(1f))
                            FocusButton("Done", onClick = onBack, primary = true, modifier = Modifier.weight(1f))
                        }
                    }
                } else {
                    item { Caption("What is it?") }
                    item {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(horizontal = FocusDimens.side),
                        ) {
                            FeedbackType.entries.forEach { type ->
                                com.d365fo.mobileops.presentation.common.focus.FocusChip(type.label, selected = state.type == type, onClick = { viewModel.setType(type) })
                            }
                        }
                    }
                    item {
                        Box(Modifier.padding(horizontal = FocusDimens.side, vertical = 12.dp)) {
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
                    }
                    item { Caption("Pictures · ${state.pictures.size} of $max") }
                    item {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            contentPadding = PaddingValues(start = FocusDimens.side, end = FocusDimens.side, top = 8.dp, bottom = 4.dp),
                        ) {
                            items(state.pictures, key = { it.absolutePath }) { file ->
                                PictureThumb(file, onOpen = { viewing = file }, onRemove = { viewModel.removePicture(file) })
                            }
                            if (state.pictures.size < max) {
                                item(key = "add") {
                                    AddPictureTile(enabled = !state.addingPictures) {
                                        pick.launch(
                                            androidx.activity.result.PickVisualMediaRequest(
                                                androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly,
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (state.pictures.isNotEmpty()) {
                        item {
                            Text(
                                "Tap a picture to see it full size.",
                                style = FocusType.rowDetail,
                                color = FocusColors.muted,
                                modifier = Modifier.padding(horizontal = FocusDimens.side, vertical = 4.dp),
                            )
                        }
                    }
                    item {
                        FocusButton(
                            "Send feedback",
                            onClick = viewModel::send,
                            primary = true,
                            enabled = state.message.isNotBlank() && !state.sending && !state.addingPictures,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = FocusDimens.side, vertical = 12.dp),
                        )
                    }
                    item {
                        Text(
                            "Sent with the app version and log.",
                            style = FocusType.rowDetail,
                            color = FocusColors.muted,
                            modifier = Modifier.padding(horizontal = FocusDimens.side),
                        )
                    }
                }

                // -- My feedback ----------------------------------------------------------------
                val all = unsent + state.mine.filterNot { m -> unsent.any { it.id == m.id } }
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(start = FocusDimens.side, end = 8.dp, top = 16.dp),
                    ) {
                        FocusCaption("My feedback · ${all.size}", modifier = Modifier.weight(1f))
                        IconButton(onClick = viewModel::refreshMine) { Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = FocusColors.navy) }
                    }
                    HorizontalDivider(color = FocusColors.divider)
                }
                state.mineError?.let {
                    item { Text(it, style = FocusType.rowDetail, color = FocusColors.muted, modifier = Modifier.padding(FocusDimens.side)) }
                }
                if (all.isEmpty() && !state.loadingMine && state.mineError == null) {
                    item { Text("Nothing sent yet.", style = FocusType.rowDetail, color = FocusColors.muted, modifier = Modifier.padding(FocusDimens.side)) }
                }
                items(all, key = { it.id }) { entry ->
                    FeedbackRow(entry, onEdit = if (!entry.waiting && entry.status == "New") ({ viewModel.startEdit(entry) }) else null)
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun Caption(text: String) {
    FocusCaption(text, modifier = Modifier.padding(start = FocusDimens.side, end = FocusDimens.side, top = 16.dp, bottom = 8.dp))
}

/** A picked picture: 72 x 100, tap to see it, a red X to take it off. */
@Composable
private fun PictureThumb(file: java.io.File, onOpen: () -> Unit, onRemove: () -> Unit) {
    val bitmap = remember(file) { runCatching { BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() }.getOrNull() }
    Box(Modifier.padding(top = 8.dp, end = 8.dp)) {
        Box(
            modifier = Modifier
                .size(width = 72.dp, height = 100.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(FocusColors.divider)
                .clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = "See picture", onClick = onOpen),
        ) {
            bitmap?.let { Image(it, contentDescription = "Picture", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 10.dp, y = (-10).dp)
                .size(30.dp)
                .clip(CircleShape)
                .background(FocusColors.page)
                .padding(3.dp)
                .clip(CircleShape)
                .background(FocusColors.badIcon)
                .clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = "Remove picture", onClick = onRemove),
        ) {
            Icon(Icons.Default.Close, contentDescription = "Remove picture", tint = FocusColors.surface, modifier = Modifier.size(16.dp))
        }
    }
}

/** The dashed "Add" tile that opens the photo picker. */
@Composable
private fun AddPictureTile(enabled: Boolean, onClick: () -> Unit) {
    val dash = FocusColors.infoIcon.copy(alpha = 0.5f)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .padding(top = 8.dp)
            .size(width = 72.dp, height = 100.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(FocusColors.infoBg.copy(alpha = 0.5f))
            .drawBehind {
                drawRoundRect(
                    color = dash,
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(10.dp.toPx()),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = 2.dp.toPx(),
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
                    ),
                )
            }
            .clickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.Button, onClickLabel = "Add a picture", onClick = onClick),
    ) {
        Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, tint = FocusColors.navy, modifier = Modifier.size(26.dp))
        Text("Add", style = FocusType.rowDetail.copy(fontWeight = FontWeight.ExtraBold), color = FocusColors.navy)
    }
}

/** A picked picture full size, tap anywhere to close. */
@Composable
private fun PictureViewer(file: java.io.File, onClose: () -> Unit) {
    val bitmap = remember(file) { runCatching { BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() }.getOrNull() }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.9f))
                .clickable(onClickLabel = "Close", onClick = onClose),
        ) {
            bitmap?.let { Image(it, contentDescription = "Picture", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(16.dp)) }
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

/**
 * One sent (or waiting) feedback: its kind, the status pill, the message, when, and the team's
 * reply. New ones can still be changed - "tap to change" and a chevron.
 */
@Composable
private fun FeedbackRow(entry: FeedbackEntry, onEdit: (() -> Unit)? = null) {
    val (pillBg, pillText) = when (entry.status) {
        "Done" -> FocusColors.okBg to FocusColors.okText
        "Not sent", "Won't do" -> FocusColors.badBg to FocusColors.badText
        else -> FocusColors.warnBg to FocusColors.warnText
    }
    val icon = when (entry.type) {
        FeedbackType.Idea.name, FeedbackType.Idea.label -> Icons.Default.Lightbulb
        FeedbackType.Question.name, FeedbackType.Question.label -> Icons.AutoMirrored.Filled.HelpOutline
        else -> Icons.Default.BugReport
    }
    Column(Modifier.fillMaxWidth()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onEdit != null) Modifier.clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = "Change feedback", onClick = onEdit) else Modifier)
                .padding(horizontal = FocusDimens.side, vertical = 12.dp),
        ) {
            Icon(icon, contentDescription = null, tint = if (entry.status == "Not sent") FocusColors.badIcon else FocusColors.iconMuted, modifier = Modifier.size(24.dp).padding(top = 2.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        FeedbackType.entries.firstOrNull { it.name == entry.type }?.label ?: entry.type,
                        style = FocusType.rowTitle,
                        color = FocusColors.ink,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        entry.status.uppercase(),
                        style = FocusType.caption.copy(fontSize = 11.sp),
                        color = pillText,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(pillBg).padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
                Text(entry.message, style = FocusType.rowDetail.copy(color = FocusColors.ink))
                Text(
                    listOfNotNull(entry.sentAt.ifBlank { null }, "tap to change".takeIf { onEdit != null }).joinToString(" · "),
                    style = FocusType.rowDetail,
                    color = FocusColors.muted,
                )
                if (entry.reply.isNotBlank()) {
                    if (entry.waiting) {
                        Text(entry.reply, style = FocusType.rowDetail, color = FocusColors.badText)
                    } else {
                        Text(
                            "Reply: " + entry.reply,
                            style = FocusType.rowDetail.copy(fontWeight = FontWeight.Bold),
                            color = FocusColors.navy,
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .drawBehind { drawRect(FocusColors.navy, size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height)) }
                                .padding(start = 10.dp),
                        )
                    }
                }
            }
            if (onEdit != null) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = FocusColors.iconMuted, modifier = Modifier.size(22.dp))
            }
        }
        HorizontalDivider(color = FocusColors.divider)
    }
}
