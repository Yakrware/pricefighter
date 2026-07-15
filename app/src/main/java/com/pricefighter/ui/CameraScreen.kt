package com.pricefighter.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pricefighter.PriceFighterApp
import com.pricefighter.data.vision.ProductCandidate
import com.pricefighter.data.vision.ProductIdentifier
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

private const val GEMINI_PACKAGE = "com.google.android.apps.bard"
private const val PRICE_CHECK_PROMPT =
    "Price check this item. Identify the product and its model number, then run a price check " +
        "and report the typical sold price."

private enum class CameraMode { Single, Continuous }

@Composable
fun CameraScreen(
    contentPadding: PaddingValues,
    onOpenHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val app = remember(context) { context.applicationContext as PriceFighterApp }
    val identifier = remember { ProductIdentifier(app) }
    val viewModel: CameraViewModel = viewModel(factory = CameraViewModel.factory(app.repository, identifier))
    val singleState by viewModel.state.collectAsStateWithLifecycle()
    val items by viewModel.items.collectAsStateWithLifecycle()
    var mode by rememberSaveable { mutableStateOf(CameraMode.Single) }
    var lastFile by remember { mutableStateOf<File?>(null) }
    // The just-captured frame, shown frozen over the live preview while the lookup runs (single mode).
    var frozenFrame by remember { mutableStateOf<Bitmap?>(null) }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasPermission = granted }

    // Start the (one-time) Gemini Nano model download as soon as the camera is usable, so
    // on-device identification is ready before the first snap instead of falling to Gemini.
    LaunchedEffect(hasPermission) {
        if (hasPermission) identifier.prepareNano()
    }

    // Single mode only: nothing identified on-device → hand the photo to the Gemini app.
    LaunchedEffect(singleState, mode) {
        if (mode == CameraMode.Single && singleState is CaptureState.NeedsGemini) {
            lastFile?.let { shareToGemini(context, it) }
            viewModel.reset()
        }
    }

    // Unfreeze back to the live preview whenever single mode returns to idle.
    LaunchedEffect(singleState, mode) {
        if (mode != CameraMode.Single || singleState is CaptureState.Idle) frozenFrame = null
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(
                top = contentPadding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding(),
            ),
    ) {
        if (hasPermission) {
            CameraCaptureView(
                mode = mode,
                onModeChange = { mode = it },
                singleState = singleState,
                items = items,
                frozenFrame = frozenFrame,
                onCaptured = { file ->
                    if (mode == CameraMode.Single) {
                        lastFile = file
                        frozenFrame = decodeForDisplay(file)
                        viewModel.onPhotoCaptured(file)
                    } else {
                        viewModel.captureContinuous(file)
                    }
                },
                onResetSingle = viewModel::reset,
                onCancelSingle = viewModel::cancelSingle,
                onClearSession = viewModel::clearSession,
                onOpenHistory = onOpenHistory,
                onSelectCandidate = viewModel::selectCandidate,
            )
        } else {
            CameraPermissionPrompt(onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) })
        }
    }
}

@Composable
private fun CameraCaptureView(
    mode: CameraMode,
    onModeChange: (CameraMode) -> Unit,
    singleState: CaptureState,
    items: List<CaptureItem>,
    frozenFrame: Bitmap?,
    onCaptured: (File) -> Unit,
    onResetSingle: () -> Unit,
    onCancelSingle: () -> Unit,
    onClearSession: () -> Unit,
    onOpenHistory: () -> Unit,
    onSelectCandidate: (ProductCandidate) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    val imageCapture = remember { ImageCapture.Builder().build() }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var reviewing by remember(mode) { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val cameraProvider = context.awaitCameraProvider()
        provider = cameraProvider
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        runCatching {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
        }.onFailure {
            Toast.makeText(context, "Couldn't start the camera: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }
    DisposableEffect(Unit) { onDispose { provider?.unbindAll() } }

    val capture: () -> Unit = {
        capturePhoto(
            context = context,
            imageCapture = imageCapture,
            onSaved = onCaptured,
            onError = { msg -> Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() },
        )
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        // Freeze on the shot we just took (single mode) so you can see what was captured.
        if (mode == CameraMode.Single && frozenFrame != null) {
            Image(
                bitmap = frozenFrame.asImageBitmap(),
                contentDescription = "Captured photo",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }

        when (mode) {
            CameraMode.Single -> when (val s = singleState) {
                is CaptureState.Idle -> ReadyChrome(mode, onModeChange) {
                    Hint("Snap an item — it’ll be identified and priced")
                    ShutterButton("Price check a photo", onClick = capture)
                }
                is CaptureState.Working -> SingleWorkingOverlay(
                    step = s.step,
                    onCancel = onCancelSingle,
                    onBackground = onResetSingle,
                )
                is CaptureState.NeedsGemini -> WorkingOverlay(s.message)
                is CaptureState.Success -> ResultOverlay(
                    state = s,
                    onOpenHistory = onOpenHistory,
                    onReset = onResetSingle,
                    onSelectCandidate = onSelectCandidate,
                )
                is CaptureState.Error -> ErrorOverlay(s.message, onReset = onResetSingle)
            }

            CameraMode.Continuous -> if (reviewing) {
                ContinuousResults(
                    items = items,
                    onKeepScanning = { reviewing = false },
                    onDone = {
                        onClearSession()
                        reviewing = false
                        onOpenHistory()
                    },
                )
            } else {
                ReadyChrome(mode, onModeChange) {
                    if (items.isNotEmpty()) SessionCounter(items)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ShutterButton("Snap", onClick = capture)
                        Spacer(Modifier.width(12.dp))
                        Button(onClick = { reviewing = true }, enabled = items.isNotEmpty()) {
                            Text("Done (${items.size})")
                        }
                    }
                }
            }
        }
    }
}

/** Shared chrome for the "ready to snap" states: the mode selector on top, [content] at the bottom. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.ReadyChrome(
    mode: CameraMode,
    onModeChange: (CameraMode) -> Unit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    ModeSelector(
        mode = mode,
        onModeChange = onModeChange,
        modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
    )
    Column(
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeSelector(mode: CameraMode, onModeChange: (CameraMode) -> Unit, modifier: Modifier = Modifier) {
    SingleChoiceSegmentedButtonRow(modifier) {
        SegmentedButton(
            selected = mode == CameraMode.Single,
            onClick = { onModeChange(CameraMode.Single) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
        ) { Text("Single") }
        SegmentedButton(
            selected = mode == CameraMode.Continuous,
            onClick = { onModeChange(CameraMode.Continuous) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
        ) { Text("Continuous") }
    }
}

@Composable
private fun Hint(text: String) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        tonalElevation = 3.dp,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
    }
}

@Composable
private fun ShutterButton(label: String, onClick: () -> Unit) {
    Button(onClick = onClick) {
        Icon(Icons.Filled.PhotoCamera, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

@Composable
private fun SessionCounter(items: List<CaptureItem>) {
    val priced = items.count { it.status is ItemStatus.Done }
    val working = items.count { it.status is ItemStatus.Working }
    val label = buildString {
        append("${items.size} snapped")
        if (priced > 0) append(" · $priced priced")
        if (working > 0) append(" · $working scanning")
    }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        tonalElevation = 3.dp,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
    }
}

@Composable
private fun WorkingOverlay(step: String) {
    Column(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = Color.White)
        Spacer(Modifier.height(16.dp))
        Text(step, color = Color.White, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Single-mode loader over the frozen shot: cancel the lookup, or let it finish in the background. */
@Composable
private fun SingleWorkingOverlay(step: String, onCancel: () -> Unit, onBackground: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = Color.White)
        Spacer(Modifier.height(16.dp))
        Text(step, color = Color.White, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onCancel) { Text("Cancel", color = Color.White) }
            Spacer(Modifier.width(12.dp))
            Button(onClick = onBackground) { Text("Snap another") }
        }
    }
}

@Composable
private fun ResultOverlay(
    state: CaptureState.Success,
    onOpenHistory: () -> Unit,
    onReset: () -> Unit,
    onSelectCandidate: (ProductCandidate) -> Unit,
) {
    val report = state.report
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center,
    ) {
        Card(Modifier.padding(24.dp)) {
            Column(Modifier.padding(20.dp)) {
                Text("Searched", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(report.searchTerm, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("identified via ${state.via}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                if (report.soldCount == 0) {
                    Text("No sold matches", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "Nothing genuinely matched these keywords — saved to history so you can see what was tried.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(Format.money(report.averagePrice, report.currency), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("average sold price", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Range ${Format.money(report.minPrice, report.currency)} – " +
                            "${Format.money(report.maxPrice, report.currency)} · ${report.soldCount} sold",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                if (state.alternatives.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Not the right match? Search instead:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    state.alternatives.forEach { candidate ->
                        CandidateRow(candidate, onClick = { onSelectCandidate(candidate) })
                    }
                }

                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onReset) { Text("Snap another") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onOpenHistory) { Text("See in History") }
                }
            }
        }
    }
}

/** One alternative the detector considered — tap to re-run the price check against it. */
@Composable
private fun CandidateRow(candidate: ProductCandidate, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                candidate.searchTerm,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            Text(
                "${candidate.confidence.name.lowercase()} confidence · ${candidate.via}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = "Search this instead",
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun ErrorOverlay(message: String, onReset: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center,
    ) {
        Card(Modifier.padding(24.dp)) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Couldn’t finish", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(message, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onReset) { Text("Try again") }
            }
        }
    }
}

@Composable
private fun ContinuousResults(items: List<CaptureItem>, onKeepScanning: () -> Unit, onDone: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center,
    ) {
        Card(Modifier.padding(16.dp).fillMaxWidth().fillMaxHeight(0.75f)) {
            Column(Modifier.padding(20.dp)) {
                Text("Results (${items.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(items, key = { it.id }) { item ->
                        ResultRow(item)
                        HorizontalDivider()
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onKeepScanning) { Text("Keep scanning") }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = onDone) { Text("Done") }
                }
            }
        }
    }
}

@Composable
private fun ResultRow(item: CaptureItem) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (val status = item.status) {
            is ItemStatus.Working -> {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("Scanning…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            is ItemStatus.Done -> {
                Column(Modifier.weight(1f)) {
                    Text(status.report.searchTerm, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1)
                    Text("via ${status.via}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (status.report.soldCount == 0) {
                    Text(
                        "No matches",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        Format.money(status.report.averagePrice, status.report.currency),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            is ItemStatus.Unidentified ->
                Text("Couldn’t identify this one", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            is ItemStatus.Failed ->
                Text("Price check failed", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun CameraPermissionPrompt(onRequest: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.PhotoCamera, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("Camera access needed", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "PriceFighter uses the camera to identify an item on-device and price-check it.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onRequest) { Text("Enable camera") }
    }
}

/** Decodes the captured JPEG downsampled (~1280px) for the frozen-frame preview. */
private fun decodeForDisplay(file: File): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0) return null
    var sample = 1
    val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
    while (maxDim / (sample * 2) >= 1280) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return runCatching { BitmapFactory.decodeFile(file.absolutePath, options) }.getOrNull()
}

private suspend fun Context.awaitCameraProvider(): ProcessCameraProvider = suspendCoroutine { cont ->
    val future = ProcessCameraProvider.getInstance(this)
    future.addListener({ cont.resume(future.get()) }, ContextCompat.getMainExecutor(this))
}

private fun capturePhoto(context: Context, imageCapture: ImageCapture, onSaved: (File) -> Unit, onError: (String) -> Unit) {
    val dir = File(context.cacheDir, "captures").apply { mkdirs() }
    val file = File(dir, "pricecheck_${System.currentTimeMillis()}.jpg")
    val options = ImageCapture.OutputFileOptions.Builder(file).build()
    imageCapture.takePicture(
        options,
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) = onSaved(file)
            override fun onError(exception: ImageCaptureException) = onError("Couldn't capture photo: ${exception.message}")
        },
    )
}

/** Tier 4 (single mode): hand the photo to the Gemini app; fall back to the system chooser. */
private fun shareToGemini(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val base = Intent(Intent.ACTION_SEND).apply {
        type = "image/*"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TEXT, PRICE_CHECK_PROMPT)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(Intent(base).setPackage(GEMINI_PACKAGE))
    } catch (e: ActivityNotFoundException) {
        context.startActivity(Intent.createChooser(base, "Price check with…").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
}
