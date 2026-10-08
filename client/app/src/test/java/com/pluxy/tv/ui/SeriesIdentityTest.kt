package com.pluxy.tv.ui

import com.pluxy.tv.api.MediaItem
import org.junit.Assert.*
import org.junit.Test

class SeriesIdentityTest {
    private fun episode(season: Int, name: String) = MediaItem("s$season", "Boreal S${season}E01", "", "mkv", 1, 1200.0,
        kind = "series", seriesName = name, seriesKey = "boreals${season}group", season = season, episode = 1)

    @Test fun eightSeasonPacksBecomeOneShowWithEightSelectableSeasons() {
        val episodes = (1..8).map { episode(it, "Boreal S%02d -GROUP".format(it)) }
        val rows = TvLibraryContent.seriesRows(episodes)
        assertEquals(1, rows.size)
        assertEquals("Boreal", rows.single().name)
        assertEquals(8, rows.single().count)
        val all = TvLibraryContent.seriesEpisodes(episodes, rows.single().key, rows.single().name)
        assertEquals((1..8).toList(), all.map { it.season })
    }

    @Test fun similarNamesAreNotFuzzyMergedAndCleanServerKeysRemainStable() {
        val one = episode(1, "Boreal").copy(seriesKey = "show-id-1")
        assertEquals("show-id-1", SeriesIdentity.key(one))
        assertNotEquals(SeriesIdentity.key(episode(1, "Boreal S01 -GROUP")),
            SeriesIdentity.key(episode(1, "Boreal North S01 -GROUP")))
    }
}
