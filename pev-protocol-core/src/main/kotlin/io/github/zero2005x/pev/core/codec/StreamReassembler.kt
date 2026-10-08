package io.github.zero2005x.pev.core.codec

/** Result of asking a [FrameSplitter] about the buffered bytes. */
sealed interface Probe {
    /** Buffer is a valid prefix; wait for more bytes. */
    data object NeedMore : Probe

    /** A complete frame of [length] bytes starts at offset 0. */
    data class Frame(val length: Int) : Probe

    /** Offset 0 cannot start a frame; drop [count] bytes and retry. */
    data class Skip(val count: Int, val reason: String) : Probe
}

/** Family-specific framing rules. Pure: no state, no Android. */
fun interface FrameSplitter {
    fun probe(buffer: ByteArray, size: Int): Probe
}

/** Event emitted by [StreamReassembler]; raw bytes are kept for diagnostics. */
sealed interface StreamEvent {
    data class FrameReady(val bytes: ByteArray) : StreamEvent
    data class Malformed(val raw: ByteArray, val reason: String) : StreamEvent
    data class Overflow(val dropped: Int) : StreamEvent
}

/**
 * Bounded notify reassembler: handles fragmentation, coalesced frames and resync.
 * The buffer never grows beyond [maxBuffer]; on overflow it is cleared and reported.
 * Not thread-safe: own it from a single transport callback context.
 */
class StreamReassembler(private val splitter: FrameSplitter, private val maxBuffer: Int = 512) {
    private var buf = ByteArray(maxBuffer)
    private var size = 0

    init {
        require(maxBuffer > 0) { "maxBuffer must be positive" }
    }

    fun reset() {
        size = 0
    }

    fun feed(chunk: ByteArray): List<StreamEvent> {
        val out = ArrayList<StreamEvent>()
        var offset = 0
        while (offset < chunk.size) {
            val take = minOf(chunk.size - offset, maxBuffer - size)
            if (take == 0) {
                out += StreamEvent.Overflow(size)
                reset()
                continue
            }
            System.arraycopy(chunk, offset, buf, size, take)
            size += take
            offset += take
            drain(out)
        }
        return out
    }

    private fun drain(out: MutableList<StreamEvent>) {
        while (size > 0) {
            when (val p = splitter.probe(buf, size)) {
                Probe.NeedMore -> return
                is Probe.Frame -> consume(p.length)?.let { out += StreamEvent.FrameReady(it) } ?: return
                is Probe.Skip -> out += StreamEvent.Malformed(consume(p.count.coerceIn(1, size)) ?: return, p.reason)
            }
        }
    }

    /** Removes [n] bytes from the head; null (and a reset) when the splitter asked for more than buffered. */
    private fun consume(n: Int): ByteArray? {
        if (n <= 0 || n > size) {
            reset()
            return null
        }
        val head = buf.copyOfRange(0, n)
        System.arraycopy(buf, n, buf, 0, size - n)
        size -= n
        return head
    }
}

/** Fixed-length frame with constant [header] prefix and [tail] suffix (no checksum semantics). */
class FixedFrameSplitter(
    private val header: ByteArray,
    private val tail: ByteArray,
    private val frameLength: Int,
) : FrameSplitter {
    init {
        require(frameLength >= header.size + tail.size) { "frame shorter than header+tail" }
    }

    override fun probe(buffer: ByteArray, size: Int): Probe {
        val headerCheck = matchPrefix(buffer, size, header, 0)
        return when {
            headerCheck == Prefix.MISMATCH -> Probe.Skip(1, "bad header")
            size < frameLength -> Probe.NeedMore
            matchPrefix(buffer, size, tail, frameLength - tail.size) != Prefix.FULL -> Probe.Skip(1, "bad tail")
            else -> Probe.Frame(frameLength)
        }
    }

    private enum class Prefix { FULL, PARTIAL, MISMATCH }

    private fun matchPrefix(buffer: ByteArray, size: Int, pattern: ByteArray, at: Int): Prefix {
        for (i in pattern.indices) {
            if (at + i >= size) return Prefix.PARTIAL
            if (buffer[at + i] != pattern[i]) return Prefix.MISMATCH
        }
        return Prefix.FULL
    }
}
