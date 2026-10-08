package com.pluxy.tv.player

import com.pluxy.tv.api.ClientCapabilities

enum class AudioOutputMode(val key: String, val title: String, val description: String) {
    AUTO("auto", "Automatique", "Utilise la sortie détectée. Adapte le son si nécessaire, en gardant le surround quand il est pris en charge."),
    PASSTHROUGH("passthrough", "Passthrough forcé", "Tente d'envoyer Dolby/DTS au home cinéma, même si Android annonce mal ses capacités. Aucune conversion audio automatique."),
    COMPATIBLE("compatible", "Audio compatible", "Demande du AAC stéréo au serveur PC si le son ne passe pas par la TV ou l'ampli."),
    ;

    companion object {
        fun fromKey(key: String?): AudioOutputMode = entries.firstOrNull { it.key == key } ?: AUTO
    }
}

object AudioOutputPolicy {
    val passthroughCodecs = setOf("ac3", "eac3", "dts", "dtshd", "truehd", "ac4")

    fun capabilities(detected: ClientCapabilities, mode: AudioOutputMode, fallbackLevel: Int = 0, failedCodec: String? = null): ClientCapabilities {
        if (mode == AudioOutputMode.PASSTHROUGH) return detected.copy(
            supportedAudioCodecs = (detected.supportedAudioCodecs + passthroughCodecs).distinct(),
            maxAudioChannels = 8,
        )
        if (mode == AudioOutputMode.COMPATIBLE || fallbackLevel >= 2) return detected.copy(
            supportedAudioCodecs = listOf("aac"), maxAudioChannels = 2,
        )
        if (fallbackLevel == 1) {
            val surround = detected.supportedAudioCodecs.filter { it in setOf("ac3", "eac3") && it != failedCodec }
            return detected.copy(supportedAudioCodecs = listOf("aac") + surround,
                maxAudioChannels = if (surround.isEmpty()) 2 else detected.maxAudioChannels.coerceIn(2, 6))
        }
        return detected
    }

    // Explicit original-video playback does not inherit learned codec/bitrate limits.
    // Its server decision is still checked: video conversion is never accepted.
    fun originalVideoCapabilities(client: ClientCapabilities): ClientCapabilities = client.copy(
        supportsHevc = true, supportsHevc10bit = true, supportsH264 = true, supportsAv1 = true,
        supportsHdr = true, supportsHdr10 = true, maxVideoHeight = Int.MAX_VALUE, maxBitrateMbps = null,
        supportedVideoCodecs = (client.supportedVideoCodecs + listOf("h264", "hevc", "av1", "vp8", "vp9", "mpeg2video", "mpeg4", "vc1", "wmv3")).distinct(),
    )

    fun codecForMime(mime: String?): String? = when (mime) {
        "audio/true-hd" -> "truehd"
        "audio/vnd.dts" -> "dts"
        "audio/vnd.dts.hd" -> "dtshd"
        "audio/ac3" -> "ac3"
        "audio/eac3", "audio/eac3-joc" -> "eac3"
        "audio/mp4a-latm" -> "aac"
        else -> null
    }

    fun canAdapt(strategy: PlaybackStrategy, mode: AudioOutputMode, available: Boolean, level: Int, mime: String?): Boolean =
        strategy != PlaybackStrategy.COMPATIBILITY && mode == AudioOutputMode.AUTO && available && level < 2 &&
            mime?.startsWith("audio/") == true
}
