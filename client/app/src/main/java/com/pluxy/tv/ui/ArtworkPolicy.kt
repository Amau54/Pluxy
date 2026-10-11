package com.pluxy.tv.ui

/**
 * Décide quelles illustrations essayer et avec quel ratio. Cette politique reste pure afin
 * que le rendu sans TMDB puisse être vérifié sans instrumentation Android.
 */
object ArtworkPolicy {
    enum class Role {
        LOCAL_POSTER,
        EMBEDDED_ARTWORK,
        TMDB_POSTER,
        LOCAL_THUMBNAIL,
    }

    enum class Aspect(val heightToWidth: Float) {
        POSTER(1.5f),
        LANDSCAPE(9f / 16f),
        SQUARE(1f),
    }

    data class Candidate(
        val role: Role,
        val value: String,
        val aspect: Aspect,
    )

    data class Plan(
        val candidates: List<Candidate>,
        val fallbackAspect: Aspect,
        val typeLabel: String,
        val resolutionLabel: String?,
    )

    /**
     * Priorité éditoriale : illustration locale explicite, pochette intégrée, TMDB,
     * puis capture locale. Les champs locaux explicites ne sont pas encore exposés par
     * l'API Pluxy actuelle, mais la politique est prête à les recevoir sans modifier l'UI.
     */
    fun plan(
        kind: String,
        width: Int? = null,
        height: Int? = null,
        localPosterUrl: String? = null,
        embeddedArtworkUrl: String? = null,
        tmdbPosterUrl: String? = null,
        localThumbnailUrl: String? = null,
    ): Plan {
        val posterAspect = when (kind) {
            "clip" -> Aspect.LANDSCAPE
            "photo", "audio" -> Aspect.SQUARE
            else -> Aspect.POSTER
        }
        val thumbnailAspect = when (kind) {
            "photo", "audio" -> Aspect.SQUARE
            else -> Aspect.LANDSCAPE
        }
        val candidates = buildList {
            localPosterUrl.usable()?.let {
                add(Candidate(Role.LOCAL_POSTER, it, posterAspect))
            }
            embeddedArtworkUrl.usable()?.let {
                add(Candidate(Role.EMBEDDED_ARTWORK, it, Aspect.SQUARE))
            }
            tmdbPosterUrl.usable()?.let {
                add(Candidate(Role.TMDB_POSTER, it, Aspect.POSTER))
            }
            localThumbnailUrl.usable()?.let {
                add(Candidate(Role.LOCAL_THUMBNAIL, it, thumbnailAspect))
            }
        }.distinctBy { it.role to it.value }

        return Plan(
            candidates = candidates,
            fallbackAspect = posterAspect,
            typeLabel = when (kind) {
                "series" -> "SÉRIE"
                "clip" -> "VIDÉO"
                "photo" -> "PHOTO"
                "audio" -> "MUSIQUE"
                else -> "FILM"
            },
            resolutionLabel = resolutionLabel(width, height),
        )
    }

    fun resolutionLabel(width: Int?, height: Int?): String? {
        val w = width?.coerceAtLeast(0) ?: 0
        val h = height?.coerceAtLeast(0) ?: 0
        // Les films panoramiques sont souvent encodés sans les bandes noires.
        // Une marge de 1 % couvre aussi les quelques pixels rognés à l'encodage.
        return when {
            w >= 3802 || h >= 2139 -> "4K"
            w >= 2535 || h >= 1426 -> "1440P"
            w >= 1901 || h >= 1070 -> "1080P"
            w >= 1268 || h >= 713 -> "720P"
            w > 0 && h > 0 -> "${w}×$h"
            h > 0 -> "${h}P"
            else -> null
        }
    }

    private fun String?.usable(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}
