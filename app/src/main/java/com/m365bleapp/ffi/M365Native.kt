package com.m365bleapp.ffi

class M365Native {
    companion object {
        init {
            try {
                System.loadLibrary("ninebot_ffi")
            } catch (e: UnsatisfiedLinkError) {
                // In dev environment or if build failed, this might throw.
                // We'll swallow or log in real app, here we let it crash or handle upper layer.
                e.printStackTrace()
            }
        }
    }

    // Initialize library (logger etc)
    external fun init()

    // Returns [8 bytes Ptr][Public Key Bytes...]
    external fun prepareHandshake(): ByteArray

    // ctxPtr is the first 8 bytes returned from prepareHandshake
    // Returns [12 bytes Token][DID Ciphertext...] or empty if failed
    external fun processHandshake(ctxPtr: Long, remoteKey: ByteArray, remoteInfo: ByteArray): ByteArray

    // Returns [8 bytes Ptr][Login Data...] or empty
    external fun login(token: ByteArray, randKey: ByteArray, remoteKey: ByteArray, remoteInfo: ByteArray): ByteArray

    // Encrypt payload using session pointer
    external fun encrypt(sessionPtr: Long, payload: ByteArray, counter: Long): ByteArray

    // Decrypt payload using session pointer
    external fun decrypt(sessionPtr: Long, encrypted: ByteArray): ByteArray

    // Free the session pointer
    external fun freeSession(sessionPtr: Long)
}
