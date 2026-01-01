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
    }
    
    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private val subscribedDevices = ConcurrentHashMap<String, BluetoothDevice>()
    
    private lateinit var telemetryCharacteristic: BluetoothGattCharacteristic
    private lateinit var statusCharacteristic: BluetoothGattCharacteristic
    private lateinit var timeCharacteristic: BluetoothGattCharacteristic
    
    // Current data
    @Volatile private var currentTelemetry: ByteArray = ByteArray(M365HudGattProfile.TELEMETRY_DATA_SIZE)
    @Volatile private var currentTime: ByteArray = ByteArray(M365HudGattProfile.TIME_DATA_SIZE)
    
    private var isRunning = false
    
    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            Log.d(TAG, "Connection state changed: ${device.address} -> $newState")
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                subscribedDevices.remove(device.address)
                Log.d(TAG, "Device disconnected: ${device.address}")
            } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "Device connected: ${device.address}")
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
            if (descriptor.uuid == M365HudGattProfile.CCCD_UUID) {
                if (value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                    Log.d(TAG, "Notifications enabled for ${device.address}")
                    subscribedDevices[device.address] = device
                } else if (value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)) {
                    Log.d(TAG, "Notifications disabled for ${device.address}")
                    subscribedDevices.remove(device.address)
                }
                
                if (responseNeeded) {
                    if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) 
                        == PackageManager.PERMISSION_GRANTED) {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                    }
                }
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
            }
        }
    }
    
    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE])
    fun start(): Boolean {
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
        
        service.addCharacteristic(telemetryCharacteristic)
        service.addCharacteristic(statusCharacteristic)
        service.addCharacteristic(timeCharacteristic)
        
        gattServer?.addService(service)
        
        // Start Advertising
        startAdvertising()
        
        isRunning = true
        Log.i(TAG, "GATT Server started successfully")
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
        
        // Primary advertisement: Service UUID only (fits in 31 bytes)
        // Note: Device name + 128-bit UUID exceeds 31 byte limit
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(M365HudGattProfile.SERVICE_UUID))
            .build()
        
        // Scan response: Device name for identification
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()
        
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
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
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
        
        // Notify all subscribed devices
        @Suppress("DEPRECATION")
        telemetryCharacteristic.value = currentTelemetry
        subscribedDevices.values.forEach { device ->
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
        subscribedDevices.values.forEach { device ->
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
        subscribedDevices.clear()
        Log.d(TAG, "GATT Server stopped")
    }
    
    fun isRunning(): Boolean = isRunning
    
    fun isDeviceConnected(): Boolean = subscribedDevices.isNotEmpty()
    
    fun getConnectedDeviceCount(): Int = subscribedDevices.size
}
