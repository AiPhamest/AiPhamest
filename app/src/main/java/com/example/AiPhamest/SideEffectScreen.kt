package com.example.AiPhamest

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.runtime.derivedStateOf
import androidx.core.app.ActivityCompat
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.filled.Delete
import com.example.AiPhamest.data.SideEffectEntity
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.example.AiPhamest.data.InputMode
import com.example.AiPhamest.data.SideEffectViewModel
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SideEffectScreen(sideEffectViewModel: SideEffectViewModel) {
    var showAddSideEffect by remember { mutableStateOf(false) }
    // Observe the side effects list from ViewModel
    val effects by sideEffectViewModel.effects.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Side Effects",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "Track and report your side effects",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            FloatingActionButton(
                onClick = { showAddSideEffect = true },
                modifier = Modifier.size(56.dp),
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "Add side effect",
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        // List of submitted side effects
        if (effects.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No side effects reported yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            // Show the list in a LazyColumn, take up all remaining space
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(effects) { effect ->
                    SideEffectCard(
                        effect = effect,
                        onDelete = { sideEffectViewModel.delete(it) }
                    )
                }
            }
        }

        // Show add side effect section when plus button is clicked
        if (showAddSideEffect) {
            ModalBottomSheet(
                onDismissRequest = { showAddSideEffect = false }
            ) {
                AddSideEffectSection(
                    onDismiss = { showAddSideEffect = false },
                    sideEffectViewModel = sideEffectViewModel
                )
            }
        }

    }
}


// Helper composable to display each effect as a card
@Composable
fun SideEffectCard(
    effect: SideEffectEntity,
    onDelete: (SideEffectEntity) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )

    ) {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp).weight(1f)) { // <-- Add .weight(1f) here for spacing!
                Text(
                    effect.description,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Input: ${effect.inputMode.name.lowercase().replaceFirstChar { it.uppercase() }}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "Occurred: ${effect.occurredAt}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = { onDelete(effect) },
                modifier = Modifier.align(Alignment.Top) // Make sure it's aligned!
            ) {
                Icon(Icons.Default.Delete, contentDescription = "Delete Side Effect",tint = Color.Red)
            }
        }
    }
}


@Composable
fun AddSideEffectSection(
    onDismiss: () -> Unit,
    sideEffectViewModel: SideEffectViewModel
) {
    var inputText by remember { mutableStateOf("") }
    var isRecording by remember { mutableStateOf(false) }
    var inputMode by remember { mutableStateOf(InputMode.MANUAL) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )

    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header with close button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Add Side Effect",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Close",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Input Mode Selection
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Manual Input Button
                FilterChip(
                    onClick = { inputMode = InputMode.MANUAL },
                    label = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = "Manual input",
                                modifier = Modifier.size(18.dp)
                            )
                            Text("Type")
                        }
                    },
                    selected = inputMode == InputMode.MANUAL,
                    modifier = Modifier.weight(1f),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        containerColor = MaterialTheme.colorScheme.surface,
                        labelColor = MaterialTheme.colorScheme.onSurface,
                        iconColor = MaterialTheme.colorScheme.primary
                    )
                )

                FilterChip(
                    onClick = { inputMode = InputMode.VOICE },
                    label = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.Mic,
                                contentDescription = "Voice input",
                                modifier = Modifier.size(18.dp)
                            )
                            Text("Voice")
                        }
                    },
                    selected = inputMode == InputMode.VOICE,
                    modifier = Modifier.weight(1f),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        containerColor = MaterialTheme.colorScheme.surface,
                        labelColor = MaterialTheme.colorScheme.onSurface,
                        iconColor = MaterialTheme.colorScheme.primary
                    )
                )

            }

            // Combined Input Section
            when (inputMode) {
                InputMode.MANUAL -> {
                    ManualInputSection(
                        inputText = inputText,
                        onTextChange = { inputText = it }
                    )
                }
                InputMode.VOICE -> {
                    VoiceInputSection(
                        inputText = inputText,
                        isRecording = isRecording,
                        onStartRecording = { isRecording = true },
                        onStopRecording = { isRecording = false },
                        onTextChange = { inputText = it } // This ensures the text gets updated
                    )
                }
            }

            // Submit Side Effect Button
            Button(
                onClick = {
                    sideEffectViewModel.log(
                        desc = inputText,
                        inputMode = inputMode,
                        // latitude/longitude can be passed if you add location later
                    )
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = inputText.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    Icons.Default.Send,
                    contentDescription = "Submit",
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Submit Side Effect")
            }
        }
    }
}

@Composable
private fun ManualInputSection(
    inputText: String,
    onTextChange: (String) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Describe Your Side Effects",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )

        OutlinedTextField(
            value = inputText,
            onValueChange = onTextChange,
            placeholder = {
                Text("Describe any side effects you're experiencing...")
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp),
            maxLines = 5,
            shape = RoundedCornerShape(12.dp)
        )

        Text(
            text = "Be as detailed as possible. Include timing, severity, and any relevant context.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun VoiceInputSection(
    inputText: String,
    isRecording: Boolean,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onTextChange: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showSettingsHint by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var retryCount by remember { mutableStateOf(0) }

    // Check permission status
    val hasMicPermission = remember(context) {
        derivedStateOf {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        }
    }.value

    // Permission launcher
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            showSettingsHint = false
            errorMsg = null
            scope.launch {
                delay(100)
                onStartRecording()
            }
        } else {
            val shouldShowRationale = ActivityCompat.shouldShowRequestPermissionRationale(
                context.findActivity(),
                Manifest.permission.RECORD_AUDIO
            )
            showSettingsHint = !shouldShowRationale
            errorMsg = "Microphone permission is required for voice input"
        }
    }

    // Speech recognizer management with retry logic
    var recognizer by remember { mutableStateOf<VoiceRecognizer?>(null) }

    DisposableEffect(isRecording, hasMicPermission) {
        if (isRecording && hasMicPermission) {
            if (!context.isNetworkAvailable()) {
                errorMsg = "No internet connection. Voice recognition requires internet access."
                onStopRecording()
            } else {
                recognizer = VoiceRecognizer(
                    context = context,
                    onResult = { result ->
                        onTextChange(result) // This will update the inputText
                        onStopRecording()
                        errorMsg = null
                        retryCount = 0
                    },
                    onError = { err ->
                        val isServerError = err.contains("Speech service unavailable") ||
                                err.contains("server") ||
                                err.contains("Server")

                        if (isServerError && retryCount < 2) {
                            retryCount++
                            errorMsg = "Connection issue, retrying... (${retryCount}/3)"
                            scope.launch {
                                delay(1000)
                                if (isRecording) {
                                    recognizer?.stopListening()
                                    delay(500)
                                    recognizer = VoiceRecognizer(
                                        context = context,
                                        onResult = { result ->
                                            onTextChange(result) // This will update the inputText
                                            onStopRecording()
                                            errorMsg = null
                                            retryCount = 0
                                        },
                                        onError = { retryErr ->
                                            if (retryCount >= 2) {
                                                errorMsg = "Speech service unavailable. Please try again later."
                                                onStopRecording()
                                            }
                                        }
                                    ).also { it.startListening() }
                                }
                            }
                        } else {
                            errorMsg = when {
                                err.contains("Speech service unavailable") -> "Speech service is temporarily unavailable. Please try again in a few moments."
                                err.contains("Network") -> "Network connection issue. Please check your internet connection."
                                err.contains("No speech") -> "No speech detected. Please try speaking more clearly."
                                else -> err
                            }
                            onStopRecording()
                            retryCount = 0
                        }
                    }
                ).also {
                    try {
                        it.startListening()
                    } catch (e: Exception) {
                        errorMsg = "Failed to start voice recognition: ${e.message}"
                        onStopRecording()
                    }
                }
            }
        } else if (isRecording && !hasMicPermission) {
            errorMsg = "Microphone permission not granted."
            onStopRecording()
        } else {
            recognizer?.stopListening()
            recognizer = null
        }

        onDispose {
            recognizer?.stopListening()
            recognizer = null
        }
    }

    // Rest of your UI code remains the same...
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = "Voice Recording",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )

        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            FloatingActionButton(
                onClick = {
                    errorMsg = null
                    retryCount = 0
                    if (isRecording) {
                        onStopRecording()
                    } else {
                        if (hasMicPermission) {
                            onStartRecording()
                        } else {
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                },
                modifier = Modifier.size(64.dp),
                containerColor = if (isRecording)
                    MaterialTheme.colorScheme.error
                else
                    MaterialTheme.colorScheme.primary
            ) {
                Icon(
                    if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                    contentDescription = if (isRecording) "Stop recording" else "Start recording",
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        // Status text
        Text(
            text = when {
                !hasMicPermission -> "🎤 Microphone permission required"
                isRecording -> "🔴 Recording... Tap to stop"
                retryCount > 0 -> "🔄 Retrying connection..."
                else -> "Tap the microphone to start recording"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = when {
                !hasMicPermission -> MaterialTheme.colorScheme.error
                isRecording -> MaterialTheme.colorScheme.error
                retryCount > 0 -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.fillMaxWidth()
        )

        // Error messages with retry option
        if (errorMsg != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = errorMsg!!,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall
                    )

                    if (errorMsg!!.contains("Speech service unavailable") && !isRecording) {
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(
                            onClick = {
                                errorMsg = null
                                retryCount = 0
                                if (hasMicPermission) {
                                    onStartRecording()
                                }
                            }
                        ) {
                            Text("Try Again")
                        }
                    }
                }
            }
        }

        if (showSettingsHint) {
            TextButton(
                onClick = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${context.packageName}")
                        )
                    )
                },
                modifier = Modifier.align(Alignment.Start)
            ) {
                Text("Open app settings to enable microphone")
            }
        }

        if (inputText.isNotBlank()) {
            HorizontalDivider()
            Text(
                text = "Transcribed Text:",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            OutlinedTextField(
                value = inputText,
                onValueChange = onTextChange,
                placeholder = { Text("Your transcribed text will appear here...") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp),
                maxLines = 4,
                shape = RoundedCornerShape(12.dp)
            )
            Text(
                text = "You can edit the transcribed text if needed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

fun Context.isNetworkAvailable(): Boolean {
    val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = connectivityManager.activeNetwork ?: return false
    val activeNetwork = connectivityManager.getNetworkCapabilities(network) ?: return false
    return when {
        activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> true
        activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> true
        activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> true
        else -> false
    }
}

/* ---------- tiny helper to get Activity from Compose ---------- */
private fun Context.findActivity(): Activity =
    generateSequence(this) { (it as? ContextWrapper)?.baseContext }
        .filterIsInstance<Activity>()
        .first()


