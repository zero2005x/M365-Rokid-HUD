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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.m365bleapp.protocol.MtuFragmenter
import com.m365bleapp.repository.ConnectionResources
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.coroutines.resume

class BleManager internal constructor(
    private val context: Context,
    private val connectionOwner: ConnectionResources<BluetoothGatt>,
) {
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter = bluetoothManager.adapter
    
    // Disconnection callback for notifying upper layer (ScooterRepository)
    private var onDisconnectCallback: ((Long) -> Unit)? = null

    // UUIDs
    companion object {
        private const val OPERATION_TIMEOUT_MS = 3_000L
        private const val CONNECT_TIMEOUT_MS = 15_000L
        val UART_SERVICE: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val UART_TX: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e") // Write
        val UART_RX: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e") // Notify
        
        val AUTH_SERVICE: UUID = UUID.fromString("0000fe95-0000-1000-8000-00805f9b34fb")
        val AUTH_UPNP: UUID = UUID.fromString("00000010-0000-1000-8000-00805f9b34fb") // Write/Notify
        val AUTH_AVDTP: UUID = UUID.fromString("00000019-0000-1000-8000-00805f9b34fb") // Write/Notify
    }

    private var connectContinuation: CancellableContinuation<BluetoothGatt?>? = null
    // We only support one pending write at a time (sequential protocol)
    private val writeOperations = GattOperationSlot<BluetoothGattCharacteristic, CancellableContinuation<Boolean>>()
    // We also need to track descriptor writes for enabling notifications securely
    private val descriptorOperations = GattOperationSlot<BluetoothGattDescriptor, CancellableContinuation<Boolean>>()
    private var onNotifyCallback: ((UUID, ByteArray) -> Unit)? = null

    /**
     * ATT_MTU the peripheral actually granted, or [MtuFragmenter.DEFAULT_ATT_MTU]
     * before negotiation completes.
     *
     * `requestMtu()` is a *request*: the peripheral may grant less, and many
     * scooter BLE modules cap it at the 23-byte minimum. Nothing here used to
     * record what came back — `onMtuChanged` was never overridden — so the write
     * path fell back to a hard-coded 20-byte chunk and could never use a larger
     * MTU even when one had been granted.
     *
     * Volatile because it is written on the BLE callback thread and read from
     * the telemetry coroutine.
     */
    @Volatile
    var negotiatedMtu: Int = MtuFragmenter.DEFAULT_ATT_MTU
        private set

    private val _discoveredProfiles =
        MutableStateFlow<List<com.m365bleapp.protocol.GattChannels>>(emptyList())

    /**
     * GATT layouts found on the connected device, in probe order.
     *
     * Empty until service discovery completes, and empty afterwards if none of
     * the known layouts are present — which is itself informative, so an empty
     * list is never silently replaced with a guess.
     */
    val discoveredProfiles: kotlinx.coroutines.flow.StateFlow<List<com.m365bleapp.protocol.GattChannels>> =
        _discoveredProfiles.asStateFlow()

    /**
     * Translates a real `BluetoothGatt` into the pure views the discovery logic
     * consumes, and publishes the result.
     *
     * All the decision-making lives in
     * [com.m365bleapp.protocol.GattProfileDiscovery], which is unit-tested on the
     * host. This method only reads Android objects, so there is nothing here that
     * a test would need to cover.
     */
    private fun publishDiscoverableProfiles(gatt: BluetoothGatt) {
        try {
            val services = gatt.services.map { svc ->
                com.m365bleapp.protocol.GattServiceView(
                    uuid = svc.uuid,
                    characteristics = svc.characteristics.map { it.uuid },
                )
            }
            val characteristics = gatt.services.flatMap { svc ->
                svc.characteristics.map { ch ->
                    com.m365bleapp.protocol.GattCharacteristicView(
                        uuid = ch.uuid,
                        serviceUuid = svc.uuid,
                        // PROPERTY_WRITE_NO_RESPONSE alone is common on these
                        // modules, so both write properties count.
                        canWrite = ch.properties and (
                            BluetoothGattCharacteristic.PROPERTY_WRITE or
                                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
                            ) != 0,
                        canNotify = ch.properties and (
                            BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                                BluetoothGattCharacteristic.PROPERTY_INDICATE
                            ) != 0,
                    )
                }
            }

            val found = com.m365bleapp.protocol.GattProfileDiscovery.discover(services, characteristics)
            _discoveredProfiles.value = found

            if (found.isEmpty()) {
                // Not an error, but the single most useful line in a bug report
                // for an unsupported scooter: it says what the device actually
                // exposes.
                Log.w(
                    "BleManager",
                    "No known GATT layout on this device. Reported services: " +
                        com.m365bleapp.protocol.GattProfileDiscovery
                            .describeUnknown(services)
                            .joinToString()
                )
            } else {
                Log.i(
                    "BleManager",
                    "GATT layouts available: " +
                        found.joinToString { "${it.kind.displayName}(${it.kind})" }
                )
            }
        } catch (e: SecurityException) {
            // Reading uuid/properties needs BLUETOOTH_CONNECT. Not fatal: the
            // connection proceeds and the caller falls back to the known layout.
            Log.w("BleManager", "Cannot inspect services without BLUETOOTH_CONNECT", e)
        }
    }

    /** Usable ATT payload for the current link, i.e. `negotiatedMtu - 3`. */
    val usableChunkSize: Int
        get() = MtuFragmenter.chunkSizeFor(negotiatedMtu)

    private fun gattCallback(expectedEpoch: Long) = ConnectionCallback(expectedEpoch)

    private inner class ConnectionCallback(private val expectedEpoch: Long) : BluetoothGattCallback() {
        /**
         * Records the MTU the peripheral agreed to.
         *
         * A failure leaves [negotiatedMtu] at the safe default rather than
         * raising it: assuming a larger MTU than the link supports is the
         * failure mode that loses frames silently.
         */
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            connectionOwner.ifCurrent(expectedEpoch) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    negotiatedMtu = mtu
                    Log.i(
                        "BleManager",
                        "MTU negotiated: $mtu (usable payload ${MtuFragmenter.chunkSizeFor(mtu)} bytes)"
                    )
                } else {
                    Log.w(
                        "BleManager",
                        "MTU request failed (status=$status); keeping ${negotiatedMtu} " +
                            "(usable payload $usableChunkSize bytes)"
                    )
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            connectionOwner.ifCurrent(expectedEpoch) {
                if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                    abortConnection(expectedEpoch)
                    return@ifCurrent
                }
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    try {
                        val refreshMethod = gatt.javaClass.getMethod("refresh")
                        refreshMethod.invoke(gatt)
                    } catch (e: Exception) {
                        Log.w("BleManager", "GATT cache refresh unavailable: ${e.message}")
                    }
                    try {
                        if (!gatt.discoverServices()) abortConnection(expectedEpoch)
                    } catch (e: SecurityException) {
                        abortConnection(expectedEpoch)
                    }
                }
            }
        }
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            connectionOwner.ifCurrent(expectedEpoch) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    abortConnection(expectedEpoch)
                    return@ifCurrent
                }
                val continuation = connectContinuation
                connectContinuation = null
                publishDiscoverableProfiles(gatt)
                if (continuation?.isActive == true) continuation.resume(gatt)
            }
        }

        // Android 13+ (API 33) new callback with value parameter
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            connectionOwner.ifCurrent(expectedEpoch) {
                onNotifyCallback?.invoke(characteristic.uuid, value.copyOf())
            }
        }

        // Android 12 and below (deprecated in API 33)
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            connectionOwner.ifCurrent(expectedEpoch) {
                @Suppress("DEPRECATION")
                characteristic.value?.let { value ->
                    onNotifyCallback?.invoke(characteristic.uuid, value.copyOf())
                }
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
            connectionOwner.ifCurrent(expectedEpoch) {
                if (gatt !== connectionOwner.handle || characteristic == null) return@ifCurrent
                val continuation = writeOperations.complete(expectedEpoch, characteristic)
                if (continuation?.isActive == true) continuation.resume(status == BluetoothGatt.GATT_SUCCESS)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
            connectionOwner.ifCurrent(expectedEpoch) {
                if (gatt !== connectionOwner.handle || descriptor == null) return@ifCurrent
                val continuation = descriptorOperations.complete(expectedEpoch, descriptor)
                if (continuation?.isActive == true) continuation.resume(status == BluetoothGatt.GATT_SUCCESS)
            }
        }
    }

    /** Retire manager callbacks/continuations before the repository closes the detached GATT. */
    fun retireConnection(): BluetoothGatt? = connectionOwner.withCurrent {
        val detached = connectionOwner.invalidate()
        val connect = connectContinuation
        val write = writeOperations.retire()
        val descriptor = descriptorOperations.retire()
        connectContinuation = null
        onNotifyCallback = null
        negotiatedMtu = MtuFragmenter.DEFAULT_ATT_MTU
        _discoveredProfiles.value = emptyList()
        if (connect?.isActive == true) connect.resume(null)
        if (write?.isActive == true) write.resume(false)
        if (descriptor?.isActive == true) descriptor.resume(false)
        detached
    }

    /** Cancellation cannot identify a late callback for the same target, so retire the link. */
    @SuppressLint("MissingPermission")
    private fun abortConnection(expectedEpoch: Long) {
        val retired = connectionOwner.ifCurrent(expectedEpoch) {
            val callback = onDisconnectCallback
            val detached = retireConnection()
            Triple(detached, callback, connectionOwner.epoch)
        } ?: return
        runCatching { retired.first?.disconnect() }
        runCatching { retired.first?.close() }
        retired.second?.invoke(retired.third)
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

    /**
     * Set callback to be notified when BLE connection is lost.
     * This is important for detecting scooter power-off or out-of-range situations.
     */
    fun setOnDisconnectCallback(expectedEpoch: Long, callback: (Long) -> Unit) {
        connectionOwner.ifCurrent(expectedEpoch) { onDisconnectCallback = callback }
    }

    fun clearOnDisconnectCallback() {
        connectionOwner.withCurrent { onDisconnectCallback = null }
    }

    @SuppressLint("MissingPermission")
    private fun beginConnection(device: BluetoothDevice, expectedEpoch: Long, onNotify: (UUID, ByteArray) -> Unit, continuation: CancellableContinuation<BluetoothGatt?>) {
        if (!continuation.isActive) return
        if (connectContinuation != null || connectionOwner.handle != null) {
            continuation.resume(null)
            return
        }
        connectContinuation = continuation
        onNotifyCallback = onNotify
        val gatt = try {
            device.connectGatt(context, false, gattCallback(expectedEpoch), BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            abortConnection(expectedEpoch)
            null
        }
        if (gatt == null) {
            connectContinuation = null
            onNotifyCallback = null
            if (continuation.isActive) continuation.resume(null)
            return
        }
        if (!connectionOwner.attach(expectedEpoch, gatt)) {
            runCatching { gatt.close() }
            if (continuation.isActive) continuation.resume(null)
            return
        }
        continuation.invokeOnCancellation { cancelConnect(expectedEpoch, continuation) }
    }

    private fun cancelConnect(epoch: Long, continuation: CancellableContinuation<BluetoothGatt?>) {
        connectionOwner.ifCurrent(epoch) {
            if (connectContinuation === continuation) abortConnection(epoch)
        }
    }

    suspend fun connect(device: BluetoothDevice, expectedEpoch: Long, onNotify: (UUID, ByteArray) -> Unit): BluetoothGatt? =
        withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val registered = connectionOwner.ifCurrent(expectedEpoch) {
                    beginConnection(device, expectedEpoch, onNotify, continuation)
                }
                if (registered == null && continuation.isActive) continuation.resume(null)
            }
        }

    @SuppressLint("MissingPermission")
    fun requestPriority(gatt: BluetoothGatt, priority: Int) {
        gatt.requestConnectionPriority(priority)
    }

    private fun cancelWrite(epoch: Long, continuation: CancellableContinuation<Boolean>) {
        connectionOwner.ifCurrent(epoch) {
            if (writeOperations.cancel(epoch, continuation)) abortConnection(epoch)
        }
    }

    private fun cancelSubscription(epoch: Long, continuation: CancellableContinuation<Boolean>) {
        connectionOwner.ifCurrent(epoch) {
            if (descriptorOperations.cancel(epoch, continuation)) abortConnection(epoch)
        }
    }

    private fun ownedCharacteristic(epoch: Long, gatt: BluetoothGatt, service: UUID, characteristic: UUID): BluetoothGattCharacteristic? {
        if (connectionOwner.handle !== gatt) return null
        return try { findCharacteristic(gatt, service, characteristic) }
        catch (e: SecurityException) { abortConnection(epoch); null }
    }

    @SuppressLint("MissingPermission")
    private fun submitWrite(epoch: Long, gatt: BluetoothGatt, serviceUuid: UUID, charUuid: UUID, value: ByteArray, waitForResponse: Boolean, continuation: CancellableContinuation<Boolean>) {
        if (!continuation.isActive) return
        val characteristic = ownedCharacteristic(epoch, gatt, serviceUuid, charUuid)
        if (characteristic == null || !writeOperations.register(epoch, characteristic, continuation)) {
            continuation.resume(false)
            return
        }
        // WRITE_NO_RESPONSE changes ATT semantics; Android's stack callback is still
        // awaited so its late completion cannot be assigned to the next operation.
        continuation.invokeOnCancellation { cancelWrite(epoch, continuation) }
        if (!continuation.isActive || connectionOwner.boundHandle(epoch) !== gatt) return
        @Suppress("DEPRECATION")
        characteristic.value = value
        characteristic.writeType = if (waitForResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        val accepted = try {
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(characteristic)
        } catch (e: SecurityException) {
            abortConnection(epoch)
            false
        }
        if (!accepted) {
            val pending = writeOperations.complete(epoch, characteristic)
            if (pending?.isActive == true) pending.resume(false)
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun write(gatt: BluetoothGatt, serviceUuid: UUID, charUuid: UUID, value: ByteArray, waitForResponse: Boolean = true): Boolean =
        withTimeoutOrNull(OPERATION_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                connectionOwner.withCurrent { epoch ->
                    submitWrite(epoch, gatt, serviceUuid, charUuid, value, waitForResponse, continuation)
                }
            }
        } ?: false

    /**
     * Finds [charUuid] on [hintedService] if it is there, otherwise on **any**
     * discovered service.
     *
     * ## Why this is not just `gatt.getService(hintedService)`
     *
     * The auth characteristics (`AUTH_UPNP` `00000010-…`, `AUTH_AVDTP`
     * `00000019-…`) are addressed here under [AUTH_SERVICE] (`0000fe95-…`). That
     * pairing is an assumption about one vendor's GATT layout, and a wrong
     * assumption fails *silently*: `getService` returns `null`, the write or the
     * subscription resumes `false`, and the caller sees a handshake that simply
     * never completes.
     *
     * The reference implementation — **m365 Tools** (`app.peretti.m365tools`,
     * statically analysed) — never looks a service up by UUID at all. It has zero
     * calls to `BluetoothGatt.getService(UUID)`; it addresses characteristics by
     * UUID and lets the BLE layer enumerate every service to find them
     * (`mb0.smali:42-92`). It also never uses `0000fe95-…` as a *service* — it
     * only reads that UUID's advertisement service-data during scanning.
     *
     * So the hinted service is treated as a hint, not a requirement. Trying it
     * first keeps today's behaviour for a device that does expose the expected
     * layout, and scanning the rest means a device that exposes the same
     * characteristic under a different service now works instead of failing
     * silently. See `doc/reverse-engineering/m365tools-reports/02-gatt-selection.md`.
     *
     * @param gatt the connected GATT client, whose services must be discovered.
     * @param hintedService the service the characteristic is expected under.
     * @param charUuid the characteristic to find.
     * @return the characteristic, or `null` when no service exposes it.
     */
    private fun findCharacteristic(
        gatt: BluetoothGatt,
        hintedService: UUID,
        charUuid: UUID,
    ): BluetoothGattCharacteristic? {
        gatt.getService(hintedService)?.getCharacteristic(charUuid)?.let { return it }
        return gatt.services.firstNotNullOfOrNull { it.getCharacteristic(charUuid) }
    }

    @SuppressLint("MissingPermission")
    private fun submitSubscription(epoch: Long, gatt: BluetoothGatt, serviceUuid: UUID, charUuid: UUID, continuation: CancellableContinuation<Boolean>) {
        if (!continuation.isActive) return
        val characteristic = ownedCharacteristic(epoch, gatt, serviceUuid, charUuid)
        val descriptor = characteristic?.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
        if (descriptor == null || !descriptorOperations.register(epoch, descriptor, continuation)) {
            continuation.resume(false)
            return
        }
        continuation.invokeOnCancellation { cancelSubscription(epoch, continuation) }
        if (!continuation.isActive || connectionOwner.boundHandle(epoch) !== gatt) return
        val accepted = try {
            if (!gatt.setCharacteristicNotification(characteristic, true)) false
            else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            }
        } catch (e: SecurityException) {
            abortConnection(epoch)
            false
        }
        if (!accepted) {
            val pending = descriptorOperations.complete(epoch, descriptor)
            if (pending?.isActive == true) pending.resume(false)
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("UNUSED_PARAMETER")
    suspend fun enableNotifications(gatt: BluetoothGatt, serviceUuid: UUID, charUuid: UUID, @Suppress("unused") callback: (ByteArray) -> Unit): Boolean =
        withTimeoutOrNull(OPERATION_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                connectionOwner.withCurrent { epoch ->
                    submitSubscription(epoch, gatt, serviceUuid, charUuid, continuation)
                }
            }
        } ?: false
}
