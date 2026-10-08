package io.github.zero2005x.pev.core.codec.xiaomi

import io.github.zero2005x.pev.core.telemetry.Evidence
import io.github.zero2005x.pev.core.telemetry.FieldId
import io.github.zero2005x.pev.core.telemetry.FieldState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Historical owner capture replay; no new hardware or vendor-app acceptance is claimed. */
class XiaomiCaptureReplayTest {
    private val directory = "/fixtures/xiaomi/m365/"
    private val motorFile = "motor-info.csv"
    private val phoneFile = "phone-correspondence.csv"
    private val rxFile = "rx-registers.csv"
    private val speedTolerance = 0.000001 // Historical app serialized Float speed as Double.

    private fun resource(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream(directory + name)) { "Missing fixture $name" }
            .use { it.readBytes() }

    private fun rows(name: String): List<Map<String, String>> {
        val lines = resource(name).toString(Charsets.UTF_8).trimEnd().lines()
        val header = lines.first().split(',')
        return lines.drop(1).map { line ->
            val values = line.split(',')
            require(values.size == header.size) { "Malformed sanitized fixture row" }
            header.zip(values).toMap()
        }
    }

    private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun sanitizedFixtureHashesMatchTransformationManifest() {
        val manifest = resource("manifest.json").toString(Charsets.UTF_8)
        for (name in listOf(motorFile, phoneFile, rxFile)) {
            val expected = requireNotNull(Regex("\"${Regex.escape(name)}\": \"([0-9a-f]{64})\"")
                .find(manifest)?.groupValues?.get(1))
            val actual = MessageDigest.getInstance("SHA-256").digest(resource(name))
                .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
            assertEquals(name, expected, actual)
        }
    }

    @Test
    fun replaysAll152BlocksAgainstCapturedParsedValuesAndWordDiagnostics() {
        val captures = rows(motorFile)
        val incoming = rows(rxFile).associateBy { it.getValue("source_line") }
        assertEquals(152, captures.size)
        captures.forEachIndexed { sequence, capture ->
            assertEquals(sequence.toString(), capture.getValue("sequence"))
            val received = incoming.getValue(capture.getValue("rx_line"))
            assertEquals("b0", received.getValue("register"))
            assertEquals(received.getValue("data_hex"), capture.getValue("data_hex"))
            val payload = hex(capture.getValue("data_hex"))
            assertEquals(32, payload.size)
            val words = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
            for (offset in 0..22 step 2) {
                // Oracle is the numeric offset diagnostic captured in logcat, not derived test data.
                assertEquals("sequence $sequence word $offset", capture.getValue("word_$offset").toInt(),
                    words.getShort(offset).toInt() and 0xFFFF)
            }
            val now = capture.getValue("elapsed_ms").toLong()
            val snapshot = XiaomiMotorInfoDecoder.decode(payload, now)
            val expected = mapOf(
                FieldId.SPEED_KMH to capture.getValue("speed_kmh").toDouble(),
                FieldId.SOC_PERCENT to capture.getValue("soc_percent").toDouble(),
                FieldId.TEMP_FRAME to capture.getValue("temp_frame_c").toDouble(),
                FieldId.TOTAL_DISTANCE_M to capture.getValue("mileage_km").toDouble() * 1000,
            )
            expected.forEach { (field, value) ->
                val reading = snapshot[field]
                assertEquals("sequence $sequence $field", value, requireNotNull(reading.value), speedTolerance)
                assertEquals(FieldState.VALID, reading.state)
                assertEquals(Evidence.WIRE_CAPTURED, reading.evidence)
                assertEquals(now, reading.observedAtMs)
            }
        }
    }

    @Test
    fun preservesCapturedRangesAndSignedTransientWithoutInventingMotionValidation() {
        val captures = rows(motorFile)
        val decoded = captures.map {
            XiaomiMotorInfoDecoder.decode(hex(it.getValue("data_hex")), it.getValue("elapsed_ms").toLong())
        }
        fun values(id: FieldId) = decoded.map { requireNotNull(it[id].value) }
        assertEquals(setOf(43.0, 44.0, 51.0), values(FieldId.SOC_PERCENT).toSet())
        val distance = values(FieldId.TOTAL_DISTANCE_M)
        assertEquals(400107.0, distance.first(), 0.0)
        assertEquals(400871.0, distance.last(), 0.0)
        assertTrue(distance.zipWithNext().all { (previous, next) -> next >= previous })
        // Actual capture reaches 45 C; the historical README's 44 C maximum is inaccurate.
        assertEquals(31.0, values(FieldId.TEMP_FRAME).min(), 0.0)
        assertEquals(45.0, values(FieldId.TEMP_FRAME).max(), 0.0)
        val speeds = values(FieldId.SPEED_KMH)
        assertEquals(1, speeds.count { it < 0 })
        assertEquals(-5.514, speeds.min(), speedTolerance)
        assertEquals(0.150, speeds.max(), speedTolerance)
        assertEquals(-5.514, speeds[46], speedTolerance)
        assertEquals(FieldState.NOT_PROVIDED, decoded.first()[FieldId.PACK_VOLTAGE].state)
    }

    @Test
    fun compares92UniqueTimestampCorrespondencesWithSeparatelyRecordedPhoneCsv() {
        val captures = rows(motorFile)
        val phone = rows(phoneFile)
        assertEquals(92, phone.size)
        assertEquals((0 until 92).toSet(), phone.map { it.getValue("sequence").toInt() }.toSet())
        phone.forEach { record ->
            assertTrue(kotlin.math.abs(record.getValue("delta_ms").toInt()) <= 9)
            val capture = captures[record.getValue("sequence").toInt()]
            val decoded = XiaomiMotorInfoDecoder.decode(hex(capture.getValue("data_hex")), 0)
            listOf(
                FieldId.SPEED_KMH to "speed_kmh", FieldId.SOC_PERCENT to "soc_percent",
                FieldId.TEMP_FRAME to "temp_frame_c", FieldId.TOTAL_DISTANCE_M to "mileage_km",
            ).forEach { (field, column) ->
                val units = if (field == FieldId.TOTAL_DISTANCE_M) 1000.0 else 1.0
                assertEquals("phone sequence ${record.getValue("sequence")} $field",
                    record.getValue(column).toDouble() * units, requireNotNull(decoded[field].value), speedTolerance)
            }
        }
    }

    @Test
    fun retainsOtherCapturedRxRegistersWithoutAssigningUnprovenPhysicalSemantics() {
        val rx = rows(rxFile)
        assertEquals(156, rx.size)
        assertEquals(152, rx.count { it.getValue("register") == "b0" })
        assertEquals(listOf("b6019100", "f4019100", "2e029100"),
            rx.filter { it.getValue("register") == "3a" }.map { it.getValue("data_hex") })
        assertEquals(listOf("0a05"), rx.filter { it.getValue("register") == "25" }.map { it.getValue("data_hex") })
    }
}
