package com.m365bleapp.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class PairingTokenBackupTest {
    private val records = listOf(
        PairingTokenRecord("aa:bb:cc:dd:ee:ff", "00112233445566778899aabb"),
        PairingTokenRecord("11-22-33-44-55-66", "FFEEDDCCBBAA998877665544"),
    )

    @Test fun encryptedBackupRoundTripsAcrossIndependentDecodes() {
        val password = "correct horse battery".toCharArray()
        val first = PairingTokenBackup.encode(records, password)
        val second = PairingTokenBackup.encode(records, password)
        assertFalse(first.contentEquals(second)) // fresh salt and nonce
        assertEquals(
            listOf(
                PairingTokenRecord("11:22:33:44:55:66", "FFEEDDCCBBAA998877665544"),
                PairingTokenRecord("AA:BB:CC:DD:EE:FF", "00112233445566778899AABB"),
            ),
            PairingTokenBackup.decode(first, password)
        )
    }

    @Test fun wrongPasswordAndTamperingCannotProduceTokens() {
        val file = PairingTokenBackup.encode(records, "correct horse battery".toCharArray())
        assertThrows(IllegalArgumentException::class.java) {
            PairingTokenBackup.decode(file, "incorrect password".toCharArray())
        }
        file[file.lastIndex] = (file.last().toInt() xor 1).toByte()
        assertThrows(IllegalArgumentException::class.java) {
            PairingTokenBackup.decode(file, "correct horse battery".toCharArray())
        }
    }

    @Test fun manualEntriesRejectInvalidLengthsAndDuplicateAddresses() {
        assertThrows(IllegalArgumentException::class.java) {
            PairingTokenBackup.normalizeToken("001122")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PairingTokenBackup.encode(records + records.first(), "correct horse battery".toCharArray())
        }
    }
}
