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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.m365hud.glass.ui.theme.GlassHudTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    
    companion object {
        private const val TAG = "MainActivity"
    }
    
    // Service connection
    private var bleService: BleConnectionService? = null
    private var serviceBound = false
    
    // Single job holding every state-flow collector, so re-observing replaces
    // rather than duplicates them.
    private var observeJob: Job? = null

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
        // Collectors are normally registered from onServiceConnected. Only
        // re-register if this Activity restarted while already bound; the
        // observeJob guard means this can never stack duplicates.
        if (serviceBound && observeJob?.isActive != true) {
            observeServiceState()
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        observeJob?.cancel()
        observeJob = null
        // Unbind from service but don't stop it - let it run in background
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
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
    
    /**
     * Collects the client's state flows into the Compose state holders.
     *
     * Uses [lifecycleScope] + [repeatOnLifecycle] rather than `MainScope()`.
     * `MainScope()` created a brand-new scope per call that was never
     * cancelled, so each of these collectors ran forever while capturing this
     * Activity — leaking the Activity (and the bound service/BLE client) across
     * every stop/start and configuration change. Because this method is called
     * from both onServiceConnected and onStart, those leaked collectors also
     * accumulated, so every emission triggered N redundant state writes.
     *
     * [observeJob] guarantees only one set of collectors is ever active.
     */
    private fun observeServiceState() {
        val client = bleService?.getBleClient() ?: return

        observeJob?.cancel()
        observeJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    client.connectionState.collect { state ->
                        connectionState.value = state
                    }
                }
                launch {
                    client.telemetry.collect { data ->
                        telemetryState.value = data
                    }
                }
                launch {
                    client.timeData.collect { data ->
                        timeDataState.value = data
                    }
                }
                launch {
                    client.signalStrength.collect { strength ->
                        signalStrengthState.value = strength
                    }
                }
                launch {
                    client.isTelemetryFresh.collect { fresh ->
                        isTelemetryFreshState.value = fresh
                    }
                }
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
