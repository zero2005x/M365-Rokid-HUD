package com.m365bleapp.ffi

import android.os.Looper
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Native FFI wrapper for Ninebot BLE protocol.
 * 
 * PERFORMANCE OPTIMIZATION:
 * - Native library loading is deferred to background thread
 * - Lazy initialization prevents blocking UI thread during app startup
 * - Thread-safe loading with AtomicBoolean
 * 
 * Usage:
 * 1. Call M365Native.loadLibraryAsync() during app initialization (on IO thread)
 * 2. Create instance: val native = M365Native()
 * 3. Call instance methods: native.init(), native.prepareHandshake(), etc.
 */
class M365Native {
    private external fun decodeLegacyMotorInfo(modelId: Int, data: ByteArray): DoubleArray
    private external fun profileControl(modelId: Int, feature: Int, value: Int): ByteArray
    fun decodeLegacyMotorInfoSafe(modelId: Int, data: ByteArray): DoubleArray { ensureLoaded(); return decodeLegacyMotorInfo(modelId, data) }
    fun profileControlSafe(modelId: Int, feature: Int, value: Int): ByteArray { ensureLoaded(); return profileControl(modelId, feature, value) }

    private external fun openVehicle(pairingHandle: Long): Long
    private external fun vehicleRequest(handle: Long, action: Int, feature: Int, value: Int): ByteArray
    private external fun vehicleReceive(handle: Long, bytes: ByteArray): ByteArray
    private external fun vehicleResolve(handle: Long, expected: Int, experimental: Boolean): ByteArray
    private external fun resolveIdentification(serial: ByteArray, expected: Int, experimental: Boolean): ByteArray
    private external fun freeVehicle(handle: Long)
    fun openVehicleSafe(pairingHandle: Long): Long { ensureLoaded(); return openVehicle(pairingHandle) }
    fun vehicleRequestSafe(handle: Long, action: Int, feature: Int = 0, value: Int = 0): ByteArray { ensureLoaded(); return vehicleRequest(handle, action, feature, value) }
    fun vehicleReceiveSafe(handle: Long, bytes: ByteArray): ByteArray { ensureLoaded(); return vehicleReceive(handle, bytes) }
    fun vehicleResolveSafe(handle: Long, expected: Int, experimental: Boolean): ByteArray { ensureLoaded(); return vehicleResolve(handle, expected, experimental) }
    fun resolveIdentificationSafe(serial: ByteArray, expected: Int, experimental: Boolean): ByteArray { ensureLoaded(); return resolveIdentification(serial, expected, experimental) }
    fun freeVehicleSafe(handle: Long) { if (handle != 0L) { ensureLoaded(); freeVehicle(handle) } }

    companion object {
        private const val TAG = "M365Native"
        private val isLoaded = AtomicBoolean(false)
        private val isLoading = AtomicBoolean(false)
        
        @Volatile
        private var loadError: Throwable? = null
        
        /**
         * Load native library synchronously (for background thread use).
         * Thread-safe and idempotent.
         */
        /**
         * The method is @Synchronized, so callers already serialise on the
         * monitor: the previous isLoading CAS always succeeded and the
         * busy-wait below it was unreachable. Rely on the monitor plus the
         * isLoaded/loadError state instead.
         */
        @Synchronized
        fun loadLibrarySync(): Boolean {
            if (isLoaded.get()) return true
            if (loadError != null) return false

            return try {
                Log.d(TAG, "Loading native library on thread: ${Thread.currentThread().name}")
                val startTime = System.currentTimeMillis()
                System.loadLibrary("ninebot_ffi")
                val elapsed = System.currentTimeMillis() - startTime
                Log.i(TAG, "Native library loaded successfully in ${elapsed}ms")
                isLoaded.set(true)
                true
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load native library: ${e.message}", e)
                loadError = e
                false
            } catch (e: Throwable) {
                // System.loadLibrary can also throw SecurityException or
                // NullPointerException; without this the throwable escaped and
                // left loadError unset, so retries silently tried again.
                Log.e(TAG, "Unexpected error loading native library: ${e.message}", e)
                loadError = e
                false
            }
        }
        
        /**
         * Load native library asynchronously on IO dispatcher.
         * Safe to call from main thread.
         */
        suspend fun loadLibraryAsync(): Boolean = withContext(Dispatchers.IO) {
            loadLibrarySync()
        }
        
        /**
         * Check if library is loaded without blocking.
         */
        fun isLibraryLoaded(): Boolean = isLoaded.get()
        
        /**
         * Get the load error if any.
         */
        fun getLoadError(): Throwable? = loadError
    }
    
    /**
     * Ensure native library is loaded before calling native methods.
     * This will block if library is still loading on another thread.
     * @throws IllegalStateException if library failed to load
     */
    private fun ensureLoaded() {
        if (isLoaded.get()) return

        // loadLibrarySync() blocks on a monitor and may run System.loadLibrary
        // itself. Doing either on the main thread risks visible jank or an ANR,
        // so require the caller to have pre-loaded via loadLibraryAsync().
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw IllegalStateException(
                "Native library is not loaded yet. Call M365Native.loadLibraryAsync() " +
                    "before using this API from the main thread."
            )
        }

        if (!loadLibrarySync()) {
            throw IllegalStateException("Native library not loaded: ${loadError?.message}")
        }
    }

    // ========== Native External Functions ==========
    // These are the actual JNI bindings that match Rust FFI exports.
    // Function names MUST match exactly: Java_com_m365bleapp_ffi_M365Native_<name>
    //
    // They are private on purpose: calling them before the library is loaded
    // throws an uncaught UnsatisfiedLinkError. Every caller must go through the
    // *Safe wrappers below, which funnel through ensureLoaded().

    /** Initialize library (logger etc) */
    private external fun init()

    /** 車款描述及正規化遙測；保留既有 JNI 方法簽章。 */
    private external fun beginPairing(name: String, appKey: ByteArray): Long
    private external fun pairingNext(handle: Long): ByteArray
    private external fun pairingReceive(handle: Long, frame: ByteArray): Int
    private external fun pairingSetSerial(handle: Long, serial: String): Boolean
    private external fun freePairing(handle: Long)

    fun beginPairingSafe(name: String, appKey: ByteArray): Long {
        ensureLoaded()
        return beginPairing(name, appKey)
    }
    fun pairingNextSafe(handle: Long): ByteArray {
        ensureLoaded()
        return pairingNext(handle)
    }
    fun pairingReceiveSafe(handle: Long, frame: ByteArray): Int {
        ensureLoaded()
        return pairingReceive(handle, frame)
    }
    fun pairingSetSerialSafe(handle: Long, serial: String): Boolean {
        ensureLoaded()
        return pairingSetSerial(handle, serial)
    }
    fun freePairingSafe(handle: Long) {
        if (handle == 0L) return
        ensureLoaded()
        freePairing(handle)
    }

    private external fun availableProfiles(): ByteArray
    private external fun decodeMotorInfo(modelId: Int, data: ByteArray): DoubleArray

    fun availableProfilesSafe(): List<ProfileDescriptor> {
        ensureLoaded()
        return ProfileDescriptor.decode(availableProfiles())
    }

    fun decodeMotorInfoSafe(modelId: Int, data: ByteArray): DoubleArray {
        ensureLoaded()
        return decodeMotorInfo(modelId, data)
    }


    /** Returns [8 bytes Handle][Public Key Bytes...] */
    private external fun prepareHandshake(): ByteArray

    /**
     * ctxPtr is the first 8 bytes returned from prepareHandshake.
     * Single-use: the native side consumes the handle, so a retry needs a fresh
     * prepareHandshake().
     * Returns [12 bytes Token][DID Ciphertext...] or empty if failed
     */
    private external fun processHandshake(ctxPtr: Long, remoteKey: ByteArray, remoteInfo: ByteArray): ByteArray

    /** Returns [8 bytes Handle][Login Data...] or empty */
    private external fun login(token: ByteArray, randKey: ByteArray, remoteKey: ByteArray, remoteInfo: ByteArray): ByteArray

    /** Encrypt payload using the session handle */
    private external fun encrypt(sessionPtr: Long, payload: ByteArray, counter: Long): ByteArray

    /** Decrypt payload using the session handle */
    private external fun decrypt(sessionPtr: Long, encrypted: ByteArray): ByteArray

    /** Release the session handle. Idempotent on the native side. */
    private external fun freeSession(sessionPtr: Long)

    // ========== Safe Wrapper Methods (optional, with ensureLoaded check) ==========
    
    /** Initialize library with automatic library loading */
    fun initSafe() {
        ensureLoaded()
        init()
    }

    /** prepareHandshake with automatic library loading */
    fun prepareHandshakeSafe(): ByteArray {
        ensureLoaded()
        return prepareHandshake()
    }

    /** processHandshake with automatic library loading */
    fun processHandshakeSafe(ctxPtr: Long, remoteKey: ByteArray, remoteInfo: ByteArray): ByteArray {
        ensureLoaded()
        return processHandshake(ctxPtr, remoteKey, remoteInfo)
    }

    /** login with automatic library loading */
    fun loginSafe(token: ByteArray, randKey: ByteArray, remoteKey: ByteArray, remoteInfo: ByteArray): ByteArray {
        ensureLoaded()
        return login(token, randKey, remoteKey, remoteInfo)
    }

    /** encrypt with automatic library loading */
    fun encryptSafe(sessionPtr: Long, payload: ByteArray, counter: Long): ByteArray {
        ensureLoaded()
        return encrypt(sessionPtr, payload, counter)
    }

    /** decrypt with automatic library loading */
    fun decryptSafe(sessionPtr: Long, encrypted: ByteArray): ByteArray {
        ensureLoaded()
        return decrypt(sessionPtr, encrypted)
    }

    /** freeSession with automatic library loading */
    fun freeSessionSafe(sessionPtr: Long) {
        if (sessionPtr == 0L) return
        ensureLoaded()
        freeSession(sessionPtr)
    }
}
