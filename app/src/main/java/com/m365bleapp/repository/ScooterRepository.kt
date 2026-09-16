package com.m365bleapp.repository

import android.Manifest
import android.annotation.SuppressLint
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
import com.m365bleapp.protocol.MtuFragmenter
import com.m365bleapp.protocol.ModelOverrideStore
import com.m365bleapp.protocol.EscTelemetryParser
import com.m365bleapp.protocol.PlaintextRegisterSession
import com.m365bleapp.protocol.ScooterModelRegistry
import com.m365bleapp.protocol.WriteRetryPolicy
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

/**
 * Tiered Retry Strategy for BLE connection error recovery.
 * Implements exponential backoff based on consecutive failure count.
 */
sealed class RetryStrategy {
    object Immediate : RetryStrategy()     // Retry immediately
    data class ShortDelay(val delayMs: Long = 500L) : RetryStrategy()  // Retry after 500ms
    data class LongDelay(val delayMs: Long = 3000L) : RetryStrategy()  // Retry after 3 seconds
    object Reconnect : RetryStrategy()     // Full reconnection required
    
    companion object {
        fun fromFailureCount(failures: Int): RetryStrategy {
            return when {
                failures < 3 -> Immediate
                failures < 5 -> ShortDelay()
                failures < 10 -> LongDelay()
                else -> Reconnect
            }
        }
    }
}

data class MotorInfo(
    val speed: Double,
    val battery: Int,
    val temp: Double,
    val mileage: Double,
    val avgSpeed: Double = 0.0,
    val tripSeconds: Int = 0,
    val tripMeters: Int = 0,
    val remainingKm: Double = 0.0,

    // ---- fields added with the BMS/ESC parsers (stages B1/B2) ----------------
    // All default to null so an older producer still compiles and a value the
    // scooter has not reported shows as "unknown" rather than as zero. A zero
    // that was never measured is indistinguishable from a genuine zero on screen,
    // which is exactly the ambiguity these types avoid.

    /** ESC `0x1B` error / warning code, when reported. */
    val errorCode: Int? = null,
    /** Human description for [errorCode]. */
    val errorDescription: String? = null,
    /** Ride mode from ESC `0x75`. */
    val rideMode: com.m365bleapp.protocol.EscTelemetryParser.RideMode? = null,
    /** KERS level from ESC `0x7B`. */
    val kersLevel: com.m365bleapp.protocol.EscTelemetryParser.KersLevel? = null,
    /** ESC / frame temperature from `0x3E`, °C. */
    val escTemperatureC: Double? = null,
    /** Battery pack temperature from BMS `0x35`, °C. */
    val batteryTemperatureC: Double? = null,
    /** Motor phase current from ESC `0x53`, A. */
    val phaseCurrentA: Double? = null,
    /** Pack current from BMS `0x31`, A (negative while discharging). */
    val batteryCurrentA: Double? = null,
    /** Pack voltage from BMS `0x31`, V. */
    val packVoltageV: Double? = null,
    /** Derived pack power, W. */
    val packPowerW: Double? = null,
    /** Charge remaining from BMS `0x31`, mAh. */
    val remainingMah: Int? = null,
    /** Highest cell voltage from BMS `0x40`, V. */
    val highestCellV: Double? = null,
    /** Lowest cell voltage from BMS `0x40`, V. */
    val lowestCellV: Double? = null,
    /** Highest-minus-lowest cell, V. The number that shows an unbalanced pack. */
    val cellSpreadV: Double? = null,
    /** State of health from BMS `0x3B`, percent. */
    val batteryHealthPercent: Int? = null,
    /** True while the BMS reports charging (`0x30` bit 6). */
    val isCharging: Boolean? = null,
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
        
        // === Dynamic Polling Interval (BLE Connection Optimization) ===
        // Shorter interval while moving for real-time HUD display, longer when idle to save power
        private const val POLL_INTERVAL_MOVING_MS = 100L    // Speed > threshold
        private const val POLL_INTERVAL_IDLE_MS = 500L      // Speed <= threshold
        private const val SPEED_THRESHOLD_KMH = 5.0

        /**
         * Tick period for the hardware-free demo ride.
         *
         * 1 s matches Scootbatt's base HUD poll rate, so a demo exercises the
         * same update cadence a real scooter would produce.
         */
        const val DEMO_TICK_MS = 1_000L

        // === Plaintext (stage C1) ===

        /**
         * Gap between plaintext register reads.
         *
         * 1 s matches Scootbatt's base HUD poll rate and leaves room for a reply
         * plus its timeout without stacking requests.
         */
        const val PLAINTEXT_POLL_INTERVAL_MS = 1_000L

        /**
         * How long to wait for a complete reply before moving on.
         *
         * Scootbatt uses 300/350/1000 ms per request depending on the parser; a
         * single 1 s budget is simpler and, on a plaintext link with no crypto
         * work, generous.
         */
        const val PLAINTEXT_REPLY_TIMEOUT_MS = 1_000L

        /** Wait for one notification before re-checking the overall deadline. */
        const val PLAINTEXT_CHUNK_WAIT_MS = 250L

        /**
         * Consecutive failures before the link is declared dead.
         *
         * Scootbatt has no such counter: it drops a request after three attempts
         * and keeps polling a link that may be gone, relying on the GATT callback
         * alone. Counting here is a deliberate improvement, because a HUD that
         * silently shows stale data is worse than one that reconnects.
         */
        const val PLAINTEXT_FAILURES_BEFORE_RECONNECT = 5

        /**
         * Handshake frames to send before declaring NinebotCrypto pairing failed.
         *
         * Each stage is retried at its own interval, so this bounds the whole
         * exchange rather than a single step. NinebotCrypto pairing normally needs
         * a power-button press, so a handful of attempts is generous.
         */
        const val NINEBOT_HANDSHAKE_MAX_ATTEMPTS = 12
        
        // === Tiered Query Strategy ===
        // Different data types have different update frequency requirements
        private const val TRIP_QUERY_INTERVAL_TICKS = 50    // ~5 seconds at 100ms polling
        private const val RANGE_QUERY_INTERVAL_TICKS = 100  // ~10 seconds
        private const val BATTERY_QUERY_INTERVAL_TICKS = 50 // ~5 seconds
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

    // Channel capacity limited to prevent memory leaks if consumer is blocked
    private val controlChannel = Channel<ByteArray>(64)
    private val uartRxChannel = Channel<ByteArray>(64)
    
    /**
     * Durable last-known vehicle state, so the detail page is readable offline.
     * See VehicleSnapshotStore for why identity and telemetry are stored
     * differently.
     */
    private val snapshotStore = VehicleSnapshotStore.getInstance(context)

    /**
     * MAC of the scooter currently (or most recently) connected.
     *
     * `activeGatt` is nulled on disconnect, so the snapshot collector needs this
     * to keep attributing readings to the right scooter during teardown.
     */
    @Volatile
    private var lastConnectedMac: String? = null

    /**
     * Advertised name of the scooter that was last connected.
     *
     * Kept because the diagnostics block and model resolution both need it, and
     * `activeGatt` is nulled on disconnect.
     */
    @Volatile
    private var lastConnectedAdvertisedName: String? = null

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

    // These are touched from the connect coroutine (Dispatchers.IO), the
    // telemetry loop, disconnect() (often Main) and the disconnect callback
    // (Main). @Volatile gives cross-thread visibility; connectionLock makes
    // teardown atomic so the native session cannot be released while another
    // thread is still deciding to use it.
    @Volatile
    private var activeGatt: BluetoothGatt? = null
    @Volatile
    private var sessionPtr: Long = 0

    private val connectionLock = Any()

    /**
     * Job feeding synthetic telemetry while the hardware-free demo runs.
     *
     * `null` whenever the demo is stopped, which is the normal state. Only ever
     * non-null through [startDemo], so a real connection never sees demo data:
     * [connect] and [disconnect] both stop the demo first.
     */
    @Volatile
    private var demoJob: Job? = null

    /** True while [startDemo] is feeding synthetic telemetry. */
    val isDemoRunning: Boolean get() = demoJob?.isActive == true

    /**
     * Plaintext register session, or `null` on a crypto scooter.
     *
     * Stage C1: scooters that speak plain `5A A5` framing never run the crypto
     * handshake, so they need their own request path. [PlaintextRegisterSession]
     * owns that framing and is the only consumer of [FrameCodec], which otherwise
     * had no call sites.
     */
    @Volatile
    private var plaintextSession: com.m365bleapp.protocol.PlaintextRegisterSession? = null

    /** True when the current connection is using plaintext framing. */
    val isPlaintextMode: Boolean get() = plaintextSession != null
    
    // Single channel for all incoming data for now.
    // In strict implementation we might separate them, but sequential flow allows this.
    // private val incomingData = Channel<ByteArray>(Channel.UNLIMITED) // Original, now deprecated

    // Data-plane UUIDs.
    //
    // These start at Nordic UART (the M365 layout) and are REPLACED after
    // service discovery with whatever the device actually exposes. Three layouts
    // exist in the field and they are not interchangeable: the Ninebot custom
    // profile notifies on `…0004` rather than `…0003`, so code that hard-codes
    // NUS subscribes to a characteristic that never emits.
    //
    // `@Volatile` because they are written once during connect and read from the
    // telemetry coroutine.
    @Volatile private var uartService: UUID = BleManager.UART_SERVICE
    @Volatile private var uartTx: UUID = BleManager.UART_TX
    @Volatile private var uartRx: UUID = BleManager.UART_RX

    /**
     * Which GATT layout the connected scooter answered on, once known.
     *
     * Exposed so a bug report can state it: "connected via Ninebot Custom" is the
     * first thing needed to explain a scooter that connects but never yields
     * telemetry.
     */
    private val _activeProfile = MutableStateFlow<com.m365bleapp.protocol.GattProfileKind?>(null)
    val activeProfile = _activeProfile.asStateFlow()

    /**
     * Protocol dialect detection, with its result cached per MAC.
     *
     * Detection is a probe — attempting a handshake and seeing what answers —
     * because nothing observable before connecting identifies a dialect. Xiaomi,
     * Ninebot and current Segway models all advertise the same Nordic UART
     * service, and the same model name spans several wire generations.
     */
    private val protocolProbe by lazy { com.m365bleapp.protocol.ProtocolProbe(context) }

    private val _detectedProtocol =
        MutableStateFlow(com.m365bleapp.protocol.ScooterProtocol.UNKNOWN)

    /**
     * The dialect this scooter was found to speak, or [ScooterProtocol.UNKNOWN].
     *
     * Only ever set from evidence — a completed handshake — never from a name or
     * a service UUID.
     */
    val detectedProtocol = _detectedProtocol.asStateFlow()

    // Auth-plane UUIDs. These belong to the Xiaomi Mi auth handshake and are
    // only meaningful on the Xiaomi family; other families do not use them.
    private val AUTH_SERVICE = BleManager.AUTH_SERVICE
    private val AUTH_UPNP = BleManager.AUTH_UPNP
    private val AUTH_AVDTP = BleManager.AUTH_AVDTP

    /**
     * Points the data plane at the layout the device actually exposes.
     *
     * Called after service discovery. With no discovered profile the NUS defaults
     * stand, which preserves the previous behaviour exactly — this is additive,
     * not a rewrite of the working M365 path.
     *
     * The **first** profile in the returned order is chosen, and that order puts
     * Nordic UART first deliberately: devices that advertise both layouts may
     * answer on only NUS (a Max G3 does), so preferring the manufacturer-specific
     * service would connect to something that never replies.
     */
    /**
     * Drives the NinebotCrypto `5B/5C/5D` pairing exchange (stage C2).
     *
     * @return true once the scooter has acknowledged the UID.
     *
     * The state machine in [NinebotHandshake] decides what to send; this method
     * owns the radio and the clock. A timeout marks the stage failed rather than
     * simply retrying forever, because a stage that never fails would report
     * "pairing" indefinitely and the caller would never surface an error.
     */
    private suspend fun runNinebotHandshake(
        gatt: android.bluetooth.BluetoothGatt,
        advertisedName: ByteArray,
    ): Boolean {
        val handshake = com.m365bleapp.protocol.NinebotHandshake(advertisedName)

        // Drain anything left from service discovery so the first reply belongs to
        // the first request.
        while (controlChannel.tryReceive().isSuccess) { /* discard */ }

        var attempts = 0
        while (currentCoroutineContext().isActive &&
            activeGatt === gatt &&
            !handshake.isPaired
        ) {
            val frame = handshake.nextFrame()
            if (frame == null) break

            if (attempts >= NINEBOT_HANDSHAKE_MAX_ATTEMPTS) {
                handshake.fail("no reply after $attempts attempts")
                break
            }
            attempts++

            try {
                writeChar(uartService, uartTx, frame, waitForResponse = false)
                val reply = withTimeoutOrNull(handshake.retryIntervalMs) {
                    controlChannel.receive()
                }
                if (reply == null) {
                    Log.d(
                        "ScooterRepo",
                        "NinebotCrypto: no reply in ${handshake.retryIntervalMs} ms, " +
                            "resending (attempt $attempts)"
                    )
                    continue
                }
                // A reply may be a full frame or just the counted bytes; the state
                // machine only inspects the payload region, so both are passed
                // through unchanged and it validates the length itself.
                if (!handshake.acceptReply(reply)) {
                    Log.d(
                        "ScooterRepo",
                        "NinebotCrypto: reply did not advance stage " +
                            "${handshake.stage} (${reply.size} bytes)"
                    )
                }
            } catch (e: Exception) {
                Log.w("ScooterRepo", "NinebotCrypto: handshake write failed: ${e.message}")
            }
        }

        return if (handshake.isPaired) {
            Log.i("ScooterRepo", "NinebotCrypto: paired")
            true
        } else {
            val reason = handshake.failureReason ?: "handshake did not complete"
            Log.e("ScooterRepo", "NinebotCrypto: pairing failed: $reason")
            _connectionState.value = ConnectionState.Error("Pairing failed: $reason")
            false
        }
    }

    /**
     * The dialect to use for the connection currently being established.
     *
     * Falls back to [ScooterProtocol.XIAOMI_MI] because a probe has not yet run at
     * this point in `connect()` and the Xiaomi path is the historically implemented
     * one. This is the single place that decision is made, so a future probe can
     * change the outcome without touching the branch that consumes it.
     */
    private fun detectedProtocolOrPlaintext(): com.m365bleapp.protocol.ScooterProtocol {
        val detected = _detectedProtocol.value
        return if (detected == com.m365bleapp.protocol.ScooterProtocol.UNKNOWN) {
            // No probe result yet: keep the previous behaviour of assuming the
            // Xiaomi path rather than guessing plaintext, because a wrong
            // plaintext guess would also skip the handshake a Xiaomi scooter needs.
            com.m365bleapp.protocol.ScooterProtocol.XIAOMI_MI
        } else {
            detected
        }
    }

    private fun adoptDiscoveredProfile() {
        val profiles = bleManager.discoveredProfiles.value
        val chosen = profiles.firstOrNull()

        if (chosen == null) {
            Log.w(
                "ScooterRepo",
                "No known GATT layout discovered; keeping Nordic UART defaults. " +
                    "If telemetry never arrives, this scooter's layout is unsupported."
            )
            _activeProfile.value = null
            return
        }

        uartService = chosen.service
        uartTx = chosen.write
        uartRx = chosen.notify
        _activeProfile.value = chosen.kind

        Log.i(
            "ScooterRepo",
            "Using ${chosen.kind.displayName}: service=${chosen.service} " +
                "write=${chosen.write} notify=${chosen.notify} " +
                "(layouts available: ${profiles.joinToString { it.kind.name }})"
        )
    }
    
    // Security status (P3: Root detection warning)
    private val _securityStatus = MutableStateFlow<com.m365bleapp.utils.SecurityChecker.SecurityStatus?>(null)
    val securityStatus = _securityStatus.asStateFlow()

    /**
     * Initialize repository with background native library loading.
     * This prevents blocking the UI thread during app startup.
     */
    fun init() {
        // Load native library and initialize in background to prevent UI blocking
        scope.launch(Dispatchers.IO) {
            try {
                val startTime = System.currentTimeMillis()
                Log.d("ScooterRepo", "Initializing native library on background thread...")
                
                // Load native library asynchronously
                if (M365Native.loadLibraryAsync()) {
                    native.initSafe()  // Call instance init after library is loaded
                    val elapsed = System.currentTimeMillis() - startTime
                    Log.i("ScooterRepo", "Native library initialized in ${elapsed}ms")
                } else {
                    Log.e("ScooterRepo", "Failed to load native library: ${M365Native.getLoadError()?.message}")
                }
            } catch (e: Exception) {
                Log.e("ScooterRepo", "Native init failed: ${e.message}", e)
            }
        }
        
        // P3: Check device security status on init (non-blocking warning)
        scope.launch(Dispatchers.IO) {
            val status = com.m365bleapp.utils.SecurityChecker.checkSecurity()
            _securityStatus.value = status
            if (status.hasWarnings) {
                Log.w("ScooterRepo", "Security check: ${status.getWarningMessage()}")
            }
        }

        // Record every telemetry reading into the durable snapshot.
        //
        // Done by observing the flow rather than by writing at each of the four
        // places that assign `_motorInfo` — those assignments are easy to add
        // to and miss, and a missed one silently means a stale offline page.
        // One collector here cannot be bypassed.
        scope.launch(Dispatchers.IO) {
            _motorInfo.collect { info ->
                if (info != null) {
                    snapshotStore.updateTelemetry(
                        mac = activeGatt?.device?.address ?: lastConnectedMac,
                        speedKmh = info.speed,
                        batteryPercent = info.battery,
                        temperatureC = info.temp,
                        totalMileageKm = info.mileage,
                        averageSpeedKmh = info.avgSpeed,
                        remainingKm = info.remainingKm,
                        tripMeters = info.tripMeters,
                        tripSeconds = info.tripSeconds
                    )
                }
            }
        }
    }

    fun isRegistered(mac: String): Boolean {
        return sharedPreferences.contains(mac.uppercase() + "_token")
    }

    fun scan(): Flow<android.bluetooth.le.ScanResult> {
        return bleManager.scan()
            .onStart { _isScanning.value = true }
            .onCompletion { _isScanning.value = false }
    }

    // ------------------------------------------------------------------ demo

    /**
     * Starts the hardware-free demo ride.
     *
     * ## Why this exists
     *
     * A phone's BLE stack cannot impersonate a scooter peripheral, so with no
     * scooter present there is no way to put a value on screen and therefore no
     * way to exercise the display path on a real device. This feeds a synthetic
     * [MotorInfo] stream into [_motorInfo] — the same flow the real parser writes
     * to — so one demo run covers the phone UI, the BLE gateway and the glasses
     * HUD.
     *
     * ## What it does NOT prove
     *
     * It bypasses [com.m365bleapp.protocol.FrameCodec], the crypto session and
     * every register parser. A clean demo run says nothing about whether real
     * scooter frames decode correctly. Telemetry produced here is marked
     * [com.m365bleapp.protocol.DemoRideSource.DEMO_MARKER] so it is never mistaken
     * for live data.
     *
     * Safe to call repeatedly: an in-flight demo is replaced rather than stacked.
     * [connect] stops the demo, so demo data can never reach a real session.
     */
    fun startDemo(
        seed: Int = com.m365bleapp.protocol.DemoRideSource.DEFAULT_SEED,
        tickMs: Long = DEMO_TICK_MS,
    ) {
        stopDemo()
        _connectionState.value = ConnectionState.Ready
        val source = com.m365bleapp.protocol.DemoRideSource(seed = seed)
        demoJob = scope.launch(Dispatchers.IO) {
            Log.i("ScooterRepo", "${com.m365bleapp.protocol.DemoRideSource.DEMO_MARKER}: ride started (seed=$seed)")
            var last = System.currentTimeMillis()
            try {
                while (isActive) {
                    val now = System.currentTimeMillis()
                    val elapsed = now - last
                    last = now
                    _motorInfo.value = source.step(elapsed)
                    delay(tickMs)
                }
            } finally {
                // Reached on cancellation as well as normal completion, so a
                // stopped demo can never leave a stale sample on screen.
                Log.i("ScooterRepo", "${com.m365bleapp.protocol.DemoRideSource.DEMO_MARKER}: ride stopped")
            }
        }
    }

    /**
     * Stops the demo ride and clears the synthetic sample.
     *
     * Idempotent. Does not touch a real connection's state beyond clearing the
     * sample, so calling it while disconnected is harmless.
     */
    fun stopDemo() {
        val wasRunning = demoJob?.isActive == true
        demoJob?.cancel()
        demoJob = null
        // Only clear the state if this call actually stopped a demo. Otherwise a
        // stray stopDemo() during a real session would flip a live connection to
        // Disconnected, which is exactly the kind of bug a demo mode must not
        // introduce.
        if (wasRunning && _connectionState.value is ConnectionState.Ready) {
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    fun connect(mac: String, register: Boolean = false) {
        // A real session must never inherit synthetic samples, so the demo is
        // stopped before anything else happens.
        stopDemo()
        // Normalize MAC address to uppercase to ensure consistent token lookup
        val normalizedMac = mac.uppercase()
        lastConnectedMac = normalizedMac

        // Record which scooter this session belongs to, before any telemetry is
        // logged. Resolved from the advertised name because that is all that is
        // available pre-connection; the confidence field records that this is a
        // hint, not a fact, so a capture sent for diagnosis is not mistaken for
        // ground truth.
        runCatching {
            val device = bleManager.getDevice(normalizedMac)
            @SuppressLint("MissingPermission")
            val advertisedName = device?.name
            // The snapshot needs the name too: the detail screen derives which
            // rows to render from the model that resolves from it.
            lastConnectedAdvertisedName = advertisedName
            snapshotStore.updateAdvertisedName(advertisedName)

            // A manual override beats the advertisement. The rider is looking at
            // the scooter; this code is matching a string. It is persisted so it
            // survives the restart that follows a language change or a crash.
            val manual = ModelOverrideStore.getInstance(context).override.value
            logger.setActiveModel(ScooterModelRegistry.resolve(advertisedName, manual))
        }.onFailure {
            Log.w("ScooterRepo", "Could not resolve scooter model: ${it.message}")
            logger.setActiveModel(null)
        }
        scope.launch(Dispatchers.IO) @androidx.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT) {
            _connectionState.value = ConnectionState.Connecting
            try {
                // Every BLE call below (getDevice, connect, requestMtu,
                // enableNotifications, requestPriority) throws SecurityException
                // on Android 12+ without this permission. The @RequiresPermission
                // annotation is compile-time only, so check it for real and fail
                // with a clear message instead of an opaque crash.
                if (ActivityCompat.checkSelfPermission(
                        context,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    throw SecurityException(getString(R.string.bluetooth_permission_required))
                }

                val device = bleManager.getDevice(normalizedMac)
                
                // Clear old data from channels
                while(controlChannel.tryReceive().isSuccess) {}
                while(uartRxChannel.tryReceive().isSuccess) {}
                
                // Set up disconnection callback to detect scooter power-off
                bleManager.setOnDisconnectCallback {
                    Log.w("ScooterRepo", "BLE disconnection detected - scooter may have powered off")
                    handleUnexpectedDisconnection()
                }

                val gatt = bleManager.connect(device) { uuid, data ->
                    Log.d("ScooterRepo", "Rx: $uuid -> ${data.toHex()}")
                    
                    // Log BLE receive to CSV
                    val charName = when (uuid) {
                        uartRx -> "UART_RX"
                        BleManager.AUTH_AVDTP -> "AUTH_AVDTP"
                        BleManager.AUTH_UPNP -> "AUTH_UPNP"
                        else -> uuid.toString().takeLast(8)
                    }
                    logger.logBle("RX", "NOTIFY", "BLE", charName, data, "")
                    
                    // trySend fails silently when the bounded channel is full.
                    // Dropping an AUTH frame causes a spurious handshake
                    // timeout, and dropping telemetry loses a sample, so at
                    // least make the loss visible instead of invisible.
                    // Route by "is this the data plane?" rather than by an
                    // exact UUID. The data-plane UUID depends on the discovered
                    // layout, which is only known after service discovery — and
                    // the auth characteristics are the only other thing this app
                    // subscribes to, so treating everything else as telemetry is
                    // both correct and immune to a late profile switch.
                    val isDataPlane = uuid != BleManager.AUTH_UPNP &&
                        uuid != BleManager.AUTH_AVDTP
                    val delivered = if (isDataPlane) {
                        uartRxChannel.trySend(data).isSuccess
                    } else {
                        controlChannel.trySend(data).isSuccess
                    }
                    if (!delivered) {
                        Log.w("ScooterRepo", "Dropped ${data.size}-byte notification from $charName: channel full")
                    }
                } 
                if (gatt == null) throw Exception(getString(R.string.error_gatt_failed))
                activeGatt = gatt

                // Service discovery has completed by the time connect() returns,
                // so the layout is known here. Everything below uses the
                // resolved data plane instead of assuming Nordic UART.
                adoptDiscoveredProfile()
                
                // Request high priority for faster handshake
                Log.d("ScooterRepo", "Requesting High Connection Priority")
                bleManager.requestPriority(gatt, BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                
                // Request MTU for larger packets (Optional but helps)
                // Permission is already verified at the top of connect(); this
                // re-check is belt-and-braces for the lint annotation.
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

                // ---- Plaintext branch (stage C1) ------------------------------
                // A plaintext scooter does not run the Xiaomi auth handshake at
                // all: no `fe95` login, no session key, no encrypted UART. Sending
                // it auth traffic would at best be ignored and at worst put the
                // link into a state the plaintext loop cannot read from, so it is
                // branched away before any of that is attempted.
                if (detectedProtocolOrPlaintext() ==
                    com.m365bleapp.protocol.ScooterProtocol.NINEBOT_PLAIN
                ) {
                    val session = PlaintextRegisterSession(
                        // NINEBOT_PLAIN is the `5A A5` generation, which is P2 in
                        // FrameCodec's naming (P1 is the older `55 AA`).
                        protocol = com.m365bleapp.protocol.FrameCodec.Protocol.P2,
                        write = PlaintextRegisterSession.Write { frame ->
                            writeChar(uartService, uartTx, frame, waitForResponse = false)
                        },
                    )
                    plaintextSession = session
                    rememberProtocol(com.m365bleapp.protocol.ScooterProtocol.NINEBOT_PLAIN)

                    Log.d("ScooterRepo", "Enabling UART RX for plaintext telemetry")
                    bleManager.enableNotifications(gatt, uartService, uartRx) { }

                    _connectionState.value = ConnectionState.Ready
                    startPlaintextTelemetryLoop(session, gatt)
                    return@launch
                }

                // ---- NinebotCrypto branch (stage C2) --------------------------
                // A NinebotCrypto scooter runs the `5B/5C/5D` pairing exchange
                // instead of the Xiaomi auth handshake. It is a different protocol
                // on the same GATT layout, so it branches here rather than sharing
                // the Xiaomi registration/login path.
                if (detectedProtocolOrPlaintext() ==
                    com.m365bleapp.protocol.ScooterProtocol.NINEBOT_CRYPTO
                ) {
                    Log.i("ScooterRepo", "NinebotCrypto dialect: starting 5B/5C/5D pairing")
                    val name = runCatching {
                        activeGatt?.device?.name?.toByteArray(Charsets.ISO_8859_1)
                    }.getOrNull()
                    if (name == null || name.isEmpty()) {
                        // The session key is derived from the advertised name, so
                        // without it no frame can ever be decrypted.
                        _connectionState.value =
                            ConnectionState.Error("NinebotCrypto needs the advertised name")
                        return@launch
                    }

                    _connectionState.value =
                        ConnectionState.Handshaking(getString(R.string.connecting))
                    bleManager.enableNotifications(gatt, uartService, uartRx) { }
                    val paired = runNinebotHandshake(gatt, name)
                    if (!paired) {
                        return@launch
                    }
                    _connectionState.value = ConnectionState.Ready
                    // The handshake installs the session cipher; the telemetry loop
                    // still needs the fallback path until an encrypted plaintext loop
                    // exists, which is the next piece of work.
                    Log.i("ScooterRepo", "NinebotCrypto: paired; encrypted telemetry pending")
                    startPlaintextTelemetryLoop(
                        PlaintextRegisterSession(
                            protocol = com.m365bleapp.protocol.FrameCodec.Protocol.P2,
                            write = PlaintextRegisterSession.Write { frame ->
                                writeChar(uartService, uartTx, frame, waitForResponse = false)
                            },
                        ),
                        gatt,
                    )
                    return@launch
                }

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
                    val tokenStr = sharedPreferences.getString(normalizedMac + "_token", null)
                        ?: throw Exception(getString(R.string.error_token_missing))
                    
                    // Give scooter a moment to persist the new token and reset auth state
                    delay(1000)
                    
                    performLogin(tokenStr.hexToBytes())
                } else {
                    val tokenStr = sharedPreferences.getString(normalizedMac + "_token", null)
                    Log.d("ScooterRepo", "Token retrieved: ${tokenStr != null}")
                    if (tokenStr == null) throw Exception(getString(R.string.error_no_token))
                    performLogin(tokenStr.hexToBytes())
                }
                
                Log.d("ScooterRepo", "Enabling UART RX...")
                val uartOk = bleManager.enableNotifications(gatt, uartService, uartRx) { }
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
                // The handshake failed, so whatever dialect was cached for this
                // scooter is either wrong or no longer true (a reflash changes it
                // while the MAC stays the same). Drop it so the next attempt
                // re-probes instead of repeating a known-bad guess.
                forgetProtocol(e.message ?: "handshake failed")
                // Clean up first: disconnect() sets state to Disconnected, so
                // setting Error before it meant the UI never saw the failure.
                disconnect()
                _connectionState.value = ConnectionState.Error(e.message ?: getString(R.string.state_unknown_error))
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
        val myPubKey = native.prepareHandshakeSafe() 
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
        
        val tokenAndDid = native.processHandshakeSafe(ctxPtr, fullRemoteKey, remoteInfo)
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
        
        // Save token with normalized MAC address (uppercase)
        val mac = activeGatt?.device?.address?.uppercase() ?: ""
        sharedPreferences.edit()
            .putString(mac + "_token", token.toHex())
            .apply()
    }

    private suspend fun performLogin(token: ByteArray) {
        Log.d("ScooterRepo", "=== performLogin START ===")
        Log.d("ScooterRepo", "Token (${token.size} bytes): ${token.toHex()}")
        
        // 1. Send Key
        // Write UPNP: CMD_LOGIN (24 00 00 00)
        Log.d("ScooterRepo", "Sending CMD_LOGIN to UPNP...")
        writeChar(AUTH_SERVICE, AUTH_UPNP, byteArrayOf(0x24, 0x00, 0x00, 0x00))
        
        // Write AVDTP: CMD_SEND_KEY (00 00 00 0B 01 00)
        Log.d("ScooterRepo", "Sending CMD_SEND_KEY to AVDTP...")
        writeChar(AUTH_SERVICE, AUTH_AVDTP, byteArrayOf(0x00, 0x00, 0x00, 0x0B, 0x01, 0x00))
        
        Log.d("ScooterRepo", "Waiting for RCV_RDY (00000101)... timeout=10s")
        waitForCmd("00000101", 10000) // RCV_RDY - increased timeout
        Log.d("ScooterRepo", "Got RCV_RDY!")
        delay(40)
        
        // SecureRandom, not java.util.Random: this nonce participates in the
        // ECDH/login exchange, and java.util.Random's output is predictable
        // from a previously observed sequence, which weakens BLE authentication.
        val randKey = ByteArray(16).apply { java.security.SecureRandom().nextBytes(this) }
        writeMiParcel(AUTH_SERVICE, AUTH_AVDTP, randKey)
        
        waitForCmd("00000100") // RCV_OK
        // delay(200) removed
        
        // 2. Read Remote Key (Parcel from AVDTP)
        val remoteKey = readMiParcelWithProtocol()
        // delay(200) removed
        
        // 3. Read Remote Info (Parcel from AVDTP)
        val remoteInfo = readMiParcelWithProtocol()
        
        // 4. Native Login
        val res = native.loginSafe(token, randKey, remoteKey, remoteInfo)
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

        // The Mi handshake completed, which is positive evidence for exactly one
        // dialect. Recorded rather than inferred: reaching this line means the
        // scooter understood `fe95` + ECDH + AES-CCM, and nothing else does.
        rememberProtocol(com.m365bleapp.protocol.ScooterProtocol.XIAOMI_MI)

        // Give scooter time to finalize login state before UART communication
        delay(500)
    }

    /**
     * The resolved model for the connected scooter.
     *
     * Single place where "what is this scooter?" is answered, so a screen cannot
     * get it wrong by forgetting the manual override or by inventing a name.
     * Deliberately shares [lastConnectedAdvertisedName] with the diagnostics
     * block, so what the UI shows and what a bug report contains cannot differ.
     */
    fun currentIdentification(): com.m365bleapp.protocol.Identification =
        com.m365bleapp.protocol.ScooterModelRegistry.resolve(
            advertisedName = lastConnectedAdvertisedName,
            override = com.m365bleapp.protocol.ModelOverrideStore.getInstance(context).override.value,
        )

    /**
     * What the connected scooter can report and be controlled for.
     *
     * The UI asks this rather than checking a model name, so adding a model does
     * not require editing every screen.
     */
    fun currentCapabilities(): com.m365bleapp.protocol.ModelCapabilities =
        currentIdentification().model.capabilities

    /**
     * A one-block summary of what the app believes about the current connection.
     *
     * Built for pasting into a bug report. Every line here has been the first
     * question in diagnosing a "connects but shows nothing" report:
     *
     *  - **which GATT layout** the device answered on, because three exist and
     *    they are not interchangeable;
     *  - **which dialect** a completed handshake proved, because a name or a
     *    service UUID cannot establish it;
     *  - **the MTU**, because a wrongly-sized write is discarded silently and
     *    looks exactly like a scooter that refuses commands;
     *  - **which model** was identified and how confidently, because a name-prefix
     *    guess must not be read as ground truth.
     *
     * Returns a plain string rather than structured data on purpose: its purpose
     * is to leave the app through a share sheet.
     */
    fun connectionDiagnostics(): String {
        val profiles = bleManager.discoveredProfiles.value
        val model = com.m365bleapp.protocol.ScooterModelRegistry.resolve(
            lastConnectedAdvertisedName,
            com.m365bleapp.protocol.ModelOverrideStore.getInstance(context).override.value,
        )

        return buildString {
            appendLine("== Scooter connection diagnostics ==")
            appendLine("App: ${com.m365bleapp.BuildConfig.VERSION_NAME} (${com.m365bleapp.BuildConfig.VERSION_CODE})")
            appendLine("Model: ${model.model.displayName} (${model.model.rustId})")
            appendLine("Model confidence: ${model.confidence.label}")
            appendLine("Model source: ${model.source}")
            appendLine("MAC: ${lastConnectedMac ?: "not connected"}")
            appendLine("Advertised name: ${lastConnectedAdvertisedName ?: "unknown"}")
            appendLine("GATT layout: ${_activeProfile.value?.displayName ?: "none recognised"}")
            appendLine(
                "GATT layouts available: " +
                    if (profiles.isEmpty()) "none"
                    else profiles.joinToString { it.kind.name }
            )
            appendLine("Protocol dialect: ${_detectedProtocol.value.label}")
            appendLine("Protocol cached: ${lastConnectedMac?.let { protocolProbe.hasCached(it) } ?: false}")
            appendLine("Negotiated MTU: ${bleManager.negotiatedMtu} (usable ${bleManager.usableChunkSize} bytes)")
            appendLine("Connection state: ${_connectionState.value}")
            appendLine(
                "Cached vehicle: serial=${snapshotStore.snapshot.value.serial ?: "-"} " +
                    "firmware=${snapshotStore.snapshot.value.firmware ?: "-"}"
            )
        }
    }

    /**
     * Caches a dialect that a completed handshake proved.
     *
     * Written through [com.m365bleapp.protocol.ProtocolProbe] so a later connect
     * can try the known dialect first instead of re-probing blindly.
     */
    private fun rememberProtocol(protocol: com.m365bleapp.protocol.ScooterProtocol) {
        lastConnectedMac?.let { protocolProbe.remember(it, protocol) }
        _detectedProtocol.value = protocol
        Log.i("ScooterRepo", "Protocol dialect detected: ${protocol.label} (${protocol.id})")
    }

    /**
     * Drops the cached dialect after a failed handshake.
     *
     * A scooter can be reflashed and change dialect while keeping its MAC, so a
     * stale entry must not be trusted forever: without this the app would retry
     * a dialect that can no longer work and never re-probe.
     */
    private fun forgetProtocol(reason: String) {
        lastConnectedMac?.let {
            if (protocolProbe.hasCached(it)) {
                Log.w("ScooterRepo", "Forgetting cached protocol for $it: $reason")
            }
            protocolProbe.forget(it)
        }
        _detectedProtocol.value = com.m365bleapp.protocol.ScooterProtocol.UNKNOWN
    }
    
    // ... startTelemetryLoop uses writeNbParcel (raw) which is correct for UART ...

    // ------------------------------------------------------------- plaintext
    // Stage C1: scooters that speak plain `5A A5` framing never run the crypto
    // handshake. Everything below is that path. It is deliberately separate from
    // the encrypted loop rather than woven into it: the two share no state except
    // `motorInfo`, and a single loop with crypto branches is how the wrong offset
    // got into `parseTelemetry` in the first place.

    /**
     * Registers polled on a plaintext connection.
     *
     * Each entry is the register byte and the number of bytes to ask for, which is
     * the frame's argument. Read lengths come from what Scootbatt requests for the
     * same registers.
     */
    private val plaintextRegisters: List<Pair<Int, Int>> = listOf(
        0xB5 to 2,   // speed
        0x1B to 2,   // error code
        0x3E to 2,   // frame temperature
        0x3B to 1,   // battery state of health
    )

    /**
     * Starts the plaintext telemetry loop.
     *
     * Polls one register per tick, matching Scootbatt's one-outstanding-request
     * discipline: a second request is only sent after the first has been answered
     * or has timed out.
     */
    private fun startPlaintextTelemetryLoop(session: PlaintextRegisterSession, gatt: android.bluetooth.BluetoothGatt) {
        scope.launch(Dispatchers.IO) {
            Log.i("ScooterRepo", "Plaintext telemetry loop starting (${session.protocol.label})")

            // Drain anything left over from the handshake attempt so the first
            // reply we read belongs to the first request we send.
            while (controlChannel.tryReceive().isSuccess) { /* discard */ }

            var index = 0
            var consecutiveFailures = 0

            while (currentCoroutineContext().isActive && activeGatt === gatt) {
                val (register, readLength) = plaintextRegisters[index % plaintextRegisters.size]
                index++

                try {
                    // The request is framed by PlaintextRegisterSession, which is
                    // what puts FrameCodec on a live path: it supplies the sync
                    // word, the length byte and the checksum.
                    val request = session.buildRead(
                        register = register.toByte(),
                        argument = readLength.toByte(),
                        destination = PlaintextRegisterSession.ADDRESS_ESC,
                    )
                    writeChar(uartService, uartTx, request, waitForResponse = false)

                    // Collect notifications until the frame is complete rather than
                    // assuming one notification carries the whole reply: a reply
                    // longer than the negotiated chunk size arrives in pieces.
                    val deadline = System.currentTimeMillis() + PLAINTEXT_REPLY_TIMEOUT_MS
                    var assembled: ByteArray? = null
                    while (System.currentTimeMillis() < deadline && assembled == null) {
                        val chunk = withTimeoutOrNull(PLAINTEXT_CHUNK_WAIT_MS) {
                            controlChannel.receive()
                        } ?: continue
                        val complete = session.isComplete(chunk)
                        if (complete == true) {
                            assembled = chunk
                        } else if (complete == false) {
                            // Keep the partial frame and try to extend it.
                            val more = withTimeoutOrNull(PLAINTEXT_CHUNK_WAIT_MS) {
                                controlChannel.receive()
                            }
                            if (more != null) {
                                val joined = session.reassemble(listOf(chunk, more))
                                if (session.isComplete(joined) == true) assembled = joined
                            }
                        }
                        // complete == null means the length byte is not here yet.
                    }

                    val frame = assembled
                    if (frame == null) {
                        consecutiveFailures++
                        Log.w(
                            "ScooterRepo",
                            "Plaintext: no complete reply for 0x${register.toString(16)} " +
                                "(failure $consecutiveFailures)"
                        )
                    } else {
                        val decoded = session.accept(frame)
                        if (session.isReplyFor(decoded, register.toByte())) {
                            applyPlaintextReply(decoded)
                            consecutiveFailures = 0
                        } else {
                            Log.w(
                                "ScooterRepo",
                                "Plaintext: reply for 0x${decoded.command.toString(16)} " +
                                    "while waiting for 0x${register.toString(16)}"
                            )
                        }
                    }
                } catch (e: com.m365bleapp.protocol.FrameCodec.FrameException) {
                    // A malformed frame is dropped, not guessed at. The next tick
                    // re-requests the same register, so nothing is lost but a tick.
                    consecutiveFailures++
                    Log.w("ScooterRepo", "Plaintext: dropped malformed frame: ${e.message}")
                } catch (e: Exception) {
                    consecutiveFailures++
                    Log.w("ScooterRepo", "Plaintext: request failed: ${e.message}")
                }

                if (consecutiveFailures >= PLAINTEXT_FAILURES_BEFORE_RECONNECT) {
                    Log.e(
                        "ScooterRepo",
                        "Plaintext: $consecutiveFailures consecutive failures; " +
                            "declaring the link dead"
                    )
                    // The encrypted path has no equivalent of this: Scootbatt never
                    // counts timeouts and relies on the GATT callback alone.
                    disconnect()
                    return@launch
                }

                delay(PLAINTEXT_POLL_INTERVAL_MS)
            }

            Log.i("ScooterRepo", "Plaintext telemetry loop ended")
        }
    }

    /**
     * Applies one decoded plaintext reply to the telemetry state.
     *
     * The register→field mapping itself lives in [PlaintextTelemetryMapper], where
     * it is unit-tested; this method only performs the side effects. Duplicating the
     * switch here is what made the original `parseTelemetry` offsets unverifiable.
     */
    private fun applyPlaintextReply(frame: com.m365bleapp.protocol.FrameCodec.Frame) {
        val update = com.m365bleapp.protocol.PlaintextTelemetryMapper.decode(
            register = frame.command.toInt() and 0xFF,
            payload = frame.payload,
            // Only the Xiaomi family uses the 0.001 speed scale on 0xB5, and this
            // loop only runs for plaintext scooters, so the default scale applies.
            xiaomi = false,
        )
        if (update is com.m365bleapp.protocol.PlaintextTelemetryMapper.Update.Ignored) {
            Log.d(
                "ScooterRepo",
                "Plaintext: nothing to apply from register 0x${(frame.command.toInt() and 0xFF).toString(16)}"
            )
            return
        }

        val updated = com.m365bleapp.protocol.PlaintextTelemetryMapper.apply(
            current = _motorInfo.value,
            update = update,
        ) ?: return

        _motorInfo.value = updated
        logger.log(updated)
    }

    private suspend fun startTelemetryLoop() {
        logger.startSession()
        Log.d("ScooterRepo", "Starting Telemetry Loop (sessionPtr=$sessionPtr)")
        
        // NOTE: The Rust ninebot-ble library ALWAYS uses counter=0 for every message!
        // See: mi_session.rs -> encrypt_uart(&self.keys.app, &cmd.as_bytes(), 0, None)
        // The scooter apparently doesn't track/require incrementing counters.
        val counter = 0L  // Always use counter=0
        var tick = 0
        var consecutiveFailures = 0
        
        // === Dynamic Polling: Track last speed for adaptive interval ===
        var lastSpeed = 0.0
        
        // === Tiered Query Strategy ===
        // Different data types have different update frequency requirements
        // Speed (0xB0) is queried most frequently for real-time HUD display
        var lastTripQueryTick = 0
        var lastRangeQueryTick = 0
        
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
                // 
                // === Tiered Query Strategy ===
                // - Query Motor Info (speed) most frequently for real-time HUD
                // - Query Trip Info less frequently (doesn't change as fast)
                // - Query Remaining KM even less frequently (changes slowly)
                
                val (attribute, payload) = when {
                    // Query trip info at defined interval
                    tick - lastTripQueryTick >= TRIP_QUERY_INTERVAL_TICKS -> {
                        lastTripQueryTick = tick
                        0x3A to byteArrayOf(0x04) // Trip info: 4 bytes
                    }
                    // Query remaining km at defined interval
                    tick - lastRangeQueryTick >= RANGE_QUERY_INTERVAL_TICKS -> {
                        lastRangeQueryTick = tick
                        0x25 to byteArrayOf(0x02) // Remaining km: 2 bytes
                    }
                    else -> 0xB0 to byteArrayOf(0x20) // Motor info (speed): 32 bytes - DEFAULT
                }
                
                val packet = buildPacket(
                    dest = 0x20.toByte(),    // D: master to scooter
                    rw = 0x01.toByte(),      // T: read
                    attr = attribute.toByte(),
                    payload = payload
                )
                
                Log.d("ScooterRepo", "Loop: Query 0x${attribute.toString(16)}: ${packet.toHex()}")
                
                val encrypted = native.encryptSafe(sessionPtr, packet, counter)
                Log.d("ScooterRepo", "Encrypted (${encrypted.size} bytes): ${encrypted.toHex()}")
                
                // Write Encrypted to UART TX
                writeUartEncrypted(encrypted)
                
                val frame = readEncryptedFrame()
                if (frame.isNotEmpty()) {
                    Log.d("ScooterRepo", "Rx Encrypted (${frame.size} bytes): ${frame.toHex()}")
                    val decrypted = native.decryptSafe(sessionPtr, frame)
                    if (decrypted.isNotEmpty()) {
                         Log.d("ScooterRepo", "Rx Decrypted: ${decrypted.toHex()}")
                         parseTelemetry(decrypted)
                         consecutiveFailures = 0 // Reset on success
                         // Update last known speed for dynamic polling
                         _motorInfo.value?.let { lastSpeed = it.speed }
                    } else {
                         Log.w("ScooterRepo", "Decryption failed")
                         consecutiveFailures++
                    }
                } else {
                    Log.w("ScooterRepo", "No response for attribute 0x${attribute.toString(16)} (failures: $consecutiveFailures)")
                    consecutiveFailures++
                }
                
                // === Tiered Retry Strategy ===
                // Handle failures with progressive backoff
                val retryStrategy = RetryStrategy.fromFailureCount(consecutiveFailures)
                when (retryStrategy) {
                    is RetryStrategy.Reconnect -> {
                        // Previously this only set Error and broke out, leaving
                        // the GATT link open and the native session allocated,
                        // so the next connect() leaked both.
                        Log.e("ScooterRepo", "CONNECTION HEALTH: Too many failures ($consecutiveFailures), tearing down connection")
                        releaseConnection()
                        _connectionState.value = ConnectionState.Error(getString(R.string.connection_lost))
                        break
                    }
                    is RetryStrategy.LongDelay -> {
                        Log.w("ScooterRepo", "CONNECTION HEALTH: Multiple failures ($consecutiveFailures), waiting ${retryStrategy.delayMs}ms before retry")
                        delay(retryStrategy.delayMs)
                    }
                    is RetryStrategy.ShortDelay -> {
                        Log.w("ScooterRepo", "CONNECTION HEALTH: Some failures ($consecutiveFailures), waiting ${retryStrategy.delayMs}ms before retry")
                        delay(retryStrategy.delayMs)
                    }
                    is RetryStrategy.Immediate -> {
                        // Continue with normal polling interval
                    }
                }
                
                tick++
            } catch(e: Exception) {
                Log.e("ScooterRepo", "Loop error: ${e.message}", e)
                consecutiveFailures++
                
                // Check if the error indicates a disconnection
                if (e.message?.contains("disconnect", ignoreCase = true) == true ||
                    e.message?.contains("closed", ignoreCase = true) == true) {
                    Log.e("ScooterRepo", "CONNECTION HEALTH: Connection error detected in telemetry loop")
                    _connectionState.value = ConnectionState.Error(getString(R.string.connection_lost))
                    break
                }
                
                // Apply retry strategy even for exceptions
                val retryStrategy = RetryStrategy.fromFailureCount(consecutiveFailures)
                if (retryStrategy is RetryStrategy.LongDelay || retryStrategy is RetryStrategy.ShortDelay) {
                    val delayMs = when (retryStrategy) {
                        is RetryStrategy.LongDelay -> retryStrategy.delayMs
                        is RetryStrategy.ShortDelay -> retryStrategy.delayMs
                        else -> 0L
                    }
                    delay(delayMs)
                }
            }
            
            // === Dynamic Polling Interval ===
            // Shorter interval while moving for real-time HUD, longer when idle to save power
            val pollInterval = if (lastSpeed > SPEED_THRESHOLD_KMH) {
                POLL_INTERVAL_MOVING_MS
            } else {
                POLL_INTERVAL_IDLE_MS
            }
            delay(pollInterval)
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
        // Chunk by the MTU the scooter actually granted, not a guess. `write()`
        // asks for MTU 512, but a peripheral may grant as little as the 23-byte
        // minimum, and a write larger than ATT_MTU - 3 is dropped by the peer
        // with no error at all — indistinguishable from the command being
        // refused. bleManager.negotiatedMtu tracks what came back.
        val chunks = MtuFragmenter.fragment(data, bleManager.negotiatedMtu)

        for ((index, chunk) in chunks.withIndex()) {
            // Retry this chunk only. The previous code discarded the boolean
            // returned by write() entirely, so a rejected write was silently
            // treated as delivered and the caller then waited out its full
            // read timeout for a reply that could never come.
            var attemptsMade = 0

            while (true) {
                attemptsMade++
                val accepted = bleManager.write(gatt, uartService, uartTx, chunk, true)

                val outcome = if (accepted) {
                    WriteRetryPolicy.Outcome.ACCEPTED
                } else {
                    WriteRetryPolicy.Outcome.REJECTED
                }

                when (val decision = WriteRetryPolicy.decide(outcome, attemptsMade)) {
                    is WriteRetryPolicy.Decision.Retry -> {
                        Log.w(
                            "ScooterRepo",
                            "Chunk ${index + 1}/${chunks.size} rejected; retrying in " +
                                "${decision.delayMs}ms (attempt ${decision.attempt}/" +
                                "${WriteRetryPolicy.maxAttempts()})"
                        )
                        delay(decision.delayMs)
                    }

                    is WriteRetryPolicy.Decision.GiveUp -> {
                        if (!accepted) {
                            // Report rather than absorb. A persistent write
                            // failure used to look like an idle scooter,
                            // because the telemetry loop counted it as a
                            // missing reply and simply tried again.
                            Log.e(
                                "ScooterRepo",
                                "Chunk ${index + 1}/${chunks.size} could not be written: " +
                                    decision.reason
                            )
                        } else {
                            Log.d(
                                "ScooterRepo",
                                "Chunk ${index + 1}/${chunks.size} written " +
                                    "(${chunk.size} bytes, mtu ${bleManager.negotiatedMtu})"
                            )
                        }
                        break
                    }
                }
            }

            // Small pacing delay between chunks; the scooter's UART bridge needs
            // a gap to drain each ATT write before the next arrives.
            if (index < chunks.lastIndex) {
                delay(5)
            }
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
            Log.d("ScooterRepo", "Turning tail light on (0x7D = 0x0002)")
            val packet = buildPacket(
                dest = 0x20.toByte(),    // Master to Motor
                rw = 0x03.toByte(),      // Write
                attr = 0x7D.toByte(),    // TailLight address
                payload = byteArrayOf(0x02, 0x00)  // Value 0x0002 (little-endian: LSB first)
            )
            Log.d("ScooterRepo", "Tail light packet: ${packet.toHex()}")
            sendCommand(packet, "Tail Light On")
            
            // Wait for scooter to process the command
            delay(200)
            
            // Read back the state to confirm
            Log.d("ScooterRepo", "Reading tail light state to confirm...")
            readLightState()
            
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
            Log.d("ScooterRepo", "Turning tail light off (0x7D = 0x0000)")
            val packet = buildPacket(
                dest = 0x20.toByte(),    // Master to Motor
                rw = 0x03.toByte(),      // Write
                attr = 0x7D.toByte(),    // TailLight address
                payload = byteArrayOf(0x00, 0x00)  // Value 0x0000 = Off
            )
            Log.d("ScooterRepo", "Tail light packet: ${packet.toHex()}")
            sendCommand(packet, "Tail Light Off")
            
            // Wait for scooter to process the command
            delay(200)
            
            // Read back the state to confirm
            Log.d("ScooterRepo", "Reading tail light state to confirm...")
            readLightState()
            
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
        val encrypted = native.encryptSafe(sessionPtr, packet, counter)
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

    // NOTE (stage A3): a `tryLegacyParse` fallback used to live here. It scanned
    // the first five bytes for a value that looked like an attribute (0xB0/0xB5)
    // and then handed *everything after it* — including the encryptor's random
    // tail — to the register parser.
    //
    // It was removed rather than fixed, for three reasons:
    //   1. It had no call sites; it was dead code.
    //   2. Its heuristic is unsound: any payload byte that happens to equal 0xB0
    //      starts a parse anchored at the wrong offset, and the parser then reads
    //      plausible-looking garbage. That is a silent wrong value on a HUD, which
    //      is worse than no value.
    //   3. No supported format needs it. Plaintext scooters use the 55 AA / 5A A5
    //      envelope handled by FrameCodec, and encrypted ones always carry a size
    //      byte, so there is no "frame without a header" to recover from.
    //
    // Malformed frames are now dropped with a logged reason by parseTelemetry.

    // Helpers
    // Changed to default waitForResponse=false (Fire and Forget) + Pacing Delay
    private suspend fun writeChar(service: UUID, char: UUID, data: ByteArray, waitForResponse: Boolean = false) {
        Log.d("ScooterRepo", "Tx: $char -> ${data.toHex()}")
        
        // Log to CSV
        val serviceName = when (service) {
            uartService -> "UART"
            AUTH_SERVICE -> "AUTH"
            else -> service.toString().takeLast(8)
        }
        val charName = when (char) {
            uartTx -> "TX"
            uartRx -> "RX"
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
        // Same MTU-3 rule as writeUartEncrypted: this used a hard-coded 20 as
        // well, so a larger granted MTU was never taken advantage of.
        for (chunk in MtuFragmenter.fragment(data, bleManager.negotiatedMtu)) {
            writeChar(service, char, chunk)
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


    /**
     * Parses one decrypted reply and dispatches it to the register parser.
     *
     * ## Two things fixed here (stage A3)
     *
     * 1. **A missing size byte.** The previous comment claimed the decrypted data
     *    had "NO size byte at the start", but [buildPacket] shows the encrypted
     *    message begins with one (`[size][direction][rw][attr][payload]`). The old
     *    code therefore read the *size byte* as the direction and shifted every
     *    field by one, which sent `0xB0`-style registers down the wrong branch and
     *    mislabelled the rest.
     *
     * 2. **No real length validation.** The old guard was a bare
     *    `packet.size < 7`, so a reply announcing 32 bytes and carrying one still
     *    reached a parser and read past the end of its data. [ScooterReply.parse]
     *    now rejects that before any offset is computed. A dropped frame leaves a
     *    stale value on screen; a mis-parsed one shows a wrong value, and a wrong
     *    speed on a HUD is the worse failure.
     *
     * The four trailing bytes are the random tail `encrypt_uart` appends; they are
     * excluded by the size byte rather than by arithmetic on the frame length.
     */
    private fun parseTelemetry(packet: ByteArray) {
        if (packet.isEmpty()) {
            Log.w("ScooterRepo", "Empty telemetry packet")
            return
        }

        Log.d("ScooterRepo", "Parsing telemetry: ${packet.toHex()}")

        // `ScooterReply` expects the frame including its size byte, which is what
        // the crypto layer hands us. The previous revision stripped it first and
        // then mis-read every field; keeping it is what makes the validator and
        // the parser agree.
        when (val validation = com.m365bleapp.protocol.ScooterReply.parse(packet)) {
            is com.m365bleapp.protocol.ScooterReplyValidation.Rejected -> {
                Log.w("ScooterRepo", "Dropped malformed reply: ${validation.reason}")
                return
            }

            is com.m365bleapp.protocol.ScooterReplyValidation.Valid -> {
                val reply = validation.reply
                val data = reply.data
                Log.d(
                    "ScooterRepo",
                    "Response: Dir=0x${reply.direction.toString(16)}, " +
                        "Type=0x${reply.type.toString(16)}, " +
                        "Attr=0x${reply.attribute.toString(16)}, " +
                        "DataLen=${data.size}, Data=${data.toHex()}"
                )

                when (reply.attribute) {
                    0xB0 -> parseMotorInfoFromData(data)
                    0x3A -> parseTripInfo(data)
                    0x25 -> parseRemainingKm(data)
                    0xB5 -> parseSpeedFromData(data)
                    0x7D -> parseTailLightState(data)
                    0x7C -> parseCruiseState(data)
                    else -> Log.d(
                        "ScooterRepo",
                        "Unknown attribute: 0x${reply.attribute.toString(16)}"
                    )
                }
            }
        }
    }
    
    /**
     * Parse 0x7D Tail Light state response
     * Data format: u16 LE - 0x0000=off, 0x0001=on brake, 0x0002=always on
     */
    private fun parseTailLightState(data: ByteArray) {
        Log.i("ScooterRepo", "=== TAIL LIGHT RESPONSE RECEIVED ===")
        Log.i("ScooterRepo", "Raw data (${data.size} bytes): ${data.toHex()}")
        
        if (data.size < 2) {
            Log.w("ScooterRepo", "Tail light data too short: ${data.size} bytes")
            return
        }
        val value = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
        val stateDesc = when (value) {
            0x0000 -> "OFF"
            0x0001 -> "ON (brake only)"
            0x0002 -> "ALWAYS ON"
            else -> "UNKNOWN ($value)"
        }
        val isOn = value > 0  // 0x0001 or 0x0002 means light is on
        Log.i("ScooterRepo", "Tail light state: 0x${value.toString(16)} = $stateDesc, isOn=$isOn")
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
        
        val info = MotorInfoParser.parse(data, _motorInfo.value) ?: return
        Log.d("ScooterRepo", "Parsed motor info: $info")
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

    /**
     * Releases the GATT link and the native crypto session.
     *
     * Idempotent and safe to call from any thread: the whole teardown runs
     * under [connectionLock], and each resource is cleared before it is
     * released so a concurrent caller cannot free the same session twice.
     */
    @SuppressLint("MissingPermission")
    private fun releaseConnection(closeGatt: Boolean = true) {
        val (gatt, ptr) = synchronized(connectionLock) {
            val g = activeGatt
            val p = sessionPtr
            activeGatt = null
            sessionPtr = 0
            g to p
        }

        if (closeGatt && gatt != null) {
            try {
                gatt.disconnect()
                // Give the stack a moment to complete the disconnect before
                // close(); closing immediately leaves the device cached and
                // undiscoverable on the next scan.
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

        if (ptr != 0L) {
            native.freeSessionSafe(ptr)
        }
    }

    /**
     * Handle unexpected BLE disconnection (e.g., scooter powered off, out of range).
     * This is called from BleManager's disconnect callback.
     */
    private fun handleUnexpectedDisconnection() {
        scope.launch(Dispatchers.Main) {
            Log.e("ScooterRepo", "CONNECTION HEALTH: Unexpected disconnection detected!")

            // Only handle if we were in Ready state (connected and authenticated)
            if (_connectionState.value == ConnectionState.Ready ||
                _connectionState.value is ConnectionState.Handshaking) {

                // The stack already closed the GATT client in
                // onConnectionStateChange, so only release the native session.
                releaseConnection(closeGatt = false)

                // Update state to trigger UI notification
                _connectionState.value = ConnectionState.Error(getString(R.string.connection_lost))
                
                // Clear motor info to show "--" on UI
                _motorInfo.value = null
                
                Log.i("ScooterRepo", "Cleaned up after unexpected disconnection")
            }
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun disconnect() {
        // Stopping the demo here means the UI's existing "disconnect" action also
        // ends a demo ride, so there is no separate control to discover.
        stopDemo()
        // Clear the disconnect callback first to avoid recursive calls
        bleManager.clearOnDisconnectCallback()
        
        logger.stopSession()
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        
        // Track if we had an actual connection
        val hadConnection = activeGatt != null
        
        // Important: releaseConnection() calls disconnect() BEFORE close() to
        // properly release the BLE connection and clear Android's connection
        // cache. Just calling close() leaves the device in a cached state,
        // preventing it from being discovered again on subsequent scans.
        releaseConnection()
        _connectionState.value = ConnectionState.Disconnected

        // Only log if we actually had a connection to disconnect
        if (hadConnection) {
            Log.i("ScooterRepo", "Disconnected and cleaned up BLE resources")
        } else {
            Log.d("ScooterRepo", "disconnect() called but no active connection")
        }
    }
    
    fun getLogs(): List<java.io.File> = logger.getLogFiles()
    
    // Utils
    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun ByteArray.sliceArray(range: IntRange): ByteArray = copyOfRange(range.first, range.last + 1)
    private fun Long.toByteArray(): ByteArray = ByteArray(8) // dummy
    private fun ByteArray.toLong(): Long = ByteBuffer.wrap(this).long
}
