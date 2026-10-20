package com.pluxy.server.library

import org.junit.Assert.*
import org.junit.Test
import java.util.Random

class SegmentSignalsTest {
    private fun signals(seed: Long, length: Int): IntArray {
        val random = Random(seed)
        return IntArray(length) { random.nextInt() and Int.MAX_VALUE }
    }
    @Test fun `the same intro is found after different cold opens`() {
        val intro = signals(99, 40)
        assertEquals(30.0 to 69.0, SegmentSignals.repeatedIntro(signals(1, 30) + intro + signals(2, 80), signals(3, 12) + intro + signals(4, 100), step = 1.0, span = 0.0))
        assertEquals(12.0 to 51.0, SegmentSignals.repeatedIntro(signals(3, 12) + intro + signals(4, 100), signals(1, 30) + intro + signals(2, 80), step = 1.0, span = 0.0))
    }
    @Test fun `silence duplicates and unrelated episodes are never marked`() {
        assertNull(SegmentSignals.repeatedIntro(IntArray(200) { -1 }, IntArray(200) { -1 }))
        assertNull(SegmentSignals.repeatedIntro(IntArray(200) { 1234 }, IntArray(200) { 1234 }))
        val duplicate = signals(1, 150)
        assertNull(SegmentSignals.repeatedIntro(duplicate, duplicate))
        assertNull(SegmentSignals.repeatedIntro(signals(1, 150), signals(2, 150)))
        assertNull(SegmentSignals.repeatedIntro(signals(1, 30) + signals(99, 40), signals(2, 12) + signals(99, 40) + signals(3, 100)))
        assertArrayEquals(IntArray(4) { -1 }, SegmentSignals.fingerprints(ShortArray(16000)))
    }
    @Test fun `ordinary subtitles are rejected and the post credit scene remains`() {
        fun frame(rows: Set<Int>) = IntArray(128 * 72) { if (it / 128 in rows && it % 128 in 32..95) 230 else 0 }
        assertTrue(SegmentSignals.creditFrame(frame(setOf(12, 19, 26, 33, 40, 47))))
        assertFalse(SegmentSignals.creditFrame(frame(setOf(60, 64))))
        assertFalse(SegmentSignals.creditFrame(IntArray(9216) { 220 }))
        assertEquals(8 to 46, SegmentSignals.creditInterval(List(4) { false } + List(20) { true } + List(8) { false }))
        assertNull(SegmentSignals.creditInterval(List(4) { false } + List(5) { true } + List(8) { false }))
    }
}
