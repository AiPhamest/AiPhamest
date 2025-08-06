// File: MainActivity.kt
package com.example.AiPhamest

import android.Manifest
import android.app.AlarmManager
import android.content.Context // <<< FIX: Added missing import
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager // <<< FIX: Added missing import
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // <<< FIX: Explicitly request the exact alarm permission on startup >>>
        requestExactAlarmPermission()

        setContent {
            OneFileAppTheme {
                NotificationPermissionGate {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        MainApp()

                        // <<< FIX: Add a check to warn user about battery optimization >>>
                        BatteryOptimizationWarning()
                    }
                }
            }
        }
    }

    // <<< FIX: Function to navigate user to grant exact alarm permission >>>
    private fun requestExactAlarmPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
            if (!alarmManager.canScheduleExactAlarms()) {
                val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                startActivity(intent)
            }
        }
    }
}


// <<< FIX: New composable to manage the battery optimization warning >>>
@Composable
private fun BatteryOptimizationWarning() {
    val context = LocalContext.current
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val packageName = context.packageName

    var showDialog by remember { mutableStateOf(false) }

    // Check only once when the composable is first launched
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                showDialog = true // If not ignoring, plan to show the dialog
            }
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Reliability Warning") },
            text = { Text("For reminders to work correctly, please disable battery optimization for this app. This can usually be found in your phone's Settings > Apps > [Your App Name] > Battery.") },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text("OK")
                }
            }
        )
    }
}

// Notification permission gate for Android 13+ (API 33+)
@Composable
private fun NotificationPermissionGate(content: @Composable () -> Unit) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        content(); return
    }

    val ctx = LocalContext.current
    val alreadyGranted = remember {
        ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }
    var promptShown by remember { mutableStateOf(alreadyGranted) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { promptShown = true }

    LaunchedEffect(Unit) {
        if (!alreadyGranted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    if (promptShown) content()
}

// Navigation model
sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    object Scan       : Screen("scan",        "Scan",        Icons.Default.Home)
    object Schedules  : Screen("schedules",   "Schedules",   Icons.Default.DateRange)
    object Warnings   : Screen("warnings",    "Warnings",    Icons.Default.Warning)
    object SideEffect : Screen("side_effect", "Side Effect", Icons.Default.Info)
    object Patient    : Screen("patient",     "Patient",     Icons.Default.Person)
    object Settings   : Screen("settings",    "Settings",    Icons.Default.Settings)
    object Detail     : Screen("detail/{itemId}", "Detail",  Icons.Default.ArrowBack) {
        fun createRoute(itemId: String) = "detail/$itemId"
    }
}

// Theme stub
@Composable
fun OneFileAppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(),
        typography  = Typography(),
        content     = content
    )
}

// Preview stub
@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
    OneFileAppTheme { MainApp() }
}