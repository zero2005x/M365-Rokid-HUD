package io.github.zero2005x.pev.core.codec

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamReassemblerTest {
    private val header = byteArrayOf(0x55, 0xAA.toByte())
    private val tail = byteArrayOf(0x5A, 0x5A)
    private fun frame(vararg body: Int) = header + body.map { it.toByte() }.toByteArray() + tail
    private val splitter = FixedFrameSplitter(header, tail, 6)

    private fun frames(events: List<StreamEvent>) =
        events.filterIsInstance<StreamEvent.FrameReady>().map { it.bytes.toList() }

    @Test
    fun everySplitPointYieldsSameFrame() {
        val f = frame(1, 2)
        for (cut in 0..f.size) {
            val r = StreamReassembler(splitter)
            val ev = r.feed(f.copyOfRange(0, cut)) + r.feed(f.copyOfRange(cut, f.size))
            assertEquals("cut=$cut", listOf(f.toList()), frames(ev))
        }
    }

    @Test
    fun coalescedFramesAreSplit() {
        val ev = StreamReassembler(splitter).feed(frame(1, 2) + frame(3, 4))
        assertEquals(2, frames(ev).size)
    }

    @Test
    fun noiseIsReportedAndResyncs() {
        val ev = StreamReassembler(splitter).feed(byteArrayOf(9, 9) + frame(1, 2))
        assertEquals(2, ev.filterIsInstance<StreamEvent.Malformed>().size)
        assertEquals(1, frames(ev).size)
    }

    @Test
    fun badTailDropsOneByteAndResyncs() {
        val bad = header + byteArrayOf(1, 2, 0, 0)
        val ev = StreamReassembler(splitter).feed(bad + frame(7, 8))
        assertTrue(ev.any { it is StreamEvent.Malformed && it.reason == "bad tail" })
        assertEquals(listOf(frame(7, 8).toList()), frames(ev))
    }

    @Test
    fun resetDiscardsPartialFrame() {
        val r = StreamReassembler(splitter)
        r.feed(frame(1, 2).copyOfRange(0, 3))
        r.reset()
        assertEquals(1, frames(r.feed(frame(5, 6))).size)
    }

    @Test
    fun overflowIsBoundedAndRecovers() {
        val never = FrameSplitter { _, _ -> Probe.NeedMore }
        val ev = StreamReassembler(never, maxBuffer = 4).feed(ByteArray(10))
        assertTrue(ev.any { it is StreamEvent.Overflow })
    }

    @Test
    fun splitterAskingForTooManyBytesResets() {
        val greedy = FrameSplitter { _, _ -> Probe.Frame(99) }
        assertTrue(StreamReassembler(greedy).feed(byteArrayOf(1)).isEmpty())
    }

    @Test
    fun malformedKeepsRawBytes() {
        val ev = StreamReassembler(splitter).feed(byteArrayOf(7))
        assertArrayEquals(byteArrayOf(7), (ev.single() as StreamEvent.Malformed).raw)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsBadConfig() {
        StreamReassembler(splitter, 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsShortFrameLength() {
        FixedFrameSplitter(header, tail, 3)
    }
}
