package com.d365fo.mobileops.presentation.common.scan

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import com.d365fo.mobileops.presentation.common.components.SquareButton as Button
import androidx.compose.material3.MaterialTheme
import com.d365fo.mobileops.presentation.common.components.SquareOutlinedButton as OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.d365fo.mobileops.presentation.common.theme.WarehouseDimens
import timber.log.Timber
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Full-screen camera scanner.
 *
 * The camera is the *fallback* input, not the primary one — a rugged handheld's laser wedge
 * is faster and works in the dark. This exists for phones and tablets without an imager, and
 * for the case where the wedge fails mid-shift and the pallet still has to be received.
 *
 * Analysis runs on its own single-threaded executor with [STRATEGY_KEEP_ONLY_LATEST], so a
 * slow decode drops frames instead of building a backlog the worker sees as lag.
 */
@Composable
fun CameraScannerSheet(
    onDecoded: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
        if (!granted) onDismiss()
    }

    // Asking on open rather than on app start: a permission prompt makes sense to a worker
    // the moment they tap the camera button, and means nothing during a shift login.
    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            if (hasPermission) {
                CameraPreview(onDecoded = onDecoded)
                ScanReticle()
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(WarehouseDimens.ScreenPadding),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "Camera access is needed to scan barcodes.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White,
                    )
                    Button(
                        onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                        modifier = Modifier
                            .padding(top = WarehouseDimens.ItemSpacing)
                            .heightIn(min = WarehouseDimens.MinTouchTarget),
                    ) {
                        Text("Allow camera")
                    }
                }
            }

            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp)
                    .fillMaxWidth(0.7f)
                    .heightIn(min = WarehouseDimens.MinTouchTarget),
            ) {
                Text("Cancel")
            }
        }
    }
}

@Composable
private fun CameraPreview(onDecoded: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnDecoded by rememberUpdatedState(onDecoded)

    val executor: ExecutorService = remember { Executors.newSingleThreadExecutor() }
    val analyzer = remember { BarcodeAnalyzer { currentOnDecoded(it) } }

    DisposableEffect(Unit) {
        onDispose {
            analyzer.release()
            executor.shutdown()
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
            val providerFuture = ProcessCameraProvider.getInstance(ctx)

            providerFuture.addListener({
                runCatching {
                    val provider = providerFuture.get()

                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    val analysis = ImageAnalysis.Builder()
                        // Drop stale frames rather than queue them; a backlog reads as lag.
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        // Without this, CameraX's default analysis resolution (well under
                        // 1080p) is fine for a coarse EAN/CODE128 barcode but not nearly
                        // enough pixels to resolve a dense QR's fine module grid - this was
                        // the actual cause of the license-certificate QR never scanning, not
                        // the QR's own density (confirmed: an independent decoder read the
                        // same QR image fine at full resolution).
                        .setResolutionSelector(
                            ResolutionSelector.Builder()
                                .setResolutionStrategy(
                                    ResolutionStrategy(
                                        Size(1920, 1080),
                                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                                    ),
                                )
                                .build(),
                        )
                        .build()
                        .also { it.setAnalyzer(executor, analyzer) }

                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                }.onFailure { Timber.e(it, "camera bind failed") }
            }, ContextCompat.getMainExecutor(ctx))

            previewView
        },
    )
}

/** A plain aiming guide. Deliberately minimal — the worker needs the label, not chrome. */
@Composable
private fun ScanReticle() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.8f)
                .height(2.dp)
                .background(Color(0xFFFFC61A)),
        )
    }
}
