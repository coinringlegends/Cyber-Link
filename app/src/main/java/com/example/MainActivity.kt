package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.ui.screens.BluetoothHubScreen
import com.example.ui.screens.CallsScreen
import com.example.ui.screens.ChatsContactsScreen
import com.example.ui.screens.CyberMapScreen
import com.example.ui.screens.SystemStatusScreen
import com.example.ui.theme.CyberLinkTheme
import com.example.viewmodel.CyberLinkViewModel
import kotlinx.serialization.Serializable

@Serializable
sealed class CyberRoute {
    @Serializable data object Hub : CyberRoute()
    @Serializable data object Chats : CyberRoute()
    @Serializable data object Calls : CyberRoute()
    @Serializable data object Map : CyberRoute()
    @Serializable data object System : CyberRoute()
}

private data class NavDestinationItem(
    val label: String,
    val icon: ImageVector,
    val route: CyberRoute,
    val qualifiedName: String,
    val testTag: String,
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CyberLinkTheme {
                CyberLinkRoot()
            }
        }
    }
}

@Composable
fun CyberLinkRoot(
    viewModel: CyberLinkViewModel = viewModel(),
) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val statusBanner by viewModel.statusBanner.collectAsStateWithLifecycle()

    var permissionTick by remember { mutableIntStateOf(0) }

    val bluetoothPerms = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
            )
        }
    }

    val btLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissionTick++ }

    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissionTick++ }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { permissionTick++ }

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { permissionTick++ }

    val hasBtPerms = remember(permissionTick) {
        bluetoothPerms.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }
    val hasLocationPerm = remember(permissionTick) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }
    val hasCameraPerm = remember(permissionTick) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    }
    val hasMicPerm = remember(permissionTick) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    LaunchedEffect(hasBtPerms) {
        if (hasBtPerms) {
            viewModel.ensureBluetoothAdvertisingAndStandby()
        }
    }

    LaunchedEffect(statusBanner) {
        val msg = statusBanner ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.clearBanner()
    }

    val destinations = remember {
        listOf(
            NavDestinationItem("Netz", Icons.Default.Bluetooth, CyberRoute.Hub, CyberRoute.Hub::class.qualifiedName!!, "nav_hub"),
            NavDestinationItem("Chats", Icons.AutoMirrored.Filled.Chat, CyberRoute.Chats, CyberRoute.Chats::class.qualifiedName!!, "nav_chats"),
            NavDestinationItem("Anrufe", Icons.Default.Call, CyberRoute.Calls, CyberRoute.Calls::class.qualifiedName!!, "nav_calls"),
            NavDestinationItem("Cyber Map", Icons.Default.Map, CyberRoute.Map, CyberRoute.Map::class.qualifiedName!!, "nav_map"),
            NavDestinationItem("System", Icons.Default.Tune, CyberRoute.System, CyberRoute.System::class.qualifiedName!!, "nav_system"),
        )
    }

    val navBackStack by navController.currentBackStackEntryAsState()
    val currentRouteName = navBackStack?.destination?.route

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 600.dp

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                if (!useRail) {
                    NavigationBar {
                        destinations.forEach { dest ->
                            val selected = currentRouteName?.contains(dest.qualifiedName) == true
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    navController.navigate(dest.route) {
                                        launchSingleTop = true
                                        restoreState = true
                                        popUpTo(navController.graph.startDestinationId) {
                                            saveState = true
                                        }
                                    }
                                },
                                icon = { Icon(dest.icon, contentDescription = dest.label) },
                                label = { Text(dest.label) },
                                modifier = Modifier.testTag(dest.testTag),
                            )
                        }
                    }
                }
            },
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                if (useRail) {
                    NavigationRail {
                        destinations.forEach { dest ->
                            val selected = currentRouteName?.contains(dest.qualifiedName) == true
                            NavigationRailItem(
                                selected = selected,
                                onClick = {
                                    navController.navigate(dest.route) {
                                        launchSingleTop = true
                                        restoreState = true
                                        popUpTo(navController.graph.startDestinationId) {
                                            saveState = true
                                        }
                                    }
                                },
                                icon = { Icon(dest.icon, contentDescription = dest.label) },
                                label = { Text(dest.label) },
                                modifier = Modifier.testTag(dest.testTag),
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    NavHost(
                        navController = navController,
                        startDestination = CyberRoute.Hub,
                        modifier = Modifier
                            .fillMaxSize()
                            .widthIn(max = 840.dp),
                    ) {
                        composable<CyberRoute.Hub> {
                            BluetoothHubScreen(
                                viewModel = viewModel,
                                hasBluetoothPermissions = hasBtPerms,
                                hasCameraPermission = hasCameraPerm,
                                onRequestBluetoothPermissions = { btLauncher.launch(bluetoothPerms) },
                                onRequestCameraPermission = { cameraLauncher.launch(Manifest.permission.CAMERA) },
                            )
                        }
                        composable<CyberRoute.Chats> {
                            ChatsContactsScreen(viewModel = viewModel)
                        }
                        composable<CyberRoute.Calls> {
                            CallsScreen(
                                viewModel = viewModel,
                                hasMicPermission = hasMicPerm,
                                hasCameraPermission = hasCameraPerm,
                                onRequestMicPermission = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                                onRequestCameraPermission = { cameraLauncher.launch(Manifest.permission.CAMERA) },
                            )
                        }
                        composable<CyberRoute.Map> {
                            CyberMapScreen(
                                viewModel = viewModel,
                                hasLocationPermission = hasLocationPerm,
                                hasBluetoothPermissions = hasBtPerms,
                                onRequestLocationPermission = {
                                    locationLauncher.launch(
                                        arrayOf(
                                            Manifest.permission.ACCESS_FINE_LOCATION,
                                            Manifest.permission.ACCESS_COARSE_LOCATION,
                                        )
                                    )
                                },
                                onRequestBluetoothPermissions = { btLauncher.launch(bluetoothPerms) },
                            )
                        }
                        composable<CyberRoute.System> {
                            SystemStatusScreen(viewModel = viewModel)
                        }
                    }
                }
            }
        }
    }
}
