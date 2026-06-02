package com.pluxy.tv.api

import android.content.Context
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.view.Display
import com.pluxy.tv.PluxyApplication

/**
 * Détecte dynamiquement les capacités réelles du téléviseur (Philips 803) :
 * support HEVC, HDR10, résolution, afin que le serveur choisisse Direct Play
 * plutôt que de transcoder inutilement.
 */
object DeviceProfile {

    fun capabilities(): ClientCapabilities {
        val ctx = PluxyApplication.instance
        return ClientCapabilities(
            supportsHevc = supportsCodec(MediaFormat.MIMETYPE_VIDEO_HEVC),
            supportsH264 = supportsCodec(MediaFormat.MIMETYPE_VIDEO_AVC),
            supportsHdr10 = supportsHdr10(ctx),
            supportsHdr = supportsHdr10(ctx),
            // ExoPlayer remux ces conteneurs nativement.
            supportedContainers = listOf("mp4", "mkv", "webm", "ts"),
            // ARC classique Philips 803 : AC3/EAC3 + AAC/MP3 décodés en interne.
            supportedAudioCodecs = listOf("aac", "ac3", "eac3", "mp3"),
            maxAudioChannels = 6,
            // Plafond Wi-Fi conseillé pour le double saut 5 GHz (laisser le serveur
            // brider si la source dépasse). null = pas de limite côté client.
            maxBitrateMbps = null,
            screenWidth = displaySize(ctx).first,
            screenHeight = displaySize(ctx).second,
        )
    }

    private fun supportsCodec(mime: String): Boolean {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        return list.codecInfos.any { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }
    }

    private fun supportsHdr10(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val display = ctx.getSystemService(Context.DISPLAY_SERVICE)
            .let { it as android.hardware.display.DisplayManager }
            .getDisplay(Display.DEFAULT_DISPLAY) ?: return false
        val caps = display.hdrCapabilities ?: return false
        return caps.supportedHdrTypes.any {
            it == Display.HdrCapabilities.HDR_TYPE_HDR10 ||
                it == Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS
        }
    }

    private fun displaySize(ctx: Context): Pair<Int, Int> {
        val dm = ctx.resources.displayMetrics
        return dm.widthPixels.coerceAtLeast(1280) to dm.heightPixels.coerceAtLeast(720)
    }
}
