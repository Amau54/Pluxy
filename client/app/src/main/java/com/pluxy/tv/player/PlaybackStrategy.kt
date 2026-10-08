package com.pluxy.tv.player

import com.pluxy.tv.api.BufferConfig
import com.pluxy.tv.api.PlaybackDecision

/**
 * Stratégie choisie par l'utilisateur. Elle reste locale au lecteur : le mode
 * automatique continue de respecter la décision du serveur, alors que les deux
 * modes explicites garantissent un comportement prévisible.
 */
enum class PlaybackStrategy(
    val key: String,
    val title: String,
    val description: String,
) {
    AUTO(
        key = "auto",
        title = "Automatique (recommandé)",
        description = "Privilégie Direct Play quand il est lisible, puis réduit la qualité seulement si nécessaire.",
    ),
    DIRECT(
        key = "direct",
        title = "Vidéo originale (4K/HDR)",
        description = "Aucune conversion vidéo. Le son suit le choix de sortie audio, avec un tampon renforcé.",
    ),
    COMPATIBILITY(
        key = "compatibility",
        title = "Compatibilité",
        description = "Demande du H.264 1080p au serveur PC. Sur Android : original avec tampon renforcé.",
    );

    companion object {
        fun fromKey(value: String?): PlaybackStrategy =
            entries.firstOrNull { it.key == value } ?: AUTO
    }
}

/** Palier de qualité d'une tentative, distinct de son protocole de transport. */
enum class PlaybackTier {
    ORIGINAL,
    ADAPTED,
    COMPATIBILITY,
    EMERGENCY,
}

/**
 * Une tentative de lecture typée. `adaptiveTarget` indique qu'elle constitue
 * réellement une baisse de débit : un Direct Play de secours ne doit jamais être
 * pris pour une amélioration réseau après un flux HLS déjà réduit.
 */
data class PlaybackAttempt(
    val url: String,
    val delivery: String,
    val tier: PlaybackTier,
    val label: String,
    val adaptiveTarget: Boolean = false,
)

/** Logique pure et testable de construction de la chaîne de lecture/repli. */
object PlaybackPlanner {

    fun build(
        strategy: PlaybackStrategy,
        itemId: String,
        decision: PlaybackDecision,
        transcodingAvailable: Boolean,
        supportsHevc: Boolean = true,
        preserveAudio: Boolean = false,
    ): List<PlaybackAttempt> {
        val directUrl = "/stream/direct/$itemId"
        val mainUrl = "/stream/hls/$itemId/main/index.m3u8"
        val compatUrl = "/stream/hls/$itemId/compat/index.m3u8"
        val attempts = mutableListOf<PlaybackAttempt>()

        fun add(attempt: PlaybackAttempt) {
            if (attempts.none { it.url == attempt.url }) attempts += attempt
        }

        fun rawDirect(label: String = "Direct Play") = PlaybackAttempt(
            url = directUrl,
            delivery = "direct",
            tier = PlaybackTier.ORIGINAL,
            label = label,
        )

        fun mainHls(adaptive: Boolean = true) = PlaybackAttempt(
            url = mainUrl,
            delivery = "hls",
            tier = PlaybackTier.ADAPTED,
            label = "Qualité originale · débit adapté",
            adaptiveTarget = adaptive,
        )

        fun compatHls(adaptive: Boolean = true) = PlaybackAttempt(
            url = compatUrl,
            delivery = "hls",
            tier = PlaybackTier.COMPATIBILITY,
            label = "Compatibilité H.264 · 1080p",
            adaptiveTarget = adaptive,
        )

        when (strategy) {
            PlaybackStrategy.DIRECT -> {
                if (decision.mode == "direct_stream" && preservesOriginalVideo(decision)) {
                    add(PlaybackAttempt(decision.streamUrl, "direct", PlaybackTier.ORIGINAL,
                        "Direct Stream · vidéo originale"))
                } else add(rawDirect())
            }

            PlaybackStrategy.COMPATIBILITY -> {
                if (transcodingAvailable && !preserveAudio) {
                    add(compatHls(adaptive = false))
                    add(
                        rawDirect("Direct Play de secours").copy(
                            tier = PlaybackTier.EMERGENCY,
                        ),
                    )
                } else {
                    add(rawDirect("Direct Play · compatibilité Android"))
                }
            }

            PlaybackStrategy.AUTO -> {
                val initialIsHls = deliveryFor(decision.streamUrl, decision.delivery) == "hls"
                if (initialIsHls) {
                    val initialIsCompat = decision.compat || decision.streamUrl.contains("/compat/")
                    add(
                        PlaybackAttempt(
                            url = decision.streamUrl,
                            delivery = "hls",
                            tier = if (initialIsCompat) PlaybackTier.COMPATIBILITY else PlaybackTier.ADAPTED,
                            label = if (initialIsCompat) {
                                "Compatibilité H.264 · 1080p"
                            } else {
                                "Qualité adaptée par le serveur"
                            },
                        ),
                    )
                    if (transcodingAvailable && !initialIsCompat && !preserveAudio) add(compatHls())
                    add(
                        rawDirect("Direct Play de secours").copy(
                            tier = PlaybackTier.EMERGENCY,
                        ),
                    )
                } else {
                    add(
                        PlaybackAttempt(
                            url = decision.streamUrl,
                            delivery = deliveryFor(decision.streamUrl, decision.delivery),
                            tier = PlaybackTier.ORIGINAL,
                            label = if (decision.mode == "direct_stream") "Direct Stream" else "Direct Play",
                        ),
                    )
                    // Un remux peut échouer alors que le fichier brut reste lisible. Il
                    // demeure un secours d'erreur, pas une baisse de débit réseau.
                    add(rawDirect())
                    if (transcodingAvailable) {
                        // Le palier principal est encodé en HEVC. Une TV H.264-only
                        // saute directement au profil compatible au lieu d'échouer.
                        if (supportsHevc) add(mainHls())
                        if (!preserveAudio) add(compatHls())
                    }
                }
            }
        }
        return attempts
    }

    fun preservesOriginalVideo(decision: PlaybackDecision): Boolean =
        decision.videoAction == "copy" && !decision.toneMap && decision.delivery == "direct" &&
            !decision.streamUrl.contains("/hls/") && !decision.streamUrl.endsWith(".m3u8", true)

    /** Prochain palier de débit inférieur, en ignorant les secours non adaptatifs. */
    fun nextAdaptiveIndex(attempts: List<PlaybackAttempt>, currentIndex: Int): Int =
        ((currentIndex + 1) until attempts.size).firstOrNull { attempts[it].adaptiveTarget } ?: -1

    /** Palier exact quitté lors d'une baisse réseau, pour une remontée sans saut. */
    fun recoveryTargetIndex(
        attempts: List<PlaybackAttempt>,
        currentIndex: Int,
        history: List<Int>,
    ): Int = history.lastOrNull()?.takeIf { candidate ->
        candidate in attempts.indices &&
            candidate < currentIndex &&
            attempts[candidate].tier != PlaybackTier.EMERGENCY
    } ?: -1

    /** Direct forcé et compatibilité sans transcode démarrent avec le gros tampon. */
    fun shouldStartWithReinforcedBuffer(
        strategy: PlaybackStrategy,
        transcodingAvailable: Boolean,
    ): Boolean = strategy == PlaybackStrategy.DIRECT ||
        (strategy == PlaybackStrategy.COMPATIBILITY && !transcodingAvailable)

    /**
     * Renforce la durée de tampon sans dépasser les bornes déjà imposées par la
     * fabrique ExoPlayer. Le plafond mémoire final reste adapté à la RAM de l'appareil.
     */
    fun reinforcedBuffer(base: BufferConfig): BufferConfig {
        val minMs = base.minBufferMs.coerceAtLeast(120_000).coerceAtMost(180_000)
        val maxMs = base.maxBufferMs.coerceAtLeast(minMs).coerceAtMost(180_000)
        return base.copy(
            minBufferMs = minMs,
            maxBufferMs = maxMs,
            bufferForPlaybackMs = base.bufferForPlaybackMs.coerceIn(250, 2_500),
            bufferForPlaybackAfterRebufferMs = base.bufferForPlaybackAfterRebufferMs
                .coerceIn(500, 5_000),
            targetBufferBytesMb = base.targetBufferBytesMb.coerceAtLeast(256),
        )
    }

    private fun deliveryFor(url: String, advertised: String): String =
        if (url.contains("/hls/") || url.endsWith(".m3u8", ignoreCase = true)) "hls" else advertised
}
