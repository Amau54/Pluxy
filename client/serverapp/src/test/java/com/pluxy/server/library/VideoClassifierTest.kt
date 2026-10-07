package com.pluxy.server.library

import org.junit.Assert.*
import org.junit.Test

class VideoClassifierTest {
    @Test fun `indices et choix explicite classent les videos sans faux film personnel`() {
        assertEquals("clip", VideoClassifier.parse("VID_20241008.mp4", listOf("Films")).kind)
        assertEquals("clip", VideoClassifier.parse("Voyage 2024.mp4", listOf("Vacances"), duration = 6000.0).kind)
        assertEquals("series", VideoClassifier.parse("Example.S01E02.mkv", emptyList()).kind)
        assertEquals("movie", VideoClassifier.parse("Example.2024.1080p.mkv", emptyList()).kind)
        assertEquals("movie", VideoClassifier.parse("Example.mkv", listOf("Films")).kind)
        assertEquals("clip", VideoClassifier.parse("Example.mkv", emptyList()).kind)
        assertEquals("movie", VideoClassifier.parse("Example.mkv", emptyList(), duration = 6000.0, embeddedTitle = "Titre").kind)
        assertEquals("movie", VideoClassifier.parse("VID_1234.mp4", listOf("Vacances"), "movie").kind)
        val manual = VideoClassifier.parse("Example.S01E02.mkv", emptyList(), "clip")
        assertEquals("clip", manual.kind)
        assertNull(manual.seriesName)
        assertNull(manual.episode)
        assertEquals("series", VideoClassifier.parse("Example.mkv", emptyList(), "series").kind)
    }

    @Test fun `le score prefere une image visible aux fondus noirs et blancs`() {
        val detailed = intArrayOf(0xff406090.toInt(), 0xff80b0e0.toInt(), 0xff202040.toInt())
        assertTrue(VideoFrameScore.score(detailed) > VideoFrameScore.score(intArrayOf(0xff000000.toInt())))
        assertTrue(VideoFrameScore.score(detailed) > VideoFrameScore.score(intArrayOf(0xffffffff.toInt())))
        assertEquals(0.0, VideoFrameScore.score(intArrayOf()), 0.0)
    }
}
