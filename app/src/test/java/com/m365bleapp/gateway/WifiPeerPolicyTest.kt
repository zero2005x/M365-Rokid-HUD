package com.m365bleapp.gateway

import com.m365bleapp.gateway.wifi.WifiPeerPolicy
import io.github.zero2005x.pev.core.gateway.*
import org.junit.Assert.*
import org.junit.Test

class WifiPeerPolicyTest {
    @Test fun `four old new deployment combinations`() {
        for (phone in 1..2) for (glass in 1..2) {
            val peer = WifiPeerPolicy(phone)
            if (glass == 2) assertTrue(peer.receiveHello(GatewayNegotiation.encodeHello(2, true, 185), true))
            val expected = if (phone == 2 && glass == 2) GatewayTransportChoice.NEGOTIATED_V2 else GatewayTransportChoice.LEGACY_V1
            assertEquals(expected, peer.choice)
            assertEquals(expected == GatewayTransportChoice.LEGACY_V1, peer.shouldSendV1(true))
        }
    }
    @Test fun `incompatible peer receives no legacy packet and sockets are isolated`() {
        val peer = WifiPeerPolicy()
        peer.receiveHello(GatewayNegotiation.encodeHello(1, false, 23), false)
        assertEquals(GatewayTransportChoice.INCOMPATIBLE, peer.choice)
        assertFalse(peer.shouldSendV1(true))
        assertFalse(peer.shouldSendV1(false))
        assertEquals(GatewayTransportChoice.LEGACY_V1, WifiPeerPolicy().choice)
    }
    @Test fun `malformed hello leaves previous choice unchanged`() {
        val peer = WifiPeerPolicy()
        assertFalse(peer.receiveHello(byteArrayOf(2), true))
        assertTrue(peer.shouldSendV1(true))
    }
}
