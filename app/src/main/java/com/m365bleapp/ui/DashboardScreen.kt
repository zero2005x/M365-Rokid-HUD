package com.m365bleapp.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.core.content.ContextCompat
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.m365bleapp.R
import com.m365bleapp.gateway.GatewayService
import com.m365bleapp.repository.ConnectionState
import com.m365bleapp.repository.ScooterRepository
import com.m365bleapp.utils.BluetoothHelper
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

import com.m365bleapp.repository.MotorInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    repository: ScooterRepository,
    onLogs: () -> Unit,
    onScooterInfo: () -> Unit,
    onDisconnect: () -> Unit
) {
    val context = LocalContext.current
    val motorInfo by repository.motorInfo.collectAsState()
    val connState by repository.connectionState.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    
    // Snackbar state for feedback (defined early for permission launcher)
    val snackbarHostState = remember { SnackbarHostState() }
    
    // Gateway state - initialize from actual service state
    var gatewayEnabled by remember { mutableStateOf(GatewayService.isRunning()) }
    var pendingGatewayEnable by remember { mutableStateOf(false) }
    
    // Glasses battery state - refreshed periodically
    var glassesBattery by remember { mutableIntStateOf(-1) }
    var glassesConnected by remember { mutableStateOf(GatewayService.isGlassesConnected()) }
    
    // Refresh gateway status and glasses info periodically
    LaunchedEffect(Unit) {
        while (true) {
            gatewayEnabled = GatewayService.isRunning()
            glassesConnected = GatewayService.isGlassesConnected()
            if (gatewayEnabled && glassesConnected) {
                glassesBattery = GatewayService.getGlassesBatteryLevel()
            }
            kotlinx.coroutines.delay(3000L)
        }
    }
    
    // Bluetooth state dialogs
    var showBluetoothDisabledDialog by remember { mutableStateOf(false) }
    
    // Launcher for enabling Bluetooth
    val enableBluetoothLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        // After returning from Bluetooth settings, check if it's enabled now
        if (BluetoothHelper.isBluetoothEnabled(context) && pendingGatewayEnable) {
            // Check permissions again and start gateway
            if (BluetoothHelper.hasAdvertisePermissions(context)) {
                gatewayEnabled = true
                GatewayService.start(context)
            }
        }
        pendingGatewayEnable = false
    }
    
    // Permission launcher for BLE Advertise
    val advertisePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted && pendingGatewayEnable) {
            gatewayEnabled = true
            GatewayService.start(context)
        } else if (!allGranted) {
            coroutineScope.launch {
                snackbarHostState.showSnackbar("需要藍牙廣播權限才能使用 Gateway 功能")
            }
        }
        pendingGatewayEnable = false
    }
    
    // Motor lock state - Note: M365 doesn't support reading lock state, 
    // so we track it locally and assume unlocked initially
    var isLocked by remember { mutableStateOf(false) }
    var isLockLoading by remember { mutableStateOf(false) }
    
    // Light state - synced from repository
    val lightStateFromRepo by repository.isLightOn.collectAsState()
    var isLightOn by remember { mutableStateOf(repository.isLightOn.value) }
    var isLightLoading by remember { mutableStateOf(false) }
    
    // Sync light state from repository whenever it changes
    LaunchedEffect(lightStateFromRepo) {
        isLightOn = lightStateFromRepo
    }

    // Disconnection alert dialog state
    var showDisconnectAlert by remember { mutableStateOf(false) }
    var disconnectMessage by remember { mutableStateOf("") }
    // Track user-initiated disconnect to avoid showing alert
    var userInitiatedDisconnect by remember { mutableStateOf(false) }
    
    // Monitor connection state for disconnection
    LaunchedEffect(connState) {
        when (connState) {
            is ConnectionState.Disconnected -> {
                // Only show alert for unexpected disconnections, not user-initiated ones
                if (!userInitiatedDisconnect) {
                    disconnectMessage = context.getString(R.string.connection_lost)
                    showDisconnectAlert = true
                }
            }
            is ConnectionState.Error -> {
                disconnectMessage = (connState as ConnectionState.Error).message
                showDisconnectAlert = true
            }
            else -> {
                // Connected states - reset the flag
                userInitiatedDisconnect = false
            }
        }
    }
    
    // Disconnection Alert Dialog
    if (showDisconnectAlert) {
        AlertDialog(
            onDismissRequest = { 
                showDisconnectAlert = false
                onDisconnect()
            },
            icon = {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { 
                Text(
                    text = stringResource(R.string.connection_lost_title),
                    color = MaterialTheme.colorScheme.error
                ) 
            },
            text = { 
                Text(disconnectMessage)
            },
            confirmButton = {
                Button(
                    onClick = { 
                        showDisconnectAlert = false
                        onDisconnect()
                    }
                ) {
                    Text(stringResource(R.string.reconnect))
                }
            }
        )
    }
    
    // Bluetooth Disabled Dialog (for Gateway)
    if (showBluetoothDisabledDialog) {
        AlertDialog(
            onDismissRequest = { 
                showBluetoothDisabledDialog = false
                pendingGatewayEnable = false
            },
            title = { 
                Text(stringResource(R.string.bluetooth_disabled_title))
            },
            text = { 
                Text(stringResource(R.string.bluetooth_gateway_disabled_message))
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
                        pendingGatewayEnable = false
                    }
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.dashboard_title)) }) },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header: Status
            if (connState is ConnectionState.Error) {
                Text(
                    text = (connState as ConnectionState.Error).message,
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                Text(stringResource(R.string.connected), color = MaterialTheme.colorScheme.primary)
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Speedometer (Simple Text for now)
            Text(
                text = "${"%.1f".format(motorInfo?.speed ?: 0.0)}",
                fontSize = 80.sp,
                fontWeight = FontWeight.Bold
            )
            Text(stringResource(R.string.unit_kmh), fontSize = 20.sp)
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Battery Section - Scooter and Glasses
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Scooter Battery
                    val scooterBattery = motorInfo?.battery ?: 0
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🛴 ", fontSize = 20.sp)
                        LinearProgressIndicator(
                            progress = { scooterBattery / 100f },
                            modifier = Modifier.weight(1f).height(16.dp),
                            color = when {
                                scooterBattery <= 15 -> MaterialTheme.colorScheme.error
                                scooterBattery <= 30 -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.primary
                            }
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = "$scooterBattery${stringResource(R.string.unit_percent)}", 
                            fontSize = 24.sp,
                            color = when {
                                scooterBattery <= 15 -> MaterialTheme.colorScheme.error
                                scooterBattery <= 30 -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.onSurface
                            }
                        )
                    }
                    
                    // Glasses Battery (only show when gateway enabled and glasses connected)
                    if (gatewayEnabled && glassesConnected && glassesBattery >= 0) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("👓 ", fontSize = 20.sp)
                            LinearProgressIndicator(
                                progress = { glassesBattery / 100f },
                                modifier = Modifier.weight(1f).height(16.dp),
                                color = when {
                                    glassesBattery <= 15 -> MaterialTheme.colorScheme.error
                                    glassesBattery <= 30 -> MaterialTheme.colorScheme.tertiary
                                    else -> MaterialTheme.colorScheme.primary
                                }
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(
                                text = "$glassesBattery${stringResource(R.string.unit_percent)}", 
                                fontSize = 24.sp,
                                color = when {
                                    glassesBattery <= 15 -> MaterialTheme.colorScheme.error
                                    glassesBattery <= 30 -> MaterialTheme.colorScheme.tertiary
                                    else -> MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // ========== Motor Lock/Unlock Control ==========
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isLocked) 
                        MaterialTheme.colorScheme.errorContainer 
                    else 
                        MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (isLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                            contentDescription = null,
                            tint = if (isLocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.control_motor),
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = if (isLocked) 
                                    stringResource(R.string.control_locked)
                                else 
                                    stringResource(R.string.control_unlocked),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                isLockLoading = true
                                coroutineScope.launch {
                                    repository.lock()
                                        .onSuccess { 
                                            isLocked = true 
                                            snackbarHostState.showSnackbar("Motor locked")
                                        }
                                        .onFailure { e ->
                                            snackbarHostState.showSnackbar("Lock failed: ${e.message}")
                                        }
                                    isLockLoading = false
                                }
                            },
                            enabled = !isLockLoading && !isLocked,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            if (isLockLoading && !isLocked) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Text(stringResource(R.string.control_lock))
                            }
                        }
                        
                        Button(
                            onClick = {
                                isLockLoading = true
                                coroutineScope.launch {
                                    repository.unlock()
                                        .onSuccess { 
                                            isLocked = false 
                                            snackbarHostState.showSnackbar("Motor unlocked")
                                        }
                                        .onFailure { e ->
                                            snackbarHostState.showSnackbar("Unlock failed: ${e.message}")
                                        }
                                    isLockLoading = false
                                }
                            },
                            enabled = !isLockLoading && isLocked
                        ) {
                            if (isLockLoading && isLocked) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                            } else {
                                Text(stringResource(R.string.control_unlock))
                            }
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // ========== Tail Light Control ==========
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isLightOn) 
                        MaterialTheme.colorScheme.tertiaryContainer 
                    else 
                        MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (isLightOn) Icons.Default.FlashlightOn else Icons.Default.FlashlightOff,
                            contentDescription = null,
                            tint = if (isLightOn) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.control_light),
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = if (isLightOn) 
                                    stringResource(R.string.control_light_on)
                                else 
                                    stringResource(R.string.control_light_off),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    
                    if (isLightLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    } else {
                        Switch(
                            checked = isLightOn,
                            onCheckedChange = { newState ->
                                isLightLoading = true
                                coroutineScope.launch {
                                    repository.setLight(newState)
                                        .onSuccess { 
                                            isLightOn = newState 
                                            snackbarHostState.showSnackbar(
                                                if (newState) "Tail light on" else "Tail light off"
                                            )
                                        }
                                        .onFailure { e ->
                                            snackbarHostState.showSnackbar("Light control failed: ${e.message}")
                                        }
                                    isLightLoading = false
                                }
                            }
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Gateway Toggle for Rokid HUD
            Card(
                modifier = Modifier.fillMaxWidth(),
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.CastConnected,
                            contentDescription = null,
                            tint = when {
                                glassesConnected -> MaterialTheme.colorScheme.primary
                                gatewayEnabled -> MaterialTheme.colorScheme.secondary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "👓 " + stringResource(R.string.gateway_hud),
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = when {
                                    glassesConnected -> stringResource(R.string.glasses_connected)
                                    gatewayEnabled -> stringResource(R.string.gateway_broadcasting)
                                    else -> stringResource(R.string.gateway_off)
                                },
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Switch(
                        checked = gatewayEnabled,
                        onCheckedChange = { enabled ->
                            if (enabled) {
                                // Step 1: Check if Bluetooth is enabled first
                                if (!BluetoothHelper.isBluetoothEnabled(context)) {
                                    pendingGatewayEnable = true
                                    showBluetoothDisabledDialog = true
                                    return@Switch
                                }
                                
                                // Step 2: Check BLE permissions for Android 12+
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    val hasConnect = ContextCompat.checkSelfPermission(
                                        context, Manifest.permission.BLUETOOTH_CONNECT
                                    ) == PackageManager.PERMISSION_GRANTED
                                    val hasAdvertise = ContextCompat.checkSelfPermission(
                                        context, Manifest.permission.BLUETOOTH_ADVERTISE
                                    ) == PackageManager.PERMISSION_GRANTED
                                    
                                    if (!hasConnect || !hasAdvertise) {
                                        pendingGatewayEnable = true
                                        advertisePermissionLauncher.launch(
                                            arrayOf(
                                                Manifest.permission.BLUETOOTH_CONNECT,
                                                Manifest.permission.BLUETOOTH_ADVERTISE
                                            )
                                        )
                                        return@Switch
                                    }
                                }
                                
                                // All checks passed, start Gateway
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
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Secondary action buttons in a row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onScooterInfo,
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    Text(stringResource(R.string.dashboard_view_details))
                }
                
                OutlinedButton(
                    onClick = onLogs,
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    Text(stringResource(R.string.dashboard_view_logs))
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))

            // Disconnect button - prominent but with warning color
            OutlinedButton(
                onClick = {
                    userInitiatedDisconnect = true
                    onDisconnect()
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                ),
                border = androidx.compose.foundation.BorderStroke(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.error
                )
            ) {
                Text(stringResource(R.string.disconnect))
            }
            
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
