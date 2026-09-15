package com.m365bleapp.pairing

import org.junit.Assert.*
import org.junit.Test

class PairingCoordinatorTest {
    private class Native : PairingNative {
        var response = 1
        var freed = 0
        override fun begin(name: String, key: ByteArray) = 1L
        override fun next(handle: Long) = byteArrayOf(1)
        override fun receive(handle: Long, bytes: ByteArray) = response
        override fun serial(handle: Long, serial: String) = serial == "N4GSD123456789"
        override fun free(handle: Long) { freed++ }
    }
    private class Store : PairingCredentialStore {
        var saved: PairingCredentials? = null
        var writes = 0
        override fun load(address: String) = saved
        override fun save(address: String, credentials: PairingCredentials) { writes++; saved = credentials }
    }
    @Test fun savesOnlyAfterFinalAcknowledgement() {
        val native = Native(); val store = Store()
        val coordinator = PairingCoordinator(native, store, "00:11:22:33:44:55", "NBSCOOTER")
        coordinator.nextFrame()
        assertEquals(PairingStage.SerialRequired, coordinator.receive(byteArrayOf(1)))
        assertFalse(coordinator.submitSerial("N4GSD000000000"))
        assertEquals(0, store.writes)
        assertTrue(coordinator.submitSerial("N4GSD123456789"))
        coordinator.nextFrame(); native.response = 3
        assertEquals(PairingStage.AwaitingConfirmation, coordinator.receive(byteArrayOf(1)))
        assertEquals(0, store.writes)
        coordinator.nextFrame(); native.response = 4
        assertEquals(PairingStage.Paired, coordinator.receive(byteArrayOf(1)))
        assertEquals(1, store.writes)
        assertEquals("N4GSD123456789", store.saved?.serial)
        assertEquals("PairingCredentials(redacted)", store.saved.toString())
        coordinator.close(); coordinator.close()
        assertEquals(1, native.freed)
    }
    @Test fun staleSavedSerialRequiresManualInputAndFailureDoesNotOverwriteIt() {
        val native = Native(); val store = Store()
        store.saved = PairingCredentials("N4GSD000000000", ByteArray(16) { 1 })
        val coordinator = PairingCoordinator(native, store, "00:11:22:33:44:55", "NBSCOOTER")
        coordinator.nextFrame(); coordinator.receive(byteArrayOf(1))
        assertEquals(PairingStage.SerialRequired, coordinator.stage)
        assertTrue(coordinator.submitSerial("N4GSD123456789"))
        coordinator.nextFrame(); native.response = -1
        assertEquals(PairingStage.Failed, coordinator.receive(byteArrayOf(1)))
        assertEquals(0, store.writes)
        assertEquals("N4GSD000000000", store.saved?.serial)
        coordinator.close()
    }
    @Test fun validatesSerialWithoutAcceptingUnicodeLookalikes() {
        assertTrue(validSerial("26354/00467353"))
        assertTrue(validSerial("N4GSD123456789"))
        assertFalse(validSerial("Ｎ4GSD123456789"))
        assertFalse(validSerial("N4GSD12345678 "))
        assertFalse(validSerial("N4GSD12345678"))
    }
}
