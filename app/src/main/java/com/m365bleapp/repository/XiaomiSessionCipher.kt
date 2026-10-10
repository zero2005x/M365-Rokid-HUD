package com.m365bleapp.repository

/**
 * One nonce sequence per authenticated login.
 *
 * The UART counter is part of the AES-CCM nonce, so reusing it under the same
 * session key breaks confidentiality. Every call consumes a counter, including a
 * failed encryption, and the 16-bit wire counter never wraps: exhaustion requires
 * a new login.
 */
internal class XiaomiSessionCipher(
    private val nativeEncrypt: (ByteArray, Long) -> ByteArray?,
    firstCounter: Long = 0,
) {
    private var nextCounter = firstCounter

    init {
        require(firstCounter in 0..MAX_COUNTER) { "Xiaomi UART counter out of range: $firstCounter" }
    }

    @Synchronized
    fun encrypt(bytes: ByteArray): ByteArray? {
        check(nextCounter <= MAX_COUNTER) { "Xiaomi UART counter exhausted; a new login is required" }
        val counter = nextCounter
        nextCounter = counter + 1
        return nativeEncrypt(bytes, counter)
    }

    private companion object {
        /** The counter travels as a 16-bit field in the UART frame. */
        const val MAX_COUNTER = 0xFFFFL
    }
}
