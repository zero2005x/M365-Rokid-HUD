package com.m365bleapp.ui

import android.Manifest
import android.bluetooth.le.ScanResult
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.m365bleapp.R
import com.m365bleapp.ble.BleManager
import com.m365bleapp.repository.ConnectionState
import com.m365bleapp.repository.ScooterRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retry
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(
    repository: ScooterRepository,
    onNavigateToDashboard: () -> Unit
) {
    val scope = rememberCoroutineScope()
    // val bleManager = BleManager(repository.context) // Removed
    
    val devices = remember { mutableStateListOf<ScanResult>() }
    var showPermissionError by remember { mutableStateOf(false) }
    var scanError by remember { mutableStateOf<String?>(null) }
    
    // Permissions
    val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    } else {
        listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN
        )
    }
    
    // Fix: Use state to trigger recomposition when permissions are granted
    val context = LocalContext.current
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
        }
    }
    
    // Ensure scan runs ONLY when permissions are granted and screen is active
    // Add delay for MIUI devices when permissions are just granted
    LaunchedEffect(permissionsGranted, scanTrigger) {
        if (!permissionsGranted) return@LaunchedEffect
        
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
                    val existing = devices.indexOfFirst { it.device.address == res.device.address }
                    if (existing >= 0) {
                        devices[existing] = res
                    } else {
                        devices.add(res)
                    }
                }
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
    var selectedDevice by remember { mutableStateOf<ScanResult?>(null) }
    
    if (selectedDevice != null) {
        ConnectDialog(
            repository = repository,
            device = selectedDevice!!,
            onDismiss = { selectedDevice = null },
            onConnect = { register ->
                val deviceToConnect = selectedDevice
                if (deviceToConnect != null) {
                    // Connect is now non-blocking and runs on Repository scope
                    repository.connect(deviceToConnect.device.address, register)
                }
                selectedDevice = null
            }
        )
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.scan_title)) }) }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (showPermissionError) {
                Text(stringResource(R.string.scan_bluetooth_permission_required), color = MaterialTheme.colorScheme.error)
            }
            
            // Show scan error with retry option
            if (scanError != null) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { 
                            scanError = null
                            devices.clear()
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
            if (devices.isEmpty() && scanError == null && permissionsGranted) {
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

            LazyColumn {
                items(devices) { res ->
                    val name = res.device.name ?: stringResource(R.string.unknown)
                    val isReg = repository.isRegistered(res.device.address)
                    ListItem(
                        headlineContent = { 
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(name)
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
                        supportingContent = { Text("${res.device.address} (${stringResource(R.string.scan_rssi, res.rssi)})") },
                        modifier = Modifier.clickable { selectedDevice = res }
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
    val isAlreadyRegistered = repository.isRegistered(device.device.address)
    var register by remember { mutableStateOf(!isAlreadyRegistered) }
    val deviceName = device.device.name ?: stringResource(R.string.unknown)
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_connect_title, deviceName)) },
        text = {
            Column {
                Text(stringResource(R.string.dialog_address, device.device.address))
                Spacer(modifier = Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = register, onCheckedChange = { register = it })
                    Text(stringResource(R.string.dialog_register_checkbox))
                }
                if (register) {
                    Text(
                        stringResource(R.string.dialog_register_warning),
                        color = MaterialTheme.colorScheme.error,
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


