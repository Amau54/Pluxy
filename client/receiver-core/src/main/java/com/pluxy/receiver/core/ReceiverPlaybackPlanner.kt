package com.pluxy.receiver.core

import java.util.Locale

object ReceiverPlaybackPlanner {
    fun plan(
        media: RemoteMedia,
        capabilities: ReceiverCapabilities,
        serverCanAdapt: Boolean,
    ): ReceiverPlaybackPlan {
        val blockers = mutableListOf<String>()
        val kind = media.kind.lowercase(Locale.ROOT)
        val container = media.container.normalized()
        if (container in capabilities.rejectedContainers.normalized()) blockers += "Conteneur refusé lors d'une lecture précédente"
        if (kind == "photo" && media.mimeType.lowercase(Locale.ROOT).substringBefore(';').trim() in
            capabilities.rejectedImageMimeTypes.map { it.lowercase(Locale.ROOT).substringBefore(';').trim() }
        ) blockers += "Image refusée lors d'une lecture précédente"
        if (kind != "photo") {
            if (media.audioCodec?.normalized() in capabilities.rejectedAudioCodecs.normalized()) blockers += "Audio refusé lors d'une lecture précédente"
            if (kind != "audio" && media.videoCodec?.normalized() in capabilities.rejectedVideoCodecs.normalized()) blockers += "Vidéo refusée lors d'une lecture précédente"
        }

        when (kind) {
            "photo" -> {
                val mime = media.mimeType.lowercase(Locale.ROOT).substringBefore(';')
                if (capabilities.imageMimeTypes.isNotEmpty() && mime !in capabilities.imageMimeTypes.normalized()) {
                    blockers += "Image ${media.container.uppercase(Locale.ROOT)} non annoncée par le récepteur"
                }
            }
            "audio" -> {
                media.audioCodec?.normalized()?.let { codec ->
                    if (capabilities.audioCodecs.isNotEmpty() && codec !in capabilities.audioCodecs.normalized()) {
                        blockers += "Audio ${codec.uppercase(Locale.ROOT)} non annoncé par le récepteur"
                    }
                }
                if (capabilities.containers.isNotEmpty() && container !in capabilities.containers.normalized()) {
                    blockers += "Conteneur ${media.container.uppercase(Locale.ROOT)} non annoncé"
                }
            }
            else -> {
                if (capabilities.containers.isNotEmpty() && container !in capabilities.containers.normalized()) {
                    blockers += "Conteneur ${media.container.uppercase(Locale.ROOT)} non annoncé"
                }
                media.videoCodec?.normalized()?.let { codec ->
                    if (capabilities.videoCodecs.isNotEmpty() && codec !in capabilities.videoCodecs.normalized()) {
                        blockers += "Vidéo ${codec.uppercase(Locale.ROOT)} non annoncée"
                    }
                }
                media.audioCodec?.normalized()?.let { codec ->
                    if (capabilities.audioCodecs.isNotEmpty() && codec !in capabilities.audioCodecs.normalized()) {
                        blockers += "Audio ${codec.uppercase(Locale.ROOT)} non annoncé"
                    }
                }
                val maxHeight = capabilities.maxVideoHeight
                if (maxHeight != null && (media.height ?: 0) > maxHeight) {
                    blockers += "Résolution ${media.height}p supérieure au profil ${maxHeight}p"
                }
                if (media.isHdr && !capabilities.supportsHdr) blockers += "HDR non annoncé"
            }
        }

        return when {
            blockers.isEmpty() -> ReceiverPlaybackPlan(
                PlaybackSuitability.DIRECT,
                "Direct Play · qualité originale",
            )
            serverCanAdapt -> ReceiverPlaybackPlan(
                PlaybackSuitability.ADAPTED,
                "Adaptation automatique nécessaire",
                blockers,
            )
            else -> ReceiverPlaybackPlan(
                PlaybackSuitability.UNSUPPORTED,
                "Format incompatible sans conversion",
                blockers,
            )
        }
    }

    private fun String.normalized(): String = normalizeReceiverFormat(this)

    private fun Set<String>.normalized(): Set<String> = mapTo(linkedSetOf()) { it.normalized() }
}
