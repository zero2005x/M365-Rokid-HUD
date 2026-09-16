package com.m365bleapp.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Tests for GATT layout discovery.
 *
 * The cases here are the ones that actually broke things in the field, encoded
 * so they cannot break again:
 *
 *  - a device exposing two profiles and answering on only one,
 *  - the Ninebot custom profile where `…0003` is a second *write* channel and
 *    the notify channel is `…0004`,
 *  - a service that exists but whose characteristics carry the wrong
 *    directions, which must not be probed.
 */
class GattProfileDiscoveryTest {

    private fun uuid(s: String) = UUID.fromString(s)

    // --- fixtures ---------------------------------------------------------

    private fun char(
        uuidStr: String,
        serviceStr: String,
        write: Boolean = false,
        notify: Boolean = false,
    ) = GattCharacteristicView(
        uuid = uuid(uuidStr),
        serviceUuid = uuid(serviceStr),
        canWrite = write,
        canNotify = notify,
    )

    private fun service(uuidStr: String, vararg charUuids: String) =
        GattServiceView(uuid(uuidStr), charUuids.map { uuid(it) })

    /** A plain Nordic UART scooter (M365 and friends). */
    private fun nusDevice(): Pair<List<GattServiceView>, List<GattCharacteristicView>> {
        val s = GattProfileDiscovery.NUS_SERVICE
        return listOf(service(s, GattProfileDiscovery.NUS_WRITE, GattProfileDiscovery.NUS_NOTIFY)) to
            listOf(
                char(GattProfileDiscovery.NUS_WRITE, s, write = true),
                char(GattProfileDiscovery.NUS_NOTIFY, s, notify = true),
            )
    }

    /** A modern model exposing BOTH profiles — the Max G3 case. */
    private fun dualProfileDevice(): Pair<List<GattServiceView>, List<GattCharacteristicView>> {
        val nus = GattProfileDiscovery.NUS_SERVICE
        val nb = GattProfileDiscovery.NINEBOT_SERVICE
        return listOf(
            service(nus, GattProfileDiscovery.NUS_WRITE, GattProfileDiscovery.NUS_NOTIFY),
            service(
                nb,
                GattProfileDiscovery.NINEBOT_WRITE,
                GattProfileDiscovery.NINEBOT_WRITE_RCTP,
                GattProfileDiscovery.NINEBOT_NOTIFY,
            ),
        ) to listOf(
            char(GattProfileDiscovery.NUS_WRITE, nus, write = true),
            char(GattProfileDiscovery.NUS_NOTIFY, nus, notify = true),
            char(GattProfileDiscovery.NINEBOT_WRITE, nb, write = true),
            char(GattProfileDiscovery.NINEBOT_WRITE_RCTP, nb, write = true),
            char(GattProfileDiscovery.NINEBOT_NOTIFY, nb, notify = true),
        )
    }

    // --- NUS ---------------------------------------------------------------

    @Test
    fun `a plain nordic uart scooter resolves to NUS`() {
        val (services, chars) = nusDevice()
        val found = GattProfileDiscovery.discover(services, chars)
        assertEquals(1, found.size)
        assertEquals(GattProfileKind.NUS, found[0].kind)
        assertEquals(uuid(GattProfileDiscovery.NUS_WRITE), found[0].write)
        assertEquals(uuid(GattProfileDiscovery.NUS_NOTIFY), found[0].notify)
    }

    @Test
    fun `NUS is tried before the vendor-specific profile`() {
        // A Max G3 advertises both and answers only on NUS, so probing the
        // "better looking" manufacturer service first wastes a connect cycle
        // and misreports the failure.
        val (services, chars) = dualProfileDevice()
        val found = GattProfileDiscovery.discover(services, chars)
        assertEquals(2, found.size)
        assertEquals(GattProfileKind.NUS, found[0].kind)
        assertEquals(GattProfileKind.NINEBOT_CUSTOM, found[1].kind)
    }

    // --- Ninebot custom ----------------------------------------------------

    @Test
    fun `ninebot custom uses 0004 for notify, not 0003`() {
        // The trap: 0003 exists on this service but is a WRITE channel. Code
        // that generalises "0002 writes, 0003 notifies" subscribes to a
        // characteristic that never emits.
        val nb = GattProfileDiscovery.NINEBOT_SERVICE
        val services = listOf(
            service(
                nb,
                GattProfileDiscovery.NINEBOT_WRITE,
                GattProfileDiscovery.NINEBOT_WRITE_RCTP,
                GattProfileDiscovery.NINEBOT_NOTIFY,
            )
        )
        val chars = listOf(
            char(GattProfileDiscovery.NINEBOT_WRITE, nb, write = true),
            char(GattProfileDiscovery.NINEBOT_WRITE_RCTP, nb, write = true),
            char(GattProfileDiscovery.NINEBOT_NOTIFY, nb, notify = true),
        )

        val found = GattProfileDiscovery.discover(services, chars)
        assertEquals(1, found.size)
        assertEquals(GattProfileKind.NINEBOT_CUSTOM, found[0].kind)
        assertEquals(uuid(GattProfileDiscovery.NINEBOT_NOTIFY), found[0].notify)
        assertTrue(
            "notify channel must not be the 0003 write channel",
            found[0].notify != uuid(GattProfileDiscovery.NINEBOT_WRITE_RCTP)
        )
    }

    @Test
    fun `ninebot custom is not reported when its notify channel is missing`() {
        val nb = GattProfileDiscovery.NINEBOT_SERVICE
        val services = listOf(
            service(nb, GattProfileDiscovery.NINEBOT_WRITE, GattProfileDiscovery.NINEBOT_WRITE_RCTP)
        )
        val chars = listOf(
            char(GattProfileDiscovery.NINEBOT_WRITE, nb, write = true),
            char(GattProfileDiscovery.NINEBOT_WRITE_RCTP, nb, write = true),
        )
        assertTrue(GattProfileDiscovery.discover(services, chars).isEmpty())
    }

    // --- HMSoft ------------------------------------------------------------

    @Test
    fun `hmsoft resolves when one characteristic does both directions`() {
        val s = GattProfileDiscovery.HMSOFT_SERVICE
        val services = listOf(service(s, GattProfileDiscovery.HMSOFT_CHAR))
        val chars = listOf(char(GattProfileDiscovery.HMSOFT_CHAR, s, write = true, notify = true))

        val found = GattProfileDiscovery.discover(services, chars)
        assertEquals(1, found.size)
        assertEquals(GattProfileKind.HMSOFT, found[0].kind)
        // Same characteristic both ways is correct for this profile.
        assertEquals(found[0].write, found[0].notify)
    }

    @Test
    fun `hmsoft is rejected when the character is write-only`() {
        // Prevents an unrelated `ffe0` device (many BLE serial modules use it)
        // from being probed as a scooter.
        val s = GattProfileDiscovery.HMSOFT_SERVICE
        val services = listOf(service(s, GattProfileDiscovery.HMSOFT_CHAR))
        val chars = listOf(char(GattProfileDiscovery.HMSOFT_CHAR, s, write = true, notify = false))
        assertTrue(GattProfileDiscovery.discover(services, chars).isEmpty())
    }

    // --- direction enforcement --------------------------------------------

    @Test
    fun `a service present but with inverted directions is not reported`() {
        // The characteristics exist and have the right UUIDs, but write and
        // notify are swapped. Probing this would fail at the first write.
        val s = GattProfileDiscovery.NUS_SERVICE
        val services = listOf(service(s, GattProfileDiscovery.NUS_WRITE, GattProfileDiscovery.NUS_NOTIFY))
        val chars = listOf(
            char(GattProfileDiscovery.NUS_WRITE, s, notify = true),
            char(GattProfileDiscovery.NUS_NOTIFY, s, write = true),
        )
        assertTrue(GattProfileDiscovery.discover(services, chars).isEmpty())
    }

    @Test
    fun `an empty device yields no profiles rather than a guess`() {
        assertTrue(GattProfileDiscovery.discover(emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun `an unknown device yields no profiles`() {
        val other = "0000180d-0000-1000-8000-00805f9b34fb" // heart rate service
        val services = listOf(service(other, "00002a37-0000-1000-8000-00805f9b34fb"))
        val chars = listOf(char("00002a37-0000-1000-8000-00805f9b34fb", other, notify = true))
        assertTrue(GattProfileDiscovery.discover(services, chars).isEmpty())
    }

    // --- diagnostics -------------------------------------------------------

    @Test
    fun `unknown services are reported sorted for stable bug reports`() {
        val a = "0000fff0-0000-1000-8000-00805f9b34fb"
        val b = "0000180a-0000-1000-8000-00805f9b34fb"
        val described = GattProfileDiscovery.describeUnknown(
            listOf(service(a), service(b))
        )
        assertEquals(described.sorted(), described)
        assertEquals(2, described.size)
    }

    @Test
    fun `all three known service uuids are recognised`() {
        assertTrue(GattProfileDiscovery.isKnownService(uuid(GattProfileDiscovery.NUS_SERVICE)))
        assertTrue(GattProfileDiscovery.isKnownService(uuid(GattProfileDiscovery.NINEBOT_SERVICE)))
        assertTrue(GattProfileDiscovery.isKnownService(uuid(GattProfileDiscovery.HMSOFT_SERVICE)))
        assertTrue(!GattProfileDiscovery.isKnownService(uuid("0000180d-0000-1000-8000-00805f9b34fb")))
    }

    @Test
    fun `kinds present lists profiles without their channels`() {
        val (services, chars) = dualProfileDevice()
        assertEquals(
            listOf(GattProfileKind.NUS, GattProfileKind.NINEBOT_CUSTOM),
            GattProfileDiscovery.kindsPresent(services, chars)
        )
    }
}
