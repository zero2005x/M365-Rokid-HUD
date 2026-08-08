package com.m365bleapp.gateway

import android.Manifest
import android.app.*
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.m365bleapp.R
import com.m365bleapp.repository.ConnectionState
import com.m365bleapp.repository.ScooterRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/**
 * Foreground Service that runs the BLE Gateway (Peripheral mode)
 * 
 * This service:
 * 1. Keeps running in background while the app is minimized
 * 2. Broadcasts scooter telemetry via BLE to Rokid Glasses
 * 3. Shows a notification with current status
 */
class GatewayService : Service() {
    
    companion object {
        private const val TAG = "GatewayService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "m365_gateway_channel"
        
        // Written in onCreate/onDestroy, read from other threads via the
        // accessors below.
        @Volatile
        private var instance: GatewayService? = null

        fun isRunning(): Boolean = instance?.isServiceRunning == true
        
        /**
         * Get the glasses battery level received from connected glasses.
         * @return Battery percentage (0-100), or -1 if not available
         */
        fun getGlassesBatteryLevel(): Int = instance?.gattServer?.getGlassesBatteryLevel() ?: -1
        
        /**
         * Check if glasses are connected to the Gateway.
         */
        fun isGlassesConnected(): Boolean = instance?.gattServer?.isDeviceConnected() == true
        
        /**
         * @return true if the service start was accepted by the system.
         *
         * On Android 12+ starting a foreground service from the background
         * throws ForegroundServiceStartNotAllowedException; report that to the
         * caller instead of crashing.
         */
        fun start(context: Context): Boolean {
            val intent = Intent(context, GatewayService::class.java)
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "Could not start GatewayService", e)
                false
            }
        }
        
        fun stop(context: Context) {
            context.stopService(Intent(context, GatewayService::class.java))
        }
    }
    
    private var repository: ScooterRepository? = null
    private var gattServer: M365GattServer? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    // Read cross-thread via the companion accessors.
    @Volatile
    private var isServiceRunning = false
    
    // === DOZE MODE HANDLING ===
    // WakeLock to prevent CPU sleep during telemetry polling
    private var wakeLock: PowerManager.WakeLock? = null
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "GatewayService onCreate - starting...")
        
        createNotificationChannel()
        
        // Validate permissions BEFORE startForeground: on Android 14+ a
        // connectedDevice FGS requires BLUETOOTH_CONNECT to be granted at the
        // moment startForeground is called, so checking afterwards is too late.
        if (!hasRequiredBlePermissions()) {
            Log.e(TAG, "Missing BLE permissions, cannot start gateway service")
            stopSelf()
            return
        }

        // On Android 12+ a background FGS start throws
        // ForegroundServiceStartNotAllowedException; handle it instead of
        // crashing.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification("Initializing..."),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification("Initializing..."))
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed; stopping service", e)
            stopSelf()
            return
        }

        // === DOZE MODE: Acquire WakeLock to prevent CPU sleep ===
        acquireWakeLock()
        
        // Initialize components
        initializeGateway()
    }
    
    /**
     * Acquire a partial wake lock to prevent CPU sleep during telemetry polling.
     * This ensures the service can continue to receive and forward telemetry data
     * even when the device screen is off.
     */
    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "M365Gateway::TelemetryLock"
            ).apply {
                // No timeout: this service is START_STICKY and intended to run
                // indefinitely. The previous 1-hour timeout claimed it "will be
                // re-acquired if needed", but nothing ever re-acquired it, so
                // after an hour Doze could suspend telemetry polling while the
                // service still appeared healthy. releaseWakeLock() in
                // onDestroy is the matching release.
                acquire()
            }
            Log.i(TAG, "DOZE: WakeLock acquired for telemetry polling")
        } catch (e: Exception) {
            Log.e(TAG, "DOZE: Failed to acquire WakeLock: ${e.message}", e)
        }
    }
    
    /**
     * Release the wake lock when service is destroyed.
     */
    private fun releaseWakeLock() {
        try {
            wakeLock?.let { wl ->
                if (wl.isHeld) {
                    wl.release()
                    Log.i(TAG, "DOZE: WakeLock released")
                }
            }
            wakeLock = null
        } catch (e: Exception) {
            Log.e(TAG, "DOZE: Failed to release WakeLock: ${e.message}", e)
        }
    }
    
    /**
     * Check if the app is ignoring battery optimizations (Doze exempt).
     * @return true if exempt, false otherwise
     */
    fun isIgnoringBatteryOptimizations(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            return pm.isIgnoringBatteryOptimizations(packageName)
        }
        return true // Pre-M devices don't have Doze
    }
    
    /**
     * Create an intent to request battery optimization exemption.
     * The caller should start this intent from an Activity.
     */
    fun createBatteryOptimizationIntent(): Intent? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!isIgnoringBatteryOptimizations()) {
                return Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
            }
        }
        return null
    }
    
    /**
     * True when the runtime permissions required to run a connectedDevice
     * foreground service and act as a BLE peripheral are granted.
     *
     * Checked before startForeground(), because Android 14+ validates the
     * while-in-use permission at that exact call.
     */
    private fun hasRequiredBlePermissions(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true

        return ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun initializeGateway() {
        // Re-check: permissions can be revoked between onCreate and here.
        if (!hasRequiredBlePermissions()) {
            Log.e(TAG, "Missing BLE permissions")
            updateNotification("⚠️ Missing Bluetooth permissions")
            stopSelf()
            return
        }

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        gattServer = M365GattServer(applicationContext, bluetoothManager)
        
        try {
            if (!gattServer!!.start()) {
                Log.e(TAG, "Failed to start GATT server")
                updateNotification("⚠️ BLE Peripheral not supported")
                stopSelf()
                return
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception starting GATT server", e)
            updateNotification("⚠️ Permission denied")
            stopSelf()
            return
        }
        
        isServiceRunning = true
        updateNotification("🔍 Waiting for Rokid connection...")
        
        // Start telemetry observation
        startTelemetryObserver()
        
        Log.i(TAG, "Gateway Service initialized successfully")
    }
    
    private fun startTelemetryObserver() {
        // Get shared repository instance (singleton pattern)
        repository = ScooterRepository.getInstance(applicationContext)
        
        Log.i(TAG, "Starting telemetry observer, scooter connected: ${repository?.connectionState?.value is ConnectionState.Ready}")
        
        // Observe motor info and push to BLE
        scope.launch {
            Log.i(TAG, "Telemetry observer coroutine started, collecting motorInfo...")
            repository?.motorInfo?.collectLatest { info ->
                if (info != null) {
                    Log.d(TAG, "MotorInfo received: speed=${info.speed}, battery=${info.battery}, subscribers=${gattServer?.getConnectedDeviceCount() ?: 0}")
                    val connState = when (repository?.connectionState?.value) {
                        is ConnectionState.Disconnected -> M365HudGattProfile.STATE_DISCONNECTED
                        is ConnectionState.Connecting, is ConnectionState.Handshaking -> M365HudGattProfile.STATE_CONNECTING
                        is ConnectionState.Ready -> M365HudGattProfile.STATE_READY
                        is ConnectionState.Error -> M365HudGattProfile.STATE_DISCONNECTED
                        else -> M365HudGattProfile.STATE_DISCONNECTED
                    }
                    
                    try {
                        gattServer?.updateTelemetry(
                            speedKmh = info.speed,
                            scooterBattery = info.battery,
                            tempC = info.temp,
                            totalMileageM = (info.mileage * 1000).toLong(),
                            avgSpeedKmh = info.avgSpeed,
                            remainingKm = info.remainingKm,
                            connectionState = connState,
                            tripMeters = info.tripMeters,
                            tripSeconds = info.tripSeconds
                        )
                        
                        val glassesStatus = if (gattServer?.isDeviceConnected() == true) "🔗" else "⏳"
                        val scooterStatus = if (connState == M365HudGattProfile.STATE_READY) "🛴" else "⚠️"
                        updateNotification("$glassesStatus $scooterStatus ${info.speed.toInt()} km/h | 🔋${info.battery}%")
                    } catch (e: Exception) {
                        // Catch broadly: anything escaping here would cancel this
                        // collector and silently stop all telemetry forwarding,
                        // while the SupervisorJob keeps the service looking alive.
                        Log.e(TAG, "Failed to update telemetry", e)
                    }
                } else {
                    // Send "disconnected" state telemetry so glasses know gateway is alive
                    // but scooter is not connected. This prevents glasses from detecting
                    // "stale data" and constantly reconnecting.
                    Log.d(TAG, "MotorInfo is null - sending disconnected state to glasses")
                    try {
                        gattServer?.updateTelemetry(
                            speedKmh = 0.0,
                            scooterBattery = 0,
                            tempC = 0.0,
                            totalMileageM = 0L,
                            avgSpeedKmh = 0.0,
                            remainingKm = 0.0,
                            connectionState = M365HudGattProfile.STATE_DISCONNECTED,
                            tripMeters = 0,
                            tripSeconds = 0
                        )
                    } catch (e: SecurityException) {
                        Log.e(TAG, "Security exception sending disconnected state", e)
                    }
                }
            }
        }
        
        // === HEARTBEAT: Keep glasses connection alive ===
        // StateFlow only emits on value CHANGE, so when scooter is idle (speed=0 steady),
        // no updates are emitted. This heartbeat ensures glasses receive regular updates
        // to prevent "stale data" detection and reconnection loops.
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(1000L) // Send every 1 second
                
                // Only send if glasses are connected
                if (gattServer?.isDeviceConnected() != true) {
                    continue
                }
                
                val info = repository?.motorInfo?.value
                val connState = when (repository?.connectionState?.value) {
                    is ConnectionState.Disconnected -> M365HudGattProfile.STATE_DISCONNECTED
                    is ConnectionState.Connecting, is ConnectionState.Handshaking -> M365HudGattProfile.STATE_CONNECTING
                    is ConnectionState.Ready -> M365HudGattProfile.STATE_READY
                    is ConnectionState.Error -> M365HudGattProfile.STATE_DISCONNECTED
                    else -> M365HudGattProfile.STATE_DISCONNECTED
                }
                
                try {
                    if (info != null) {
                        // Scooter connected: send current telemetry as heartbeat
                        gattServer?.updateTelemetry(
                            speedKmh = info.speed,
                            scooterBattery = info.battery,
                            tempC = info.temp,
                            totalMileageM = (info.mileage * 1000).toLong(),
                            avgSpeedKmh = info.avgSpeed,
                            remainingKm = info.remainingKm,
                            connectionState = connState,
                            tripMeters = info.tripMeters,
                            tripSeconds = info.tripSeconds
                        )
                        Log.d(TAG, "Heartbeat: speed=${info.speed}, battery=${info.battery}")
                    } else {
                        // Scooter not connected: send disconnected state
                        gattServer?.updateTelemetry(
                            speedKmh = 0.0,
                            scooterBattery = 0,
                            tempC = 0.0,
                            totalMileageM = 0L,
                            avgSpeedKmh = 0.0,
                            remainingKm = 0.0,
                            connectionState = connState,
                            tripMeters = 0,
                            tripSeconds = 0
                        )
                        Log.d(TAG, "Heartbeat: scooter disconnected (state: $connState)")
                    }
                } catch (e: SecurityException) {
                    Log.e(TAG, "Security exception in heartbeat", e)
                }
            }
        }
        
        // Observe connection state changes
        scope.launch {
            repository?.connectionState?.collectLatest { state ->
                val stateText = when (state) {
                    is ConnectionState.Disconnected -> "Scooter disconnected"
                    is ConnectionState.Connecting -> "Connecting to scooter..."
                    is ConnectionState.Handshaking -> state.status
                    is ConnectionState.Ready -> "Scooter connected"
                    is ConnectionState.Error -> "Error: ${state.message}"
                }
                
                val glassesStatus = if (gattServer?.isDeviceConnected() == true) "🔗 Glasses" else "⏳ Waiting"
                updateNotification("$glassesStatus | $stateText")
            }
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "GatewayService onStartCommand")
        return START_STICKY
    }
    
    override fun onDestroy() {
        Log.d(TAG, "GatewayService onDestroy")
        isServiceRunning = false
        instance = null
        
        scope.cancel()
        
        // === DOZE MODE: Release WakeLock ===
        releaseWakeLock()
        
        try {
            gattServer?.stop()
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception stopping GATT server", e)
        }
        
        // Note: Do NOT call repository?.disconnect() here!
        // Gateway service stopping should not affect the scooter connection.
        // The scooter connection is managed separately by the main app.
        
        super.onDestroy()
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "M365 HUD Gateway",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "M365 to Rokid Glasses Gateway Service"
                setShowBadge(false)
            }
            
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
    
    private fun buildNotification(content: String): Notification {
        // Intent to open the app when notification is tapped
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("M365 HUD Gateway")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_notification_gateway)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            // Use PRIORITY_HIGH for foreground service to prevent system from killing it
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
    
    private fun updateNotification(content: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(content))
    }
}
