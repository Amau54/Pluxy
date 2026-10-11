package com.pluxy.tv.player

import org.junit.Assert.*
import org.junit.Test

class SkipSegmentsTest {
    @Test fun `precise episode chapters take priority over a season template`() {
        val chapter = SkipSegment("intro", 120000, 180000)
        val season = SkipSegment("intro", 0, 60000)
        val resolved = SkipSegments.resolve(listOf(chapter), emptyList(), listOf(season))
        assertNull(SkipSegments.active(resolved, 10000))
        assertEquals(180000L, SkipSegments.active(resolved, 125000)!!.endMs)
        assertEquals(listOf(season), SkipSegments.resolve(emptyList(), emptyList(), listOf(season)))
    }
    @Test fun `named chapters preserve cold open and post credits scene`() {
        val found = SkipSegments.detected(listOf(MediaChapter(10000, 40000, "Opening"),
            MediaChapter(900000, 950000, "Générique de fin"), MediaChapter(950000, 1000000, "Post credits")), 1000000)
        assertEquals(2, found.size)
        assertNull(SkipSegments.active(found, 5000))
        assertEquals(40000L, SkipSegments.active(found, 10000)!!.endMs)
        assertEquals(950000L, SkipSegments.active(found, 900000)!!.endMs)
        assertNull(SkipSegments.active(found, 950000))
    }
    @Test fun `unlabelled chapters and implausible intervals do not skip scenes`() {
        assertTrue(SkipSegments.detected(listOf(MediaChapter(0, 40000, "Chapter 1"),
            MediaChapter(0, 900000, "Intro"), MediaChapter(0, 40000, "Credits"),
            MediaChapter(900000, 2000000, "Credits")), 1000000).isEmpty())
    }
    @Test fun `manual intro overrides chapter detection without removing credits`() {
        val detected = listOf(SkipSegment("intro", 0, 60000), SkipSegment("credits", 900000, 1000000))
        val own = SkipMarkers(10000, 50000).segments(1000000)
        val merged = SkipSegments.merge(detected, own)
        assertEquals(2, merged.size)
        assertEquals(50000L, SkipSegments.active(merged, 20000)!!.endMs)
    }
    @Test fun `invalid saved markers and tiny remaining intervals are ignored`() {
        assertTrue(SkipMarkers(60000, 50000, 1200000).segments(1000000).isEmpty())
        assertNull(SkipSegments.active(listOf(SkipSegment("intro", 0, 60000)), 59700))
        assertEquals(950000L, SkipMarkers(creditsStartMs = 900000, creditsEndMs = 950000).segments(1000000).single().endMs)
    }
}
