package com.m365bleapp.gateway.wifi

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.m365bleapp.R
import com.m365bleapp.gateway.M365HudGattProfile
import com.m365bleapp.repository.ConnectionState
import com.m365bleapp.repository.ScooterRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.util.Calendar

/**
 * WiFi Gateway Service - Streams M365 telemetry over WiFi.
 * 
 * This service runs alongside or instead of the BLE Gateway Service,
 * providing lower latency telemetry streaming to Rokid Glasses via WiFi.
 * 
 * Advantages over BLE:
 * - Lower latency (< 10ms vs < 50ms)
 * - Higher bandwidth
 * - More reliable in high-interference environments
 * 
 * Requirements:
 * - Both devices on same WiFi network
 * - Or WiFi Direct connection
 */
class WifiGatewayService : Service() {
    
    companion object {
        private const val TAG = "WifiGatewayService"
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "m365_wifi_gateway_channel"
        
        private var instance: WifiGatewayService? = null
        
        fun isRunning(): Boolean = instance?.isServiceRunning == true
        
        fun getServerState(): WifiGatewayServer.ServerState? = instance?.wifiServer?.serverState?.value
        
        fun getConnectedDeviceCount(): Int = instance?.wifiServer?.getConnectedDeviceCount() ?: 0
        
        fun getGlassesBatteryLevel(): Int = instance?.wifiServer?.getGlassesBatteryLevel() ?: -1
        
        fun start(context: Context) {
            val intent = Intent(context, WifiGatewayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        fun stop(context: Context) {
            context.stopService(Intent(context, WifiGatewayService::class.java))
        }
    }
    
    private var repository: ScooterRepository? = null
    private var wifiServer: WifiGatewayServer? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var isServiceRunning = false
    
    // WakeLock for background operation
    private var wakeLock: PowerManager.WakeLock? = null
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "WifiGatewayService onCreate")
        
        createNotificationChannel()
        
        // Start foreground immediately
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification("Initializing WiFi Gateway..."),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification("Initializing WiFi Gateway..."))
        }
        
        // Acquire wake lock
        acquireWakeLock()
        
        // Initialize
        initializeGateway()
    }
    
    private fun initializeGateway() {
        Log.i(TAG, "Initializing WiFi Gateway...")
        
        wifiServer = WifiGatewayServer(applicationContext)
        
        if (!wifiServer!!.start()) {
            Log.e(TAG, "Failed to start WiFi server")
            updateNotification("⚠️ WiFi Gateway failed to start")
            stopSelf()
            return
        }
        
        isServiceRunning = true
        
        // Observe server state
        scope.launch {
            wifiServer?.serverState?.collect { state ->
                when (state) {
                    is WifiGatewayServer.ServerState.Running -> {
                        updateNotification("📡 WiFi: ${state.localAddress}:${state.port}")
                    }
                    is WifiGatewayServer.ServerState.Error -> {
                        updateNotification("⚠️ ${state.message}")
                    }
                    else -> {}
                }
            }
        }
        
        // Observe connected device count
        scope.launch {
            wifiServer?.connectedDeviceCount?.collect { count ->
                if (count > 0) {
                    val state = wifiServer?.serverState?.value
                    if (state is WifiGatewayServer.ServerState.Running) {
                        updateNotification("📡 WiFi | 🔗 $count glasses connected")
                    }
                }
            }
        }
        
        // Start telemetry observation
        startTelemetryObserver()
        
        Log.i(TAG, "WiFi Gateway Service initialized successfully")
    }
    
    private fun startTelemetryObserver() {
        repository = ScooterRepository.getInstance(applicationContext)
        
        Log.i(TAG, "Starting telemetry observer...")
        
        // Observe motor info and push to WiFi clients
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
                    
                    wifiServer?.updateTelemetry(
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
                    
                    val clientCount = wifiServer?.getConnectedDeviceCount() ?: 0
                    if (clientCount > 0) {
                        updateNotification("📡 WiFi | 🛴 ${info.speed.toInt()} km/h | 🔋${info.battery}%")
                    }
                } else {
                    // Send disconnected state
                    wifiServer?.updateTelemetry(
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
                }
            }
        }
        
        // Heartbeat: send time data periodically
        scope.launch {
            while (true) {
                delay(1000L)
                
                if (wifiServer?.getConnectedDeviceCount() ?: 0 > 0) {
                    val calendar = Calendar.getInstance()
                    val batteryManager = getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
                    val phoneBattery = batteryManager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                    
                    wifiServer?.updateTimeData(
                        hour = calendar.get(Calendar.HOUR_OF_DAY),
                        minute = calendar.get(Calendar.MINUTE),
                        second = calendar.get(Calendar.SECOND),
                        phoneBattery = phoneBattery
                    )
                }
            }
        }
    }
    
    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "M365WifiGateway::TelemetryLock"
            ).apply {
                acquire(60 * 60 * 1000L) // 1 hour
            }
            Log.i(TAG, "WakeLock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire WakeLock", e)
        }
    }
    
    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.i(TAG, "WakeLock released")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing WakeLock", e)
        }
        wakeLock = null
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "WifiGatewayService onStartCommand")
        return START_STICKY
    }
    
    override fun onDestroy() {
        Log.d(TAG, "WifiGatewayService onDestroy")
        isServiceRunning = false
        instance = null
        
        scope.cancel()
        releaseWakeLock()
        
        wifiServer?.stop()
        wifiServer = null
        
        super.onDestroy()
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "M365 WiFi Gateway",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "WiFi telemetry streaming to glasses"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun buildNotification(content: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("M365 WiFi Gateway")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_notification_gateway)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
    
    private fun updateNotification(content: String) {
        val notification = buildNotification(content)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}
