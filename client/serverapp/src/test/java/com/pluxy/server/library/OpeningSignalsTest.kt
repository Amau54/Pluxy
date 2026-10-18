package com.pluxy.server.library

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.util.Base64

class OpeningSignalsTest {
    @Test fun `known software decoders are accepted despite incorrect platform metadata`() {
        assertTrue(SegmentDecoderPolicy.software("OMX.google.h264.decoder", false))
        assertTrue(SegmentDecoderPolicy.software("c2.android.avc.decoder", false))
        assertTrue(SegmentDecoderPolicy.software("vendor.software.decoder", true))
        assertFalse(SegmentDecoderPolicy.software("OMX.vendor.hardware.avc.decoder", false))
    }
    private fun card(top: Int = 18, letters: Int = 16): IntArray {
        val pixels = IntArray(9216)
        for (y in listOf(top, top + 14)) for (x in 12 until 12 + letters * 6 step 6)
            for ((dx, dy) in listOf(0 to 0, 0 to 1, 0 to 2, 0 to 3, 1 to 3)) pixels[(y + dy) * 128 + x + dx] = 230
        return pixels
    }
    private fun sequence(): List<OpeningSignals.Frame> {
        val black = OpeningSignals.frame(IntArray(9216))
        return List(3) { black } + List(5) { OpeningSignals.frame(card()) } + black +
            List(5) { OpeningSignals.frame(card(28, 12)) } + black +
            List(5) { OpeningSignals.frame(card(12, 18)) } + List(3) { black }
    }
    @Test fun `movie opening needs no chapters and preserves cold open`() {
        assertEquals(6 to 38, OpeningSignals.interval(sequence()))
        assertEquals(26 to 58, OpeningSignals.interval(List(10) { OpeningSignals.Frame() } + sequence()))
    }
    @Test fun `encoded antialiased text cards remain recognizable`() {
        val fixture = javaClass.classLoader!!.getResourceAsStream("opening-cards.json")!!.bufferedReader().use { JSONObject(it.readText()) }
        val encoded = fixture.getJSONArray("frames_base64")
        val cards = (0 until encoded.length()).map { index ->
            val bytes = Base64.getDecoder().decode(encoded.getString(index))
            OpeningSignals.frame(IntArray(bytes.size) { bytes[it].toInt() and 255 })
        }
        assertTrue(cards.all { it.text })
        val black = OpeningSignals.Frame(black = true)
        assertEquals(8 to 40, OpeningSignals.interval(List(4) { black } + List(5) { cards[0] } + black +
            List(5) { cards[1] } + black + List(5) { cards[2] } + black))
    }
    @Test fun `subtitles black screens one logo and narrative gaps are rejected`() {
        assertFalse(OpeningSignals.frame(card(54)).text)
        assertFalse(OpeningSignals.frame(IntArray(9216) { 220 }).text)
        assertFalse(OpeningSignals.frame(IntArray(12)).black)
        val rectangle = IntArray(9216) { if (it / 128 in 20..39 && it % 128 in 40..79) 230 else 0 }
        assertFalse(OpeningSignals.frame(rectangle).text)
        val black = OpeningSignals.Frame(black = true)
        val text = OpeningSignals.frame(card())
        assertTrue(text.text)
        assertNull(OpeningSignals.interval(listOf(black) + List(40) { text } + black))
        assertNull(OpeningSignals.interval(List(80) { black }))
        val interrupted = sequence().toMutableList().apply { set(8, OpeningSignals.Frame()) }
        assertNull(OpeningSignals.interval(interrupted))
        assertNull(OpeningSignals.interval(sequence().take(8)))
    }
}
