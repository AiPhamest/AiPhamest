// File: ScanScreen.kt
package com.example.AiPhamest

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.viewmodel.compose.viewModel
import android.Manifest
import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import com.example.AiPhamest.llm.DrugNormalizer
import com.example.AiPhamest.llm.PrescriptionExtractor
import com.example.AiPhamest.data.AppVMFactory
import com.example.AiPhamest.data.PrescriptionViewModel
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*


private enum class ProcessingStage { DOWNLOADING, EXTRACTING }
private enum class PrimaryActionState { IDLE, PROCESSING, READY_TO_SAVE, SAVING, SAVED }
private val ButtonCornerRadius = 16.dp


@OptIn(ExperimentalMaterial3Api::class, ExperimentalAnimationApi::class)
@Composable
fun ScanScreen(
    navController: NavController,
    prescriptionVM: PrescriptionViewModel = viewModel(
        factory = AppVMFactory(LocalContext.current.applicationContext as Application)
    )
) {
    var showCamera by rememberSaveable { mutableStateOf(false) }
    var capturedBitmap by remember { mutableStateOf<Bitmap?>(null) }

    var editableText by remember { mutableStateOf("") }
    var lastSavedText by remember { mutableStateOf("") }
    var showResult by remember { mutableStateOf(false) }
    var stage by remember { mutableStateOf<ProcessingStage?>(null) }
    var downloadProgress by remember { mutableStateOf(0f) }
    var imageCapture: ImageCapture? by remember { mutableStateOf(null) }
    var primaryState by remember { mutableStateOf(PrimaryActionState.IDLE) }


    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }

    val latestSavedRaw by prescriptionVM.lastSavedRawText.collectAsState()
    val tempOcr by prescriptionVM.tempOcr.collectAsState()

    val scrollState = rememberScrollState()
    LaunchedEffect(showResult) {
        if (showResult) {
            withFrameNanos { }
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    LaunchedEffect(latestSavedRaw, tempOcr) {
        if (editableText.isNotBlank()) return@LaunchedEffect
        val seed = tempOcr ?: latestSavedRaw
        if (!seed.isNullOrBlank()) {
            editableText = seed
            showResult = true
            lastSavedText = latestSavedRaw ?: ""
            primaryState =
                if (seed == lastSavedText && seed.isNotBlank()) PrimaryActionState.SAVED
                else PrimaryActionState.READY_TO_SAVE
        }
    }

    LaunchedEffect(editableText, primaryState) {
        if (primaryState == PrimaryActionState.SAVED &&
            editableText.isNotBlank() &&
            editableText != lastSavedText
        ) {
            primaryState = PrimaryActionState.READY_TO_SAVE
        }
        if (primaryState != PrimaryActionState.PROCESSING) {
            prescriptionVM.setTempOcr(
                editableText.takeIf { it.isNotBlank() && it != lastSavedText }
            )
        }
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            showCamera = true
            showResult = false
            if (capturedBitmap != null) primaryState = PrimaryActionState.IDLE
        } else scope.launch {
            snackbarHost.showSnackbar("Camera permission denied")
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            runCatching {
                MediaStore.Images.Media.getBitmap(ctx.contentResolver, it)
            }.onSuccess { bmp ->
                capturedBitmap = bmp
                showCamera = false
                showResult = false
                primaryState = PrimaryActionState.IDLE
            }.onFailure { e ->
                Log.e("ScanScreen", "Gallery pick failed", e)
                scope.launch { snackbarHost.showSnackbar("Failed to load image") }
            }
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHost) },
        contentWindowInsets = WindowInsets(0)) { inner ->

        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp),


            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            AnimatedVisibility(
                visible = showCamera,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(450.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black.copy(alpha = .65f))
                ) {
                    CameraPreview(
                        onImageCapture = { imageCapture = it },
                        modifier = Modifier
                            .matchParentSize()
                            .alpha(.95f)
                    )

                    // ---- [NEW!] Capture Button on bottom center of camera preview ----
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 24.dp, start = 16.dp, end = 16.dp)
                    ) {
                        CaptureButton(
                            imageCapture = imageCapture,
                            onCaptured = { bmp ->
                                capturedBitmap = bmp
                                showCamera = false
                                showResult = false
                                primaryState = PrimaryActionState.IDLE
                            },
                            showSnackbar = { msg ->
                                scope.launch { snackbarHost.showSnackbar(msg) }
                            }
                        )
                    }
                }
            }

            if (!showCamera) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = { permLauncher.launch(Manifest.permission.CAMERA) },
                        enabled = primaryState !in listOf(PrimaryActionState.PROCESSING, PrimaryActionState.SAVING),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(ButtonCornerRadius),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                            contentColor = Color.White,                         // <- text & icon become white
                            disabledContentColor = Color.White.copy(alpha = .6f)
                        )
                    ) {
                        Icon(Icons.Filled.CameraAlt, contentDescription = null) // tints to white automatically
                        Spacer(Modifier.width(8.dp))
                        Text("Camera")                                          // white
                    }
                    OutlinedButton(
                        onClick = { galleryLauncher.launch("image/*") },
                        enabled = primaryState !in listOf(
                            PrimaryActionState.PROCESSING,
                            PrimaryActionState.SAVING
                        ),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(ButtonCornerRadius),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (capturedBitmap != null)
                                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                            else
                                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f),
                            contentColor = Color.White,                          // <- text & icon white
                            disabledContentColor = Color.White.copy(alpha = .6f) // <- optional
                        )
                    ) {
                        Icon(Icons.Filled.Image, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Gallery")
                    }
                }
            }

            if (!showResult) {
                capturedBitmap?.let { bmp ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                    ) {
                        Column {
                            Box {
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = "Captured",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(450.dp)
                                        .clip(
                                            RoundedCornerShape(
                                                topStart = 12.dp,
                                                topEnd = 12.dp
                                            )
                                        )
                                )
                                Box(
                                    Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(8.dp)
                                        .background(
                                            Color.Black.copy(alpha = 0.6f),
                                            RoundedCornerShape(12.dp)
                                        )
                                        .padding(8.dp)
                                ) {
                                    Text(
                                        "Ready to process",
                                        color = Color.White,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                TextButton(onClick = {
                                    capturedBitmap = null
                                    primaryState = PrimaryActionState.IDLE
                                    showCamera = true         // <--- ADD THIS LINE!
                                    showResult = false        // (optional: keeps the UI clean)
                                }) {
                                    Text("Retake", color = MaterialTheme.colorScheme.error)
                                }

                                TextButton(onClick = {
                                    galleryLauncher.launch("image/*")
                                }) {
                                    Text("Choose Different")
                                }
                            }
                        }
                    }
                }
            }

            UnifiedProcessSaveButton(
                state = primaryState,
                hasBitmap = capturedBitmap != null,
                progress = if (
                    primaryState == PrimaryActionState.PROCESSING &&
                    stage == ProcessingStage.DOWNLOADING
                ) downloadProgress else null
            ) {
                when (primaryState) {
                    PrimaryActionState.IDLE -> {
                        val src = capturedBitmap
                        if (src == null) {
                            scope.launch {
                                snackbarHost.showSnackbar("Capture or select an image first.")
                            }
                            return@UnifiedProcessSaveButton
                        }
                        PrescriptionExtractor.close()
                        primaryState = PrimaryActionState.PROCESSING
                        stage = ProcessingStage.EXTRACTING
                        downloadProgress = 0f
                        showResult = false
                        editableText = ""
                        prescriptionVM.setTempOcr(null)

                        val workingBitmap = src.copy(Bitmap.Config.ARGB_8888, false)
                        scope.launch {
                            val raw = runCatching {
                                PrescriptionExtractor.extract(ctx, workingBitmap) { p ->
                                    if (p < 1f) {
                                        stage = ProcessingStage.DOWNLOADING
                                        downloadProgress = p
                                    } else {
                                        stage = ProcessingStage.EXTRACTING
                                    }
                                }
                            }.getOrElse { e ->
                                Log.e("ScanScreen", "Extraction failed", e)
                                ""
                            }

                            val drugList = DrugNormalizer.loadDrugList(ctx)
                            val normalized =
                                DrugNormalizer.normalizeWithLLM(ctx, raw, drugList)

                            editableText = normalized
                            showResult = normalized.isNotBlank()
                            stage = null
                            primaryState = if (normalized.isBlank()) {
                                snackbarHost.showSnackbar("No text extracted.")
                                PrimaryActionState.IDLE
                            } else {
                                prescriptionVM.setTempOcr(normalized)
                                if (normalized == lastSavedText) PrimaryActionState.SAVED
                                else PrimaryActionState.READY_TO_SAVE
                            }
                        }
                    }
                    PrimaryActionState.READY_TO_SAVE -> {
                        if (missingPack(editableText)) {
                            scope.launch {
                                snackbarHost.showSnackbar(
                                    "Please add pack quantity (e.g. 30p) or volume (e.g. 200ml) after each line."
                                )
                            }
                            return@UnifiedProcessSaveButton
                        }
                        primaryState = PrimaryActionState.SAVING
                        scope.launch {
                            withFrameNanos { }
                            val start = System.currentTimeMillis()
                            var error: Throwable? = null

                            withContext(Dispatchers.IO) {
                                try {
                                    capturedBitmap?.let { saveBitmapToGallery(ctx, it) }
                                    prescriptionVM.ingestOcr(editableText)
                                } catch (t: Throwable) {
                                    error = t
                                }
                            }

                            // copy to an immutable
                            val e = error
                            if (e != null) {
                                primaryState = PrimaryActionState.READY_TO_SAVE
                                snackbarHost.showSnackbar("Save failed: ${e.message}")
                            } else {
                                lastSavedText = editableText
                                prescriptionVM.setTempOcr(null)
                                primaryState = PrimaryActionState.SAVED
                                snackbarHost.showSnackbar("Saved prescription lines.")
                            }
                        }
                    }
                    else -> Unit
                }
            }

            if (!latestSavedRaw.isNullOrBlank() || !tempOcr.isNullOrBlank()) {
                OutlinedButton(
                    onClick = {
                        editableText = ""
                        lastSavedText = ""
                        showResult = false
                        primaryState = PrimaryActionState.IDLE
                        prescriptionVM.clearAllData()
                        prescriptionVM.clearLastSaved()
                        prescriptionVM.setTempOcr(null)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.1f),
                        contentColor = Color.White,                              // <- make text & icon white
                        disabledContentColor = Color.White.copy(alpha = 0.6f)
                    ),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.5f))  // <- optional: white border
                ) {
                    Icon(
                        Icons.Outlined.DeleteOutline,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)                           // inherits white tint
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Clear All Data")                                       // white
                }
            }

            if (showResult) {
                ExtractionList(
                    rawText = editableText,
                    onTextChange = { editableText = it }
                )

            }

            if (primaryState == PrimaryActionState.SAVED) {
                Spacer(Modifier.height(8.dp))
                StartFirstDoseButton(prescriptionVM = prescriptionVM)
            }
        }
    }

    ProcessingDialog(
        stage = stage ?: ProcessingStage.EXTRACTING,
        progress = downloadProgress,
        show = primaryState == PrimaryActionState.PROCESSING && stage != null
    )

    DisposableEffect(Unit) {
        onDispose { PrescriptionExtractor.close() }
    }
}

@Composable
private fun UnifiedProcessSaveButton(
    state: PrimaryActionState,
    hasBitmap: Boolean,
    progress: Float?,
    onClick: () -> Unit
) {
    val green = Color(0xFF2E7D32)
    val primary = MaterialTheme.colorScheme.primary
    val targetColor = when (state) {
        PrimaryActionState.IDLE, PrimaryActionState.PROCESSING -> primary
        PrimaryActionState.READY_TO_SAVE,
        PrimaryActionState.SAVING,
        PrimaryActionState.SAVED -> green
    }
    val animatedColor by animateColorAsState(targetColor, label = "btnColor")
    val label = when (state) {
        PrimaryActionState.IDLE -> "Process"
        PrimaryActionState.PROCESSING ->
            if (progress != null && progress < 1f)
                "Downloading ${(progress * 100).toInt()}%"
            else "Processing…"
        PrimaryActionState.READY_TO_SAVE -> "Save Text"
        PrimaryActionState.SAVING -> "Saving…"
        PrimaryActionState.SAVED -> "Saved"
    }
    val enabled = when (state) {
        PrimaryActionState.IDLE -> hasBitmap
        PrimaryActionState.READY_TO_SAVE -> true
        else -> false
    }
    val showSpinner =
        state == PrimaryActionState.PROCESSING || state == PrimaryActionState.SAVING

    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(ButtonCornerRadius), // <-- ADD THIS LINE
        colors = ButtonDefaults.buttonColors(
            containerColor = animatedColor,
            disabledContainerColor = animatedColor.copy(alpha = 0.6f)
        ),
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 14.dp, horizontal = 20.dp)
    ) {
        if (showSpinner) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                modifier = Modifier
                    .size(18.dp)
                    .padding(end = 10.dp),
                color = MaterialTheme.colorScheme.onPrimary
            )
        } else if (state == PrimaryActionState.SAVED) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                modifier = Modifier
                    .size(18.dp)
                    .padding(end = 8.dp)
            )
        }
        Text(label)
    }
}

@Composable
private fun ProcessingDialog(
    stage: ProcessingStage,
    progress: Float,
    show: Boolean
) {
    if (!show) return
    AlertDialog(
        onDismissRequest = {},
        confirmButton = {},
        title = {
            Text(
                if (stage == ProcessingStage.DOWNLOADING)
                    "Downloading Model"
                else
                    "Processing Prescription"
            )
        },
        text = {
            when (stage) {
                ProcessingStage.DOWNLOADING -> Column {
                    Text("Downloading ${(progress * 100).toInt()}%")
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = progress.coerceIn(0f, 1f),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                ProcessingStage.EXTRACTING -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator()
                    Text("Running extraction…")
                }
            }
        },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        )
    )
}

@Composable
private fun CameraPreview(
    onImageCapture: (ImageCapture) -> Unit,
    modifier: Modifier = Modifier
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val ctx = LocalContext.current

    Box(modifier = modifier) {
        AndroidView(factory = {
            val view = PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                // 👇 Force TextureView instead of SurfaceView
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }

            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().apply {
                    setSurfaceProvider(view.surfaceProvider)
                }
                val imgCap = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                try {
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imgCap
                    )
                    onImageCapture(imgCap)
                } catch (e: Exception) {
                    Log.e("CameraPreview", "Bind failed", e)
                }
            }, ContextCompat.getMainExecutor(ctx))

            view
        }, modifier = modifier)

        Box(
            Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .background(
                        Color.White.copy(alpha = 0.1f),
                        RoundedCornerShape(12.dp)
                    )
                    .padding(2.dp)
            ) {
                Text(
                    "Position prescription here",
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp)
                        .background(
                            Color.Black.copy(alpha = 0.6f),
                            RoundedCornerShape(12.dp)
                        )
                        .padding(12.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun CaptureButton(
    imageCapture: ImageCapture?,
    onCaptured: (Bitmap) -> Unit,
    showSnackbar: (String) -> Unit
) {
    val ctx = LocalContext.current
    var isCapturing by remember { mutableStateOf(false) }

    Button(
        onClick = {
            val cap = imageCapture ?: return@Button showSnackbar("Camera not ready")
            isCapturing = true
            val file = createPhotoFile(ctx)
            val opts = ImageCapture.OutputFileOptions.Builder(file).build()
            cap.takePicture(
                opts,
                ContextCompat.getMainExecutor(ctx),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(res: ImageCapture.OutputFileResults) {
                        isCapturing = false
                        // Fix orientation before sending bitmap up!
                        val correctedBitmap = getCorrectlyOrientedBitmap(file.absolutePath)
                        onCaptured(correctedBitmap)
                    }

                    override fun onError(exception: ImageCaptureException) {
                        isCapturing = false
                        Log.e("Capture", "Save failed", exception)
                        showSnackbar("Capture failed")
                    }
                }
            )
        },
        enabled = !isCapturing,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = Color.White,
            disabledContentColor = Color.White.copy(alpha = .6f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        AnimatedContent(targetState = isCapturing) { capturing ->
            if (capturing) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Capturing...")
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CameraAlt, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Capture")
                }
            }
        }
    }
}


private fun createPhotoFile(ctx: Context): File {
    val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: ctx.cacheDir
    return File.createTempFile("IMG_${timeStamp}_", ".jpg", dir)
}

private fun saveBitmapToGallery(context: Context, bmp: Bitmap): Uri? {
    val name = "prescription_${System.currentTimeMillis()}.jpg"
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(
            MediaStore.Images.Media.RELATIVE_PATH,
            Environment.DIRECTORY_PICTURES + "/OneFileApp"
        )
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val resolver = context.contentResolver
    val uri =
        resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null.also {
                Log.e("GallerySave", "Insert returned null")
            }
    return try {
        resolver.openOutputStream(uri)?.use { out ->
            if (!bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)) {
                Log.e("GallerySave", "Bitmap compress failed")
            }
        }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        Log.d("GallerySave", "Saved image: $uri")
        uri
    } catch (e: Exception) {
        Log.e("GallerySave", "Save failed", e)
        null
    }
}

fun getCorrectlyOrientedBitmap(filePath: String): Bitmap {
    val bitmap = BitmapFactory.decodeFile(filePath)
    val exif = ExifInterface(filePath)
    val orientation = exif.getAttributeInt(
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.ORIENTATION_NORMAL
    )
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
    }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}