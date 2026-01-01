package com.m365bleapp.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.util.Log
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID
import kotlin.coroutines.resume

class BleManager(private val context: Context) {
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter = bluetoothManager.adapter

    // UUIDs
    companion object {
        val UART_SERVICE: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val UART_TX: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e") // Write
        val UART_RX: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e") // Notify
        
        val AUTH_SERVICE: UUID = UUID.fromString("0000fe95-0000-1000-8000-00805f9b34fb")
        val AUTH_UPNP: UUID = UUID.fromString("00000010-0000-1000-8000-00805f9b34fb") // Write/Notify
        val AUTH_AVDTP: UUID = UUID.fromString("00000019-0000-1000-8000-00805f9b34fb") // Write/Notify
    }

    private var connectContinuation: CancellableContinuation<BluetoothGatt?>? = null
    // We only support one pending write at a time (sequential protocol)
    private var writeContinuation: CancellableContinuation<Boolean>? = null
    // We also need to track descriptor writes for enabling notifications securely
    private var descriptorContinuation: CancellableContinuation<Boolean>? = null
    private var onNotifyCallback: ((UUID, ByteArray) -> Unit)? = null

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
             if (status != BluetoothGatt.GATT_SUCCESS) {
                gatt.close()
                val cont = connectContinuation
                connectContinuation = null
                if (cont?.isActive == true) cont.resume(null)
                return
             }
             if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.discoverServices()
             } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                gatt.close()
             }
        }
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val cont = connectContinuation
            connectContinuation = null
            if (status == BluetoothGatt.GATT_SUCCESS) {
                 if (cont?.isActive == true) cont.resume(gatt)
            } else {
                 gatt.close()
                 if (cont?.isActive == true) cont.resume(null)
            }
        }
        
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            onNotifyCallback?.invoke(characteristic.uuid, characteristic.value)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
            super.onCharacteristicWrite(gatt, characteristic, status)
             val success = status == BluetoothGatt.GATT_SUCCESS
             val cont = writeContinuation
             writeContinuation = null
             if (cont?.isActive == true) cont.resume(success)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
            super.onDescriptorWrite(gatt, descriptor, status)
            val success = status == BluetoothGatt.GATT_SUCCESS
            val cont = descriptorContinuation
            descriptorContinuation = null
            if (cont?.isActive == true) cont.resume(success)
        }
    }

    @SuppressLint("MissingPermission")
    fun scan(): Flow<ScanResult> = callbackFlow {
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            close()
            return@callbackFlow
        }

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                trySend(result)
            }
            override fun onScanFailed(errorCode: Int) {
                close(Exception("Scan failed: $errorCode"))
            }
        }

        scanner.startScan(callback)
        awaitClose { scanner.stopScan(callback) }
    }

    fun getDevice(mac: String): BluetoothDevice {
        return adapter.getRemoteDevice(mac)
    }
    
    @SuppressLint("MissingPermission")
    suspend fun connect(device: BluetoothDevice, onNotify: (UUID, ByteArray) -> Unit): BluetoothGatt? = suspendCancellableCoroutine { cont ->
        if (connectContinuation != null) {
            // Busy
             cont.resume(null)
             return@suspendCancellableCoroutine
        }
        connectContinuation = cont
        onNotifyCallback = onNotify
        device.connectGatt(context, false, gattCallback)
    }

    @SuppressLint("MissingPermission")
    fun requestPriority(gatt: BluetoothGatt, priority: Int) {
        gatt.requestConnectionPriority(priority)
    }

    @SuppressLint("MissingPermission")
    suspend fun write(gatt: BluetoothGatt, serviceUuid: UUID, charUuid: UUID, value: ByteArray, waitForResponse: Boolean = true): Boolean = suspendCancellableCoroutine { cont ->
        if (waitForResponse && writeContinuation != null) {
            // Already writing and waiting
            cont.resume(false)
            return@suspendCancellableCoroutine
        }
        
        if (waitForResponse) {
             writeContinuation = cont
        }

        val service = gatt.getService(serviceUuid)
        val char = service?.getCharacteristic(charUuid)
        if (char == null) {
            if (waitForResponse) writeContinuation = null
            cont.resume(false)
            return@suspendCancellableCoroutine
        }
        
        @Suppress("DEPRECATION")
        char.value = value
        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        
        @Suppress("DEPRECATION")
        if (!gatt.writeCharacteristic(char)) {
            Log.e("BleManager", "writeCharacteristic failed for $charUuid")
            if (waitForResponse) writeContinuation = null
            cont.resume(false)
        } else {
             if (!waitForResponse) {
                 Log.d("BleManager", "writeCharacteristic initiated (no wait) for $charUuid")
                 cont.resume(true)
             }
             // If waitForResponse is true, we wait for onCharacteristicWrite
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("UNUSED_PARAMETER")
    suspend fun enableNotifications(gatt: BluetoothGatt, serviceUuid: UUID, charUuid: UUID, @Suppress("unused") callback: (ByteArray) -> Unit): Boolean = suspendCancellableCoroutine { cont ->
        if (descriptorContinuation != null) {
            cont.resume(false)
            return@suspendCancellableCoroutine
        }

        val service = gatt.getService(serviceUuid)
        val char = service?.getCharacteristic(charUuid)
        if (char == null) {
            cont.resume(false)
            return@suspendCancellableCoroutine
        }
        
        if (!gatt.setCharacteristicNotification(char, true)) {
            cont.resume(false)
            return@suspendCancellableCoroutine
        }
        
        // Write Descriptor for CCCD
        val descriptor = char.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
        if (descriptor != null) {
            descriptorContinuation = cont
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            if (!gatt.writeDescriptor(descriptor)) {
                descriptorContinuation = null
                cont.resume(false)
            }
            // Wait for onDescriptorWrite in callback
        } else {
            // No descriptor (rare for Notify), just assume success
            cont.resume(true)
        }
    }
}