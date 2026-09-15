package com.m365bleapp.pairing

import java.io.Closeable
import java.security.SecureRandom

interface PairingNative {
    fun begin(name: String, key: ByteArray): Long
    fun next(handle: Long): ByteArray
    fun receive(handle: Long, bytes: ByteArray): Int
    fun serial(handle: Long, serial: String): Boolean
    fun free(handle: Long)
}
interface PairingCredentialStore {
    fun load(address: String): PairingCredentials?
    fun save(address: String, credentials: PairingCredentials)
}
class PairingCredentials(val serial: String, key: ByteArray) {
    private val key = key.copyOf()
    init { require(validSerial(serial) && key.size == 16 && key.any { it != 0.toByte() }) }
    fun keyCopy(): ByteArray = key.copyOf()
    override fun toString(): String = "PairingCredentials(redacted)"
}
fun validSerial(serial: String): Boolean = serial.length == 14 && serial.all {
    it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '/'
}

enum class PairingStage { AwaitingChallenge, SerialRequired, AwaitingButton, AwaitingConfirmation, Paired, Failed, Closed }

/** BLE 收發由呼叫端負責；此類別只負責配對狀態與成功後的持久化。 */
class PairingCoordinator(
    private val native: PairingNative,
    private val store: PairingCredentialStore,
    private val address: String,
    name: String,
) : Closeable {
    private val saved = store.load(address)
    private val key = saved?.keyCopy() ?: ByteArray(16).also { SecureRandom().nextBytes(it) }
    private var serial: String? = null
    private var handle = native.begin(name, key)
    @Volatile var stage: PairingStage = if (handle == 0L) PairingStage.Failed else PairingStage.AwaitingChallenge
        private set

    @Synchronized fun nextFrame(): ByteArray {
        check(stage in listOf(PairingStage.AwaitingChallenge, PairingStage.AwaitingButton, PairingStage.AwaitingConfirmation))
        val bytes = native.next(handle)
        if (bytes.isEmpty()) { stage = PairingStage.Failed; error("配對失敗，請重新連線") }
        return bytes
    }

    @Synchronized fun receive(bytes: ByteArray): PairingStage {
        check(stage in listOf(PairingStage.AwaitingChallenge, PairingStage.AwaitingButton, PairingStage.AwaitingConfirmation))
        stage = when (native.receive(handle, bytes)) {
            1 -> PairingStage.SerialRequired
            2 -> PairingStage.AwaitingButton
            3 -> PairingStage.AwaitingConfirmation
            4 -> PairingStage.Paired
            else -> PairingStage.Failed
        }
        if (stage == PairingStage.SerialRequired && saved != null) submitSerial(saved.serial)
        if (stage == PairingStage.Paired) {
            try { store.save(address, PairingCredentials(checkNotNull(serial), key)) }
            catch (e: Exception) { stage = PairingStage.Failed; throw e }
        }
        return stage
    }

    @Synchronized fun submitSerial(value: String): Boolean {
        if (stage != PairingStage.SerialRequired || !validSerial(value)) return false
        if (!native.serial(handle, value)) return false
        serial = value
        stage = PairingStage.AwaitingButton
        return true
    }

    @Synchronized fun transferSession(open: (Long) -> Long): Long {
        check(stage == PairingStage.Paired) { "尚未完成配對" }
        return try { open(handle).also { check(it != 0L) { "無法建立車輛會話" } } } finally { close() }
    }

    @Synchronized override fun close() {
        val old = handle; handle = 0
        try { if (old != 0L) native.free(old) }
        finally { key.fill(0); serial = null; stage = PairingStage.Closed }
    }
}
