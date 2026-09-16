package com.m365bleapp.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.m365bleapp.R
import com.m365bleapp.ble.BleManager
import com.m365bleapp.gateway.GatewayService
import com.m365bleapp.repository.ConnectionState
import com.m365bleapp.protocol.Identification
import com.m365bleapp.protocol.ScooterModelRegistry
import com.m365bleapp.ui.components.ModelBadge
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.RadioButton
import com.m365bleapp.protocol.ModelOverrideStore
import com.m365bleapp.repository.ScooterRepository
import com.m365bleapp.utils.BluetoothHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retry
import kotlinx.coroutines.launch

/**
 * Check if the app is exempt from battery optimization (Doze mode).
 */
private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }
    return true // Pre-M devices don't have Doze
}

/**
 * Create an intent to open the system battery optimization settings.
 *
 * Using ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS complies with Google Play
 * policies by allowing the user to voluntarily whitelist the app without
 * declaring the restricted REQUEST_IGNORE_BATTERY_OPTIMIZATIONS permission in the manifest.
 */
private fun createBatteryOptimizationIntent(context: Context): Intent {
    return Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
}

/**
 * Launches the battery-optimization settings screen.
 *
 * Falls back to application details settings if the specific battery optimization
 * settings intent is not handled on this device/ROM.
 */
private fun requestBatteryOptimizationExemption(
    context: Context,
    launcher: ManagedActivityResultLauncher<Intent, ActivityResult>
) {
    try {
        launcher.launch(createBatteryOptimizationIntent(context))
    } catch (e: Exception) {
        Log.w("ScanScreen", "Battery optimization settings intent failed, falling back to app details", e)
        try {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (e2: Exception) {
            Log.e("ScanScreen", "Could not open application settings", e2)
        }
    }
}

/**
 * Data class to hold scan result with registration status for sorting
 */
// The property initializers read the device name, which requires
// BLUETOOTH_CONNECT on API 31+. The scan flow requests that permission
// before any ScannedDevice is created, so suppress at the class level to
// cover both the `name` and any future device-derived initializers.
@SuppressLint("MissingPermission")
@androidx.compose.runtime.Stable
private data class ScannedDevice(
    val scanResult: ScanResult,
    val isRegistered: Boolean,
    val rssi: Int = scanResult.rssi,
    // Prefer advertised device name from scan record, fallback to bonded device name
    val name: String? = scanResult.scanRecord?.deviceName ?: scanResult.device.name,
    val address: String = scanResult.device.address
) {
    /**
     * What this device looks like it is.
     *
     * Resolved through [ScooterModelRegistry] rather than a local name check.
     *
     * The old logic was a single hard-coded `MIScooter` prefix plus a `fe95`
     * UUID test, which had two problems: it recognised exactly one family, and
     * it presented the result as a fact. A name prefix cannot establish a
     * protocol — the same model name spans several wire generations, and Xiaomi,
     * Ninebot and current Segway models all advertise the same Nordic UART
     * service — so the registry returns a confidence alongside the guess and the
     * badge shows both.
     */
    val identification: Identification
        get() = ScooterModelRegistry.resolve(advertisedName = name)

    /**
     * Whether this device is worth offering as a scooter.
     *
     * A manual override exists on the scan screen for the case this returns
     * false: a scooter we cannot name is still a scooter, and the rider must be
     * able to say so. Without that, an unidentifiable vehicle would be
     * permanently unreachable.
     */
    val looksLikeScooter: Boolean
        get() = !identification.isUnknown
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(
    repository: ScooterRepository,
    onNavigateToDashboard: () -> Unit,
    onNavigateToLanguage: () -> Unit = {},
    onNavigateToLogViewer: () -> Unit = {},
    onNavigateToHudDisplay: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {}
) {
    // val bleManager = BleManager(repository.context) // Removed
    
    // Store scanned devices with their registration status
    // Use SnapshotStateMap for better performance
    val devicesMap = remember { mutableStateMapOf<String, ScannedDevice>() }
    
    // Sorted devices: registered first, then scooters, then named devices, then by RSSI (stronger signal = higher priority)
    // Use derivedStateOf with stable key to avoid unnecessary recomputation
    val sortedDevices by remember {
        derivedStateOf {
            devicesMap.values.sortedWith(
                compareByDescending<ScannedDevice> { it.isRegistered }
                    .thenByDescending { it.looksLikeScooter }
                    .thenByDescending { 
                        // Named devices first, unknown last
                        !it.name.isNullOrBlank() && !it.name.startsWith("Unknown")
                    }
                    .thenByDescending { it.rssi }
            )
        }
    }
    var showPermissionError by remember { mutableStateOf(false) }
    
    // Track if registered device was newly discovered for auto-scroll
    var hasAutoScrolledToRegistered by remember { mutableStateOf(false) }
    var scanError by remember { mutableStateOf<String?>(null) }
    
    // Permissions.
    //
    // Single source of truth: BluetoothHelper.getRequiredPermissions(). This
    // list used to be duplicated here, in MainActivity and in BluetoothHelper,
    // and the three copies had already drifted — a permission removed in one
    // place would silently keep being requested by another, which on API 31+
    // (where ACCESS_FINE_LOCATION is no longer declared) means the launcher
    // returns false for it forever and the user is stuck on the permission
    // error screen.
    val permissions = BluetoothHelper.getRequiredPermissions()
    
    // Fix: Use state to trigger recomposition when permissions are granted
    val context = LocalContext.current

    // Manual model override.
    //
    // The app cannot identify every scooter from a scan — a name prefix covers
    // several wire generations and the service list is not a discriminator — so
    // on untested hardware automatic identification can fail outright. This is
    // the rider's way to say what the scooter is, and without it such a scooter
    // would be permanently unreachable.
    val overrideStore = remember { ModelOverrideStore.getInstance(context) }
    val modelOverride by overrideStore.override.collectAsState()
    var overridePickerOpen by remember { mutableStateOf(false) }
    var permissionsGranted by remember { 
        mutableStateOf(
            permissions.all { 
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context, it
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED 
            }
        ) 
    }
    
    // Track if permissions were just granted (to add delay for MIUI)
    var permissionsJustGranted by remember { mutableStateOf(false) }
    
    // Scan trigger - incremented to restart scan
    var scanTrigger by remember { mutableStateOf(0) }
    
    // Bluetooth enabled state.
    // Deliberately starts false and is only queried once permissions are
    // granted: on Android 12+ BluetoothAdapter.isEnabled() requires
    // BLUETOOTH_CONNECT and throws SecurityException without it, and this
    // composition runs before the permission launcher fires — so a fresh
    // install could crash on this screen before the dialog even appeared.
    var isBluetoothEnabled by remember { mutableStateOf(false) }

    LaunchedEffect(permissionsGranted) {
        if (permissionsGranted) {
            isBluetoothEnabled = BluetoothHelper.isBluetoothEnabled(context)
        }
    }
    var showBluetoothDisabledDialog by remember { mutableStateOf(false) }
    
    // Launcher for enabling Bluetooth
    val enableBluetoothLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        // After returning from Bluetooth settings, check if it's enabled now
        isBluetoothEnabled = BluetoothHelper.isBluetoothEnabled(context)
        if (isBluetoothEnabled && permissionsGranted) {
            // Restart scan
            scanTrigger++
        }
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.all { it.value }) {
            permissionsJustGranted = true
            permissionsGranted = true
        } else {
            showPermissionError = true
        }
    }
    
    LaunchedEffect(Unit) {
        if (!permissionsGranted) {
             launcher.launch(permissions.toTypedArray())
        } else {
            // Check if Bluetooth is enabled after permissions granted
            isBluetoothEnabled = BluetoothHelper.isBluetoothEnabled(context)
            if (!isBluetoothEnabled) {
                showBluetoothDisabledDialog = true
            }
        }
    }
    
    // Also check Bluetooth state when permissions become granted
    LaunchedEffect(permissionsGranted) {
        if (permissionsGranted) {
            isBluetoothEnabled = BluetoothHelper.isBluetoothEnabled(context)
            if (!isBluetoothEnabled) {
                showBluetoothDisabledDialog = true
            }
        }
    }
    
    // Ensure scan runs ONLY when permissions are granted and screen is active
    // Add delay for MIUI devices when permissions are just granted
    LaunchedEffect(permissionsGranted, isBluetoothEnabled, scanTrigger) {
        if (!permissionsGranted) return@LaunchedEffect
        if (!isBluetoothEnabled) return@LaunchedEffect
        
        // MIUI and some Chinese ROMs need extra time after permission grant
        // to fully process the permission before BLE scan works
        if (permissionsJustGranted) {
            Log.d("ScanScreen", "Permissions just granted, waiting for system to process...")
            delay(1500) // Give MIUI time to fully process permission
            permissionsJustGranted = false
        }
        
        scanError = null
        Log.d("ScanScreen", "Starting BLE scan...")
        
        try {
            repository.scan()
                .retry(3) { cause ->
                    // Retry on scan failures (common on MIUI first launch)
                    Log.w("ScanScreen", "Scan failed, retrying: ${cause.message}")
                    delay(1000)
                    true
                }
                .catch { e ->
                    Log.e("ScanScreen", "Scan error after retries: ${e.message}")
                    scanError = e.message
                }
                .collect { res ->
                    val mac = res.device.address
                    val isReg = repository.isRegistered(mac)
                    // Get advertised name from scan record (more reliable)
                    val advertisedName = res.scanRecord?.deviceName ?: res.device.name
                    
                    // Only update if device is new or RSSI changed significantly (>5 dBm)
                    // This reduces unnecessary recompositions
                    val existing = devicesMap[mac]
                    if (existing == null || 
                        kotlin.math.abs(existing.rssi - res.rssi) > 5 ||
                        existing.name != advertisedName) {
                        val scannedDevice = ScannedDevice(res, isReg)
                        // Log scooter discovery
                        if (scannedDevice.looksLikeScooter) {
                            Log.i("ScanScreen", "Found scooter: $advertisedName ($mac)")
                        }
                        devicesMap[mac] = scannedDevice
                    }
                }
        } catch (e: CancellationException) {
            // Normal cancellation when navigating away from scan screen - not an error
            Log.d("ScanScreen", "Scan cancelled (navigating away)")
        } catch (e: Exception) {
            Log.e("ScanScreen", "Scan exception: ${e.message}")
            scanError = e.message
        }
    }
    
    // Connection State Observation
    val connState by repository.connectionState.collectAsState()
    
    LaunchedEffect(connState) {
        if (connState is ConnectionState.Ready) {
            onNavigateToDashboard()
        }
    }

    // UI
    var selectedDevice by remember { mutableStateOf<ScannedDevice?>(null) }
    
    if (overridePickerOpen) {
        ModelOverrideDialog(
            current = modelOverride,
            onDismiss = { overridePickerOpen = false },
            onSelect = { choice ->
                overrideStore.set(choice)
                overridePickerOpen = false
            }
        )
    }

    if (selectedDevice != null) {
        ConnectDialog(
            repository = repository,
            device = selectedDevice!!.scanResult,
            onDismiss = { selectedDevice = null },
            onConnect = { register ->
                val deviceToConnect = selectedDevice
                if (deviceToConnect != null) {
                    // Connect is now non-blocking and runs on Repository scope
                    repository.connect(deviceToConnect.scanResult.device.address, register)
                }
                selectedDevice = null
            }
        )
    }
    
    // Bluetooth Disabled Dialog
    if (showBluetoothDisabledDialog) {
        AlertDialog(
            onDismissRequest = { showBluetoothDisabledDialog = false },
            title = { 
                Text(stringResource(R.string.bluetooth_disabled_title))
            },
            text = { 
                Text(stringResource(R.string.bluetooth_disabled_message))
            },
            confirmButton = {
                Button(
                    onClick = { 
                        showBluetoothDisabledDialog = false
                        enableBluetoothLauncher.launch(BluetoothHelper.createEnableBluetoothIntent())
                    }
                ) {
                    Text(stringResource(R.string.bluetooth_enable))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { 
                        showBluetoothDisabledDialog = false
                    }
                ) {
                    Text(stringResource(R.string.bluetooth_settings))
                }
            }
        )
    }

    Scaffold(
        topBar = { 
            TopAppBar(
                title = { Text(stringResource(R.string.scan_title)) },
                actions = {
                    // One labelled entry point instead of four unlabelled icons.
                    //
                    // The bar used to carry glasses-display, logs and language
                    // as three equal-weight glyphs next to the title. Three
                    // icons is already a guessing game, and it put diagnostics
                    // on the same footing as riding controls. Everything now
                    // lives in Settings, grouped and labelled.
                    IconButton(onClick = { overridePickerOpen = true }) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            // Tinted when an override is active, so an
                            // overridden identification is visible at a glance
                            // rather than silently changing what the app reads.
                            tint = if (modelOverride != null) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                LocalContentColor.current
                            },
                            contentDescription = stringResource(R.string.model_override_title)
                        )
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = stringResource(R.string.settings_title)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (showPermissionError) {
                Text(stringResource(R.string.scan_bluetooth_permission_required), color = MaterialTheme.colorScheme.error)
            }
            
            // Show Bluetooth disabled banner with enable button
            if (!isBluetoothEnabled && permissionsGranted) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { 
                            enableBluetoothLauncher.launch(BluetoothHelper.createEnableBluetoothIntent())
                        }
                        .padding(8.dp),
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.bluetooth_disabled),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = { 
                                enableBluetoothLauncher.launch(BluetoothHelper.createEnableBluetoothIntent())
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text(stringResource(R.string.bluetooth_enable))
                        }
                    }
                }
            }
            
            // Show scan error with retry option
            if (scanError != null) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { 
                            scanError = null
                            devicesMap.clear()
                            scanTrigger++ // Trigger scan restart
                        }
                        .padding(8.dp),
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Text(
                        text = stringResource(R.string.scan_failed_tap_retry, scanError ?: ""),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
            
            if (connState is ConnectionState.Connecting || connState is ConnectionState.Handshaking) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                val statusMsg = if (connState is ConnectionState.Handshaking) {
                    (connState as ConnectionState.Handshaking).status
                } else {
                    stringResource(R.string.connecting)
                }
                Text(statusMsg, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
            
            if (connState is ConnectionState.Error) {
                Text(
                    text = "${stringResource(R.string.error)}: ${(connState as ConnectionState.Error).message}",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(8.dp)
                )
            }
            
            // Show scanning indicator when no devices found yet
            if (devicesMap.isEmpty() && scanError == null && permissionsGranted) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(stringResource(R.string.scan_scanning))
                    }
                }
            }

            // Use rememberLazyListState for better scroll performance
            val listState = rememberLazyListState()
            
            // ========== Glasses Connection Section ==========
            // Gateway state for glasses connection
            var gatewayEnabled by remember { mutableStateOf(GatewayService.isRunning()) }
            var glassesConnected by remember { mutableStateOf(false) }
            
            // Refresh glasses connection status periodically
            LaunchedEffect(gatewayEnabled) {
                while (gatewayEnabled) {
                    glassesConnected = GatewayService.isGlassesConnected()
                    delay(2000L)
                }
                glassesConnected = false
            }
            
            // Permission launcher for BLE Advertise (for Gateway)
            val advertisePermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { permissions ->
                val allGranted = permissions.values.all { it }
                if (allGranted) {
                    gatewayEnabled = true
                    GatewayService.start(context)
                }
            }
            
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = when {
                        glassesConnected -> MaterialTheme.colorScheme.primaryContainer
                        gatewayEnabled -> MaterialTheme.colorScheme.secondaryContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "👓 " + stringResource(R.string.gateway_hud),
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = when {
                                glassesConnected -> stringResource(R.string.glasses_connected)
                                gatewayEnabled -> stringResource(R.string.gateway_broadcasting)
                                else -> stringResource(R.string.glasses_connect_hint)
                            },
                            fontSize = MaterialTheme.typography.bodySmall.fontSize,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = gatewayEnabled,
                        onCheckedChange = { enabled ->
                            if (enabled) {
                                // Check if Bluetooth is enabled first
                                if (!BluetoothHelper.isBluetoothEnabled(context)) {
                                    showBluetoothDisabledDialog = true
                                    return@Switch
                                }
                                
                                // Check BLE permissions for Android 12+
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    val hasConnect = ContextCompat.checkSelfPermission(
                                        context, Manifest.permission.BLUETOOTH_CONNECT
                                    ) == PackageManager.PERMISSION_GRANTED
                                    val hasAdvertise = ContextCompat.checkSelfPermission(
                                        context, Manifest.permission.BLUETOOTH_ADVERTISE
                                    ) == PackageManager.PERMISSION_GRANTED
                                    
                                    if (!hasConnect || !hasAdvertise) {
                                        advertisePermissionLauncher.launch(
                                            arrayOf(
                                                Manifest.permission.BLUETOOTH_CONNECT,
                                                Manifest.permission.BLUETOOTH_ADVERTISE
                                            )
                                        )
                                        return@Switch
                                    }
                                }
                                
                                // Start Gateway
                                gatewayEnabled = true
                                GatewayService.start(context)
                            } else {
                                gatewayEnabled = false
                                GatewayService.stop(context)
                            }
                        }
                    )
                }
            }
            
            // ========== Battery Optimization Warning ==========
            // Check if battery optimization is enabled (can cause app to be killed)
            var isBatteryOptimized by remember { 
                mutableStateOf(!isIgnoringBatteryOptimizations(context)) 
            }
            
            // Launcher for battery optimization settings
            val batteryOptLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) { _ ->
                // Refresh battery optimization status after returning from settings
                isBatteryOptimized = !isIgnoringBatteryOptimizations(context)
            }
            
            // Show warning banner if battery optimization is ON and gateway is enabled
            if (isBatteryOptimized && gatewayEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            requestBatteryOptimizationExemption(context, batteryOptLauncher)
                        }
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = MaterialTheme.shapes.small
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.battery_optimization_warning),
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                            Text(
                                text = stringResource(R.string.battery_optimization_message),
                                fontSize = MaterialTheme.typography.bodySmall.fontSize,
                                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
                            )
                        }
                        Button(
                            onClick = { 
                                requestBatteryOptimizationExemption(context, batteryOptLauncher)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.tertiary
                            )
                        ) {
                            Text(stringResource(R.string.battery_optimization_exempt))
                        }
                    }
                }
            }
            
            // Divider before device list
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            
            // Auto-scroll to top when registered device is discovered
            LaunchedEffect(sortedDevices) {
                val hasRegistered = sortedDevices.any { it.isRegistered }
                if (hasRegistered && !hasAutoScrolledToRegistered) {
                    hasAutoScrolledToRegistered = true
                    listState.animateScrollToItem(0)
                }
            }
            
            LazyColumn(state = listState) {
                items(
                    items = sortedDevices, 
                    key = { it.address },
                    contentType = { "device" }  // Help Compose reuse item compositions
                ) { scannedDevice ->
                    // Use cached properties from ScannedDevice for better performance
                    val displayName = scannedDevice.name ?: stringResource(R.string.unknown)
                    val isReg = scannedDevice.isRegistered
                    val identification = scannedDevice.identification
                    val isScooter = scannedDevice.looksLikeScooter
                    val address = scannedDevice.address
                    val rssi = scannedDevice.rssi
                    
                    // Animate background and scale for registered devices
                    val backgroundColor by animateColorAsState(
                        targetValue = if (isReg) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                      else MaterialTheme.colorScheme.surface,
                        animationSpec = tween(durationMillis = 500),
                        label = "registeredBgColor"
                    )
                    val scaleValue by animateFloatAsState(
                        targetValue = if (isReg) 1.02f else 1f,
                        animationSpec = tween(durationMillis = 300),
                        label = "registeredScale"
                    )
                    
                    ListItem(
                        modifier = Modifier
                            .scale(scaleValue)
                            .background(backgroundColor)
                            .clickable { selectedDevice = scannedDevice },
                        headlineContent = { 
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (isScooter) {
                                    Text("🛴 ", style = MaterialTheme.typography.bodyLarge)
                                }
                                Text(displayName)
                                // Model + confidence badge.
                                //
                                // The confidence is part of the badge on purpose:
                                // a name prefix identifies a family, not a
                                // protocol, so presenting the model alone would
                                // claim more than the scan can know.
                                if (isScooter) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    ModelBadge(identification)
                                }
                                if (isReg) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Surface(
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        shape = MaterialTheme.shapes.extraSmall
                                    ) {
                                        Text(
                                            stringResource(R.string.registered),
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                            }
                        },
                        supportingContent = { Text("$address (${stringResource(R.string.scan_rssi, rssi)})") }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
fun ConnectDialog(
    repository: ScooterRepository,
    device: ScanResult,
    onDismiss: () -> Unit,
    onConnect: (Boolean) -> Unit
) {
    // Registration is decided here, not asked.
    //
    // The dialog used to present a "Register" checkbox, which asked the rider to
    // choose between two protocol paths they have no way to evaluate. The answer
    // is not a preference — it is a fact the app already knows: a scooter with a
    // stored token must log in, one without must register first. `register` is
    // therefore derived from storage and passed straight through to connect().
    val isAlreadyRegistered = repository.isRegistered(device.device.address)
    // Kept as a recallable value so the confirm button reads from state rather
    // than recomputing storage on every recomposition.
    val register = !isAlreadyRegistered

    // The dialog is only shown for a device discovered by a permission-gated
    // scan, so BLUETOOTH_CONNECT is already held here.
    @SuppressLint("MissingPermission")
    val deviceName = device.device.name ?: stringResource(R.string.unknown)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(deviceName) },
        text = {
            Column {
                Text(
                    text = if (isAlreadyRegistered) {
                        stringResource(R.string.dialog_connect_known)
                    } else {
                        stringResource(R.string.dialog_connect_new)
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                if (!isAlreadyRegistered) {
                    Spacer(modifier = Modifier.height(12.dp))
                    // The one instruction that genuinely matters on a first
                    // pairing, and the reason it is here rather than in a log.
                    Text(
                        text = stringResource(R.string.dialog_register_warning),
                        color = MaterialTheme.colorScheme.tertiary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConnect(register) }) {
                Text(stringResource(R.string.connect))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}



/**
 * Lets the rider pin the scooter model when identification fails or is wrong.
 *
 * The list is deliberately flat and labelled rather than grouped: a rider
 * reaching for this is looking at a scooter that the app could not name, and a
 * two-level picker would be a worse experience at exactly the moment they are
 * already stuck.
 *
 * Models whose register layout this app cannot read are still listed. Selecting
 * one is honest — it tells the app what the scooter *is*, even though the app
 * will then report that it cannot read it. Hiding them would leave the rider
 * wondering whether their scooter was in the list at all.
 */
@Composable
fun ModelOverrideDialog(
    current: com.m365bleapp.protocol.ScooterModel?,
    onDismiss: () -> Unit,
    onSelect: (com.m365bleapp.protocol.ScooterModel?) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.model_override_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(R.string.model_override_explanation),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(12.dp))

                // "Automatic" first: clearing the override is the common case
                // once a scooter has been identified correctly.
                ModelOverrideRow(
                    label = stringResource(R.string.model_override_automatic),
                    detail = null,
                    selected = current == null,
                    onClick = { onSelect(null) }
                )

                for (model in com.m365bleapp.protocol.ScooterModelRegistry.selectableModels) {
                    ModelOverrideRow(
                        label = model.displayName,
                        detail = if (model.producesTelemetry) {
                            null
                        } else {
                            stringResource(R.string.model_override_no_readings)
                        },
                        selected = current == model,
                        onClick = { onSelect(model) }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
fun ModelOverrideRow(
    label: String,
    detail: String?,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(modifier = Modifier.width(4.dp))
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
