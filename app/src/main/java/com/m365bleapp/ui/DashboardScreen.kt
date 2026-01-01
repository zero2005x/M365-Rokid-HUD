package com.m365bleapp.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.core.content.ContextCompat
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.m365bleapp.R
import com.m365bleapp.gateway.GatewayService
import com.m365bleapp.repository.ConnectionState
import com.m365bleapp.repository.ScooterRepository
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
    
    // Gateway state
    var gatewayEnabled by remember { mutableStateOf(false) }
    var pendingGatewayEnable by remember { mutableStateOf(false) }
    
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
    var isLightOn by remember { mutableStateOf(false) }
    var isLightLoading by remember { mutableStateOf(false) }
    
    // Sync light state from repository
    LaunchedEffect(lightStateFromRepo) {
        isLightOn = lightStateFromRepo
    }

    // Disconnection alert dialog state
    var showDisconnectAlert by remember { mutableStateOf(false) }
    var disconnectMessage by remember { mutableStateOf("") }
    
    // Monitor connection state for disconnection
    LaunchedEffect(connState) {
        when (connState) {
            is ConnectionState.Disconnected -> {
                disconnectMessage = context.getString(R.string.connection_lost)
                showDisconnectAlert = true
            }
            is ConnectionState.Error -> {
                disconnectMessage = (connState as ConnectionState.Error).message
                showDisconnectAlert = true
            }
            else -> {
                // Connected states - don't show alert
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

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.dashboard_title)) }) },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp),
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
            
            // Battery
            Row(verticalAlignment = Alignment.CenterVertically) {
                LinearProgressIndicator(
                    progress = { (motorInfo?.battery ?: 0) / 100f },
                    modifier = Modifier.weight(1f).height(16.dp),
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text("${motorInfo?.battery ?: 0}${stringResource(R.string.unit_percent)}", fontSize = 24.sp)
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
                    containerColor = if (gatewayEnabled) 
                        MaterialTheme.colorScheme.primaryContainer 
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
                    Column {
                        Text(
                            text = stringResource(R.string.gateway_hud),
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = if (gatewayEnabled) 
                                stringResource(R.string.gateway_broadcasting)
                            else 
                                stringResource(R.string.gateway_off),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = gatewayEnabled,
                        onCheckedChange = { enabled ->
                            if (enabled) {
                                // Check BLE permissions for Android 12+
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
            
            Spacer(modifier = Modifier.weight(1f))
            
            Button(
                onClick = onScooterInfo,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
            ) {
                Text(stringResource(R.string.dashboard_view_details))
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Button(
                onClick = onLogs,
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Text(stringResource(R.string.dashboard_view_logs))
            }
            
            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = onDisconnect,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text(stringResource(R.string.disconnect))
            }
        }
    }
}
