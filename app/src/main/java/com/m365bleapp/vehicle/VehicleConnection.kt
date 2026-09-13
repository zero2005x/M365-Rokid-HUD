package com.m365bleapp.vehicle

import com.m365bleapp.ffi.M365Native
import com.m365bleapp.ffi.ProfileDescriptor
import com.m365bleapp.pairing.NinebotFrameBuffer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.Closeable

fun modelName(id: Int): String = listOf("Xiaomi M365", "Xiaomi M365 Pro", "Xiaomi Pro 2", "Xiaomi 1S", "Ninebot Max G30", "Ninebot ESx").getOrNull(id) ?: "未知車款"

data class DetectionResult(val outcome: Int, val modelId: Int, val expectedId: Int, val capabilities: Int) {
    val accepted: Boolean get() = outcome == 1 || outcome == 3
    val message: String get() = when (outcome) {
        0 -> "已連線到尚無設定的車款，請回報車款與韌體版本；未啟用控制。"
        2 -> "已連線到 "+modelName(modelId)+"，與選擇的 "+modelName(expectedId)+" 不符，請選擇對應設定。"
        4 -> "此為未驗證車款，請先啟用「實驗性車款」。"
        3 -> "部分控制尚未定義，已停用；遙測可繼續使用。"
        else -> "車款辨識完成"
    }
    companion object {
        fun decode(bytes: ByteArray): DetectionResult {
            require(bytes.size == 5 && bytes[0] == 1.toByte()) { "車款辨識回覆格式不符" }
            val v = bytes.map { it.toInt() and 0xff }
            require(v[1] in 0..4 && v[4] in 0..7 && (v[2] in 0..5 || v[2] == 255) && (v[3] in 0..5 || v[3] == 255))
            require(v[1] == 0 || v[2] != 255)
            require(v[1] != 2 || v[3] != 255)
            return DetectionResult(v[1], v[2], v[3], v[4])
        }
    }
}

/** 一把互斥鎖維持請求／回覆次序；逾時後關閉，避免把舊回覆當成新資料。 */
class VehicleConnection(
    private val native: M365Native,
    @Volatile private var handle: Long,
    encrypted: Boolean,
    private val send: suspend (ByteArray) -> Unit,
    private val receive: suspend () -> ByteArray,
) : Closeable {
    private val mutex = Mutex()
    private val buffer = NinebotFrameBuffer(encrypted)
    @Volatile private var profile: ProfileDescriptor? = null
    init { require(handle != 0L) { "無法建立車輛會話" } }
    private suspend fun request(action: Int): ByteArray {
        val bytes = native.vehicleRequestSafe(handle, action)
        check(bytes.isNotEmpty()) { "車輛尚未辨識或查詢未完成" }
        try {
            send(bytes)
            return withTimeout(4000) {
                var frame = buffer.next()
                while (frame == null) { buffer.append(receive()); frame = buffer.next() }
                native.vehicleReceiveSafe(handle, frame).also { check(it.isNotEmpty()) { "車輛回覆驗證失敗" } }
            }
        } catch (failure: Throwable) { close(); throw failure }
    }
    suspend fun identify(expected: Int, experimental: Boolean): DetectionResult = mutex.withLock {
        request(0)
        val result = DetectionResult.decode(native.vehicleResolveSafe(handle, expected, experimental))
        if (result.accepted) {
            profile = native.availableProfilesSafe().single { it.modelId == result.modelId }
        }
        result
    }
    suspend fun telemetry(): DoubleArray = mutex.withLock {
        val p = checkNotNull(profile) { "尚未辨識車款" }
        native.decodeMotorInfoSafe(p.modelId, request(1)).also {
            check(it.size == 7 && it.all(Double::isFinite) && it[0] in 0.0..100.0) { "車輛遙測格式不符" }
        }
    }
    suspend fun control(feature: Int, value: Int) = mutex.withLock {
        check(profile != null) { "尚未辨識車款" }
        val bytes = native.vehicleRequestSafe(handle, 2, feature, value)
        check(bytes.isNotEmpty()) { "此車款尚未支援這項控制" }
        try { send(bytes) } catch (failure: Throwable) { close(); throw failure }
    }
    @Synchronized override fun close() {
        val old = handle; handle = 0
        if (old != 0L) native.freeVehicleSafe(old)
        profile = null
    }
}
