package com.m365bleapp.vehicle

import com.m365bleapp.ffi.ProfileDescriptor
import org.junit.Assert.*
import org.junit.Test

class DetectionResultTest {
    @Test fun mismatchAndUnknownNeverEnableControls() {
        val mismatch = DetectionResult.decode(byteArrayOf(1, 2, 2, 0, 1))
        assertFalse(mismatch.accepted)
        assertTrue(mismatch.message.contains("Xiaomi Pro 2"))
        assertTrue(mismatch.message.contains("Xiaomi M365"))
        assertFalse(DetectionResult.decode(byteArrayOf(1, 0, -1, -1, 0)).accepted)
        assertFalse(DetectionResult.decode(byteArrayOf(1, 4, 2, -1, 1)).accepted)
    }
    @Test fun partialProfileKeepsTelemetryButDisablesMissingControls() {
        val partial = DetectionResult.decode(byteArrayOf(1, 3, 2, -1, 1))
        assertTrue(partial.accepted)
        val profile = ProfileDescriptor(partial.modelId, false, 2, partial.capabilities)
        assertTrue(profile.supportsLock)
        assertFalse(profile.supportsLight)
        assertFalse(profile.supportsRideMode)
    }
    @Test fun malformedNativeDescriptorsFailClosed() {
        for (bytes in listOf(byteArrayOf(), byteArrayOf(2, 3, 2, -1, 1), byteArrayOf(1, 3, -1, -1, 1), byteArrayOf(1, 2, 2, -1, 1), byteArrayOf(1, 3, 2, -1, 8))) {
            assertThrows(IllegalArgumentException::class.java) { DetectionResult.decode(bytes) }
        }
    }
}
