package io.github.zero2005x.pev.core.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VehicleProfileCodecTest {
    @Test
    fun manualModelStaysOnTheBoundAddress() {
        val bound = VehicleProfileCodec.bind(emptyList(), "AA:BB", "M365", "desk")
        assertEquals("M365", VehicleProfileCodec.manualModelId(bound, "aa:bb"))
        assertNull(VehicleProfileCodec.manualModelId(bound, "CC:DD"))
        val roundTrip = VehicleProfileCodec.decode(VehicleProfileCodec.encode(bound))
        assertEquals(bound, roundTrip)
    }

    @Test
    fun escapedFieldsRoundTrip() {
        val raw = VehicleProfileCodec.bind(emptyList(), "AA\tBB", "id\\x", "line\nname")
        assertEquals(raw, VehicleProfileCodec.decode(VehicleProfileCodec.encode(raw)))
    }

    @Test
    fun discoveryOrderIgnoresSignalStrength() {
        val entries = listOf(
            DiscoveryEntry("new-strong", 2, saved = false, recognized = true),
            DiscoveryEntry("saved-weak", 1, saved = true, recognized = true),
            DiscoveryEntry("unknown", 0, saved = false, recognized = false),
        )
        val visible = NearbyListOrder.order(entries, showUnrecognized = false).map { it.address }
        assertEquals(listOf("saved-weak", "new-strong"), visible)
    }
}
