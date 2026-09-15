package com.m365bleapp.pairing

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PairingTransportTest {
    private fun frame(payload: Int = 0) = ByteArray(13 + payload).also {
        it[0] = 0x5a; it[1] = 0xa5.toByte(); it[2] = payload.toByte()
    }
    @Test fun reconstructsEverySplitAndKeepsFollowingFrame() {
        val first = frame(30); val second = frame()
        for (split in 0..first.size) {
            val buffer = NinebotFrameBuffer()
            buffer.append(byteArrayOf(0, 1, 2) + first.copyOfRange(0, split))
            if (split < first.size) assertNull(buffer.next())
            buffer.append(first.copyOfRange(split, first.size) + second)
            assertArrayEquals(first, buffer.next())
            assertArrayEquals(second, buffer.next())
            assertNull(buffer.next())
        }
    }
    @Test fun rejectsUnsupportedHeaderAndOversizedInput() {
        val buffer = NinebotFrameBuffer()
        buffer.append(byteArrayOf(0x5a, 0xab.toByte()))
        assertThrows(IllegalArgumentException::class.java) { buffer.next() }
        buffer.clear()
        assertThrows(IllegalArgumentException::class.java) { buffer.append(ByteArray(4097)) }
        buffer.append(byteArrayOf(0x5a, 0xa5.toByte(), 0xff.toByte()))
        assertThrows(IllegalArgumentException::class.java) { buffer.next() }
    }
    private class Native : PairingNative {
        var stage = 0; var freed = 0
        override fun begin(name: String, key: ByteArray) = 1L
        override fun next(handle: Long) = byteArrayOf(1)
        override fun receive(handle: Long, bytes: ByteArray): Int {
            stage = when (stage) { 0 -> 1; 2 -> 3; 3 -> 4; else -> -1 }; return stage
        }
        override fun serial(handle: Long, serial: String): Boolean { stage = 2; return true }
        override fun free(handle: Long) { freed++ }
    }
    private class Store : PairingCredentialStore {
        var writes = 0
        override fun load(address: String): PairingCredentials? = null
        override fun save(address: String, credentials: PairingCredentials) { writes++ }
    }
    @Test fun pairsThroughFragmentedTransportAndSerialPrompt() = runBlocking {
        val native = Native(); val store = Store()
        val coordinator = PairingCoordinator(native, store, "00:11:22:33:44:55", "NBSCOOTER")
        val incoming = Channel<ByteArray>(Channel.UNLIMITED)
        val states = mutableListOf<PairingStage>()
        PairingTransport(coordinator, send = {
            val response = frame()
            incoming.send(response.copyOfRange(0, 5)); incoming.send(response.copyOfRange(5, response.size))
        }, receive = { incoming.receive() }, requestSerial = {
            assertEquals(0, store.writes)
            assertTrue(coordinator.submitSerial("N4GSD123456789"))
        }, onStage = { states += it }).pair()
        assertEquals(PairingStage.Paired, coordinator.stage)
        assertTrue(states.contains(PairingStage.SerialRequired))
        assertEquals(1, store.writes)
        coordinator.close(); assertEquals(1, native.freed)
    }
    @Test fun cancellationClosesNativeAndNeverSavesCredentials() = runBlocking {
        val native = Native(); val store = Store()
        val coordinator = PairingCoordinator(native, store, "00:11:22:33:44:55", "NBSCOOTER")
        try {
            PairingTransport(coordinator, {}, { throw CancellationException("取消") }, {}, {}).pair()
            fail("應中止配對")
        } catch (_: CancellationException) { }
        assertEquals(PairingStage.Closed, coordinator.stage)
        assertEquals(1, native.freed); assertEquals(0, store.writes)
    }

    @Test fun unansweredChallengeClosesSessionWithoutSaving() = runBlocking {
        val native = Native(); val store = Store()
        val coordinator = PairingCoordinator(native, store, "00:11:22:33:44:55", "NBSCOOTER")
        var sends = 0
        try {
            PairingTransport(coordinator, { sends++ }, { awaitCancellation() }, {}, {}, responseTimeoutMs = 20).pair()
            fail("沒有挑戰回覆時應中止配對")
        } catch (failure: IllegalStateException) {
            assertTrue(failure.message.orEmpty().contains("未回覆配對挑戰"))
        }
        assertEquals(1, sends)
        assertEquals(PairingStage.Closed, coordinator.stage)
        assertEquals(1, native.freed)
        assertEquals(0, store.writes)
    }

    @Test fun retriesButtonRequestAfterTimeoutThenCompletesPairing() = runBlocking {
        val native = Native(); val store = Store()
        val coordinator = PairingCoordinator(native, store, "00:11:22:33:44:55", "NBSCOOTER")
        val incoming = Channel<ByteArray>(Channel.UNLIMITED)
        var sends = 0
        PairingTransport(coordinator, send = {
            sends++
            // 第一次按鍵請求無回覆，下一次重試才收到確認。
            if (sends != 2) incoming.send(frame())
        }, receive = { incoming.receive() }, requestSerial = {
            assertTrue(coordinator.submitSerial("N4GSD123456789"))
        }, onStage = {}, responseTimeoutMs = 20, retryDelayMs = 1).pair()
        assertEquals(4, sends)
        assertEquals(PairingStage.Paired, coordinator.stage)
        assertEquals(1, store.writes)
        coordinator.close()
        assertEquals(1, native.freed)
    }
}
