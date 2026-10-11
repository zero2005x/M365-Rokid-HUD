package com.m365bleapp.gateway.wifi

import io.github.zero2005x.pev.core.gateway.*

/** One instance per socket. No hello is the deployed V1 peer. */
internal class WifiPeerPolicy(private val phoneMaxVersion: Int = 2) {
    @Volatile var choice = GatewayTransportChoice.LEGACY_V1
        private set
    fun receiveHello(payload: ByteArray, supportsV1: Boolean): Boolean {
        val hello = GatewayNegotiation.decodeHello(payload) ?: return false
        choice = GatewayNegotiation.choose(phoneMaxVersion, hello, supportsV1)
        return true
    }
    fun shouldSendV1(supportsV1: Boolean) = supportsV1 && choice == GatewayTransportChoice.LEGACY_V1
}
