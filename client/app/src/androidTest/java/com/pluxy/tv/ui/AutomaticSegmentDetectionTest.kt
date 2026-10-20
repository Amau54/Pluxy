package com.pluxy.tv.ui

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.pluxy.server.library.AutomaticSegments
import com.pluxy.server.library.MediaEntry
import com.pluxy.server.library.MediaLibrary
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class AutomaticSegmentDetectionTest {
    @Test fun movieWithoutChaptersIsAnalyzedByTheAndroidServer() = verify(false)
    @Test fun libraryIsPreparedBeforeTheFirstPlaybackRequestAndSurvivesRestart() = verify(true)

    private fun verify(anticipate: Boolean) {
        val path = InstrumentationRegistry.getArguments().getString("opening_fixture_path").orEmpty()
        assumeTrue("Requires the isolated 210-second movie without chapters", path.isNotBlank())
        val source = File(path)
        assertTrue(source.isFile)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (codec in android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos) {
            if (!codec.isEncoder && codec.supportedTypes.any { it == "video/avc" }) {
                android.util.Log.i("PluxySegmentTest", "AVC ${codec.name}: software=${if (android.os.Build.VERSION.SDK_INT >= 29) codec.isSoftwareOnly else "unknown"}, colors=${codec.getCapabilitiesForType("video/avc").colorFormats.joinToString()}")
            }
        }
        val item = MediaEntry(id = "visual-opening-test-${System.currentTimeMillis()}", uri = Uri.fromFile(source).toString(),
            fileName = source.name, title = "Visual opening fixture", parsedYear = null, container = "mp4", mimeType = "video/mp4",
            size = source.length(), modifiedAtMs = source.lastModified(), durationSeconds = 210.0, width = 640, height = 360,
            videoCodec = "h264", audioCodec = null, audioChannels = null, isHdr = false, kind = "movie", seriesName = null,
            seriesKey = null, season = null, episode = null, artist = null, album = null, folder = null, subtitles = emptyList())
        val analyzer = AutomaticSegments(context, MediaLibrary(context) { _, _, _ -> })
        val items = if (anticipate) (0..6).map { item.copy(id = "${item.id}-$it", addedAtMs = it.toLong()) } else listOf(item)
        try {
            val deadline = System.currentTimeMillis() + if (anticipate) 300_000 else 150_000
            if (anticipate) analyzer.prepare(items)
            while (!items.all { analyzer.prepared(it) } && System.currentTimeMillis() < deadline) {
                if (!anticipate) analyzer.request(item)
                Thread.sleep(250)
            }
            assertTrue("All seven files must be ready without any chapter request", items.all { analyzer.prepared(it) })
            val result = analyzer.request(items.first())
            assertEquals("Both visual segments must be found without metadata", 2, result.length())
            val opening = result.getJSONObject(0)
            assertEquals("Opening", opening.getString("title"))
            assertTrue("Keep the cold open", opening.getLong("start_ms") in 8000..15000)
            assertTrue("Never skip into the following scene", opening.getLong("end_ms") in 34000..42000)
            val credits = result.getJSONObject(1)
            assertEquals("End credits", credits.getString("title"))
            assertTrue(credits.getLong("start_ms") in 170000..180000)
            assertTrue("Keep the post-credit scene", credits.getLong("end_ms") in 184000..194000)
            assertEquals("Results must be available from cache", result.toString(), analyzer.request(items.first()).toString())
        } finally { analyzer.close() }
        val restored = AutomaticSegments(context, MediaLibrary(context) { _, _, _ -> })
        try { assertTrue("A server restart must reuse prepared results", restored.prepared(items.first())) }
        finally { restored.close() }
    }
}
