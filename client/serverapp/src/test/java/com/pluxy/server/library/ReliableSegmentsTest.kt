package com.pluxy.server.library

import org.junit.Assert.*
import org.junit.Test
import java.util.Random
import kotlin.math.sin
import kotlin.math.PI
import org.json.JSONObject

class ReliableSegmentsTest {
    @Test fun `fingerprints agree with the PC contract`() {
        val data = JSONObject(javaClass.classLoader!!.getResource("audio-spectral.json")!!.readText())
        val pcm = ShortArray(data.getInt("samples")) { i ->
            (6000 * sin(2 * PI * (140 + (i / 4000 * 173) % 1460) * i / 8000) +
                4000 * sin(2 * PI * (140 + (i / 4000 * 307) % 1860) * i / 8000)).toInt().toShort()
        }
        val hashes = data.getJSONArray("hashes")
        assertArrayEquals(IntArray(hashes.length()) { hashes.getInt(it) }, AudioSignals.fingerprints(pcm))
    }
    @Test fun `spectral comparison tolerates sub second cuts`() {
        val notes = Random(38)
        val chords = List(40) { (140 + notes.nextInt(1460)) to (140 + notes.nextInt(1860)) }
        val pcm = ShortArray(20 * 8000) { i ->
            val (a, b) = chords[i / 4000]
            (6000 * sin(2 * PI * a * i / 8000) + 4000 * sin(2 * PI * b * i / 8000)).toInt().toShort()
        }
        val random = Random(45)
        fun padding() = IntArray(150) { random.nextInt() and Int.MAX_VALUE }
        val left = padding() + AudioSignals.fingerprints(pcm) + padding()
        for (offset in listOf(0, 400, 800, 2000, 4000)) {
            val right = padding() + AudioSignals.fingerprints(pcm.copyOfRange(offset, pcm.size)) + padding()
            val found = AudioSignals.match(left, right)
            assertNotNull("Offset $offset", found)
            assertTrue(found!!.end - found.start >= 15)
            assertTrue(found.coverage >= .85)
        }
    }

    @Test fun `credits require distinct roles and names rather than dialogue`() {
        val cards = listOf(listOf("PRODUCED BY", "ALICE MARTIN"), listOf("MUSIC BY", "PAUL RIVERA"),
            listOf("DIRECTED BY", "JANE MILLER")).map { CreditTextPolicy.summarize(it) }
        assertEquals(13.0 to 36.0, CreditTextPolicy.opening(listOf(12.0, 24.0, 36.0).zip(cards)))
        assertNull(CreditTextPolicy.opening(listOf(12.0, 24.0, 36.0).map { it to cards[0] }))
        val dialogue = listOf("Where is the director?", "The music is loud.", "I am a producer.")
        assertNull(CreditTextPolicy.opening(dialogue.mapIndexed { i, line -> (12.0 * (i + 1)) to CreditTextPolicy.summarize(listOf(line)) }))
        assertTrue(CreditTextPolicy.summarize(List(10) { "PAUL RIVERA" }).roster)
        assertTrue(CreditTextPolicy.summarize(listOf("edited by", "ALICE MARTIN")).nameCard)
        assertFalse(CreditTextPolicy.summarize(List(10) { "This is a sentence." }).roster)
    }
}
