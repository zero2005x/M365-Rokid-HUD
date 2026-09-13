package com.m365bleapp.ffi

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/** 載入主機版 Rust JNI，驗證真實 ABI；不複製解析邏輯作為替身。 */
class NativeProfileIntegrationTest {
    companion object {
        @BeforeClass @JvmStatic fun loadNative() {
            val library = System.getenv("M365_NATIVE_TEST_LIBRARY")
            assumeTrue("須先建置主機版 ninebot-ffi 並設定 M365_NATIVE_TEST_LIBRARY", !library.isNullOrEmpty())
            System.load(checkNotNull(library))
        }
    }
    private val native = M365Native()
    private fun call(name: String, vararg args: Any): Any? {
        val types = args.map { when (it) {
            is Int -> Int::class.javaPrimitiveType!!
            is Long -> Long::class.javaPrimitiveType!!
            is Boolean -> Boolean::class.javaPrimitiveType!!
            else -> it.javaClass
        } }.toTypedArray()
        return M365Native::class.java.getDeclaredMethod(name, *types).apply { isAccessible = true }.invoke(native, *args)
    }
    @Test fun nativeDiscoveryAndLegacyDecoderPreserveAndroidBehavior() {
        val profiles = ProfileDescriptor.decode(call("availableProfiles") as ByteArray)
        assertEquals(6, profiles.size)
        assertEquals(listOf(0), profiles.filter { it.verified }.map { it.modelId })
        val data = ByteArray(24); data[7] = 79
        data[10] = 0xc7.toByte(); data[11] = 0xcf.toByte()
        data[14] = 0; data[15] = 0x5e; data[16] = 0xd0.toByte(); data[17] = 0xb2.toByte()
        data[22] = 0x85.toByte(); data[23] = 0xff.toByte()
        val values = call("decodeLegacyMotorInfo", 0, data) as DoubleArray
        assertEquals(7, values.size)
        assertEquals(79.0, values[0], 0.0)
        assertEquals((-12345f / 1000f).toDouble(), values[1], 0.0)
        assertEquals(-1294967296.0, values[3], 0.0)
        assertEquals((-123f / 10f).toDouble(), values[6], 0.0)
        assertEquals(0.0, (call("decodeLegacyMotorInfo", 0, data.copyOf(23)) as DoubleArray)[6], 0.0)
        assertEquals(0, (call("decodeLegacyMotorInfo", 0, data.copyOf(21)) as DoubleArray).size)
        assertEquals(0, (call("profileControl", 2, 1, 1) as ByteArray).size)
        assertArrayEquals(byteArrayOf(4, 0x20, 3, 0x7d, 2, 0), call("profileControl", 0, 1, 1) as ByteArray)
    }
    @Test fun fullPairingIdentificationTelemetryAndReleaseCrossJni() {
        val frames = File("../ninebot-ble/tests/fixtures/xiaomi_vehicle.txt").readLines().drop(1).map { line ->
            line.split(' ')[2].chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }
        val pairing = call("beginPairing", "NBSCOOTER", ByteArray(16) { (0xf0 + it).toByte() }) as Long
        assertTrue(pairing != 0L)
        var vehicle = 0L
        try {
            assertArrayEquals(frames[0], call("pairingNext", pairing) as ByteArray)
            assertEquals(1, call("pairingReceive", pairing, frames[1]))
            assertEquals(true, call("pairingSetSerial", pairing, "21886/12345678"))
            assertArrayEquals(frames[2], call("pairingNext", pairing) as ByteArray)
            assertEquals(3, call("pairingReceive", pairing, frames[3]))
            assertArrayEquals(frames[4], call("pairingNext", pairing) as ByteArray)
            assertEquals(4, call("pairingReceive", pairing, frames[5]))
            vehicle = call("openVehicle", pairing) as Long
            assertTrue(vehicle != 0L)
            assertEquals(0, (call("vehicleRequest", vehicle, 2, 0, 1) as ByteArray).size)
            assertArrayEquals(frames[6], call("vehicleRequest", vehicle, 0, 0, 0) as ByteArray)
            assertArrayEquals("21886/12345678".toByteArray(), call("vehicleReceive", vehicle, frames[7]) as ByteArray)
            assertArrayEquals(byteArrayOf(1, 4, 1, -1, 1), call("vehicleResolve", vehicle, -1, false) as ByteArray)
            assertArrayEquals(byteArrayOf(1, 3, 1, -1, 1), call("vehicleResolve", vehicle, -1, true) as ByteArray)
            assertArrayEquals(frames[8], call("vehicleRequest", vehicle, 1, 0, 0) as ByteArray)
            val payload = call("vehicleReceive", vehicle, frames[9]) as ByteArray
            val telemetry = call("decodeMotorInfo", 1, payload) as DoubleArray
            assertEquals(25.3, telemetry[1], 0.0); assertEquals(420.0, telemetry[4], 0.0)
            assertArrayEquals(frames[10], call("vehicleRequest", vehicle, 2, 0, 1) as ByteArray)
        } finally { call("freePairing", pairing); call("freeVehicle", vehicle) }
        assertEquals(0, (call("vehicleRequest", vehicle, 2, 0, 1) as ByteArray).size)
        call("freeVehicle", vehicle)
    }
}
