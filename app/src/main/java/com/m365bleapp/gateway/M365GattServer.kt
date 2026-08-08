package com.m365bleapp.gateway

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Calendar
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * BLE GATT Server that acts as a Peripheral to broadcast M365 telemetry
 * to Rokid Glasses or any BLE Central device.
 */
class M365GattServer(
    private val context: Context,
    private val bluetoothManager: BluetoothManager
) {
    companion object {
        private const val TAG = "M365GattServer"
        
        // LATENCY MONITORING: Track update frequency for debugging
        private const val LATENCY_LOG_INTERVAL_MS = 5000L // Log stats every 5 seconds
    }
    
    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null

    /**
     * Notification subscriptions, keyed by characteristic UUID and then by
     * device address.
     *
     * Telemetry and time each have their own CCCD (both using CCCD_UUID). When
     * subscriptions were keyed by device address alone, disabling notifications
     * on one characteristic silently unsubscribed the device from the other,
     * and enabling either one subscribed it to both.
     */
    private val subscriptions = ConcurrentHashMap<UUID, ConcurrentHashMap<String, BluetoothDevice>>()

    private fun subscribersOf(charUuid: UUID): ConcurrentHashMap<String, BluetoothDevice> =
        subscriptions.getOrPut(charUuid) { ConcurrentHashMap() }

    /** Total number of distinct devices subscribed to at least one characteristic. */
    private fun subscriberCount(): Int =
        subscriptions.values.flatMap { it.keys }.toSet().size
    
    // Track if service has been added to GATT server
    @Volatile private var serviceAdded = false
    private val serviceAddedLock = Object()
    
    private lateinit var telemetryCharacteristic: BluetoothGattCharacteristic
    private lateinit var statusCharacteristic: BluetoothGattCharacteristic
    private lateinit var timeCharacteristic: BluetoothGattCharacteristic
    private lateinit var glassesBatteryCharacteristic: BluetoothGattCharacteristic
    
    // Current data
    @Volatile private var currentTelemetry: ByteArray = ByteArray(M365HudGattProfile.TELEMETRY_DATA_SIZE)
    @Volatile private var currentTime: ByteArray = ByteArray(M365HudGattProfile.TIME_DATA_SIZE)
    @Volatile private var glassesBatteryLevel: Int = -1  // -1 means not received yet
    
    // Written by start()/stop(), read from the telemetry threads.
    @Volatile private var isRunning = false
    
    // LATENCY MONITORING: Track last update time and frequency for debugging delays
    @Volatile private var lastTelemetryUpdateMs: Long = 0
    @Volatile private var telemetryUpdateCount: Int = 0
    @Volatile private var lastLogTimeMs: Long = 0
    @Volatile private var lastSpeedValue: Double = 0.0
    
    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "Service added successfully: ${service.uuid}")
                synchronized(serviceAddedLock) {
                    serviceAdded = true
                    serviceAddedLock.notifyAll()
                }
            } else {
                Log.e(TAG, "Failed to add service: ${service.uuid}, status=$status")
            }
        }
        
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            // BluetoothDevice.getAddress() requires BLUETOOTH_CONNECT on API 31+
            // and throws SecurityException without it. This callback runs on the
            // main thread, so an unguarded read would crash the app.
            val address = device.addressOrNull() ?: run {
                Log.w(TAG, "Cannot read device address (missing BLUETOOTH_CONNECT)")
                return
            }

            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                subscriptions.values.forEach { it.remove(address) }
                Log.i(TAG, "Device DISCONNECTED: $address, remaining subscribers: ${subscriberCount()}")
            } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "Device CONNECTED: $address, waiting for notification subscription...")
            }
        }
        
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (descriptor.uuid != M365HudGattProfile.CCCD_UUID) return

            val hasPermission = ActivityCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasPermission) {
                // Dropping the reply silently makes the central time out. There
                // is nothing else we can do here without the permission, so at
                // least make the failure visible in the log.
                Log.w(TAG, "Cannot answer CCCD write: BLUETOOTH_CONNECT not granted")
                return
            }

            val address = device.addressOrNull() ?: return
            // The CCCD belongs to a specific characteristic; track it per
            // characteristic rather than per device.
            val charUuid = descriptor.characteristic?.uuid

            val status = when {
                charUuid == null -> BluetoothGatt.GATT_FAILURE

                value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) -> {
                    subscribersOf(charUuid)[address] = device
                    Log.i(TAG, "Notifications ENABLED for $address on $charUuid, total subscribers: ${subscriberCount()}")
                    BluetoothGatt.GATT_SUCCESS
                }

                value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE) -> {
                    subscribersOf(charUuid).remove(address)
                    Log.i(TAG, "Notifications DISABLED for $address on $charUuid, total subscribers: ${subscriberCount()}")
                    BluetoothGatt.GATT_SUCCESS
                }

                else -> {
                    // This server only ever sends notifications. Acknowledging
                    // an indication-enable (0x0002) or an arbitrary value would
                    // leave the central believing it is subscribed while no data
                    // ever arrives.
                    Log.w(TAG, "Unsupported CCCD value from $address: ${value.joinToString("") { "%02x".format(it) }}")
                    BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
                }
            }

            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, status, 0, null)
            }
        }
        
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) 
                != PackageManager.PERMISSION_GRANTED) {
                return
            }
            
            when (characteristic.uuid) {
                M365HudGattProfile.TELEMETRY_CHAR_UUID -> {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, currentTelemetry)
                }
                M365HudGattProfile.STATUS_CHAR_UUID -> {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, 
                        byteArrayOf(currentTelemetry[13]))
                }
                M365HudGattProfile.TIME_CHAR_UUID -> {
                    updateTimeData()
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, currentTime)
                }
                M365HudGattProfile.GLASSES_BATTERY_CHAR_UUID -> {
                    // Return current glasses battery level (or -1 if not received yet)
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0,
                        byteArrayOf(glassesBatteryLevel.toByte()))
                }
            }
        }
        
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) 
                != PackageManager.PERMISSION_GRANTED) {
                return
            }
            
            when (characteristic.uuid) {
                M365HudGattProfile.GLASSES_BATTERY_CHAR_UUID -> {
                    if (value.isNotEmpty()) {
                        glassesBatteryLevel = (value[0].toInt() and 0xFF).coerceIn(0, 100)
                        Log.d(TAG, "Received glasses battery: $glassesBatteryLevel% from ${device.address}")
                    }
                    
                    if (responseNeeded) {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                    }
                }
                else -> {
                    if (responseNeeded) {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_WRITE_NOT_PERMITTED, 0, null)
                    }
                }
            }
        }
    }
    
    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE])
    fun start(): Boolean {
        // Without this guard a second start() opens another GATT server and
        // another advertiser while the previous ones are never closed, leaking
        // native BLE resources and resetting serviceAdded under the old server.
        if (isRunning) {
            Log.w(TAG, "start() called while already running, ignoring")
            return true
        }

        val adapter = bluetoothManager.adapter ?: run {
            Log.e(TAG, "Bluetooth adapter not available")
            return false
        }
        
        advertiser = adapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            Log.e(TAG, "BLE Peripheral mode not supported on this device")
            return false
        }
        
        // Create GATT Server
        gattServer = bluetoothManager.openGattServer(context, gattCallback)
        if (gattServer == null) {
            Log.e(TAG, "Failed to open GATT server")
            return false
        }
        
        // Build Service
        val service = BluetoothGattService(
            M365HudGattProfile.SERVICE_UUID, 
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )
        
        // Telemetry Characteristic (Notify + Read)
        telemetryCharacteristic = BluetoothGattCharacteristic(
            M365HudGattProfile.TELEMETRY_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).apply {
            addDescriptor(BluetoothGattDescriptor(
                M365HudGattProfile.CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            ))
        }
        
        // Status Characteristic (Read only)
        statusCharacteristic = BluetoothGattCharacteristic(
            M365HudGattProfile.STATUS_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        
        // Time Characteristic (Notify + Read)
        timeCharacteristic = BluetoothGattCharacteristic(
            M365HudGattProfile.TIME_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).apply {
            addDescriptor(BluetoothGattDescriptor(
                M365HudGattProfile.CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            ))
        }
        
        // Glasses Battery Characteristic (Write only - glasses write their battery level)
        glassesBatteryCharacteristic = BluetoothGattCharacteristic(
            M365HudGattProfile.GLASSES_BATTERY_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        
        service.addCharacteristic(telemetryCharacteristic)
        service.addCharacteristic(statusCharacteristic)
        service.addCharacteristic(timeCharacteristic)
        service.addCharacteristic(glassesBatteryCharacteristic)
        
        // Reset service added flag before adding
        serviceAdded = false

        val addResult = gattServer?.addService(service)
        Log.d(TAG, "addService called, result: $addResult")

        // Wait for service to be added (with timeout)
        if (addResult == true) {
            synchronized(serviceAddedLock) {
                if (!serviceAdded) {
                    try {
                        // Wait up to 5 seconds for service to be added
                        serviceAddedLock.wait(5000)
                    } catch (e: InterruptedException) {
                        Log.e(TAG, "Interrupted while waiting for service to be added")
                        Thread.currentThread().interrupt()
                    }
                }
            }
        } else {
            // Registration failed outright. Advertising anyway would let a
            // central connect and find no service at all, so fail here.
            Log.e(TAG, "addService returned false or gattServer is null")
            gattServer?.close()
            gattServer = null
            return false
        }

        if (!serviceAdded) {
            Log.e(TAG, "Timeout waiting for service to be added")
            gattServer?.close()
            gattServer = null
            return false
        }

        Log.i(TAG, "Service successfully registered with GATT server")

        // Only advertise once the service is confirmed present.
        startAdvertising()

        isRunning = true
        
        // Log device name for debugging (helps identify which device is advertising)
        val deviceName = adapter.name ?: "Unknown"
        Log.i(TAG, "GATT Server started successfully - Device: $deviceName")
        return true
    }
    
    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    private fun startAdvertising() {
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0) // Advertise indefinitely
            .build()
        
        // Primary advertisement: Service UUID (required for scan filter to work)
        // Note: 128-bit UUID takes 18 bytes, leaving room for flags (3 bytes)
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)  // Device name in scan response to save space
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(M365HudGattProfile.SERVICE_UUID))
            .build()
        
        // Scan response: Device name for identification
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .setIncludeTxPowerLevel(true)
            .build()
        
        Log.i(TAG, "Starting BLE advertising with Service UUID: ${M365HudGattProfile.SERVICE_UUID}")
        advertiser?.startAdvertising(settings, data, scanResponse, advertiseCallback)
    }
    
    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.i(TAG, "BLE Advertising started successfully")
        }
        
        override fun onStartFailure(errorCode: Int) {
            val errorMsg = when (errorCode) {
                ADVERTISE_FAILED_ALREADY_STARTED -> "Already started"
                ADVERTISE_FAILED_DATA_TOO_LARGE -> "Data too large"
                ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "Feature unsupported"
                ADVERTISE_FAILED_INTERNAL_ERROR -> "Internal error"
                ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "Too many advertisers"
                else -> "Unknown error $errorCode"
            }
            Log.e(TAG, "BLE Advertising failed: $errorMsg")
        }
    }
    
    /**
     * Update telemetry data and notify all subscribed devices
     * LATENCY OPTIMIZED: Immediately sends notifications to glasses for real-time display
     *
     * Synchronized: GatewayService drives this from both a `collectLatest`
     * collector and a 1-second heartbeat, both on the multi-threaded
     * Dispatchers.Default. Interleaved calls would otherwise race on
     * currentTelemetry / telemetryCharacteristic.value and could put a
     * half-written buffer on the air.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    @Synchronized
    fun updateTelemetry(
        speedKmh: Double,
        scooterBattery: Int,
        tempC: Double,
        totalMileageM: Long,
        avgSpeedKmh: Double,
        remainingKm: Double,
        connectionState: Int,
        tripMeters: Int,
        tripSeconds: Int
    ) {
        // telemetryCharacteristic is lateinit and only assigned in start(); a
        // call before start() (or after stop(), on a closed server) would throw.
        if (!isRunning) return

        val now = System.currentTimeMillis()
        
        // LATENCY MONITORING: Log update frequency stats periodically
        telemetryUpdateCount++
        if (now - lastLogTimeMs >= LATENCY_LOG_INTERVAL_MS) {
            val intervalSec = (now - lastLogTimeMs) / 1000.0
            val updatesPerSec = telemetryUpdateCount / intervalSec
            Log.d(TAG, "LATENCY STATS: ${telemetryUpdateCount} updates in ${intervalSec}s = ${String.format("%.1f", updatesPerSec)} updates/sec, subscribers: ${subscriberCount()}")
            telemetryUpdateCount = 0
            lastLogTimeMs = now
        }
        
        // LATENCY MONITORING: Log significant speed changes for debugging
        if (kotlin.math.abs(speedKmh - lastSpeedValue) >= 0.5) {
            val delta = now - lastTelemetryUpdateMs
            Log.d(TAG, "Speed changed: ${String.format("%.1f", lastSpeedValue)} -> ${String.format("%.1f", speedKmh)} km/h (delta: ${delta}ms)")
            lastSpeedValue = speedKmh
        }
        lastTelemetryUpdateMs = now
        
        val buffer = ByteBuffer.allocate(M365HudGattProfile.TELEMETRY_DATA_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
        
        buffer.putShort((speedKmh * 100).toInt().toShort())           // 0-1
        buffer.put(scooterBattery.coerceIn(0, 100).toByte())          // 2
        buffer.putShort((tempC * 10).toInt().toShort())               // 3-4
        buffer.putInt(totalMileageM.toInt())                          // 5-8
        buffer.putShort((avgSpeedKmh * 100).toInt().toShort())        // 9-10
        buffer.putShort((remainingKm * 10).toInt().toShort())         // 11-12
        buffer.put(connectionState.toByte())                          // 13
        buffer.putShort(tripMeters.toShort())                         // 14-15
        buffer.putShort(tripSeconds.toShort())                        // 16-17
        
        // Calculate CRC16
        val data = buffer.array()
        val crc = calculateCrc16(data, 0, 18)
        buffer.putShort(18, crc)
        
        currentTelemetry = data
        
        // LATENCY OPTIMIZATION: Immediately notify all subscribed devices
        // The notifyCharacteristicChanged with confirm=false (3rd param) uses 
        // notifications (unacknowledged) which is faster than indications (acknowledged)
        @Suppress("DEPRECATION")
        telemetryCharacteristic.value = currentTelemetry
        subscribersOf(M365HudGattProfile.TELEMETRY_CHAR_UUID).values.forEach { device ->
            @Suppress("DEPRECATION")
            gattServer?.notifyCharacteristicChanged(device, telemetryCharacteristic, false)
        }

        // Also update and notify time
        updateTimeData()
        notifyTime()
    }
    
    /**
     * Update time data with current time and phone battery
     */
    private fun updateTimeData() {
        val calendar = Calendar.getInstance()
        val phoneBattery = getPhoneBatteryLevel()
        
        val buffer = ByteBuffer.allocate(M365HudGattProfile.TIME_DATA_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
        
        buffer.put(calendar.get(Calendar.HOUR_OF_DAY).toByte())  // 0: Hour
        buffer.put(calendar.get(Calendar.MINUTE).toByte())       // 1: Minute
        buffer.put(calendar.get(Calendar.SECOND).toByte())       // 2: Second
        buffer.put(phoneBattery.toByte())                        // 3: Phone battery
        buffer.putInt((System.currentTimeMillis() / 1000).toInt()) // 4-7: Unix timestamp
        
        currentTime = buffer.array()
    }
    
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun notifyTime() {
        @Suppress("DEPRECATION")
        timeCharacteristic.value = currentTime
        subscribersOf(M365HudGattProfile.TIME_CHAR_UUID).values.forEach { device ->
            @Suppress("DEPRECATION")
            gattServer?.notifyCharacteristicChanged(device, timeCharacteristic, false)
        }
    }
    
    private fun getPhoneBatteryLevel(): Int {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }
    
    private fun calculateCrc16(data: ByteArray, offset: Int, length: Int): Short {
        var crc = 0xFFFF
        for (i in offset until offset + length) {
            crc = crc xor (data[i].toInt() and 0xFF)
            for (j in 0 until 8) {
                crc = if (crc and 1 != 0) (crc shr 1) xor 0xA001 else crc shr 1
            }
        }
        return crc.toShort()
    }
    
    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE])
    fun stop() {
        isRunning = false
        advertiser?.stopAdvertising(advertiseCallback)
        gattServer?.close()
        // Null out so a later start() cannot reuse a closed server.
        advertiser = null
        gattServer = null
        subscriptions.clear()
        Log.d(TAG, "GATT Server stopped")
    }

    fun isRunning(): Boolean = isRunning

    fun isDeviceConnected(): Boolean = subscriberCount() > 0

    fun getConnectedDeviceCount(): Int = subscriberCount()

    /**
     * Reads the device address, returning null instead of throwing when
     * BLUETOOTH_CONNECT is not granted (required on API 31+).
     */
    private fun BluetoothDevice.addressOrNull(): String? = try {
        address
    } catch (e: SecurityException) {
        null
    }
    
    /**
     * Get the glasses battery level received from connected glasses.
     * @return Battery percentage (0-100), or -1 if no glasses connected or not yet received
     */
    fun getGlassesBatteryLevel(): Int = glassesBatteryLevel
}
