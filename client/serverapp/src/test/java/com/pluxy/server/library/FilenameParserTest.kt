package com.pluxy.server.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FilenameParserTest {
    @Test
    fun `eight season release folders identify one show`() {
        val seasons = (1..8).map { season ->
            FilenameParser.parse("Boreal.S%02dE01.MULTI.1080p.mkv".format(season),
                listOf("Series", "Boreal.S%02d.MULTI.1080p.WEB-DL-TEAM".format(season)))
        }
        assertEquals(setOf("boreal"), seasons.map { it.seriesKey }.toSet())
        assertEquals((1..8).toList(), seasons.map { it.season })
        assertEquals(setOf("Boreal"), seasons.map { it.seriesName }.toSet())
    }

    @Test
    fun `film release name is cleaned without losing year`() {
        val parsed = FilenameParser.parse("Dune.2021.2160p.UHD.BluRay.x265-GROUP.mkv")
        assertEquals("Dune", parsed.title)
        assertEquals(2021, parsed.year)
        assertEquals("movie", parsed.kind)
    }

    @Test
    fun `numeric title is not mistaken for the release year`() {
        val parsed = FilenameParser.parse("2012.2009.1080p.BluRay.x264.mkv")
        assertEquals("2012", parsed.title)
        assertEquals(2009, parsed.year)
    }

    @Test
    fun `isolated numeric title stays a title`() {
        val parsed = FilenameParser.parse("1917.mkv")
        assertEquals("1917", parsed.title)
        assertNull(parsed.year)
    }

    @Test
    fun `series episode uses series folder and extracts numbers`() {
        val parsed = FilenameParser.parse(
            "Breaking.Bad.S02E03.1080p.WEB-DL.mkv",
            listOf("Séries", "Breaking Bad", "Saison 2"),
        )
        assertEquals("series", parsed.kind)
        assertEquals("Breaking Bad", parsed.seriesName)
        assertEquals("breakingbad", parsed.seriesKey)
        assertEquals(2, parsed.season)
        assertEquals(3, parsed.episode)
    }
}
