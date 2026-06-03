package com.pluxy.tv.api

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.view.Display
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioCapabilities
import com.pluxy.tv.PluxyApplication

/**
 * Détecte dynamiquement les capacités RÉELLES de décodage de l'appareil
 * (téléphone OU TV) : support HEVC, HEVC **Main10 (10 bits)**, hauteur max
 * décodable, HDR. Le serveur s'en sert pour choisir DÈS LE DÉPART le bon flux
 * (Direct Play vs transcodage compat H.264) — sans tenter une lecture vouée à l'échec.
 */
object DeviceProfile {

    @UnstableApi
    fun capabilities(): ClientCapabilities {
        val ctx = PluxyApplication.instance
        val hevc = hevcInfo()            // (supporté, main10, hauteurMax)
        val (audioCodecs, maxCh) = audioCapabilities(ctx)
        return ClientCapabilities(
            supportsHevc = hevc.supported,
            supportsHevc10bit = hevc.main10,
            supportsH264 = supportsCodec(MediaFormat.MIMETYPE_VIDEO_AVC),
            supportsHdr10 = supportsHdr10(ctx),
            supportsHdr = supportsHdr10(ctx),
            supportedContainers = listOf("mp4", "mkv", "webm", "ts"),
            supportedAudioCodecs = audioCodecs,
            maxAudioChannels = maxCh,
            maxBitrateMbps = null,
            maxVideoHeight = hevc.maxHeight,
            screenWidth = displaySize(ctx).first,
            screenHeight = displaySize(ctx).second,
        )
    }

    /**
     * Codecs audio réellement restituables (décodage logiciel + PASSTHROUGH/bitstream
     * vers l'ampli via HDMI/eARC) : DTS, DTS-HD, TrueHD, Dolby Atmos (EAC3 JOC)…
     * Le serveur s'en sert pour transmettre l'audio TEL QUEL quand c'est possible.
     */
    @UnstableApi
    private fun audioCapabilities(ctx: Context): Pair<List<String>, Int> {
        val codecs = linkedSetOf("aac", "mp3", "ac3", "eac3")   // décodés logiciellement
        var maxCh = 6
        try {
            val caps = AudioCapabilities.getCapabilities(ctx)
            fun pt(enc: Int, name: String) { if (caps.supportsEncoding(enc)) codecs.add(name) }
            pt(C.ENCODING_AC3, "ac3")
            pt(C.ENCODING_E_AC3, "eac3")
            pt(C.ENCODING_E_AC3_JOC, "eac3")            // Dolby Atmos (EAC3+JOC)
            pt(C.ENCODING_DTS, "dts")
            pt(C.ENCODING_DTS_HD, "dtshd")
            pt(C.ENCODING_DOLBY_TRUEHD, "truehd")       // Dolby TrueHD / Atmos
            maxCh = caps.maxChannelCount.coerceAtLeast(6)
        } catch (_: Throwable) {
        }
        return codecs.toList() to maxCh
    }

    private data class HevcInfo(val supported: Boolean, val main10: Boolean, val maxHeight: Int)

    /** Inspecte tous les décodeurs HEVC et agrège leurs capacités. */
    private fun hevcInfo(): HevcInfo {
        var supported = false
        var main10 = false
        var maxHeight = 0
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (info in list.codecInfos) {
            if (info.isEncoder) continue
            val type = info.supportedTypes.firstOrNull {
                it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true)
            } ?: continue
            supported = true
            val caps = runCatching { info.getCapabilitiesForType(type) }.getOrNull() ?: continue
            if (caps.profileLevels.any {
                    it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 ||
                        it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 ||
                        it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus
                }) main10 = true
            caps.videoCapabilities?.supportedHeights?.upper?.let { if (it > maxHeight) maxHeight = it }
        }
        if (maxHeight == 0) maxHeight = if (supported) 2160 else 1080
        return HevcInfo(supported, main10, maxHeight)
    }

    private fun supportsCodec(mime: String): Boolean {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        return list.codecInfos.any { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }
    }

    private fun supportsHdr10(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val display = (ctx.getSystemService(Context.DISPLAY_SERVICE)
            as android.hardware.display.DisplayManager)
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
