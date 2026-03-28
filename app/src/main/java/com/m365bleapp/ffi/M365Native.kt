package com.m365bleapp.ffi

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
        @Synchronized
        fun loadLibrarySync(): Boolean {
            if (isLoaded.get()) return true
            if (loadError != null) return false
            
            if (isLoading.compareAndSet(false, true)) {
                try {
                    Log.d(TAG, "Loading native library on thread: ${Thread.currentThread().name}")
                    val startTime = System.currentTimeMillis()
                    System.loadLibrary("ninebot_ffi")
                    val elapsed = System.currentTimeMillis() - startTime
                    Log.i(TAG, "Native library loaded successfully in ${elapsed}ms")
                    isLoaded.set(true)
                    return true
                } catch (e: UnsatisfiedLinkError) {
                    Log.e(TAG, "Failed to load native library: ${e.message}", e)
                    loadError = e
                    return false
                } finally {
                    isLoading.set(false)
                }
            }
            
            // Another thread is loading, wait for it
            while (isLoading.get()) {
                Thread.sleep(10)
            }
            return isLoaded.get()
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
        if (!isLoaded.get()) {
            if (!loadLibrarySync()) {
                throw IllegalStateException("Native library not loaded: ${loadError?.message}")
            }
        }
    }

    // ========== Native External Functions ==========
    // These are the actual JNI bindings that match Rust FFI exports
    // Function names MUST match exactly: Java_com_m365bleapp_ffi_M365Native_<name>
    
    /** Initialize library (logger etc) */
    external fun init()

    /** Returns [8 bytes Ptr][Public Key Bytes...] */
    external fun prepareHandshake(): ByteArray

    /** 
     * ctxPtr is the first 8 bytes returned from prepareHandshake
     * Returns [12 bytes Token][DID Ciphertext...] or empty if failed 
     */
    external fun processHandshake(ctxPtr: Long, remoteKey: ByteArray, remoteInfo: ByteArray): ByteArray

    /** Returns [8 bytes Ptr][Login Data...] or empty */
    external fun login(token: ByteArray, randKey: ByteArray, remoteKey: ByteArray, remoteInfo: ByteArray): ByteArray

    /** Encrypt payload using session pointer */
    external fun encrypt(sessionPtr: Long, payload: ByteArray, counter: Long): ByteArray

    /** Decrypt payload using session pointer */
    external fun decrypt(sessionPtr: Long, encrypted: ByteArray): ByteArray

    /** Free the session pointer */
    external fun freeSession(sessionPtr: Long)
    
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
        ensureLoaded()
        freeSession(sessionPtr)
    }
}
