package io.github.zero2005x.pev.core.gateway

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayNegotiationTest {
    private val hello = GatewayNegotiation.encodeHello(2, true, 185)

    @Test fun `hello round trip keeps version flags and mtu`() {
        val decoded = GatewayNegotiation.decodeHello(hello)
        assertEquals(GatewayHello(2, true, 185), decoded)
    }

    @Test fun `malformed hello is rejected`() {
        assertNull(GatewayNegotiation.decodeHello(hello.copyOf(7)))
        val bad = hello.copyOf()
        bad[0] = 0
        assertNull(GatewayNegotiation.decodeHello(bad))
    }

    @Test fun `old phone and old glasses stay on legacy v1 for m365`() {
        assertEquals(GatewayTransportChoice.LEGACY_V1, GatewayNegotiation.choose(1, null, true))
    }

    @Test fun `new phone and old glasses do not invent a v2 peer`() {
        assertEquals(GatewayTransportChoice.LEGACY_V1, GatewayNegotiation.choose(2, null, true))
        assertEquals(GatewayTransportChoice.INCOMPATIBLE, GatewayNegotiation.choose(2, null, false))
    }

    @Test fun `old phone and new glasses remain on v1`() {
        val glasses = GatewayNegotiation.decodeHello(hello)
        assertEquals(GatewayTransportChoice.LEGACY_V1, GatewayNegotiation.choose(1, glasses, true))
        assertEquals(GatewayTransportChoice.INCOMPATIBLE, GatewayNegotiation.choose(1, glasses, false))
    }

    @Test fun `new phone and new glasses negotiate v2`() {
        val glasses = GatewayNegotiation.decodeHello(hello)
        assertEquals(GatewayTransportChoice.NEGOTIATED_V2, GatewayNegotiation.choose(2, glasses, false))
    }

    @Test fun `packet gate isolates session generation and duplicates`() {
        val gate = GatewayPacketGate()
        assertTrue(gate.accept(1, 0))
        assertFalse(gate.accept(1, 0))
        assertFalse(gate.accept(1, 0))
        assertTrue(gate.accept(1, 2))
        assertFalse(gate.accept(1, 1))
        assertTrue(gate.accept(2, 0))
    }
}

class GatewayTransportFramingTest {
    @Test fun `fragments reassemble in order and out of order`() {
        val packet = ByteArray(40) { it.toByte() }
        val parts = GatewayBleFraming.fragment(packet, 23, 7)
        assertTrue(parts.size > 1)
        val forward = GatewayBleReassembler()
        var done: ByteArray? = null
        parts.forEach { done = forward.accept(it, 7) ?: done }
        assertArrayEquals(packet, done)
        val reverse = GatewayBleReassembler()
        done = null
        parts.asReversed().forEach { done = reverse.accept(it, 7) ?: done }
        assertArrayEquals(packet, done)
    }

    @Test fun `late session fragment cannot complete the new session`() {
        val packet = ByteArray(30) { 1 }
        val parts = GatewayBleFraming.fragment(packet, 23, 1)
        val other = GatewayBleFraming.fragment(packet, 23, 2)
        val box = GatewayBleReassembler()
        assertNull(box.accept(parts[0], 2))
        assertNull(box.accept(other[0], 1))
        var done: ByteArray? = null
        other.forEach { done = box.accept(it, 2) ?: done }
        assertArrayEquals(packet, done)
    }

    @Test fun `truncated duplicate and conflicting fragments do not decode`() {
        val box = GatewayBleReassembler()
        assertNull(box.accept(byteArrayOf(0x01), 1))
        val parts = GatewayBleFraming.fragment(ByteArray(25) { 3 }, 23, 4)
        assertNull(box.accept(parts[0].copyOf(4), 4))
        val conflict = parts[0].copyOf()
        conflict[conflict.lastIndex] = 9
        assertNull(box.accept(parts[0], 4))
        assertNull(box.accept(conflict, 4))
    }

    @Test fun `wifi length bound matches v2 and rejects truncation`() {
        assertTrue(GatewayWifiFraming.validDeclaredLength(21))
        assertTrue(GatewayWifiFraming.validDeclaredLength(GatewayWifiFraming.maxDeclaredLength()))
        assertFalse(GatewayWifiFraming.validDeclaredLength(0))
        assertFalse(GatewayWifiFraming.validDeclaredLength(GatewayWifiFraming.maxDeclaredLength() + 1))
    }
}
