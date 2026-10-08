package io.github.zero2005x.pev.core.codec.begode

import io.github.zero2005x.pev.core.codec.FixedFrameSplitter
import io.github.zero2005x.pev.core.codec.StreamEvent
import io.github.zero2005x.pev.core.codec.StreamReassembler
import io.github.zero2005x.pev.core.telemetry.Evidence
import io.github.zero2005x.pev.core.telemetry.TelemetrySnapshot

/**
 * Bounded, raw-only A2 notification decoder. The CAP-A corrected research establishes
 * the 24-byte envelope and eight big-endian words, not nonzero physical scales.
 * Voltage/SOC/current/temperature telemetry is deliberately absent pending further evidence.
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
    ) {
        /** Evidence for envelope structure only; does not validate physical meanings or these input bytes. */
        val layoutEvidence: Evidence get() = Evidence.WIRE_CAPTURED
        val telemetry: TelemetrySnapshot get() = TelemetrySnapshot()
    }

    private val stream = StreamReassembler(
        FixedFrameSplitter(byteArrayOf(0x55, 0xAA.toByte()), ByteArray(4) { 0x5A }, FRAME_LENGTH),
        maxBuffer,
    )

    /** No partial frame crosses disconnect/reconnect when callers reset the session. */
    fun reset() = stream.reset()

    fun feed(chunk: ByteArray): List<Event> = stream.feed(chunk).map { event ->
        when (event) {
            is StreamEvent.FrameReady -> Event.Frame(diagnostic(event.bytes))
            is StreamEvent.Malformed -> Event.Malformed(event.raw, event.reason)
            is StreamEvent.Overflow -> Event.Overflow(event.dropped)
        }
    }

    private fun diagnostic(raw: ByteArray): Diagnostic {
        val channels = List(8) { slot ->
            val offset = 2 + slot * 2
            ((raw[offset].toInt() and 0xFF) shl 8) or (raw[offset + 1].toInt() and 0xFF)
        }
        val type = raw[18].toInt() and 0xFF
        val discriminator = raw[19].toInt() and 0xFF
        return Diagnostic(raw, channels, type, discriminator, profile, body(channels, type, discriminator), inputEvidence)
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
    }
}
