package com.m365bleapp.pairing

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** 重組通知分片；限制緩衝區大小，保留同一通知內的後續封包。 */
class NinebotFrameBuffer(private val encrypted: Boolean = true) {
    private var pending = byteArrayOf()
    fun append(bytes: ByteArray) {
        require(pending.size + bytes.size <= 4096) { "藍牙接收資料超過上限" }
        pending += bytes
    }
    fun next(): ByteArray? {
        while (pending.size >= 2 && !(pending[0] == 0x5a.toByte() && pending[1] == 0xa5.toByte())) {
            require(!(pending[0] == 0x5a.toByte() && pending[1] == 0xab.toByte())) { "此韌體使用尚未支援的 5AAB 協定" }
            pending = pending.copyOfRange(1, pending.size)
        }
        if (pending.size < 3) return null
        val size = (pending[2].toInt() and 0xff) + if (encrypted) 13 else 9
        require(size <= 141) { "藍牙封包長度超過上限" }
        if (pending.size < size) return null
        return pending.copyOfRange(0, size).also { pending = pending.copyOfRange(size, pending.size) }
    }
    fun clear() { pending.fill(0); pending = byteArrayOf() }
}

/** 呼叫端持有配對工作；取消、斷線與逾時均須關閉 coordinator。 */
class PairingTransport(
    private val coordinator: PairingCoordinator,
    private val send: suspend (ByteArray) -> Unit,
    private val receive: suspend () -> ByteArray,
    private val requestSerial: suspend () -> Unit,
    private val onStage: (PairingStage) -> Unit,
    private val responseTimeoutMs: Long = 3000,
    private val retryDelayMs: Long = 1000,
) {
    suspend fun pair() {
        val buffer = NinebotFrameBuffer()
        try {
            withTimeout(360_000) {
                while (coordinator.stage != PairingStage.Paired) {
                    onStage(coordinator.stage)
                    when (coordinator.stage) {
                        PairingStage.SerialRequired -> {
                            withTimeout(300_000) { requestSerial() }
                            check(coordinator.stage == PairingStage.AwaitingButton) { "序號尚未確認" }
                        }
                        PairingStage.AwaitingChallenge, PairingStage.AwaitingButton, PairingStage.AwaitingConfirmation -> {
                            exchangeFrame(buffer)
                        }
                        else -> error("配對已中止，請重新連線")
                    }
                }
                onStage(PairingStage.Paired)
            }
        } catch (failure: Throwable) {
            coordinator.close()
            throw failure
        } finally { buffer.clear() }
    }

    private suspend fun exchangeFrame(buffer: NinebotFrameBuffer) {
        val before = coordinator.stage
        send(coordinator.nextFrame())
        val frame = withTimeoutOrNull(responseTimeoutMs) { receiveFrame(buffer) }
        if (frame != null) {
            check(coordinator.receive(frame) != PairingStage.Failed) { "配對回覆驗證失敗，請重新連線" }
        } else if (before == PairingStage.AwaitingChallenge) {
            error("車輛未回覆配對挑戰，請確認韌體與藍牙連線")
        }
        if (coordinator.stage == before) delay(retryDelayMs)
    }

    private suspend fun receiveFrame(buffer: NinebotFrameBuffer): ByteArray {
        var complete = buffer.next()
        while (complete == null) {
            buffer.append(receive())
            complete = buffer.next()
        }
        return complete
    }
}
