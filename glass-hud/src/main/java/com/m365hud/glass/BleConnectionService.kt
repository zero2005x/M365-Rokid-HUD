package com.m365hud.glass

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow

/**
 * Foreground Service to maintain BLE connection even when app is in background.
 * 
 * This service:
 * - Keeps the app alive via foreground notification
 * - Holds a partial wake lock to prevent CPU sleep during BLE operations
 * - Manages the BleClient lifecycle
 * - Auto-reconnects when connection is lost
 */
@SuppressLint("MissingPermission")
class BleConnectionService : Service() {
    
    companion object {
        private const val TAG = "BleConnectionService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "ble_connection_channel"
        private const val CHANNEL_NAME = "BLE Connection"
        
        // Wake lock timeout - 4 hours max to prevent battery drain
        private const val WAKE_LOCK_TIMEOUT_MS = 4 * 60 * 60 * 1000L
    }
    
    private var bleClient: BleClient? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // Binder for local binding
    private val binder = LocalBinder()
    
    inner class LocalBinder : Binder() {
        fun getService(): BleConnectionService = this@BleConnectionService
        fun getBleClient(): BleClient? = bleClient
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service created")
        
        // Create notification channel for Android O+
        createNotificationChannel()
        
        // Acquire wake lock to keep CPU awake for BLE operations
        acquireWakeLock()
        
        // Initialize BLE client
        bleClient = BleClient(this)
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "Service started")
        
        // Start as foreground service with notification
        startForeground(NOTIFICATION_ID, createNotification("Connecting..."))
        
        // Start BLE scan
        bleClient?.startScan()
        
        // Monitor connection state and update notification
        monitorConnectionState()
        
        // If service is killed, restart it
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder {
        return binder
    }
    
    override fun onDestroy() {
        Log.i(TAG, "Service destroyed")
        
        serviceScope.cancel()
        bleClient?.disconnect()
        bleClient = null
        releaseWakeLock()
        
        super.onDestroy()
    }
    
    /**
     * Get the BleClient instance for UI observation
     */
    fun getBleClient(): BleClient? = bleClient
    
    /**
     * Get connection state flow for UI
     */
    fun getConnectionState(): StateFlow<BleClient.ConnectionState>? = bleClient?.connectionState
    
    /**
     * Get telemetry flow for UI
     */
    fun getTelemetry(): StateFlow<TelemetryData>? = bleClient?.telemetry
    
    /**
     * Get time data flow for UI
     */
    fun getTimeData(): StateFlow<TimeData>? = bleClient?.timeData
    
    /**
     * Manually trigger reconnection
     */
    fun reconnect() {
        Log.i(TAG, "Manual reconnect requested")
        bleClient?.disconnect()
        bleClient?.startScan()
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW // Low importance = no sound
            ).apply {
                description = "Maintains BLE connection to phone"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(status: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("M365 HUD")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
    
    private fun updateNotification(status: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, createNotification(status))
    }
    
    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "M365HUD::BleWakeLock"
        ).apply {
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
        Log.d(TAG, "Wake lock acquired")
    }
    
    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "Wake lock released")
            }
        }
        wakeLock = null
    }
    
    private fun monitorConnectionState() {
        serviceScope.launch {
            bleClient?.connectionState?.collect { state ->
                val statusText = when (state) {
                    is BleClient.ConnectionState.Disconnected -> "Disconnected"
                    is BleClient.ConnectionState.Scanning -> "Scanning..."
                    is BleClient.ConnectionState.Connecting -> "Connecting..."
                    is BleClient.ConnectionState.Connected -> "Connected"
                    is BleClient.ConnectionState.Error -> "Error: ${state.message}"
                }
                updateNotification(statusText)
                Log.d(TAG, "Connection state: $statusText")
            }
        }
    }
}
