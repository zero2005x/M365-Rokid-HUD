package io.github.zero2005x.pev.core.codec.begode

import io.github.zero2005x.pev.core.codec.FixedFrameSplitter
import io.github.zero2005x.pev.core.codec.StreamEvent
import io.github.zero2005x.pev.core.codec.StreamReassembler
import io.github.zero2005x.pev.core.telemetry.Evidence
import io.github.zero2005x.pev.core.telemetry.FieldId
import io.github.zero2005x.pev.core.telemetry.Reading
import io.github.zero2005x.pev.core.telemetry.TelemetrySnapshot

/**
 * Bounded A2 notification decoder. The CAP-A corrected research establishes
 * the 24-byte envelope (`55 AA ... 5A 5A 5A 5A`) and eight big-endian words, plus
 * real-vehicle-confirmed `0x00` live pack voltage (`voltageWord / 100.0` V in `20.0..200.0` V)
 * and MPU6050 temperature (`temperatureRaw / 340.0 + 36.53` °C in `-40.0..150.0` °C).
 *
 * When [feed] is called without `observedAtMs`, [Diagnostic.telemetry] remains empty
 * (`TelemetrySnapshot()`), preserving synthetic envelope-only tests. When `observedAtMs`
 * is supplied on [Profile.A2], only the verified `PACK_VOLTAGE` and `TEMP_IMU` fields
 * are populated on `0x00` live frames; unresolved fields (speed, current, SOC, range,
 * distance, and the `@14..15` packed mode word, which is never PWM) remain `NOT_PROVIDED`.
 *
 * Profile selection is explicit and never inferred from an advertised name, voltage or type word.
 * A codec instance belongs to one connection session; [reset] discards buffered partial bytes.
 * The host memory budget [maxBuffer] defaults to 256 bytes. A budget below [FRAME_LENGTH]
 * can report overflow but cannot assemble a full frame. No wire length is inferred from byte 19.
 */
class BegodeA2Codec(
    private val profile: Profile,
    private val inputEvidence: Evidence = Evidence.SYNTHETIC,
    maxBuffer: Int = 256,
) {
    enum class Profile { A2, UNKNOWN }

    sealed interface Event {
        data class Frame(val diagnostic: Diagnostic) : Event
        data class Malformed(val raw: ByteArray, val reason: String) : Event
        data class Overflow(val dropped: Int) : Event
    }

    /** Raw branch names describe static interpretations, not validated physical measurements. */
    sealed interface RawBody {
        data class Live(
            val voltageWord: Int,
            val speedRaw: Int,
            val rangeWord: Int,
            val distanceWord: Int,
            val currentRaw: Int,
            val temperatureRaw: Int,
            /** Packed mode/configuration word at @14. This is not PWM. */
            val modeWord: Int,
            val flagsWord: Int,
        ) : RawBody

        /** The A2's type 0x01/byte19=0 layout is unresolved. Keep the frame's eight raw words. */
        data object BatteryUnresolved : RawBody

        data class Settings(
            val odometerHighWord: Int,
            val odometerLowWord: Int,
            val settingsWord: Int,
            val timerWord: Int,
            val pedalSensitivityWord: Int,
            val ambientModeWord: Int,
            /** High byte of channel 6, frame byte 14. */
            val stateByte: Int,
            /** Low byte of channel 6, frame byte 15. */
            val headlightByte: Int,
            /** Raw vendor device-type word, not an exact model identity. */
            val vehicleTypeWord: Int,
        ) : RawBody

        data class Advanced(
            val phaseCurrentRaw: Int,
            val configurationWord: Int,
            val motorTemperatureRaw: Int,
            /** Signed raw word @8; no percentage scale is asserted. */
            val pwmRaw: Int,
            val reservedWords: List<Int>,
        ) : RawBody

        data object Unresolved : RawBody
    }

    data class Diagnostic(
        val raw: ByteArray,
        /** Exactly eight unsigned big-endian words in original wire order. */
        val channels: List<Int>,
        val type: Int,
        /** Byte 19 is a branch discriminator, not a packet length. */
        val discriminator: Int,
        val profile: Profile,
        val body: RawBody,
        /** Evidence for these input bytes, supplied by the transport/replay caller. */
        val inputEvidence: Evidence,
        val telemetry: TelemetrySnapshot = TelemetrySnapshot(),
    ) {
        /** Evidence for envelope structure only; does not validate physical meanings or these input bytes. */
        val layoutEvidence: Evidence get() = Evidence.WIRE_CAPTURED
    }

    private val stream = StreamReassembler(
        FixedFrameSplitter(byteArrayOf(0x55, 0xAA.toByte()), ByteArray(4) { 0x5A }, FRAME_LENGTH),
        maxBuffer,
    )

    /** No partial frame crosses disconnect/reconnect when callers reset the session. */
    fun reset() = stream.reset()

    fun feed(chunk: ByteArray): List<Event> = feed(chunk, observedAtMs = null)

    fun feed(chunk: ByteArray, observedAtMs: Long?): List<Event> = stream.feed(chunk).map { event ->
        when (event) {
            is StreamEvent.FrameReady -> Event.Frame(diagnostic(event.bytes, observedAtMs))
            is StreamEvent.Malformed -> Event.Malformed(event.raw, event.reason)
            is StreamEvent.Overflow -> Event.Overflow(event.dropped)
        }
    }

    private fun diagnostic(raw: ByteArray, observedAtMs: Long?): Diagnostic {
        val channels = List(8) { slot ->
            val offset = 2 + slot * 2
            ((raw[offset].toInt() and 0xFF) shl 8) or (raw[offset + 1].toInt() and 0xFF)
        }
        val type = raw[18].toInt() and 0xFF
        val discriminator = raw[19].toInt() and 0xFF
        val parsedBody = body(channels, type, discriminator)
        val snapshot = if (observedAtMs != null && profile == Profile.A2 && parsedBody is RawBody.Live) {
            decodeVerifiedLiveTelemetry(parsedBody, observedAtMs)
        } else {
            TelemetrySnapshot()
        }
        return Diagnostic(raw, channels, type, discriminator, profile, parsedBody, inputEvidence, snapshot)
    }

    private fun decodeVerifiedLiveTelemetry(live: RawBody.Live, observedAtMs: Long): TelemetrySnapshot {
        val voltageV = live.voltageWord / 100.0
        val voltageReading = if (voltageV in MIN_VALID_PACK_VOLTAGE_V..MAX_VALID_PACK_VOLTAGE_V) {
            Reading.valid(voltageV, observedAtMs, inputEvidence, SOURCE_LIVE_00)
        } else {
            Reading.invalid(observedAtMs, SOURCE_LIVE_00)
        }
        val imuTempC = live.temperatureRaw / 340.0 + 36.53
        val tempReading = if (imuTempC in MIN_VALID_IMU_TEMP_C..MAX_VALID_IMU_TEMP_C) {
            Reading.valid(imuTempC, observedAtMs, inputEvidence, SOURCE_LIVE_00)
        } else {
            Reading.invalid(observedAtMs, SOURCE_LIVE_00)
        }
        return TelemetrySnapshot(
            mapOf(
                FieldId.PACK_VOLTAGE to voltageReading,
                FieldId.TEMP_IMU to tempReading,
            )
        )
    }

    private fun body(words: List<Int>, type: Int, discriminator: Int): RawBody {
        if (profile != Profile.A2) return RawBody.Unresolved
        return when {
            type == 0x00 && discriminator == 0x18 -> RawBody.Live(
                words[0], signed(words[1]), words[2], words[3], signed(words[4]), signed(words[5]), words[6], words[7],
            )
            type == 0x01 && discriminator == 0 -> RawBody.BatteryUnresolved
            type == 0x04 && discriminator == 0x18 -> RawBody.Settings(
                words[0], words[1], words[2], words[3], words[4], words[5], words[6] ushr 8, words[6] and 0xFF, words[7],
            )
            type == 0x07 && discriminator == 0x18 -> RawBody.Advanced(
                signed(words[0]), words[1], signed(words[2]), signed(words[3]), words.subList(4, 8),
            )
            else -> RawBody.Unresolved
        }
    }

    private fun signed(word: Int): Int = if (word >= 0x8000) word - 0x10000 else word

    companion object {
        const val FRAME_LENGTH = 24
        const val SOURCE_LIVE_00 = "begode:a2:0x00"
        const val STALE_AFTER_MS = 1_000L
        private const val MIN_VALID_PACK_VOLTAGE_V = 20.0
        private const val MAX_VALID_PACK_VOLTAGE_V = 200.0
        private const val MIN_VALID_IMU_TEMP_C = -40.0
        private const val MAX_VALID_IMU_TEMP_C = 150.0

        /**
         * Recognizes the 9-octet ASCII device-info reply (e.g. `GW1511014`) observed
         * on Begode A2 so sessions can route it to device-info handling rather than
         * feeding it into the binary `55 AA` frame stream.
         */
        fun parseDeviceInfoAsciiOrNull(chunk: ByteArray): String? {
            if (chunk.size !in 4..16) return null
            if (chunk[0] != 'G'.code.toByte() || chunk[1] != 'W'.code.toByte()) return null
            for (i in 2 until chunk.size) {
                val b = chunk[i].toInt() and 0xFF
                val isDigit = b in '0'.code..'9'.code
                val isUpper = b in 'A'.code..'Z'.code
                val isLower = b in 'a'.code..'z'.code
                if (!isDigit && !isUpper && !isLower) return null
            }
            return String(chunk, Charsets.US_ASCII)
        }
    }
}
