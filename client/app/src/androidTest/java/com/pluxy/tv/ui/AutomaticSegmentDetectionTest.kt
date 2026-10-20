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
    @Test fun movieWithoutChaptersIsAnalyzedByTheAndroidServer() {
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
        try {
            val deadline = System.currentTimeMillis() + 150_000
            var result = analyzer.request(item)
            while (result.length() < 2 && System.currentTimeMillis() < deadline) {
                Thread.sleep(250)
                result = analyzer.request(item)
            }
            assertEquals("Both visual segments must be found without metadata", 2, result.length())
            val opening = result.getJSONObject(0)
            assertEquals("Opening", opening.getString("title"))
            assertTrue("Keep the cold open", opening.getLong("start_ms") in 8000..15000)
            assertTrue("Never skip into the following scene", opening.getLong("end_ms") in 34000..42000)
            val credits = result.getJSONObject(1)
            assertEquals("End credits", credits.getString("title"))
            assertTrue(credits.getLong("start_ms") in 170000..180000)
            assertTrue("Keep the post-credit scene", credits.getLong("end_ms") in 184000..194000)
            assertEquals("Results must be available from cache", result.toString(), analyzer.request(item).toString())
        } finally { analyzer.close() }
    }
}
