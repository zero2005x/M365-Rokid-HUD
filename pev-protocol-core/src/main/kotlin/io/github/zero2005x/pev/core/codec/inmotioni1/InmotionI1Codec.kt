package io.github.zero2005x.pev.core.codec.inmotioni1

import io.github.zero2005x.pev.core.codec.FrameSplitter
import io.github.zero2005x.pev.core.codec.Probe
import io.github.zero2005x.pev.core.codec.StreamEvent
import io.github.zero2005x.pev.core.codec.StreamReassembler
import io.github.zero2005x.pev.core.telemetry.Evidence
import io.github.zero2005x.pev.core.telemetry.TelemetrySnapshot

/**
 * Receive-only legacy CAN envelope, independently implemented from neutral framing facts.
 * AA AA also occurs in I2: selecting this codec does not identify a model or authorize writes.
 * Unknown physical scales, model-specific fields and alert meanings remain raw diagnostics.
 * One instance belongs to one connection; call [reset] on disconnect before reusing it.
 */
class InmotionI1Codec(
    maxExtendedBytes: Int = 512,
    maxBuffer: Int = 2048,
    private val inputEvidence: Evidence = Evidence.SYNTHETIC,
) {
    sealed interface Event {
        data class Frame(val packet: Packet) : Event
        data class Malformed(val raw: ByteArray, val reason: String) : Event
        data class Overflow(val dropped: Int) : Event
    }

    /** CAN words and metadata have structural meanings only; no model is guessed from their value. */
    class Packet internal constructor(
        wire: ByteArray,
        body: ByteArray,
        val checksumEscaped: Boolean,
        val inputEvidence: Evidence,
    ) {
        private val wireBytes = wire.copyOf()
        private val bodyBytes = body.copyOf()
        val raw: ByteArray get() = wireBytes.copyOf()
        val canId: Long = u32le(bodyBytes, 0)
        val canData: ByteArray get() = bodyBytes.copyOfRange(4, 12)
        val lengthCode: Int = bodyBytes[12].toInt() and 0xFF
        val channel: Int = bodyBytes[13].toInt() and 0xFF
        val format: Int = bodyBytes[14].toInt() and 0xFF
        val type: Int = bodyBytes[15].toInt() and 0xFF
        val extendedData: ByteArray?
            get() = if (lengthCode == EXTENDED) bodyBytes.copyOfRange(HEADER_SIZE, bodyBytes.size) else null
        val telemetry: TelemetrySnapshot get() = TelemetrySnapshot()
    }

    private val wire = I1WireScanner(maxExtendedBytes)
    private val stream = StreamReassembler(wire, maxBuffer)

    fun reset() = stream.reset()

    fun feed(chunk: ByteArray): List<Event> = stream.feed(chunk).map { event ->
        when (event) {
            is StreamEvent.Malformed -> Event.Malformed(event.raw, event.reason)
            is StreamEvent.Overflow -> Event.Overflow(event.dropped)
            is StreamEvent.FrameReady -> {
                val decoded = wire.scan(event.bytes, event.bytes.size) as I1WireResult.Complete
                Event.Frame(Packet(event.bytes, decoded.body, decoded.checksumEscaped, inputEvidence))
            }
        }
    }
}

private const val HEADER_SIZE = 16
private const val EXTENDED = 0xFE
private const val ESCAPE = 0xA5
private const val START = 0xAA
private const val END = 0x55

private fun reserved(value: Int): Boolean = value == START || value == END || value == ESCAPE

private fun u32le(bytes: ByteArray, at: Int): Long = (0..3).fold(0L) { value, index ->
    value or ((bytes[at + index].toLong() and 0xFF) shl (8 * index))
}

private sealed interface I1WireResult {
    data object Incomplete : I1WireResult
    data class Invalid(val reason: String) : I1WireResult
    data class Complete(val length: Int, val body: ByteArray, val checksumEscaped: Boolean) : I1WireResult
}

private sealed interface Octet {
    data object Incomplete : Octet
    data class Invalid(val reason: String) : Octet
    data class Value(val value: Byte) : Octet
}

private sealed interface BodyLength {
    data class Valid(val bytes: Int) : BodyLength
    data class Invalid(val reason: String) : BodyLength
}

/** A bounded view of one probe; no buffered bytes survive between probes. */
private class WireCursor(private val bytes: ByteArray, private val size: Int) {
    var at = 2
        private set

    fun readBodyOctet(): Octet {
        if (at >= size) return Octet.Incomplete
        val first = bytes[at].toInt() and 0xFF
        if (first != ESCAPE) {
            if (reserved(first)) return Octet.Invalid("unescaped body delimiter")
            at++
            return Octet.Value(first.toByte())
        }
        if (at + 1 >= size) return Octet.Incomplete
        val escaped = bytes[at + 1].toInt() and 0xFF
        if (!reserved(escaped)) return Octet.Invalid("invalid escape pair")
        at += 2
        return Octet.Value(escaped.toByte())
    }
}

private class I1WireScanner(private val maxExtendedBytes: Int) : FrameSplitter {
    init { require(maxExtendedBytes in 0..16384) { "extended-data budget out of range" } }

    override fun probe(buffer: ByteArray, size: Int): Probe = when (val result = scan(buffer, size)) {
        I1WireResult.Incomplete -> Probe.NeedMore
        is I1WireResult.Invalid -> Probe.Skip(1, result.reason)
        is I1WireResult.Complete -> Probe.Frame(result.length)
    }

    fun scan(buffer: ByteArray, size: Int): I1WireResult {
        for (index in 0..1) {
            if (index >= size) return I1WireResult.Incomplete
            if ((buffer[index].toInt() and 0xFF) != START) return I1WireResult.Invalid("bad preamble")
        }
        val cursor = WireCursor(buffer, size)
        val body = ByteArray(HEADER_SIZE + maxExtendedBytes)
        var required = HEADER_SIZE
        var count = 0
        while (count < required) {
            when (val octet = cursor.readBodyOctet()) {
                Octet.Incomplete -> return I1WireResult.Incomplete
                is Octet.Invalid -> return I1WireResult.Invalid(octet.reason)
                is Octet.Value -> body[count++] = octet.value
            }
            if (count == HEADER_SIZE) {
                when (val length = bodyLength(body)) {
                    is BodyLength.Valid -> required = length.bytes
                    is BodyLength.Invalid -> return I1WireResult.Invalid(length.reason)
                }
            }
        }
        return checkSuffix(buffer, size, cursor.at, body.copyOf(required))
    }

    private fun bodyLength(header: ByteArray): BodyLength = when (header[12].toInt() and 0xFF) {
        0x08 -> BodyLength.Valid(HEADER_SIZE)
        EXTENDED -> {
            val extension = u32le(header, 4)
            if (extension > maxExtendedBytes) BodyLength.Invalid("extended data exceeds budget")
            else BodyLength.Valid(HEADER_SIZE + extension.toInt())
        }
        else -> BodyLength.Invalid("unsupported length code")
    }

    private fun checkSuffix(buffer: ByteArray, size: Int, at: Int, body: ByteArray): I1WireResult {
        if (size < at + 3) return I1WireResult.Incomplete
        val checksum = body.sumOf { it.toInt() and 0xFF } and 0xFF
        if ((buffer[at].toInt() and 0xFF) == checksum && trailer(buffer, at + 1)) {
            return I1WireResult.Complete(at + 3, body, false)
        }
        // Historical I1 references escape the CHECK too. Accept its raw form as a
        // compatibility variant; neither variant supplies a setting encoder or I2 decoder.
        if ((buffer[at].toInt() and 0xFF) == ESCAPE && reserved(checksum)) {
            if (size < at + 4) return I1WireResult.Incomplete
            if ((buffer[at + 1].toInt() and 0xFF) == checksum && trailer(buffer, at + 2)) {
                return I1WireResult.Complete(at + 4, body, true)
            }
        }
        return I1WireResult.Invalid("checksum or trailer mismatch")
    }

    private fun trailer(buffer: ByteArray, at: Int): Boolean =
        (buffer[at].toInt() and 0xFF) == END && (buffer[at + 1].toInt() and 0xFF) == END
}
