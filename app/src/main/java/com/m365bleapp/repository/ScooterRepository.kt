package com.m365bleapp.repository

import android.Manifest
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.m365bleapp.R
import com.m365bleapp.ble.BleManager
import com.m365bleapp.ffi.M365Native
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import java.util.UUID
import java.nio.ByteBuffer

sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Connecting : ConnectionState()
    data class Handshaking(val status: String = "Handshaking...") : ConnectionState()
    object Ready : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

data class MotorInfo(
    val speed: Double,
    val battery: Int,
    val temp: Double,
    val mileage: Double,
    val avgSpeed: Double = 0.0,
    val tripSeconds: Int = 0,
    val tripMeters: Int = 0,
    val remainingKm: Double = 0.0
)

class ScooterRepository private constructor(private val context: Context) {
    
    companion object {
        @Volatile
        private var INSTANCE: ScooterRepository? = null
        
        fun getInstance(context: Context): ScooterRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ScooterRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    private val native = M365Native()
    private val bleManager = BleManager(context)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Helper function to get localized strings
    private fun getString(resId: Int): String = context.getString(resId)

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    private val sharedPreferences: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "secret_shared_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val incomingData = Channel<ByteArray>(Channel.UNLIMITED) // Deprecated
    private val controlChannel = Channel<ByteArray>(Channel.UNLIMITED)
    private val uartRxChannel = Channel<ByteArray>(Channel.UNLIMITED)
    
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState = _connectionState.asStateFlow()

    private val _motorInfo = MutableStateFlow<MotorInfo?>(null)
    val motorInfo = _motorInfo.asStateFlow()
    
    // Lock and Light state tracking
    private val _isLocked = MutableStateFlow(false)
    val isLocked = _isLocked.asStateFlow()
    
    private val _isLightOn = MutableStateFlow(false)
    val isLightOn = _isLightOn.asStateFlow()
    
    private val _isScanning = MutableStateFlow(false)
    val isScanning = _isScanning.asStateFlow()
    
    private val logger = com.m365bleapp.utils.TelemetryLogger(context)

    private var activeGatt: BluetoothGatt? = null
    private var sessionPtr: Long = 0
    
    // Single channel for all incoming data for now.
    // In strict implementation we might separate them, but sequential flow allows this.
    // private val incomingData = Channel<ByteArray>(Channel.UNLIMITED) // Original, now deprecated

    // UUIDs from BleManager
    private val UART_SERVICE = BleManager.UART_SERVICE
    private val UART_TX = BleManager.UART_TX
    private val UART_RX = BleManager.UART_RX
    
    private val AUTH_SERVICE = BleManager.AUTH_SERVICE
    private val AUTH_UPNP = BleManager.AUTH_UPNP
    private val AUTH_AVDTP = BleManager.AUTH_AVDTP

    fun init() {
        native.init()
    }

    fun isRegistered(mac: String): Boolean {
        return sharedPreferences.contains(mac + "_token")
    }

    fun scan(): Flow<android.bluetooth.le.ScanResult> {
        return bleManager.scan()
            .onStart { _isScanning.value = true }
            .onCompletion { _isScanning.value = false }
    }

    fun connect(mac: String, register: Boolean = false) {
        scope.launch(Dispatchers.IO) @androidx.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT) {
            _connectionState.value = ConnectionState.Connecting
            try {
                val device = bleManager.getDevice(mac)
                
                // Clear old data
                while(incomingData.tryReceive().isSuccess) {}
                while(controlChannel.tryReceive().isSuccess) {}
                while(uartRxChannel.tryReceive().isSuccess) {}

                val gatt = bleManager.connect(device) { uuid, data ->
                    Log.d("ScooterRepo", "Rx: $uuid -> ${data.toHex()}")
                    
                    // Log BLE receive to CSV
                    val charName = when (uuid) {
                        BleManager.UART_RX -> "UART_RX"
                        BleManager.AUTH_AVDTP -> "AUTH_AVDTP"
                        BleManager.AUTH_UPNP -> "AUTH_UPNP"
                        else -> uuid.toString().takeLast(8)
                    }
                    logger.logBle("RX", "NOTIFY", "BLE", charName, data, "")
                    
                    if (uuid == BleManager.UART_RX) {
                        uartRxChannel.trySend(data)
                    } else {
                        controlChannel.trySend(data)
                    }
                } 
                if (gatt == null) throw Exception(getString(R.string.error_gatt_failed))
                activeGatt = gatt
                
                // Request high priority for faster handshake
                Log.d("ScooterRepo", "Requesting High Connection Priority")
                bleManager.requestPriority(gatt, BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                
                // Request MTU for larger packets (Optional but helps)
                Log.d("ScooterRepo", "Requesting MTU 512")
                if (androidx.core.app.ActivityCompat.checkSelfPermission(
                        context,
                        android.Manifest.permission.BLUETOOTH_CONNECT
                    ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    Log.w("ScooterRepo", "Missing BLUETOOTH_CONNECT permission")
                }
                gatt.requestMtu(512)
                delay(200)

                // Enable Notifications on Handshake chars
                Log.d("ScooterRepo", "Enabling AUTH UPNP")
                bleManager.enableNotifications(gatt, AUTH_SERVICE, AUTH_UPNP) { }
                // delay(300) removed
                
                Log.d("ScooterRepo", "Enabling AUTH AVDTP")
                bleManager.enableNotifications(gatt, AUTH_SERVICE, AUTH_AVDTP) { }
                // delay(500) removed

                _connectionState.value = ConnectionState.Handshaking(getString(R.string.connecting))

                if (register) {
                    performRegistration()
                    // Registration successful. Chain to Login immediately for seamless experience.
                    Log.d("ScooterRepo", "Registration complete. Proceeding to Login.")
                    _connectionState.value = ConnectionState.Handshaking(getString(R.string.state_logging_in))
                    
                    // Retrieve the token we just saved
                    val tokenStr = sharedPreferences.getString(mac + "_token", null)
                        ?: throw Exception(getString(R.string.error_token_missing))
                    
                    // Give scooter a moment to persist the new token and reset auth state
                    delay(1000)
                    
                    performLogin(tokenStr.hexToBytes())
                } else {
                    val tokenStr = sharedPreferences.getString(mac + "_token", null)
                    Log.d("ScooterRepo", "Token retrieved: ${tokenStr != null}")
                    if (tokenStr == null) throw Exception(getString(R.string.error_no_token))
                    performLogin(tokenStr.hexToBytes())
                }
                
                Log.d("ScooterRepo", "Enabling UART RX...")
                val uartOk = bleManager.enableNotifications(gatt, UART_SERVICE, UART_RX) { }
                Log.d("ScooterRepo", "UART RX Status: $uartOk")
                
                _connectionState.value = ConnectionState.Ready
                // Beep to confirm connection (Optional but nice)
                beep()
                
                // Read initial states (light, etc.) to sync UI with scooter
                delay(500)  // Wait for connection to stabilize
                readInitialStates()
                
                startTelemetryLoop()

            } catch (e: Exception) {
                Log.e("ScooterRepo", "Connection error", e)
                _connectionState.value = ConnectionState.Error(e.message ?: getString(R.string.state_unknown_error))
                disconnect()
            }
        }
    }

    private suspend fun performRegistration() {
        // 1. Get Remote Info (UPNP -> AVDTP)
        // CMD_GET_INFO: A2 00 00 00
        writeChar(AUTH_SERVICE, AUTH_UPNP, byteArrayOf(0xA2.toByte(), 0x00, 0x00, 0x00))
        
        // Protocol: response to CMD_GET_INFO is a MiParcel (Header -> Ack -> Data -> Ack)
        // Log "00 00 00 00 02 00" confirmed it's a Header frame (Frame 4=02, 5=00 -> 2 frames).
        // Write Parcel to AVDTP
        // Write Parcel to AVDTP
        val remoteInfo = readMiParcelWithProtocol()
        // delay(250) // Reverted: Delay caused timeout. Rust proceeds fast.

        // 2. Prepare ECDH
        val myPubKey = native.prepareHandshake() 
        val ctxPtr = myPubKey.sliceArray(0 until 8).toLong()
        val myPubKeyBytes = myPubKey.sliceArray(8 until myPubKey.size)
        // Rust: public_key_bytes.as_bytes()[1..]
        val pubKeyToSend = if (myPubKeyBytes.size == 65 && myPubKeyBytes[0] == 0x04.toByte()) {
            myPubKeyBytes.sliceArray(1 until 65)
        } else {
            myPubKeyBytes
        }
        
        // Write UPNP: CMD_SET_KEY (15 00 00 00)
        Log.d("ScooterRepo", "Tx UPNP: 15 00 00 00")
        writeChar(AUTH_SERVICE, AUTH_UPNP, byteArrayOf(0x15, 0x00, 0x00, 0x00))
        // delay(50) // Reverted
        
        // Write AVDTP: CMD_SEND_DATA (00 00 00 03 04 00)
        Log.d("ScooterRepo", "Tx AVDTP: 00 00 00 03 04 00")
        writeChar(AUTH_SERVICE, AUTH_AVDTP, byteArrayOf(0x00, 0x00, 0x00, 0x03, 0x04, 0x00))
        
        // Wait for RCV_RDY (00 00 01 01) - User needs to press power button!
        Log.d("ScooterRepo", "Waiting for RCV_RDY... PLEASE PRESS POWER BUTTON ON SCOOTER!")
        _connectionState.value = ConnectionState.Handshaking(getString(R.string.state_press_power_button))
        // Essential: Users need time to reach and press the physical button on the scooter.
        // Use 30 second timeout for this step.
        waitForCmd("00000101", 30000)

        // Write Parcel to AVDTP (MiParcel: Index encoded)
        writeMiParcel(AUTH_SERVICE, AUTH_AVDTP, pubKeyToSend)
        
        // Wait for RCV_OK (00 00 01 00)
        waitForCmd("00000100")
        // delay(200) removed
        
        // 4. Send DID
        // Read Remote Key from AVDTP (Parcel)
        val remoteKeyBytes = readMiParcelWithProtocol()
        // delay(200) removed
        
        val fullRemoteKey = byteArrayOf(0x04) + remoteKeyBytes
        
        val tokenAndDid = native.processHandshake(ctxPtr, fullRemoteKey, remoteInfo)
        if (tokenAndDid.isEmpty()) throw Exception("Handshake failed")
        
        val token = tokenAndDid.sliceArray(0 until 12)
        val didCiphertext = tokenAndDid.sliceArray(12 until tokenAndDid.size)
        
        // Write AVDTP: CMD_SEND_DID (00 00 00 00 02 00)
        writeChar(AUTH_SERVICE, AUTH_AVDTP, byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x02, 0x00))
        
        waitForCmd("00000101") // RCV_RDY
        writeMiParcel(AUTH_SERVICE, AUTH_AVDTP, didCiphertext)
        waitForCmd("00000100") // RCV_OK
        
        // 5. Auth
        writeChar(AUTH_SERVICE, AUTH_UPNP, byteArrayOf(0x13, 0x00, 0x00, 0x00)) // CMD_AUTH
        waitForCmd("11000000") // RCV_AUTH_OK
        
        // Save token
        val mac = activeGatt?.device?.address ?: ""
        sharedPreferences.edit()
            .putString(mac + "_token", token.toHex())
            .apply()
    }

    private suspend fun performLogin(token: ByteArray) {
        // 1. Send Key
        // Write UPNP: CMD_LOGIN (24 00 00 00)
        // 1. Send Key
        // Write UPNP: CMD_LOGIN (24 00 00 00)
        writeChar(AUTH_SERVICE, AUTH_UPNP, byteArrayOf(0x24, 0x00, 0x00, 0x00))
        // Write AVDTP: CMD_SEND_KEY (00 00 00 0B 01 00)
        writeChar(AUTH_SERVICE, AUTH_AVDTP, byteArrayOf(0x00, 0x00, 0x00, 0x0B, 0x01, 0x00))
        
        waitForCmd("00000101") // RCV_RDY
        delay(40)
        
        val randKey = ByteArray(16).apply { java.util.Random().nextBytes(this) }
        writeMiParcel(AUTH_SERVICE, AUTH_AVDTP, randKey)
        
        waitForCmd("00000100") // RCV_OK
        // delay(200) removed
        
        // 2. Read Remote Key (Parcel from AVDTP)
        val remoteKey = readMiParcelWithProtocol()
        // delay(200) removed
        
        // 3. Read Remote Info (Parcel from AVDTP)
        val remoteInfo = readMiParcelWithProtocol()
        
        // 4. Native Login
        val res = native.login(token, randKey, remoteKey, remoteInfo)
        if (res.isEmpty()) throw Exception("Login calc failed")
        
        sessionPtr = res.sliceArray(0 until 8).toLong()
        val loginData = res.sliceArray(8 until res.size)
        
        // 5. Send Info
        writeChar(AUTH_SERVICE, AUTH_AVDTP, byteArrayOf(0x00, 0x00, 0x00, 0x0A, 0x02, 0x00)) // CMD_SEND_INFO
        waitForCmd("00000101") // RCV_RDY
        writeMiParcel(AUTH_SERVICE, AUTH_AVDTP, loginData)
        waitForCmd("00000100") // RCV_OK
        
        // 6. Confirm
        waitForCmd("21000000")
        
        // Give scooter time to finalize login state before UART communication
        delay(500)
    }
    
    // ... startTelemetryLoop uses writeNbParcel (raw) which is correct for UART ...

    private suspend fun startTelemetryLoop() {
        logger.startSession()
        Log.d("ScooterRepo", "Starting Telemetry Loop (sessionPtr=$sessionPtr)")
        
        // NOTE: The Rust ninebot-ble library ALWAYS uses counter=0 for every message!
        // See: mi_session.rs -> encrypt_uart(&self.keys.app, &cmd.as_bytes(), 0, None)
        // The scooter apparently doesn't track/require incrementing counters.
        val counter = 0L  // Always use counter=0
        var tick = 0
        var consecutiveFailures = 0
        while (currentCoroutineContext().isActive && activeGatt != null) {
            try {
                if (sessionPtr == 0L) {
                     delay(1000)
                     continue
                }
                
                // M365 Protocol Commands (from CamiAlfa M365-BLE-PROTOCOL):
                // 0xB0: Motor Info - battery%, speed, avg speed, total km, temp (param=0x20, read 32 bytes)
                // 0x3A: Trip Info - seconds this trip, meters this trip (param=0x04)
                // 0x25: Remaining km (param=0x02)
                // Cycle through these commands like Mi Home does
                
                val (attribute, payload) = when (tick % 3) {
                    0 -> 0xB0 to byteArrayOf(0x20) // Motor info: 32 bytes
                    1 -> 0x3A to byteArrayOf(0x04) // Trip info: 4 bytes  
                    else -> 0x25 to byteArrayOf(0x02) // Remaining km: 2 bytes
                }
                
                val packet = buildPacket(
                    dest = 0x20.toByte(),    // D: master to scooter
                    rw = 0x01.toByte(),      // T: read
                    attr = attribute.toByte(),
                    payload = payload
                )
                
                Log.d("ScooterRepo", "Loop: Query 0x${attribute.toString(16)}: ${packet.toHex()}")
                
                val encrypted = native.encrypt(sessionPtr, packet, counter)
                Log.d("ScooterRepo", "Encrypted (${encrypted.size} bytes): ${encrypted.toHex()}")
                
                // Write Encrypted to UART TX
                writeUartEncrypted(encrypted)
                
                val frame = readEncryptedFrame()
                if (frame.isNotEmpty()) {
                    Log.d("ScooterRepo", "Rx Encrypted (${frame.size} bytes): ${frame.toHex()}")
                    val decrypted = native.decrypt(sessionPtr, frame)
                    if (decrypted.isNotEmpty()) {
                         Log.d("ScooterRepo", "Rx Decrypted: ${decrypted.toHex()}")
                         parseTelemetry(decrypted)
                         consecutiveFailures = 0 // Reset on success
                    } else {
                         Log.w("ScooterRepo", "Decryption failed")
                         consecutiveFailures++
                    }
                } else {
                    Log.w("ScooterRepo", "No response for attribute 0x${attribute.toString(16)} (failures: $consecutiveFailures)")
                    consecutiveFailures++
                }
                
                // If too many failures, reconnect might be needed
                if (consecutiveFailures >= 10) {
                    Log.e("ScooterRepo", "Too many failures, stopping telemetry loop")
                    break
                }
                
                tick++
            } catch(e: Exception) {
                Log.e("ScooterRepo", "Loop error: ${e.message}", e)
                consecutiveFailures++
            }
            delay(500) // Poll interval
        }
    }

    private fun buildPacket(dest: Byte, rw: Byte, attr: Byte, payload: ByteArray): ByteArray {
        // For ENCRYPTED UART communication (after login), the format is DIFFERENT
        // from raw M365 serial frames!
        //
        // The encrypt_uart function expects:
        // - msg[0] = size byte (L = payload.len + 2)
        // - msg[1] = direction (0x20 = master to motor)
        // - msg[2] = read/write (0x01 = read)
        // - msg[3] = attribute (e.g., 0xB0)
        // - msg[4..] = payload parameters
        //
        // NO 55 AA header and NO checksum! The encryption function adds its own
        // 55 AB header and CRC to the encrypted output.
        //
        // Reference: ninebot-ble/src/session/commands.rs ScooterCommand::as_bytes()
        
        val payloadLen = payload.size
        // Size = payload + 2 (direction + read_write bytes, counting attr in payload)
        // Actually: size = payloadLen + 2 where the "+2" accounts for D and T
        val size = (payloadLen + 2).toByte()
        
        // Build the command bytes: [size, direction, rw, attr, payload...]
        val commandSize = 1 + 1 + 1 + 1 + payloadLen // size + D + T + attr + payload
        val commandBytes = ByteArray(commandSize)
        
        commandBytes[0] = size      // Size byte (for encrypt_uart msg[0])
        commandBytes[1] = dest      // D: 0x20 = master to motor
        commandBytes[2] = rw        // T: 0x01 = read, 0x03 = write
        commandBytes[3] = attr      // Attribute (e.g., 0xB0, 0x3A, 0x25)
        
        if (payloadLen > 0) {
            System.arraycopy(payload, 0, commandBytes, 4, payloadLen)
        }

        return commandBytes
    }
    
    private suspend fun writeUartEncrypted(data: ByteArray) {
        val gatt = activeGatt ?: return
        // MTU is 23, so payload is 20 bytes.
        val mtu = 20 
        for (i in 0 until data.size step mtu) {
            val end = (i + mtu).coerceAtMost(data.size)
            val chunk = data.copyOfRange(i, end)
            Log.d("ScooterRepo", "Tx Chunk ($i-$end): ${chunk.toHex()}")
            // Use Write With Response (true) to ensure delivery and correct pacing
            bleManager.write(gatt, UART_SERVICE, UART_TX, chunk, true)
            // No strict delay needed if waiting for response, but a small one helps stability
            delay(5) 
        }
    }
    
    // ========== Lock/Unlock Control ==========
    
    /**
     * Lock the scooter motor
     * When locked, the motor is disabled and throttle input is ignored.
     * 
     * Protocol: Write 0x0001 to address 0x70
     * Direction: Master to Motor (0x20), Command: Write (0x03)
     */
    suspend fun lock(): Result<Unit> = withContext(Dispatchers.IO) {
        if (sessionPtr == 0L) {
            return@withContext Result.failure(Exception("No active session"))
        }
        try {
            Log.d("ScooterRepo", "Locking scooter motor")
            val packet = buildPacket(
                dest = 0x20.toByte(),    // Master to Motor
                rw = 0x03.toByte(),      // Write
                attr = 0x70.toByte(),    // Lock address
                payload = byteArrayOf(0x01, 0x00)  // Value 0x0001 (little-endian: LSB first)
            )
            sendCommand(packet, "Lock Motor")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("ScooterRepo", "Lock failed: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * Unlock the scooter motor
     * Re-enables the motor after being locked.
     * 
     * Protocol: Write 0x0001 to address 0x71
     * Direction: Master to Motor (0x20), Command: Write (0x03)
     */
    suspend fun unlock(): Result<Unit> = withContext(Dispatchers.IO) {
        if (sessionPtr == 0L) {
            return@withContext Result.failure(Exception("No active session"))
        }
        try {
            Log.d("ScooterRepo", "Unlocking scooter motor")
            val packet = buildPacket(
                dest = 0x20.toByte(),    // Master to Motor
                rw = 0x03.toByte(),      // Write
                attr = 0x71.toByte(),    // Unlock address
                payload = byteArrayOf(0x01, 0x00)  // Value 0x0001 (little-endian: LSB first)
            )
            sendCommand(packet, "Unlock Motor")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("ScooterRepo", "Unlock failed: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * Set scooter lock state
     * @param locked true to lock, false to unlock
     */
    suspend fun setLock(locked: Boolean): Result<Unit> {
        return if (locked) lock() else unlock()
    }
    
    // ========== Tail Light Control ==========
    
    /**
     * Turn on the tail light (Always On mode)
     * 
     * Protocol: Write 0x0002 to address 0x7D
     * Direction: Master to Motor (0x20), Command: Write (0x03)
     */
    suspend fun lightOn(): Result<Unit> = withContext(Dispatchers.IO) {
        if (sessionPtr == 0L) {
            return@withContext Result.failure(Exception("No active session"))
        }
        try {
            Log.d("ScooterRepo", "Turning tail light on")
            val packet = buildPacket(
                dest = 0x20.toByte(),    // Master to Motor
                rw = 0x03.toByte(),      // Write
                attr = 0x7D.toByte(),    // TailLight address
                payload = byteArrayOf(0x02, 0x00)  // Value 0x0002 (little-endian: LSB first)
            )
            sendCommand(packet, "Tail Light On")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("ScooterRepo", "Light on failed: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * Turn off the tail light
     * 
     * Protocol: Write 0x0000 to address 0x7D
     * Direction: Master to Motor (0x20), Command: Write (0x03)
     */
    suspend fun lightOff(): Result<Unit> = withContext(Dispatchers.IO) {
        if (sessionPtr == 0L) {
            return@withContext Result.failure(Exception("No active session"))
        }
        try {
            Log.d("ScooterRepo", "Turning tail light off")
            val packet = buildPacket(
                dest = 0x20.toByte(),    // Master to Motor
                rw = 0x03.toByte(),      // Write
                attr = 0x7D.toByte(),    // TailLight address
                payload = byteArrayOf(0x00, 0x00)  // Value 0x0000 = Off
            )
            sendCommand(packet, "Tail Light Off")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("ScooterRepo", "Light off failed: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * Set tail light state
     * @param on true to turn on, false to turn off
     */
    suspend fun setLight(on: Boolean): Result<Unit> {
        return if (on) lightOn() else lightOff()
    }
    
    /**
     * Read the current tail light state from the scooter
     * 
     * Protocol: Read address 0x7D with param 0x02
     * Direction: Master to Motor (0x20), Command: Read (0x01)
     * Response: 0x0000=off, 0x0001=on brake, 0x0002=always on
     */
    suspend fun readLightState(): Result<Boolean> = withContext(Dispatchers.IO) {
        if (sessionPtr == 0L) {
            return@withContext Result.failure(Exception("No active session"))
        }
        try {
            Log.d("ScooterRepo", "Reading tail light state")
            val packet = buildPacket(
                dest = 0x20.toByte(),    // Master to Motor
                rw = 0x01.toByte(),      // Read
                attr = 0x7D.toByte(),    // TailLight address
                payload = byteArrayOf(0x02)  // Param: read 2 bytes
            )
            sendCommand(packet, "Read Tail Light")
            // Response will be parsed in parseTelemetryPacket
            Result.success(_isLightOn.value)
        } catch (e: Exception) {
            Log.e("ScooterRepo", "Read light state failed: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * Read the initial states (light, lock) after connection
     * Call this after the scooter is connected and ready
     */
    suspend fun readInitialStates() {
        try {
            Log.d("ScooterRepo", "Reading initial scooter states...")
            readLightState()
            // Add short delay between commands
            kotlinx.coroutines.delay(100)
            // Note: Lock state cannot be read directly from M365
            // The scooter doesn't expose a "read lock state" command
            // We'll assume unlocked by default (safer assumption)
        } catch (e: Exception) {
            Log.e("ScooterRepo", "Failed to read initial states: ${e.message}", e)
        }
    }
    
    /**
     * Send a command packet to the scooter
     * Encrypts the packet and sends it via UART
     */
    private suspend fun sendCommand(packet: ByteArray, commandName: String = "Command") {
        val counter = 0L  // Always use counter=0 (scooter doesn't track)
        val encrypted = native.encrypt(sessionPtr, packet, counter)
        Log.d("ScooterRepo", "Command Encrypted (${encrypted.size} bytes): ${encrypted.toHex()}")
        
        // Log the command to CSV
        logger.logCommand(commandName, packet)
        logger.logUartTx(encrypted, "$commandName (encrypted)")
        
        writeUartEncrypted(encrypted)
    }
    
    // Beep command
    suspend fun beep() {
        if (sessionPtr == 0L) return
        try {
            // Beep logic not verified, using a known safe query (get version) or silence usually.
            // Let's rely on Connect sound for now if we can't confirm CMD_BEEP.
        } catch (e: Exception) {}
    }

    private fun tryLegacyParse(data: ByteArray) {
        // Legacy fallback for frames without proper 55 AA header
        // Try to detect the attribute byte in the first few bytes
        for (i in 0 until minOf(data.size, 5)) {
            val attr = data[i].toUByte().toInt()
            when (attr) {
                0xB0 -> {
                    if (data.size > i + 22) {
                        parseMotorInfoFromData(data.sliceArray(i + 1 until data.size))
                        return
                    }
                }
                0xB5 -> {
                    if (data.size > i + 2) {
                        parseSpeedFromData(data.sliceArray(i + 1 until data.size))
                        return
                    }
                }
            }
        }
        Log.d("ScooterRepo", "Legacy parse failed for: ${data.toHex()}")
    }

    // Helpers
    // Changed to default waitForResponse=false (Fire and Forget) + Pacing Delay
    private suspend fun writeChar(service: UUID, char: UUID, data: ByteArray, waitForResponse: Boolean = false) {
        Log.d("ScooterRepo", "Tx: $char -> ${data.toHex()}")
        
        // Log to CSV
        val serviceName = when (service) {
            UART_SERVICE -> "UART"
            AUTH_SERVICE -> "AUTH"
            else -> service.toString().takeLast(8)
        }
        val charName = when (char) {
            UART_TX -> "TX"
            UART_RX -> "RX"
            AUTH_UPNP -> "UPNP"
            AUTH_AVDTP -> "AVDTP"
            else -> char.toString().takeLast(8)
        }
        logger.logBle("TX", "WRITE", serviceName, charName, data, "")
        
        val gatt = activeGatt
        if (gatt != null) {
            bleManager.write(gatt, service, char, data, waitForResponse)
        }
        delay(20) // Normal pacing delay
    }
    
    // Write Raw Chunks (NbParcel)
    private suspend fun writeNbParcel(service: UUID, char: UUID, data: ByteArray) {
         val chunkSize = 20
         var offset = 0
         while (offset < data.size) {
             val end = (offset + chunkSize).coerceAtMost(data.size)
             val chunk = data.sliceArray(offset until end)
             writeChar(service, char, chunk)
             offset += chunkSize
             delay(20)
         }
    }
    
    // Write Mi Protocol Chunks (Index + 0x00 + payload)
    private suspend fun writeMiParcel(service: UUID, char: UUID, data: ByteArray) {
         val chunkSize = 18 // 20 - 2 bytes header
         var offset = 0
         var chunkIndex = 1
         
         while (offset < data.size) {
             val end = (offset + chunkSize).coerceAtMost(data.size)
             val payload = data.sliceArray(offset until end)
             
             // [Index, 0x00, Payload...]
             val buffer = ByteArray(2 + payload.size)
             buffer[0] = chunkIndex.toByte()
             buffer[1] = 0x00
             System.arraycopy(payload, 0, buffer, 2, payload.size)
             
             writeChar(service, char, buffer)
             
             offset += chunkSize
             chunkIndex++
             delay(20)
         }
    }
    
    private suspend fun readMiParcelWithProtocol(): ByteArray {
        Log.d("ScooterRepo", "Reading MiParcel Header...")
        val header = waitForControlData()
        // Header: [.. .. .. .. LenL LenH]
        if (header.size < 6) throw Exception("Invalid parcel header: ${header.toHex()}")
        
        val totalFrames = (header[4].toUByte().toInt()) + (header[5].toUByte().toInt() * 256)
        Log.d("ScooterRepo", "MiParcel expecting $totalFrames frames")
        
        // Ack (RCV_RDY)
        writeChar(AUTH_SERVICE, AUTH_AVDTP, byteArrayOf(0x00, 0x00, 0x01, 0x01))
        
        val buffer = java.io.ByteArrayOutputStream()
        var framesRead = 0
        while (framesRead < totalFrames) {
            val f = waitForControlData()
            // Data Frame: [Index, 0x00, Payload...]
            if (f.size > 2) {
                 buffer.write(f, 2, f.size - 2)
            }
            framesRead++
        }
        
        Log.d("ScooterRepo", "MiParcel read complete: ${buffer.toByteArray().toHex()}")
        
        // Ack (RCV_OK)
        writeChar(AUTH_SERVICE, AUTH_AVDTP, byteArrayOf(0x00, 0x00, 0x01, 0x00))
        
        return buffer.toByteArray()
    }
    
    private suspend fun readNbParcel(frames: Int): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        repeat(frames) {
            val chunk = waitForControlData()
            buffer.write(chunk, 0, chunk.size)
        }
        return buffer.toByteArray()
    }
    
    private suspend fun waitForControlData(): ByteArray {
        // BLE can be slow, especially during heavy crypto or negotiation.
        // Increased to 30s to avoid timeouts during registration/pairing
        return withTimeout(30000) {
            controlChannel.receive()
        }
    }

    private suspend fun waitForData(timeoutMs: Long = 5000): ByteArray {
        // Shorter timeout for UART telemetry responses
        return withTimeout(timeoutMs) {
            uartRxChannel.receive()
        }
    }

    private suspend fun waitForCmd(expectedHex: String, timeoutMs: Long = 5000): ByteArray {
        return withTimeout(timeoutMs) {
            while (true) {
                val data = controlChannel.receive()
                val hex = data.toHex()
                if (hex.startsWith(expectedHex)) {
                    Log.d("ScooterRepo", "Matched CMD: $hex")
                    return@withTimeout data
                }
                Log.d("ScooterRepo", "Ignored: $hex != $expectedHex")
            }
            @Suppress("UNREACHABLE_CODE")
            ByteArray(0)
        }
    }
    
    private suspend fun readEncryptedFrame(): ByteArray {
        Log.d("ScooterRepo", "Waiting for Encrypted Frame...")
        
        val chunk1: ByteArray
        try {
            chunk1 = waitForData(5000) // 5 second timeout for initial response
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Log.w("ScooterRepo", "Timeout waiting for encrypted frame")
            return ByteArray(0)
        }
        
        Log.d("ScooterRepo", "Received Chunk1: ${chunk1.toHex()}")
        
        if (chunk1.size < 3 || chunk1[0] != 0x55.toByte()) {
            Log.w("ScooterRepo", "Invalid frame start: ${chunk1.toHex()}")
            return chunk1
        }
        
        val buffer = java.io.ByteArrayOutputStream()
        buffer.write(chunk1, 0, chunk1.size)
        
        // If the chunk is maxed out for typical MTU (23->20 or 256->244), wait for more
        if (chunk1.size >= 20) {
            while (true) {
                try {
                    // Short timeout for fragments
                    val next = withTimeout(500) { uartRxChannel.receive() }
                    Log.d("ScooterRepo", "Received Fragment: ${next.toHex()}")
                    buffer.write(next, 0, next.size)
                    if (next.size < 20) break // Last packet is usually smaller
                } catch (e: Exception) {
                    break // Timeout means no more fragments
                }
            }
        }
        
        val fullData = buffer.toByteArray()
        Log.d("ScooterRepo", "Full Encrypted Frame: ${fullData.toHex()}")
        return fullData
    }


    private fun parseTelemetry(packet: ByteArray) {
        if (packet.isEmpty()) return
        
        // ENCRYPTED UART Response Format (from decrypt_uart):
        // The decrypted data does NOT include size byte or 55 AA header!
        // 
        // Actual format (from ninebot-ble/src/session/payload.rs):
        // [0]: direction (0x23 = motor to master, 0x25 = battery to master)
        // [1]: type (0x01 = read response)
        // [2]: attribute (e.g., 0xB0)
        // [3...n-4]: response data
        // [last 4 bytes]: random padding from encryption (should be ignored)
        //
        // Reference: pop_head() in payload.rs removes first 3 bytes (dir, type, attr)
        
        Log.d("ScooterRepo", "Parsing telemetry: ${packet.toHex()}")
        
        if (packet.size < 7) { // At least dir + type + attr + some data + 4 padding
            Log.w("ScooterRepo", "Packet too short: ${packet.size} bytes")
            return
        }
        
        // Parse the header (NO size byte at the start!)
        val direction = packet[0].toUByte().toInt()  // 0x23 = motor to master, 0x25 = battery to master
        val rw = packet[1].toUByte().toInt()         // 0x01 = read response
        val attr = packet[2].toUByte().toInt()       // Attribute (0xB0, 0x3A, 0x25, etc.)
        
        // Data starts at index 3, ends 4 bytes before the end (random padding)
        val dataEnd = packet.size - 4  // Exclude 4 bytes of random padding
        val dataLen = dataEnd - 3      // Subtract 3 for header (dir, type, attr)
        
        if (dataLen <= 0) {
            Log.w("ScooterRepo", "No data in response, packetSize=${packet.size}")
            return
        }
        
        val data = packet.sliceArray(3 until dataEnd)
        Log.d("ScooterRepo", "Response: Dir=0x${direction.toString(16)}, RW=0x${rw.toString(16)}, Attr=0x${attr.toString(16)}, DataLen=${data.size}, Data=${data.toHex()}")
        
        when (attr) {
            0xB0 -> parseMotorInfoFromData(data)
            0x3A -> parseTripInfo(data)
            0x25 -> parseRemainingKm(data)
            0xB5 -> parseSpeedFromData(data)
            0x7D -> parseTailLightState(data)
            0x7C -> parseCruiseState(data)
            else -> Log.d("ScooterRepo", "Unknown attribute: 0x${attr.toString(16)}")
        }
    }
    
    /**
     * Parse 0x7D Tail Light state response
     * Data format: u16 LE - 0x0000=off, 0x0001=on brake, 0x0002=always on
     */
    private fun parseTailLightState(data: ByteArray) {
        if (data.size < 2) {
            Log.w("ScooterRepo", "Tail light data too short: ${data.size} bytes")
            return
        }
        val value = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
        val isOn = value > 0  // 0x0001 or 0x0002 means light is on
        Log.d("ScooterRepo", "Tail light state: 0x${value.toString(16)} -> isOn=$isOn")
        _isLightOn.value = isOn
    }
    
    /**
     * Parse 0x7C Cruise state response
     * Data format: u16 LE - 0x0000=off, 0x0001=on
     */
    private fun parseCruiseState(data: ByteArray) {
        if (data.size < 2) {
            Log.w("ScooterRepo", "Cruise data too short: ${data.size} bytes")
            return
        }
        val value = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
        Log.d("ScooterRepo", "Cruise state: 0x${value.toString(16)} -> isOn=${value > 0}")
        // Could add cruise state flow if needed
    }

    /**
     * Parse 0xB0 Motor Info response
     * Based on ninebot-ble/src/session/info.rs and protocol.md:
     * 
     * The data layout after header removal (from protocol.md examples):
     * Bytes 0-7:   Var176-179 (error, warning, flags, workmode) - 8 bytes to skip
     * Bytes 8-9:   Var180 = battery % (u16 LE)
     * Bytes 10-11: Var181 = speed in m/h (i16 LE, divide by 1000 for km/h)
     * Bytes 12-13: Var182 = avg speed in m/h (u16 LE, divide by 1000 for km/h)
     * Bytes 14-17: Var183-184 = total distance in meters (u32 LE)
     * Bytes 18-19: Var185 = trip distance (i16)
     * Bytes 20-21: Var186 = uptime seconds (i16)
     * Bytes 22-23: Var187 = temperature * 10 (i16 LE, divide by 10 for Celsius)
     */
    private fun parseMotorInfoFromData(data: ByteArray) {
        Log.d("ScooterRepo", "MotorInfo raw data (${data.size} bytes): ${data.toHex()}")
        
        if (data.size < 22) {
            Log.w("ScooterRepo", "Motor info data too short: ${data.size} bytes, need at least 22")
            return
        }
        
        // Debug: dump all u16 values at each offset
        val bb = ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until minOf(data.size - 1, 24) step 2) {
            val v = bb.getShort(i).toInt() and 0xFFFF
            Log.d("ScooterRepo", "  offset $i: 0x${v.toString(16)} = $v")
        }
        
        // Try the standard offsets first
        val batteryPercent = bb.getShort(8).toInt() and 0xFFFF
        val speedRaw = bb.getShort(10)
        val avgSpeedRaw = bb.getShort(12).toInt() and 0xFFFF
        val totalDistanceM = bb.getInt(14)
        var tempRaw = 0
        if (data.size >= 24) {
            tempRaw = bb.getShort(22).toInt()
        }
        
        val speedKmh = speedRaw.toFloat() / 1000.0f
        val avgSpeedKmh = avgSpeedRaw.toFloat() / 1000.0f
        val tempC = tempRaw.toFloat() / 10.0f
        val totalDistanceKm = totalDistanceM / 1000.0
        
        Log.d("ScooterRepo", "Parsed (standard offsets): Battery=$batteryPercent%, Speed=$speedKmh km/h, AvgSpeed=$avgSpeedKmh km/h, TotalDist=$totalDistanceKm km, Temp=$tempC°C")
        
        // Also try single-byte battery at offset 7 (observed value 0x4F = 79)
        val batteryAlt = data[7].toUByte().toInt()
        Log.d("ScooterRepo", "Alt battery (byte 7): $batteryAlt%")
        
        // Use the better battery value
        val finalBattery = if (batteryPercent in 1..100) batteryPercent else batteryAlt
        
        // Preserve existing trip/remaining values
        val existing = _motorInfo.value
        val info = MotorInfo(
            speed = speedKmh.toDouble(),
            battery = finalBattery,
            temp = tempC.toDouble(),
            mileage = totalDistanceKm,
            avgSpeed = avgSpeedKmh.toDouble(),
            tripSeconds = existing?.tripSeconds ?: 0,
            tripMeters = existing?.tripMeters ?: 0,
            remainingKm = existing?.remainingKm ?: 0.0
        )
        _motorInfo.value = info
        logger.log(info)
    }
    
    /**
     * Parse 0x3A Trip Info response (4 bytes)
     * Var58: seconds this trip (u16)
     * Var59: meters this trip (u16)
     */
    private fun parseTripInfo(data: ByteArray) {
        if (data.size < 4) return
        
        val bb = ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val tripSeconds = bb.getShort(0).toInt() and 0xFFFF
        val tripMeters = bb.getShort(2).toInt() and 0xFFFF
        
        Log.d("ScooterRepo", "TripInfo: ${tripSeconds}s, ${tripMeters}m")
        
        // Update motorInfo with trip data
        val existing = _motorInfo.value
        if (existing != null) {
            _motorInfo.value = existing.copy(
                tripSeconds = tripSeconds,
                tripMeters = tripMeters
            )
        }
    }
    
    /**
     * Parse 0x25 Remaining KM response (2 bytes)
     * Var37: remaining km * 10 (u16, divide by 10 for km)
     */
    private fun parseRemainingKm(data: ByteArray) {
        if (data.size < 2) return
        
        val bb = ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val remainingRaw = bb.getShort(0).toInt() and 0xFFFF
        val remainingKm = remainingRaw / 10.0
        
        Log.d("ScooterRepo", "Remaining: $remainingKm km")
        
        // Update motorInfo with remaining range
        val existing = _motorInfo.value
        if (existing != null) {
            _motorInfo.value = existing.copy(remainingKm = remainingKm)
        }
    }
    
    /**
     * Parse speed from 0xB5 response
     */
    private fun parseSpeedFromData(data: ByteArray) {
        if (data.size < 2) return
        
        val bb = ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val speedRaw = bb.getShort(0)
        val speedKmh = speedRaw.toFloat() / 1000.0f
        
        Log.d("ScooterRepo", "Speed (0xB5): $speedKmh km/h")
        updateSpeed(speedKmh.toDouble())
    }

    private fun updateSpeed(speedKmh: Double) {
         val current = _motorInfo.value
         if (current != null) {
              _motorInfo.value = current.copy(speed = speedKmh)
         } else {
              _motorInfo.value = MotorInfo(speedKmh, 0, 0.0, 0.0)
         }
    }


    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun disconnect() {
        logger.stopSession()
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        
        // Important: Must call disconnect() BEFORE close() to properly release
        // the BLE connection and clear Android's connection cache.
        // Just calling close() leaves the device in a cached state,
        // preventing it from being discovered again on subsequent scans.
        activeGatt?.let { gatt ->
            try {
                gatt.disconnect()
                // Small delay to ensure disconnect completes before close
                Thread.sleep(100)
            } catch (e: Exception) {
                Log.w("ScooterRepo", "Error during disconnect: ${e.message}")
            }
            try {
                gatt.close()
            } catch (e: Exception) {
                Log.w("ScooterRepo", "Error during close: ${e.message}")
            }
        }
        activeGatt = null
        _connectionState.value = ConnectionState.Disconnected
        if (sessionPtr != 0L) {
             native.freeSession(sessionPtr)
             sessionPtr = 0
        }
        
        Log.i("ScooterRepo", "Disconnected and cleaned up BLE resources")
    }
    
    fun getLogs(): List<java.io.File> = logger.getLogFiles()
    
    // Utils
    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun ByteArray.sliceArray(range: IntRange): ByteArray = copyOfRange(range.first, range.last + 1)
    private fun Long.toByteArray(): ByteArray = ByteArray(8) // dummy
    private fun ByteArray.toLong(): Long = ByteBuffer.wrap(this).long
}
