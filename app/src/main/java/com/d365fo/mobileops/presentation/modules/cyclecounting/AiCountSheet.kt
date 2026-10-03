package com.d365fo.mobileops.presentation.modules.cyclecounting

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.d365fo.mobileops.data.ai.ObjectCounter
import com.d365fo.mobileops.domain.ai.AiCount
import com.d365fo.mobileops.presentation.common.components.SquareButton as Button
import com.d365fo.mobileops.presentation.common.components.SquareOutlinedButton as OutlinedButton
import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import kotlin.math.min

/** Gives the sheet the app-wide detector, so the model loads once per process. */
@HiltViewModel
class AiCountViewModel @Inject constructor(val counter: ObjectCounter) : ViewModel() {
    private var job: kotlinx.coroutines.Job? = null

    fun run(photo: Bitmap, closer: Boolean, onDone: (Result<List<com.d365fo.mobileops.domain.ai.Detection>>) -> Unit) {
        job?.cancel()
        job = viewModelScope.launch {
            val result = runCatching { counter.detect(photo, closer) }
            if (result.exceptionOrNull() !is kotlinx.coroutines.CancellationException) onDone(result)
        }
    }
}

private sealed interface AiPhase {
    data object Live : AiPhase
    data class Analysing(val photo: Bitmap, val startedAt: Long = System.currentTimeMillis()) : AiPhase
    data class Result(val photo: Bitmap, val count: AiCount, val closer: Boolean = false) : AiPhase
    data class Failed(val message: String) : AiPhase
}

/**
 * Count with the camera: a photo of the shelf or pallet, an on-device detector proposes every
 * item, the worker corrects it with taps and confirms the number. Nothing is saved until the
 * count popup's own OK - this only fills its field.
 */
@Composable
fun AiCountSheet(
    itemLabel: String,
    onUse: (Int) -> Unit,
    onDismiss: () -> Unit,
    viewModel: AiCountViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
        if (!granted) onDismiss()
    }
    LaunchedEffect(Unit) { if (!hasPermission) launcher.launch(Manifest.permission.CAMERA) }

    var phase by remember { mutableStateOf<AiPhase>(AiPhase.Live) }
    var preview by remember { mutableStateOf<PreviewView?>(null) }
    var camera by remember { mutableStateOf<androidx.camera.core.Camera?>(null) }
    var torch by remember { mutableStateOf(false) }
    var pickSample by remember { mutableStateOf(false) }

    fun analyse(photo: Bitmap, closer: Boolean) {
        phase = AiPhase.Analysing(photo)
        // In the view model's scope: leaving this screen mid-count must not lose or crash it.
        viewModel.run(photo, closer) { result ->
            phase = result.fold(
                { AiPhase.Result(photo, AiCount(it), closer) },
                {
                    Timber.e(it, "ai count failed")
                    AiPhase.Failed("Couldn't count this photo: ${it.message ?: it.javaClass.simpleName}")
                },
            )
        }
    }

    fun analyse() {
        val photo = preview?.bitmap ?: return
        if (isTooDark(photo)) {
            phase = AiPhase.Failed("The photo is too dark to count. Turn on Light or move to better lighting.")
            return
        }
        analyse(photo, closer = false)
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            when (val p = phase) {
                AiPhase.Live -> if (hasPermission) LivePreview(onView = { preview = it }, onCamera = { camera = it })
                is AiPhase.Analysing -> {
                    PhotoWithMarks(p.photo, null, onTap = { _, _ -> })
                    var seconds by remember(p) { mutableStateOf(0) }
                    LaunchedEffect(p) {
                        while (true) {
                            kotlinx.coroutines.delay(1_000)
                            seconds = ((System.currentTimeMillis() - p.startedAt) / 1_000).toInt()
                        }
                    }
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color.White)
                        Text("Counting… ${seconds}s", color = Color.White, modifier = Modifier.padding(top = 12.dp))
                    }
                }
                is AiPhase.Result -> PhotoWithMarks(p.photo, p.count) { x, y ->
                    val c = p.count
                    phase = p.copy(
                        count = if (pickSample) c.sampleAt(x, y) else c.tap(x, y, min(p.photo.width, p.photo.height) * 0.03f),
                    )
                    pickSample = false
                }
                is AiPhase.Failed -> Text(
                    p.message,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center).padding(WarehouseDimens.ScreenPadding),
                )
            }

            // Header: what is being counted, and the running number.
            Column(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Color(0x99000000)).padding(16.dp),
            ) {
                Text("AI count · $itemLabel", color = Color.White, style = MaterialTheme.typography.titleMedium)
                val result = phase as? AiPhase.Result
                Text(
                    text = when {
                        result != null -> "${result.count.count} counted" +
                            (if (result.count.sample != null) " · like the sample" else "")
                        phase == AiPhase.Live -> "Fit the items in the frame, then tap Count"
                        else -> ""
                    },
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                if (phase == AiPhase.Live && camera?.cameraInfo?.hasFlashUnit() == true) {
                    FilterChip(
                        selected = torch,
                        onClick = { torch = !torch; camera?.cameraControl?.enableTorch(torch) },
                        label = { Text(if (torch) "Light on" else "Light") },
                        colors = chipColors(),
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                if (result != null) {
                    Text(
                        text = if (pickSample) "Tap one item to count only items like it"
                        else "Tap a mark to remove it, tap a missed item to add it",
                        color = Color(0xFFDDDDDD),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                        FilterChip(selected = pickSample, onClick = { pickSample = !pickSample }, label = { Text("Count only like…") }, colors = chipColors())
                        if (!result.closer) {
                            FilterChip(selected = false, onClick = { analyse(result.photo, closer = true) }, label = { Text("Look closer") }, colors = chipColors())
                        }
                        if (result.count.sample != null) {
                            FilterChip(selected = false, onClick = { phase = result.copy(count = result.count.copy(sample = null)) }, label = { Text("Count all") }, colors = chipColors())
                        }
                    }
                }
            }

            // Actions.
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget)) {
                    Text("Cancel", color = Color.White)
                }
                when (val p = phase) {
                    AiPhase.Live -> Button(
                        onClick = ::analyse,
                        enabled = hasPermission,
                        modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget),
                    ) { Text("Count") }
                    is AiPhase.Result -> {
                        OutlinedButton(
                            onClick = { phase = AiPhase.Live },
                            modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget),
                        ) { Text("Retake", color = Color.White) }
                        Button(
                            onClick = { onUse(p.count.count) },
                            modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget),
                        ) { Text("Use ${p.count.count}") }
                    }
                    is AiPhase.Failed -> Button(
                        onClick = { phase = AiPhase.Live },
                        modifier = Modifier.weight(1f).heightIn(min = WarehouseDimens.MinTouchTarget),
                    ) { Text("Retake", color = Color.White) }
                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun LivePreview(onView: (PreviewView) -> Unit, onCamera: (androidx.camera.core.Camera) -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            // FIT_CENTER so the photo taken is exactly what the worker framed.
            val view = PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
            val future = ProcessCameraProvider.getInstance(ctx)
            future.addListener({
                runCatching {
                    val provider = future.get()
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                    provider.unbindAll()
                    onCamera(provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview))
                }.onFailure { Timber.e(it, "camera bind failed") }
            }, ContextCompat.getMainExecutor(ctx))
            onView(view)
            view
        },
    )
}

/** The photo, fitted to the screen, with a numbered mark on each counted item. */
@Composable
private fun PhotoWithMarks(photo: Bitmap, count: AiCount?, onTap: (Float, Float) -> Unit) {
    val image = remember(photo) { photo.asImageBitmap() }
    Box(Modifier.fillMaxSize()) {
        Image(image, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        Canvas(
            Modifier.fillMaxSize().pointerInput(count) {
                detectTapGestures { tap ->
                    val (scale, ox, oy) = fit(photo, Size(size.width.toFloat(), size.height.toFloat()))
                    onTap((tap.x - ox) / scale, (tap.y - oy) / scale)
                }
            },
        ) {
            count ?: return@Canvas
            val (scale, ox, oy) = fit(photo, size)
            val counted = count.counted.toSet()
            count.detections.forEachIndexed { i, d ->
                val tl = Offset(ox + d.left * scale, oy + d.top * scale)
                val sz = Size(d.width * scale, d.height * scale)
                if (i in counted) {
                    drawRect(Color(0xFF34C759), tl, sz, style = Stroke(width = 3.dp.toPx()))
                    drawCircle(Color(0xFF34C759), radius = 7.dp.toPx(), center = Offset(ox + d.centerX * scale, oy + d.centerY * scale))
                } else {
                    drawRect(Color(0x66FFFFFF), tl, sz, style = Stroke(width = 1.dp.toPx()))
                }
            }
            count.added.forEach { (x, y) ->
                drawCircle(Color(0xFFFFC61A), radius = 9.dp.toPx(), center = Offset(ox + x * scale, oy + y * scale))
            }
        }
    }
}

/** Scale and offset of a ContentScale.Fit image inside [box]. */
private fun fit(photo: Bitmap, box: Size): Triple<Float, Float, Float> {
    val scale = min(box.width / photo.width, box.height / photo.height)
    return Triple(scale, (box.width - photo.width * scale) / 2, (box.height - photo.height * scale) / 2)
}

@Composable
private fun chipColors() = androidx.compose.material3.FilterChipDefaults.filterChipColors(
    labelColor = Color.White,
    selectedLabelColor = Color.Black,
    selectedContainerColor = Color(0xFFFFC61A),
)

/** Average brightness of a coarse pixel sample - a black frame would count as zero items. */
private fun isTooDark(photo: Bitmap): Boolean {
    var sum = 0L
    var n = 0
    val stepX = (photo.width / 32).coerceAtLeast(1)
    val stepY = (photo.height / 32).coerceAtLeast(1)
    for (y in 0 until photo.height step stepY) for (x in 0 until photo.width step stepX) {
        val c = photo.getPixel(x, y)
        sum += (299 * ((c shr 16) and 0xFF) + 587 * ((c shr 8) and 0xFF) + 114 * (c and 0xFF)) / 1000
        n++
    }
    return n > 0 && sum / n < DARK_LUMA
}

private const val DARK_LUMA = 28
