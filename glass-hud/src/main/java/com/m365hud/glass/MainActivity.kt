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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
    ) { results ->
        // Only the scan/connect permissions actually gate the service: they
        // are what FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE is validated
        // against. Requiring every requested permission would mean a user who
        // declines the notification prompt gets no HUD at all, which is a far
        // worse outcome than a HUD with no status notification.
        //
        // `!= false` rather than `== true`: a permission that was not part of
        // this request (because the platform is too old to have it) is absent
        // from the result map, and absent must not read as denied.
        val bleGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            ).all { results[it] != false }
        } else {
            results[Manifest.permission.ACCESS_FINE_LOCATION] != false
        }

        if (bleGranted) {
            if (results[Manifest.permission.POST_NOTIFICATIONS] == false) {
                Log.w(TAG, "Notification permission denied; HUD service will run without a visible notification")
            }
            startBleService()
        } else {
            Log.e(TAG, "Bluetooth permissions denied; cannot start HUD service")
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen on for HUD
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        applyImmersiveMode()

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
    
    /**
     * Puts the HUD in true full-screen (immersive sticky) mode.
     *
     * Why this replaces the old approach: apps targeting Android 15 (API 35)
     * and above are laid out edge-to-edge and CANNOT opt out — Android 16
     * deleted `windowOptOutEdgeToEdgeEnforcement`. At the same time
     * `android:statusBarColor` and `android:navigationBarColor` became no-ops,
     * so the black bars themes.xml used to paint no longer exist and the
     * system bars now float over the HUD. On a pair of glasses that means the
     * speed readout can sit underneath a translucent navigation bar.
     *
     * Hiding the bars outright is the right answer for a HUD (there is nothing
     * on the glasses for the user to tap in the status bar), and it is the one
     * behaviour the system still honours at target 36. BEHAVIOR_SHOW_
     * TRANSIENT_BARS_BY_SWIPE keeps them recoverable by swipe rather than
     * gone forever.
     */
    private fun applyImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Transient bars reappear after a swipe or a permission dialog and do
        // not re-hide themselves. Without this the HUD permanently loses the
        // top and bottom strips the first time a dialog is shown.
        if (hasFocus) applyImmersiveMode()
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
        val permissions = mutableListOf<String>()

        // The list must match the running platform version, not just the
        // target. BLUETOOTH_SCAN/BLUETOOTH_CONNECT do not exist below API 31,
        // so requesting them on Android 9-11 (this module's minSdk is 28)
        // returned "denied" for permissions the OS has never heard of — every
        // check failed and the HUD service simply never started. Rokid ships
        // Android 12, which is why this went unnoticed.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions += Manifest.permission.BLUETOOTH_SCAN
            permissions += Manifest.permission.BLUETOOTH_CONNECT
            // No ACCESS_FINE_LOCATION here: BLUETOOTH_SCAN is declared
            // neverForLocation in the manifest, so the platform does not
            // require a location grant to scan. Prompting for location on a
            // pair of glasses — where dismissing a system dialog is genuinely
            // awkward — for a permission the scan does not need is the worst
            // of both worlds.
        } else {
            // API 28-30: the legacy install-time Bluetooth permissions are
            // enough to talk to the adapter, but BLE scanning genuinely
            // returns zero results without a location grant on these releases.
            permissions += Manifest.permission.ACCESS_FINE_LOCATION
        }

        // POST_NOTIFICATIONS became a runtime permission in Android 13 (API 33).
        // It was declared in the manifest but never requested, so on API 33+ the
        // foreground-service notification was silently suppressed: the user had
        // no visible sign the HUD service was running, and an invisible
        // foreground service is a much easier target for aggressive OEM battery
        // managers.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        // NOTE: FOREGROUND_SERVICE is deliberately NOT in this list. It is a
        // normal (install-time) permission, so requestPermissions() cannot
        // grant it and checkSelfPermission() always reports it granted —
        // including it was a no-op that made the intent of this block unclear.

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
