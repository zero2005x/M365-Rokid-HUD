package com.m365bleapp.ffi

import org.junit.Assert.*
import org.junit.Test

class ProfileDescriptorTest {
    @Test fun readsCapabilitiesFromNativeMetadata() {
        val profiles = ProfileDescriptor.decode(byteArrayOf(1, 2, 0, 1, 1, 3, 4, 0, 2, 0))
        assertEquals(2, profiles.size)
        assertTrue(profiles[0].verified)
        assertTrue(profiles[0].supportsLock)
        assertTrue(profiles[0].supportsLight)
        assertFalse(profiles[0].supportsRideMode)
        assertFalse(profiles[1].verified)
        assertFalse(profiles[1].supportsLock)
        assertEquals(2, profiles[1].cryptoStrategy)
    }

    @Test fun rejectsMalformedOrIncompatibleNativeMetadata() {
        val invalid = listOf(
            byteArrayOf(), byteArrayOf(1), byteArrayOf(2, 0),
            byteArrayOf(1, 1), byteArrayOf(1, 0, 0),
            byteArrayOf(1, 1, 0, 2, 1, 3),
            byteArrayOf(1, 1, 0, 1, 3, 3),
            byteArrayOf(1, 1, 0, 1, 1, 8),
            byteArrayOf(1, 2, 0, 1, 1, 3, 0, 1, 1, 3),
        )
        for (bytes in invalid) {
            assertThrows(IllegalArgumentException::class.java) { ProfileDescriptor.decode(bytes) }
        }
    }
}
