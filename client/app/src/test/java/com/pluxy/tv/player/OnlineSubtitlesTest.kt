package com.pluxy.tv.player

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class OnlineSubtitlesTest {
    @Test fun seekDropsExpiredCuesAndKeepsOverlappingCue() {
        val dir = Files.createTempDirectory("pluxy-subs").toFile()
        try {
            val source = java.io.File(dir, "original.srt").apply { writeText("1\n00:00:01,000 --> 00:00:03,000\nOld\n\n2\n00:00:09,000 --> 00:00:12,000\nOverlap\n\n3\n00:00:20,500 --> 00:00:22,000\nLater\n") }
            val target = OnlineSubtitles.shifted(source, 10000, java.io.File(dir, "shifted.srt"))
            val text = target.readText()
            assertFalse(text.contains("Old"))
            assertTrue(text.contains("00:00:00,000 --> 00:00:02,000"))
            assertTrue(text.contains("00:00:10,500 --> 00:00:12,000"))
            assertTrue(source.readText().contains("Old"))
            assertEquals(source, OnlineSubtitles.shifted(source, 0, target))
        } finally { dir.deleteRecursively() }
    }
}
