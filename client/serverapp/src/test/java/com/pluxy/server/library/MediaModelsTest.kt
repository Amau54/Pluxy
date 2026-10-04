package com.pluxy.server.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaModelsTest {
    @Test
    fun `every visual media kind exposes a local thumbnail fallback`() {
        listOf("movie", "series", "photo", "audio").forEach { kind ->
            assertEquals("/image/thumbnail/media-id", localThumbnailUrl(kind, "media-id"))
        }
    }

    @Test
    fun `unknown media kind has no thumbnail endpoint`() {
        assertNull(localThumbnailUrl("document", "media-id"))
    }
}
