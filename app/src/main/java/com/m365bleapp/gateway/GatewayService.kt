package com.m365bleapp.gateway

import android.Manifest
import android.app.*
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
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
        
        private var instance: GatewayService? = null
        
        fun isRunning(): Boolean = instance?.isServiceRunning == true
        
        fun start(context: Context) {
            val intent = Intent(context, GatewayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        fun stop(context: Context) {
            context.stopService(Intent(context, GatewayService::class.java))
        }
    }
    
    private var repository: ScooterRepository? = null
    private var gattServer: M365GattServer? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var isServiceRunning = false
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "GatewayService onCreate")
        
        createNotificationChannel()
        
        // Start foreground immediately
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, 
                buildNotification("Initializing..."),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification("Initializing..."))
        }
        
        // Initialize components
        initializeGateway()
    }
    
    private fun initializeGateway() {
        // Check permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) 
                != PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) 
                != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "Missing BLE permissions")
                updateNotification("⚠️ Missing Bluetooth permissions")
                stopSelf()
                return
            }
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
        
        Log.d(TAG, "Gateway Service initialized successfully")
    }
    
    private fun startTelemetryObserver() {
        // Get repository from application (you may need to adjust this based on your DI setup)
        repository = ScooterRepository(applicationContext)
        
        // Observe motor info and push to BLE
        scope.launch {
            repository?.motorInfo?.collectLatest { info ->
                if (info != null) {
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
                    } catch (e: SecurityException) {
                        Log.e(TAG, "Security exception updating telemetry", e)
                    }
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
        
        try {
            gattServer?.stop()
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception stopping GATT server", e)
        }
        
        repository?.disconnect()
        
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
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
    
    private fun updateNotification(content: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(content))
    }
}
