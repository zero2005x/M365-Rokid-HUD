package com.m365bleapp.protocol

import io.github.zero2005x.pev.core.vehicle.*
import org.junit.Assert.*
import org.junit.Test

class NearbyListOrderTest {
    @Test fun `rssi updates do not alter first discovery order`() {
        data class Scan(val address: String, val index: Int, val saved: Boolean, val rssi: Int)
        val first = listOf(Scan("A", 0, false, -95), Scan("B", 1, false, -30), Scan("C", 2, true, -80))
        fun order(scans: List<Scan>) = NearbyListOrder.order(scans.map { DiscoveryEntry(it.address, it.index, it.saved, true) }, true).map { it.address }
        assertEquals(listOf("C", "A", "B"), order(first))
        assertEquals(order(first), order(first.map { it.copy(rssi = -it.rssi) }))
    }
}
