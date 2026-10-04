package com.pluxy.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkPolicyTest {
    @Test
    fun `poster local puis tmdb precedent la capture paysage`() {
        val plan = ArtworkPolicy.plan(
            kind = "movie",
            height = 2160,
            localPosterUrl = "/poster.jpg",
            tmdbPosterUrl = "https://image.tmdb.org/poster.jpg",
            localThumbnailUrl = "/thumbnail.jpg",
        )

        assertEquals(
            listOf(
                ArtworkPolicy.Role.LOCAL_POSTER,
                ArtworkPolicy.Role.TMDB_POSTER,
                ArtworkPolicy.Role.LOCAL_THUMBNAIL,
            ),
            plan.candidates.map { it.role },
        )
        assertEquals(ArtworkPolicy.Aspect.POSTER, plan.candidates[0].aspect)
        assertEquals(ArtworkPolicy.Aspect.LANDSCAPE, plan.candidates.last().aspect)
        assertEquals("4K", plan.resolutionLabel)
    }

    @Test
    fun `absence image produit un fallback informatif et stable`() {
        val plan = ArtworkPolicy.plan(kind = "series", width = 1920, height = 1080)

        assertTrue(plan.candidates.isEmpty())
        assertEquals(ArtworkPolicy.Aspect.POSTER, plan.fallbackAspect)
        assertEquals("SÉRIE", plan.typeLabel)
        assertEquals("1080P", plan.resolutionLabel)
    }
}
