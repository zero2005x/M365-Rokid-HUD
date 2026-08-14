package com.m365hud.glass

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
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
        
        // Retry scan after this delay when scan fails to find gateway
        private const val SCAN_RETRY_DELAY_MS = 5000L
    }
    
    private var bleClient: BleClient? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Single connection-state collector, one pending retry, one wake-lock
    // renewal loop — so repeated onStartCommand calls cannot stack duplicates.
    private var monitorJob: Job? = null
    private var retryJob: Job? = null
    private var wakeLockRenewJob: Job? = null
    
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

        // Validate permissions BEFORE startForeground. From Android 14 (API 34)
        // the system checks the runtime permission backing a
        // FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE service at the moment
        // startForeground is called and throws SecurityException if it is
        // missing — so checking afterwards is too late.
        //
        // This is not hypothetical: the service is START_STICKY, so the system
        // restarts it with a null intent after a process kill. If the user
        // revoked Bluetooth access in the meantime, the old ordering crashed
        // the service on every restart attempt.
        if (!hasBlePermissions()) {
            Log.e(TAG, "BLE permissions not granted; cannot run as a connectedDevice foreground service")
            stopSelf()
            return START_NOT_STICKY
        }

        // Start as foreground service with notification.
        // The explicit type argument keeps this in sync with the
        // android:foregroundServiceType declared in AndroidManifest.xml; the
        // two-argument overload silently inherits it and hides mismatches.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    createNotification("Connecting..."),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NOTIFICATION_ID, createNotification("Connecting..."))
            }
        } catch (e: Exception) {
            // Covers SecurityException (permission revoked between the check
            // above and this call) and ForegroundServiceStartNotAllowedException
            // (Android 12+ background-start restriction). Stopping is far better
            // than letting the process crash on the user's glasses.
            Log.e(TAG, "startForeground failed; stopping service", e)
            stopSelf()
            return START_NOT_STICKY
        }

        // Idempotent: onStartCommand also runs on repeat startService calls and
        // on a START_STICKY restart with a null intent. Without this guard each
        // one stacked another connection-state collector and restarted scanning
        // even while already connected.
        if (monitorJob?.isActive != true) {
            // @SuppressLint only silences lint: on Android 12+ startScan
            // throws SecurityException without BLUETOOTH_SCAN/CONNECT and
            // would kill the service. hasBlePermissions() above is what
            // actually makes this safe.
            bleClient?.startScan()

            // Monitor connection state and update notification
            monitorConnectionState()
        }

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

        // This service is START_STICKY and runs indefinitely, but the lock is
        // capped at WAKE_LOCK_TIMEOUT_MS. Without renewal the system silently
        // released it and the CPU could sleep mid-session, stalling BLE scans
        // and reconnects. Renew well before it expires.
        wakeLockRenewJob?.cancel()
        wakeLockRenewJob = serviceScope.launch {
            while (isActive) {
                delay(WAKE_LOCK_TIMEOUT_MS / 2)
                if (wakeLock?.isHeld != true) {
                    Log.d(TAG, "Wake lock expired, re-acquiring")
                    acquireWakeLock()
                    return@launch
                }
            }
        }
    }
    
    private fun releaseWakeLock() {
        wakeLockRenewJob?.cancel()
        wakeLockRenewJob = null
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "Wake lock released")
            }
        }
        wakeLock = null
    }
    
    /**
     * True when the runtime permissions BLE scanning needs are granted.
     * Below API 31 these are install-time permissions.
     */
    private fun hasBlePermissions(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true

        return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) ==
            PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun monitorConnectionState() {
        monitorJob?.cancel()
        monitorJob = serviceScope.launch {
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

                // Auto-retry scan when in Error state (e.g., scan timeout,
                // gateway not found). This handles the case where the glasses
                // start scanning before the phone starts advertising.
                //
                // The retry runs in its own job: delaying inside the collect
                // lambda blocked the collector for the whole retry window, so
                // Connecting/Connected transitions during it were conflated
                // away and the notification went stale.
                if (state is BleClient.ConnectionState.Error) {
                    Log.i(TAG, "Connection error detected, will retry scan in ${SCAN_RETRY_DELAY_MS}ms...")
                    retryJob?.cancel()
                    retryJob = serviceScope.launch {
                        delay(SCAN_RETRY_DELAY_MS)
                        // Only retry if still in error state (not manually reconnected)
                        if (bleClient?.connectionState?.value is BleClient.ConnectionState.Error &&
                            hasBlePermissions()
                        ) {
                            Log.i(TAG, "Retrying scan after error...")
                            bleClient?.startScan()
                        }
                    }
                }
            }
        }
    }
}
