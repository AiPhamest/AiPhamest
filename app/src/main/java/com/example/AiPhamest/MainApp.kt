package com.example.AiPhamest

import com.example.AiPhamest.data.PatientViewModel
import android.Manifest
import android.app.Application
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.*
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.launch
import com.example.AiPhamest.data.AppVMFactory
import com.example.AiPhamest.data.SideEffectViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun MainApp() {
    // ----- Permission Handling -----
    val recordAudioPermission = rememberPermissionState(Manifest.permission.RECORD_AUDIO)
    LaunchedEffect(recordAudioPermission.status) {
        when (val status = recordAudioPermission.status) {
            is PermissionStatus.Denied -> {
                if (!status.shouldShowRationale) {
                    recordAudioPermission.launchPermissionRequest()
                }
            }
            is PermissionStatus.Granted -> { /* Permission granted */ }
        }
    }

    val navController = rememberNavController()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val application = LocalContext.current.applicationContext as Application

    val allScreens = listOf(
        Screen.Scan, Screen.Schedules, Screen.Warnings,
        Screen.SideEffect, Screen.Patient, Screen.Settings
    )
    val bottomNavItems = listOf(Screen.Scan, Screen.Schedules, Screen.Warnings, Screen.SideEffect)
    val drawerScreens = listOf(Screen.Patient, Screen.Settings)

    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = currentBackStackEntry?.destination
    val currentScreen = allScreens.find { it.route == currentDestination?.route }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text("Menu", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge)
                drawerScreens.forEach { screen ->
                    NavigationDrawerItem(
                        label = { Text(screen.label) },
                        icon = { Icon(screen.icon, contentDescription = screen.label) },
                        selected = currentScreen?.route == screen.route,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                            scope.launch { drawerState.close() }
                        },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                    )
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(currentScreen?.label ?: "OneFileApp") },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Open drawer menu")
                        }
                    }
                )
            },
            bottomBar = {
                NavigationBar {
                    bottomNavItems.forEach { screen ->
                        NavigationBarItem(
                            icon = { Icon(screen.icon, contentDescription = screen.label) },
                            label = { Text(screen.label) },
                            selected = currentDestination?.route == screen.route,
                            onClick = {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.startDestinationId) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            }
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = Screen.Scan.route,
                modifier = Modifier.padding(innerPadding)
            ) {
                composable(Screen.Scan.route) { ScanScreen(navController = navController) }
                composable(Screen.Schedules.route) { SchedulesScreen() }
                composable(Screen.Warnings.route) { WarningsScreen() }
                composable(Screen.SideEffect.route) {
                    val sideEffectViewModel: SideEffectViewModel = viewModel(factory = AppVMFactory(application))
                    SideEffectScreen(sideEffectViewModel = sideEffectViewModel)
                }
                composable(Screen.Patient.route) {
                    val vm: PatientViewModel = viewModel(factory = AppVMFactory(application))
                    PatientScreen(viewModel = vm)
                }
                composable(Screen.Settings.route) { SettingsScreen() }
                composable(Screen.Detail.route) { backStackEntry ->
                    val itemId = backStackEntry.arguments?.getString("itemId")
                    DetailScreen(itemId = itemId)
                }
            }
        }
    }
}
