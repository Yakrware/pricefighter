package com.pricefighter.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.pricefighter.data.vision.ProductIdentifier
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** Gemini app package; tier-4 fallback shares the photo here with a price-check prompt. */
private const val GEMINI_PACKAGE = "com.google.android.apps.bard"
private const val PRICE_CHECK_PROMPT =
    "Price check this item. Identify the product and its model number, then run a price check " +
        "and report the typical sold price."

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
    val state by viewModel.state.collectAsStateWithLifecycle()
    var lastFile by remember { mutableStateOf<File?>(null) }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasPermission = granted }

    // Tier 4: nothing identified on-device → hand the photo to the Gemini app.
    LaunchedEffect(state) {
        if (state is CaptureState.NeedsGemini) {
            lastFile?.let { shareToGemini(context, it) }
            viewModel.reset()
        }
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
                state = state,
                onCaptured = { file -> lastFile = file; viewModel.onPhotoCaptured(file) },
                onReset = viewModel::reset,
                onOpenHistory = onOpenHistory,
            )
        } else {
            CameraPermissionPrompt(onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) })
        }
    }
}

@Composable
private fun CameraCaptureView(
    state: CaptureState,
    onCaptured: (File) -> Unit,
    onReset: () -> Unit,
    onOpenHistory: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    val imageCapture = remember { ImageCapture.Builder().build() }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    LaunchedEffect(Unit) {
        val cameraProvider = context.awaitCameraProvider()
        provider = cameraProvider
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        runCatching {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageCapture,
            )
        }.onFailure {
            Toast.makeText(context, "Couldn't start the camera: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }
    DisposableEffect(Unit) { onDispose { provider?.unbindAll() } }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        when (state) {
            is CaptureState.Idle -> {
                Surface(
                    modifier = Modifier.align(Alignment.TopCenter).padding(16.dp),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                    tonalElevation = 3.dp,
                ) {
                    Text(
                        "Snap an item — it’ll be identified and priced",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
                Button(
                    onClick = {
                        capturePhoto(
                            context = context,
                            imageCapture = imageCapture,
                            onSaved = onCaptured,
                            onError = { msg -> Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() },
                        )
                    },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp),
                ) {
                    Icon(Icons.Filled.PhotoCamera, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Price check a photo")
                }
            }

            is CaptureState.Working -> WorkingOverlay(state.step)
            is CaptureState.NeedsGemini -> WorkingOverlay(state.message)
            is CaptureState.Success -> ResultOverlay(state, onOpenHistory = onOpenHistory, onReset = onReset)
            is CaptureState.Error -> ErrorOverlay(state.message, onReset = onReset)
        }
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

@Composable
private fun ResultOverlay(state: CaptureState.Success, onOpenHistory: () -> Unit, onReset: () -> Unit) {
    val report = state.report
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center,
    ) {
        Card(Modifier.padding(24.dp)) {
            Column(Modifier.padding(20.dp)) {
                Text(report.searchTerm, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("identified via ${state.via}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Text(
                    Format.money(report.averagePrice, report.currency),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text("average sold price", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Range ${Format.money(report.minPrice, report.currency)} – " +
                        "${Format.money(report.maxPrice, report.currency)} · ${report.soldCount} sold",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(18.dp))
                androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onReset) { Text("Snap another") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onOpenHistory) { Text("See in History") }
                }
            }
        }
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
private fun CameraPermissionPrompt(onRequest: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.PhotoCamera,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
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

private suspend fun Context.awaitCameraProvider(): ProcessCameraProvider = suspendCoroutine { cont ->
    val future = ProcessCameraProvider.getInstance(this)
    future.addListener({ cont.resume(future.get()) }, ContextCompat.getMainExecutor(this))
}

private fun capturePhoto(
    context: Context,
    imageCapture: ImageCapture,
    onSaved: (File) -> Unit,
    onError: (String) -> Unit,
) {
    val dir = File(context.cacheDir, "captures").apply { mkdirs() }
    val file = File(dir, "pricecheck_${System.currentTimeMillis()}.jpg")
    val options = ImageCapture.OutputFileOptions.Builder(file).build()
    imageCapture.takePicture(
        options,
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) = onSaved(file)
            override fun onError(exception: ImageCaptureException) =
                onError("Couldn't capture photo: ${exception.message}")
        },
    )
}

/** Tier 4: hand the photo to the Gemini app; fall back to the system chooser. */
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
        context.startActivity(
            Intent.createChooser(base, "Price check with…").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }
}
