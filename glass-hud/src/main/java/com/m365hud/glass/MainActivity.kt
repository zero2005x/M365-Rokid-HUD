package com.m365hud.glass

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import com.m365hud.glass.ui.theme.GlassHudTheme
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    
    companion object {
        private const val TAG = "MainActivity"
    }
    
    // Service connection
    private var bleService: BleConnectionService? = null
    private var serviceBound = false
    
    // Fallback BLE client when service not available
    private var fallbackBleClient: BleClient? = null
    
    // State holders for UI
    private val connectionState = mutableStateOf<BleClient.ConnectionState>(BleClient.ConnectionState.Disconnected)
    private val telemetryState = mutableStateOf(TelemetryData())
    private val timeDataState = mutableStateOf(TimeData())
    private val signalStrengthState = mutableStateOf(BleClient.SignalStrength.Good)
    private val isTelemetryFreshState = mutableStateOf(true)
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.i(TAG, "Service connected")
            val binder = service as BleConnectionService.LocalBinder
            bleService = binder.getService()
            serviceBound = true
            
            // Observe service state flows
            observeServiceState()
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            Log.i(TAG, "Service disconnected")
            bleService = null
            serviceBound = false
        }
    }
    
    // Permission launcher
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            startBleService()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Keep screen on for HUD
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        setContent {
            GlassHudTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black
                ) {
                    val currentConnectionState by connectionState
                    val currentTelemetry by telemetryState
                    val currentTimeData by timeDataState
                    val currentSignalStrength by signalStrengthState
                    val currentIsTelemetryFresh by isTelemetryFreshState
                    
                    HudScreen(
                        telemetry = currentTelemetry,
                        timeData = currentTimeData,
                        connectionState = currentConnectionState,
                        signalStrength = currentSignalStrength,
                        isTelemetryFresh = currentIsTelemetryFresh,
                        onRetryClick = { retryConnection() }
                    )
                }
            }
        }
        
        // Check permissions and start service
        checkPermissionsAndStart()
    }
    
    override fun onStart() {
        super.onStart()
        // Bind to service if it's running
        if (serviceBound) {
            observeServiceState()
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Unbind from service but don't stop it - let it run in background
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        fallbackBleClient?.disconnect()
    }
    
    private fun checkPermissionsAndStart() {
        val permissions = mutableListOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        
        // Add foreground service permission for Android 9+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            permissions.add(Manifest.permission.FOREGROUND_SERVICE)
        }
        
        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        
        if (allGranted) {
            startBleService()
        } else {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }
    
    private fun startBleService() {
        Log.i(TAG, "Starting BLE connection service")
        
        val serviceIntent = Intent(this, BleConnectionService::class.java)
        
        // Start as foreground service
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        
        // Bind to service to get updates
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }
    
    private fun observeServiceState() {
        val client = bleService?.getBleClient() ?: return
        
        // Launch coroutines to observe state flows
        MainScope().launch {
            client.connectionState.collect { state ->
                connectionState.value = state
            }
        }
        
        MainScope().launch {
            client.telemetry.collect { data ->
                telemetryState.value = data
            }
        }
        
        MainScope().launch {
            client.timeData.collect { data ->
                timeDataState.value = data
            }
        }
        
        MainScope().launch {
            client.signalStrength.collect { strength ->
                signalStrengthState.value = strength
            }
        }
        
        MainScope().launch {
            client.isTelemetryFresh.collect { fresh ->
                isTelemetryFreshState.value = fresh
            }
        }
    }
    
    private fun retryConnection() {
        Log.i(TAG, "Retry connection requested")
        if (serviceBound) {
            bleService?.reconnect()
        } else {
            // Service not bound, restart it
            startBleService()
        }
    }
}
